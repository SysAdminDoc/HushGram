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

/** Keep clear mode on sits under Reels after Clean up Reels' other rows, starts off and is kept in a backup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelClearModeSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_REEL_CLEAR_MODE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_REEL_CLEAR_MODE.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void itIsUnderReelsAfterBurnInGuardAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference row = page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_CLEAR_MODE.key);
        assertNotNull(row);
        assertTrue(row instanceof SwitchPreference);
        assertEquals("Keep clear mode on", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("the next reels open in it too"));
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        int guard = -1;
        int keep = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.BURN_IN_STAY_DIM.key.equals(key)) guard = i;
            if (Settings.KEEP_REEL_CLEAR_MODE.key.equals(key)) keep = i;
        }
        assertEquals("right after Burn-in guard's Stay dim when I tap", guard + 1, keep);
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.KEEP_REEL_CLEAR_MODE.get());
        assertEquals(Boolean.FALSE, Settings.KEEP_REEL_CLEAR_MODE.defaultValue);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_REEL_CLEAR_MODE.key));
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.KEEP_REEL_CLEAR_MODE));
    }
    @Test public void withoutCleanUpReelsThereIsNoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_CLEAR_MODE.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.KEEP_REEL_CLEAR_MODE.key));
    }
}
