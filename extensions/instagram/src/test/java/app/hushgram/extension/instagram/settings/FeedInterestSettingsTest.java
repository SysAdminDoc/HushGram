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

/** Hide 'Are you interested?' bubbles sits under Feed with Hide suggested posts' switches, starts off and is kept in a backup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedInterestSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_FEED_INTEREST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_FEED_INTEREST.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void itIsUnderFeedAfterHideShoppingAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SUGGESTIONS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_INTEREST.key);
        assertNotNull(row);
        assertEquals("Hide 'Are you interested?' bubbles", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("Nothing is answered for you."));
        PreferenceGroup feed = (PreferenceGroup) row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        int at = -1, shoppingAt = -1;
        for (int i = 0; i < feed.getPreferenceCount(); i++) {
            String key = feed.getPreference(i).getKey();
            if (Settings.HIDE_FEED_INTEREST.key.equals(key)) at = i;
            if (Settings.HIDE_FEED_SHOPPING.key.equals(key)) shoppingAt = i;
        }
        assertTrue("Hide shopping is in Feed", shoppingAt >= 0);
        assertEquals("right after Hide shopping", shoppingAt + 1, at);
        assertFalse(Settings.HIDE_FEED_INTEREST.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_FEED_INTEREST.key));
        assertTrue(PatchFamily.FEED_SUGGESTIONS.switches.contains(Settings.HIDE_FEED_INTEREST));
    }
    @Test public void withoutHideSuggestedPostsThereIsNoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_INTEREST.key));
    }
}
