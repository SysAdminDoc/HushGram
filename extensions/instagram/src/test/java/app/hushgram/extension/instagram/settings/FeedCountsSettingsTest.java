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
 * Hide counts on posts and Hide Liked by (#762) sit under Feed right after Hide the Like button,
 * start off, are kept in a backup and come with the Save in the post menu patch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedCountsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_FEED_COUNTS.resetToDefault();
        Settings.HIDE_LIKED_BY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_FEED_COUNTS.resetToDefault();
        Settings.HIDE_LIKED_BY.resetToDefault();
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
    @Test public void bothAreUnderFeedAfterTheLikeSwitchAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SAVE));
        Preference counts = page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_COUNTS.key);
        Preference likedBy = page.getPreferenceScreen().findPreference(Settings.HIDE_LIKED_BY.key);
        assertNotNull(counts);
        assertNotNull(likedBy);
        assertEquals("Hide counts on posts", counts.getTitle().toString());
        assertTrue(counts.getSummary().toString(), counts.getSummary().toString().contains("The buttons stay."));
        assertEquals("Hide Liked by", likedBy.getTitle().toString());
        PreferenceGroup feed = (PreferenceGroup) counts.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        int like = indexIn(feed, Settings.HIDE_FEED_LIKE_BUTTON.key);
        assertEquals(like + 1, indexIn(feed, Settings.HIDE_FEED_COUNTS.key));
        assertEquals(like + 2, indexIn(feed, Settings.HIDE_LIKED_BY.key));
        for (app.hushgram.extension.shared.settings.BooleanSetting setting
                : new app.hushgram.extension.shared.settings.BooleanSetting[]{Settings.HIDE_FEED_COUNTS, Settings.HIDE_LIKED_BY}) {
            assertFalse(setting.key, setting.get());
            assertTrue(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
            assertTrue(setting.key, PatchFamily.FEED_SAVE.switches.contains(setting));
        }
    }
    @Test public void withoutSaveInThePostMenuThereAreNoSwitches() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_COUNTS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_LIKED_BY.key));
    }
}
