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

/** Seek bar on photos with music (#10) is its own switch under Reels, right after the seek bar's other rows, and starts off. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelPhotoSeekBarSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.PHOTO_REEL_SEEK_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.PHOTO_REEL_SEEK_BAR.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    @Test public void itIsAnOwnSwitchUnderReelsAndStartsOff() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_SEEK_BAR);
        controller = Robolectric.buildActivity(Activity.class).setup();
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();

        Preference row = page.getPreferenceScreen().findPreference(Settings.PHOTO_REEL_SEEK_BAR.key);
        assertNotNull(row);
        assertTrue(row instanceof SwitchPreference);
        assertEquals("Seek bar on photos with music", row.getTitle().toString());
        assertEquals("Reels that are a photo with a song get the same seek bar and time as a video reel, "
                + "following the song. Drag it to seek.", row.getSummary().toString());
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        int dm = -1;
        int photo = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.DM_REEL_SEEK_BAR.key.equals(key)) dm = i;
            if (Settings.PHOTO_REEL_SEEK_BAR.key.equals(key)) photo = i;
        }
        assertEquals("right after Seek bar like DM videos", dm + 1, photo);
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.PHOTO_REEL_SEEK_BAR.get());
        assertEquals(Boolean.FALSE, Settings.PHOTO_REEL_SEEK_BAR.defaultValue);
        assertFalse("read as each reel binds, so no restart", Settings.PHOTO_REEL_SEEK_BAR.rebootApp);
        assertTrue(PatchFamily.REEL_SEEK_BAR.switches.contains(Settings.PHOTO_REEL_SEEK_BAR));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PHOTO_REEL_SEEK_BAR.key));
    }
}
