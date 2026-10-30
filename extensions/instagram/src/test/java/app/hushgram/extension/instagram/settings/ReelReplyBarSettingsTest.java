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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide the reply bar on reels from chats sits under Reels after the comment bar's switches, starts off, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelReplyBarSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_REEL_REPLY_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_REEL_REPLY_BAR.resetToDefault();
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
    @Test public void itIsUnderReelsAfterTheCommentBarSwitchesAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_REPLY_BAR.key);
        assertNotNull(row);
        assertEquals("Hide the reply bar on reels from chats", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("The chat's own message box stays."));
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        int everywhere = -1;
        int reply = -1;
        int topBar = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.key.equals(key)) everywhere = i;
            if (Settings.HIDE_REEL_REPLY_BAR.key.equals(key)) reply = i;
            if (Settings.HIDE_REELS_TOP_BAR.key.equals(key)) topBar = i;
        }
        assertTrue("the comment bar's switch for all reels is there", everywhere >= 0);
        assertEquals("right after Hide the comment bar on all reels", everywhere + 1, reply);
        assertEquals("and before Hide the top bar on the Reels tab", reply + 1, topBar);
        assertFalse(Settings.HIDE_REEL_REPLY_BAR.defaultValue);
        assertFalse(Settings.HIDE_REEL_REPLY_BAR.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_REEL_REPLY_BAR.key));
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.HIDE_REEL_REPLY_BAR));
    }
    @Test public void theSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Settings.HIDE_REEL_REPLY_BAR.save(true);
        assertTrue(Settings.HIDE_REEL_REPLY_BAR.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_REEL_REPLY_BAR.get());
        assertTrue(Settings.HIDE_REEL_REPLY_BAR.savedValue());
    }
    @Test public void withoutCleanUpReelsThereIsNoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_REPLY_BAR.key));
    }
}
