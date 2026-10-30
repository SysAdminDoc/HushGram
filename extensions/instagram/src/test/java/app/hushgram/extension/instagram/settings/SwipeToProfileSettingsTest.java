/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
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

/** Swipe left to open the profile's switch: under Reels, off to start, off while paused, applied on a restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SwipeToProfileSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.REEL_SWIPE_TO_PROFILE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.REEL_SWIPE_TO_PROFILE.resetToDefault();
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
        open(EnumSet.of(PatchFamily.REEL_SCROLLING));
        assertNull(page.getPreferenceScreen().findPreference(Settings.REEL_SWIPE_TO_PROFILE.key));
    }
    @Test public void aloneItStillGetsReels() throws Exception {
        open(EnumSet.of(PatchFamily.SWIPE_TO_PROFILE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REEL_SWIPE_TO_PROFILE.key);
        assertNotNull(row);
        assertEquals("Reels", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffUnderReelsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_SCROLLING, PatchFamily.SWIPE_TO_PROFILE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REEL_SWIPE_TO_PROFILE.key);
        assertNotNull(row);
        assertEquals("Swipe left to open the profile", row.getTitle().toString());
        assertEquals("Reels", row.getParent().getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.REEL_SWIPE_TO_PROFILE.get());
        assertTrue("the swipe setup reads it once to build the panel, so a restart", Settings.REEL_SWIPE_TO_PROFILE.rebootApp);
        assertEquals(java.util.Collections.singletonList(Settings.REEL_SWIPE_TO_PROFILE), PatchFamily.SWIPE_TO_PROFILE.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_SWIPE_TO_PROFILE.key));

        Settings.REEL_SWIPE_TO_PROFILE.save(true);
        assertTrue(Settings.REEL_SWIPE_TO_PROFILE.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.REEL_SWIPE_TO_PROFILE.get());
        assertTrue(Settings.REEL_SWIPE_TO_PROFILE.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.REEL_SWIPE_TO_PROFILE.get());
    }
}
