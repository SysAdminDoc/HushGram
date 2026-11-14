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

import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the pause icon on reels (#686): the two hooks the patch puts first in the pause-and-mute
 * component's builders answer yes only with the switch on, and count each part they take off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelPauseIconTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void prepare() {
        reset();
    }

    @After
    public void restore() {
        reset();
    }

    private static void reset() {
        Settings.HIDE_REEL_PAUSE_ICON.resetToDefault();
        Settings.HIDE_REEL_LIKE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static boolean counted(String count, int times) {
        return HookStatus.report().toString().contains(count + " " + times);
    }

    private static boolean countedAnything() {
        String report = HookStatus.report().toString();
        return report.contains(ReelDeclutter.COUNT_PAUSE_ICON_HIDDEN) || report.contains(ReelDeclutter.COUNT_PAUSE_SOUND_HIDDEN);
    }

    @Test
    public void theSwitchTakesTheIconAndTheSoundButtonAndNothingElse() {
        assertFalse("starts off", Settings.HIDE_REEL_PAUSE_ICON.defaultValue);
        assertFalse("no restart", Settings.HIDE_REEL_PAUSE_ICON.rebootApp);
        assertTrue("in the Clean up Reels family", PatchFamily.REEL_DECLUTTER.switches.contains(Settings.HIDE_REEL_PAUSE_ICON));
        assertEquals("a switch of its own", 0, Settings.HIDE_REEL_PAUSE_ICON.getParentSettings().size());
        Settings.HIDE_REEL_PAUSE_ICON.save(true);

        assertTrue(ReelDeclutter.hidePauseIcon());
        assertTrue(ReelDeclutter.hidePauseSoundButton());
        assertTrue("the sound button has two places and both ask", ReelDeclutter.hidePauseSoundButton());
        assertFalse("the reel's buttons stay", ReelDeclutter.hideLikeButton());

        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_PAUSE_ICON_HIDDEN, 1));
        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_PAUSE_SOUND_HIDDEN, 2));
    }

    /** Off to start, paused and before the settings are read, a paused reel keeps its icon and nothing is counted. */
    @Test
    public void offPausedAndUnreadyKeepTheIconAndCountNothing() {
        assertFalse("off to start", ReelDeclutter.hidePauseIcon());
        assertFalse("off to start", ReelDeclutter.hidePauseSoundButton());
        assertFalse("off counts nothing", countedAnything());

        Settings.HIDE_REEL_PAUSE_ICON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ReelDeclutter.hidePauseIcon());
        assertFalse("paused", ReelDeclutter.hidePauseSoundButton());
        assertFalse("paused counts nothing", countedAnything());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("unready", ReelDeclutter.hidePauseIcon());
            assertFalse("unready", ReelDeclutter.hidePauseSoundButton());
        });
        assertFalse("unready counts nothing", countedAnything());

        assertTrue("and on again once resumed and ready", ReelDeclutter.hidePauseIcon());
        assertTrue(counted(ReelDeclutter.COUNT_PAUSE_ICON_HIDDEN, 1));
    }
}
