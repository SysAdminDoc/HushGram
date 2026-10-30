/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.hushgram.extension.shared;

import static app.hushgram.extension.shared.settings.BaseSettings.DEBUG;
import static app.hushgram.extension.shared.settings.BaseSettings.DEBUG_STACKTRACE;
import static app.hushgram.extension.shared.settings.BaseSettings.DEBUG_TOAST_ON_ERROR;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.DiagnosticRedactor;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * Morphe specific logger.  Logging is done to standard device log (accessible thru ADB),
 * and additionally accessible thru {@link LogBufferManager}.
 *
 * All methods are thread safe, and are safe to call even
 * if {@link Utils#getContext()} is not available.
 */
public class Logger {

    /**
     * Log messages using lambdas.
     */
    @FunctionalInterface
    public interface LogMessage {
        /**
         * @return Logger string message. This method is only called if logging is enabled.
         */
        @NonNull
        String buildMessageString();
    }

    private enum LogLevel {
        DEBUG,
        INFO,
        ERROR
    }

    /**
     * Log tag prefix. Only used for system logging.
     */
    private static final String MORPHE_LOG_TAG_PREFIX = "morphe: ";

    private static final String LOGGER_CLASS_NAME = Logger.class.getName();

    /**
     * Error toasts already shown in this process, so one broken hook cannot repeat itself.
     *
     * <p>An error on a path the feed runs fires once per video. The queue then holds a minute of
     * identical toasts, each covering the video underneath it, long after the reader has read the
     * first one. Bounded so a stream of genuinely different failures cannot grow it without end;
     * every occurrence still reaches the log and the diagnostic report either way.
     */
    private static final java.util.Set<String> TOASTED_ERRORS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static final int MAX_TOASTED_ERRORS = 24;

    /** About what logcat keeps of one entry. Anything longer was dropped by the system anyway. */
    private static final int SYSTEM_LOG_MAX_CHARS = 4_000;

    /** An error toast past this is cut: nobody reads that far on a phone. */
    private static final int TOAST_MAX_CHARS = 1_000;

    /**
     * The most of one line the redactor reads before a system log entry or a toast. Four times
     * what logcat keeps, so a value the cut splits sits past what's kept unless redaction took
     * out most of the text before it, and the line it split is dropped all the same.
     */
    private static final int REDACT_MAX_CHARS = 16_000;

    /**
     * @return For outer classes, this returns {@link Class#getSimpleName()}.
     * For static, inner, or anonymous classes, this returns the simple name of the enclosing class.
     * <br>
     * For example, each of these classes returns 'SomethingView':
     * <code>
     * com.company.SomethingView
     * com.company.SomethingView$StaticClass
     * com.company.SomethingView$1
     * </code>
     */
    private static String getOuterClassSimpleName(Object obj) {
        Class<?> logClass = obj.getClass();
        String fullClassName = logClass.getName();
        final int dollarSignIndex = fullClassName.indexOf('$');
        if (dollarSignIndex < 0) {
            return logClass.getSimpleName(); // Already an outer class.
        }

        // Class is inner, static, or anonymous.
        // Parse the simple name full name.
        // A class with no package returns index of -1, but incrementing gives index zero which is correct.
        final int simpleClassNameStartIndex = fullClassName.lastIndexOf('.') + 1;
        return fullClassName.substring(simpleClassNameStartIndex, dollarSignIndex);
    }

    /**
     * Internal method to handle logging to Android Log and {@link LogBufferManager}.
     * Appends the log message, stack trace (if enabled), and exception (if present) to logBuffer
     * with class name but without 'morphe:' prefix.
     *
     * @param logLevel The log level.
     * @param message  Log message object.
     * @param ex       Optional exception.
     * @param mayToast If the line may be shown as a toast, which the settings then decide.
     */
    private static void logInternal(
            LogLevel logLevel,
            @Nullable DiagnosticCategory category,
            @Nullable String explicitSource,
            LogMessage message,
            @Nullable Throwable ex,
            boolean mayToast
    ) {
        // It's very important that no Settings are used in this method outside the two decisions
        // below, as this code is used when a context is not set and thus referencing a setting will
        // crash the app. Both decisions check for the context first and can't throw.
        //
        // The message is built by the caller's lambda, and that lambda reads whatever the caller
        // was in the middle of: a host object's fields, a list's size, a nullable name. Every
        // hook in the extension logs on its way through, so a message that cannot be built must
        // not be the thing that throws out of the hook into the host app.
        //
        // So is everything after the message: the stack trim, the buffer and the toast all run
        // inside a hook too. An Error counts as much as an exception here, since a toString that
        // recurses or a class that fails to load is exactly what a host object hands over.
        String messageString;
        try {
            messageString = message.buildMessageString();
        } catch (Throwable failure) {
            try {
                messageString = "Could not build the log message: " + failure;
            } catch (Throwable ignored) {
                // Throwable.toString() calls getMessage(), and host throwables can override both.
                messageString = "Could not build the log message.";
            }
        }
        // Asked here for every caller, where a failure can't escape: see settingsSayYes(). Callers
        // used to ask before this method was entered, outside any guard.
        boolean includeStackTrace = includeStackTrace();
        boolean showToast = mayToast && shouldShowErrorToast();
        try {
            logBuilt(logLevel, category, explicitSource, message, messageString, ex, includeStackTrace, showToast);
        } catch (Throwable failure) {
            String tag = MORPHE_LOG_TAG_PREFIX + "Logger";
            try {
                Log.e(tag, redacted("Could not log a message: " + messageString, failure, SYSTEM_LOG_MAX_CHARS));
            } catch (Throwable unredactable) {
                try {
                    // The message and the failure's text are what couldn't be made safe, so neither goes.
                    Log.e(tag, "Could not log a message. " + failure.getClass().getName());
                } catch (Throwable ignored) {
                    // Nothing is left to report through.
                }
            }
        }
    }

    /**
     * [text], then [ex]'s stack trace with its causes and suppressed ones, as the system log and
     * an error toast get them: past {@link DiagnosticRedactor}, the policy the exported report
     * uses, and within [max] characters. Android's log reaches whoever holds a bug report or ADB,
     * so a token in a message or an exception's text has to stay out of it as well. Exception
     * types, frames and causes stay. The redactor reads at most {@link #REDACT_MAX_CHARS}.
     */
    private static String redacted(String text, @Nullable Throwable ex, int max) {
        String raw = ex == null ? text : text + '\n' + traceOf(ex);
        boolean longer = raw.length() > REDACT_MAX_CHARS;
        String safe = DiagnosticRedactor.redact(longer ? raw.substring(0, REDACT_MAX_CHARS) : raw);
        // The last line is the one the cut split, so a value in it may be only partly there.
        if (longer) safe = safe.substring(0, Math.max(0, safe.lastIndexOf('\n'))) + "\n[cut]";
        if (safe.length() <= max) return safe;
        int end = safe.lastIndexOf('\n', max);
        return safe.substring(0, end > max / 2 ? end : max) + "\n[cut]";
    }

    /**
     * [ex]'s stack trace as printStackTrace writes it, kept to {@link #REDACT_MAX_CHARS}. A host
     * throwable's getMessage or toString can throw partway, and the type then says what failed.
     */
    private static String traceOf(Throwable ex) {
        StringBuilder trace = new StringBuilder();
        try {
            ex.printStackTrace(new PrintWriter(new Writer() {
                @Override
                public void write(char[] chars, int offset, int length) {
                    trace.append(chars, offset, Math.min(length, Math.max(0, REDACT_MAX_CHARS + 1 - trace.length())));
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            }));
        } catch (Throwable unprintable) {
            trace.append(trace.length() == 0 ? ex.getClass().getName()
                    : "\n[the rest couldn't be printed: " + unprintable.getClass().getName() + "]");
        }
        return trace.toString();
    }

    private static void logBuilt(
            LogLevel logLevel,
            @Nullable DiagnosticCategory category,
            @Nullable String explicitSource,
            LogMessage message,
            String messageString,
            @Nullable Throwable ex,
            boolean includeStackTrace,
            boolean showToast
    ) {
        String className = explicitSource == null ? getOuterClassSimpleName(message) : explicitSource;
        if (category == null) category = legacyCategory(className, logLevel);

        String logText = messageString;

        // Append exception message if present.
        if (ex != null) {
            var exceptionMessage = ex.getMessage();
            if (exceptionMessage != null) {
                logText += "\nException: " + exceptionMessage;
            }
        }

        if (includeStackTrace) {
            var sw = new StringWriter();
            new Throwable().printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            // Remove the stacktrace elements of this class.
            final int loggerIndex = stackTrace.lastIndexOf(LOGGER_CLASS_NAME);
            final int loggerBegins = stackTrace.indexOf('\n', loggerIndex);
            // With no line after the logger's last frame there is nothing of the caller's to
            // keep past it, and substring(-1) would throw.
            logText += loggerBegins >= 0 ? stackTrace.substring(loggerBegins) : "\n" + stackTrace;
        }

        // Do not include "morphe:" prefix in clipboard logs.
        String managerToastString = className + ": " + logText;
        LogBufferManager.appendEvent(category, className, logLevel.name(), logText);

        // The buffer keeps the raw line, which the export redacts. The system log and the toast
        // leave this process as they are, so they're redacted here, the trace written into the text.
        String logTag = MORPHE_LOG_TAG_PREFIX + className;
        String systemText = redacted(logText, ex, SYSTEM_LOG_MAX_CHARS);
        switch (logLevel) {
            case DEBUG:
                Log.d(logTag, systemText);
                break;
            case INFO:
                Log.i(logTag, systemText);
                break;
            case ERROR:
                Log.e(logTag, systemText);
                break;
        }

        if (showToast && TOASTED_ERRORS.size() < MAX_TOASTED_ERRORS
                && TOASTED_ERRORS.add(managerToastString)) {
            Utils.showToastLong(redacted(managerToastString, null, TOAST_MAX_CHARS));
        }
    }

    /**
     * Whether the logging switches [switches] reads are on, asked while a line is being logged.
     *
     * <p>A switch can be unreadable then. A stored value of the wrong type is reported from inside
     * BaseSettings' own class setup, before the switches declared after it are assigned, and read
     * unguarded, the NullPointerException left that setup. BaseSettings then stayed broken for the
     * rest of the process, and setContext threw out of Instagram's start. An unreadable switch
     * counts as off, and the line itself is logged all the same.
     */
    private static boolean settingsSayYes(BooleanSupplier switches) {
        try {
            return switches.getAsBoolean();
        } catch (Throwable unreadable) {
            return false;
        }
    }

    private static boolean shouldLogDebug() {
        // If the app is still starting up and the context is not yet set,
        // then allow debug logging regardless what the debug setting actually is.
        return Utils.context == null || settingsSayYes(() -> DEBUG.get());
    }

    /**
     * Whether a failure inside the bundle is put in front of the reader as a toast.
     *
     * <p>Only while diagnostic logging is on. These messages are written for whoever is fixing
     * the code: they carry the class that failed and the exception's own text, in English, past
     * every translation this bundle ships. A reader who has not turned diagnostic logging on
     * cannot act on "PlaybackQuality: Could not read the playback quality model", and on a path
     * the feed runs they were getting it once per video.
     *
     * <p>Nothing is lost by keeping it out of their way. Every one of these still reaches
     * logcat, the diagnostic buffer and the exported report, which is what the bug report form
     * asks for, and a hook that stopped binding still says so in Hook status.
     */
    private static boolean shouldShowErrorToast() {
        return Utils.context != null && settingsSayYes(() -> DEBUG.get() && DEBUG_TOAST_ON_ERROR.get());
    }

    private static boolean includeStackTrace() {
        return Utils.context != null && settingsSayYes(() -> DEBUG_STACKTRACE.get());
    }

    /**
     * Logs debug messages under the outer class name of the code calling this method.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if {@link BaseSettings#DEBUG} is enabled.
     */
    public static void printDebug(LogMessage message) {
        printDebug(message, null);
    }

    /**
     * Logs debug messages under the outer class name of the code calling this method.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if {@link BaseSettings#DEBUG} is enabled.
     */
    public static void printDebug(LogMessage message, @Nullable Exception ex) {
        if (shouldLogDebug()) {
            logInternal(LogLevel.DEBUG, null, null, message, ex, false);
        }
    }

    /**
     * Logs information messages using the outer class name of the code calling this method.
     */
    public static void printInfo(LogMessage message) {
        printInfo(message, null);
    }

    /**
     * Logs information messages using the outer class name of the code calling this method.
     */
    public static void printInfo(LogMessage message, @Nullable Exception ex) {
        logInternal(LogLevel.INFO, null, null, message, ex, false);
    }

    /**
     * Logs exceptions under the outer class name of the code calling this method.
     * Appends the log message, exception (if present), and toast message (if enabled) to logBuffer.
     */
    public static void printException(LogMessage message) {
        printException(message, null);
    }

    /**
     * Logs exceptions under the outer class name of the code calling this method.
     * <p>
     * If the calling code is showing it's own error toast,
     * instead use {@link #printInfo(LogMessage, Exception)}
     *
     * @param message          log message
     * @param ex               exception (optional)
     */
    public static void printException(LogMessage message, @Nullable Throwable ex) {
        logInternal(LogLevel.ERROR, DiagnosticCategory.PATCH_ERRORS, null, message, ex, true);
    }

    public static void diagnosticDebug(
            DiagnosticCategory category,
            String source,
            LogMessage message
    ) {
        if (shouldLogDebug()) {
            logInternal(LogLevel.DEBUG, category, source, message, null, false);
        }
    }

    public static void diagnosticInfo(
            DiagnosticCategory category,
            String source,
            LogMessage message
    ) {
        logInternal(LogLevel.INFO, category, source, message, null, false);
    }

    public static void diagnosticError(
            DiagnosticCategory category,
            String source,
            LogMessage message,
            @Nullable Throwable throwable
    ) {
        logInternal(LogLevel.ERROR, category, source, message, throwable, true);
    }

    private static DiagnosticCategory legacyCategory(String source, LogLevel level) {
        if (level == LogLevel.ERROR) return DiagnosticCategory.PATCH_ERRORS;
        if (source.startsWith("FollowDiagnostics")) return DiagnosticCategory.FOLLOW;
        if (source.startsWith("Downloads") || source.startsWith("Sticker")) {
            return DiagnosticCategory.DOWNLOADS;
        }
        if (source.startsWith("FeatureGateLab")) return DiagnosticCategory.FEATURE_GATE_LAB;
        if (source.startsWith("Feed") || source.startsWith("Navigation")
                || source.startsWith("BottomNavigation")) {
            return DiagnosticCategory.FEED_AND_NAVIGATION;
        }
        if (source.startsWith("InstagramActivityHook") || source.contains("Preference")
                || source.startsWith("Settings")) {
            return DiagnosticCategory.SETTINGS;
        }
        return DiagnosticCategory.OTHER;
    }
}
