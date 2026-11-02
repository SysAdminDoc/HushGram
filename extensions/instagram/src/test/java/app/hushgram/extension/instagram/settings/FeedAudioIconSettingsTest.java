/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
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
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Hide the audio icon on posts switch: under Feed, off to start, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedAudioIconSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_FEED_AUDIO_ICON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_FEED_AUDIO_ICON.resetToDefault();
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
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_AUDIO_ICON.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HOME.key));
    }
    /** Without the other Feed patches, Feed still opens for this switch alone. */
    @Test public void aloneItStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_AUDIO_ICON));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_AUDIO_ICON.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffUnderFeedAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS, PatchFamily.HIDE_CAPTIONS, PatchFamily.FEED_AUDIO_ICON));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_AUDIO_ICON.key);
        assertNotNull(row);
        assertEquals("Hide the audio icon on posts", row.getTitle().toString());
        assertEquals("Takes the speaker icon off videos in your feed. Tapping the video still turns the sound on or off. "
                + "Posts already on screen change when they load again.", row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        assertNotNull(feed.findPreference(Settings.HIDE_CAPTIONS.key));
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_FEED_AUDIO_ICON.get());
        assertFalse("each post state reads the switch, so no restart prompt", Settings.HIDE_FEED_AUDIO_ICON.rebootApp);
        assertEquals(Collections.singletonList(Settings.HIDE_FEED_AUDIO_ICON), PatchFamily.FEED_AUDIO_ICON.switches);
        assertEquals("Hide the audio icon on posts", PatchFamily.FEED_AUDIO_ICON.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_FEED_AUDIO_ICON.key));
    }
}
