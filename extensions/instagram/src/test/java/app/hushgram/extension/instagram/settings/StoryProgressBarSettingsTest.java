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
import java.util.Arrays;
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
import app.hushgram.extension.instagram.stories.StoryBarThickness;
import app.hushgram.extension.instagram.stories.StoryProgressBar;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Thicker story progress bar (#360): a Stories switch that starts off, a Thickness list under it, both kept in a backup, and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryProgressBarSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.THICKER_STORY_BAR.resetToDefault();
        Settings.STORY_BAR_THICKNESS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.THICKER_STORY_BAR.resetToDefault();
        Settings.STORY_BAR_THICKNESS.resetToDefault();
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
    @Test public void theSwitchAndItsListAreUnderStoriesAfterTheRingAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_RING, PatchFamily.STORY_PROGRESS_BAR));
        Preference row = page.getPreferenceScreen().findPreference(Settings.THICKER_STORY_BAR.key);
        assertNotNull(row);
        assertEquals("Thicker story progress bar", row.getTitle().toString());
        assertEquals("Draws the bar along the top of a story as thick as the choice below. Its segments "
                + "and timing stay as they were.", row.getSummary().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.THICKER_STORY_BAR.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.THICKER_STORY_BAR.key);
        // The ring's switch and its two lists come right before.
        assertEquals(Arrays.toString(keys), Settings.STORY_RING.key, keys[at - 3]);
        assertEquals(Arrays.toString(keys), Settings.STORY_RING_COLOR.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.STORY_BAR_THICKNESS.key, keys[at + 1]);

        HushgramPreferenceFragment.StoryBarRow thickness = (HushgramPreferenceFragment.StoryBarRow) stories.getPreference(at + 1);
        assertEquals("Thickness", thickness.getTitle().toString());
        assertEquals("Thickness", String.valueOf(thickness.getDialogTitle()));
        assertEquals(Arrays.asList("Twice as thick", "Three times as thick"),
                Arrays.asList(thickness.getEntries()[0].toString(), thickness.getEntries()[1].toString()));
        assertEquals(Arrays.asList("TWICE", "THREE_TIMES"),
                Arrays.asList(thickness.getEntryValues()[0].toString(), thickness.getEntryValues()[1].toString()));
        assertEquals("TWICE", thickness.getValue());
        assertEquals("Twice as thick", thickness.getSummary().toString());
        assertFalse("the list waits for the switch", thickness.isEnabled());
        ((SwitchPreference) row).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(thickness.isEnabled());
        thickness.setValue("THREE_TIMES");
        ShadowLooper.idleMainLooper();
        assertEquals(StoryBarThickness.THREE_TIMES, Settings.STORY_BAR_THICKNESS.savedValue());
        assertEquals("Three times as thick", thickness.getSummary().toString());

        assertEquals("Thicker story progress bar", PatchFamily.STORY_PROGRESS_BAR.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.THICKER_STORY_BAR.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_BAR_THICKNESS.key));
    }
    @Test public void eachThicknessIsTwiceOrThreeTimesTheBar() {
        assertEquals(2f, StoryBarThickness.TWICE.scale, 0f);
        assertEquals(3f, StoryBarThickness.THREE_TIMES.scale, 0f);
        assertEquals("Twice as thick", HushgramPreferenceFragment.storyBarLabel(StoryBarThickness.TWICE));
        assertEquals("Three times as thick", HushgramPreferenceFragment.storyBarLabel(StoryBarThickness.THREE_TIMES));
    }
    /** Settings are a dialog over Instagram's screen, which doesn't resume when they close, so flipping the switch on reaches it at once. */
    @Test public void flippingTheSwitchOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_PROGRESS_BAR));
        Preference row = page.getPreferenceScreen().findPreference(Settings.THICKER_STORY_BAR.key);
        try {
            assertFalse(StoryProgressBar.listeningForTests(controller.get()));
            assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
            // The switch saves after its listener, so the helper reads the value once the change is posted.
            Settings.THICKER_STORY_BAR.save(true);
            ShadowLooper.idleMainLooper();
            assertTrue(StoryProgressBar.listeningForTests(controller.get()));
        } finally {
            StoryProgressBar.resetForTests();
        }
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_PROGRESS_BAR));
        Settings.THICKER_STORY_BAR.save(true);
        assertTrue(Settings.THICKER_STORY_BAR.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.THICKER_STORY_BAR.get());
        assertTrue(Settings.THICKER_STORY_BAR.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_RING));
        assertNull(page.getPreferenceScreen().findPreference(Settings.THICKER_STORY_BAR.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_BAR_THICKNESS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.THICKER_STORY_BAR.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_BAR_THICKNESS.key));
    }
}
