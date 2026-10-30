/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Arrays;
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

/**
 * Hide the Threads button's switches: Threads, Create (#254) and Share profile (#253), under Profiles
 * after Hide highlights, off to start, off while paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ThreadsButtonSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_THREADS_BUTTON.resetToDefault();
        Settings.HIDE_CREATE_BUTTON.resetToDefault();
        Settings.HIDE_SHARE_PROFILE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_THREADS_BUTTON.resetToDefault();
        Settings.HIDE_CREATE_BUTTON.resetToDefault();
        Settings.HIDE_SHARE_PROFILE_BUTTON.resetToDefault();
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
    @Test public void missingPatchHasNoThreadsSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_HIGHLIGHTS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_THREADS_BUTTON.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_HIGHLIGHTS.key));
    }
    /** Without the other profile patches, Profiles still opens for Hide the Threads button alone. */
    @Test public void threadsAloneStillGetsProfiles() throws Exception {
        open(EnumSet.of(PatchFamily.THREADS_BUTTON));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_THREADS_BUTTON.key);
        assertNotNull(row);
        assertEquals("Profiles", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_HIGHLIGHTS.key));
    }
    @Test public void threadsSwitchStartsOffUnderProfilesPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_HIGHLIGHTS, PatchFamily.THREADS_BUTTON));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_THREADS_BUTTON.key);
        assertNotNull(row);
        assertEquals("Hide the Threads button", row.getTitle().toString());
        assertEquals("Takes the Threads button off the top of profiles, yours included. The menu and the other "
                + "buttons stay where they were.", row.getSummary().toString());
        PreferenceGroup profiles = row.getParent();
        assertEquals("Profiles", profiles.getTitle().toString());
        String[] keys = new String[profiles.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = profiles.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.HIDE_HIGHLIGHTS.key, Settings.HIDE_THREADS_BUTTON.key,
                Settings.HIDE_CREATE_BUTTON.key, Settings.HIDE_SHARE_PROFILE_BUTTON.key), Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_THREADS_BUTTON.get());
        assertEquals(Arrays.asList(Settings.HIDE_THREADS_BUTTON, Settings.HIDE_CREATE_BUTTON,
                Settings.HIDE_SHARE_PROFILE_BUTTON), PatchFamily.THREADS_BUTTON.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_THREADS_BUTTON.key));

        Settings.HIDE_THREADS_BUTTON.save(true);
        assertTrue(Settings.HIDE_THREADS_BUTTON.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_THREADS_BUTTON.get());
        assertTrue(Settings.HIDE_THREADS_BUTTON.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_THREADS_BUTTON.get());
    }
    @Test public void createAndShareSwitchesStartOffAndHonorPause() throws Exception {
        open(EnumSet.of(PatchFamily.THREADS_BUTTON));
        SwitchPreference create = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CREATE_BUTTON.key);
        SwitchPreference share = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_SHARE_PROFILE_BUTTON.key);
        assertEquals("Hide the Create button", create.getTitle().toString());
        assertEquals("Takes the plus for new posts off the top of your own profile. The other buttons stay "
                + "where they were.", create.getSummary().toString());
        assertEquals("Hide Share profile", share.getTitle().toString());
        assertEquals("Takes the Share profile button off your profile, and Edit profile stretches to fill the "
                + "space.", share.getSummary().toString());
        assertFalse(create.isChecked());
        assertFalse(share.isChecked());
        for (app.hushgram.extension.shared.settings.BooleanSetting setting
                : Arrays.asList(Settings.HIDE_CREATE_BUTTON, Settings.HIDE_SHARE_PROFILE_BUTTON)) {
            assertEquals(Boolean.FALSE, setting.defaultValue);
            assertTrue(ConfigurationBackup.eligible().containsKey(setting.key));
            setting.save(true);
            assertTrue(setting.get());
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertFalse(setting.get());
            assertTrue(setting.savedValue());
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
        }
    }
}
