/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.reels.ScrollBreakTime;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Take a scroll break (#297) is a list right after Stop after a set number of reels and its sliders under Reels, starts off, says what it does and is backed up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ScrollBreakSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        Settings.SCROLL_BREAK.resetToDefault();
        Settings.SCROLL_BREAK_LENGTH.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SCROLL_BREAK.resetToDefault();
        Settings.SCROLL_BREAK_LENGTH.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void itFollowsStopAfterTwentyReelsAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_SCROLLING));
        Preference found = page.getPreferenceScreen().findPreference(Settings.SCROLL_BREAK.key);
        assertTrue(found instanceof HushgramPreferenceFragment.ScrollBreakRow);
        ListPreference row = (ListPreference) found;
        assertEquals("Take a scroll break", row.getTitle().toString());
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        int cap = -1;
        int pause = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.REEL_CAP.key.equals(key)) cap = i;
            if (Settings.SCROLL_BREAK.key.equals(key)) pause = i;
        }
        assertEquals("right after Stop after a set number of reels and its two sliders", cap + 3, pause);
        assertTrue("Off says what it does", row.getSummary().toString().contains("for a break"));

        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(entry.toString());
        assertEquals(Arrays.asList("Off", "After 5 minutes", "After 10 minutes", "After 15 minutes", "After 20 minutes",
                "After 30 minutes", "After 45 minutes", "After 60 minutes", "After 90 minutes", "After 120 minutes"),
                entries);
        assertEquals(ScrollBreakTime.OFF, Settings.SCROLL_BREAK.get());
        assertEquals("OFF", row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Off. "));

        row.setValue("TWENTY_MINUTES");
        ShadowLooper.idleMainLooper();
        assertEquals(ScrollBreakTime.TWENTY_MINUTES, Settings.SCROLL_BREAK.savedValue());
        assertEquals("After 20 minutes on the Home, Reels and Explore tabs, a break screen covers them for 5 minutes. "
                + "Messages, your profile and the tab bar still work.", row.getSummary().toString());

        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SCROLL_BREAK.key));
    }

    /**
     * #297: Break length is a slider right under Take a scroll break, 5 to 120 minutes, starting on the 5 the
     * break has always lasted. It waits for a time to be picked, the list's summary says the length picked,
     * and it's backed up.
     */
    @Test public void breakLengthIsASliderUnderItThatTheSummaryFollows() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_SCROLLING));
        ListPreference list = (ListPreference) page.getPreferenceScreen().findPreference(Settings.SCROLL_BREAK.key);
        Preference found = page.getPreferenceScreen().findPreference(Settings.SCROLL_BREAK_LENGTH.key);
        assertTrue(found instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow length = (HushgramPreferenceFragment.SliderRow) found;
        assertEquals("Break length", String.valueOf(length.getTitle()));
        PreferenceGroup reels = list.getParent();
        int at = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            if (Settings.SCROLL_BREAK.key.equals(reels.getPreference(i).getKey())) at = i;
        }
        assertEquals("right under Take a scroll break", Settings.SCROLL_BREAK_LENGTH.key, reels.getPreference(at + 1).getKey());

        assertFalse("off, there's no break to size", Settings.SCROLL_BREAK_LENGTH.isAvailable());
        assertFalse(length.isEnabled());
        assertEquals("Turn on Take a scroll break to use this slider.", String.valueOf(length.getSummary()));
        SeekBar slider = sliderOf(length);
        assertEquals("5 to 120 minutes in steps of 5", 23, slider.getMax());
        assertEquals(0, slider.getProgress());

        list.setValue("TEN_MINUTES");
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.SCROLL_BREAK_LENGTH.isAvailable());
        assertTrue(length.isEnabled());
        assertEquals("5 minutes (standard)", String.valueOf(length.getSummary()));

        length.choose(60);
        ShadowLooper.idleMainLooper();
        assertEquals(60, (int) Settings.SCROLL_BREAK_LENGTH.savedValue());
        assertEquals("60 minutes", String.valueOf(length.getSummary()));
        assertEquals("the list says the break picked", "After 10 minutes on the Home, Reels and Explore tabs, a break "
                + "screen covers them for 60 minutes. Messages, your profile and the tab bar still work.",
                String.valueOf(list.getSummary()));
        assertFalse("the next break reads it", Settings.SCROLL_BREAK_LENGTH.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SCROLL_BREAK_LENGTH.key));
    }

    private SeekBar sliderOf(Preference row) {
        View view = row.getView(null, (ViewGroup) controller.get().findViewById(android.R.id.content));
        View slider = view.findViewWithTag("hushgram_slider");
        assertTrue("the row carries its slider", slider instanceof SeekBar);
        return (SeekBar) slider;
    }

    @Test public void searchFindsItUnderItsPatch() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_SCROLLING));
        Preference row = page.getPreferenceScreen().findPreference(Settings.SCROLL_BREAK.key);
        page.searchSettings("stop reels scrolling");
        assertNotNull("the patch's name finds it", row.getParent());
        page.searchSettings("scroll break");
        assertNotNull("its own name finds it", row.getParent());
        page.searchSettings("burn-in");
        assertNull(row.getParent());
        page.searchSettings("");
        assertNotNull(row.getParent());
    }

    @Test public void withoutStopReelsScrollingThereIsNoRowAndNothingToBackUp() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SCROLL_BREAK.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.SCROLL_BREAK.key));
    }
}
