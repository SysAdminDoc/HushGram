/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide lives in Reels" patch (#566).
 *
 * <p>Instagram slips previews of live broadcasts in among the reels it picks: a clips item whose
 * live preview part is set, which plays the broadcast in the reel's place with a Watch live button.
 * The Reels viewer takes each page it fetched in one network callback, with the viewer's module
 * name, and the patch hands that page and that name to {@link #page} there, the same place the
 * other Reels filters read.
 *
 * <p>Only the Reels tab (see {@link PaidPartnershipReels#PICKED_BY_INSTAGRAM}) and the viewers
 * Explore opens when a tile is tapped and the user keeps swiping ("clips_viewer_explore" and the
 * unit it came from) are filtered. A viewer opened on something the user chose keeps its pages as
 * they came.
 *
 * <p>A page where every item is a live keeps every item, so the viewer always has something to
 * show, and the report counts it. Off, paused, before the settings are read, or when anything
 * throws, the page comes back as it came, the same list.
 */
public final class LiveReels {
    /** The diagnostic counter route: the pages seen, and the lives taken out of them. */
    static final String ROUTE = "Lives in Reels";

    /** The kind counted for a live taken out. */
    static final String LIVE = "live";

    /** The kind counted for a page left whole because every item in it was a live. */
    static final String PAGE_KEPT = "page kept whole";

    /** Every viewer Explore opens on a tile starts its module name with this. */
    static final String EXPLORE = "clips_viewer_explore";

    private LiveReels() {
    }

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module
     * name. Answers a new list without the live previews while the switch is on and the viewer is
     * the Reels tab or one Explore opened, and [items] itself otherwise, when nothing goes, when
     * every item would, or when anything goes wrong. Never throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, LiveReels::livePreview, LiveReels::switchedOn);
    }

    static List<?> page(List<?> items, String module, Function<Object, Object> preview, BooleanSupplier on) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.LIVE_REELS);
            if (!filtered(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0 || !on.getAsBoolean()) return items;

            boolean[] live = new boolean[size];
            int lives = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                boolean isLive = item != null && preview.apply(item) != null;
                if (isLive) lives++;
                live[at++] = isLive;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size || lives == 0) return items;
            if (lives == size) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "Lives in Reels: kept a page of " + size + " lives");
                return items;
            }

            List<Object> kept = new ArrayList<>(size - lives);
            at = 0;
            for (Object item : items) {
                if (!live[at++]) kept.add(item);
            }
            FeedFilterCounters.removed(ROUTE, lives, LIVE);
            int taken = lives;
            Logger.printDebug(() -> "Lives in Reels: took " + taken + " of " + size + " items out");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIVE_REELS, "reels page", failure);
            return items;
        }
    }

    /** Whether a viewer's module name is the Reels tab's or one Explore opened, where Instagram picks what comes next. */
    static boolean filtered(String module) {
        return module != null && (PaidPartnershipReels.pickedByInstagram(module) || module.startsWith(EXPLORE));
    }

    /** Whether the switch is on, read only once the settings are. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_LIVE_REELS.get();
    }

    /**
     * A Reels item's live preview part, or null for anything else, a reel included. The patch writes
     * the body, which checks the item is Instagram's clips item, asks it for that part by the key
     * Instagram's own live preview code uses, and keeps the part only when it passes that code's own
     * check for a broadcast. Every reel carries a part, an empty one for a plain reel, so the lookup
     * alone would call every reel a live. Null as built.
     */
    public static Object livePreview(Object item) {
        return null;
    }
}
