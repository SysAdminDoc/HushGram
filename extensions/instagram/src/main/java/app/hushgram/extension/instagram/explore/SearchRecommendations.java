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
 * {@link #units}, which leaves that block out. The builders then draw no header for it either, since
 * they draw the header from the unit. The patch fills {@link SearchRecommendationReader} with
 * Instagram's own check, so this class never reads the marker itself.
 *
 * <p>Every other unit stays, in order. The hook fails open: with the switch off, HushGram paused, the
 * settings not read yet or anything thrown, Instagram's own list stands.
 */
public final class SearchRecommendations {
    /** The diagnostic counter route for the You may also like blocks left out of search results. */
    static final String ROUTE = "Search You may also like";

    static final String UNITS = "results units";

    private SearchRecommendations() {
    }

    /**
     * Injected first thing in both builders of the search results page, with the list of units it's
     * handed. Answers a new list without the You may also like block while the switch is on, and
     * {@code units} itself otherwise, when it isn't a list, has no such block, or when anything goes
     * wrong. The list Instagram passed is never changed. Never throws.
     */
    public static Object units(Object units) {
        return units(units, SearchRecommendations::switchedOn, SearchRecommendationReader::isRecommendation);
    }

    static Object units(Object units, BooleanSupplier on, Predicate<Object> recommendation) {
        if (!(units instanceof List)) return units;
        List<?> list = (List<?>) units;
        if (list.isEmpty()) return units;
        try {
            HookStatus.invoked(FamilyNames.EXPLORE_GRID);
            FeedFilterCounters.sawList(ROUTE, list.size());
            if (!on.getAsBoolean()) return units;
            List<Object> kept = new ArrayList<>(list.size());
            int removed = 0;
            for (Object unit : list) {
                if (unit != null && recommendation.test(unit)) {
                    removed++;
                } else {
                    kept.add(unit);
                }
            }
            if (removed == 0) return units;
            FeedFilterCounters.removed(ROUTE, removed, UNITS);
            int count = removed;
            Logger.printDebug(() -> "Search recommendations: left out " + count + " You may also like blocks");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_GRID, UNITS, failure);
            return units;
        }
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_SEARCH_RECOMMENDATIONS.get();
    }
}
