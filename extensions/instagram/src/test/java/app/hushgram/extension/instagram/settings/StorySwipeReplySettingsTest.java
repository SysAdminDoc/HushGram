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

/** Stop swipe up to reply: a Stories row that starts off, follows Start stories with sound, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StorySwipeReplySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.STOP_STORY_SWIPE_REPLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.STOP_STORY_SWIPE_REPLY.resetToDefault();
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
    @Test public void theSwitchIsUnderStoriesAfterSoundAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SOUND, PatchFamily.STORY_SWIPE_REPLY));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STOP_STORY_SWIPE_REPLY.key);
        assertNotNull(row);
        assertEquals("Stop swipe up to reply", row.getTitle().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.STOP_STORY_SWIPE_REPLY.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), Settings.START_STORIES_WITH_SOUND.key,
                keys[Arrays.asList(keys).indexOf(Settings.STOP_STORY_SWIPE_REPLY.key) - 1]);
        assertEquals("Stop swipe up to reply", PatchFamily.STORY_SWIPE_REPLY.patchName);
        assertEquals(Collections.singletonList(Settings.STOP_STORY_SWIPE_REPLY), PatchFamily.STORY_SWIPE_REPLY.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STOP_STORY_SWIPE_REPLY.key));
    }
    @Test public void aSavedSwitchPersistsAndAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SWIPE_REPLY));
        Settings.STOP_STORY_SWIPE_REPLY.save(true);
        assertTrue(Settings.STOP_STORY_SWIPE_REPLY.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.STOP_STORY_SWIPE_REPLY.get());
        assertTrue(Settings.STOP_STORY_SWIPE_REPLY.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.STOP_STORY_SWIPE_REPLY.get());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SOUND));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STOP_STORY_SWIPE_REPLY.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.START_STORIES_WITH_SOUND.key));
    }
}
