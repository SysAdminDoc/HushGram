/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.reels.BurnInMode;
import app.hushgram.extension.instagram.reels.BurnInWait;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Burn-in guard (#162) is a list after Clean up Reels' switches under Reels, starts off, says what each choice does and is backed up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class BurnInGuardSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void itFollowsCleanUpReelsUnderReelsAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference found = page.getPreferenceScreen().findPreference(Settings.BURN_IN_GUARD.key);
        assertTrue(found instanceof HushgramPreferenceFragment.BurnInGuardRow);
        ListPreference row = (ListPreference) found;
        assertEquals("Burn-in guard", row.getTitle().toString());
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        int save = -1;
        int guard = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.HIDE_REEL_SAVE_BUTTON.key.equals(key)) save = i;
            if (Settings.BURN_IN_GUARD.key.equals(key)) guard = i;
        }
        assertEquals("right after Hide the Save button on reels", save + 1, guard);

        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(entry.toString());
        assertEquals(Arrays.asList("Off", "Dim when idle", "Dim when idle and shift"), entries);
        assertEquals(BurnInMode.OFF, Settings.BURN_IN_GUARD.get());
        assertEquals("OFF", row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Off."));

        row.setValue("DIM_AND_SHIFT");
        ShadowLooper.idleMainLooper();
        assertEquals(BurnInMode.DIM_AND_SHIFT, Settings.BURN_IN_GUARD.savedValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("every 2 minutes"));
        row.setValue("DIM");
        assertFalse(row.getSummary().toString(), row.getSummary().toString().contains("every 2 minutes"));
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("leave them alone"));

        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.BURN_IN_GUARD.key));
    }
    /** Dim after (#162) follows Burn-in guard, starts at the 5 seconds it always waited and says what doesn't count as a touch. */
    @Test public void dimAfterFollowsBurnInGuardAndStartsAtFiveSeconds() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference found = page.getPreferenceScreen().findPreference(Settings.BURN_IN_WAIT.key);
        assertTrue(found instanceof HushgramPreferenceFragment.BurnInWaitRow);
        ListPreference row = (ListPreference) found;
        assertEquals("Dim after", row.getTitle().toString());
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        int guard = -1;
        int wait = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.BURN_IN_GUARD.key.equals(key)) guard = i;
            if (Settings.BURN_IN_WAIT.key.equals(key)) wait = i;
        }
        assertEquals("right after Burn-in guard", guard + 1, wait);

        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(entry.toString());
        assertEquals(Arrays.asList("At once", "After 2 seconds", "After 5 seconds", "After 10 seconds"), entries);
        assertEquals(BurnInWait.FIVE_SECONDS, Settings.BURN_IN_WAIT.get());
        String summary = row.getSummary().toString();
        assertTrue(summary, summary.startsWith("After 5 seconds. Tapping the video"));
        assertTrue(summary, summary.contains("Save and Download"));

        row.setValue("AT_ONCE");
        ShadowLooper.idleMainLooper();
        assertEquals(BurnInWait.AT_ONCE, Settings.BURN_IN_WAIT.savedValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("At once. "));

        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.BURN_IN_WAIT.key));
    }
    /** Stay dim when I tap (#162) is a switch right after Dim after, off to start, and backed up. */
    @Test public void stayDimFollowsDimAfterAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference found = page.getPreferenceScreen().findPreference(Settings.BURN_IN_STAY_DIM.key);
        assertTrue(found instanceof android.preference.SwitchPreference);
        android.preference.SwitchPreference row = (android.preference.SwitchPreference) found;
        assertEquals("Stay dim when I tap", row.getTitle().toString());
        assertEquals("Once Burn-in guard fades the buttons, they stay faded on Reels whatever you tap or swipe, the "
                + "next reel too. They still work. Leaving Reels brings them back.", row.getSummary().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.BURN_IN_STAY_DIM.defaultValue);
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        int wait = -1;
        int stay = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.BURN_IN_WAIT.key.equals(key)) wait = i;
            if (Settings.BURN_IN_STAY_DIM.key.equals(key)) stay = i;
        }
        assertEquals("right after Dim after", wait + 1, stay);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.BURN_IN_STAY_DIM.key));
    }
    @Test public void withoutCleanUpReelsThereIsNoRowAndNothingToBackUp() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.BURN_IN_GUARD.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.BURN_IN_WAIT.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.BURN_IN_STAY_DIM.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.BURN_IN_GUARD.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.BURN_IN_WAIT.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.BURN_IN_STAY_DIM.key));
    }
}
