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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class DmMediaSeenSettingsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.resetToDefault();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        PauseForTests.resume();
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.DM_MEDIA_SEEN)
                : EnumSet.of(PatchFamily.STORY_SEEN);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void storyPatchAloneDoesNotShowOrEnableDirectMediaPrivacy() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.VIEW_DM_MEDIA_ANONYMOUSLY.key));
        assertFalse(Settings.VIEW_DM_MEDIA_ANONYMOUSLY.get());
        assertFalse("the story switch keeps its own default", Settings.VIEW_STORIES_ANONYMOUSLY.get());
    }

    @Test public void directMediaPatchHasItsOwnOffByDefaultSwitchAndPausePreservesChoice() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.VIEW_DM_MEDIA_ANONYMOUSLY.key);
        assertNotNull(row);
        assertEquals("View DM photos and videos anonymously", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertEquals("Stops people seeing that you opened their view once photos and videos. They still disappear "
                + "after you view them. This is a test feature and starts off.", row.getSummary().toString());
        assertEquals(1, PatchFamily.DM_MEDIA_SEEN.switches.size());
        assertSame(Settings.VIEW_DM_MEDIA_ANONYMOUSLY, PatchFamily.DM_MEDIA_SEEN.switches.get(0));
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.VIEW_DM_MEDIA_ANONYMOUSLY.get());
        assertTrue(Settings.VIEW_DM_MEDIA_ANONYMOUSLY.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.VIEW_DM_MEDIA_ANONYMOUSLY.get());
        assertFalse("the story switch keeps its own default", Settings.VIEW_STORIES_ANONYMOUSLY.get());
    }
}
