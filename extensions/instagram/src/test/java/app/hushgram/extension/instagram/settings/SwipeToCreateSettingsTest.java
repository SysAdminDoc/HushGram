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

/** Stop swipe to create's switch: under Feed on its own, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SwipeToCreateSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.STOP_SWIPE_TO_CREATE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PatchFamily.homeLeftSwipeForTests = null;
        Settings.STOP_SWIPE_TO_CREATE.resetToDefault();
        Settings.SWIPE_LEFT_MESSAGES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.SWIPE_TO_CREATE) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoSwipeSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.STOP_SWIPE_TO_CREATE.key));
    }
    @Test public void swipeSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STOP_SWIPE_TO_CREATE.key);
        assertNotNull(row);
        assertEquals("Stop swipe to create", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.STOP_SWIPE_TO_CREATE.get());
        assertEquals(java.util.Arrays.asList(Settings.STOP_SWIPE_TO_CREATE, Settings.SWIPE_LEFT_MESSAGES), PatchFamily.SWIPE_TO_CREATE.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STOP_SWIPE_TO_CREATE.key));

        Settings.STOP_SWIPE_TO_CREATE.save(true);
        assertTrue(Settings.STOP_SWIPE_TO_CREATE.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.STOP_SWIPE_TO_CREATE.get());
        assertTrue(Settings.STOP_SWIPE_TO_CREATE.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.STOP_SWIPE_TO_CREATE.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
    @Test public void missingPatchHasNoLeftSwipeSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.SWIPE_LEFT_MESSAGES.key));
    }
    @Test public void leftSwipeSwitchSitsUnderStopSwipeStartsOffAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SWIPE_LEFT_MESSAGES.key);
        assertNotNull(row);
        assertEquals("Swipe left for Messages", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Swiping left on Home opens Messages"));
        assertFalse(row.isChecked());
        assertFalse(Settings.SWIPE_LEFT_MESSAGES.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SWIPE_LEFT_MESSAGES.key));
        android.preference.Preference stop = page.getPreferenceScreen().findPreference(Settings.STOP_SWIPE_TO_CREATE.key);
        assertSame("both in one group", stop.getParent(), row.getParent());
        assertTrue("after Stop swipe to create", stop.getOrder() < row.getOrder());

        Settings.SWIPE_LEFT_MESSAGES.save(true);
        assertTrue(Settings.SWIPE_LEFT_MESSAGES.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.SWIPE_LEFT_MESSAGES.get());
        assertTrue(Settings.SWIPE_LEFT_MESSAGES.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.SWIPE_LEFT_MESSAGES.get());
    }
    /** A build whose touch hook didn't go in keeps Stop swipe to create and offers no left swipe that does nothing. */
    @Test public void aBuildWithoutTheLeftSwipeKeepsStopSwipeWithoutIt() throws Exception {
        PatchFamily.homeLeftSwipeForTests = false;
        open(true);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.STOP_SWIPE_TO_CREATE.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SWIPE_LEFT_MESSAGES.key));
        java.util.List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.SWIPE_TO_CREATE), false);
        assertTrue(report.toString(), report.contains("  Swipe left for Messages: not in this build "
                + "(the screens' touch dispatch didn't match)"));
    }
}
