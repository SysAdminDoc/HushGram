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
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
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
    }

    private static final class Live implements Messages {
        private final Object session;
        private final Object factory;

        Live(Object viewer) {
            session = session(viewer);
            factory = factory(viewer);
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

        @Override @Nullable public Object plainPage(Object message) {
            return plainItem(factory, message);
        }

        @Override @Nullable public Object sharedPage(Object message) {
            Object media = legacyMedia(message);
            if (media == null) {
                Object attachment = attachment(message);
                if (attachment == null || previewId(attachment) <= 0L || preview(attachment) == null) return null;
                media = previewMedia(session, attachment);
            }
            return media == null ? null : sharedItem(factory, message, media);
        }

        @Override @Nullable public String id(Object page) {
            return itemId(page);
        }
    }

    /** Read in place of a viewer's own chat, since a test has no viewer. */
    @Nullable static Messages chatForTests;

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
            if (viewer == null || key == null || tapped == null || tapped.size() != 1 || index != 0) return tapped;
            if (!Utils.settingsReady() || !Settings.SWIPE_CHAT_MEDIA.get()) return tapped;
            Object page = tapped.get(0);
            if (page == null) return tapped;
            Messages chat = chatForTests != null ? chatForTests : new Live(viewer);
            List<Object> pages = pages(chat, key, page);
            if (pages == null) return tapped;
            opening = true;
            return pages;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_MEDIA_WALK, "open", failure);
            return tapped;
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
        if (thread == null) return null;
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
        if (slot < 0 || pages.size() < 2) return null;
        start = slot;
        return pages;
    }

    private static boolean isPicture(Messages chat, Object message) {
        String kind = chat.kind(message);
        return (PLAIN.equals(kind) || SHARE.equals(kind) || XMA_SHARE.equals(kind)) && !chat.expiring(message);
    }

    /** One message that can't be read loses its page, not the walk. */
    @Nullable
    private static Object page(Messages chat, Object message) {
        try {
            return PLAIN.equals(chat.kind(message)) ? chat.plainPage(message) : chat.sharedPage(message);
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

    /** Rewritten by the patch: the id of the media on a page, or null. */
    @Nullable public static String itemId(Object item) {
        return null;
    }

    /** Rewritten by the patch: sets the pager's direction by its name. */
    public static void scroll(Object pager, String direction) {
    }
}
