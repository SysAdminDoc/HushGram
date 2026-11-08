/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

/** What the reel's fast play label filter hands back (#552). */
@RunWith(RobolectricTestRunner.class)
public class FastPlayLabelTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object label = new Object();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_REEL_2X_POPUP.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_REEL_2X_POPUP.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** It starts off, and needs no restart: the label is built again each time a reel speeds up. */
    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.HIDE_REEL_2X_POPUP.resetToDefault();
        assertFalse(Settings.HIDE_REEL_2X_POPUP.get());
        assertFalse(Settings.HIDE_REEL_2X_POPUP.rebootApp);
    }

    /** On, the reel gets no label, and the hook counts as reached. */
    @Test
    public void onTheLabelIsGone() {
        assertNull(FastPlayLabel.label(label));
        assertNull(FastPlayLabel.label(null));
        assertTrue(HookStatus.missing(FamilyNames.HIDE_REEL_2X_POPUP).toString(),
                HookStatus.missing(FamilyNames.HIDE_REEL_2X_POPUP).isEmpty());
    }

    /** Off, paused or before the settings are read, the reel keeps Instagram's label. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsLabel() {
        Settings.HIDE_REEL_2X_POPUP.save(false);
        assertSame("off", label, FastPlayLabel.label(label));
        Settings.HIDE_REEL_2X_POPUP.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", label, FastPlayLabel.label(label));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame("no context", label, FastPlayLabel.label(label)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame("pause undecided", label, FastPlayLabel.label(label)));

        assertNull("back on", FastPlayLabel.label(label));
    }
}
