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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
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
import app.hushgram.extension.instagram.stories.StoryTimeMode;

/**
 * Show a story's exact time's switch: under Stories, on to start, off while paused. Its choice of
 * how the time shows sits right below it and starts at the date and time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryTimeSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.SHOW_STORY_TIME.resetToDefault();
        Settings.STORY_TIME_MODE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SHOW_STORY_TIME.resetToDefault();
        Settings.STORY_TIME_MODE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.STORY_TIME) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoStoryTimeSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.SHOW_STORY_TIME.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_TIME_MODE.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_TIME_MODE.key));
    }
    @Test public void storyTimeSwitchStartsOnUnderStoriesPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_STORY_TIME.key);
        assertNotNull(row);
        assertEquals("Show a story's exact time", row.getTitle().toString());
        assertEquals("Stories", String.valueOf(sectionOf(page.getPreferenceScreen(), row).getTitle()));
        assertTrue(row.isChecked());
        assertTrue(Settings.SHOW_STORY_TIME.get());
        assertEquals(Collections.singletonList(Settings.SHOW_STORY_TIME), PatchFamily.STORY_TIME.switches);
        assertEquals("Show a story's exact time", PatchFamily.STORY_TIME.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SHOW_STORY_TIME.key));

        Settings.SHOW_STORY_TIME.save(false);
        assertFalse(Settings.SHOW_STORY_TIME.savedValue());
        Settings.SHOW_STORY_TIME.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.SHOW_STORY_TIME.get());
        assertTrue(Settings.SHOW_STORY_TIME.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.SHOW_STORY_TIME.get());
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }

    @Test public void theChoiceSitsBelowTheSwitchStartsAtTheDateAndTimeAndSaysWhatItShows() throws Exception {
        open(true);
        Preference found = page.getPreferenceScreen().findPreference(Settings.STORY_TIME_MODE.key);
        assertTrue("no choice of how with the patch in the build", found instanceof HushgramPreferenceFragment.StoryTimeModeRow);
        HushgramPreferenceFragment.StoryTimeModeRow row = (HushgramPreferenceFragment.StoryTimeModeRow) found;
        assertEquals("How the time shows", String.valueOf(row.getTitle()));
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), row);
        int switchAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            if (Settings.SHOW_STORY_TIME.key.equals(section.getPreference(i).getKey())) switchAt = i;
        }
        assertTrue("the switch is in the choice's section", switchAt >= 0);
        assertEquals("right below the switch", row, section.getPreference(switchAt + 1));

        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Date and time", "Time left", "Time posted"), entries);
        List<String> values = new ArrayList<>();
        for (CharSequence value : row.getEntryValues()) values.add(String.valueOf(value));
        List<String> names = new ArrayList<>();
        for (StoryTimeMode each : StoryTimeMode.values()) names.add(each.name());
        assertEquals(names, values);

        assertEquals("DATE_AND_TIME", row.getValue());
        assertEquals("The date and time the story was posted, like Oct 2, 3:45 PM.", String.valueOf(row.getSummary()));

        row.setValue("TIME_LEFT");
        ShadowLooper.idleMainLooper();
        assertEquals(StoryTimeMode.TIME_LEFT, Settings.STORY_TIME_MODE.savedValue());
        assertEquals("How long until the story expires, like 18h 14m left. Older stories show the date and time.",
                String.valueOf(row.getSummary()));

        row.setValue("TIME_POSTED");
        ShadowLooper.idleMainLooper();
        assertEquals(StoryTimeMode.TIME_POSTED, Settings.STORY_TIME_MODE.savedValue());
        assertEquals("Only the time the story was posted, like 3:45 PM. Stories posted before today show the date and time.",
                String.valueOf(row.getSummary()));

        // A value saved behind the row shows on it once the page hears of it.
        Settings.STORY_TIME_MODE.save(StoryTimeMode.TIME_LEFT);
        ShadowLooper.idleMainLooper();
        assertEquals("TIME_LEFT", row.getValue());

        // With the switch off, the choice asks for it and keeps what was picked.
        Settings.SHOW_STORY_TIME.save(false);
        ShadowLooper.idleMainLooper();
        row.showSummary();
        assertEquals("Turn on Show a story's exact time to use this choice.", String.valueOf(row.getSummary()));
        assertEquals(StoryTimeMode.TIME_LEFT, Settings.STORY_TIME_MODE.savedValue());

        // An exported configuration carries the choice with the switch.
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_TIME_MODE.key));
    }

    /** The section a row sits in, found from the screen down, which works on API 28 too. */
    static PreferenceGroup sectionOf(PreferenceGroup screen, Preference row) {
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference child = screen.getPreference(i);
            if (child instanceof PreferenceGroup && contains((PreferenceGroup) child, row)) return (PreferenceGroup) child;
        }
        throw new AssertionError("no section holds " + row.getKey());
    }

    private static boolean contains(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child == row) return true;
            if (child instanceof PreferenceGroup && contains((PreferenceGroup) child, row)) return true;
        }
        return false;
    }
}
