/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When Hide the Explore grid empties an Explore page and hides its load more row. */
@RunWith(RobolectricTestRunner.class)
public class ExploreGridTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.HIDE_EXPLORE_GRID.save(true);
    }

    @After
    public void switchBack() {
        Settings.HIDE_EXPLORE_GRID.resetToDefault();
    }

    @Test
    public void aPageIsEmptiedWhileTheSwitchIsOn() {
        assertTrue(ExploreGrid.hide(Arrays.asList("section", "section")));
        assertTrue(ExploreGrid.hide(Collections.emptyList()));
        assertTrue(ExploreGrid.hide(null));
    }

    @Test
    public void withTheSwitchOffThePageStays() {
        Settings.HIDE_EXPLORE_GRID.save(false);
        try {
            assertFalse(ExploreGrid.hide(Arrays.asList("section", "section")));
        } finally {
            Settings.HIDE_EXPLORE_GRID.save(true);
        }
    }

    /** With the switch on, Explore's load more row hides and any other list keeps its own. */
    @Test
    public void onlyExploresLoadMoreRowGoes() {
        Object explore = new Object();
        Object location = new Object();
        ExploreGrid.track(explore);

        assertEquals(0, ExploreGrid.loadMoreRow(explore, 1));
        assertEquals(0, ExploreGrid.loadMoreRow(explore, 0));
        assertEquals(1, ExploreGrid.loadMoreRow(location, 1));
        assertEquals(0, ExploreGrid.loadMoreRow(location, 0));
        assertEquals(1, ExploreGrid.loadMoreRow(null, 1));
    }

    @Test
    public void withTheSwitchOffExploreKeepsItsRow() {
        Object explore = new Object();
        ExploreGrid.track(explore);
        Settings.HIDE_EXPLORE_GRID.save(false);
        try {
            assertEquals(1, ExploreGrid.loadMoreRow(explore, 1));
            assertEquals(0, ExploreGrid.loadMoreRow(explore, 0));
        } finally {
            Settings.HIDE_EXPLORE_GRID.save(true);
        }
    }
}
