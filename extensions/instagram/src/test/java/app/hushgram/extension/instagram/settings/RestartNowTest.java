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
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Process;
import android.preference.Preference;
import android.preference.TwoStatePreference;
import android.view.View;
import android.widget.TextView;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowProcess;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.preference.ExportStatus;

/**
 * #146: a change Instagram only reads as it starts puts Restart now under the page, which opens
 * Instagram again in a fresh task and ends the process. Only on a tap, and not while a save or an
 * export would be cut off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36, 37})
public class RestartNowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final AtomicInteger ended = new AtomicInteger();
    private ComponentName launcher;

    @Before
    public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37; // Instagram 453's target on every runtime.
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FOLLOWING_FEED);
        RestartNow.endProcessForTests = ended::incrementAndGet;
        launcher = new ComponentName(RuntimeEnvironment.getApplication().getPackageName(),
                "com.instagram.mainactivity.LauncherActivity");
        IntentFilter main = new IntentFilter(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        ShadowPackageManager packages = Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager());
        packages.addActivityIfNotPresent(launcher);
        packages.addIntentFilterForActivity(launcher, main);
        ShadowToast.reset();
    }

    @After
    public void restore() {
        RestartNow.endProcessForTests = null;
        PatchFamily.inBuildForTests = null;
        Settings.START_ON_FOLLOWING.resetToDefault();
    }

    private static SettingsDialog open(ActivityController<Activity> controller) throws Exception {
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "restart_now");
        controller.get().getFragmentManager().executePendingTransactions();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        return dialog;
    }

    private static HushgramPreferenceFragment pageOf(SettingsDialog dialog) {
        return (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    @Test
    public void aChangeThatWaitsOnARestartShowsTheBarThatRestartsInstagram() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = open(controller);
            HushgramPreferenceFragment page = pageOf(dialog);
            int rows = page.getPreferenceScreen().getPreferenceCount();
            View bar = dialog.getView().findViewById(SettingsDialog.RESTART_ID);
            assertNull(page.getPreferenceScreen().findPreference("hushgram_status"));
            assertEquals("hushgram_settings_search", page.getPreferenceScreen().getPreference(0).getKey());
            assertEquals("nothing owed, so no bar", View.GONE, bar.getVisibility());

            TwoStatePreference following = (TwoStatePreference) page.findPreference(Settings.START_ON_FOLLOWING.key);
            following.setChecked(!following.isChecked());
            ShadowLooper.idleMainLooper();
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals("Some changes apply when Instagram restarts. Tap to restart it now.",
                    ((TextView) bar).getText().toString());
            assertEquals("the restart bar doesn't insert a row", rows, page.getPreferenceScreen().getPreferenceCount());
            assertNull("a pending restart doesn't bring the active banner back", page.getPreferenceScreen().findPreference("hushgram_status"));
            assertEquals("nothing restarts without the tap", 0, ended.get());

            assertTrue(bar.performClick());
            Intent started = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertNotNull("Instagram wasn't opened again", started);
            assertEquals(launcher, started.getComponent());
            assertEquals(launcher.getPackageName(), started.getPackage());
            assertTrue("a fresh task", (started.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
            assertEquals(1, ended.get());

            // Back where it started, nothing is owed and the bar goes.
            following.setChecked(!following.isChecked());
            ShadowLooper.idleMainLooper();
            assertEquals(View.GONE, bar.getVisibility());
        }
    }

    @Test
    public void aRunningExportKeepsInstagramUpAndSaysSo() throws Exception {
        String token = ExportStatus.CONFIGURATION.begin("Exporting", false);
        assertNotNull(token);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = open(controller);
            HushgramPreferenceFragment page = pageOf(dialog);
            TwoStatePreference following = (TwoStatePreference) page.findPreference(Settings.START_ON_FOLLOWING.key);
            following.setChecked(!following.isChecked());
            ShadowLooper.idleMainLooper();
            assertTrue(dialog.getView().findViewById(SettingsDialog.RESTART_ID).performClick());

            assertNull("Instagram was opened again mid-export", Shadows.shadowOf(controller.get()).getNextStartedActivity());
            assertEquals(0, ended.get());
            assertEquals("A save or an export is still running. Restart once it's done.", ShadowToast.getTextOfLatestToast());
        } finally {
            ExportStatus.CONFIGURATION.finish(token, "done");
        }
    }

    /**
     * #146: System.exit ran Instagram's crash reporter on the way out, which hung with HushGram
     * paused. The process is killed instead (Robolectric records the kill; System.exit would end
     * this test run).
     */
    @Test
    public void theRestartKillsTheProcessRatherThanRunningExitHandlers() {
        RestartNow.endProcessForTests = null;
        ShadowProcess.clearKilledProcesses();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(RestartNow.Outcome.RESTARTING, RestartNow.restart(controller.get()));
            assertNotNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());
            assertTrue("the process wasn't killed", ShadowProcess.wasKilled(Process.myPid()));
        } finally {
            ShadowProcess.clearKilledProcesses();
        }
    }
}
