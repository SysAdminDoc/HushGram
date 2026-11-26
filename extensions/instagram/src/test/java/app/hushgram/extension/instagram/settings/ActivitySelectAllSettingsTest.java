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

/** The #762 switch: under Profiles after the follow switches, off to start, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ActivitySelectAllSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.ACTIVITY_SELECT_ALL.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.ACTIVITY_SELECT_ALL.resetToDefault();
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
    private SwitchPreference row() {
        return (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.ACTIVITY_SELECT_ALL.key);
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_FOLLOW_COUNTS));
        assertNull(row());
    }
    /** Without the other profile patches, Profiles still opens for this switch alone. */
    @Test public void aloneItStillGetsProfiles() throws Exception {
        open(EnumSet.of(PatchFamily.ACTIVITY_SELECT_ALL));
        SwitchPreference all = row();
        assertNotNull(all);
        assertEquals("Profiles", all.getParent().getTitle().toString());
    }
    @Test public void startsOffRightAfterTheFollowSwitches() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_FOLLOW_COUNTS, PatchFamily.ACTIVITY_SELECT_ALL));
        SwitchPreference all = row();
        assertEquals("Select all in Your activity", all.getTitle().toString());
        assertEquals("Adds Select all beside Select on the photo and video grids in Your activity, like your posts, "
                + "reels and likes. Tick one first and it shows. It only ticks them. Nothing goes until you press "
                + "Delete or Unlike yourself.",
                all.getSummary().toString());
        PreferenceGroup profiles = all.getParent();
        assertEquals("Profiles", profiles.getTitle().toString());
        assertEquals("right after Hide Followers and Following", Settings.HIDE_PROFILE_FOLLOW_BUTTONS.key,
                profiles.getPreference(indexOf(profiles, all) - 1).getKey());
        assertFalse(all.isChecked());
        assertFalse("each layout reads the switch, so no restart prompt", Settings.ACTIVITY_SELECT_ALL.rebootApp);
        assertEquals(Collections.singletonList(Settings.ACTIVITY_SELECT_ALL), PatchFamily.ACTIVITY_SELECT_ALL.switches);
        assertEquals("Select all in Your activity", PatchFamily.ACTIVITY_SELECT_ALL.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACTIVITY_SELECT_ALL.key));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
