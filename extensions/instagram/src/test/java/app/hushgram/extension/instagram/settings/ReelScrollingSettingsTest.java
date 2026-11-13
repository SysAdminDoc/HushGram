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
import app.hushgram.extension.shared.settings.BooleanSetting;
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
        Settings.REEL_SCROLL_FROM_PROFILE.resetToDefault();
        Settings.REEL_SCROLL_FROM_SEARCH.resetToDefault();
        Settings.REEL_SCROLL_FROM_AUDIO.resetToDefault();
        Settings.PLAY_ONE_REEL.resetToDefault();
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
        Settings.REEL_SCROLL_FROM_PROFILE.resetToDefault();
        Settings.REEL_SCROLL_FROM_SEARCH.resetToDefault();
        Settings.REEL_SCROLL_FROM_AUDIO.resetToDefault();
        Settings.PLAY_ONE_REEL.resetToDefault();
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
        assertEquals(java.util.Arrays.asList(Settings.STOP_REELS_SCROLLING, Settings.REEL_SCROLL_FROM_PROFILE,
                Settings.REEL_SCROLL_FROM_SEARCH, Settings.REEL_SCROLL_FROM_AUDIO, Settings.PLAY_ONE_REEL, Settings.REEL_CAP),
                PatchFamily.REEL_SCROLLING.switches);
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
     * The three viewers you can keep scrolling (#508) sit right under Stop Reels scrolling, start off,
     * rest until it's on, need no restart and are kept in a backup.
     */
    @Test public void theViewersYouCanKeepScrollingSitUnderTheStop() throws Exception {
        open(true);
        BooleanSetting[] kept = {Settings.REEL_SCROLL_FROM_PROFILE, Settings.REEL_SCROLL_FROM_SEARCH, Settings.REEL_SCROLL_FROM_AUDIO};
        String[] titles = {"Still scroll reels from a profile", "Still scroll reels from Search and Explore", "Still scroll reels from a song page"};
        SwitchPreference stop = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STOP_REELS_SCROLLING.key);
        PreferenceGroup reels = stop.getParent();
        int under = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            if (Settings.STOP_REELS_SCROLLING.key.equals(reels.getPreference(i).getKey())) under = i;
        }
        for (int i = 0; i < kept.length; i++) {
            SwitchPreference row = (SwitchPreference) reels.getPreference(under + 1 + i);
            assertEquals(kept[i].key, row.getKey());
            assertEquals(titles[i], row.getTitle().toString());
            assertFalse(row.isChecked());
            assertEquals(Boolean.FALSE, kept[i].defaultValue);
            assertFalse("read as each viewer opens", kept[i].rebootApp);
            assertFalse("rests until the stop is on", kept[i].isAvailable());
            assertTrue(ConfigurationBackup.eligible().containsKey(kept[i].key));
        }
        Settings.STOP_REELS_SCROLLING.save(true);
        for (BooleanSetting setting : kept) assertTrue(setting.isAvailable());
    }

    /**
     * Play only the reel you opened (#656) is its own switch right after the three viewers kept
     * scrolling: off to start, usable with Stop Reels scrolling off, read as each viewer opens with no
     * restart, off while paused, and kept in a backup.
     */
    @Test public void playOnlyTheReelYouOpenedIsItsOwnSwitchAfterTheThree() throws Exception {
        open(true);
        SwitchPreference stop = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STOP_REELS_SCROLLING.key);
        PreferenceGroup reels = stop.getParent();
        int under = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            if (Settings.STOP_REELS_SCROLLING.key.equals(reels.getPreference(i).getKey())) under = i;
        }
        SwitchPreference row = (SwitchPreference) reels.getPreference(under + 4);
        assertEquals(Settings.PLAY_ONE_REEL.key, row.getKey());
        assertEquals("Play only the reel you opened", row.getTitle().toString());
        assertTrue(String.valueOf(row.getSummary()), String.valueOf(row.getSummary()).contains("The Reels tab scrolls as usual."));
        assertFalse(row.isChecked());
        assertEquals(Boolean.FALSE, Settings.PLAY_ONE_REEL.defaultValue);
        assertFalse(Settings.PLAY_ONE_REEL.get());
        assertFalse("read as each viewer opens", Settings.PLAY_ONE_REEL.rebootApp);
        assertFalse(Settings.STOP_REELS_SCROLLING.get());
        assertTrue("works without the stop", Settings.PLAY_ONE_REEL.isAvailable());
        assertTrue(row.isEnabled());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PLAY_ONE_REEL.key));

        row.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.PLAY_ONE_REEL.savedValue());
        assertTrue(Settings.PLAY_ONE_REEL.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.PLAY_ONE_REEL.get());
        assertTrue(Settings.PLAY_ONE_REEL.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.PLAY_ONE_REEL.get());
    }

    /**
     * How many reels and how long a break (#405) are two sliders right under Stop after a set number of
     * reels, starting on 20 reels and 15 minutes, resting until that switch is on, and backed up.
     */
    @Test public void theReelCountAndTheBreakAreSlidersUnderTheReelStop() throws Exception {
        open(true);
        String waitsOnTheSwitch = String.format("Turn on %s to use this slider.", "Stop after a set number of reels");
        Preference countFound = page.getPreferenceScreen().findPreference(Settings.REEL_CAP_COUNT.key);
        assertTrue(countFound instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow count = (HushgramPreferenceFragment.SliderRow) countFound;
        assertEquals("Reels before swiping stops", String.valueOf(count.getTitle()));
        assertEquals(waitsOnTheSwitch, String.valueOf(count.getSummary()));
        SeekBar countSlider = sliderOf(count);
        assertEquals("5 to 100 reels in steps of 5", 19, countSlider.getMax());
        assertEquals(3, countSlider.getProgress());

        Preference breakFound = page.getPreferenceScreen().findPreference(Settings.REEL_CAP_BREAK.key);
        assertTrue(breakFound instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow pause = (HushgramPreferenceFragment.SliderRow) breakFound;
        assertEquals("Time away before swiping comes back", String.valueOf(pause.getTitle()));
        assertEquals(waitsOnTheSwitch, String.valueOf(pause.getSummary()));
        SeekBar breakSlider = sliderOf(pause);
        assertEquals("5 to 120 minutes in steps of 5", 23, breakSlider.getMax());
        assertEquals(2, breakSlider.getProgress());

        assertFalse("Stop after a set number of reels starts off", Settings.REEL_CAP.get());
        assertFalse(count.isEnabled());
        assertFalse(pause.isEnabled());
        assertFalse(Settings.REEL_CAP_COUNT.isAvailable());
        ((SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REEL_CAP.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.REEL_CAP_COUNT.isAvailable());
        assertTrue(Settings.REEL_CAP_BREAK.isAvailable());
        assertTrue(count.isEnabled());
        assertTrue(pause.isEnabled());
        assertEquals("20 reels (standard)", String.valueOf(count.getSummary()));
        assertEquals("15 minutes in the background (standard)", String.valueOf(pause.getSummary()));

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

    /**
     * A screen reader hears the slider's value as the row shows it, "20 reels (standard)", on every
     * version. Android 9 and 10 have no state description, so the value rides in the node's extras
     * under AndroidX's key, which TalkBack reads in place of the percent.
     */
    @Test public void aScreenReaderHearsTheSlidersValueAsShownOnEveryVersion() throws Exception {
        open(true);
        ((SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REEL_CAP.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        HushgramPreferenceFragment.SliderRow count =
                (HushgramPreferenceFragment.SliderRow) page.getPreferenceScreen().findPreference(Settings.REEL_CAP_COUNT.key);
        assertEquals("20 reels (standard)", spoken(count));
        count.choose(35);
        ShadowLooper.idleMainLooper();
        assertEquals("35 reels", spoken(count));
    }

    /** What TalkBack hears from the row's slider, read on screen: newer Android fills no node for a view outside a window. */
    private String spoken(Preference row) {
        ViewGroup content = (ViewGroup) controller.get().findViewById(android.R.id.content);
        View view = row.getView(null, content);
        content.addView(view);
        View slider = view.findViewWithTag("hushgram_slider");
        assertTrue("the row carries its slider", slider instanceof SeekBar);
        assertTrue(slider.isAttachedToWindow());
        android.view.accessibility.AccessibilityNodeInfo node = slider.createAccessibilityNodeInfo();
        CharSequence value = android.os.Build.VERSION.SDK_INT >= 30 ? node.getStateDescription()
                : node.getExtras().getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY");
        return String.valueOf(value);
    }

    private SeekBar sliderOf(Preference row) {
        View view = row.getView(null, (ViewGroup) controller.get().findViewById(android.R.id.content));
        View slider = view.findViewWithTag("hushgram_slider");
        assertTrue("the row carries its slider", slider instanceof SeekBar);
        return (SeekBar) slider;
    }
}
