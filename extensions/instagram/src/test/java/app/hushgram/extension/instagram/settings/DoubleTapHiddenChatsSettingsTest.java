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

/** Double-tap Messages for hidden chats (#358): a Messages row right after Hidden chats that starts off, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class DoubleTapHiddenChatsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.DOUBLE_TAP_HIDDEN_CHATS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.DOUBLE_TAP_HIDDEN_CHATS.resetToDefault();
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
    @Test public void theSwitchFollowsHiddenChatsUnderMessagesAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.MESSAGES_LOCK));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.DOUBLE_TAP_HIDDEN_CHATS.key);
        assertNotNull(row);
        assertEquals("Double-tap Messages for hidden chats", row.getTitle().toString());
        assertEquals("Messages", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.DOUBLE_TAP_HIDDEN_CHATS.get());
        PreferenceGroup messages = row.getParent();
        String[] keys = new String[messages.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = messages.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), HushgramPreferenceFragment.HIDDEN_CHATS_ROW,
                keys[Arrays.asList(keys).indexOf(Settings.DOUBLE_TAP_HIDDEN_CHATS.key) - 1]);
        assertTrue(PatchFamily.MESSAGES_LOCK.switches.contains(Settings.DOUBLE_TAP_HIDDEN_CHATS));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DOUBLE_TAP_HIDDEN_CHATS.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.MESSAGES_LOCK));
        Settings.DOUBLE_TAP_HIDDEN_CHATS.save(true);
        assertTrue(Settings.DOUBLE_TAP_HIDDEN_CHATS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.DOUBLE_TAP_HIDDEN_CHATS.get());
        assertTrue(Settings.DOUBLE_TAP_HIDDEN_CHATS.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_LOOP));
        assertNull(page.getPreferenceScreen().findPreference(Settings.DOUBLE_TAP_HIDDEN_CHATS.key));
    }
}
