/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
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

/** Full screen Reels sits under Reels and Full screen Home under Feed, both start off, are kept in a backup and answer off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FullScreenBarsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FULL_SCREEN_HOME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FULL_SCREEN_HOME.resetToDefault();
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
    @Test public void reelsIsUnderReelsAndHomeIsUnderFeedAndBothStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        android.preference.Preference reels = page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_REELS.key);
        android.preference.Preference home = page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HOME.key);
        assertNotNull(reels);
        assertNotNull(home);
        assertEquals("Full screen Reels", reels.getTitle().toString());
        assertEquals("Full screen Home", home.getTitle().toString());
        assertEquals("Reels", ((PreferenceGroup) reels.getParent()).getTitle().toString());
        assertEquals("Feed", ((PreferenceGroup) home.getParent()).getTitle().toString());
        assertFalse(Settings.FULL_SCREEN_REELS.get());
        assertFalse(Settings.FULL_SCREEN_HOME.get());
        assertEquals("Full screen Reels and Home", PatchFamily.FULL_SCREEN_BARS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FULL_SCREEN_REELS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FULL_SCREEN_HOME.key));
    }
    @Test public void savedSwitchesAnswerOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        Settings.FULL_SCREEN_REELS.save(true);
        Settings.FULL_SCREEN_HOME.save(true);
        assertTrue(Settings.FULL_SCREEN_REELS.get());
        assertTrue(Settings.FULL_SCREEN_HOME.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FULL_SCREEN_REELS.get());
        assertFalse(Settings.FULL_SCREEN_HOME.get());
        assertTrue(Settings.FULL_SCREEN_REELS.savedValue());
    }
    @Test public void missingPatchHasNoSwitches() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_REELS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HOME.key));
    }
}
