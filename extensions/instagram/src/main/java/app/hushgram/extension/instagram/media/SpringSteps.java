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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * spring system that set off since it began, or in the {@link #KEEP_MS} before, a frame at a time on
 * the main thread, for {@link #KEEP_MS} after its last start, and {@link #RUN_MS} at most in a row.
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
     * The longest a spring system is stepped on starts made from frames, a step's or one the screen
     * gives Instagram's own looper. One that keeps setting itself off, or two that keep setting each
     * other off, stop here, rather than waking the phone every frame for the whole session. A start from
     * outside any frame, like the viewer moving to the next story, begins a new run.
     */
    static final long RUN_MS = 10_000;

    /** Each spring system's frame callback, with when its steps stop. */
    private static final Map<Choreographer.FrameCallback, Long> kept = new WeakHashMap<>();

    /**
     * When each spring system's run began, for the whole session. It outlives the steps, so a system
     * that keeps setting itself off on the screen's frames carries on its run once its steps have
     * stopped, rather than beginning a new one on the next frame.
     */
    private static final Map<Choreographer.FrameCallback, Long> runs = new WeakHashMap<>();

    /**
     * The class names of the frame callbacks seen. One's doFrame on the stack, outside a step, is a
     * frame the screen gave Instagram's own looper. On 454 that's the shared lambda class 08lR, so
     * another frame it runs counts too, which only happens while the screen gives frames anyway.
     */
    private static final Set<String> loopers = new HashSet<>();

    /** When each spring system last set off with no session, so one still moving as a session begins is stepped too. */
    private static final Map<Choreographer.FrameCallback, Long> lately = new WeakHashMap<>();
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
     * Steps [frames] from now until {@link #KEEP_MS} after this call, and no further than
     * {@link #RUN_MS} into its run. A start made from a step, of its own system or another, carries on
     * the run of the system being stepped, the later one if [frames] has a run of its own. One made from
     * a frame the screen gives Instagram's own looper carries on [frames]' own run, so a system that
     * keeps setting itself off there stops being stepped once that run is over. Any other begins a new run.
     */
    static void keep(Choreographer.FrameCallback frames) {
        long now = SystemClock.uptimeMillis();
        loopers.add(frames.getClass().getName());
        Long last = runs.get(frames);
        long began;
        if (steppingRun != NOT_STEPPING) began = last == null ? steppingRun : Math.max(last, steppingRun);
        else if (last != null && inScreenFrame()) began = last;
        else began = now;
        runs.put(frames, began);
        long until = Math.min(now + KEEP_MS, began + RUN_MS);
        // Past its run: the steps it had, if any, are already due to stop.
        if (until <= now) return;
        kept.put(frames, until);
        post();
    }

    /**
     * Whether a frame the screen gives one of Instagram's spring loopers is under way: the doFrame of a
     * frame callback seen before is on the stack, and no step is (the caller asks outside one). Only a
     * start whose system has a run asks, so a first start costs nothing.
     */
    private static boolean inScreenFrame() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if ("doFrame".equals(element.getMethodName()) && loopers.contains(element.getClassName())) return true;
        }
        return false;
    }

    /** Notes that [frames] set off with no session under way. */
    static void saw(Choreographer.FrameCallback frames) {
        lately.put(frames, SystemClock.uptimeMillis());
    }

    /**
     * As a session begins, steps each spring system that set off in the last {@link #KEEP_MS}, as a
     * start made now. A slide you set off just before leaving is still moving, and once Instagram is
     * hidden it gets no frames to end on; one that has come to rest costs a call a step.
     */
    static void carryOn() {
        long now = SystemClock.uptimeMillis();
        List<Choreographer.FrameCallback> moving = new ArrayList<>(lately.size());
        for (Map.Entry<Choreographer.FrameCallback, Long> entry : lately.entrySet()) {
            if (entry.getValue() > now - KEEP_MS) moving.add(entry.getKey());
        }
        lately.clear();
        for (Choreographer.FrameCallback frames : moving) keep(frames);
    }

    /** Stops stepping, as the session ends. */
    static void stop() {
        kept.clear();
        runs.clear();
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
        for (Iterator<Map.Entry<Choreographer.FrameCallback, Long>> it = kept.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Choreographer.FrameCallback, Long> entry = it.next();
            if (entry.getValue() <= now) it.remove();
            else due.add(entry.getKey());
        }
        // A step can set another spring off, which comes back through keep: the copy is walked. While
        // Instagram is still leaving, its screen draws and the springs get real frames, and a step can
        // end the session, so that's asked before each one.
        Choreographer choreographer = Choreographer.getInstance();
        for (Choreographer.FrameCallback frames : due) {
            if (!BackgroundPlay.active()) break;
            if (!kept.containsKey(frames)) continue;
            Long began = runs.get(frames);
            try {
                choreographer.removeFrameCallback(frames);
                steppingRun = began != null ? began : now;
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
        lately.clear();
        loopers.clear();
        handler = null;
        steppingRun = NOT_STEPPING;
        stepsForTests = 0;
    }
}
