/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
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

/** Hide Instants' switch: under Messages after the notes row, off to start, off while paused, applied on restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class InstantsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.HIDE_INSTANTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_INSTANTS.resetToDefault();
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
    @Test public void missingPatchHasNoInstantsSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_INSTANTS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_ROW.key));
    }
    /** Without the notes row patch, Messages still opens for Hide Instants alone. */
    @Test public void instantsAloneStillGetsMessages() throws Exception {
        open(EnumSet.of(PatchFamily.INSTANTS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_INSTANTS.key);
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_ROW.key));
    }
    @Test public void instantsSwitchStartsOffUnderMessagesPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW, PatchFamily.INSTANTS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_INSTANTS.key);
        assertNotNull(row);
        assertEquals("Hide Instants", row.getTitle().toString());
        PreferenceGroup messages = row.getParent();
        assertEquals("Messages", messages.getTitle().toString());
        String[] keys = new String[messages.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = messages.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.HIDE_NOTES_ROW.key, Settings.HIDE_INSTANTS.key), Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_INSTANTS.get());
        assertTrue("Instagram settles Instants when it starts", Settings.HIDE_INSTANTS.rebootApp);
        assertEquals(java.util.Collections.singletonList(Settings.HIDE_INSTANTS), PatchFamily.INSTANTS.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_INSTANTS.key));

        Settings.HIDE_INSTANTS.save(true);
        assertTrue(Settings.HIDE_INSTANTS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_INSTANTS.get());
        assertTrue(Settings.HIDE_INSTANTS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_INSTANTS.get());
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
}
