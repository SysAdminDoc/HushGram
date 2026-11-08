/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Instagram's feed hold for 2x flags answer through the hooks (#552). */
@RunWith(RobolectricTestRunner.class)
public class FeedHoldSpeedTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HOLD_FEED_VIDEO_FAST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedHoldSpeed.inBuildForTests = null;
    }

    /** It starts off, and needs no restart: Instagram reads the flags again on every held press. */
    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.HOLD_FEED_VIDEO_FAST.resetToDefault();
        assertFalse(Settings.HOLD_FEED_VIDEO_FAST.get());
        assertFalse(Settings.HOLD_FEED_VIDEO_FAST.rebootApp);
    }

    /** On, every held feed video plays fast, and a hold counts anywhere on it: half the width from each side, no fixed strip. */
    @Test
    public void onEveryHoldIsFastAnywhereOnTheVideo() {
        assertTrue("the server said no", FeedHoldSpeed.fast(0));
        assertTrue("the server said yes", FeedHoldSpeed.fast(1));
        assertFalse("a fixed strip the server asked for", FeedHoldSpeed.fixedStrip(1));
        assertFalse("no fixed strip", FeedHoldSpeed.fixedStrip(0));
        assertEquals(0.5d, FeedHoldSpeed.strip(0.2d), 0d);
        assertEquals(0.5d, FeedHoldSpeed.strip(0d), 0d);
        assertTrue(HookStatus.missing(FamilyNames.FEED_HOLD_FAST).toString(),
                HookStatus.missing(FamilyNames.FEED_HOLD_FAST).isEmpty());
    }

    /** Off, paused or before the settings are read, every answer stays Instagram's. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswers() {
        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        assertInstagrams("off");
        Settings.HOLD_FEED_VIDEO_FAST.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertInstagrams("paused");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertInstagrams("no context"));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertInstagrams("pause undecided"));

        assertTrue("back on", FeedHoldSpeed.fast(0));
        assertEquals("back on", 0.5d, FeedHoldSpeed.strip(0.2d), 0d);
    }

    /** A held press is Instagram's to speed up only when the patch is in and the switch is on. */
    @Test
    public void holdingNeedsThePatchAndTheSwitch() {
        FeedHoldSpeed.inBuildForTests = true;
        assertTrue("in and on", FeedHoldSpeed.holding());
        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        assertFalse("in and off", FeedHoldSpeed.holding());
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        FeedHoldSpeed.inBuildForTests = false;
        assertFalse("out and on", FeedHoldSpeed.holding());
        FeedHoldSpeed.inBuildForTests = null;
        assertFalse("the stub says the patch is out", FeedHoldSpeed.holding());
        FeedHoldSpeed.inBuildForTests = true;
        SettingsContextRule.withoutContext(() -> assertFalse("no context", FeedHoldSpeed.holding()));
    }

    private static void assertInstagrams(String when) {
        assertFalse(when, FeedHoldSpeed.fast(0));
        assertTrue(when + ", Instagram's own yes", FeedHoldSpeed.fast(1));
        assertTrue(when + ", Instagram's fixed strip", FeedHoldSpeed.fixedStrip(1));
        assertFalse(when, FeedHoldSpeed.fixedStrip(0));
        assertEquals(when, 0.2d, FeedHoldSpeed.strip(0.2d), 0d);
    }
}
