/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.DiagnosticRedactorTest;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * What Logger hands Android's log and its error toasts: past the redactor the exported report
 * uses, with the trace written into the text instead of a Throwable logcat would print as it is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LoggerRedactionTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String TAG = "morphe: LoggerRedactionTest";
    private static final String SIGNED_URL = "https://scontent.cdninstagram.com/v/t51/1.jpg?oh=00_signedOhP1&oe=signedOeP2";
    private static final String STORAGE_FILE = "/storage/emulated/0/Download/Private Lease P3.pdf";

    private Set<String> toasted;
    private Set<String> toastedBefore;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        ShadowLog.reset();
        ShadowToast.reset();
        LogBufferManager.clearLogBuffer();
        // Process-wide and capped, so error toasts from earlier classes could leave no room. It's put
        // back afterwards: emptied, it would let later classes show toasts they expect to be capped.
        Field field = Logger.class.getDeclaredField("TOASTED_ERRORS");
        field.setAccessible(true);
        toasted = (Set<String>) field.get(null);
        toastedBefore = new HashSet<>(toasted);
        toasted.clear();
        BaseSettings.DEBUG.save(false);
    }

    @After
    public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        LogBufferManager.clearLogBuffer();
        // A setting keeps its value in memory across the sandbox's classes, so Debug logging left on
        // here gives later classes error toasts and debug lines they don't expect.
        BaseSettings.DEBUG.resetToDefault();
        toasted.clear();
        toasted.addAll(toastedBefore);
    }

    /** Debug logging off: errors and info lines still reach logcat, and nothing of the corpus with them. */
    @Test
    public void noCorpusValueReachesLogcatOrTheReport() {
        for (String[] row : DiagnosticRedactorTest.CREDENTIAL_CORPUS) {
            Logger.diagnosticError(DiagnosticCategory.PATCH_ERRORS, "RedactionProbe",
                    () -> "failed with " + row[0], new IllegalStateException(row[0]));
            Logger.diagnosticInfo(DiagnosticCategory.OTHER, "RedactionProbe", () -> row[0]);
        }

        String logged = logcat("morphe: RedactionProbe");
        String report = LogBufferManager.buildExportText();
        assertTrue(logged, logged.contains("failed with"));
        StringBuilder leaks = new StringBuilder();
        for (String[] row : DiagnosticRedactorTest.CREDENTIAL_CORPUS) {
            for (int i = 1; i < row.length; i++) {
                if (logged.contains(row[i])) leaks.append("\nlogcat: ").append(row[i]);
                if (report.contains(row[i])) leaks.append("\nreport: ").append(row[i]);
            }
        }
        assertEquals("", leaks.toString());
    }

    @Test
    public void causesAndSuppressedOnesAreRedactedAndKeepTheirTypes() {
        IllegalStateException top = new IllegalStateException("load " + SIGNED_URL,
                new IOException(STORAGE_FILE + ": open failed: EACCES"));
        top.addSuppressed(new IllegalArgumentException("access_token=EAABsuppressedQ4"));

        Logger.printException(() -> "Could not load the cover", top);

        String logged = logcat(TAG);
        for (String kept : new String[]{"Could not load the cover", "java.lang.IllegalStateException: load [url omitted]",
                "Caused by: java.io.IOException: [path omitted]: open failed: EACCES",
                "Suppressed: java.lang.IllegalArgumentException", "at app.hushgram.extension.shared.LoggerRedactionTest"}) {
            assertTrue(kept + " is missing from:\n" + logged, logged.contains(kept));
        }
        for (String gone : new String[]{"signedOhP1", "signedOeP2", "Private Lease P3", "EAABsuppressedQ4"}) {
            assertFalse(gone + " reached logcat:\n" + logged, logged.contains(gone));
        }
    }

    @Test
    public void infoAndErrorLinesAreRedactedWithDebugLoggingOff() {
        Logger.printInfo(() -> "token=EAABinfoR5");
        Logger.printException(() -> "Authorization: Bearer EAABerrorR6");
        Logger.printDebug(() -> "debug line D1");

        String logged = logcat(TAG);
        assertTrue(logged, logged.contains("token=[omitted]") && logged.contains("Authorization=[omitted]"));
        assertFalse(logged, logged.contains("EAABinfoR5") || logged.contains("EAABerrorR6"));
        assertFalse("printDebug logged with Debug logging off", logged.contains("debug line D1"));
    }

    @Test
    public void theErrorToastIsRedacted() {
        BaseSettings.DEBUG.save(true);

        Logger.printException(() -> "Could not open " + SIGNED_URL + " for the toast S7");
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertEquals("LoggerRedactionTest: Could not open [url omitted] for the toast S7", toast);
        String logged = logcat("morphe: Utils");
        assertTrue(logged, logged.contains("Showing toast: LoggerRedactionTest: Could not open [url omitted]"));
        assertFalse(logged, logged.contains("signedOhP1"));
    }

    /** The lock and hide feedback names the chat on purpose. Its toast keeps the name, logcat doesn't. */
    @Test
    public void aToastNamingAChatKeepsTheNameOutOfLogcatOnly() {
        BaseSettings.DEBUG.save(true);
        String shown = "Hid " + L10n.isolate("Dana Quill") + "'s chat";

        Utils.showToastShort(shown);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(shown, String.valueOf(ShadowToast.getTextOfLatestToast()));
        String logged = logcat("morphe: Utils");
        assertTrue(logged, logged.contains("Showing toast: Hid [name omitted]"));
        assertFalse(logged, logged.contains("Dana Quill"));
    }

    @Test
    public void aMessageThatCannotBeBuiltIsRedacted() {
        Logger.printException(() -> {
            throw new IllegalStateException("token=EAABformatterT8");
        });

        String logged = logcat(TAG);
        assertTrue(logged, logged.contains("Could not build the log message: java.lang.IllegalStateException: token=[omitted]"));
        assertFalse(logged, logged.contains("EAABformatterT8"));
    }

    /** getMessage throwing takes Logger to its own fallback line, which goes past the redactor too. */
    @Test
    public void theLoggersOwnFallbackIsRedacted() {
        RuntimeException host = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new AssertionError("token=EAABhostU9");
            }
        };

        Logger.printException(() -> "Lost the cover " + SIGNED_URL, host);

        String logged = logcat("morphe: Logger");
        assertTrue(logged, logged.contains("Could not log a message: Lost the cover [url omitted]"));
        assertTrue(logged, logged.contains("java.lang.AssertionError: token=[omitted]"));
        assertFalse(logged, logged.contains("signedOhP1") || logged.contains("EAABhostU9"));
    }

    /** toString throwing stops the trace, and the throwable's type says what failed. */
    @Test
    public void aThrowableThatCannotPrintItselfLeavesItsType() {
        RuntimeException host = new RuntimeException("token=EAABtostrV10") {
            @Override
            public String toString() {
                throw new AssertionError("toString must stay contained");
            }
        };

        Logger.printException(() -> "Could not print the failure", host);

        String logged = logcat(TAG);
        assertTrue(logged, logged.contains("Exception: token=[omitted]\n" + host.getClass().getName()));
        assertFalse(logged, logged.contains("EAABtostrV10"));
    }

    /** Before Instagram sets the context, debug lines are logged whatever the switch says. */
    @Test
    public void startupLinesAreRedacted() {
        Utils.context = null;
        try {
            Logger.printDebug(() -> "starting with token=EAABstartupW11");
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }

        String logged = logcat(TAG);
        assertTrue(logged, logged.contains("starting with token=[omitted]"));
        assertFalse(logged, logged.contains("EAABstartupW11"));
    }

    /**
     * The redactor reads 16,000 characters at most and logcat gets 4,000. The line the first cut
     * splits is dropped, since a value in it may be only partly there. Here the URLs before it
     * shrink to a few hundred characters, so that line would land inside what's kept.
     */
    @Test
    public void aLongLineIsCutWithinWhatLogcatKeeps() {
        StringBuilder message = new StringBuilder();
        // 49 lines of 321 characters end at 15,729, so the 16,000th falls inside the next line.
        while (message.length() < 15_600) {
            message.append("https://example.org/");
            for (int i = 0; i < 300; i++) message.append('a');
            message.append('\n');
        }
        message.append("SPLITLINE ");
        for (int i = 0; i < 400; i++) message.append('b');
        message.append("\nAFTERCUT\n");
        String text = message.toString();

        Logger.printInfo(() -> text);

        String logged = ShadowLog.getLogsForTag(TAG).get(0).msg;
        assertTrue(logged, logged.startsWith("[url omitted]\n") && logged.endsWith("\n[cut]"));
        assertFalse(logged, logged.contains("SPLITLINE") || logged.contains("AFTERCUT"));

        StringBuilder words = new StringBuilder();
        while (words.length() < 100_000) words.append("plain words in a long line of text\n");
        String many = words.toString();
        Logger.printInfo(() -> many);
        String long2 = ShadowLog.getLogsForTag(TAG).get(1).msg;
        assertTrue(long2.length() + " characters", long2.length() <= 4_000 + "\n[cut]".length());
        assertTrue(long2, long2.startsWith("plain words") && long2.endsWith("\n[cut]"));
    }

    /** Every entry for [tag] joined, after checking none of them carries a Throwable logcat would print as it is. */
    private static String logcat(String tag) {
        StringBuilder all = new StringBuilder();
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag(tag)) {
            assertNull("a Throwable went to logcat as it is: " + item.msg, item.throwable);
            all.append(item.msg).append('\n');
        }
        return all.toString();
    }
}
