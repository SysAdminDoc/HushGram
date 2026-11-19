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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide New message button and suggestions' two switches: under Messages, side by side, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class NewMessageSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_NEW_MESSAGE_BUTTON.resetToDefault();
        Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_NEW_MESSAGE_BUTTON.resetToDefault();
        Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.NEW_MESSAGE) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoNewMessageSwitches() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NEW_MESSAGE_BUTTON.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.key));
    }
    @Test public void bothSwitchesStartOffUnderMessagesSideBySideAndHonorPause() throws Exception {
        open(true);
        SwitchPreference button = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_NEW_MESSAGE_BUTTON.key);
        SwitchPreference picker = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.key);
        assertNotNull(button);
        assertNotNull(picker);
        assertEquals("Hide the New message button", button.getTitle().toString());
        assertEquals("Hide suggestions in New message", picker.getTitle().toString());
        PreferenceGroup messages = button.getParent();
        assertEquals("Messages", messages.getTitle().toString());
        assertSame(messages, picker.getParent());
        int at = -1;
        for (int i = 0; i < messages.getPreferenceCount(); i++) {
            Preference row = messages.getPreference(i);
            if (row == button) at = i;
        }
        assertSame("the suggestions switch comes right after the button's", picker, messages.getPreference(at + 1));
        assertFalse(button.isChecked());
        assertFalse(picker.isChecked());
        assertEquals(Arrays.asList(Settings.HIDE_NEW_MESSAGE_BUTTON, Settings.HIDE_NEW_MESSAGE_SUGGESTIONS), PatchFamily.NEW_MESSAGE.switches);

        for (BooleanSetting setting : Arrays.asList(Settings.HIDE_NEW_MESSAGE_BUTTON, Settings.HIDE_NEW_MESSAGE_SUGGESTIONS)) {
            assertFalse(setting.get());
            assertTrue(ConfigurationBackup.eligible().containsKey(setting.key));
            setting.save(true);
            assertTrue(setting.get());
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertFalse(setting.get());
            assertTrue(setting.savedValue());
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
            assertTrue(setting.get());
        }
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
}
