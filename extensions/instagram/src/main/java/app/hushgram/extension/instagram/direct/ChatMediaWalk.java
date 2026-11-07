/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Swipe through a chat's photos and posts (#350). A tap on a photo or video in a chat hands Instagram's
 * media viewer one page. On this switch the viewer gets a page for each photo, video and shared post
 * the chat has loaded, in chat order, with the tapped one's own page in its slot, and its pager turns
 * to page up and down. Back and the rest stay the viewer's own.
 *
 * <p>The viewer already pages through an album it's handed, sideways, and its pager honours a vertical
 * direction in its layout, touch and fling. What a walk gives up is the viewer's own paging of a
 * chat's media past a plain tap, which replaces the list it holds, so a walk opens in the album mode.
 * Only a message kind on the lists below gets a page. View once and disappearing media never does, and
 * a tap on either opens as Instagram has it, as does any tap the walk can't find in the chat.
 *
 * <p>A tap on a shared post goes to a different viewer, so {@link #tap} opens this viewer on the post
 * alone the way a tap on a photo does, and the walk goes on from there. A shared reel stays Instagram's
 * while Swipe through a chat's reels is on, and so does an album, which this viewer shows only the
 * cover of.
 *
 * <p>Off, paused or on any failure, the viewer opens with what Instagram gave it and its pager goes on
 * paging sideways. No hook here throws.
 */
public final class ChatMediaWalk {
    private ChatMediaWalk() {
    }

    /** Sent straight to the chat. Instagram's own page builder reads it from the message. */
    private static final String PLAIN = "MEDIA";

    /** A post shared to the chat, old and current kind. The page gets the shared post's media. */
    private static final String SHARE = "MEDIA_SHARE";
    private static final String XMA_SHARE = "XMA_MEDIA_SHARE";

    /** The names of Instagram's pager directions, an enum whose names survive its build. */
    private static final String VERTICAL = "VERTICAL";
    private static final String HORIZONTAL = "HORIZONTAL";

    /** Instagram's media types a shared post's tap leaves alone: a video, while chat reels are on, and an album. */
    static final int VIDEO = 2;
    static final int ALBUM = 8;

    /** How a walk reads a chat. {@link Live} asks the bodies the patch wrote. */
    interface Messages {
        @Nullable List<?> thread(Object key);

        @Nullable String kind(Object message);

        long time(Object message);

        boolean expiring(Object message);

        /** The viewer's page for a message of the kind {@link #PLAIN}, or null. */
        @Nullable Object plainPage(Object message);

        /** The viewer's page for a shared post, or null when it has nothing to show. */
        @Nullable Object sharedPage(Object message);

        /** The id of the media on a page, or null. */
        @Nullable String id(Object page);

        /** Instagram's media type of the media on a page, 0 when it has none. */
        int type(Object page);
    }

    private static final class Live implements Messages {
        private final Object session;
        private final Object factory;

        Live(Object viewer) {
            this(session(viewer), factory(viewer));
        }

        Live(Object session, Object factory) {
            this.session = session;
            this.factory = factory;
        }

        @Override @Nullable public List<?> thread(Object key) {
            return messages(session, key);
        }

        @Override @Nullable public String kind(Object message) {
            return ChatMediaWalk.kind(message);
        }

        @Override public long time(Object message) {
            return ChatMediaWalk.time(message);
        }

        @Override public boolean expiring(Object message) {
            return ChatMediaWalk.expiring(message);
        }

        /**
         * Instagram's reader finds a plain photo or video, but the page it makes is a small round one with
         * no header, so the media it found goes the way a shared post's does. A photo still sending has
         * no media to hand on and keeps the reader's page.
         */
        @Override @Nullable public Object plainPage(Object message) {
            Object read = plainItem(factory, message);
            if (read == null) {
                Logger.printDebug(() -> "Chat media walk: Instagram's reader found no plain photo or video");
                return null;
            }
            Object media = itemMedia(read);
            Object page = media == null ? null : sharedItem(factory, message, media);
            return page != null ? page : read;
        }

        @Override @Nullable public Object sharedPage(Object message) {
            Object media = legacyMedia(message);
            if (media == null) {
                Object attachment = attachment(message);
                if (attachment == null || previewId(attachment) <= 0L || preview(attachment) == null) {
                    Logger.printDebug(() -> "Chat media walk: a shared post with no preview, attachment " + (attachment != null));
                    return null;
                }
                media = previewMedia(session, attachment);
            }
            if (media == null) {
                Logger.printDebug(() -> "Chat media walk: no media made from a shared post's attachment");
                return null;
            }
            Object page = sharedItem(factory, message, media);
            if (page == null) Logger.printDebug(() -> "Chat media walk: no page for a shared post's media");
            return page;
        }

        @Override @Nullable public String id(Object page) {
            return itemId(page);
        }

        @Override public int type(Object page) {
            Object media = itemMedia(page);
            return media == null ? 0 : mediaType(media);
        }
    }

    /** How a tap on a shared post reads its chat and opens the viewer. {@link LiveTaps} asks the bodies the patch wrote. */
    interface Taps {
        @Nullable Object key(Object delegate);

        /** The message [identifier] names in the chat with [key], or null. */
        @Nullable Object message(Object delegate, Object key, Object identifier);

        @Nullable Messages chat(Object delegate, View view);

        /** Opens the viewer on [page] alone, as a tap on a photo does. */
        void show(Object delegate, Object rect, View view, Object page);
    }

    private static final class LiveTaps implements Taps {
        @Override @Nullable public Object key(Object delegate) {
            return tapKey(delegate);
        }

        @Override @Nullable public Object message(Object delegate, Object key, Object identifier) {
            Object session = tapSession(delegate);
            String id = messageId(identifier);
            return session == null || id == null ? null : lookup(session, key, id);
        }

        @Override @Nullable public Messages chat(Object delegate, View view) {
            Object session = tapSession(delegate);
            Object factory = session == null ? null : maker(view.getContext(), session);
            return factory == null ? null : new Live(session, factory);
        }

        @Override public void show(Object delegate, Object rect, View view, Object page) {
            showPage(delegate, rect, view, page);
        }
    }

    private static final Taps LIVE_TAPS = new LiveTaps();

    /** Read in place of a viewer's own chat, since a test has no viewer. */
    @Nullable static Messages chatForTests;

    /** Used in place of a chat's tap delegate, since a test has none. */
    @Nullable static Taps tapsForTests;

    /** Whether a shared post's tap is opening the viewer now, and whether the viewer's opener came to {@link #open}. */
    private static boolean tapping;
    private static boolean reached;

    /** The pages a shared post's tap read, for the {@link #open} its viewer makes. */
    @Nullable private static List<Object> ready;

    /** Whether the viewer opening now is a walk, from {@link #open} to the {@link #pager} that follows it. */
    private static boolean opening;

    /** Where the walk opening now starts: the tapped page's slot. */
    private static int start;

    /** The pager of the viewer on a walk. */
    @Nullable private static WeakReference<View> walking;

    /** The pagers turned upright. A later tap can open sideways in the same one. */
    private static final Map<Object, Boolean> turned = new WeakHashMap<>();

    /**
     * What the viewer opens with: the chat's pages when a single page was tapped in a chat and there's
     * more than one to walk, otherwise [tapped] itself, which tells the caller nothing changed.
     */
    @Nullable
    public static List<?> open(@Nullable Object viewer, @Nullable Object key, @Nullable List<?> tapped, int index) {
        opening = false;
        try {
            HookStatus.invoked(FamilyNames.CHAT_MEDIA_WALK);
            if (tapping) reached = true;
            if (viewer == null || key == null || tapped == null || tapped.size() != 1 || index != 0) {
                Logger.printDebug(() -> "Chat media walk: left alone, " + (tapped == null ? "no" : tapped.size())
                        + " pages tapped at " + index + ", chat known " + (key != null));
                return tapped;
            }
            if (!Utils.settingsReady() || !Settings.SWIPE_CHAT_MEDIA.get()) return tapped;
            Object page = tapped.get(0);
            if (page == null) return tapped;
            Messages chat = chatForTests != null ? chatForTests : new Live(viewer);
            List<Object> pages = readyFor(chat, page);
            if (pages == null) pages = pages(chat, key, page);
            if (pages == null) return tapped;
            opening = true;
            return pages;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_MEDIA_WALK, "open", failure);
            return tapped;
        }
    }

    /** The pages a shared post's tap read a moment ago when [tapped] is the post it read them for, else null. */
    @Nullable
    private static List<Object> readyFor(Messages chat, Object tapped) {
        List<Object> pages = ready;
        ready = null;
        if (pages == null || start < 0 || start >= pages.size()) return null;
        String id = chat.id(tapped);
        if (id == null || !id.equals(chat.id(pages.get(start)))) return null;
        pages.set(start, tapped);
        return pages;
    }

    /**
     * A tap on a post shared to a chat, before Instagram opens it. Non-zero when it opened this viewer on
     * the chat's walk at that post, so the tap ends there. Zero leaves the tap to Instagram: off, paused,
     * a post the walk can't find in the chat, a disappearing one, an album, a reel while Swipe through a
     * chat's reels is on, and any failure before the viewer opened.
     */
    public static int tap(@Nullable Object delegate, @Nullable Object rect, @Nullable View view, @Nullable Object identifier) {
        reached = false;
        try {
            HookStatus.invoked(FamilyNames.CHAT_MEDIA_WALK);
            if (delegate == null || rect == null || view == null || identifier == null) return 0;
            if (!Utils.settingsReady() || !Settings.SWIPE_CHAT_MEDIA.get()) return 0;
            Taps taps = tapsForTests != null ? tapsForTests : LIVE_TAPS;
            Object key = taps.key(delegate);
            Object message = key == null ? null : taps.message(delegate, key, identifier);
            Messages chat = message == null ? null : chatForTests != null ? chatForTests : taps.chat(delegate, view);
            if (chat == null) {
                Logger.printDebug(() -> "Chat media walk: a shared post's tap left alone, chat known " + (key != null)
                        + ", message found " + (message != null));
                return 0;
            }
            String kind = chat.kind(message);
            if (!(SHARE.equals(kind) || XMA_SHARE.equals(kind)) || chat.expiring(message)) {
                Logger.printDebug(() -> "Chat media walk: a tap on a " + kind + " message left alone");
                return 0;
            }
            Object page = chat.sharedPage(message);
            int type = page == null ? 0 : chat.type(page);
            if (page == null || type == ALBUM || (type == VIDEO && Settings.SWIPE_CHAT_REELS.get())) {
                Logger.printDebug(() -> "Chat media walk: a shared post's tap left alone, page " + (page != null) + ", type " + type);
                return 0;
            }
            List<Object> pages = pages(chat, key, page);
            if (pages == null) return 0;
            ready = pages;
            tapping = true;
            taps.show(delegate, rect, view, page);
            return reached ? 1 : 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_MEDIA_WALK, "tap", failure);
            return reached ? 1 : 0;
        } finally {
            tapping = false;
            ready = null;
        }
    }

    /** The slot of the tapped page in what {@link #open} last answered. */
    public static int startIndex() {
        return start;
    }

    /**
     * The chat's pages in chat order with [tapped] in its own slot, or null when the tapped page isn't
     * among them or it's the only one.
     */
    @Nullable
    static List<Object> pages(Messages chat, Object key, Object tapped) {
        String tappedId = chat.id(tapped);
        List<?> thread = tappedId == null ? null : chat.thread(key);
        if (thread == null) {
            Logger.printDebug(() -> "Chat media walk: no walk, tapped page has an id " + (tappedId != null) + ", chat not read");
            return null;
        }
        List<Object> pictures = new ArrayList<>();
        for (Object message : thread) {
            if (message != null && isPicture(chat, message)) pictures.add(message);
        }
        Collections.sort(pictures, (one, other) -> Long.compare(chat.time(one), chat.time(other)));
        List<Object> pages = new ArrayList<>(pictures.size());
        int slot = -1;
        for (Object message : pictures) {
            Object page = page(chat, message);
            if (page == null) continue;
            if (slot < 0 && tappedId.equals(chat.id(page))) {
                slot = pages.size();
                page = tapped;
            }
            pages.add(page);
        }
        int found = slot;
        Logger.printDebug(() -> "Chat media walk: " + thread.size() + " messages, " + kinds(chat, thread) + ", "
                + pictures.size() + " with pictures, " + pages.size() + " pages, tapped slot " + found);
        if (slot < 0 || pages.size() < 2) return null;
        start = slot;
        return pages;
    }

    /** How many of each kind of message a chat has, for the log. A kind's name says nothing about what's in it. */
    private static Map<String, Integer> kinds(Messages chat, List<?> thread) {
        Map<String, Integer> kinds = new TreeMap<>();
        for (Object message : thread) {
            String kind = message == null ? null : chat.kind(message);
            String name = kind == null ? "unknown" : kind;
            Integer seen = kinds.get(name);
            kinds.put(name, seen == null ? 1 : seen + 1);
        }
        return kinds;
    }

    private static boolean isPicture(Messages chat, Object message) {
        String kind = chat.kind(message);
        return (PLAIN.equals(kind) || SHARE.equals(kind) || XMA_SHARE.equals(kind)) && !chat.expiring(message);
    }

    /**
     * One message that can't be read loses its page, not the walk. Instagram's builder only finds a plain
     * photo or video in one of the fields it can arrive in, so one it can't find goes the way a shared
     * post does, which reads the media the message holds.
     */
    @Nullable
    private static Object page(Messages chat, Object message) {
        try {
            if (!PLAIN.equals(chat.kind(message))) return chat.sharedPage(message);
            Object page = chat.plainPage(message);
            return page != null ? page : chat.sharedPage(message);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_MEDIA_WALK, "page", failure);
            return null;
        }
    }

    /**
     * The viewer's pager, as the viewer readies it for what it's opening. Turns it upright for a walk
     * and back for anything else, and leaves a pager this class never turned alone.
     */
    public static void pager(@Nullable Object pager) {
        boolean walk = opening;
        opening = false;
        walking = null;
        try {
            HookStatus.invoked(FamilyNames.CHAT_MEDIA_WALK);
            if (pager == null) return;
            if (walk && !turned.containsKey(pager)) {
                turn(pager, VERTICAL);
                turned.put(pager, Boolean.TRUE);
            } else if (!walk && turned.containsKey(pager)) {
                turned.remove(pager);
                turn(pager, HORIZONTAL);
            }
            if (walk) forward(pager);
            if (walk && pager instanceof View) walking = new WeakReference<>((View) pager);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_MEDIA_WALK, "pager", failure);
        }
    }

    /**
     * The pager lays its pages out along one axis at a time and leaves the other alone, so the offset
     * pages kept from the last direction goes first.
     */
    private static void turn(Object pager, String direction) {
        scroll(pager, direction);
        if (!(pager instanceof ViewGroup)) return;
        ViewGroup pages = (ViewGroup) pager;
        for (int child = 0; child < pages.getChildCount(); child++) {
            if (VERTICAL.equals(direction)) pages.getChildAt(child).setTranslationX(0f);
            else pages.getChildAt(child).setTranslationY(0f);
        }
    }

    /**
     * Non-zero while a walk is on screen, when the viewer's drag to dismiss must leave an up or down
     * swipe to the pager. Zero otherwise, so Instagram decides.
     */
    public static int holdDismiss() {
        try {
            WeakReference<View> current = walking;
            View pager = current == null ? null : current.get();
            return pager != null && pager.isShown() && Settings.SWIPE_CHAT_MEDIA.get() ? 1 : 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_MEDIA_WALK, "hold dismiss", failure);
            return 0;
        }
    }

    /** Rewritten by the patch: the viewer's session. */
    @Nullable public static Object session(Object viewer) {
        return null;
    }

    /** Rewritten by the patch: Instagram's builder of the viewer's pages, for the viewer's activity and session. */
    @Nullable public static Object factory(Object viewer) {
        return null;
    }

    /** Rewritten by the patch: the messages the app holds for a chat. */
    @Nullable public static List<?> messages(Object session, Object key) {
        return null;
    }

    /** Rewritten by the patch: the name of a message's kind. */
    @Nullable public static String kind(Object message) {
        return null;
    }

    /** Rewritten by the patch: when a message was sent. */
    public static long time(Object message) {
        return 0L;
    }

    /** Rewritten by the patch: whether a message is set to disappear. */
    public static boolean expiring(Object message) {
        return false;
    }

    /** Rewritten by the patch: Instagram's own page for a message with media of its own. */
    @Nullable public static Object plainItem(Object factory, Object message) {
        return null;
    }

    /** Rewritten by the patch: the media on an old kind of shared post. */
    @Nullable public static Object legacyMedia(Object message) {
        return null;
    }

    /** Rewritten by the patch: what the current kind of shared post holds in place of media. */
    @Nullable public static Object attachment(Object message) {
        return null;
    }

    /** Rewritten by the patch: the id of the media an attachment previews, or 0. */
    public static long previewId(Object attachment) {
        return 0L;
    }

    /** Rewritten by the patch: the image an attachment previews, or null. */
    @Nullable public static Object preview(Object attachment) {
        return null;
    }

    /** Rewritten by the patch: the media Instagram makes of an attachment for its chat image prefetch. */
    @Nullable public static Object previewMedia(Object session, Object attachment) {
        return null;
    }

    /** Rewritten by the patch: a page for a message and media it doesn't read from the message itself. */
    @Nullable public static Object sharedItem(Object factory, Object message, Object media) {
        return null;
    }

    /** Rewritten by the patch: the media on a page, or null. */
    @Nullable public static Object itemMedia(Object item) {
        return null;
    }

    /** Rewritten by the patch: the id of the media on a page, or null. */
    @Nullable public static String itemId(Object item) {
        return null;
    }

    /** Rewritten by the patch: sets the pager's direction by its name. */
    public static void scroll(Object pager, String direction) {
    }

    /**
     * Rewritten by the patch: lays the pager's pages out first to last. The viewer's opener lays them out
     * last to first each time, where a swipe up can't leave the first page, and reads nothing else of it.
     */
    public static void forward(Object pager) {
    }

    /** Rewritten by the patch: Instagram's media type of [media], 0 when it has none. */
    public static int mediaType(Object media) {
        return 0;
    }

    /** Rewritten by the patch: the session of the chat whose tap delegate is [delegate]. */
    @Nullable public static Object tapSession(Object delegate) {
        return null;
    }

    /** Rewritten by the patch: the key of the chat whose tap delegate is [delegate]. */
    @Nullable public static Object tapKey(Object delegate) {
        return null;
    }

    /** Rewritten by the patch: Instagram's builder of the viewer's pages, for a context and a session. */
    @Nullable public static Object maker(Object context, Object session) {
        return null;
    }

    /** Rewritten by the patch: the message with [id] in the chat with [key], or null. */
    @Nullable public static Object lookup(Object session, Object key, String id) {
        return null;
    }

    /** Rewritten by the patch: the id of the message a tap names. */
    @Nullable public static String messageId(Object identifier) {
        return null;
    }

    /** Rewritten by the patch: opens the viewer from a chat's tap delegate on [page] alone, as a tap on a photo does. */
    public static void showPage(Object delegate, Object rect, View view, Object page) {
    }
}
