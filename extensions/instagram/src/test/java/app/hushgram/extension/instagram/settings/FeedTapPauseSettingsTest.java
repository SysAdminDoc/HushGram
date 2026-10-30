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

/** Tap a feed video to pause it: a Feed row that starts off, comes after Start feed videos with sound, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedTapPauseSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.TAP_FEED_VIDEO_TO_PAUSE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.TAP_FEED_VIDEO_TO_PAUSE.resetToDefault();
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
    @Test public void theSwitchIsUnderFeedBetweenFeedSoundAndTheZoomAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SOUND, PatchFamily.FEED_TAP_PAUSE, PatchFamily.LONG_PRESS_ZOOM));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.TAP_FEED_VIDEO_TO_PAUSE.key);
        assertNotNull(row);
        assertEquals("Tap a feed video to pause it", row.getTitle().toString());
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.TAP_FEED_VIDEO_TO_PAUSE.get());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.TAP_FEED_VIDEO_TO_PAUSE.key);
        assertEquals(Arrays.toString(keys), Settings.START_FEED_VIDEOS_WITH_SOUND.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.LONG_PRESS_TO_ZOOM.key, keys[at + 1]);
        assertEquals("Tap a feed video to pause it", PatchFamily.FEED_TAP_PAUSE.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.TAP_FEED_VIDEO_TO_PAUSE.key));
    }
    @Test public void theSwitchAloneStillMakesTheFeedSection() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_TAP_PAUSE));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.TAP_FEED_VIDEO_TO_PAUSE.key);
        assertNotNull(row);
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_TAP_PAUSE));
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue(Settings.TAP_FEED_VIDEO_TO_PAUSE.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.TAP_FEED_VIDEO_TO_PAUSE.get());
        assertTrue(Settings.TAP_FEED_VIDEO_TO_PAUSE.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SOUND));
        assertNull(page.getPreferenceScreen().findPreference(Settings.TAP_FEED_VIDEO_TO_PAUSE.key));
    }
}
