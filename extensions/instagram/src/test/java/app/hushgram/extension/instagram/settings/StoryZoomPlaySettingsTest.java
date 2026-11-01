/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
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

/** Keep stories playing while zooming: under Stories, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryZoomPlaySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_STORIES_PLAYING_ON_ZOOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_STORIES_PLAYING_ON_ZOOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.STORY_ZOOM_PLAY) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoSmoothStoryOpenSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_STORIES_PLAYING_ON_ZOOM.key));
    }
    @Test public void smoothStoryOpenSwitchStartsOffUnderStoriesPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_STORIES_PLAYING_ON_ZOOM.key);
        assertNotNull(row);
        assertEquals("Keep stories playing while zooming", row.getTitle().toString());
        assertEquals("Stories", String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
        assertFalse(row.isChecked());
        assertFalse(Settings.KEEP_STORIES_PLAYING_ON_ZOOM.get());
        assertEquals(Collections.singletonList(Settings.KEEP_STORIES_PLAYING_ON_ZOOM), PatchFamily.STORY_ZOOM_PLAY.switches);
        assertEquals("Keep stories playing while zooming", PatchFamily.STORY_ZOOM_PLAY.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_STORIES_PLAYING_ON_ZOOM.key));

        Settings.KEEP_STORIES_PLAYING_ON_ZOOM.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.KEEP_STORIES_PLAYING_ON_ZOOM.get());
        assertTrue(Settings.KEEP_STORIES_PLAYING_ON_ZOOM.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.KEEP_STORIES_PLAYING_ON_ZOOM.get());
    }
}
