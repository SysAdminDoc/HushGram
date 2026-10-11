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

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Hide Notes on profile pictures answers where Instagram reads the flag that turns Notes off on profiles. */
@RunWith(RobolectricTestRunner.class)
public class ProfileNotesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_PROFILE_NOTES.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_PROFILE_NOTES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The switch starts off, and then Instagram's own answer goes through whichever it is. */
    @Test
    public void theSwitchStartsOffAndInstagramsAnswerStands() {
        assertFalse(Settings.HIDE_PROFILE_NOTES.get());
        assertTrue(ProfileNotes.consumptionDisabled(1));
        assertFalse(ProfileNotes.consumptionDisabled(0));
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_NOTES).toString(), HookStatus.missing(FamilyNames.PROFILE_NOTES).isEmpty());
    }

    /** On, a no becomes a yes and is counted, and a yes stays a yes. */
    @Test
    public void onNotesAreTurnedOff() {
        Settings.HIDE_PROFILE_NOTES.save(true);
        assertTrue("the server said no", ProfileNotes.consumptionDisabled(0));
        assertTrue("the server said yes", ProfileNotes.consumptionDisabled(1));
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_NOTES).toString(), HookStatus.missing(FamilyNames.PROFILE_NOTES).isEmpty());
    }

    /** Paused, or before the settings are read, the flag keeps Instagram's answer. */
    @Test
    public void pausedAndUnreadyKeepInstagramsAnswer() {
        Settings.HIDE_PROFILE_NOTES.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ProfileNotes.consumptionDisabled(0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", ProfileNotes.consumptionDisabled(0)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", ProfileNotes.consumptionDisabled(0)));

        assertTrue("back on", ProfileNotes.consumptionDisabled(0));
    }
}
