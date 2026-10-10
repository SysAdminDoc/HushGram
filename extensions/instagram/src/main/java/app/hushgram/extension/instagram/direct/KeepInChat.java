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
 * <p>That has to leave the ones you sent alone. A view once photo you sent has no media on your
 * side, so as "permanent" Instagram draws an empty bubble where its own sent bubble belongs. The
 * media is read before the message says whether you sent it (the order of the keys isn't fixed),
 * so {@link #viewMode} remembers the mode it replaced, and {@link #messageRead} puts it back once
 * the message says it was sent by you. A message that never says (no flag) is treated as one you
 * received and stays kept in the chat.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram gets the view mode the server sent.
 */
public final class KeepInChat {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";
    /** The step a failed read of a message's sender is reported under. */
    static final String SENDER = "sent by you";

    static final String ONCE = "once";
    static final String REPLAYABLE = "replayable";
    static final String PERMANENT = "permanent";

    /** How many rewritten media are remembered. A message holds one or two, and is settled as soon as it's read. */
    private static final int REMEMBERED = 32;

    private static final Object[] rememberedMedia = new Object[REMEMBERED];
    private static final String[] rememberedMode = new String[REMEMBERED];
    private static int nextSlot;

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
            remember(media, mode);
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
     * Called with a message each time one of its photo or video media or its sent-by-you flag has
     * been read. Once both are in, the media of a message you sent gets the view mode it came with
     * back. Never throws.
     */
    public static void messageRead(Object message) {
        messageRead(message, MESSAGE_STUBS);
    }

    static void messageRead(Object message, Message reader) {
        try {
            if (message == null || !anythingRemembered() || !reader.sentByYou(message)) {
                return;
            }
            restore(reader.visualMedia(message), reader);
            restore(reader.itemMedia(message), reader);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_IN_CHAT, SENDER, t);
        }
    }

    private static void restore(Object media, Message reader) {
        String mode = forget(media);
        if (mode != null) {
            reader.setViewMode(media, mode);
        }
    }

    private static synchronized void remember(Object media, String mode) {
        if (media == null) {
            return;
        }
        for (int i = 0; i < REMEMBERED; i++) {
            if (rememberedMedia[i] == media) {
                rememberedMode[i] = mode;
                return;
            }
        }
        rememberedMedia[nextSlot] = media;
        rememberedMode[nextSlot] = mode;
        nextSlot = (nextSlot + 1) % REMEMBERED;
    }

    private static synchronized String forget(Object media) {
        if (media == null) {
            return null;
        }
        for (int i = 0; i < REMEMBERED; i++) {
            if (rememberedMedia[i] == media) {
                String mode = rememberedMode[i];
                rememberedMedia[i] = null;
                rememberedMode[i] = null;
                return mode;
            }
        }
        return null;
    }

    private static synchronized boolean anythingRemembered() {
        for (int i = 0; i < REMEMBERED; i++) {
            if (rememberedMedia[i] != null) {
                return true;
            }
        }
        return false;
    }

    static synchronized void clearRemembered() {
        for (int i = 0; i < REMEMBERED; i++) {
            rememberedMedia[i] = null;
            rememberedMode[i] = null;
        }
        nextSlot = 0;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_IN_CHAT.get();
    }

    /** Filled in by the patch: whether [message], one of Instagram's direct messages, was sent by the signed-in account. */
    public static boolean sentByYou(Object message) {
        return false;
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
