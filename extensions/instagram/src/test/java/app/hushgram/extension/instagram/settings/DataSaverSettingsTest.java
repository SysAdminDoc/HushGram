/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
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

/** Data saver's two switches: under Playback, the first off to start, the second on and waiting for the first. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class DataSaverSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.DATA_SAVER.resetToDefault();
        Settings.DATA_SAVER_MOBILE_DATA_ONLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.DATA_SAVER.resetToDefault();
        Settings.DATA_SAVER_MOBILE_DATA_ONLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.DATA_SAVER) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoDataSaverSwitches() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.DATA_SAVER.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.DATA_SAVER_MOBILE_DATA_ONLY.key));
    }
    @Test public void bothSwitchesSitUnderPlaybackAndTheSecondWaits() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.DATA_SAVER.key);
        SwitchPreference mobile = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.DATA_SAVER_MOBILE_DATA_ONLY.key);
        assertNotNull(row);
        assertNotNull(mobile);
        assertEquals("Data saver", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("lowest quality"));
        assertEquals("Only on mobile data", mobile.getTitle().toString());
        PreferenceCategory playback = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(playback);
        assertEquals("Playback", playback.getTitle().toString());
        assertEquals(2, playback.getPreferenceCount());
        assertFalse(row.isChecked());
        assertFalse(Settings.DATA_SAVER.get());
        assertTrue(mobile.isChecked());
        assertFalse("waits for Data saver", mobile.isEnabled());
        row.setChecked(true);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(mobile.isEnabled());
        // Only on mobile data picks where, not whether, so the report and Pause go by the first switch alone.
        assertEquals(java.util.Collections.singletonList(Settings.DATA_SAVER), PatchFamily.DATA_SAVER.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DATA_SAVER.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DATA_SAVER_MOBILE_DATA_ONLY.key));
    }
    @Test public void theSwitchPersistsAndHonorsPause() throws Exception {
        open(true);
        Settings.DATA_SAVER.save(true);
        assertTrue(Settings.DATA_SAVER.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.DATA_SAVER.get());
        assertTrue(Settings.DATA_SAVER.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.DATA_SAVER.get());
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
