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
import app.hushgram.extension.instagram.stories.StoriesTray;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Keep the Stories tray in the feed (#357): a Stories row that starts off, follows Hide the Stories tray, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class KeepTrayInFeedSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_TRAY_IN_FEED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_TRAY_IN_FEED.resetToDefault();
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
    @Test public void theSwitchIsUnderStoriesAfterTheTrayAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORIES_TRAY));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.KEEP_TRAY_IN_FEED.key);
        assertNotNull(row);
        assertEquals("Keep the Stories tray in the feed", row.getTitle().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.KEEP_TRAY_IN_FEED.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), Settings.HIDE_STORIES_TRAY.key, keys[Arrays.asList(keys).indexOf(Settings.KEEP_TRAY_IN_FEED.key) - 1]);
        assertTrue(PatchFamily.STORIES_TRAY.switches.contains(Settings.KEEP_TRAY_IN_FEED));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_TRAY_IN_FEED.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORIES_TRAY));
        Settings.KEEP_TRAY_IN_FEED.save(true);
        assertTrue(StoriesTray.keepTrayOutOfOverlay());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.KEEP_TRAY_IN_FEED.get());
        assertFalse(StoriesTray.keepTrayOutOfOverlay());
        assertTrue(Settings.KEEP_TRAY_IN_FEED.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_LOOP));
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_TRAY_IN_FEED.key));
    }
}
