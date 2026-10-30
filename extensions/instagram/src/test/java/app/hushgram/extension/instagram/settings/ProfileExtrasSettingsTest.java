/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
import java.util.EnumSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** #234: the Profile extras button's switch sits with Save profile picture, off to start, and goes with the patch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ProfileExtrasSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.PROFILE_EXTRAS_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.PROFILE_EXTRAS_BUTTON.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.PROFILE_PICTURE) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.PROFILE_EXTRAS_BUTTON.key));
    }

    @Test public void theSwitchSitsWithSaveProfilePictureAndStartsOff() throws Exception {
        open(true);
        SwitchPreference extras = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PROFILE_EXTRAS_BUTTON.key);
        SwitchPreference save = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SAVE_PROFILE_PICTURES.key);
        assertNotNull(extras);
        assertEquals("Profile extras button", extras.getTitle().toString());
        assertEquals("Puts a button next to the three dots on profiles that opens HushGram's profile actions. The rows in the three-dot menu stay too.",
                extras.getSummary().toString());
        assertSame(save.getParent(), extras.getParent());
        assertFalse(extras.isChecked());
        assertFalse(Settings.PROFILE_EXTRAS_BUTTON.get());
        assertTrue(PatchFamily.PROFILE_PICTURE.switches.contains(Settings.PROFILE_EXTRAS_BUTTON));
    }

    @Test public void theSwitchIsInTheSettingsBackup() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PROFILE_EXTRAS_BUTTON.key));
    }
}
