/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Frames for Instagram's springs while it plays in the background (#497).
 *
 * <p>The story viewer moves to the next story with a spring, and the next story's player only
 * starts once that spring comes to rest. A spring moves one step on each frame of the screen, and a
 * hidden app gets no frames, so a video story that ends in the background left the viewer stuck
 * between two stories with nothing playing. While a session is in the background, this runs each
 * spring system that set off since it began a frame at a time on the main thread, for
 * {@link #KEEP_MS} after its last start, and {@link #RUN_MS} at most in a row.
 *
 * <p>Each step goes to the spring system's own frame callback. It times itself by the clock, rests
 * by itself, and does nothing once every spring rests, so a step past the end costs a call. A step
 * first takes back the frame the system asked the screen for, so coming back finds one waiting, not
 * one for every step. A step that throws hands that frame back to the screen and isn't stepped
 * again, so the system goes on as Instagram's own looper would once Instagram is back. Main thread
 * only.
 */
final class SpringSteps {
    /** About one frame of a 60 Hz screen. */
    static final long FRAME_MS = 16;

    /** How long a spring system is stepped after it last set off; a story's slide takes well under a second. */
    static final long KEEP_MS = 3_000;

    /**
     * The longest a spring system is stepped on starts made from steps. One that keeps setting itself
     * off, or two that keep setting each other off, stop here, rather than waking the phone every frame
     * for the whole session. A start from outside a step, like the viewer moving to the next story,
     * begins a new run.
     */
    static final long RUN_MS = 10_000;

    /** Each spring system's frame callback, with when its steps stop and when this run of them began. */
    private static final Map<Choreographer.FrameCallback, long[]> kept = new WeakHashMap<>();
    private static final Runnable STEP = SpringSteps::step;
    @Nullable
    private static Handler handler;
    private static boolean posted;
    /** When the run of the system being stepped began, or {@link #NOT_STEPPING} between steps. */
    private static long steppingRun = Long.MIN_VALUE;
    private static final long NOT_STEPPING = Long.MIN_VALUE;
    static int stepsForTests;

    private SpringSteps() {
    }

    /**
     * Steps [frames] from now until {@link #KEEP_MS} after this call. A start made from a step, of its
     * own system or another, carries on the run of the system being stepped, the later one if [frames]
     * has a run of its own, and goes no further than {@link #RUN_MS} into it; any other begins a new run.
     */
    static void keep(Choreographer.FrameCallback frames) {
        long now = SystemClock.uptimeMillis();
        long[] run = kept.get(frames);
        if (steppingRun == NOT_STEPPING) {
            if (run == null) kept.put(frames, new long[] {now + KEEP_MS, now});
            else {
                run[0] = now + KEEP_MS;
                run[1] = now;
            }
        } else {
            long began = run == null ? steppingRun : Math.max(run[1], steppingRun);
            long until = Math.min(now + KEEP_MS, began + RUN_MS);
            if (run == null) kept.put(frames, new long[] {until, began});
            else {
                run[0] = until;
                run[1] = began;
            }
        }
        post();
    }

    /** Stops stepping, as the session ends. */
    static void stop() {
        kept.clear();
        if (posted) {
            posted = false;
            handler().removeCallbacks(STEP);
        }
    }

    static int keptForTests() {
        return kept.size();
    }

    private static void step() {
        posted = false;
        if (kept.isEmpty()) return;
        if (!BackgroundPlay.keepsSprings()) {
            kept.clear();
            return;
        }
        long now = SystemClock.uptimeMillis();
        List<Choreographer.FrameCallback> due = new ArrayList<>(kept.size());
        for (Iterator<Map.Entry<Choreographer.FrameCallback, long[]>> it = kept.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Choreographer.FrameCallback, long[]> entry = it.next();
            if (entry.getValue()[0] <= now) it.remove();
            else due.add(entry.getKey());
        }
        // A step can set another spring off, which comes back through keep: the copy is walked. While
        // Instagram is still leaving, its screen draws and the springs get real frames, and a step can
        // end the session, so that's asked before each one.
        Choreographer choreographer = Choreographer.getInstance();
        for (Choreographer.FrameCallback frames : due) {
            if (!BackgroundPlay.active()) break;
            long[] run = kept.get(frames);
            if (run == null) continue;
            try {
                choreographer.removeFrameCallback(frames);
                steppingRun = run[1];
                try {
                    frames.doFrame(System.nanoTime());
                } finally {
                    steppingRun = NOT_STEPPING;
                }
                stepsForTests++;
            } catch (Throwable t) {
                kept.remove(frames);
                // The looper's frame is taken back before a step and its own doFrame posts the next
                // one last, so without this a throw would leave the system started with no frame
                // coming, and its springs would never move again.
                choreographer.removeFrameCallback(frames);
                choreographer.postFrameCallback(frames);
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "spring step", t);
            }
        }
        if (!kept.isEmpty()) post();
    }

    private static void post() {
        if (posted) return;
        posted = true;
        handler().postDelayed(STEP, FRAME_MS);
    }

    private static Handler handler() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }

    /** Clears everything, for tests. */
    static void resetForTests() {
        stop();
        handler = null;
        steppingRun = NOT_STEPPING;
        stepsForTests = 0;
    }
}
