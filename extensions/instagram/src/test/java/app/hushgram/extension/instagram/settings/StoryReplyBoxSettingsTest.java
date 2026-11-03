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
 * Hide the reply box on stories (#486): a Stories switch that starts off, right after Stop swipe up
 * to reply and before Show a story's exact time, kept in a backup, and off while paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryReplyBoxSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_STORY_REPLY_BOX.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_STORY_REPLY_BOX.resetToDefault();
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
    @Test public void theSwitchSitsUnderStoriesAfterSwipeReplyAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SWIPE_REPLY, PatchFamily.STORY_REPLY_BOX, PatchFamily.STORY_TIME));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_REPLY_BOX.key);
        assertNotNull(row);
        assertEquals("Hide the reply box on stories", row.getTitle().toString());
        assertEquals("Takes the Send message box off the bottom of other people's stories. The like and share "
                + "buttons next to it stay where they are.", row.getSummary().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.HIDE_STORY_REPLY_BOX.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.HIDE_STORY_REPLY_BOX.key);
        assertEquals(Arrays.toString(keys), Settings.STOP_STORY_SWIPE_REPLY.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.SHOW_STORY_TIME.key, keys[at + 1]);
        assertEquals("Hide the reply box on stories", PatchFamily.STORY_REPLY_BOX.patchName);
        assertEquals(Collections.singletonList(Settings.HIDE_STORY_REPLY_BOX), PatchFamily.STORY_REPLY_BOX.switches);

        ((SwitchPreference) row).setChecked(true);
        assertTrue(Settings.HIDE_STORY_REPLY_BOX.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_STORY_REPLY_BOX.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_REPLY_BOX));
        Settings.HIDE_STORY_REPLY_BOX.save(true);
        assertTrue(Settings.HIDE_STORY_REPLY_BOX.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_STORY_REPLY_BOX.get());
        assertTrue(Settings.HIDE_STORY_REPLY_BOX.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SWIPE_REPLY));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_REPLY_BOX.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_STORY_REPLY_BOX.key));
    }
}
