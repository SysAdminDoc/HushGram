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

/** Hide the Join bar on channel posts (#625) sits under Feed after Hide the caught up card, starts off and is kept in a backup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ChannelJoinBarSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_CHANNEL_JOIN_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_CHANNEL_JOIN_BAR.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void itIsUnderFeedAfterTheCaughtUpCardAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SUGGESTIONS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_CHANNEL_JOIN_BAR.key);
        assertNotNull(row);
        assertEquals("Hide the Join bar on channel posts", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("join the account's channel"));
        PreferenceGroup feed = (PreferenceGroup) row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        int at = -1, caughtUpAt = -1;
        for (int i = 0; i < feed.getPreferenceCount(); i++) {
            String key = feed.getPreference(i).getKey();
            if (Settings.HIDE_CHANNEL_JOIN_BAR.key.equals(key)) at = i;
            if (Settings.HIDE_CAUGHT_UP_CARD.key.equals(key)) caughtUpAt = i;
        }
        assertTrue("the caught up card row is in Feed", caughtUpAt >= 0);
        assertEquals("right after the caught up card row", caughtUpAt + 1, at);
        assertFalse(Settings.HIDE_CHANNEL_JOIN_BAR.get());
        assertFalse("each bind reads the switch, so no restart prompt", Settings.HIDE_CHANNEL_JOIN_BAR.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_CHANNEL_JOIN_BAR.key));
        assertTrue(PatchFamily.FEED_SUGGESTIONS.switches.contains(Settings.HIDE_CHANNEL_JOIN_BAR));
    }
    @Test public void withoutHideSuggestedPostsThereIsNoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_CHANNEL_JOIN_BAR.key));
    }
}
