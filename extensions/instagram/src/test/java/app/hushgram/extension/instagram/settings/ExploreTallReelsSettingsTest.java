/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide tall reels in Explore (#880): an Explore row that starts off, follows the You may also like row, needs no restart, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ExploreTallReelsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_EXPLORE_TALL_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_EXPLORE_TALL_REELS.resetToDefault();
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
    @Test public void theSwitchIsUnderExploreAfterYouMayAlsoLikeAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.EXPLORE_GRID));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_EXPLORE_TALL_REELS.key);
        assertNotNull(row);
        assertEquals("Hide tall reels in Explore", row.getTitle().toString());
        assertEquals("Explore", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.HIDE_EXPLORE_TALL_REELS.get());
        assertFalse(Settings.HIDE_EXPLORE_TALL_REELS.rebootApp);
        PreferenceGroup explore = row.getParent();
        String[] keys = new String[explore.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = explore.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), Settings.HIDE_SEARCH_RECOMMENDATIONS.key, keys[Arrays.asList(keys).indexOf(Settings.HIDE_EXPLORE_TALL_REELS.key) - 1]);
        assertTrue(PatchFamily.EXPLORE_GRID.switches.contains(Settings.HIDE_EXPLORE_TALL_REELS));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_EXPLORE_TALL_REELS.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.EXPLORE_GRID));
        Settings.HIDE_EXPLORE_TALL_REELS.save(true);
        assertTrue(Settings.HIDE_EXPLORE_TALL_REELS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_EXPLORE_TALL_REELS.get());
        assertTrue(Settings.HIDE_EXPLORE_TALL_REELS.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.RECENT_SEARCHES));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_EXPLORE_TALL_REELS.key));
    }
}
