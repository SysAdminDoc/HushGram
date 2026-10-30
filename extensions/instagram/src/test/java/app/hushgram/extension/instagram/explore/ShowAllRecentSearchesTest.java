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
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Show all recent searches does to the number of recent searches the empty search screen lists. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ShowAllRecentSearchesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHOW_ALL_RECENT_SEARCHES.resetToDefault();
        Settings.DONT_SAVE_RECENT_SEARCHES.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SHOW_ALL_RECENT_SEARCHES.resetToDefault();
        Settings.DONT_SAVE_RECENT_SEARCHES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void offToStartKeepsInstagramsLimit() {
        assertFalse(Settings.SHOW_ALL_RECENT_SEARCHES.get());
        assertEquals(3, RecentSearches.shown(3));
        assertEquals(8, RecentSearches.shown(8));
        assertTrue(HookStatus.missing(FamilyNames.ALL_RECENT_SEARCHES).toString(),
                HookStatus.missing(FamilyNames.ALL_RECENT_SEARCHES).isEmpty());
    }

    @Test
    public void onListsEverything() {
        Settings.SHOW_ALL_RECENT_SEARCHES.save(true);
        assertEquals(Integer.MAX_VALUE, RecentSearches.shown(5));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(RecentSearches.LISTED + " 1"));
    }

    @Test
    public void pausedAnswersInstagramsOwnLimit() {
        Settings.SHOW_ALL_RECENT_SEARCHES.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(5, RecentSearches.shown(5));
        assertTrue(Settings.SHOW_ALL_RECENT_SEARCHES.savedValue());
    }

    @Test
    public void dontSaveStillStopsNewSearchesWhileEverythingShows() {
        Settings.SHOW_ALL_RECENT_SEARCHES.save(true);
        Settings.DONT_SAVE_RECENT_SEARCHES.save(true);
        assertFalse("nothing new is saved", RecentSearches.keep());
        assertEquals(Integer.MAX_VALUE, RecentSearches.shown(5));
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        assertEquals(4, RecentSearches.shown(4, () -> {
            throw new IllegalStateException("no");
        }));
        assertTrue(HookStatus.report().toString().contains("threw"));
    }
}
