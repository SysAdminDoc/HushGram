/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityManager;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.media.ResumePlaybackForTests.Player;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests.Video;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ClearResumeHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private SharedPreferences file;
    private ResumePoints points;

    @Before public void start() {
        ResumePlaybackForTests.install();
        file = RuntimeEnvironment.getApplication().getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
        file.edit().clear().commit();
        points = new ResumePoints(file);
        ResumePlayback.pointsForTests = points;
        Settings.RESUME_LONG_VIDEOS.save(true);
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ResumePlaybackForTests.forget();
        PauseForTests.resume();
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
        Settings.HIDE_ADS.resetToDefault();
    }

    @Test public void clearRemovesDiskMemoryAndQueuedRestoresEvenAfterUndo() {
        String key = ResumePlayback.ownedKey(ResumePlaybackForTests.ACCOUNT, "private-media");
        points.put(key, 60_000, System.currentTimeMillis());
        Player queued = new Player(new Video("private-media", 180_000));
        ResumePlayback.started(queued);
        assertEquals(1, ResumePlaybackForTests.LATER.size());
        ResumePlayback.clearHistory();
        assertTrue(file.getAll().isEmpty());
        assertEquals(0, points.size(System.currentTimeMillis()));
        assertEquals(0, ResumePlayback.playersKnown());
        Player opened = new Player(queued.video);
        ResumePlayback.started(opened);
        ResumePlaybackForTests.runLater();
        assertTrue(queued.seeks.isEmpty());
        assertTrue(opened.seeks.isEmpty());
        assertEquals(0, opened.position);
        assertTrue(ResumePlayback.undoHistory());
        ResumePlaybackForTests.runLater();
        assertTrue(queued.seeks.isEmpty());
        assertFalse("Undo is one-use", ResumePlayback.undoHistory());
        assertEquals(60_000, new ResumePoints(file).get(key, System.currentTimeMillis()).positionMs);
        Player beforeUndo = new Player(queued.video);
        ResumePlayback.started(beforeUndo);
        assertEquals(1, ResumePlaybackForTests.LATER.size());
        ResumePlayback.clearHistory();
        assertTrue(ResumePlayback.undoHistory());
        ResumePlaybackForTests.runLater();
        assertTrue("Undo reactivated a seek queued before clearing", beforeUndo.seeks.isEmpty());
        assertFalse(String.join("\n", FeedFilterCounters.report()).contains("private-media"));
    }

    @Test public void undoExpiresAtTenSecondsAndTheTimerDropsItsCopy() {
        points.put("id", 60_000, System.currentTimeMillis());
        ResumePlayback.clearHistory();
        SystemClock.sleep(ResumePlayback.UNDO_WINDOW_MS - 1);
        assertTrue(ResumePlayback.canUndoHistory());
        SystemClock.sleep(1);
        ShadowLooper.idleMainLooper();
        assertFalse(ResumePlayback.canUndoHistory());
        assertFalse(ResumePlayback.undoHistory());
        assertTrue(file.getAll().isEmpty());
    }

    @Test public void aProcessRestartHasNoUndoOrRememberedPosition() {
        points.put(ResumePlayback.ownedKey(ResumePlaybackForTests.ACCOUNT, "id"), 60_000, System.currentTimeMillis());
        ResumePlayback.clearHistory();
        ResumePlaybackForTests.forget();
        ResumePlaybackForTests.install();
        assertFalse(ResumePlayback.canUndoHistory());
        Player opened = new Player(new Video("id", 180_000));
        ResumePlayback.started(opened);
        ResumePlaybackForTests.runLater();
        assertTrue(opened.seeks.isEmpty());
        assertTrue(new ResumePoints(file).size(System.currentTimeMillis()) == 0);
    }

    @Test @Config(sdk = {28, 29, 37})
    public void undoUsesTheRecommendedInteractiveWindowOrTheLegacyFallback() {
        AccessibilityManager manager = RuntimeEnvironment.getApplication()
                .getSystemService(AccessibilityManager.class);
        for (int recommendation : new int[]{10_000, 30_000, 120_000}) {
            if (Build.VERSION.SDK_INT >= 29) {
                Shadows.shadowOf(manager).setInteractiveUiTimeout(recommendation);
                Shadows.shadowOf(manager).setNonInteractiveUiTimeout(0);
            }
            points.put("id", 60_000, System.currentTimeMillis());
            ResumePlayback.clearHistory();
            int window = Build.VERSION.SDK_INT >= 29 ? recommendation : 10_000;
            SystemClock.sleep(window - 1);
            assertTrue("Undo ended before the recommended window", ResumePlayback.canUndoHistory());
            SystemClock.sleep(1);
            ShadowLooper.idleMainLooper();
            assertFalse(ResumePlayback.canUndoHistory());
        }
    }

    @Test @Config(sdk = {29, 37})
    public void undoAlsoRespectsTheRecommendedReadingTime() {
        AccessibilityManager manager = RuntimeEnvironment.getApplication()
                .getSystemService(AccessibilityManager.class);
        Shadows.shadowOf(manager).setInteractiveUiTimeout(10_000);
        Shadows.shadowOf(manager).setNonInteractiveUiTimeout(120_000);
        points.put("id", 60_000, System.currentTimeMillis());
        ResumePlayback.clearHistory();
        SystemClock.sleep(30_000);
        assertTrue("Undo omitted the text content flag", ResumePlayback.canUndoHistory());
    }

    @Test @Config(sdk = {28, 29, 37})
    public void staleActionAndExpiryCannotConsumeTheNextClear() {
        points.put("first", 60_000, System.currentTimeMillis());
        ResumePlayback.clearHistory();
        long old = ResumePlayback.undoHistoryToken();
        long firstDeadline = ResumePlayback.undoHistoryDeadline(old);
        SystemClock.sleep(5_000);
        points.put("second", 70_000, System.currentTimeMillis());
        ResumePlayback.clearHistory();
        long current = ResumePlayback.undoHistoryToken();
        assertTrue(old != current);
        assertEquals(0, ResumePlayback.undoHistoryDeadline(old));
        assertFalse(ResumePlayback.undoHistory(old));
        SystemClock.sleep(firstDeadline - SystemClock.elapsedRealtime());
        ShadowLooper.idleMainLooper();
        assertTrue(ResumePlayback.canUndoHistory());
        assertTrue(ResumePlayback.undoHistory(current));
        assertEquals(1, points.size(System.currentTimeMillis()));
        assertNotNull(points.get("second", System.currentTimeMillis()));
        assertFalse(ResumePlayback.undoHistory(current));
        assertEquals(0, ResumePlayback.undoHistoryToken());
    }

    @Test public void clearingWhileOffAndPausedLeavesOtherSettingsAlone() {
        points.put("id", 60_000, System.currentTimeMillis());
        Settings.RESUME_LONG_VIDEOS.save(false);
        Settings.HIDE_ADS.save(false);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ResumePlayback.clearHistory();
        assertTrue(file.getAll().isEmpty());
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertFalse(Settings.RESUME_LONG_VIDEOS.savedValue());
        assertTrue(ResumePlayback.undoHistory());
    }

    @Test public void snapshotsStayBoundedAndUndoKeepsNewerPointsAndOriginalAge() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 250; i++) points.put("id" + i, 60_000, now - 1_000 + i);
        Map<String, ResumePoints.Point> snapshot = points.clear(now);
        assertEquals(200, snapshot.size());
        points.put("id249", 90_000, now + 1);
        points.put("new", 70_000, now + 2);
        points.restore(snapshot, now + 3);
        assertEquals(200, points.size(now + 3));
        assertEquals(90_000, points.get("id249", now + 3).positionMs);
        assertNotNull(points.get("new", now + 3));
        assertEquals(now - 1_000 + 248, points.get("id248", now + 3).savedAt);
        snapshot = points.clear(now + 3);
        points.restore(snapshot, now + ResumePoints.KEEP_MS + 1_000);
        assertEquals(0, points.size(now + ResumePoints.KEEP_MS + 1_000));
    }

    @Test public void aFailedCommitRestoresTheFileAndDoesNotOfferUndo() {
        long now = System.currentTimeMillis();
        points.put("id", 60_000, now);
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(file, args);
                    SharedPreferences.Editor editor = file.edit();
                    boolean[] clearing = {false};
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                if (editMethod.getName().equals("clear")) clearing[0] = true;
                                Object result = editMethod.invoke(editor, editArgs);
                                if (editMethod.getName().equals("commit") && clearing[0]
                                        && commits.incrementAndGet() == 1) return false;
                                return result == editor ? editProxy : result;
                            });
                });
        ResumePlayback.pointsForTests = new ResumePoints(failing);
        boolean failed = false;
        try { ResumePlayback.clearHistory(); } catch (IllegalStateException expected) { failed = true; }
        assertTrue(failed);
        assertEquals("failed clear did not attempt its rollback", 2, commits.get());
        assertFalse(ResumePlayback.canUndoHistory());
        assertEquals(60_000, new ResumePoints(file).get("id", now).positionMs);
    }
}
