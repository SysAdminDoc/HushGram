/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
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

/** Hide the notes row's switch: under Messages on its own, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class NotesRowSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_NOTES_ROW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_NOTES_ROW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.NOTES_ROW) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoNotesRowSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_ROW.key));
    }
    @Test public void theMapBubbleHasItsOwnSwitchRightAfterTheRow() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_MAP.key);
        assertNotNull(row);
        assertEquals("Hide the Map bubble", row.getTitle().toString());
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_NOTES_MAP.get());
        assertEquals(row.getParent().findPreference(Settings.HIDE_NOTES_ROW.key).getOrder() + 1, row.getOrder());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_NOTES_MAP.key));
    }
    @Test public void notesRowSwitchStartsOffUnderMessagesPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_ROW.key);
        assertNotNull(row);
        assertEquals("Hide the notes row", row.getTitle().toString());
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_NOTES_ROW.get());
        assertEquals(java.util.Arrays.asList(Settings.HIDE_NOTES_ROW, Settings.HIDE_NOTES_MAP), PatchFamily.NOTES_ROW.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_NOTES_ROW.key));

        Settings.HIDE_NOTES_ROW.save(true);
        assertTrue(Settings.HIDE_NOTES_ROW.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_NOTES_ROW.get());
        assertTrue(Settings.HIDE_NOTES_ROW.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_NOTES_ROW.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
}
