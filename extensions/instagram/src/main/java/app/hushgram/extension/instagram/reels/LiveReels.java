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
 * <p>A page where every item is a live keeps every item there, since the fetcher may read an empty
 * page as the end of the Reels, and the report counts it. Every list then reaches the viewer's items
 * through its data source's add or insert, a page included and lists that never came through that
 * callback too, and the patch hands each to {@link #added} with the viewer's module name and how many
 * items the viewer already holds. There a list of only lives goes whole too, unless the viewer holds
 * nothing yet. Off, paused, before the settings are read, or when anything throws, a list comes back
 * as it came, the same list.
 */
public final class LiveReels {
    /** The diagnostic counter route: the pages seen, and the lives taken out of them. */
    static final String ROUTE = "Lives in Reels";

    /** The diagnostic counter route for the lists the viewer's data source adds to its items. */
    static final String ADDED_ROUTE = "Lives added to Reels";

    /** The kind counted for a live taken out. */
    static final String LIVE = "live";

    /** The kind counted for a page left whole because every item in it was a live. */
    static final String PAGE_KEPT = "page kept whole";

    /** The kind counted for a list of only lives kept because the viewer held nothing yet. */
    static final String FIRST_KEPT = "first list kept whole";

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
        return without(items, module, false, ROUTE, PAGE_KEPT, "reels page", preview, on);
    }

    /**
     * Injected first thing in the Reels viewer's data source where it adds a list to its items or
     * inserts one among them, with the viewer's module name and [held], how many items it holds
     * already. Answers a new list without the live previews while the switch is on and the viewer is
     * one {@link #page} filters, a list of only lives included unless the viewer holds nothing yet,
     * and [items] itself otherwise. Never throws.
     */
    public static List<?> added(List<?> items, String module, int held) {
        return added(items, module, held, LiveReels::livePreview, LiveReels::switchedOn);
    }

    static List<?> added(List<?> items, String module, int held, Function<Object, Object> preview, BooleanSupplier on) {
        return without(items, module, held > 0, ADDED_ROUTE, FIRST_KEPT, "reels items added", preview, on);
    }

    /**
     * [items] without the lives [preview] finds, counted under [route]. A list of only lives comes
     * back whole, counted as [keptWhole], unless [allMayGo]. [where] names the hook when it throws.
     */
    private static List<?> without(List<?> items, String module, boolean allMayGo, String route, String keptWhole,
            String where, Function<Object, Object> preview, BooleanSupplier on) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.LIVE_REELS);
            if (!filtered(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(route, size);
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
            if (lives == size && !allMayGo) {
                FeedFilterCounters.sawKind(route, keptWhole);
                Logger.printDebug(() -> route + ": kept a list of " + size + " lives");
                return items;
            }

            List<Object> kept = new ArrayList<>(size - lives);
            at = 0;
            for (Object item : items) {
                if (!live[at++]) kept.add(item);
            }
            FeedFilterCounters.removed(route, lives, LIVE);
            int taken = lives;
            Logger.printDebug(() -> route + ": took " + taken + " of " + size + " items out");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIVE_REELS, where, failure);
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
