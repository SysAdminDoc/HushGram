/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.WorkerPoolForTests;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Copy quick report has to fit a GitHub issue. It used to keep the report's last characters, so
 * after a long Debug logging session the header, the paused line and [PATCHES] were what got cut.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class LogBufferManagerClipboardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final LogBufferManager.ReportSection PATCHES = section("PATCHES",
            "Hide sponsored posts: disabled while paused (saved hushgram_hide_sponsored_posts=on)");

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        LogBufferManager.registerReportSection(PATCHES);
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        LogBufferManager.clearReportSectionsForTests();
        LogBufferManager.clearLogBuffer();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    private static LogBufferManager.ReportSection section(String title, String line) {
        return new LogBufferManager.ReportSection() {
            @Override
            public String title() {
                return title;
            }

            @Override
            public List<String> lines() {
                return Collections.singletonList(line);
            }
        };
    }

    private static String run(char c, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) text.append(c);
        return text.toString();
    }

    /** Each build stamps the time to the millisecond, so two builds compare with it taken out. */
    private static String timeless(String report) {
        return report.replaceFirst("generated_utc: \\S+", "generated_utc: T");
    }

    private static void events(int count) {
        for (int i = 0; i < count; i++) {
            LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "Probe", "INFO", "event " + i + " " + run('x', 40));
        }
    }

    @Test
    public void aLongReportKeepsEverythingAheadOfTheEventsAndLosesTheOldestEvents() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        events(200);
        String whole = timeless(LogBufferManager.buildExportText());
        assertTrue("the whole report fits, so nothing here is tested", whole.length() > 3_000);

        String raw = LogBufferManager.clipboardText(3_000);
        assertTrue(raw.length() + " characters", raw.length() <= 3_000);
        String copy = timeless(raw);
        String head = whole.substring(0, whole.indexOf("\n\n[SELECTED EVENTS]"));
        assertTrue("the copy lost part of what comes before the events:\n" + copy, copy.startsWith(head));
        assertTrue(copy, copy.contains("hushgram: paused (switch)"));
        assertTrue(copy, copy.contains("[PATCHES]\nHide sponsored posts: disabled while paused"));

        Matcher note = Pattern.compile("clipboard_note: (\\d+) older events left out").matcher(copy);
        assertTrue(copy, note.find());
        int dropped = Integer.parseInt(note.group(1));
        assertTrue(copy, dropped > 0 && dropped < 200);
        for (int i = 0; i < 200; i++) {
            assertEquals("event " + i + " of 200, with " + dropped + " left out", i >= dropped,
                    copy.contains("| event " + i + " "));
        }
    }

    @Test
    public void aReportThatFitsIsCopiedWhole() {
        events(3);
        String whole = LogBufferManager.buildExportText();
        assertEquals(timeless(whole), timeless(LogBufferManager.clipboardText(whole.length())));
        assertFalse(whole.contains("clipboard_note"));
    }

    private static final String CUT_AT_1000 =
            "\nclipboard_note: cut at 1000 characters; use Save full report for everything\n";

    /** The report up to its events, as the full export writes it. */
    private static String headOf(String report) {
        int events = report.indexOf("\n\n[SELECTED EVENTS]");
        return events < 0 ? report : report.substring(0, events);
    }

    /** A section whose one line the test sets, to put the head at any length it wants. */
    private static final class Adjustable implements LogBufferManager.ReportSection {
        String line = "";

        @Override
        public String title() {
            return "ADJUSTABLE";
        }

        @Override
        public List<String> lines() {
            return Collections.singletonList(line);
        }
    }

    @Test
    public void aHeadTooLongForTheCopyIsCutAtItsEndNotItsStart() {
        LogBufferManager.registerReportSection(section("LONG", run('y', 5_000)));
        events(3);
        String head = headOf(LogBufferManager.buildExportText());
        String copy = LogBufferManager.clipboardText(1_000);
        assertTrue(copy.length() + " characters", copy.length() <= 1_000);
        assertTrue(copy, copy.endsWith(CUT_AT_1000));
        // What's kept is the start of the head: the banner, the build lines and [PATCHES].
        int kept = copy.length() - CUT_AT_1000.length();
        assertEquals(timeless(head.substring(0, kept)), timeless(copy.substring(0, kept)));
    }

    /**
     * Every head length around the limit. A head just short of it used to throw, so nothing was
     * copied, or lose every event with no count. Now nothing throws, and whenever a line saying
     * the events were left out fits beside the head, the head arrives whole.
     */
    @Test
    public void everyHeadLengthNearTheLimitCopiesAndKeepsTheHeadWholeWhenANoteFits() {
        Adjustable adjustable = new Adjustable();
        LogBufferManager.registerReportSection(adjustable);
        events(20);
        int limit = 3_000;
        int bare = headOf(LogBufferManager.buildExportText()).length();
        String allLeftOut = "\nclipboard_note: 20 events left out; use Save full report for everything\n";

        for (int target = limit - 400; target <= limit + 20; target++) {
            if (target < bare) continue;
            adjustable.line = run('y', target - bare);
            String head = headOf(LogBufferManager.buildExportText());
            String copy = LogBufferManager.clipboardText(limit);
            assertTrue(target + ": " + copy.length() + " characters", copy.length() <= limit);
            assertTrue(target + ": " + copy, copy.startsWith("MORPHE DIAGNOSTIC REPORT\n"));
            if (head.length() + allLeftOut.length() <= limit) {
                assertTrue("a head of " + head.length() + " was not kept whole", timeless(copy).startsWith(timeless(head)));
                // Twenty events never fit beside these heads, so the copy has to say they went, and
                // how many: every one of them, or every one the copy doesn't carry.
                assertTrue("a head of " + head.length() + " lost the events with no count: " + copy,
                        copy.contains(" events left out; use Save full report for everything"));
                int kept = copy.split("\\| event ", -1).length - 1;
                Matcher all = Pattern.compile("clipboard_note: (\\d+) events left out").matcher(copy);
                Matcher older = Pattern.compile("clipboard_note: (\\d+) older events left out").matcher(copy);
                if (all.find()) {
                    assertEquals("a head of " + head.length() + " said how many went", 20, Integer.parseInt(all.group(1)));
                    assertEquals("the copy carries events it says it left out", 0, kept);
                } else {
                    assertTrue(copy, older.find());
                    assertEquals("a head of " + head.length() + " kept " + kept + " events", 20 - kept,
                            Integer.parseInt(older.group(1)));
                }
            }
        }
    }

    private static ClipboardManager clipboard() {
        return RuntimeEnvironment.getApplication().getSystemService(ClipboardManager.class);
    }

    private static String clipText(ClipboardManager clipboard) {
        return String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText());
    }

    /** A clip the test can tell apart from any report, and no toast or bundle sentence left over. */
    private static ClipboardManager clipboardHolding(String text) {
        ClipboardManager clipboard = clipboard();
        clipboard.setPrimaryClip(ClipData.newPlainText("test", text));
        LogBufferManager.copiedMessage = null;
        LogBufferManager.exportFailedMessage = null;
        LogBufferManager.couldNotStartMessage = null;
        ShadowToast.reset();
        return clipboard;
    }

    /**
     * Copy quick report builds and redacts the report on a worker, and only the copy and its toast
     * come back to the main thread. Built on the main thread, a full buffer's redaction held up
     * the tap that asked for it.
     */
    @Test
    public void theQuickReportIsBuiltOffTheMainThreadAndCopiedOnIt() throws Exception {
        List<Boolean> builtOnMain = new CopyOnWriteArrayList<>();
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override
            public String title() {
                return "PROBE";
            }

            @Override
            public List<String> lines() {
                builtOnMain.add(Looper.getMainLooper().isCurrentThread());
                return Collections.singletonList("probe line");
            }
        });
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "Probe", "INFO",
                "opened www.facebook.com/dana.q.1987");
        ClipboardManager clipboard = clipboardHolding("before");

        LogBufferManager.exportToClipboard();
        Utils.awaitBackgroundTasksForTests();

        assertEquals("the report was built " + builtOnMain.size() + " times", 1, builtOnMain.size());
        assertFalse("the report was built on the main thread", builtOnMain.get(0));
        assertEquals("the copy didn't wait for the main thread", "before", clipText(clipboard));
        assertNull(ShadowToast.getTextOfLatestToast());

        ShadowLooper.idleMainLooper();
        String copied = clipText(clipboard);
        assertTrue(copied, copied.startsWith("MORPHE DIAGNOSTIC REPORT\n"));
        assertTrue(copied, copied.contains("[PROBE]\nprobe line"));
        assertFalse("the copy wasn't redacted: " + copied, copied.contains("dana.q.1987"));
        assertEquals("Diagnostic report copied to the clipboard.", ShadowToast.getTextOfLatestToast());
    }

    /**
     * A full worker queue copies nothing and says the copy couldn't start, and the next tap copies.
     * The pool is one static executor for every test in this JVM; WorkerPoolForTests says how it's
     * held full.
     */
    @Test
    public void aFullWorkerQueueSaysTheCopyCouldNotStart() throws Exception {
        events(3);
        ClipboardManager clipboard = clipboardHolding("before");
        try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
            LogBufferManager.exportToClipboard();
            ShadowLooper.idleMainLooper();
            assertEquals("Couldn't start the report export. Try again shortly.", ShadowToast.getTextOfLatestToast());
            assertEquals("before", clipText(clipboard));
        }

        LogBufferManager.exportToClipboard();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertTrue(clipText(clipboard), clipText(clipboard).startsWith("MORPHE DIAGNOSTIC REPORT\n"));
    }

    /** A report that can't be built says so, and whatever was on the clipboard stays there. */
    @Test
    public void aReportThatCannotBeBuiltSaysSoAndCopiesNothing() throws Exception {
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override
            public String title() {
                throw new IllegalStateException("no title");
            }

            @Override
            public List<String> lines() {
                return Collections.singletonList("a line");
            }
        });
        events(1);
        ClipboardManager clipboard = clipboardHolding("before");

        LogBufferManager.exportToClipboard();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();

        assertEquals("Couldn't save the diagnostic report. Try again.", ShadowToast.getTextOfLatestToast());
        assertEquals("before", clipText(clipboard));
    }

    /** A cut never leaves half of a character in front of the note. */
    @Test
    public void aCutNeverSplitsACharacter() {
        Adjustable adjustable = new Adjustable();
        LogBufferManager.registerReportSection(adjustable);
        events(1);
        String bareHead = headOf(LogBufferManager.buildExportText());
        int lineStart = bareHead.indexOf("[ADJUSTABLE]\n") + "[ADJUSTABLE]\n".length();
        int end = 1_000 - CUT_AT_1000.length();
        // The emoji's first half sits at the last place the cut would keep.
        adjustable.line = run('h', end - 1 - lineStart) + "😀" + run('t', 500);

        String copy = LogBufferManager.clipboardText(1_000);
        assertTrue(copy, copy.endsWith(CUT_AT_1000));
        // Stepping forward over the character would keep it whole too, one over the limit.
        assertTrue(copy.length() + " characters", copy.length() <= 1_000);
        assertEquals("the copy holds half a character",
                copy, new String(copy.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
    }
}
