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

/** The Hide Follow on stories switch: under Stories after See who a story mentions, off to start, off while paused, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryFollowSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_STORY_FOLLOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_STORY_FOLLOW.resetToDefault();
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
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_MENTIONS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_FOLLOW.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.SHOW_STORY_MENTIONS.key));
    }
    /** Without the other story patches, Stories still opens for this switch alone. */
    @Test public void aloneItStillGetsStories() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_FOLLOW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_FOLLOW.key);
        assertNotNull(row);
        assertEquals("Stories", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.SHOW_STORY_MENTIONS.key));
    }
    @Test public void switchStartsOffUnderStoriesAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_MENTIONS, PatchFamily.STORY_FOLLOW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_FOLLOW.key);
        assertNotNull(row);
        assertEquals("Hide Follow on stories", row.getTitle().toString());
        assertEquals("Takes the Follow button off the top of stories from accounts you don't follow. "
                + "The name, time and menu stay.", row.getSummary().toString());
        PreferenceGroup stories = row.getParent();
        assertEquals("Stories", stories.getTitle().toString());
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int mentions = Arrays.asList(keys).indexOf(Settings.SHOW_STORY_MENTIONS.key);
        assertTrue(Arrays.toString(keys), mentions >= 0);
        assertEquals("right after See who a story mentions", Settings.HIDE_STORY_FOLLOW.key, keys[mentions + 1]);
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_STORY_FOLLOW.get());
        assertFalse("each bind reads the switch, so no restart prompt", Settings.HIDE_STORY_FOLLOW.rebootApp);
        assertEquals(Collections.singletonList(Settings.HIDE_STORY_FOLLOW), PatchFamily.STORY_FOLLOW.switches);
        assertEquals("Hide Follow on stories", PatchFamily.STORY_FOLLOW.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_STORY_FOLLOW.key));

        Settings.HIDE_STORY_FOLLOW.save(true);
        assertTrue(Settings.HIDE_STORY_FOLLOW.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_STORY_FOLLOW.get());
        assertTrue(Settings.HIDE_STORY_FOLLOW.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_STORY_FOLLOW.get());
    }
}
