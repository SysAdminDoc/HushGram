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

/** Stop Reels scrolling's switch: under Reels, off to start, off while paused, and a change asks for a restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelScrollingSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.STOP_REELS_SCROLLING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.STOP_REELS_SCROLLING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.REEL_SCROLLING) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoReelScrollingSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.STOP_REELS_SCROLLING.key));
    }
    @Test public void reelScrollingSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STOP_REELS_SCROLLING.key);
        assertNotNull(row);
        assertEquals("Stop Reels scrolling", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.STOP_REELS_SCROLLING.get());
        assertEquals(java.util.Arrays.asList(Settings.STOP_REELS_SCROLLING, Settings.REEL_CAP), PatchFamily.REEL_SCROLLING.switches);
        SwitchPreference cap = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REEL_CAP.key);
        assertNotNull(cap);
        assertEquals("Stop after 20 reels", cap.getTitle().toString());
        assertFalse(cap.isChecked());
        assertFalse(Settings.REEL_CAP.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_CAP.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STOP_REELS_SCROLLING.key));
        assertTrue("a viewer turns its pager off as it opens", Settings.STOP_REELS_SCROLLING.rebootApp);

        Settings.STOP_REELS_SCROLLING.save(true);
        assertTrue(Settings.STOP_REELS_SCROLLING.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.STOP_REELS_SCROLLING.get());
        assertTrue(Settings.STOP_REELS_SCROLLING.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.STOP_REELS_SCROLLING.get());
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
}
