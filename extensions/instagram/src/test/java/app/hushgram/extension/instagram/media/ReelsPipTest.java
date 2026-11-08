/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

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

/** Picture in picture for reels (#572): the switch answers yes for Instagram's two checks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelsPipTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.REELS_PICTURE_IN_PICTURE.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.REELS_PICTURE_IN_PICTURE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start, so Instagram's checks decide. */
    @Test
    public void startsOff() {
        Settings.REELS_PICTURE_IN_PICTURE.resetToDefault();
        assertFalse(ReelsPip.on());
    }

    /** On, both checks answer yes, and the hook reports in. */
    @Test
    public void theSwitchAnswersYes() {
        assertTrue(ReelsPip.on());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.BACKGROUND_PLAY));
        assertTrue(HookStatus.missing(FamilyNames.BACKGROUND_PLAY).toString(),
                HookStatus.missing(FamilyNames.BACKGROUND_PLAY).isEmpty());
    }

    /** Paused, or before HushGram's settings are ready, Instagram decides. */
    @Test
    public void pausedOrNotReadyLeavesItToInstagram() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelsPip.on());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(ReelsPip.on()));
    }

    /** A failing read leaves it to Instagram and is reported. */
    @Test
    public void aFailureLeavesItToInstagram() {
        assertFalse(ReelsPip.on(() -> {
            throw new IllegalStateException("boom");
        }));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(ReelsPip.HOOK));
    }
}
