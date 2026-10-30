/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
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
import app.hushgram.extension.shared.settings.PauseForTests;

/** #244: the switch also empties highlights on profiles, so its name and description say so and a search for them finds it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StopLoadingStoriesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.STOP_LOADING_STORIES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.STOP_LOADING_STORIES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORIES_TRAY);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void theNameAndDescriptionMentionHighlights() throws Exception {
        open();
        Preference row = page.getPreferenceScreen().findPreference(Settings.STOP_LOADING_STORIES.key);
        assertNotNull(row);
        assertEquals("Stop loading stories and highlights", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("highlights on profiles stay empty too"));
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.STOP_LOADING_STORIES.get());
    }
    @Test public void searchingForHighlightsFindsIt() throws Exception {
        open();
        Preference row = page.getPreferenceScreen().findPreference(Settings.STOP_LOADING_STORIES.key);
        page.searchSettings("screenshots");
        assertNull("a search for something else leaves it out", row.getParent());
        page.searchSettings("highlights");
        assertNotNull(row.getParent());
        page.searchSettings("");
    }
}
