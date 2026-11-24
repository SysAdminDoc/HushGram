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
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the Like button and Hide the Comment button on reels (#668) sit under Reels right before
 * the Save button's switch, start off and are kept in a backup.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelLikeCommentButtonsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_REEL_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_REEL_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_REEL_PAUSE_ICON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_REEL_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_REEL_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_REEL_PAUSE_ICON.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    private static int indexIn(PreferenceGroup group, String key) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (key.equals(group.getPreference(i).getKey())) return i;
        }
        return -1;
    }
    private void assertRow(BooleanSetting setting, String title, String summaryPart, int beforeSave) {
        Preference row = page.getPreferenceScreen().findPreference(setting.key);
        assertNotNull(title, row);
        assertEquals(title, row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains(summaryPart));
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        assertEquals(title + " sits before the Save button's switch",
                indexIn(reels, Settings.HIDE_REEL_SAVE_BUTTON.key) - beforeSave, indexIn(reels, setting.key));
        assertFalse(setting.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(setting.key));
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(setting));
    }
    @Test public void bothAreUnderReelsBeforeTheSaveSwitchAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        assertRow(Settings.HIDE_REEL_LIKE_BUTTON, "Hide the Like button on reels", "Double tap still likes a reel.", 2);
        assertRow(Settings.HIDE_REEL_COMMENT_BUTTON, "Hide the Comment button on reels", "has its own switch.", 1);
    }
    /** Hide the More button on reels (#647) comes right after the Save button's switch, the column's last button. */
    @Test public void theMoreSwitchFollowsTheSaveSwitchAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        assertRow(Settings.HIDE_REEL_MORE_BUTTON, "Hide the More button on reels", "the menu they open", -1);
    }
    /** Hide the pause icon on reels (#686) comes after the Save and More buttons' switches and the five count switches. */
    @Test public void thePauseIconSwitchFollowsTheSaveSwitchAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        assertRow(Settings.HIDE_REEL_PAUSE_ICON, "Hide the pause icon on reels", "Tap the reel to play it again.", -7);
    }
    /** The count-only switches (#719) follow the Save and More buttons' switches, one per button, in the column's order. */
    @Test public void theCountSwitchesFollowTheSaveSwitchAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        assertRow(Settings.HIDE_REEL_LIKE_COUNT, "Hide like counts on reels", "The heart stays.", -2);
        assertRow(Settings.HIDE_REEL_COMMENT_COUNT, "Hide comment counts on reels", "under the Comment button", -3);
        assertRow(Settings.HIDE_REEL_REPOST_COUNT, "Hide repost counts on reels", "under the Repost button", -4);
        assertRow(Settings.HIDE_REEL_SHARE_COUNT, "Hide share counts on reels", "under the Share button", -5);
        assertRow(Settings.HIDE_REEL_SAVE_COUNT, "Hide save counts on reels", "under the Save button", -6);
    }
    @Test public void withoutCleanUpReelsThereIsNoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_LIKE_BUTTON.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_COMMENT_BUTTON.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_PAUSE_ICON.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_MORE_BUTTON.key));
    }
}
