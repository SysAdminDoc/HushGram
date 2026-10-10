/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Keep in chat" patch.
 *
 * <p>A photo or video message carries a view mode: "once" (view once), "replayable" (allow replay)
 * or "permanent" (Keep in chat). Instagram reads it to decide whether the message shows in the chat
 * or as a tap-to-view bubble that's gone after you've looked. The patch hands the view mode to
 * {@link #viewMode} as each message's media is read, so while the switch is on the first two read
 * as Keep in chat.
 *
 * <p>That has to leave the ones you sent alone. A view once photo you sent has no picture on your
 * side, so as "permanent" Instagram draws an empty bubble where its own sent bubble belongs (#114).
 * The media is read before the message says who sent it (the order of the keys isn't fixed), so
 * {@link #viewMode} remembers the mode it replaced, and {@link #messageRead} puts it back once the
 * message turns out to be yours. Instagram itself tells a message you sent by its user_id matching
 * the signed-in account and never reads is_sent_by_viewer to draw a chat, and the copy of a view
 * once photo you'd just sent didn't have that flag set, so the flag alone left it kept. The flag
 * still counts when it's there. A message whose sender can't be told is treated as one you
 * received and stays kept in the chat.
 *
 * <p>Some readers parse without an account. One of those goes by the account the other readers
 * have had, but only while they've all had the same one. With two accounts signed in it could be
 * reading for either, and a view once photo one of them sent the other could pass for one you sent
 * on the other's side, so opening it there would use it up. From the second account on, a reader
 * without one can't tell, and the photo stays kept as one you received.
 *
 * <p>Instagram saves its messages to a cache with the view mode they hold, and reads them back the
 * next time the chat opens. {@link #storedViewMode} hands the cache the mode the server sent for a
 * media this class rewrote, so the cache keeps Instagram's own mode and the next read decides again.
 * Without it a "permanent" written there could never be told apart from a real one.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram gets the view mode the server sent.
 */
public final class KeepInChat {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";
    /** The step a failed read of a message's sender is reported under. */
    static final String SENDER = "sent by you";
    /** The step a failed cache write is reported under. */
    static final String SAVE = "cache write";

    /** Counted for each photo or video you sent that got its own view mode back. */
    static final String GAVE_BACK = "gave a photo or video you sent its own bubble";
    /**
     * Counted when a message's sender was read but not the account it was read for: its reader had
     * none, and readers haven't had exactly one account to go by.
     */
    static final String NO_VIEWER = "couldn't tell who's signed in";
    /** Counted each time a kept photo or video went to the cache with the view mode it came with. */
    static final String SAVED = "saved a kept photo or video with its own view mode";

    static final String ONCE = "once";
    static final String REPLAYABLE = "replayable";
    static final String PERMANENT = "permanent";

    /**
     * Each media this class rewrote to "permanent" and the mode it came with, until Instagram drops
     * the media or it turns out to be one you sent. Weak keys, so the cache can still ask for the
     * mode however long Instagram keeps the message. The patch refuses a media class with its own
     * equals or hashCode, so this is a lookup by the object itself.
     */
    private static final Map<Object, String> rewritten = Collections.synchronizedMap(new WeakHashMap<>());

    /** Guards {@link #firstViewer} and {@link #severalViewers}, which change together. */
    private static final Object VIEWERS = new Object();
    /** The id of the first account a message's reader had in this process, or null before one. */
    private static String firstViewer;
    /** Whether a message's reader has had an account other than {@link #firstViewer}. */
    private static boolean severalViewers;

    private static volatile boolean logged;

    private KeepInChat() {
    }

    /**
     * Called with the view mode of each photo or video message as Instagram reads it, and the media
     * object that's about to hold it. Returns "permanent" for view once and replayable media while
     * the switch is on, and the mode it was given otherwise. Never throws.
     */
    public static String viewMode(String mode, Object media) {
        return viewMode(mode, media, KeepInChat::switchedOn);
    }

    static String viewMode(String mode, Object media, BooleanSupplier on) {
        if (!ONCE.equals(mode) && !REPLAYABLE.equals(mode)) {
            return mode;
        }
        try {
            HookStatus.invoked(FamilyNames.KEEP_IN_CHAT);
            if (!on.getAsBoolean()) {
                return mode;
            }
            if (media != null) {
                rewritten.put(media, mode);
            }
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Keep in chat: kept a " + mode + " photo or video in the chat");
            }
            return PERMANENT;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_IN_CHAT, SWITCH, t);
            return mode;
        }
    }

    /**
     * Called with a message and the reader parsing it each time one of its photo or video media,
     * its sent-by-you flag or its sender has been read. Once the media and either of the other two
     * are in, the media of a message you sent gets the view mode it came with back. Never throws.
     */
    public static void messageRead(Object message, Object reader) {
        messageRead(message, reader, MESSAGE_STUBS);
    }

    static void messageRead(Object message, Object reader, Message stubs) {
        try {
            if (message == null || rewritten.isEmpty()) {
                return;
            }
            Object visual = stubs.visualMedia(message);
            Object item = stubs.itemMedia(message);
            if (!isRewritten(visual) && !isRewritten(item)) {
                return;
            }
            if (!isYours(message, reader, stubs)) {
                return;
            }
            restore(visual, stubs);
            restore(item, stubs);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_IN_CHAT, SENDER, t);
        }
    }

    /**
     * Whether [message] is one the signed-in account sent: its own flag says so, or its sender is
     * the account [reader] is reading for (or, when the reader has no account, the one account
     * readers have had, if they've only had one).
     */
    private static boolean isYours(Object message, Object reader, Message stubs) {
        if (stubs.sentByYou(message)) {
            return true;
        }
        String sender = stubs.senderId(message);
        if (sender == null || sender.isEmpty()) {
            return false;
        }
        String viewer = stubs.viewerId(reader);
        if (viewer != null && !viewer.isEmpty()) {
            sawViewer(viewer);
        } else {
            viewer = onlyViewer();
        }
        if (viewer == null) {
            HookStatus.counted(FamilyNames.KEEP_IN_CHAT, NO_VIEWER);
            return false;
        }
        return sender.equals(viewer);
    }

    /** Notes [viewer], the account a message's reader had. */
    private static void sawViewer(String viewer) {
        synchronized (VIEWERS) {
            if (firstViewer == null) {
                firstViewer = viewer;
            } else if (!firstViewer.equals(viewer)) {
                severalViewers = true;
            }
        }
    }

    /** The one account readers have had in this process, or null with none or more than one. */
    private static String onlyViewer() {
        synchronized (VIEWERS) {
            return severalViewers ? null : firstViewer;
        }
    }

    /**
     * Called with the view mode a photo or video message's media holds as Instagram writes it to
     * its cache, and the media. Returns the mode the server sent for one this class kept in the
     * chat, and the mode it was given otherwise. Never throws.
     */
    public static String storedViewMode(String mode, Object media) {
        try {
            if (media == null || !PERMANENT.equals(mode)) {
                return mode;
            }
            String original = rewritten.get(media);
            if (original == null) {
                return mode;
            }
            HookStatus.counted(FamilyNames.KEEP_IN_CHAT, SAVED);
            return original;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_IN_CHAT, SAVE, t);
            return mode;
        }
    }

    private static boolean isRewritten(Object media) {
        return media != null && rewritten.containsKey(media);
    }

    private static void restore(Object media, Message stubs) {
        if (media == null) {
            return;
        }
        String mode = rewritten.remove(media);
        if (mode != null) {
            stubs.setViewMode(media, mode);
            HookStatus.counted(FamilyNames.KEEP_IN_CHAT, GAVE_BACK);
        }
    }

    static void clearRemembered() {
        rewritten.clear();
        synchronized (VIEWERS) {
            firstViewer = null;
            severalViewers = false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_IN_CHAT.get();
    }

    /** Filled in by the patch: whether [message], one of Instagram's direct messages, says it was sent by the signed-in account. */
    public static boolean sentByYou(Object message) {
        return false;
    }

    /** Filled in by the patch: the user id of [message]'s sender (its user_id), or null. */
    public static String senderId(Object message) {
        return null;
    }

    /** Filled in by the patch: the user id of the account [reader], the reader parsing a message, reads for, or null. */
    public static String viewerId(Object reader) {
        return null;
    }

    /** Filled in by the patch: the photo or video media in [message]'s visual_media, or null. */
    public static Object visualMedia(Object message) {
        return null;
    }

    /** Filled in by the patch: the photo or video media in [message]'s message_item_dict, or null. */
    public static Object itemMedia(Object message) {
        return null;
    }

    /** Filled in by the patch: sets the view mode of [media], a photo or video media. */
    public static void setViewMode(Object media, String mode) {
    }

    /** What this class reads from and writes to a message. {@link #MESSAGE_STUBS} is the patch's; tests stand in. */
    interface Message {
        boolean sentByYou(Object message);

        String senderId(Object message);

        String viewerId(Object reader);

        Object visualMedia(Object message);

        Object itemMedia(Object message);

        void setViewMode(Object media, String mode);
    }

    private static final Message MESSAGE_STUBS = new Message() {
        @Override
        public boolean sentByYou(Object message) {
            return KeepInChat.sentByYou(message);
        }

        @Override
        public String senderId(Object message) {
            return KeepInChat.senderId(message);
        }

        @Override
        public String viewerId(Object reader) {
            return KeepInChat.viewerId(reader);
        }

        @Override
        public Object visualMedia(Object message) {
            return KeepInChat.visualMedia(message);
        }

        @Override
        public Object itemMedia(Object message) {
            return KeepInChat.itemMedia(message);
        }

        @Override
        public void setViewMode(Object media, String mode) {
            KeepInChat.setViewMode(media, mode);
        }
    };
}
