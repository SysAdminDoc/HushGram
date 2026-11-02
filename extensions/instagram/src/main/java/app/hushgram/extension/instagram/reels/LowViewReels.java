/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide reels with few views" patch (#444).
 *
 * <p>It filters the same pages as {@link CaptionWordReels}: each page the Reels viewer's network
 * callback takes, already turned into clips items, with the viewer's module name. Only the Reels
 * tab's pages are filtered, so a reel opened from search, a profile, Saved or a message still plays.
 *
 * <p>A reel goes when its post carries a view count under the minimum the user typed and its author
 * isn't someone they follow. A reel with no count stays, and so does every reel from an account the
 * user follows. A page where every reel would go keeps every item, so the viewer always has a reel
 * to show, and the report counts it. Off, paused, before the settings are read, or when anything
 * throws, the page comes back as it came, the same list.
 */
public final class LowViewReels {
    /** The diagnostic counter route: the pages seen, and the items taken out of them. */
    static final String ROUTE = "Low view reels";

    /** The reason, and kind, of an item whose view count is under the minimum. */
    static final String BELOW = "below the minimum";

    /** The kind counted for a page left whole because every reel in it matched. */
    static final String PAGE_KEPT = "page kept whole";

    /** The count of reels taken out, in the diagnostics report. */
    static final String REMOVED = "reels hidden for few views";

    /** The view count of a post that has none. */
    static final long NO_COUNT = -1;

    /** Reads what decides an item; the patch-filled stubs outside tests. */
    interface Reader {
        /** The item's post, or null for anything that isn't a Reels item carrying one. */
        Object media(Object item);

        /** The post's view count, or {@link #NO_COUNT} when it carries none. */
        long views(Object media);

        /** Whether the post's author is someone the user follows. */
        boolean followed(Object media);
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return LowViewReels.media(item);
        }

        @Override
        public long views(Object media) {
            Integer plays = playCount(media);
            if (plays == null) plays = igPlayCount(media);
            return plays == null ? NO_COUNT : plays.longValue();
        }

        @Override
        public boolean followed(Object media) {
            return Boolean.TRUE.equals(LowViewReels.followed(media));
        }
    };

    private LowViewReels() {
    }

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module
     * name. Answers a new list without the reels under the minimum while the switch is on and the
     * page is for the Reels tab, and [items] itself when the viewer is any other, when nothing goes,
     * when every reel would, when the switch is off or paused, or when anything goes wrong. Never
     * throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, PATCHED, LowViewReels::minimumWhenOn);
    }

    static List<?> page(List<?> items, String module, Reader reader, IntSupplier minimum) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.LOW_VIEW_REELS);
            if (!PaidPartnershipReels.pickedByInstagram(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0) return items;
            int least = minimum.getAsInt();
            if (least <= 0) return items;

            boolean[] goes = new boolean[size];
            int matched = 0;
            int reels = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                Object media = item == null ? null : reader.media(item);
                if (media != null) reels++;
                boolean hit = false;
                if (media != null) {
                    long views = reader.views(media);
                    hit = views != NO_COUNT && views < least && !reader.followed(media);
                }
                if (hit) {
                    matched++;
                    FeedFilterCounters.sawKind(ROUTE, BELOW);
                }
                goes[at++] = hit;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size) return items;
            if (matched == 0) return items;
            if (matched == reels) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "Low view reels: kept a page of " + size + " where every reel matched");
                return items;
            }

            List<Object> kept = new ArrayList<>(size - matched);
            at = 0;
            for (Object item : items) {
                if (!goes[at++]) kept.add(item);
            }
            FeedFilterCounters.removed(ROUTE, matched, BELOW);
            for (int count = 0; count < matched; count++) HookStatus.counted(FamilyNames.LOW_VIEW_REELS, REMOVED);
            int left = kept.size();
            int removed = matched;
            Logger.printDebug(() -> "Low view reels: took " + removed + " of " + size + " reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOW_VIEW_REELS, "reels page", failure);
            return items;
        }
    }

    /** The minimum view count while the switch is on, and 0 (nothing goes) while off, paused or before the settings are read. */
    static int minimumWhenOn() {
        if (!Utils.settingsReady() || !Settings.HIDE_LOW_VIEW_REELS.get()) return 0;
        return Settings.MIN_REEL_VIEWS.get();
    }

    /**
     * The post a Reels item carries, or null for anything else. The patch writes the body, which
     * checks the item is Instagram's clips item and reads its one post field. Null as built.
     */
    public static Object media(Object item) {
        return null;
    }

    /** A post's play count, which the patch fills in. Null as built, and for a post with none. */
    public static Integer playCount(Object media) {
        return null;
    }

    /** A post's Instagram-side play count, the one the patch falls back on. Null as built. */
    public static Integer igPlayCount(Object media) {
        return null;
    }

    /**
     * Whether the user follows the post's author, read through the author's friendship status.
     * Null as built, and when the post has no author or status.
     */
    public static Boolean followed(Object media) {
        return null;
    }
}
