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

/** Keep Reels auto scroll on's switch: under Reels, on to start, off while paused; its memory is no switch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelAutoScrollSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.KEEP_REEL_AUTO_SCROLL.resetToDefault();
        Settings.REEL_AUTO_SCROLL_ON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_REEL_AUTO_SCROLL.resetToDefault();
        Settings.REEL_AUTO_SCROLL_ON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.REEL_AUTO_SCROLL) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoAutoScrollSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_AUTO_SCROLL.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.REEL_AUTO_SCROLL_ON.key));
    }
    @Test public void autoScrollSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_AUTO_SCROLL.key);
        assertNotNull(row);
        assertEquals("Keep auto scroll on", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.KEEP_REEL_AUTO_SCROLL.get());
        assertNull("the memory isn't a control", page.getPreferenceScreen().findPreference(Settings.REEL_AUTO_SCROLL_ON.key));
        assertEquals(java.util.Collections.singletonList(Settings.KEEP_REEL_AUTO_SCROLL), PatchFamily.REEL_AUTO_SCROLL.switches);
        assertFalse("a backup leaves the memory out", ConfigurationBackup.eligible().containsKey(Settings.REEL_AUTO_SCROLL_ON.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_REEL_AUTO_SCROLL.key));

        Settings.KEEP_REEL_AUTO_SCROLL.save(false);
        assertFalse(Settings.KEEP_REEL_AUTO_SCROLL.savedValue());
        Settings.KEEP_REEL_AUTO_SCROLL.save(true);
        Settings.REEL_AUTO_SCROLL_ON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.KEEP_REEL_AUTO_SCROLL.get());
        assertTrue(Settings.KEEP_REEL_AUTO_SCROLL.savedValue());
        assertTrue("a pause leaves the memory alone", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.KEEP_REEL_AUTO_SCROLL.get());
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
}
