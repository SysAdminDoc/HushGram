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
import app.hushgram.extension.instagram.stories.UploadPhotoQuality;

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
        Settings.UPLOAD_PHOTO_QUALITY.resetToDefault();
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
        Settings.UPLOAD_PHOTO_QUALITY.resetToDefault();
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
        assertEquals(Arrays.toString(keys), Settings.UPLOAD_QUALITY_APPLIES_TO.key, keys[at + 1]);
        assertEquals(Arrays.toString(keys), Settings.UPLOAD_VIDEO_BITRATE.key, keys[at + 2]);
        assertEquals(Arrays.toString(keys), Settings.UPLOAD_VIDEO_RESOLUTION.key, keys[at + 3]);
        assertEquals(Arrays.toString(keys), Settings.UPLOAD_FRAME_RATE.key, keys[at + 4]);
        assertEquals(Arrays.toString(keys), Settings.UPLOAD_PHOTO_QUALITY.key, keys[at + 5]);
        assertEquals(Arrays.toString(keys), Settings.UPLOAD_PHOTO_SIZE.key, keys[at + 6]);
        assertEquals(Arrays.toString(keys), keys.length - 7, at);
        assertEquals("Upload stories at full quality", PatchFamily.STORY_UPLOAD_QUALITY.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.key));
    }
    @Test public void theNewChoicesListWhatTheyOffer() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_UPLOAD_QUALITY));
        assertEquals("[Instagram's own, 1080p, 1440p, 2160p]", entries(Settings.UPLOAD_VIDEO_RESOLUTION.key, "Video resolution"));
        assertEquals("[Instagram's own, 60 fps, 120 fps]", entries(Settings.UPLOAD_FRAME_RATE.key, "Video frame rate"));
        assertEquals("[Instagram's own, 90%, 95%, 100%]", entries(Settings.UPLOAD_PHOTO_QUALITY.key, "Photo quality"));
        assertEquals("[Instagram's own, 3072 px, 4096 px, 8192 px]", entries(Settings.UPLOAD_PHOTO_SIZE.key, "Photo size"));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.UPLOAD_VIDEO_RESOLUTION.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.UPLOAD_FRAME_RATE.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.UPLOAD_PHOTO_QUALITY.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.UPLOAD_PHOTO_SIZE.key));
    }
    /** A saved percent still opens the page and reads as itself, then follows a new pick. */
    @Test public void aSavedPhotoQualityPercentStillOpensThePage() throws Exception {
        Settings.UPLOAD_PHOTO_QUALITY.save(UploadPhotoQuality.Q100);
        open(EnumSet.of(PatchFamily.STORY_UPLOAD_QUALITY));
        assertNotNull("the page built its rows", page.getPreferenceScreen());
        android.preference.ListPreference row =
                (android.preference.ListPreference) page.getPreferenceScreen().findPreference(Settings.UPLOAD_PHOTO_QUALITY.key);
        assertNotNull(row);
        assertEquals("100%", row.getSummary().toString());
        row.setValue(UploadPhotoQuality.Q90.name());
        assertEquals("90%", row.getSummary().toString());
    }
    private String entries(String key, String title) {
        android.preference.ListPreference row = (android.preference.ListPreference) page.getPreferenceScreen().findPreference(key);
        assertNotNull(key, row);
        assertEquals(title, row.getTitle().toString());
        assertEquals("Instagram's own", row.getSummary().toString());
        return Arrays.toString(row.getEntries());
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
