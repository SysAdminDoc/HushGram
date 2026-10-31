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
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
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
import org.robolectric.shadows.ShadowLooper;
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
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.STOP_REELS_SCROLLING.resetToDefault();
        Settings.REEL_CAP.resetToDefault();
        Settings.REEL_CAP_COUNT.resetToDefault();
        Settings.REEL_CAP_BREAK.resetToDefault();
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
        Settings.REEL_CAP.resetToDefault();
        Settings.REEL_CAP_COUNT.resetToDefault();
        Settings.REEL_CAP_BREAK.resetToDefault();
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
        assertEquals("Stop after a set number of reels", cap.getTitle().toString());
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
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }

    /**
     * How many reels and how long a break (#405) are two sliders right under Stop after a set number of
     * reels, starting on 20 reels and 15 minutes, resting until that switch is on, and backed up.
     */
    @Test public void theReelCountAndTheBreakAreSlidersUnderTheReelStop() throws Exception {
        open(true);
        Preference countFound = page.getPreferenceScreen().findPreference(Settings.REEL_CAP_COUNT.key);
        assertTrue(countFound instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow count = (HushgramPreferenceFragment.SliderRow) countFound;
        assertEquals("Reels before swiping stops", String.valueOf(count.getTitle()));
        assertEquals("20 reels (standard)", String.valueOf(count.getSummary()));
        SeekBar countSlider = sliderOf(count);
        assertEquals("5 to 100 reels in steps of 5", 19, countSlider.getMax());
        assertEquals(3, countSlider.getProgress());

        Preference breakFound = page.getPreferenceScreen().findPreference(Settings.REEL_CAP_BREAK.key);
        assertTrue(breakFound instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow pause = (HushgramPreferenceFragment.SliderRow) breakFound;
        assertEquals("Time away before swiping comes back", String.valueOf(pause.getTitle()));
        assertEquals("15 minutes in the background (standard)", String.valueOf(pause.getSummary()));
        SeekBar breakSlider = sliderOf(pause);
        assertEquals("5 to 120 minutes in steps of 5", 23, breakSlider.getMax());
        assertEquals(2, breakSlider.getProgress());

        assertFalse("Stop after a set number of reels starts off", Settings.REEL_CAP.get());
        assertFalse(count.isEnabled());
        assertFalse(pause.isEnabled());
        assertFalse(Settings.REEL_CAP_COUNT.isAvailable());
        Settings.REEL_CAP.save(true);
        assertTrue(Settings.REEL_CAP_COUNT.isAvailable());
        assertTrue(Settings.REEL_CAP_BREAK.isAvailable());

        PreferenceGroup reels = count.getParent();
        String[] keys = new String[reels.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = reels.getPreference(i).getKey();
        int cap = java.util.Arrays.asList(keys).indexOf(Settings.REEL_CAP.key);
        assertEquals(java.util.Arrays.toString(keys), Settings.REEL_CAP_COUNT.key, keys[cap + 1]);
        assertEquals(Settings.REEL_CAP_BREAK.key, keys[cap + 2]);

        count.choose(35);
        pause.choose(60);
        ShadowLooper.idleMainLooper();
        assertEquals(35, (int) Settings.REEL_CAP_COUNT.savedValue());
        assertEquals(60, (int) Settings.REEL_CAP_BREAK.savedValue());
        assertEquals("35 reels", String.valueOf(count.getSummary()));
        assertEquals("60 minutes in the background", String.valueOf(pause.getSummary()));
        assertFalse("the next reel counts against it at once", Settings.REEL_CAP_COUNT.rebootApp);
        assertFalse(Settings.REEL_CAP_BREAK.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_CAP_COUNT.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_CAP_BREAK.key));
    }

    private SeekBar sliderOf(Preference row) {
        View view = row.getView(null, (ViewGroup) controller.get().findViewById(android.R.id.content));
        View slider = view.findViewWithTag("hushgram_slider");
        assertTrue("the row carries its slider", slider instanceof SeekBar);
        return (SeekBar) slider;
    }
}
