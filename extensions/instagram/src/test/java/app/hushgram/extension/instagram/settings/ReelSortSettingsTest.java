/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Reels sort switch (#679): under Profiles, off to start, off while paused, and no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelSortSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.PROFILE_REEL_SORT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.PROFILE_REEL_SORT.resetToDefault();
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
        open(EnumSet.of(PatchFamily.PROFILE_GRID_JUMP));
        assertNull(page.getPreferenceScreen().findPreference(Settings.PROFILE_REEL_SORT.key));
    }
    /** Without the other profile patches, Profiles still opens for this switch alone. */
    @Test public void aloneItStillGetsProfiles() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_REEL_SORT));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PROFILE_REEL_SORT.key);
        assertNotNull(row);
        assertEquals("Profiles", row.getParent().getTitle().toString());
        assertEquals("Sort reels on profiles", row.getTitle().toString());
    }
    @Test public void startsOffAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_GRID_JUMP, PatchFamily.PROFILE_REEL_SORT));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PROFILE_REEL_SORT.key);
        assertFalse(row.isChecked());
        assertFalse(Settings.PROFILE_REEL_SORT.get());
        assertFalse("each read asks the switch, so no restart prompt", Settings.PROFILE_REEL_SORT.rebootApp);
        assertEquals(Collections.singletonList(Settings.PROFILE_REEL_SORT), PatchFamily.PROFILE_REEL_SORT.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PROFILE_REEL_SORT.key));

        Settings.PROFILE_REEL_SORT.save(true);
        assertTrue(Settings.PROFILE_REEL_SORT.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.PROFILE_REEL_SORT.get());
        assertTrue(Settings.PROFILE_REEL_SORT.savedValue());
    }
}
