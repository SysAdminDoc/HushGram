/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

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

/** What the Reels zoom flags answer through the hook. */
@RunWith(RobolectricTestRunner.class)
public class ReelZoomTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.PINCH_ZOOM_REELS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.PINCH_ZOOM_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The patch is in Manager's default selection, so its switch starts off, and a change needs a restart. */
    @Test
    public void theSwitchStartsOffAndAsksForARestart() {
        Settings.PINCH_ZOOM_REELS.resetToDefault();
        assertFalse(Settings.PINCH_ZOOM_REELS.get());
        assertTrue(Settings.PINCH_ZOOM_REELS.rebootApp);
    }

    /** With the switch on, both flags say yes whatever Instagram's server said. */
    @Test
    public void onBothFlagsSayYes() {
        assertTrue("the server said no", ReelZoom.zoom(0));
        assertTrue("the server said yes", ReelZoom.zoom(1));
        assertTrue(HookStatus.missing(FamilyNames.REEL_ZOOM).toString(), HookStatus.missing(FamilyNames.REEL_ZOOM).isEmpty());
    }

    /** Off, paused or before the settings are read, the flags keep Instagram's answer. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.PINCH_ZOOM_REELS.save(false);
        assertFalse("off", ReelZoom.zoom(0));
        assertTrue("off, Instagram's own yes", ReelZoom.zoom(1));
        Settings.PINCH_ZOOM_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ReelZoom.zoom(0));
        assertTrue("paused, Instagram's own yes", ReelZoom.zoom(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", ReelZoom.zoom(0));
            assertTrue("no context, Instagram's own yes", ReelZoom.zoom(1));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertFalse("pause undecided", ReelZoom.zoom(0));
            assertTrue("pause undecided, Instagram's own yes", ReelZoom.zoom(1));
        });

        assertTrue("back on", ReelZoom.zoom(0));
    }
}
