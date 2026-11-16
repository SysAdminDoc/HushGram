/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.widget.Toast;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the reel's fast play label filter hands back (#552), and when the speed lock toast shows (#702). */
@RunWith(RobolectricTestRunner.class)
public class FastPlayLabelTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object label = new Object();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_REEL_2X_POPUP.save(true);
        Settings.HIDE_REEL_LOCK_MESSAGE.resetToDefault();
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After
    public void restore() {
        Settings.HIDE_REEL_2X_POPUP.resetToDefault();
        Settings.HIDE_REEL_LOCK_MESSAGE.resetToDefault();
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

    /** The lock message switch starts off and needs no restart: each lock shows its toast anew (#702). */
    @Test
    public void theLockMessageSwitchStartsOffAndNeedsNoRestart() {
        assertFalse(Settings.HIDE_REEL_LOCK_MESSAGE.get());
        assertFalse(Settings.HIDE_REEL_LOCK_MESSAGE.rebootApp);
    }

    /** On, the lock toast isn't shown, the hook counts as reached and the hidden toast is counted. */
    @Test
    public void onTheLockMessageIsKeptOff() {
        Settings.HIDE_REEL_LOCK_MESSAGE.save(true);
        FastPlayLabel.lockToast(toast());
        FastPlayLabel.lockToast(null);
        assertEquals("toasts shown", 0, ShadowToast.shownToastCount());
        assertTrue(HookStatus.missing(FamilyNames.HIDE_REEL_2X_POPUP).toString(),
                HookStatus.missing(FamilyNames.HIDE_REEL_2X_POPUP).isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(FastPlayLabel.LOCK_HIDDEN));
    }

    /** Off, paused or before the settings are read, Instagram's lock toast shows; the 2x popup switch alone doesn't hide it. */
    @Test
    public void offPausedAndUnreadyShowInstagramsLockMessage() {
        Toast off = toast();
        FastPlayLabel.lockToast(off);
        assertSame("off, with the 2x popup switch on", off, ShadowToast.getLatestToast());

        Settings.HIDE_REEL_LOCK_MESSAGE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Toast paused = toast();
        FastPlayLabel.lockToast(paused);
        assertSame("paused", paused, ShadowToast.getLatestToast());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        Toast noContext = toast();
        SettingsContextRule.withoutContext(() -> FastPlayLabel.lockToast(noContext));
        assertSame("no context", noContext, ShadowToast.getLatestToast());
        Toast undecided = toast();
        SettingsContextRule.beforeThePauseIsDecided(() -> FastPlayLabel.lockToast(undecided));
        assertSame("pause undecided", undecided, ShadowToast.getLatestToast());

        assertEquals("toasts shown", 4, ShadowToast.shownToastCount());
        FastPlayLabel.lockToast(toast());
        assertEquals("back on", 4, ShadowToast.shownToastCount());
    }

    private static Toast toast() {
        return Toast.makeText(RuntimeEnvironment.getApplication(), "Locked at 2x speed", Toast.LENGTH_SHORT);
    }
}
