/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
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

/** Swipe through a chat's reels' switch (#350): under Messages, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ChatReelsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        Settings.STOP_AFTER_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        Settings.STOP_AFTER_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.CHAT_REELS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoChatReelsSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.SWIPE_CHAT_REELS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STOP_AFTER_CHAT_REELS.key));
    }
    /** Stop after the chat's reels (#390) sits right under the swipe switch, off to start, off while paused. */
    @Test public void stopSwitchStartsOffUnderTheSwipeSwitchPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference swipe = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SWIPE_CHAT_REELS.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STOP_AFTER_CHAT_REELS.key);
        assertNotNull(row);
        assertEquals("Stop after the chat's reels", row.getTitle().toString());
        PreferenceGroup section = StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row);
        assertEquals("Messages", String.valueOf(section.getTitle()));
        assertEquals("right under the swipe switch", indexIn(section, swipe) + 1, indexIn(section, row));
        assertFalse(row.isChecked());
        assertFalse(Settings.STOP_AFTER_CHAT_REELS.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STOP_AFTER_CHAT_REELS.key));

        Settings.STOP_AFTER_CHAT_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.STOP_AFTER_CHAT_REELS.get());
        assertTrue(Settings.STOP_AFTER_CHAT_REELS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.STOP_AFTER_CHAT_REELS.get());
    }
    private static int indexIn(PreferenceGroup section, Preference row) {
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            if (section.getPreference(i) == row) return i;
        }
        return -1;
    }
    @Test public void chatReelsSwitchStartsOffUnderMessagesPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SWIPE_CHAT_REELS.key);
        assertNotNull(row);
        assertEquals("Swipe through a chat's reels", row.getTitle().toString());
        assertEquals("Messages", String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
        assertFalse(row.isChecked());
        assertFalse(Settings.SWIPE_CHAT_REELS.get());
        assertEquals(Arrays.asList(Settings.SWIPE_CHAT_REELS, Settings.STOP_AFTER_CHAT_REELS), PatchFamily.CHAT_REELS.switches);
        assertEquals("Swipe through a chat's reels", PatchFamily.CHAT_REELS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SWIPE_CHAT_REELS.key));

        Settings.SWIPE_CHAT_REELS.save(false);
        assertFalse(Settings.SWIPE_CHAT_REELS.savedValue());
        Settings.SWIPE_CHAT_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.SWIPE_CHAT_REELS.get());
        assertTrue(Settings.SWIPE_CHAT_REELS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.SWIPE_CHAT_REELS.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }

}
