/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
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
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Reels Threads switch (#765) sits on the Reels page right after Hide stories in Reels, starts off and is kept in a backup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelsThreadsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_REELS_THREADS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_REELS_THREADS.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void itIsOnTheReelsPageAfterStoriesInReelsAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REELS_SUGGESTIONS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_THREADS.key);
        assertNotNull(row);
        assertEquals("Hide Threads posts", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("onto Threads"));
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        int at = -1, storiesAt = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.HIDE_REELS_THREADS.key.equals(key)) at = i;
            if (Settings.HIDE_REELS_STORIES.key.equals(key)) storiesAt = i;
        }
        assertTrue("Hide stories in Reels is on Reels", storiesAt >= 0);
        assertEquals("right after Hide stories in Reels", storiesAt + 1, at);
        assertFalse(Settings.HIDE_REELS_THREADS.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_REELS_THREADS.key));
        assertTrue(PatchFamily.REELS_SUGGESTIONS.switches.contains(Settings.HIDE_REELS_THREADS));
    }
    @Test public void withoutTheReelsSuggestionsPatchThereIsNoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_THREADS.key));
    }
}
