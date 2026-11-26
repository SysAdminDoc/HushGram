/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/Feedback.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Iterator;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Tells the user what happened.
 *
 * <p>A download takes seconds and happens away from the screen, so without a word from it a tap
 * looks like it did nothing and the user taps again.
 *
 * <p>The text is HushGram's own, read from {@link app.hushgram.extension.shared.L10n} in the
 * phone's language, rather than taken from the app: Meta's apps keep the words for their own menus
 * in a pack they download, not in the resources of the APK, so there is nothing to borrow.
 *
 * <p>Android keeps at most five toasts of one app waiting, and drops any more without a word
 * ("Package has already queued 5 toasts"). Five saves tapped in a row each said when they started
 * and how they ended, and some of the "Saved" toasts were the ones dropped, so a save that worked
 * looked like one that never ran (#792). So no more than {@link #MOST_WAITING} of these are left
 * with Android at once. Past that, a {@linkplain #note note} that a save started or is waiting is
 * cut short to make room, since its result says more, and a result waits here for its turn rather
 * than being lost.
 */
final class Feedback {

    private Feedback() {}

    /** The source a message that couldn't be shown carries in the diagnostic report. */
    private static final String SOURCE = "Feedback";

    /** The most of these toasts left with Android at once, one short of its five for the app's own. */
    static final int MOST_WAITING = 4;

    /**
     * How long Android shows a short and a long toast, and about how long one takes to go before
     * the next shows. A guess on the long side only makes a result wait a little longer.
     */
    private static final long SHORT_MS = 2_000;
    private static final long LONG_MS = 3_500;
    private static final long BETWEEN_MS = 300;

    /** A toast handed to Android, the note it may be, and when it should be gone. */
    private static final class Shown {
        final Toast toast;
        final boolean note;
        final long length;
        long until;

        Shown(Toast toast, boolean note, long length, long until) {
            this.toast = toast;
            this.note = note;
            this.length = length;
            this.until = until;
        }
    }

    /** A line still to show. */
    private static final class Line {
        final Context context;
        final String text;
        final boolean longToast;
        final boolean note;

        Line(Context context, String text, boolean longToast, boolean note) {
            this.context = context;
            this.text = text;
            this.longToast = longToast;
            this.note = note;
        }
    }

    // Touched on the main thread only.
    private static final ArrayDeque<Shown> SHOWN = new ArrayDeque<>();
    private static final ArrayDeque<Line> WAITING = new ArrayDeque<>();
    private static Context owner;
    private static boolean pumpPosted;

    /**
     * Shows [text], a toast for each of its lines. Android 12 and newer cut a toast at two lines,
     * so a result with a second line (Save all's "Already saved" one) would lose it. Toasts queue,
     * so the lines follow each other.
     */
    static void show(Context applicationContext, String text, boolean longToast) {
        post(applicationContext, text, longToast, false);
    }

    /**
     * Like {@link #show}, for a word that a save has started or is waiting its turn. Its result
     * makes it stale, so with Android's queue full it's the one cut short, and one that would only
     * show after results already waiting isn't shown at all (#792).
     */
    static void note(Context applicationContext, String text, boolean longToast) {
        post(applicationContext, text, longToast, true);
    }

    private static void post(Context applicationContext, String text, boolean longToast, boolean note) {
        if (applicationContext == null || text == null) return;

        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                // This is the one place where this patch runs on the thread that draws the app,
                // so it carries its own guard. An exception from here reaches the
                // looper and stops the app.
                try {
                    forgetAnother(applicationContext);
                    for (String line : text.split("\n")) {
                        if (line.trim().isEmpty()) continue;
                        WAITING.add(new Line(applicationContext, line, longToast, note));
                    }
                    pump();
                } catch (Throwable t) {
                    Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not show a message", t);
                }
            });
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not reach the main thread", t);
        }
    }

    /** What another app (a test's, in practice) left here is no measure of this one's queue. */
    private static void forgetAnother(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        if (app == owner) return;
        owner = app;
        SHOWN.clear();
        WAITING.clear();
    }

    /** Hand Android what it has room for, and come back when it has room for more. Main thread. */
    private static void pump() {
        long now = SystemClock.uptimeMillis();
        forgetGone(now);
        while (!WAITING.isEmpty()) {
            if (SHOWN.size() >= MOST_WAITING && !cutANoteShort(now)) {
                // Only results are with Android. A note behind them would come too late to mean anything.
                WAITING.removeIf(line -> line.note);
                if (WAITING.isEmpty()) return;
                comeBack(SHOWN.peekFirst().until - now);
                return;
            }
            Line line = WAITING.pollFirst();
            long length = line.longToast ? LONG_MS : SHORT_MS;
            Toast toast = Toast.makeText(line.context, line.text, line.longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
            toast.show();
            long start = SHOWN.isEmpty() ? now : Math.max(now, SHOWN.peekLast().until);
            SHOWN.add(new Shown(toast, line.note, length, start + length + BETWEEN_MS));
        }
    }

    /**
     * Forget the toasts that are gone by [now]. One said to end further off than a full queue can
     * reach means the clock it was timed by isn't this one, so none of them can be judged.
     */
    private static void forgetGone(long now) {
        long reach = (long) (MOST_WAITING + 1) * (LONG_MS + BETWEEN_MS);
        for (Shown shown : SHOWN) {
            if (shown.until - now > reach) {
                SHOWN.clear();
                return;
            }
        }
        SHOWN.removeIf(shown -> shown.until <= now);
    }

    /**
     * Cancel the oldest note still with Android, and move the toasts after it up by the time it
     * would have taken. False when there's no note to cut.
     */
    private static boolean cutANoteShort(long now) {
        Iterator<Shown> each = SHOWN.iterator();
        long saved = 0;
        boolean cut = false;
        while (each.hasNext()) {
            Shown shown = each.next();
            if (cut) {
                shown.until -= saved;
                continue;
            }
            if (!shown.note) continue;
            try {
                shown.toast.cancel();
            } catch (Throwable t) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not cut a note short", t);
            }
            each.remove();
            long start = shown.until - shown.length - BETWEEN_MS;
            saved = shown.until - Math.max(now, start);
            cut = true;
        }
        return cut;
    }

    private static void comeBack(long delayMs) {
        if (pumpPosted) return;
        pumpPosted = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            pumpPosted = false;
            try {
                pump();
            } catch (Throwable t) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not show a message", t);
            }
        }, Math.max(1, delayMs));
    }
}
