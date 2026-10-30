/*
 * Modified for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.hushgram.extension.shared.settings.preference;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;

import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticRedactor;

/**
 * Keeps the Java crash that ends a start for the diagnostic report's [LATEST JAVA CRASH] section.
 *
 * <p>Android keeps no stack trace for a Java crash: its exit record says CRASH and nothing more, so
 * a report sent after safe mode turned on couldn't say what crashed. The pause module's
 * uncaught-exception handler hands a crash here before passing it on, when it comes inside the
 * start's first minute (the crashes safe mode counts) or while Debug logging is on. The trace, the
 * patches' switches and the last diagnostic events go through the redactor and stay in Instagram's
 * own files, so every report exported after it carries them, until the next crash replaces them or
 * the diagnostic log is cleared.
 *
 * <p>The handler can sit in front of Instagram's own, so it also sees an exception one of
 * Instagram's swallows. When the handlers after it return, the process lives on, and an exception
 * off the main thread puts the report back as it was ({@link #survived}), unless the report is no
 * longer the one it wrote: cleared, or replaced by a newer crash. One swallowed on the main thread
 * stays, since that thread is gone and Instagram freezes.
 */
public final class JavaCrashReport {
    /** How much of the diagnostic event log goes in after the trace. */
    static final int RECENT_EVENTS_MAX_CHARS = 12_000;

    /** The exception kept last, weakly: a handler Instagram put on top of ours can hand it back. */
    private static volatile WeakReference<Throwable> lastSaved = new WeakReference<>(null);

    /** One save, for {@link #survived}: what the report held before it and what it wrote. */
    public static final class Saved {
        final String before;
        final String written;

        Saved(String before, String written) {
            this.before = before;
            this.written = written;
        }
    }

    private JavaCrashReport() {
    }

    /**
     * Keeps [throwable] as the latest Java crash, unless it's the one kept last. Answers the save,
     * for {@link #survived}, or null when nothing was written. Never throws.
     */
    @Nullable
    public static Saved save(@Nullable Thread thread, @Nullable Throwable throwable) {
        try {
            Context context = Utils.getContext();
            if (context == null || throwable == null || lastSaved.get() == throwable) return null;
            lastSaved = new WeakReference<>(throwable);
            String report = build(context, thread, throwable);
            synchronized (LogBufferManager.CLEAR_UNDO_LOCK) {
                String before = LogBufferManager.readCrashReport(context);
                LogBufferManager.persistCrashReport(context, report);
                // Read back rather than kept as built: a report past the ceiling is stored cut.
                return new Saved(before, LogBufferManager.readCrashReport(context));
            }
        } catch (Throwable ignored) {
            // Crash-time code must never get in the way of the handlers after this one.
            return null;
        }
    }

    /**
     * The handlers after ours returned for [thread]'s exception, so the process lives on. Off the
     * main thread, the report goes back to what it held before [saved], while it still holds what
     * [saved] wrote. Never throws.
     */
    public static void survived(@Nullable Thread thread, @Nullable Saved saved) {
        try {
            Context context = Utils.getContext();
            if (context == null || saved == null || thread == null || thread == Looper.getMainLooper().getThread()) return;
            synchronized (LogBufferManager.CLEAR_UNDO_LOCK) {
                if (!LogBufferManager.readCrashReport(context).equals(saved.written)) return;
                LogBufferManager.persistCrashReport(context, saved.before);
            }
        } catch (Throwable ignored) {
            // The exception's report stays; nothing else is lost.
        }
    }

    static String build(Context context, @Nullable Thread thread, Throwable throwable) {
        long versionCode = Utils.getAppVersionCode();
        StringBuilder report = new StringBuilder();
        report.append("timestamp_utc: ").append(LogBufferManager.utcOf(System.currentTimeMillis())).append('\n')
                .append("package: ").append(context.getPackageName()).append('\n')
                .append("app_version: ").append(Utils.getAppVersionName()).append('\n')
                .append("morphe_version: ").append(Utils.getPatchesReleaseVersion()).append('\n')
                .append("android_api: ").append(Build.VERSION.SDK_INT).append('\n')
                .append("abi: ").append(LogBufferManager.abiLine()).append('\n')
                .append("process: ").append(Application.getProcessName()).append('\n')
                .append("thread: ").append(thread == null ? "unknown" : thread.getName()).append('\n')
                .append("exception: ").append(throwable.getClass().getName()).append('\n')
                .append("\n[STACK TRACE]\n").append(stackTrace(throwable));
        // Which switches were on is the first thing a crash report is read for after the trace. A
        // section that can't be read leaves the trace kept without it.
        try {
            report.append(LogBufferManager.reportSectionsText());
        } catch (Throwable unread) {
            report.append("\n[REPORT SECTIONS]\ncould not be read: ").append(unread).append('\n');
        }
        String recent = LogBufferManager.snapshotForCrash(RECENT_EVENTS_MAX_CHARS);
        if (!recent.isEmpty()) report.append("\n[RECENT EVENTS]\n").append(recent).append('\n');
        // Messages and log lines can carry addresses and ids: a download names what it couldn't
        // reach. The version code is a numeric package fact and the source build a literal the
        // patcher embeds; prose redaction would mistake the digit runs in either for content ids.
        return "schema: 1\ncomplete: true\n"
                + "app_version_code: " + (versionCode < 0 ? "unknown" : Long.toString(versionCode)) + '\n'
                + "source_build: " + Utils.getSourceBuildIdentity() + '\n'
                + DiagnosticRedactor.redact(report.toString());
    }

    /** The whole trace, causes and suppressed ones included, as Java prints it. */
    private static String stackTrace(Throwable throwable) {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            throwable.printStackTrace(writer);
        }
        return text.toString();
    }

    /** Forgets the exception kept last, as in a new process. Called by the pause module's reset. */
    public static void resetForTests() {
        lastSaved = new WeakReference<>(null);
    }
}
