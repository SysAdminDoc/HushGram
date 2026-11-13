/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Hide You may also like in search leaves out of the results page: the recommended block, nothing else. */
@RunWith(RobolectricTestRunner.class)
public class SearchRecommendationsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A unit of the results page, marked the way Instagram's own check would answer for it. */
    static final class Unit {
        final boolean recommendation;

        Unit(boolean recommendation) {
            this.recommendation = recommendation;
        }
    }

    /** Stands in for Instagram's check, which the patch puts in the reader. */
    private static final Predicate<Object> MARKED = unit -> unit instanceof Unit && ((Unit) unit).recommendation;

    /** The hook with its real switch and the stand-in check. */
    private static Object filtered(Object units) {
        return SearchRecommendations.units(units, SearchRecommendations::switchedOn, MARKED);
    }

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_SEARCH_RECOMMENDATIONS.resetToDefault();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    @After
    public void restore() {
        Settings.HIDE_SEARCH_RECOMMENDATIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    /** The switch starts off, and then Instagram's own list comes back. */
    @Test
    public void theSwitchStartsOffAndTheListStays() {
        assertFalse(Settings.HIDE_SEARCH_RECOMMENDATIONS.get());
        List<Unit> units = Arrays.asList(new Unit(false), new Unit(true));
        assertSame(units, filtered(units));
        assertSame(units, SearchRecommendations.units(units));
        assertTrue(HookStatus.missing(FamilyNames.EXPLORE_GRID).toString(), HookStatus.missing(FamilyNames.EXPLORE_GRID).isEmpty());
    }

    /** On, the recommended block goes and every other unit stays in order; Instagram's list isn't changed. */
    @Test
    public void onTheRecommendedBlockIsLeftOut() {
        Settings.HIDE_SEARCH_RECOMMENDATIONS.save(true);
        Unit accounts = new Unit(false);
        Unit posts = new Unit(false);
        List<Unit> units = Arrays.asList(accounts, null, posts, new Unit(true));
        List<Unit> before = new ArrayList<>(units);

        Object kept = filtered(units);

        assertEquals(Arrays.asList(accounts, null, posts), kept);
        assertEquals("Instagram's list", before, units);
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(SearchRecommendations.ROUTE));
        assertTrue(report, report.contains("1 removed"));
        assertTrue(report, report.contains(SearchRecommendations.UNITS + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.EXPLORE_GRID).toString(), HookStatus.missing(FamilyNames.EXPLORE_GRID).isEmpty());
    }

    /** On, a page with no recommended block, an empty list, something that isn't a list and null come back as they are. */
    @Test
    public void aPageWithoutTheBlockComesBackAsItIs() {
        Settings.HIDE_SEARCH_RECOMMENDATIONS.save(true);
        List<Unit> results = Collections.singletonList(new Unit(false));
        assertSame(results, filtered(results));
        List<Unit> empty = Collections.emptyList();
        assertSame(empty, filtered(empty));
        Object notAList = new Unit(true);
        assertSame(notAList, filtered(notAList));
        assertNull(filtered(null));
    }

    /** Before the patch fills the reader it answers no, so the real hook keeps every unit. */
    @Test
    public void anUnfilledReaderKeepsEverything() {
        Settings.HIDE_SEARCH_RECOMMENDATIONS.save(true);
        List<Unit> units = Arrays.asList(new Unit(false), new Unit(true));
        assertFalse(SearchRecommendationReader.isRecommendation(units.get(1)));
        assertSame(units, SearchRecommendations.units(units));
    }

    /** Paused, or before the settings are read, the block stays. */
    @Test
    public void pausedAndUnreadyLeaveTheBlock() {
        Settings.HIDE_SEARCH_RECOMMENDATIONS.save(true);
        List<Unit> units = Arrays.asList(new Unit(false), new Unit(true));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", units, filtered(units));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame("no context", units, filtered(units)));

        assertEquals("back on", 1, ((List<?>) filtered(units)).size());
    }

    /** A switch or a check that throws leaves Instagram's list and is reported. */
    @Test
    public void aThrowFailsOpenAndIsReported() {
        List<Unit> units = Arrays.asList(new Unit(false), new Unit(true));
        assertSame(units, SearchRecommendations.units(units, () -> {
            throw new IllegalStateException("switch");
        }, MARKED));
        assertSame(units, SearchRecommendations.units(units, () -> true, unit -> {
            throw new IllegalStateException("check");
        }));
        String missing = HookStatus.missing(FamilyNames.EXPLORE_GRID).toString();
        assertTrue(missing, missing.contains("'" + SearchRecommendations.UNITS + "'"));
    }
}
