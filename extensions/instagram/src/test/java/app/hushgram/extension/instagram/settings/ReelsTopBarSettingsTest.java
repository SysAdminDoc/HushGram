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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide the top bar on the Reels tab sits under Reels with Clean up Reels' other switches, starts off, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelsTopBarSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_REELS_TOP_BAR.resetToDefault();
        Settings.HIDE_REELS_CREATE_BUTTON.resetToDefault();
        Settings.HIDE_REELS_YOUR_ALGORITHM.resetToDefault();
        Settings.HIDE_REELS_FRIENDS_TAB.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_REELS_TOP_BAR.resetToDefault();
        Settings.HIDE_REELS_CREATE_BUTTON.resetToDefault();
        Settings.HIDE_REELS_YOUR_ALGORITHM.resetToDefault();
        Settings.HIDE_REELS_FRIENDS_TAB.resetToDefault();
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
    @Test public void itIsUnderReelsAfterTheOtherCleanUpSwitchesAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_TOP_BAR.key);
        assertNotNull(row);
        assertEquals("Hide the top bar on the Reels tab", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("The camera still opens from Home."));
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        Preference everywhere = page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.key);
        assertNotNull(everywhere);
        assertTrue("after the comment bar switches", row.getOrder() > everywhere.getOrder()
                || indexIn(reels, row) > indexIn(reels, everywhere));
        assertFalse(Settings.HIDE_REELS_TOP_BAR.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_REELS_TOP_BAR.key));
    }
    private static int indexIn(PreferenceGroup group, Preference wanted) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == wanted) return i;
        return -1;
    }
    @Test public void theSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Settings.HIDE_REELS_TOP_BAR.save(true);
        assertTrue(Settings.HIDE_REELS_TOP_BAR.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_REELS_TOP_BAR.get());
        assertTrue(Settings.HIDE_REELS_TOP_BAR.savedValue());
    }
    @Test public void withoutCleanUpReelsThereIsNoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_TOP_BAR.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_CREATE_BUTTON.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_YOUR_ALGORITHM.key));
    }
    /** The Create and Your algorithm switches (#177, #217) follow the top bar's, each its own row. */
    @Test public void theButtonSwitchesFollowTheTopBarAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference bar = page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_TOP_BAR.key);
        Preference create = page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_CREATE_BUTTON.key);
        Preference algorithm = page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_YOUR_ALGORITHM.key);
        assertNotNull(create);
        assertNotNull(algorithm);
        assertEquals("Hide the Create button on reels", create.getTitle().toString());
        assertTrue(create.getSummary().toString(), create.getSummary().toString().contains("The rest of the bar stays."));
        assertEquals("Hide Your algorithm on reels", algorithm.getTitle().toString());
        PreferenceGroup reels = (PreferenceGroup) bar.getParent();
        assertEquals(indexIn(reels, bar) + 1, indexIn(reels, create));
        assertEquals(indexIn(reels, create) + 1, indexIn(reels, algorithm));
        for (BooleanSetting setting : new BooleanSetting[] {Settings.HIDE_REELS_CREATE_BUTTON, Settings.HIDE_REELS_YOUR_ALGORITHM}) {
            assertFalse(setting.key, setting.get());
            assertTrue(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
        }
    }

    /** The Friends tab switch (#300) comes right after Your algorithm, off, and goes with the patch. */
    @Test public void theFriendsTabSwitchFollowsYourAlgorithmAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference algorithm = page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_YOUR_ALGORITHM.key);
        Preference friends = page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_FRIENDS_TAB.key);
        assertNotNull(friends);
        assertEquals("Hide the Friends tab on Reels", friends.getTitle().toString());
        assertTrue(friends.getSummary().toString(), friends.getSummary().toString().contains("Reels stays where it is."));
        PreferenceGroup reels = (PreferenceGroup) algorithm.getParent();
        assertEquals(indexIn(reels, algorithm) + 1, indexIn(reels, friends));
        assertFalse(Settings.HIDE_REELS_FRIENDS_TAB.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_REELS_FRIENDS_TAB.key));
        controller.close();
        controller = null;
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_FRIENDS_TAB.key));
    }
}
