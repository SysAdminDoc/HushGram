/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
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

/** Full screen Reels sits under Reels and Full screen Home under Feed, both start off, are kept in a backup and answer off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FullScreenBarsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FILL_REEL_SCREEN.resetToDefault();
        Settings.FIT_REEL_SCREEN.resetToDefault();
        Settings.FULL_SCREEN_HOME.resetToDefault();
        Settings.FULL_SCREEN_MESSAGES.resetToDefault();
        Settings.FULL_SCREEN_HIDES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FILL_REEL_SCREEN.resetToDefault();
        Settings.FIT_REEL_SCREEN.resetToDefault();
        Settings.FULL_SCREEN_HOME.resetToDefault();
        Settings.FULL_SCREEN_MESSAGES.resetToDefault();
        Settings.FULL_SCREEN_HIDES.resetToDefault();
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
    @Test public void reelsIsUnderReelsAndHomeIsUnderFeedAndBothStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        android.preference.Preference reels = page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_REELS.key);
        android.preference.Preference home = page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HOME.key);
        assertNotNull(reels);
        assertNotNull(home);
        assertEquals("Full screen Reels", reels.getTitle().toString());
        assertEquals("Full screen Home", home.getTitle().toString());
        assertEquals("Reels", ((PreferenceGroup) reels.getParent()).getTitle().toString());
        assertEquals("Feed", ((PreferenceGroup) home.getParent()).getTitle().toString());
        assertFalse(Settings.FULL_SCREEN_REELS.get());
        assertFalse(Settings.FULL_SCREEN_HOME.get());
        assertEquals("Full screen Reels and Home", PatchFamily.FULL_SCREEN_BARS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FULL_SCREEN_REELS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FULL_SCREEN_HOME.key));
    }
    @Test public void savedSwitchesAnswerOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        Settings.FULL_SCREEN_REELS.save(true);
        Settings.FULL_SCREEN_HOME.save(true);
        assertTrue(Settings.FULL_SCREEN_REELS.get());
        assertTrue(Settings.FULL_SCREEN_HOME.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FULL_SCREEN_REELS.get());
        assertFalse(Settings.FULL_SCREEN_HOME.get());
        assertTrue(Settings.FULL_SCREEN_REELS.savedValue());
    }
    @Test public void missingPatchHasNoSwitches() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_REELS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HOME.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_MESSAGES.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HIDES.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FIT_REEL_SCREEN.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.FULL_SCREEN_HIDES.key));
    }

    /** #231: Bars to hide sits right under Full screen Reels, starts at both and waits for a switch. */
    @Test public void barsToHideSitsUnderFullScreenReelsAndStartsAtBoth() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        android.preference.Preference reels = page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_REELS.key);
        android.preference.ListPreference row =
                (android.preference.ListPreference) page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HIDES.key);
        assertNotNull(row);
        PreferenceGroup group = (PreferenceGroup) reels.getParent();
        assertSame(group, row.getParent());
        int at = -1;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == reels) at = i;
        }
        assertSame("right under Full screen Reels", row, group.getPreference(at + 1));
        assertEquals("Bars to hide", row.getTitle().toString());
        assertArrayEquals(new CharSequence[] {"Both bars", "The status bar only", "The navigation bar only"}, row.getEntries());
        assertArrayEquals(new CharSequence[] {"BOTH", "STATUS_BAR", "NAVIGATION_BAR"}, row.getEntryValues());
        assertEquals("BOTH", row.getValue());
        assertEquals(app.hushgram.extension.instagram.reels.HiddenBars.BOTH, Settings.FULL_SCREEN_HIDES.get());
        assertFalse(Settings.FULL_SCREEN_HIDES.isAvailable());
        assertEquals("Turn on Full screen Reels, Full screen Home or Full screen Messages to use this choice.", row.getSummary().toString());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FULL_SCREEN_HIDES.key));
    }

    /** #288: Full screen Messages sits under Messages, starts off, is backed up, wakes Bars to hide and answers off while paused. */
    @Test public void messagesIsUnderMessagesAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_MESSAGES.key);
        assertNotNull(row);
        assertEquals("Full screen Messages", row.getTitle().toString());
        assertEquals("Hides the status bar and the navigation bar while your messages or a chat shows. Swipe in from "
                + "the edge to bring them back for a moment. Back and the keyboard work as usual.", row.getSummary().toString());
        assertEquals("Messages", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.FULL_SCREEN_MESSAGES.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FULL_SCREEN_MESSAGES.key));

        page.searchSettings("full screen reels and home");
        assertNotNull("the patch's name finds it", row.getParent());
        page.searchSettings("");

        assertFalse(Settings.FULL_SCREEN_HIDES.isAvailable());
        Settings.FULL_SCREEN_MESSAGES.save(true);
        assertTrue("Bars to hide picks for it too", Settings.FULL_SCREEN_HIDES.isAvailable());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FULL_SCREEN_MESSAGES.get());
        assertTrue(Settings.FULL_SCREEN_MESSAGES.savedValue());
    }

    /** #231: with either switch on the row says what each choice hides. */
    @Test public void barsToHideSaysWhatEachChoiceHides() throws Exception {
        Settings.FULL_SCREEN_HOME.save(true);
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        android.preference.ListPreference row =
                (android.preference.ListPreference) page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HIDES.key);
        assertTrue(Settings.FULL_SCREEN_HIDES.isAvailable());
        assertEquals("Full screen Reels and Home hide the status bar and the navigation bar.", row.getSummary().toString());
        row.setValue("STATUS_BAR");
        assertEquals("Full screen Reels and Home hide only the status bar at the top. The navigation bar stays.",
                row.getSummary().toString());
        row.setValue("NAVIGATION_BAR");
        assertEquals("Full screen Reels and Home hide only the navigation bar at the bottom. The status bar stays.",
                row.getSummary().toString());
    }

    /** #288: Fill the screen sits right under Bars to hide, starts off, waits for Full screen Reels, is backed up and answers off while paused. */
    @Test public void fillTheScreenSitsUnderBarsToHideAndWaitsForFullScreenReels() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        android.preference.Preference bars = page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HIDES.key);
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.FILL_REEL_SCREEN.key);
        assertNotNull(row);
        PreferenceGroup group = (PreferenceGroup) bars.getParent();
        assertSame(group, row.getParent());
        int at = -1;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == bars) at = i;
        }
        assertSame("right under Bars to hide", row, group.getPreference(at + 1));
        assertEquals("Fill the screen", row.getTitle().toString());
        assertFalse(Settings.FILL_REEL_SCREEN.defaultValue);
        assertFalse(Settings.FILL_REEL_SCREEN.isAvailable());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FILL_REEL_SCREEN.key));

        Settings.FULL_SCREEN_REELS.save(true);
        assertTrue(Settings.FILL_REEL_SCREEN.isAvailable());
        Settings.FILL_REEL_SCREEN.save(true);
        assertTrue(Settings.FILL_REEL_SCREEN.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FILL_REEL_SCREEN.get());
        assertTrue(Settings.FILL_REEL_SCREEN.savedValue());
    }

    /** piko #2183: Show the whole reel sits right under Fill the screen, starts off, needs no other switch, is backed up and answers off while paused. */
    @Test public void showTheWholeReelSitsUnderFillTheScreenAndStandsAlone() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        android.preference.Preference fill = page.getPreferenceScreen().findPreference(Settings.FILL_REEL_SCREEN.key);
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.FIT_REEL_SCREEN.key);
        assertNotNull(row);
        PreferenceGroup group = (PreferenceGroup) fill.getParent();
        assertSame(group, row.getParent());
        int at = -1;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == fill) at = i;
        }
        assertSame("right under Fill the screen", row, group.getPreference(at + 1));
        assertEquals("Show the whole reel", row.getTitle().toString());
        assertEquals("Every reel fits inside the screen, with black bars where its shape differs, so nothing is cut off at "
                + "the sides. Works without Full screen Reels and wins over Fill the screen.", row.getSummary().toString());
        assertFalse(Settings.FIT_REEL_SCREEN.defaultValue);
        assertFalse(Settings.FULL_SCREEN_REELS.get());
        assertTrue("needs no other switch", Settings.FIT_REEL_SCREEN.isAvailable());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FIT_REEL_SCREEN.key));

        Settings.FIT_REEL_SCREEN.save(true);
        assertTrue(Settings.FIT_REEL_SCREEN.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FIT_REEL_SCREEN.get());
        assertTrue(Settings.FIT_REEL_SCREEN.savedValue());
    }
}
