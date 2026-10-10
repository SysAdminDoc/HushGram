/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.ads;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide ads' answer to the ad-insert method (audit A04). Only an on switch, read with settings ready
 * and HushGram not paused, hides an ad. Every other state leaves Instagram's own insert running.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class AdsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private HookStatus.Snapshot saved;

    @Before
    public void setUp() {
        saved = HookStatus.snapshotAndClear();
        PauseForTests.resume();
        Settings.HIDE_ADS.save(true);
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        Settings.HIDE_ADS.resetToDefault();
        HookStatus.restore(saved);
    }

    @Test
    public void anOnSwitchHidesTheAd() {
        assertTrue(Ads.hide());
        assertFalse("the call wasn't counted", HookStatus.snapshot().isEmpty());
    }

    @Test
    public void anOffSwitchLetsInstagramInsertIt() {
        Settings.HIDE_ADS.save(false);
        assertFalse(Ads.hide());
    }

    @Test
    public void pauseLetsInstagramInsertIt() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Ads.hide());
    }

    @Test
    public void unreadySettingsLetInstagramInsertIt() {
        SettingsContextRule.withoutContext(() -> assertFalse("no context", Ads.hide()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", Ads.hide()));
        assertTrue("ready again", Ads.hide());
    }
}
