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

/**
 * Keep story buttons below the story (#598): a Stories switch that starts on, right after the reply
 * box's family (its last row, Hide the Share button on stories) and before Show a story's exact
 * time, kept in a backup, and off while paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryButtonsRoomSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_STORY_BUTTONS_BELOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_STORY_BUTTONS_BELOW.resetToDefault();
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
    @Test public void theSwitchSitsUnderStoriesAfterTheReplyBoxAndStartsOn() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_REPLY_BOX, PatchFamily.STORY_BUTTONS_ROOM, PatchFamily.STORY_TIME));
        Preference row = page.getPreferenceScreen().findPreference(Settings.KEEP_STORY_BUTTONS_BELOW.key);
        assertNotNull(row);
        assertEquals("Keep story buttons below the story", row.getTitle().toString());
        assertEquals("With a large font or display size, the reply box and buttons at the bottom of a story sit "
                + "in the space under it instead of covering the story.", row.getSummary().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertTrue(((SwitchPreference) row).isChecked());
        assertTrue(Settings.KEEP_STORY_BUTTONS_BELOW.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.KEEP_STORY_BUTTONS_BELOW.key);
        assertEquals(Arrays.toString(keys), Settings.HIDE_STORY_SHARE_BUTTON.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.SHOW_STORY_TIME.key, keys[at + 1]);
        assertEquals("Keep story buttons below the story", PatchFamily.STORY_BUTTONS_ROOM.patchName);
        assertEquals(Collections.singletonList(Settings.KEEP_STORY_BUTTONS_BELOW), PatchFamily.STORY_BUTTONS_ROOM.switches);

        ((SwitchPreference) row).setChecked(false);
        assertFalse(Settings.KEEP_STORY_BUTTONS_BELOW.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_STORY_BUTTONS_BELOW.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_BUTTONS_ROOM));
        assertTrue(Settings.KEEP_STORY_BUTTONS_BELOW.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.KEEP_STORY_BUTTONS_BELOW.get());
        assertTrue(Settings.KEEP_STORY_BUTTONS_BELOW.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_REPLY_BOX));
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_STORY_BUTTONS_BELOW.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.KEEP_STORY_BUTTONS_BELOW.key));
    }
}
