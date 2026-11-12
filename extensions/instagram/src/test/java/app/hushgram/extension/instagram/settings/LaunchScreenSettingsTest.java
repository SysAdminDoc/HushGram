/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

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

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** HushGram launch screen's switch: under Layout after the accent rows, off to start, backed up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class LaunchScreenSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.LAUNCH_SCREEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.LAUNCH_SCREEN.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.ACCENT_COLOR));
        assertNull(page.getPreferenceScreen().findPreference(Settings.LAUNCH_SCREEN.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.LAUNCH_SCREEN.key));
    }

    /** Layout opens for the launch screen alone. */
    @Test public void launchScreenAloneStillGetsLayout() throws Exception {
        open(EnumSet.of(PatchFamily.LAUNCH_SCREEN));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.LAUNCH_SCREEN.key);
        assertNotNull(row);
        assertEquals("Layout", row.getParent().getTitle().toString());
    }

    @Test public void switchStartsOffAfterTheAccentRowsAndIsBackedUp() throws Exception {
        open(EnumSet.of(PatchFamily.EMOJI_STYLE, PatchFamily.ACCENT_COLOR, PatchFamily.LAUNCH_SCREEN));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.LAUNCH_SCREEN.key);
        assertNotNull(row);
        assertEquals("HushGram launch screen", row.getTitle().toString());
        assertEquals("Shows HushGram's logo instead of Instagram's on the screen you see while Instagram starts, "
                + "on Android 13 and newer. It shows from the next start.", row.getSummary().toString());
        PreferenceGroup layout = row.getParent();
        String[] keys = new String[layout.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = layout.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.NOTO_EMOJI.key, Settings.EMOJI_FONT.key, Settings.ACCENT_COLOR.key, Settings.ACCENT_HUE.key, Settings.ACCENT_BADGES.key,
                Settings.ACCENT_ICONS.key,
                LayoutCategory.PURE_BLACK_HINT_ROW, Settings.LAUNCH_SCREEN.key), Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.LAUNCH_SCREEN.get());
        assertEquals(Collections.singletonList(Settings.LAUNCH_SCREEN), PatchFamily.LAUNCH_SCREEN.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.LAUNCH_SCREEN.key));

        row.setChecked(true);
        assertTrue(Settings.LAUNCH_SCREEN.savedValue());
    }
}
