/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

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

/** What Don't save recent searches tells the cache's add and the call to the servers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class RecentSearchesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.DONT_SAVE_RECENT_SEARCHES.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.DONT_SAVE_RECENT_SEARCHES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnNothingIsSaved() {
        assertFalse(RecentSearches.keep());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(RecentSearches.SKIPPED + " 1"));
    }

    @Test
    public void offToStartAndOffSaveAsBefore() {
        Settings.DONT_SAVE_RECENT_SEARCHES.resetToDefault();
        assertFalse(Settings.DONT_SAVE_RECENT_SEARCHES.get());
        assertTrue(RecentSearches.keep());
    }

    @Test
    public void pausedUnreadyOrThrowingSaveAsBefore() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(RecentSearches.keep());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue(RecentSearches.keep()));

        assertTrue(RecentSearches.keep(() -> {
            throw new IllegalStateException("settings went away");
        }));
        assertFalse(HookStatus.missing(FamilyNames.RECENT_SEARCHES).isEmpty());
    }
}
