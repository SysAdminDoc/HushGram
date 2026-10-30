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

/** Take a scroll break (#297) is a list right after Stop after 20 reels under Reels, starts off, says what it does and is backed up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ScrollBreakSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        Settings.SCROLL_BREAK.resetToDefault();
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
        assertEquals("right after Stop after 20 reels", cap + 1, pause);

        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(entry.toString());
        assertEquals(Arrays.asList("Off", "After 10 minutes", "After 20 minutes", "After 30 minutes", "After 60 minutes"),
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
