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

/**
 * Hide the Like button on feed posts (#668) sits under Feed right after the Save button's switch,
 * starts off, is kept in a backup and comes with the Save in the post menu patch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedLikeButtonSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_FEED_LIKE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_FEED_LIKE_BUTTON.resetToDefault();
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
    @Test public void itIsUnderFeedAfterTheSaveSwitchAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SAVE));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_LIKE_BUTTON.key);
        assertNotNull(row);
        assertEquals("Hide the Like button", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("Double tap still likes a post."));
        PreferenceGroup feed = (PreferenceGroup) row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        assertEquals(indexIn(feed, Settings.HIDE_FEED_SAVE_BUTTON.key) + 1, indexIn(feed, Settings.HIDE_FEED_LIKE_BUTTON.key));
        assertFalse(Settings.HIDE_FEED_LIKE_BUTTON.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_FEED_LIKE_BUTTON.key));
        assertTrue(PatchFamily.FEED_SAVE.switches.contains(Settings.HIDE_FEED_LIKE_BUTTON));
    }
    @Test public void withoutSaveInThePostMenuThereIsNoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_LIKE_BUTTON.key));
    }
}
