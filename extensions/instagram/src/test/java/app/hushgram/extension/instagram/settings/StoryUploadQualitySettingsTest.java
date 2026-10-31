/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
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

/** Upload stories at full quality: the last Stories row, after Download on stories, that starts off, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryUploadQualitySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.resetToDefault();
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
    @Test public void theSwitchIsUnderStoriesAfterDownloadAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SOUND, PatchFamily.STORY_DOWNLOAD, PatchFamily.STORY_UPLOAD_QUALITY));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.key);
        assertNotNull(row);
        assertEquals("Upload stories at full quality", row.getTitle().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.key);
        assertEquals(Arrays.toString(keys), Settings.TEXT_BUTTON_ON_STORIES.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.DOWNLOAD_BUTTON_ON_STORIES.key, keys[at - 2]);
        assertEquals(Arrays.toString(keys), Settings.DOWNLOAD_STORIES.key, keys[at - 3]);
        assertEquals(Arrays.toString(keys), keys.length - 1, at);
        assertEquals("Upload stories at full quality", PatchFamily.STORY_UPLOAD_QUALITY.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_UPLOAD_QUALITY));
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        assertTrue(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.get());
        assertTrue(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SOUND, PatchFamily.STORY_DOWNLOAD));
        assertNull(page.getPreferenceScreen().findPreference(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.key));
    }
}
