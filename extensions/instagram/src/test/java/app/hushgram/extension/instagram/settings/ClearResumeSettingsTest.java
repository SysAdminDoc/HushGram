/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.preference.Preference;
import android.view.accessibility.AccessibilityManager;
import java.util.EnumSet;

import app.hushgram.extension.instagram.media.ResumePlayback;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ClearResumeSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void start() { ResumePlaybackForTests.install(); }
    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ResumePlaybackForTests.forget();
        PauseForTests.resume();
        PatchFamily.inBuildForTests = null;
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
    }

    @Test public void rowClearsThenOffersOneUseUndoWhilePausedAndOff() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.RESUME_LONG_VIDEOS);
        Settings.RESUME_LONG_VIDEOS.save(false);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference row = page.findPreference("hushgram_clear_resume_points");
            assertNotNull(row);
            assertTrue(row.isEnabled());
            assertEquals("Clear remembered positions", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()).contains("200"));
            assertTrue(String.valueOf(row.getSummary()).contains("30 days"));
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Undo cleared positions", String.valueOf(row.getTitle()));
            long deadline = ResumePlayback.undoHistoryDeadline(ResumePlayback.undoHistoryToken());
            ConfigurationDocumentsTest.assertDeadlineLabel(row, deadline, ".");
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Clear remembered positions", String.valueOf(row.getTitle()));
        }
    }

    @Test @Config(sdk = {28, 29, 37})
    public void reopeningKeepsTheDeadlineAndOldRowsCannotClearAgain() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.RESUME_LONG_VIDEOS);
        AccessibilityManager manager = RuntimeEnvironment.getApplication().getSystemService(AccessibilityManager.class);
        if (Build.VERSION.SDK_INT >= 29) Shadows.shadowOf(manager).setInteractiveUiTimeout(30_000);
        long deadline;
        Preference old;
        Preference.OnPreferenceClickListener oldClear;
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            old = page.findPreference("hushgram_clear_resume_points");
            oldClear = old.getOnPreferenceClickListener();
            oldClear.onPreferenceClick(old);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            deadline = ResumePlayback.undoHistoryDeadline(ResumePlayback.undoHistoryToken());
            SystemClock.sleep(5_000);
        }
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Preference row = DownloadSettingsTest.pageIn(controller).findPreference("hushgram_clear_resume_points");
            ConfigurationDocumentsTest.assertDeadlineLabel(row, deadline, ".");
            assertEquals(deadline, ResumePlayback.undoHistoryDeadline(ResumePlayback.undoHistoryToken()));
            oldClear.onPreferenceClick(old);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals(deadline, ResumePlayback.undoHistoryDeadline(ResumePlayback.undoHistoryToken()));
            SystemClock.sleep(deadline - SystemClock.elapsedRealtime());
            ShadowLooper.idleMainLooper();
            assertEquals("Clear remembered positions", row.getTitle().toString());
        }
    }

    @Test public void noClearRowIsAddedWithoutTheResumePatch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertNull(DownloadSettingsTest.pageIn(controller).findPreference("hushgram_clear_resume_points"));
        }
    }

    @Test public void expiredUndoStillShownNeverClearsNewlyRememberedPositions() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.RESUME_LONG_VIDEOS);
        Settings.RESUME_LONG_VIDEOS.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference row = page.findPreference("hushgram_clear_resume_points");
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Undo cleared positions", String.valueOf(row.getTitle()));
            // Advance the clock without delivering the page's queued refresh.
            SystemClock.sleep(ResumePlayback.UNDO_WINDOW_MS + 1);
            ResumePlaybackForTests.Player playing = new ResumePlaybackForTests.Player(
                    new ResumePlaybackForTests.Video("new-after-clear", 180_000));
            ResumePlayback.started(playing);
            ResumePlaybackForTests.runLater();
            playing.position = 60_000;
            ResumePlayback.stopped(playing, "scroll");
            var positions = RuntimeEnvironment.getApplication().getSharedPreferences(
                    "hushgram_resume_points_by_account", Context.MODE_PRIVATE);
            assertTrue(holds(positions, "new-after-clear"));
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue("Expired Undo deleted the new position", holds(positions, "new-after-clear"));
            assertEquals("Clear remembered positions", String.valueOf(row.getTitle()));
        }
    }

    /** Whether [positions] holds a point for [videoId], under whichever account saved it. */
    private static boolean holds(android.content.SharedPreferences positions, String videoId) {
        for (String key : positions.getAll().keySet()) if (key.endsWith("/" + videoId)) return true;
        return false;
    }
}
