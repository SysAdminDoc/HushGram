/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

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

/** What Sort reels on profiles answers each read of whether a profile's reels can be sorted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelSortTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.PROFILE_REEL_SORT.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.PROFILE_REEL_SORT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnEveryProfileGetsTheSort() {
        assertTrue(ReelSort.sortable(0));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelSort.OFFERED + " 1"));
        assertTrue("a profile Instagram already offers it for keeps it", ReelSort.sortable(1));
        report = HookStatus.report().toString();
        assertTrue("only a changed answer is counted: " + report, report.contains(ReelSort.OFFERED + " 1"));
    }

    @Test
    public void offToStartAndOffKeepsInstagramsAnswer() {
        Settings.PROFILE_REEL_SORT.resetToDefault();
        assertFalse(Settings.PROFILE_REEL_SORT.get());
        assertFalse(ReelSort.sortable(0));
        assertTrue(ReelSort.sortable(1));
    }

    @Test
    public void pausedUnreadyOrThrowingKeepInstagramsAnswer() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelSort.sortable(0));
        assertTrue(ReelSort.sortable(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ReelSort.sortable(0)));

        assertFalse(ReelSort.sortable(0, () -> {
            throw new IllegalStateException("settings went away");
        }));
        assertFalse(HookStatus.missing(FamilyNames.PROFILE_REEL_SORT).isEmpty());
    }
}
