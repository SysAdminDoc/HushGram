/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.hushgram.extension.shared.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Looper;

import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Three crashed starts in a row, the crash report, the marker file and the way back, for Pause HushGram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30, 37})
public class HushgramPauseTest {
    private Context context;
    private File record;
    private File streak;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 37; // Instagram 453's target on every runtime.
        Utils.setContext(context);
        HushgramPause.resetForTests();
        record = new File(context.getFilesDir(), HushgramPause.START_RECORD_NAME);
        streak = new File(context.getFilesDir(), HushgramPause.CRASH_STREAK_NAME);
        record.delete();
        streak.delete();
        File marker = HushgramPause.markerFile(context);
        if (marker != null) marker.delete();
        BaseSettings.SAFE_MODE.save(false);
        BaseSettings.PAUSED.save(false);
    }

    @After public void tearDown() {
        setUp();
        HushgramPause.resetForTests();
    }

    /** One start that ended the way [lastRecord] says, then this start. */
    private void start(String lastRecord) {
        HushgramPause.resetForTests();
        if (lastRecord == null) record.delete();
        else HushgramPause.write(record, lastRecord);
        HushgramPause.onProcessStart(context);
    }

    @Test public void threeStartsThatCrashedInTheirFirstMinuteTurnSafeModeOn() {
        start(null);
        assertFalse(Setting.isPaused());

        start("4101 1000 crashed");
        assertEquals("1", HushgramPause.read(streak));
        start("4102 2000 crashed");
        assertEquals("2", HushgramPause.read(streak));
        assertFalse("two crashes are not yet a loop", Setting.isPaused());

        start("4103 3000 crashed");
        assertTrue("the fourth start runs in safe mode", Setting.isPaused());
        assertEquals(HushgramPause.Reason.CRASH_LOOP, HushgramPause.reason());
        assertTrue(BaseSettings.SAFE_MODE.savedValue());
        assertEquals("the count starts again once safe mode is on", "0", HushgramPause.read(streak));

        // Safe mode holds for every later start until someone turns HushGram back on.
        start(null);
        assertEquals(HushgramPause.Reason.CRASH_LOOP, HushgramPause.reason());
    }

    @Test public void aStartThatDiedWithoutACrashEndsTheStreak() {
        start("4101 1000 crashed");
        start("4102 2000 crashed");
        assertEquals("2", HushgramPause.read(streak));

        // Swiped away inside its first minute: no crash mark, and the streak is over.
        start("4103 3000");
        assertEquals("0", HushgramPause.read(streak));
        start("4104 4000 crashed");
        assertEquals("1", HushgramPause.read(streak));
        assertFalse(Setting.isPaused());
    }

    @Test public void aStartThatRanForAMinuteClearsItsRecordAndTheStreak() {
        start("4101 1000 crashed");
        assertTrue("this start left its own record", record.exists());
        shadowOf(Looper.getMainLooper()).idleFor(HushgramPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse("the record of a start that survived is gone", record.exists());
        assertEquals("0", HushgramPause.read(streak));
    }

    @Test public void aCrashAndroidHoldsOpenPastTheMinuteStillCounts() {
        start("4101 1000 crashed");
        start("4102 2000 crashed");
        assertEquals("2", HushgramPause.read(streak));

        // This start crashes off the main thread, and Android holds it open behind "keeps
        // stopping" while the main looper runs on past the minute.
        HushgramPause.markCrash();
        HushgramPause.crashState = () -> true;
        shadowOf(Looper.getMainLooper()).idleFor(HushgramPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertTrue("the minute cleared the record of a start that crashed", record.exists());
        assertEquals("2", HushgramPause.read(streak));

        // The next start judges the record this one left: the third crash in a row.
        HushgramPause.resetForTests();
        HushgramPause.onProcessStart(context);
        assertEquals(HushgramPause.Reason.CRASH_LOOP, HushgramPause.reason());
    }

    @Test public void anExceptionAHandlerSwallowedDoesNotEndTheStart() {
        start("4101 1000 crashed");
        assertEquals("1", HushgramPause.read(streak));
        HushgramPause.markCrash();
        HushgramPause.crashState = () -> false;
        shadowOf(Looper.getMainLooper()).idleFor(HushgramPause.START_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse("a start still running after the minute kept its record", record.exists());
        assertEquals("0", HushgramPause.read(streak));
    }

    @Test public void onlyACrashInsideTheFirstMinuteMarksTheStart() {
        start(null);
        ShadowSystemClock.advanceBy(HushgramPause.START_WINDOW_MS - 1000, TimeUnit.MILLISECONDS);
        HushgramPause.markCrash();
        assertTrue("a crash inside the minute was not marked",
                HushgramPause.read(record).trim().endsWith("crashed"));

        // The clock runs past the minute before the looper gets to clear the record: a crash
        // this late is not a start crash.
        start(null);
        ShadowSystemClock.advanceBy(HushgramPause.START_WINDOW_MS + 1000, TimeUnit.MILLISECONDS);
        HushgramPause.markCrash();
        assertTrue(record.exists());
        assertFalse("a crash after the minute was marked",
                HushgramPause.read(record).trim().endsWith("crashed"));
    }

    /** An exit Android wrote down for the pid at the wall-clock moment [at]. */
    private void exited(int pid, int reason, long at) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(manager).addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.getPackageName())
                .setPid(pid)
                .setReason(reason)
                .setStatus(reason == ApplicationExitInfo.REASON_CRASH ? 1 : 0)
                .setTimestamp(at)
                .build());
    }

    @Test @Config(sdk = {30, 37})
    public void android11CountsCrashesNativeCrashesAndHangsButNotBeingSwipedAway() {
        exited(5101, ApplicationExitInfo.REASON_CRASH, 11_000);
        exited(5102, ApplicationExitInfo.REASON_CRASH_NATIVE, 12_000);
        exited(5103, ApplicationExitInfo.REASON_ANR, 13_000);
        exited(5104, ApplicationExitInfo.REASON_USER_REQUESTED, 14_000);
        exited(5105, ApplicationExitInfo.REASON_LOW_MEMORY, 15_000);

        assertTrue(HushgramPause.diedYoungFromACrash(context, "5101 1000"));
        assertTrue(HushgramPause.diedYoungFromACrash(context, "5102 1000"));
        assertTrue(HushgramPause.diedYoungFromACrash(context, "5103 1000"));
        // Android's own reason wins over the handler's mark: a force stop is not a crash.
        assertFalse(HushgramPause.diedYoungFromACrash(context, "5104 1000 crashed"));
        assertFalse(HushgramPause.diedYoungFromACrash(context, "5105 1000"));
        // No reason on record for the process, so the handler's mark decides.
        assertTrue(HushgramPause.diedYoungFromACrash(context, "5199 1000 crashed"));
        assertFalse(HushgramPause.diedYoungFromACrash(context, "5199 1000"));
        assertFalse(HushgramPause.diedYoungFromACrash(context, "not a pid"));
    }

    @Test @Config(sdk = {30, 37})
    public void android11ReadsTheCrashOffTheFirstRecordSinceTheStart() {
        // Held open behind "keeps stopping": Android wrote the crash down at 30 s and the kill
        // that ended the dialog five minutes later. The first record is the crash.
        exited(6101, ApplicationExitInfo.REASON_CRASH, 1000 + 30_000);
        exited(6101, ApplicationExitInfo.REASON_CRASH, 1000 + 330_000);
        assertTrue(HushgramPause.diedYoungFromACrash(context, "6101 1000"));

        // The device slept before the process saw a minute of uptime, so the record was still
        // there when it crashed two minutes of wall time after its start: not a young crash.
        exited(6102, ApplicationExitInfo.REASON_CRASH, 1000 + 120_000);
        assertFalse(HushgramPause.diedYoungFromACrash(context, "6102 1000 crashed"));

        // A pid handed out again: the crash on record is older than this start, so it belonged
        // to another process, and only the handler's mark can speak for this one.
        exited(6103, ApplicationExitInfo.REASON_CRASH, 500);
        assertFalse(HushgramPause.diedYoungFromACrash(context, "6103 1000"));
        assertTrue(HushgramPause.diedYoungFromACrash(context, "6103 1000 crashed"));
    }

    /**
     * Instagram runs on Android 9, which keeps no exit reasons. The handler's mark is all there
     * is, and asking for the reasons there would throw before HushGram had decided anything.
     */
    @Test @Config(sdk = 28)
    public void android9CountsOnlyTheHandlersMark() {
        assertTrue(HushgramPause.diedYoungFromACrash(context, "7101 1000 crashed"));
        assertFalse(HushgramPause.diedYoungFromACrash(context, "7101 1000"));
        assertFalse(HushgramPause.diedYoungFromACrash(context, "not a pid"));
    }

    @Test public void theMarkerFilePausesEveryStartUntilItIsGone() throws Exception {
        File marker = HushgramPause.markerFile(context);
        assertTrue(marker.getParentFile().exists() || marker.getParentFile().mkdirs());
        assertTrue(marker.createNewFile());

        start(null);
        assertEquals(HushgramPause.Reason.MARKER_FILE, HushgramPause.reason());
        assertTrue(Setting.isPaused());

        assertEquals("turning back on could not remove the marker", HushgramPause.Reason.NONE,
                HushgramPause.turnBackOn(context));
        assertFalse(marker.exists());
        start(null);
        assertEquals(HushgramPause.Reason.NONE, HushgramPause.reason());
    }

    @Test public void thePauseSwitchPausesTheNextStartAndTurningBackOnUndoesEverything() {
        BaseSettings.PAUSED.save(true);
        start(null);
        assertEquals(HushgramPause.Reason.SWITCH, HushgramPause.reason());

        BaseSettings.SAFE_MODE.save(true);
        HushgramPause.write(streak, "2");
        assertEquals(HushgramPause.Reason.NONE, HushgramPause.turnBackOn(context));
        assertFalse(BaseSettings.PAUSED.savedValue());
        assertFalse(BaseSettings.SAFE_MODE.savedValue());
        assertEquals("0", HushgramPause.read(streak));
        start(null);
        assertFalse(Setting.isPaused());
    }

    /**
     * Safe mode and the Pause switch go off together or not at all, so a failed write can't leave
     * the next start paused by one of them after the other went. Whatever happened, the switches,
     * the store and the next start agree.
     */
    @Test public void resumeTurnsBothSwitchesOffTogetherOrNeither() {
        FailingStore.Fault[][] scripts = {
                {FailingStore.Fault.NONE, FailingStore.Fault.COMMIT_THROWS},
                {FailingStore.Fault.COMMIT_THROWS, FailingStore.Fault.COMMIT_THROWS},
                {FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.LOST}};
        for (FailingStore.Fault[] script : scripts) {
            BaseSettings.SAFE_MODE.save(true);
            BaseSettings.PAUSED.save(true);
            HushgramPause.Reason still;
            try (FailingStore ignored = FailingStore.install(script)) {
                still = HushgramPause.turnBackOn(context);
            }
            String name = java.util.Arrays.toString(script);
            assertEquals(name + " answered " + still, BaseSettings.PAUSED.savedValue(), still != HushgramPause.Reason.NONE);
            assertEquals(name + " turned one switch off", BaseSettings.SAFE_MODE.savedValue(),
                    BaseSettings.PAUSED.savedValue());
            assertEquals(name, BaseSettings.SAFE_MODE.savedValue(), stored(BaseSettings.SAFE_MODE));
            assertEquals(name, BaseSettings.PAUSED.savedValue(), stored(BaseSettings.PAUSED));
            assertEquals(name, BaseSettings.PAUSED.savedValue(), HushgramPause.pausesNextStart(context));
        }
    }

    /**
     * A marker that can't be removed leaves the switches on: the pause they hold still applies
     * once the file is gone, and nothing says it won't.
     */
    @Test public void aMarkerThatCanNotBeRemovedLeavesThePauseSwitchOn() throws Exception {
        File marker = HushgramPause.markerFile(context);
        File held = new File(marker, "held");
        // A folder with something in it can't be deleted.
        assertTrue(marker.mkdirs() && held.createNewFile());
        try {
            BaseSettings.PAUSED.save(true);
            assertEquals(HushgramPause.Reason.MARKER_FILE, HushgramPause.turnBackOn(context));
            assertTrue("the Pause switch went while the file stayed", BaseSettings.PAUSED.savedValue());
            assertTrue(marker.exists());
            assertTrue(HushgramPause.pausesNextStart(context));
        } finally {
            held.delete();
            marker.delete();
        }
    }

    private static boolean stored(BooleanSetting setting) {
        return Setting.preferences.preferences.getBoolean(setting.key, setting.defaultValue);
    }

    /** The handlers after ours: each records what it was handed, and returns, so a test lives on. */
    private static final class Delegate implements Thread.UncaughtExceptionHandler {
        final List<Throwable> handed = new ArrayList<>();

        @Override public void uncaughtException(Thread thread, Throwable throwable) {
            handed.add(throwable);
        }
    }

    /**
     * A section standing in for the patches' switches. It says [switchLine] while that holds one and
     * nothing otherwise, so it stays out of every other test's report once registered.
     */
    private static volatile String switchLine;

    private static final LogBufferManager.ReportSection SWITCHES = new LogBufferManager.ReportSection() {
        @Override public String title() {
            return "SWITCH PROBE";
        }

        @Override public List<String> lines() {
            String line = switchLine;
            return line == null ? Collections.emptyList() : Collections.singletonList(line);
        }
    };

    @Test public void aCrashInTheFirstMinuteKeepsItsReportForTheNextExportAndHandsTheCrashOn() {
        LogBufferManager.clearLogBuffer();
        LogBufferManager.registerReportSection(SWITCHES);
        switchLine = "Hide suggested posts: on";
        try {
            start(null);
            Delegate delegate = new Delegate();
            Throwable crash = new IllegalStateException("feed went away", new java.io.IOException("socket closed"));
            HushgramPause.crashMarkForTests(delegate).uncaughtException(Thread.currentThread(), crash);

            assertEquals("the crash was not handed on", Collections.singletonList(crash), delegate.handed);
            assertTrue("the safe mode count lost its mark", HushgramPause.read(record).trim().endsWith("crashed"));
            String report = LogBufferManager.readCrashReport(context);
            for (String part : new String[]{"complete: true\n", "thread: " + Thread.currentThread().getName() + "\n",
                    "exception: java.lang.IllegalStateException\n", "java.lang.IllegalStateException: feed went away",
                    "Caused by: java.io.IOException: socket closed", "[SWITCH PROBE]\nHide suggested posts: on\n"}) {
                assertTrue("the report lost " + part + ": " + report, report.contains(part));
            }

            // The next start's export carries it.
            start(null);
            String export = LogBufferManager.buildExportText();
            assertTrue(export, export.contains("[LATEST JAVA CRASH]\n"));
            assertTrue(export, export.contains("java.lang.IllegalStateException: feed went away"));
        } finally {
            switchLine = null;
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void aCrashAfterTheFirstMinuteKeepsNoReportUnlessDebugLoggingIsOn() {
        LogBufferManager.clearLogBuffer();
        try {
            start(null);
            ShadowSystemClock.advanceBy(HushgramPause.START_WINDOW_MS + 1000, TimeUnit.MILLISECONDS);
            Delegate delegate = new Delegate();
            Thread.UncaughtExceptionHandler handler = HushgramPause.crashMarkForTests(delegate);
            Throwable late = new IllegalStateException("late");
            handler.uncaughtException(Thread.currentThread(), late);
            assertEquals("a late crash kept a report", "", LogBufferManager.readCrashReport(context));
            assertEquals(Collections.singletonList(late), delegate.handed);

            BaseSettings.DEBUG.save(true);
            Throwable debugged = new IllegalStateException("late, with Debug logging on");
            handler.uncaughtException(Thread.currentThread(), debugged);
            assertTrue("Debug logging kept no report of a late crash",
                    LogBufferManager.readCrashReport(context).contains("late, with Debug logging on"));
            assertEquals(Arrays.asList(late, debugged), delegate.handed);
            assertFalse("a late crash marked the start", HushgramPause.read(record).trim().endsWith("crashed"));
        } finally {
            BaseSettings.DEBUG.save(false);
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void anExceptionSwallowedOffTheMainThreadPutsTheReportBack() throws Exception {
        LogBufferManager.clearLogBuffer();
        try {
            LogBufferManager.persistCrashReport(context, "complete: true\nan earlier start's crash\n");
            start(null);
            Thread worker = new Thread("worker");
            HushgramPause.crashMarkForTests(new Delegate()).uncaughtException(worker, new IllegalStateException("swallowed"));
            assertEquals("complete: true\nan earlier start's crash\n", LogBufferManager.readCrashReport(context));

            // On the main thread the app is frozen after it, so that report stays.
            HushgramPause.crashMarkForTests(new Delegate())
                    .uncaughtException(Thread.currentThread(), new IllegalStateException("main thread"));
            assertTrue(LogBufferManager.readCrashReport(context).contains("main thread"));
        } finally {
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void aReportThatCanNotBeBuiltStillHandsTheCrashOn() throws Exception {
        LogBufferManager.clearLogBuffer();
        try {
            LogBufferManager.persistCrashReport(context, "complete: true\nan earlier start's crash\n");
            start(null);
            Delegate delegate = new Delegate();
            Throwable unprintable = new IllegalStateException("unprintable") {
                @Override public void printStackTrace(java.io.PrintWriter writer) {
                    throw new IllegalStateException("no trace");
                }
            };
            HushgramPause.crashMarkForTests(delegate).uncaughtException(Thread.currentThread(), unprintable);
            // The earlier report standing shows the build threw before anything was written.
            assertEquals("complete: true\nan earlier start's crash\n", LogBufferManager.readCrashReport(context));
            assertEquals(Collections.singletonList(unprintable), delegate.handed);
            assertTrue(HushgramPause.read(record).trim().endsWith("crashed"));
        } finally {
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void theStreakFileSurvivesNonsense() {
        HushgramPause.write(streak, "not a number");
        assertEquals(0, HushgramPause.parseCount(HushgramPause.read(streak)));
        assertEquals(0, HushgramPause.parseCount("-4"));
        assertEquals(0, HushgramPause.parseCount(null));
        assertNull(HushgramPause.read(new File(context.getFilesDir(), "never-written")));
    }
}
