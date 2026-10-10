/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.ListPreference;
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
import app.hushgram.extension.instagram.reels.ReelTapChoice;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The reel tap choice: under Playback right after Tap to play's choice, Instagram's own to start, kept while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelTapAndVolumeSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.REEL_TAP_CHOICE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.REEL_TAP_CHOICE.resetToDefault();
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
    @Test public void missingPatchHasNoTapChoice() throws Exception {
        open(EnumSet.of(PatchFamily.TAP_TO_PLAY));
        assertNull(page.getPreferenceScreen().findPreference(Settings.REEL_TAP_CHOICE.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.TAP_TO_PLAY_SCOPE.key));
    }
    /** Without Tap to play or any other Playback patch, Playback still opens for this choice alone. */
    @Test public void theChoiceAloneStillGetsPlayback() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_TAP_AND_VOLUME));
        ListPreference row = (ListPreference) page.getPreferenceScreen().findPreference(Settings.REEL_TAP_CHOICE.key);
        assertNotNull(row);
        assertEquals("Playback", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.TAP_TO_PLAY.key));
    }
    @Test public void choiceStartsAtInstagramsDefaultRightAfterTapToPlay() throws Exception {
        open(EnumSet.of(PatchFamily.TAP_TO_PLAY, PatchFamily.REEL_TAP_AND_VOLUME, PatchFamily.RESUME_LONG_VIDEOS));
        ListPreference row = (ListPreference) page.getPreferenceScreen().findPreference(Settings.REEL_TAP_CHOICE.key);
        assertNotNull(row);
        assertEquals("A tap on a reel", row.getTitle().toString());
        assertEquals("Instagram's default", row.getEntries()[0].toString());
        assertEquals("Pause", row.getEntries()[1].toString());
        assertEquals("Mute", row.getEntries()[2].toString());
        assertEquals(ReelTapChoice.DEFAULT.name(), row.getValue());
        assertEquals("A tap on a reel does what Instagram does.", row.getSummary().toString());
        PreferenceGroup playback = row.getParent();
        String[] keys = new String[playback.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = playback.getPreference(i).getKey();
        int scope = Arrays.asList(keys).indexOf(Settings.TAP_TO_PLAY_SCOPE.key);
        assertTrue(Arrays.toString(keys), scope >= 0);
        assertEquals("right after Where videos wait", Settings.REEL_TAP_CHOICE.key, keys[scope + 1]);
        assertFalse("it takes effect on the next tap, so no restart prompt", Settings.REEL_TAP_CHOICE.rebootApp);
        assertEquals("Control taps and volume on Reels", PatchFamily.REEL_TAP_AND_VOLUME.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_TAP_CHOICE.key));
    }
    @Test public void choosingMuteChangesTheSummaryAndPauseKeepsTheSavedChoice() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_TAP_AND_VOLUME));
        ListPreference row = (ListPreference) page.getPreferenceScreen().findPreference(Settings.REEL_TAP_CHOICE.key);
        row.setValue(ReelTapChoice.MUTE.name());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("A tap on a playing reel turns its sound"));
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.MUTE);
        assertEquals(ReelTapChoice.MUTE, Settings.REEL_TAP_CHOICE.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(ReelTapChoice.DEFAULT, Settings.REEL_TAP_CHOICE.get());
        assertEquals(ReelTapChoice.MUTE, Settings.REEL_TAP_CHOICE.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(ReelTapChoice.MUTE, Settings.REEL_TAP_CHOICE.get());
    }
}
