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
import java.util.Collections;
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

/** The Copy links from chat buttons switch (#852): under Messages after Export a chat as text, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ChatButtonLinksSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.COPY_CHAT_BUTTON_LINKS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.COPY_CHAT_BUTTON_LINKS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> families) throws Exception {
        PatchFamily.inBuildForTests = families;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void aBuildWithoutThePatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.CHAT_HISTORY));
        assertNull(page.getPreferenceScreen().findPreference(Settings.COPY_CHAT_BUTTON_LINKS.key));
    }
    @Test public void theSwitchStartsOffAfterExportUnderMessagesPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.CHAT_HISTORY, PatchFamily.CHAT_BUTTON_LINKS));
        SwitchPreference export = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.EXPORT_CHAT.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COPY_CHAT_BUTTON_LINKS.key);
        assertNotNull(row);
        assertEquals("Copy links from chat buttons", row.getTitle().toString());
        assertTrue(String.valueOf(row.getSummary()), String.valueOf(row.getSummary()).contains("Copy link"));
        PreferenceGroup section = StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row);
        assertEquals("Messages", String.valueOf(section.getTitle()));
        assertEquals(indexIn(section, export) + 1, indexIn(section, row));
        assertFalse(row.isChecked());
        assertFalse(Settings.COPY_CHAT_BUTTON_LINKS.get());
        assertEquals(Collections.singletonList(Settings.COPY_CHAT_BUTTON_LINKS), PatchFamily.CHAT_BUTTON_LINKS.switches);
        assertEquals("Copy links from chat buttons", PatchFamily.CHAT_BUTTON_LINKS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.COPY_CHAT_BUTTON_LINKS.key));

        Settings.COPY_CHAT_BUTTON_LINKS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.COPY_CHAT_BUTTON_LINKS.get());
        assertTrue(Settings.COPY_CHAT_BUTTON_LINKS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.COPY_CHAT_BUTTON_LINKS.get());
    }
    private static int indexIn(PreferenceGroup section, Preference row) {
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            if (section.getPreference(i) == row) return i;
        }
        return -1;
    }
}
