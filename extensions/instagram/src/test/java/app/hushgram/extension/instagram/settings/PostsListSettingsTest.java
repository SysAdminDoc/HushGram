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
import java.util.Collections;
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

/** The profile posts list switch: last under Profiles, off to start, off while paused, and no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class PostsListSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.PROFILE_POSTS_LIST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.PROFILE_POSTS_LIST.resetToDefault();
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
    @Test public void missingPatchHasNoPostsListSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_HIGHLIGHTS, PatchFamily.THREADS_BUTTON));
        assertNull(page.getPreferenceScreen().findPreference(Settings.PROFILE_POSTS_LIST.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_THREADS_BUTTON.key));
    }
    /** Without the other profile patches, Profiles still opens for this switch alone. */
    @Test public void postsListAloneStillGetsProfiles() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_POSTS_LIST));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PROFILE_POSTS_LIST.key);
        assertNotNull(row);
        assertEquals("Profiles", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_THREADS_BUTTON.key));
    }
    @Test public void postsListSwitchStartsOffLastUnderProfilesAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_HIGHLIGHTS, PatchFamily.SAVED_ON_PROFILE, PatchFamily.THREADS_BUTTON,
                PatchFamily.PROFILE_POSTS_LIST));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PROFILE_POSTS_LIST.key);
        assertNotNull(row);
        assertEquals("Show profile posts as a list", row.getTitle().toString());
        assertEquals("Opening someone's profile takes you on to their posts as a scrolling list of full posts. Go Back "
                + "for the grid. Your own profile keeps its grid.", row.getSummary().toString());
        PreferenceGroup profiles = row.getParent();
        assertEquals("Profiles", profiles.getTitle().toString());
        String[] keys = new String[profiles.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = profiles.getPreference(i).getKey();
        int threads = Arrays.asList(keys).indexOf(Settings.HIDE_THREADS_BUTTON.key);
        assertTrue(Arrays.toString(keys), threads >= 0);
        assertEquals("right after Hide the Threads button", Settings.PROFILE_POSTS_LIST.key, keys[threads + 1]);
        assertFalse(row.isChecked());
        assertFalse(Settings.PROFILE_POSTS_LIST.get());
        assertFalse("each posts tab reads the switch, so no restart prompt", Settings.PROFILE_POSTS_LIST.rebootApp);
        assertEquals(Collections.singletonList(Settings.PROFILE_POSTS_LIST), PatchFamily.PROFILE_POSTS_LIST.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PROFILE_POSTS_LIST.key));

        Settings.PROFILE_POSTS_LIST.save(true);
        assertTrue(Settings.PROFILE_POSTS_LIST.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.PROFILE_POSTS_LIST.get());
        assertTrue(Settings.PROFILE_POSTS_LIST.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.PROFILE_POSTS_LIST.get());
    }
}
