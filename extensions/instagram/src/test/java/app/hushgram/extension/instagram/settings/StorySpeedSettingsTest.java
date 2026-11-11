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
 * Speed button on stories (#469): a Stories switch that starts off, after Seek bar on stories and
 * before Stop Story auto-advance, kept in a backup, and off while paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StorySpeedSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.STORY_SPEED_BUTTON.resetToDefault();
        Settings.STORY_SPEED_IN_MENU.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.STORY_SPEED_BUTTON.resetToDefault();
        Settings.STORY_SPEED_IN_MENU.resetToDefault();
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
        open(EnumSet.of(PatchFamily.STORY_SEEK_BAR, PatchFamily.STORY_SPEED, PatchFamily.STORY_AUTO_ADVANCE));
        Preference row = page.getPreferenceScreen().findPreference(Settings.STORY_SPEED_BUTTON.key);
        assertNotNull(row);
        assertEquals("Speed button on stories", row.getTitle().toString());
        assertEquals("Puts a speed button at the top of a video story, beside the three dots. Tap it for 1.5x, "
                + "again for 2x and once more for 1x. Later video stories keep the speed until you change it or "
                + "restart Instagram. Ads play at normal speed.", row.getSummary().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.STORY_SPEED_BUTTON.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.STORY_SPEED_BUTTON.key);
        assertEquals(Arrays.toString(keys), Settings.STORY_SEEK_BAR_POSITION.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.STORY_SEEK_BAR_LOOK.key, keys[at - 2]);
        assertEquals(Arrays.toString(keys), Settings.STORY_SEEK_BAR.key, keys[at - 3]);
        assertEquals(Arrays.toString(keys), Settings.BLOCK_STORY_AUTO_ADVANCE.key, keys[at + 1]);

        ((SwitchPreference) row).setChecked(true);
        assertTrue(Settings.STORY_SPEED_BUTTON.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_SPEED_BUTTON.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SPEED));
        Settings.STORY_SPEED_BUTTON.save(true);
        assertTrue(Settings.STORY_SPEED_BUTTON.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.STORY_SPEED_BUTTON.get());
        assertTrue(Settings.STORY_SPEED_BUTTON.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SEEK_BAR));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_SPEED_BUTTON.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_SPEED_BUTTON.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_SPEED_IN_MENU.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_SPEED_IN_MENU.key));
    }
    /** #621: Speed in the story menu sits right after the speed button, starts off, and needs Download any story's menu. */
    @Test public void speedInTheStoryMenuFollowsTheButtonAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SPEED, PatchFamily.STORY_DOWNLOAD, PatchFamily.STORY_AUTO_ADVANCE));
        Preference row = page.getPreferenceScreen().findPreference(Settings.STORY_SPEED_IN_MENU.key);
        assertNotNull(row);
        assertEquals("Speed in the story menu", row.getTitle().toString());
        assertEquals("Adds Playback speed to the menu a video story's three dots open, with the same 1x, 1.5x "
                + "and 2x, and keeps the speed button off the story. Later video stories keep the speed you pick "
                + "until you change it or restart Instagram.", row.getSummary().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertEquals(Boolean.FALSE, Settings.STORY_SPEED_IN_MENU.defaultValue);
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.STORY_SPEED_IN_MENU.key);
        assertEquals(Arrays.toString(keys), Settings.STORY_SPEED_BUTTON.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.BLOCK_STORY_AUTO_ADVANCE.key, keys[at + 1]);

        ((SwitchPreference) row).setChecked(true);
        assertTrue(Settings.STORY_SPEED_IN_MENU.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_SPEED_IN_MENU.key));
    }
    @Test public void withoutDownloadAnyStoryThereIsNoMenuRow() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SPEED));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.STORY_SPEED_BUTTON.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_SPEED_IN_MENU.key));
    }
}
