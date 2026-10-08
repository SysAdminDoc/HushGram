/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
import java.util.EnumSet;
import java.util.List;
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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Following list mark's switch: in Profiles beside the profile label's, off to start, and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FollowingListSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.MARK_FOLLOWING_LIST.resetToDefault();
        Settings.SHOW_FRIENDSHIP_STATUS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PatchFamily.followingListMarkForTests = null;
        Settings.MARK_FOLLOWING_LIST.resetToDefault();
        Settings.SHOW_FRIENDSHIP_STATUS.resetToDefault();
        Settings.FRIENDSHIP_STATUS_CHIP.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.FRIENDSHIP_STATUS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoFollowingListSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_FOLLOWING_LIST.key));
        assertFalse(Settings.MARK_FOLLOWING_LIST.get());
    }
    @Test public void followingListSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference label = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_FRIENDSHIP_STATUS.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_FOLLOWING_LIST.key);
        assertNotNull(label);
        assertNotNull(row);
        assertTrue(label.isChecked());
        assertEquals("Mark who doesn't follow you back", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.MARK_FOLLOWING_LIST.get());
        List<BooleanSetting> switches = PatchFamily.FRIENDSHIP_STATUS.switches;
        assertEquals(Settings.SHOW_FRIENDSHIP_STATUS, switches.get(0));
        assertEquals(Settings.MARK_FOLLOWING_LIST, switches.get(1));
        Settings.MARK_FOLLOWING_LIST.save(true);
        assertTrue(Settings.MARK_FOLLOWING_LIST.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.MARK_FOLLOWING_LIST.get());
        assertTrue(Settings.MARK_FOLLOWING_LIST.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.MARK_FOLLOWING_LIST.get());
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
    /** Show it as a chip sits right under the label's switch, starts off and is off while paused. */
    @Test public void chipSwitchStartsOffUnderTheLabelAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference chip = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FRIENDSHIP_STATUS_CHIP.key);
        assertNotNull(chip);
        assertEquals("Show it as a chip", chip.getTitle().toString());
        assertFalse(chip.isChecked());
        assertFalse(Settings.FRIENDSHIP_STATUS_CHIP.get());
        SwitchPreference label = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_FRIENDSHIP_STATUS.key);
        assertEquals(label.getOrder() + 1, chip.getOrder());
        assertTrue(PatchFamily.FRIENDSHIP_STATUS.switches.contains(Settings.FRIENDSHIP_STATUS_CHIP));
        Settings.FRIENDSHIP_STATUS_CHIP.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FRIENDSHIP_STATUS_CHIP.get());
        assertTrue(Settings.FRIENDSHIP_STATUS_CHIP.savedValue());
        PauseForTests.resume();
        assertTrue(Settings.FRIENDSHIP_STATUS_CHIP.get());
        controller.close();
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.FRIENDSHIP_STATUS_CHIP.key));
    }

    @Test public void aBuildWhoseFollowListMovedKeepsTheLabelWithoutTheSecondSwitch() throws Exception {
        PatchFamily.followingListMarkForTests = false;
        open(true);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.SHOW_FRIENDSHIP_STATUS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_FOLLOWING_LIST.key));
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.FRIENDSHIP_STATUS), false);
        assertTrue(report.toString(), report.contains(
                "  Mark who doesn't follow you back: not in this build (Instagram's follow list didn't match)"));
    }
    @Test public void aBuildMarkingTheListReportsNothingMissing() throws Exception {
        open(true);
        assertTrue(PatchFamily.followingListMarkInBuild());
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.FRIENDSHIP_STATUS), false);
        for (String line : report) assertFalse(line, line.contains("not in this build ("));
    }
}
