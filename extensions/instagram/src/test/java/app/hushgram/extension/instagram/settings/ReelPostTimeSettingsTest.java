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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The Show a reel's exact time switch and its Show seconds in a reel's time: under Reels after Keep
 * the reel speed, both off to start, the seconds waiting for the switch, off while paused, and gone
 * without the patch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelPostTimeSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SHOW_REEL_POST_TIME.resetToDefault();
        Settings.REEL_POST_TIME_SECONDS.resetToDefault();
        Settings.REEL_POST_TIME_AGO.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SHOW_REEL_POST_TIME.resetToDefault();
        Settings.REEL_POST_TIME_SECONDS.resetToDefault();
        Settings.REEL_POST_TIME_AGO.resetToDefault();
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
    @Test public void missingPatchHasNoReelTimeSwitches() throws Exception {
        open(EnumSet.of(PatchFamily.KEEP_REEL_SPEED));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SHOW_REEL_POST_TIME.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.REEL_POST_TIME_SECONDS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_SPEED.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.SHOW_REEL_POST_TIME.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.REEL_POST_TIME_SECONDS.key));
    }
    /** Without the other Reels patches, Reels still opens for these two rows alone. */
    @Test public void reelTimeAloneStillGetsReels() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_POST_TIME));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_REEL_POST_TIME.key);
        assertNotNull(row);
        PreferenceGroup reels = row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        assertEquals(3, reels.getPreferenceCount());
        assertEquals(Settings.REEL_POST_TIME_SECONDS.key, reels.getPreference(1).getKey());
        assertEquals(Settings.REEL_POST_TIME_AGO.key, reels.getPreference(2).getKey());
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_SPEED.key));
    }
    @Test public void reelTimeSwitchStartsOffUnderReelsAfterTheSpeedAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.KEEP_REEL_SPEED, PatchFamily.REEL_POST_TIME, PatchFamily.REEL_SEEK_BAR));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_REEL_POST_TIME.key);
        assertNotNull(row);
        assertEquals("Show a reel's exact time", row.getTitle().toString());
        assertEquals("Shows the date and time a reel was posted under the author's name, like Oct 2, 3:45 PM. "
                + "A change shows from the next reel.", row.getSummary().toString());
        PreferenceGroup reels = row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        String[] keys = new String[reels.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = reels.getPreference(i).getKey();
        int speed = Arrays.asList(keys).indexOf(Settings.KEEP_REEL_SPEED.key);
        assertTrue(Arrays.toString(keys), speed >= 0);
        assertEquals("right after Keep the reel speed", Settings.SHOW_REEL_POST_TIME.key, keys[speed + 1]);
        assertEquals("then its seconds", Settings.REEL_POST_TIME_SECONDS.key, keys[speed + 2]);
        assertEquals("then how long ago", Settings.REEL_POST_TIME_AGO.key, keys[speed + 3]);
        assertEquals("then the seek bar", Settings.REEL_SEEK_BAR.key, keys[speed + 4]);
        assertFalse(row.isChecked());
        assertFalse(Settings.SHOW_REEL_POST_TIME.get());
        assertFalse("each reel's title reads the switch as it's drawn, so no restart prompt", Settings.SHOW_REEL_POST_TIME.rebootApp);
        assertEquals(Collections.singletonList(Settings.SHOW_REEL_POST_TIME), PatchFamily.REEL_POST_TIME.switches);
        assertEquals("Show a reel's exact time", PatchFamily.REEL_POST_TIME.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SHOW_REEL_POST_TIME.key));

        Settings.SHOW_REEL_POST_TIME.save(false);
        assertFalse(Settings.SHOW_REEL_POST_TIME.savedValue());
        Settings.SHOW_REEL_POST_TIME.save(true);
        assertTrue(Settings.SHOW_REEL_POST_TIME.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.SHOW_REEL_POST_TIME.get());
        assertTrue(Settings.SHOW_REEL_POST_TIME.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.SHOW_REEL_POST_TIME.get());
    }
    /** The seconds are a choice of their own, off to start so a reel's time reads the way a post's does, and wait for the switch. */
    @Test public void theSecondsStartOffAndWaitForTheSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_POST_TIME));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_REEL_POST_TIME.key);
        SwitchPreference seconds = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REEL_POST_TIME_SECONDS.key);
        assertNotNull(seconds);
        assertEquals("Show seconds in a reel's time", seconds.getTitle().toString());
        assertEquals("Adds the seconds to a reel's time, like Oct 2, 3:45:30 PM.", seconds.getSummary().toString());
        assertFalse(seconds.isChecked());
        assertEquals(Boolean.FALSE, Settings.REEL_POST_TIME_SECONDS.defaultValue);
        assertFalse("read as each reel's title is drawn", Settings.REEL_POST_TIME_SECONDS.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_POST_TIME_SECONDS.key));

        assertFalse("the seconds wait for Show a reel's exact time", seconds.isEnabled());
        row.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(seconds.isEnabled());
        row.setChecked(false);
        ShadowLooper.idleMainLooper();
        assertFalse(seconds.isEnabled());
    }
    /** How long ago is a choice of its own too (#195), off to start, backed up, and waiting for the switch. */
    @Test public void howLongAgoStartsOffAndWaitsForTheSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_POST_TIME));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_REEL_POST_TIME.key);
        SwitchPreference ago = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REEL_POST_TIME_AGO.key);
        assertNotNull(ago);
        assertEquals("Show how long ago a reel was posted", ago.getTitle().toString());
        assertEquals("Shows the days, hours and minutes since it went up on a line under the time, like 1d 2h 5m ago.",
                ago.getSummary().toString());
        assertFalse(ago.isChecked());
        assertEquals(Boolean.FALSE, Settings.REEL_POST_TIME_AGO.defaultValue);
        assertFalse("read as each reel's title is drawn", Settings.REEL_POST_TIME_AGO.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_POST_TIME_AGO.key));

        assertFalse("waits for Show a reel's exact time", ago.isEnabled());
        row.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(ago.isEnabled());
    }
    /** Searching the patch's name finds both of its choices with it, and nothing of the patch next to it. */
    @Test public void searchingThePatchsNameFindsItsChoices() throws Exception {
        open(EnumSet.of(PatchFamily.KEEP_REEL_SPEED, PatchFamily.REEL_POST_TIME));
        Preference row = page.getPreferenceScreen().findPreference(Settings.SHOW_REEL_POST_TIME.key);
        Preference seconds = page.getPreferenceScreen().findPreference(Settings.REEL_POST_TIME_SECONDS.key);
        Preference ago = page.getPreferenceScreen().findPreference(Settings.REEL_POST_TIME_AGO.key);
        Preference speed = page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_SPEED.key);
        page.searchSettings("show a reel's exact time");
        assertNotNull("the switch", row.getParent());
        assertNotNull("its seconds", seconds.getParent());
        assertNotNull("how long ago", ago.getParent());
        assertNull("Keep the reel speed", speed.getParent());
        page.searchSettings("");
        assertNotNull(speed.getParent());
    }
}
