/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the tab list and tab switch hooks do with the Search, Create and Profile switches. */
@RunWith(RobolectricTestRunner.class)
public class TabBarHidingTest {
    /** Named like Instagram's tab enum, which is all the hooks go by. */
    enum Tab { FEED, SEARCH, CREATION, CLIPS, DIRECT, PROFILE }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        Settings.HIDE_REELS_TAB.resetToDefault();
        Settings.SHOW_REELS_TAB.resetToDefault();
        Settings.HIDE_SEARCH_TAB.resetToDefault();
        Settings.HIDE_CREATE_TAB.resetToDefault();
        Settings.HIDE_PROFILE_TAB.resetToDefault();
    }

    private static List<Tab> built() {
        return Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CREATION, Tab.CLIPS, Tab.PROFILE);
    }

    @Test
    public void everySwitchStartsOffAndAnUntouchedListComesBackAsBuilt() {
        assertFalse(Settings.HIDE_SEARCH_TAB.get());
        assertFalse(Settings.HIDE_CREATE_TAB.get());
        assertFalse(Settings.HIDE_PROFILE_TAB.get());
        List<Tab> list = built();
        assertSame(list, ReelsTab.tabs(list));
        for (Tab tab : Tab.values()) assertSame(tab, ReelsTab.tab(tab));
    }

    @Test
    public void eachTabLeavesTheListAloneAndClosesUpWithNoGap() {
        Settings.HIDE_SEARCH_TAB.save(true);
        assertEquals(Arrays.asList(Tab.FEED, Tab.CREATION, Tab.CLIPS, Tab.PROFILE), ReelsTab.tabs(built()));
        Settings.HIDE_SEARCH_TAB.save(false);

        Settings.HIDE_CREATE_TAB.save(true);
        assertEquals(Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CLIPS, Tab.PROFILE), ReelsTab.tabs(built()));
        Settings.HIDE_CREATE_TAB.save(false);

        Settings.HIDE_PROFILE_TAB.save(true);
        assertEquals(Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CREATION, Tab.CLIPS), ReelsTab.tabs(built()));
    }

    @Test
    public void severalTabsLeaveTogetherAndTheBuiltListIsNeverChanged() {
        Settings.HIDE_SEARCH_TAB.save(true);
        Settings.HIDE_PROFILE_TAB.save(true);
        Settings.HIDE_REELS_TAB.save(true);
        List<Tab> list = new ArrayList<>(built());

        List<?> shown = ReelsTab.tabs(list);

        assertEquals(Arrays.asList(Tab.FEED, Tab.CREATION), shown);
        assertEquals("the built list was changed", built(), list);
    }

    @Test
    public void theBarNeverEndsUpEmpty() {
        Settings.HIDE_SEARCH_TAB.save(true);
        Settings.HIDE_CREATE_TAB.save(true);
        Settings.HIDE_PROFILE_TAB.save(true);
        Settings.HIDE_REELS_TAB.save(true);
        List<Tab> onlyHidden = Arrays.asList(Tab.SEARCH, Tab.PROFILE);

        assertSame(onlyHidden, ReelsTab.tabs(onlyHidden));
        assertEquals("Home is never hidden", Collections.singletonList(Tab.FEED), ReelsTab.tabs(built()));
        assertSame(Collections.emptyList(), ReelsTab.tabs(Collections.emptyList()));
    }

    @Test
    public void aTabTheListDoesNotHoldChangesNothing() {
        Settings.HIDE_SEARCH_TAB.save(true);
        List<Tab> noSearch = Arrays.asList(Tab.FEED, Tab.PROFILE);

        assertSame(noSearch, ReelsTab.tabs(noSearch));
        List<String> strings = Arrays.asList("SEARCH", "FEED");
        assertEquals("names that are not tabs stay", strings, ReelsTab.tabs(strings));
    }

    @Test
    public void aSwitchMeantForAHiddenSearchOrProfileLandsOnHome() {
        Settings.HIDE_SEARCH_TAB.save(true);
        assertSame(Tab.FEED, ReelsTab.tab(Tab.SEARCH));
        assertSame(Tab.PROFILE, ReelsTab.tab(Tab.PROFILE));
        Settings.HIDE_PROFILE_TAB.save(true);
        assertSame(Tab.FEED, ReelsTab.tab(Tab.PROFILE));
        assertSame(Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
    }

    @Test
    public void createIsNeverRedirectedBecauseInstagramOpensItsCameraThroughIt() {
        Settings.HIDE_CREATE_TAB.save(true);
        assertSame(Tab.CREATION, ReelsTab.tab(Tab.CREATION));
        assertSame(Tab.DIRECT, ReelsTab.tab(Tab.DIRECT));
    }

    @Test
    public void hideAndShowReelsKeepTheirOrderOfPrecedence() {
        Settings.SHOW_REELS_TAB.save(true);
        Settings.HIDE_REELS_TAB.save(true);
        assertEquals(Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CREATION, Tab.PROFILE), ReelsTab.tabs(built()));
        assertSame(Tab.FEED, ReelsTab.tab(Tab.CLIPS));

        // Hide off, Show on: Reels comes back after Home even when another tab is hidden.
        Settings.HIDE_REELS_TAB.save(false);
        Settings.HIDE_SEARCH_TAB.save(true);
        List<Tab> noReels = Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.PROFILE);
        assertEquals(Arrays.asList(Tab.FEED, Tab.CLIPS, Tab.PROFILE), ReelsTab.tabs(noReels));
    }

    @Test
    public void eachHiddenTabIsCountedUnderItsOwnLine() {
        HookStatus.clear();
        Settings.HIDE_SEARCH_TAB.save(true);
        Settings.HIDE_CREATE_TAB.save(true);
        Settings.HIDE_PROFILE_TAB.save(true);
        Settings.HIDE_REELS_TAB.save(true);

        ReelsTab.tabs(built());

        String report = String.join(" | ", HookStatus.report());
        for (String label : Arrays.asList("Search tab hidden 1", "Create tab hidden 1", "Profile tab hidden 1", "Reels tab hidden 1")) {
            assertTrue(label + " in " + report, report.contains(label));
        }
        assertTrue(report, report.contains(FamilyNames.REELS_TAB));
    }

    @Test
    public void pausedOrNotReadySettingsLeaveTheBarAsBuilt() {
        Settings.HIDE_SEARCH_TAB.save(true);
        List<Tab> list = built();
        SettingsContextRule.withoutContext(() -> {
            assertSame(list, ReelsTab.tabs(list));
            assertSame(Tab.SEARCH, ReelsTab.tab(Tab.SEARCH));
        });
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            assertSame(list, ReelsTab.tabs(list));
            assertSame(Tab.SEARCH, ReelsTab.tab(Tab.SEARCH));
        } finally {
            PauseForTests.resume();
        }
    }
}
