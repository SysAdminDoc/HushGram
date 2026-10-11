/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide Notes on profile pictures: a Profiles row that starts off, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ProfileNotesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.HIDE_PROFILE_NOTES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_PROFILE_NOTES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void theSwitchIsUnderProfilesAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_NOTES));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_PROFILE_NOTES.key);
        assertNotNull(row);
        assertEquals("Hide Notes on profile pictures", row.getTitle().toString());
        assertEquals("Profiles", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.HIDE_PROFILE_NOTES.get());
        assertTrue(PatchFamily.PROFILE_NOTES.switches.contains(Settings.HIDE_PROFILE_NOTES));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_PROFILE_NOTES.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_NOTES));
        Settings.HIDE_PROFILE_NOTES.save(true);
        assertTrue(Settings.HIDE_PROFILE_NOTES.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_PROFILE_NOTES.get());
        assertTrue(Settings.HIDE_PROFILE_NOTES.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.THREADS_BUTTON));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_PROFILE_NOTES.key));
    }
}
