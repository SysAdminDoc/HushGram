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

/** The Mark as seen button's switch: under Stories after View stories anonymously, off to start, and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StorySeenSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.MARK_STORIES_SEEN.resetToDefault();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.MARK_STORIES_SEEN.resetToDefault();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.STORY_SEEN) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoMarkAsSeenSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_STORIES_SEEN.key));
        assertFalse(Settings.MARK_STORIES_SEEN.get());
    }
    @Test public void markAsSeenSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference label = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.VIEW_STORIES_ANONYMOUSLY.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_STORIES_SEEN.key);
        assertNotNull(label);
        assertNotNull(row);
        assertFalse("View stories anonymously starts off", label.isChecked());
        assertEquals("Mark as seen button", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.MARK_STORIES_SEEN.get());
        List<BooleanSetting> switches = PatchFamily.STORY_SEEN.switches;
        assertEquals(Settings.VIEW_STORIES_ANONYMOUSLY, switches.get(0));
        assertEquals(Settings.MARK_STORIES_SEEN, switches.get(1));
        assertEquals(Settings.GRAY_OUT_WATCHED_STORIES, switches.get(2));
        SwitchPreference gray = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GRAY_OUT_WATCHED_STORIES.key);
        assertNotNull(gray);
        assertEquals("Gray out stories you've watched", gray.getTitle().toString());
        assertFalse("Gray out stories you've watched starts off", gray.isChecked());
        Settings.MARK_STORIES_SEEN.save(true);
        assertTrue(Settings.MARK_STORIES_SEEN.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.MARK_STORIES_SEEN.get());
        assertTrue(Settings.MARK_STORIES_SEEN.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.MARK_STORIES_SEEN.get());
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
}
