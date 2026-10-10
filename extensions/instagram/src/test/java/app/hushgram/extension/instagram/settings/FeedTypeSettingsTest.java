/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide videos, Hide photos, Hide carousels and Hide posts you've liked: in Feed with Hide suggested posts, off to start, and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedTypeSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final BooleanSetting[] SWITCHES =
            {Settings.HIDE_FEED_VIDEOS, Settings.HIDE_FEED_PHOTOS, Settings.HIDE_FEED_CAROUSELS, Settings.HIDE_FEED_LIKED};
    private static final String[] TITLES = {"Hide videos", "Hide photos", "Hide carousels", "Hide posts you've liked"};
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        for (BooleanSetting setting : SWITCHES) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PatchFamily.feedTypesForTests = null;
        for (BooleanSetting setting : SWITCHES) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.FEED_SUGGESTIONS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoTypeSwitches() throws Exception {
        open(false);
        for (BooleanSetting setting : SWITCHES) {
            assertNull(setting.key, page.getPreferenceScreen().findPreference(setting.key));
            assertFalse(setting.key, setting.get());
        }
    }
    @Test public void typeSwitchesStartOffPersistAndHonorPause() throws Exception {
        open(true);
        for (int i = 0; i < SWITCHES.length; i++) {
            SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(SWITCHES[i].key);
            assertNotNull(SWITCHES[i].key, row);
            assertEquals(TITLES[i], row.getTitle().toString());
            assertFalse(row.isChecked());
            assertTrue(PatchFamily.FEED_SUGGESTIONS.switches.contains(SWITCHES[i]));
        }
        Settings.HIDE_FEED_VIDEOS.save(true);
        assertTrue(Settings.HIDE_FEED_VIDEOS.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_FEED_VIDEOS.get());
        assertTrue(Settings.HIDE_FEED_VIDEOS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_FEED_VIDEOS.get());
    }
    @Test public void aBuildWhoseHomeReadsMovedKeepsTheSuggestionSwitchesWithoutTheTypes() throws Exception {
        PatchFamily.feedTypesForTests = false;
        open(true);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_SUGGESTED_POSTS.key));
        for (BooleanSetting setting : SWITCHES) assertNull(setting.key, page.getPreferenceScreen().findPreference(setting.key));
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.FEED_SUGGESTIONS), false);
        assertTrue(report.toString(), report.contains(
                "  Hide videos, Hide photos, Hide carousels, Hide posts you've liked and Hidden accounts: "
                        + "not in this build (Home's feed or a post's details didn't match)"));
    }
    @Test public void aBuildFilteringByTypeReportsNothingMissing() throws Exception {
        open(true);
        assertTrue(PatchFamily.feedTypesInBuild());
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.FEED_SUGGESTIONS), false);
        for (String line : report) assertFalse(line, line.contains("not in this build ("));
    }
}
