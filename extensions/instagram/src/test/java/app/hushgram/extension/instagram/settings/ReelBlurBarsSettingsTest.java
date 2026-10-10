/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Arrays;
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

/** The blur bars switch: under Reels after the seek bar rows, off to start, off while paused, and no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelBlurBarsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.BLUR_REEL_BARS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.BLUR_REEL_BARS.resetToDefault();
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
    @Test public void missingPatchHasNoBlurSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_SEEK_BAR));
        assertNull(page.getPreferenceScreen().findPreference(Settings.BLUR_REEL_BARS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.REEL_SEEK_BAR.key));
    }
    /** Without the other Reels patches, Reels still opens for this switch alone. */
    @Test public void blurAloneStillGetsReels() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_BLUR_BARS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.BLUR_REEL_BARS.key);
        assertNotNull(row);
        assertEquals("Reels", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.REEL_SEEK_BAR.key));
    }
    @Test public void blurSwitchStartsOffUnderReelsAfterTheSeekBarAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_SEEK_BAR, PatchFamily.REEL_BLUR_BARS, PatchFamily.REEL_AUTO_SCROLL));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.BLUR_REEL_BARS.key);
        assertNotNull(row);
        assertEquals("Blur the bars around a reel", row.getTitle().toString());
        assertEquals("A reel that doesn't fill the screen shows a blurred copy of itself above and below it instead "
                + "of black bars. Open Reels again after a change.", row.getSummary().toString());
        PreferenceGroup reels = row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        String[] keys = new String[reels.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = reels.getPreference(i).getKey();
        int bigger = Arrays.asList(keys).indexOf(Settings.BIG_REEL_SEEK_BAR.key);
        assertTrue(Arrays.toString(keys), bigger >= 0);
        assertEquals("right after Bigger seek bar on reels", Settings.BLUR_REEL_BARS.key, keys[bigger + 1]);
        assertFalse(row.isChecked());
        assertFalse(Settings.BLUR_REEL_BARS.get());
        assertFalse("the viewer reads the switch as pages settle, so no restart prompt", Settings.BLUR_REEL_BARS.rebootApp);
        assertEquals(Collections.singletonList(Settings.BLUR_REEL_BARS), PatchFamily.REEL_BLUR_BARS.switches);
        assertEquals("Blur the bars around Reels", PatchFamily.REEL_BLUR_BARS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.BLUR_REEL_BARS.key));

        Settings.BLUR_REEL_BARS.save(true);
        assertTrue(Settings.BLUR_REEL_BARS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.BLUR_REEL_BARS.get());
        assertTrue(Settings.BLUR_REEL_BARS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.BLUR_REEL_BARS.get());
    }
}
