/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Hide You may also like in search, a switch of the "Hide the Explore grid" patch.
 *
 * <p>Instagram's search results are a list of units, each a block of the page: the accounts, the
 * posts and reels, a header. At the end it adds one more, a You may also like block of posts nobody
 * searched for, and Instagram itself tells that unit apart by a marker the server puts on it. Both
 * builders of the results page are handed the list first thing, and the patch passes it through
 * {@link #units}, which leaves that block out. Its You may also like title is a unit of its own,
 * Instagram's HEADER kind, right before the block (both builders look at the unit after a title to
 * see whether it's the block's), so a title followed by the block goes with it. The patch fills
 * {@link SearchRecommendationReader} with Instagram's own check and kind, so this class never reads
 * the marker itself.
 *
 * <p>Every other unit stays, in order. The hook fails open: with the switch off, HushGram paused, the
 * settings not read yet or anything thrown, Instagram's own list stands.
 *
 * <p>Pages after the block hold more of it and nothing else, and with it gone the page is often too
 * short to scroll, so Instagram puts its load more button under it, which fetched pages that came out
 * empty and took the accounts above with them. The patch also asks {@link #lastPage} first thing in a
 * page's own answer to whether more come after it, so a page holding the block is the last one.
 */
public final class SearchRecommendations {
    /** The diagnostic counter route for the You may also like blocks left out of search results. */
    static final String ROUTE = "Search You may also like";

    static final String UNITS = "results units";

    static final String PAGES = "results pages";

    private SearchRecommendations() {
    }

    /**
     * Injected first thing in both builders of the search results page, with the list of units it's
     * handed. Answers a new list without the You may also like block and its title while the switch
     * is on, and {@code units} itself otherwise, when it isn't a list, has no such block, or when
     * anything goes wrong. The list Instagram passed is never changed. Never throws.
     */
    public static Object units(Object units) {
        return units(units, SearchRecommendations::switchedOn, SearchRecommendationReader::isRecommendation,
                SearchRecommendationReader::isHeader);
    }

    static Object units(Object units, BooleanSupplier on, Predicate<Object> recommendation, Predicate<Object> header) {
        if (!(units instanceof List)) return units;
        List<?> list = (List<?>) units;
        if (list.isEmpty()) return units;
        try {
            HookStatus.invoked(FamilyNames.EXPLORE_GRID);
            FeedFilterCounters.sawList(ROUTE, list.size());
            if (!on.getAsBoolean()) return units;
            Object[] all = list.toArray();
            boolean[] out = new boolean[all.length];
            int removed = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] == null || !recommendation.test(all[i])) continue;
                out[i] = true;
                removed++;
                // Instagram pairs a title with the unit right after it, so that's the block's title.
                if (i > 0 && !out[i - 1] && all[i - 1] != null && header.test(all[i - 1])) {
                    out[i - 1] = true;
                    removed++;
                }
            }
            if (removed == 0) return units;
            List<Object> kept = new ArrayList<>(all.length - removed);
            for (int i = 0; i < all.length; i++) {
                if (!out[i]) kept.add(all[i]);
            }
            FeedFilterCounters.removed(ROUTE, removed, UNITS);
            int count = removed;
            Logger.printDebug(() -> "Search recommendations: left out " + count + " You may also like units");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_GRID, UNITS, failure);
            return units;
        }
    }

    /**
     * Injected first thing in a page of search results' answer to whether more pages come after it,
     * with the page's units. True, so Instagram reads the page as the last, while the switch is on and
     * the page holds the You may also like block. False otherwise, when it isn't a list, or when
     * anything goes wrong, and Instagram's own answer stands. Never throws.
     */
    public static boolean lastPage(Object units) {
        return lastPage(units, SearchRecommendations::switchedOn, SearchRecommendationReader::isRecommendation);
    }

    static boolean lastPage(Object units, BooleanSupplier on, Predicate<Object> recommendation) {
        if (!(units instanceof List)) return false;
        List<?> list = (List<?>) units;
        if (list.isEmpty()) return false;
        try {
            if (!on.getAsBoolean()) return false;
            for (Object unit : list.toArray()) {
                if (unit != null && recommendation.test(unit)) return true;
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_GRID, PAGES, failure);
            return false;
        }
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_SEARCH_RECOMMENDATIONS.get();
    }
}
