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

/** The chat history switches (#246): under Messages, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ChatHistorySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.JUMP_IN_CHAT.resetToDefault();
        Settings.EXPORT_CHAT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.JUMP_IN_CHAT.resetToDefault();
        Settings.EXPORT_CHAT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.CHAT_HISTORY) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoJumpSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.JUMP_IN_CHAT.key));
    }
    /** Export a chat as text (#247) sits under the jump switch, off to start, off while paused. */
    @Test public void exportSwitchStartsOffUnderTheJumpSwitchPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference jump = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.JUMP_IN_CHAT.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.EXPORT_CHAT.key);
        assertNotNull(row);
        assertEquals("Export a chat as text", row.getTitle().toString());
        PreferenceGroup section = StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row);
        assertEquals("Messages", String.valueOf(section.getTitle()));
        assertEquals(indexIn(section, jump) + 1, indexIn(section, row));
        assertFalse(row.isChecked());
        assertFalse(Settings.EXPORT_CHAT.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.EXPORT_CHAT.key));

        Settings.EXPORT_CHAT.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.EXPORT_CHAT.get());
        assertTrue(Settings.EXPORT_CHAT.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.EXPORT_CHAT.get());
    }
    private static int indexIn(PreferenceGroup section, Preference row) {
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            if (section.getPreference(i) == row) return i;
        }
        return -1;
    }
    @Test public void jumpSwitchStartsOffUnderMessagesPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.JUMP_IN_CHAT.key);
        assertNotNull(row);
        assertEquals("Jump to the first message or a date", row.getTitle().toString());
        assertEquals("Messages", String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
        assertFalse(row.isChecked());
        assertFalse(Settings.JUMP_IN_CHAT.get());
        assertEquals(Arrays.asList(Settings.JUMP_IN_CHAT, Settings.EXPORT_CHAT), PatchFamily.CHAT_HISTORY.switches);
        assertEquals("Chat history tools", PatchFamily.CHAT_HISTORY.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.JUMP_IN_CHAT.key));

        Settings.JUMP_IN_CHAT.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.JUMP_IN_CHAT.get());
        assertTrue(Settings.JUMP_IN_CHAT.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.JUMP_IN_CHAT.get());
    }
}
