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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    /** Stands in for Instagram's Explore layouts, an enum keeping the server's names. */
    enum StockLayout { MEDIA_GRID, ONE_BY_TWO_LEFT, ONE_BY_TWO_RIGHT_WITH_AD, TWO_BY_TWO_RIGHT, MEMORY_GRID_ONE_BY_TWO_LEFT }

    /** Stands in for an Explore page's section, DiscoverySection: its item info, content and layout. */
    static final class StockSection {
        final Object info = new Object();
        final Object content = new Object();
        final StockLayout layout;

        StockSection(StockLayout layout) {
            this.layout = layout;
        }

        @Override
        public String toString() {
            return layout.name();
        }
    }

    private static List<Object> page(StockLayout... layouts) {
        List<Object> sections = new ArrayList<>();
        for (StockLayout layout : layouts) sections.add(new StockSection(layout));
        return sections;
    }

    /** #880: with Hide tall reels in Explore on, the sections built around a tall reel leave the page and the rest stay in order. */
    @Test
    public void tallReelSectionsLeaveThePage() {
        Settings.HIDE_EXPLORE_GRID.save(false);
        Settings.HIDE_EXPLORE_TALL_REELS.save(true);
        try {
            List<Object> sections = page(StockLayout.MEDIA_GRID, StockLayout.ONE_BY_TWO_LEFT, StockLayout.TWO_BY_TWO_RIGHT,
                    StockLayout.ONE_BY_TWO_RIGHT_WITH_AD, StockLayout.MEMORY_GRID_ONE_BY_TWO_LEFT, StockLayout.MEDIA_GRID);

            assertFalse(ExploreGrid.hide(sections));
            assertEquals("[MEDIA_GRID, TWO_BY_TWO_RIGHT, MEMORY_GRID_ONE_BY_TWO_LEFT, MEDIA_GRID]", sections.toString());
        } finally {
            Settings.HIDE_EXPLORE_TALL_REELS.resetToDefault();
            Settings.HIDE_EXPLORE_GRID.save(true);
        }
    }

    @Test
    public void withTheTallSwitchOffEverySectionStays() {
        Settings.HIDE_EXPLORE_GRID.save(false);
        try {
            List<Object> sections = page(StockLayout.MEDIA_GRID, StockLayout.ONE_BY_TWO_LEFT);
            assertFalse(ExploreGrid.hide(sections));
            assertEquals("[MEDIA_GRID, ONE_BY_TWO_LEFT]", sections.toString());
        } finally {
            Settings.HIDE_EXPLORE_GRID.save(true);
        }
    }

    /** A page of nothing but tall reels keeps them: an empty page leaves Explore on its loading placeholder. */
    @Test
    public void aPageOfOnlyTallReelsStaysWhole() {
        Settings.HIDE_EXPLORE_GRID.save(false);
        Settings.HIDE_EXPLORE_TALL_REELS.save(true);
        try {
            List<Object> sections = page(StockLayout.ONE_BY_TWO_LEFT, StockLayout.ONE_BY_TWO_RIGHT_WITH_AD);
            assertFalse(ExploreGrid.hide(sections));
            assertEquals(2, sections.size());
        } finally {
            Settings.HIDE_EXPLORE_TALL_REELS.resetToDefault();
            Settings.HIDE_EXPLORE_GRID.save(true);
        }
    }

    /** Sections the code can't read, and a list Instagram made fixed, stay as they are. Nothing throws. */
    @Test
    public void oddSectionsAndFixedListsStay() {
        Settings.HIDE_EXPLORE_GRID.save(false);
        Settings.HIDE_EXPLORE_TALL_REELS.save(true);
        try {
            List<Object> strangers = new ArrayList<>(Arrays.asList("section", null, 7));
            assertFalse(ExploreGrid.hide(strangers));
            assertEquals(3, strangers.size());

            List<Object> fixed = Collections.unmodifiableList(page(StockLayout.MEDIA_GRID, StockLayout.ONE_BY_TWO_LEFT));
            assertFalse(ExploreGrid.hide(fixed));
            assertEquals(2, fixed.size());
        } finally {
            Settings.HIDE_EXPLORE_TALL_REELS.resetToDefault();
            Settings.HIDE_EXPLORE_GRID.save(true);
        }
    }

    /** Hide the Explore grid wins: the page is emptied whole and its sections aren't sorted first. */
    @Test
    public void hidingTheGridWinsOverTheTallSwitch() {
        Settings.HIDE_EXPLORE_TALL_REELS.save(true);
        try {
            List<Object> sections = page(StockLayout.MEDIA_GRID, StockLayout.ONE_BY_TWO_LEFT);
            assertTrue(ExploreGrid.hide(sections));
            assertEquals(2, sections.size());
        } finally {
            Settings.HIDE_EXPLORE_TALL_REELS.resetToDefault();
        }
    }
}
