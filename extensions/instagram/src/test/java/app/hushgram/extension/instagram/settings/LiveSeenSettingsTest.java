/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
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

/** #412: the live Mark as seen button's switch: under Stories after View live anonymously, off to start, off while paused, never part of Ghost mode. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class LiveSeenSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.MARK_LIVE_SEEN.resetToDefault();
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.MARK_LIVE_SEEN.resetToDefault();
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.LIVE_SEEN) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoLiveButtonSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_LIVE_SEEN.key));
        assertFalse(Settings.MARK_LIVE_SEEN.get());
    }
    @Test public void liveButtonSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference live = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.VIEW_LIVE_ANONYMOUSLY.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_LIVE_SEEN.key);
        assertNotNull(live);
        assertNotNull(row);
        assertEquals("Mark as seen button on lives", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.MARK_LIVE_SEEN.get());
        assertEquals(Arrays.asList(Settings.VIEW_LIVE_ANONYMOUSLY, Settings.MARK_LIVE_SEEN), PatchFamily.LIVE_SEEN.switches);
        assertEquals("Ghost mode turns on the live switch alone", java.util.Collections.singletonList(Settings.VIEW_LIVE_ANONYMOUSLY),
                GhostMode.switches(EnumSet.of(PatchFamily.LIVE_SEEN)));
        Settings.MARK_LIVE_SEEN.save(true);
        assertTrue(Settings.MARK_LIVE_SEEN.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.MARK_LIVE_SEEN.get());
        assertTrue(Settings.MARK_LIVE_SEEN.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.MARK_LIVE_SEEN.get());
    }
}
