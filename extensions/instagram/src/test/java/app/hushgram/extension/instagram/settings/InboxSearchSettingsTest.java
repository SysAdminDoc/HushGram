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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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

/** Hide the search bar in Messages: under Messages on its own or after the notes row, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class InboxSearchSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_INBOX_SEARCH.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_INBOX_SEARCH.resetToDefault();
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
    @Test public void missingPatchHasNoSearchBarSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_INBOX_SEARCH.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_ROW.key));
    }
    @Test public void searchBarSwitchStartsOffUnderMessagesPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.INBOX_SEARCH));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_INBOX_SEARCH.key);
        assertNotNull(row);
        assertEquals("Hide the search bar in Messages", row.getTitle().toString());
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_INBOX_SEARCH.get());
        assertEquals(java.util.Collections.singletonList(Settings.HIDE_INBOX_SEARCH), PatchFamily.INBOX_SEARCH.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_INBOX_SEARCH.key));

        Settings.HIDE_INBOX_SEARCH.save(true);
        assertTrue(Settings.HIDE_INBOX_SEARCH.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_INBOX_SEARCH.get());
        assertTrue(Settings.HIDE_INBOX_SEARCH.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_INBOX_SEARCH.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
    @Test public void withTheNotesRowBothSitTogetherAtTheTopOfMessages() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW, PatchFamily.INBOX_SEARCH));
        Preference search = page.getPreferenceScreen().findPreference(Settings.HIDE_INBOX_SEARCH.key);
        PreferenceGroup messages = search.getParent();
        List<String> keys = new ArrayList<>();
        for (int at = 0; at < messages.getPreferenceCount(); at++) keys.add(messages.getPreference(at).getKey());
        assertEquals(java.util.Arrays.asList(Settings.HIDE_NOTES_ROW.key, Settings.HIDE_INBOX_SEARCH.key), keys);
    }
}
