/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.function.LongSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Change the like animation" patch's Grow the heart switch (#461).
 *
 * <p>The heart that pops up over a post on a double tap is one drawable Instagram plays each time.
 * The patch tells this class when any drawable of that kind starts playing ({@link #played}), and
 * wraps the heart's own draw call in {@link #begin} and {@link #end}. Each double tap on the same
 * post within {@link #WINDOW_MS} of the last one is a step bigger ({@link #STEPS}), with a small
 * spring overshoot on the way, and the first tap after the window starts at the normal size again.
 * A reel keeps one heart, but a feed post builds a new one for every double tap, so a run follows
 * the view the heart draws into ({@link Drawable#getCallback}) and not the drawable. A heart that
 * isn't in a view yet when it starts playing, as the feed's isn't, counts as the same post when
 * it plays inside the window: nobody scrolls to another post and double taps it that fast.
 * Nothing Instagram stores is written: with the switch off or HushGram paused the heart is drawn
 * as it was.
 */
public final class LikeHeartGrowth {
    /** How long after a double tap the next one still counts as the same run. */
    static final long WINDOW_MS = 1_500L;
    /** A play this soon after the last is the same double tap played twice, not another one. */
    static final long SAME_TAP_MS = 300L;
    /** The heart's size at each step of a run, the last being the cap. */
    static final float[] STEPS = {1f, 1.25f, 1.5f, 1.75f};

    /** The spring the size follows to its step: damping ratio and natural frequency in radians a second. */
    private static final double DAMPING = 0.45;
    private static final double FREQUENCY = 20.0;
    private static final long SETTLED_MS = 1_200L;

    static final String COUNT_GREW = "double taps that grew the heart";
    static final String PLAYED = "heart played";
    static final String DRAW = "heart draw";

    private static LongSupplier clock = SystemClock::uptimeMillis;
    private static WeakReference<Object> last = new WeakReference<>(null);
    /** The view the run's heart draws into, once it's known. */
    private static WeakReference<Object> lastHost = new WeakReference<>(null);
    private static long lastAt = Long.MIN_VALUE / 2;
    private static int step;
    private static float from = 1f;
    private static long startedAt;
    /** The canvas {@link #begin} scaled and {@link #end} gives back, or null when it left the draw alone. */
    private static Canvas scaled;

    private LikeHeartGrowth() {
    }

    /** Filled in by the patch: whether [drawable] is the double tap heart's kind. Unpatched, never. */
    static boolean isHeart(@Nullable Object drawable) {
        return false;
    }

    /** Whether the switch is on and HushGram is ready and not paused. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.GROW_LIKE_HEART.get();
    }

    /**
     * Injected first thing in the call that starts a keyframes drawable playing, with the drawable.
     * Only the heart's kind counts, and only while the switch is on. Never throws.
     */
    public static void played(@Nullable Object drawable) {
        try {
            if (!isHeart(drawable)) return;
            HookStatus.invoked(FamilyNames.LIKE_ANIMATION);
            if (!enabled()) return;
            tap(drawable, clock.getAsLong());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIKE_ANIMATION, PLAYED, failure);
        }
    }

    /**
     * One double tap on [heart] at [now]: the next step of the run, or the first step of a new one. A
     * second heart playing within {@link #SAME_TAP_MS} belongs to the same double tap: a fresh heart
     * for the same post takes the run's size over, and any other is left out of the run.
     */
    static void tap(Object heart, long now) {
        Object host = heart instanceof Drawable ? ((Drawable) heart).getCallback() : null;
        boolean samePost = samePost(heart, host);
        if (now - lastAt < SAME_TAP_MS) {
            if (samePost) {
                last = new WeakReference<>(heart);
                if (host != null) lastHost = new WeakReference<>(host);
            }
            return;
        }
        boolean run = samePost && now - lastAt <= WINDOW_MS;
        from = run ? runScale(now) : 1f;
        step = run ? Math.min(step + 1, STEPS.length - 1) : 0;
        last = new WeakReference<>(heart);
        if (host != null || !run) lastHost = new WeakReference<>(host);
        lastAt = now;
        startedAt = now;
        if (step > 0) HookStatus.counted(FamilyNames.LIKE_ANIMATION, COUNT_GREW);
    }

    /** Whether [heart], drawn into [host], is on the run's post: the same heart, the same view, or a view not known yet. */
    private static boolean samePost(Object heart, @Nullable Object host) {
        if (last.get() == heart) return true;
        Object runHost = lastHost.get();
        return host == null || runHost == null || host == runHost;
    }

    /** The size [heart] is drawn at at [now]: 1 unless it is the one the run is on. */
    static float scaleAt(@Nullable Object heart, long now) {
        if (heart == null || last.get() != heart) return 1f;
        return runScale(now);
    }

    /** The run's size at [now], on its spring from the size it was at toward its step. */
    private static float runScale(long now) {
        float target = STEPS[step];
        long age = now - startedAt;
        if (age >= SETTLED_MS || from == target) return target;
        if (age <= 0) return from;
        double t = age / 1000.0;
        double damped = Math.sqrt(1 - DAMPING * DAMPING);
        double decay = Math.exp(-DAMPING * FREQUENCY * t);
        double wave = Math.cos(FREQUENCY * damped * t) + DAMPING / damped * Math.sin(FREQUENCY * damped * t);
        return (float) (target + (from - target) * decay * wave);
    }

    /**
     * Injected first thing in the heart's draw, with the heart and the canvas it draws on: scales
     * the canvas about the middle of the heart while a run has grown it. Never throws.
     */
    public static void begin(@Nullable Object heart, @Nullable Canvas canvas) {
        scaled = null;
        try {
            if (canvas == null || !(heart instanceof Drawable) || !enabled()) return;
            if (heart == last.get() && lastHost.get() == null) lastHost = new WeakReference<>(((Drawable) heart).getCallback());
            float scale = scaleAt(heart, clock.getAsLong());
            if (scale == 1f) return;
            Rect bounds = ((Drawable) heart).getBounds();
            canvas.save();
            scaled = canvas;
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            canvas.translate(cx, cy);
            canvas.scale(scale, scale);
            canvas.translate(-cx, -cy);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIKE_ANIMATION, DRAW, failure);
        }
    }

    /** Injected right before the heart's draw returns, taking nothing: undoes what {@link #begin} did, if it did anything. Never throws. */
    public static void end() {
        try {
            Canvas canvas = scaled;
            scaled = null;
            if (canvas != null) canvas.restore();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIKE_ANIMATION, DRAW, failure);
        }
    }

    static void clockForTests(@Nullable LongSupplier replacement) {
        clock = replacement == null ? SystemClock::uptimeMillis : replacement;
    }

    static void resetForTests() {
        clock = SystemClock::uptimeMillis;
        last = new WeakReference<>(null);
        lastHost = new WeakReference<>(null);
        lastAt = Long.MIN_VALUE / 2;
        step = 0;
        from = 1f;
        startedAt = 0L;
        scaled = null;
    }
}
