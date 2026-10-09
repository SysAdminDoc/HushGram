/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;

/** What the Reels tab hooks answer. */
@RunWith(RobolectricTestRunner.class)
public class ReelsTabTest {
    /** Named like Instagram's tab enum, which is all the hooks go by. */
    enum Tab { FEED, SEARCH, CLIPS, DIRECT, PROFILE }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.HIDE_REELS_TAB.save(true);
    }

    @After
    public void tearDown() {
        Settings.HIDE_REELS_TAB.resetToDefault();
    }

    /** On, the list comes back as a copy without Reels, in the same order, and the hidden tab is counted. */
    @Test
    public void withTheSwitchOnReelsLeavesTheList() {
        FeedFilterCounters.snapshotAndClear();
        List<Tab> built = Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CLIPS, Tab.DIRECT, Tab.PROFILE);

        List<?> shown = ReelsTab.tabs(built);

        assertEquals(Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.DIRECT, Tab.PROFILE), shown);
        assertEquals("the built list was changed", 5, built.size());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ReelsTab.ROUTE));
        assertTrue(report, report.contains(ReelsTab.HIDDEN));
    }

    /** On, a switch to Reels or a home tab of Reels becomes Home, and any other tab goes through. */
    @Test
    public void withTheSwitchOnReelsBecomesHome() {
        assertSame(Tab.FEED, ReelsTab.tab(Tab.CLIPS));
        assertSame(Tab.DIRECT, ReelsTab.tab(Tab.DIRECT));
        assertNull(ReelsTab.tab(null));
    }

    /** Off, the list and every tab come back as they came. */
    @Test
    public void withTheSwitchOffNothingChanges() {
        Settings.HIDE_REELS_TAB.save(false);
        List<Tab> built = Arrays.asList(Tab.CLIPS, Tab.FEED);

        assertSame(built, ReelsTab.tabs(built));
        assertSame(Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
    }

    /** A list without Reels, or with nothing but Reels, comes back as it was: the bar never ends up empty. */
    @Test
    public void aListItCantShortenComesBackAsItWas() {
        List<Tab> noReels = Arrays.asList(Tab.FEED, Tab.PROFILE);
        List<Tab> onlyReels = Arrays.asList(Tab.CLIPS);

        assertSame(noReels, ReelsTab.tabs(noReels));
        assertSame(onlyReels, ReelsTab.tabs(onlyReels));
        assertNull(ReelsTab.tabs(null));
    }

    /** A tab enum with no Home to send Reels to keeps Reels rather than failing. */
    @Test
    public void anEnumWithoutHomeKeepsReels() {
        assertSame(NoHome.CLIPS, ReelsTab.tab(NoHome.CLIPS));
    }

    enum NoHome { CLIPS }
}
