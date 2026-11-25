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
import android.preference.Preference;
import android.preference.PreferenceCategory;
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
import java.util.EnumSet;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Where Match the video's frame rate sits under Layout (#868), and when it's offered. */
@RunWith(RobolectricTestRunner.class)
public class VideoFrameRateSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIGHEST_REFRESH_RATE.resetToDefault();
        Settings.MATCH_VIDEO_FRAME_RATE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIGHEST_REFRESH_RATE.resetToDefault();
        Settings.MATCH_VIDEO_FRAME_RATE.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.REFRESH_RATE) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void theSwitchBelongsToTheRefreshRatePatch() {
        assertEquals(Arrays.asList(Settings.HIGHEST_REFRESH_RATE, Settings.MATCH_VIDEO_FRAME_RATE), PatchFamily.REFRESH_RATE.switches);
        assertFalse(Settings.MATCH_VIDEO_FRAME_RATE.get());
        assertFalse("each frame reads it, so no restart prompt", Settings.MATCH_VIDEO_FRAME_RATE.rebootApp);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REFRESH_RATE);
        assertTrue("a backup carries it", ConfigurationBackup.eligible().containsKey(Settings.MATCH_VIDEO_FRAME_RATE.key));
    }

    @Test @Config(sdk = 37) public void missingPatchHasNoRow() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.MATCH_VIDEO_FRAME_RATE.key));
    }

    /** It sits right under Use the highest refresh rate, starts off and keeps its own value. */
    @Test @Config(sdk = 37) public void theRowFollowsTheHighestRefreshRateAndStartsOff() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MATCH_VIDEO_FRAME_RATE.key);
        assertNotNull(row);
        assertEquals("Match the video's frame rate", row.getTitle().toString());
        assertEquals("While a video plays, tells Android how many frames a second it has, so a screen that picks its "
                + "own refresh rate can choose one the video fits evenly.", row.getSummary().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals(Settings.HIGHEST_REFRESH_RATE.key, section.getPreference(indexOf(section, row) - 1).getKey());
        assertFalse(row.isChecked());
        row.setChecked(true);
        assertTrue(Settings.MATCH_VIDEO_FRAME_RATE.get());
        assertFalse("each keeps its own value", Settings.HIGHEST_REFRESH_RATE.get());
    }

    /** Before Android 15 nothing takes the request, so the switch isn't offered. */
    @Test @Config(sdk = 28) public void noRowBeforeAndroid15() throws Exception {
        open(true);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIGHEST_REFRESH_RATE.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.MATCH_VIDEO_FRAME_RATE.key));
    }

    private static int indexOf(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }

    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
        }
        return null;
    }
}
