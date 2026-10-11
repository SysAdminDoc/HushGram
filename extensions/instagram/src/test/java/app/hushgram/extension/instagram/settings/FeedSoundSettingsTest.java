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

/** Start feed videos with sound: a Feed row that starts off, comes after Show like counts the poster hid, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedSoundSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.START_FEED_VIDEOS_WITH_SOUND.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.START_FEED_VIDEOS_WITH_SOUND.resetToDefault();
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
    @Test public void theSwitchIsUnderFeedAfterTheHiddenLikeCountsAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.HIDDEN_LIKE_COUNTS, PatchFamily.FEED_SOUND));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.START_FEED_VIDEOS_WITH_SOUND.key);
        assertNotNull(row);
        assertEquals("Start feed videos with sound", row.getTitle().toString());
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.START_FEED_VIDEOS_WITH_SOUND.get());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), Settings.SHOW_HIDDEN_LIKE_COUNTS.key,
                keys[Arrays.asList(keys).indexOf(Settings.START_FEED_VIDEOS_WITH_SOUND.key) - 1]);
        assertEquals("Start feed videos with sound", PatchFamily.FEED_SOUND.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.START_FEED_VIDEOS_WITH_SOUND.key));
    }
    @Test public void theSwitchAloneStillMakesTheFeedSection() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SOUND));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.START_FEED_VIDEOS_WITH_SOUND.key);
        assertNotNull(row);
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SOUND));
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        assertTrue(Settings.START_FEED_VIDEOS_WITH_SOUND.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.START_FEED_VIDEOS_WITH_SOUND.get());
        assertTrue(Settings.START_FEED_VIDEOS_WITH_SOUND.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.HIDDEN_LIKE_COUNTS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.START_FEED_VIDEOS_WITH_SOUND.key));
    }
}
