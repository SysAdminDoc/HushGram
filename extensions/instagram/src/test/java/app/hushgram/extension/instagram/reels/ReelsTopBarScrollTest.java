/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

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

/** Keep the Reels top bar (#411): the top bar's keep-in-place flag reads yes while the switch is on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelsTopBarScrollTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_REELS_TOP_BAR.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_REELS_TOP_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertEquals(Boolean.FALSE, Settings.KEEP_REELS_TOP_BAR.defaultValue);
        assertFalse(Settings.KEEP_REELS_TOP_BAR.rebootApp);
    }

    @Test
    public void offToStartPassesInstagramsAnswer() {
        assertFalse(ReelsTopBarScroll.keepInPlace(0));
        assertTrue(ReelsTopBarScroll.keepInPlace(1));
        assertTrue("any non-zero int is Instagram's yes", ReelsTopBarScroll.keepInPlace(2));
        assertTrue(HookStatus.missing(FamilyNames.KEEP_REELS_TOP_BAR).toString(),
                HookStatus.missing(FamilyNames.KEEP_REELS_TOP_BAR).isEmpty());
        assertFalse("nothing kept", HookStatus.report().toString().contains(ReelsTopBarScroll.COUNT_KEPT));
    }

    @Test
    public void onKeepsTheBarAndCountsOnlyWhatItChanged() {
        Settings.KEEP_REELS_TOP_BAR.save(true);

        assertTrue(ReelsTopBarScroll.keepInPlace(0));
        assertTrue("Instagram's own yes stays a yes", ReelsTopBarScroll.keepInPlace(1));

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelsTopBarScroll.COUNT_KEPT + " 1"));
    }

    @Test
    public void pausedAndUnreadyReadAsOff() {
        Settings.KEEP_REELS_TOP_BAR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ReelsTopBarScroll.keepInPlace(0));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("unready", ReelsTopBarScroll.keepInPlace(0)));

        assertTrue("on again once resumed and ready", ReelsTopBarScroll.keepInPlace(0));
    }

    @Test
    public void aThrowingSwitchFailsOpen() {
        assertFalse(ReelsTopBarScroll.keepInPlace(false, () -> {
            throw new IllegalStateException("boom");
        }));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelsTopBarScroll.HOOK));
    }
}
