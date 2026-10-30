/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
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
 * Seek bar on stories (#355): a Stories switch that starts off, after Thicker story progress bar's
 * rows and before Stop Story auto-advance, kept in a backup, and off while paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StorySeekBarSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.STORY_SEEK_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.STORY_SEEK_BAR.resetToDefault();
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
    @Test public void theSwitchSitsUnderStoriesAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_PROGRESS_BAR, PatchFamily.STORY_SEEK_BAR, PatchFamily.STORY_AUTO_ADVANCE));
        Preference row = page.getPreferenceScreen().findPreference(Settings.STORY_SEEK_BAR.key);
        assertNotNull(row);
        assertEquals("Seek bar on stories", row.getTitle().toString());
        assertEquals("Shows a bar near the bottom of a video story. Drag it to jump to any point in the video. "
                + "Photos don't get one.", row.getSummary().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.STORY_SEEK_BAR.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.STORY_SEEK_BAR.key);
        assertEquals(Arrays.toString(keys), Settings.STORY_BAR_THICKNESS.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.BLOCK_STORY_AUTO_ADVANCE.key, keys[at + 1]);

        ((SwitchPreference) row).setChecked(true);
        assertTrue(Settings.STORY_SEEK_BAR.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_SEEK_BAR.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SEEK_BAR));
        Settings.STORY_SEEK_BAR.save(true);
        assertTrue(Settings.STORY_SEEK_BAR.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.STORY_SEEK_BAR.get());
        assertTrue(Settings.STORY_SEEK_BAR.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_PROGRESS_BAR));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_SEEK_BAR.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_SEEK_BAR.key));
    }
}
