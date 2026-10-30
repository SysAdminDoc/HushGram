/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * When the last tap on an Instagram screen ended.
 *
 * <p>Every touch on an Instagram activity passes through {@code IgFragmentActivity.dispatchTouchEvent},
 * and the patch hands each one here before Instagram sees it. A tap is a finger that went down and
 * came up again without moving further than the touch slop, however long it stayed: a quick tap and
 * a long press both count, a scroll, a fling or a second finger don't. The time the finger came up
 * is kept, on the clock {@link MotionEvent#getEventTime()} uses, and how long it stayed down, which
 * tells a press and hold from a tap ({@link TapToPlay#resumeHeldStory}), and where it came up, which
 * finds the play button of the video a tap started ({@link PlayButtonFinder}). A later non-tap gesture
 * invalidates that tap, so swiping to another reel can't borrow it.
 *
 * <p>It only reads the event. It never changes, consumes or recycles one, and the hook returns
 * nothing, so Instagram gets every event exactly as it was.
 */
public final class TapClock {
    static final long NO_TAP = Long.MIN_VALUE;

    /** The slop a test asks for, or -1 for the activity's own. */
    static volatile int slopForTests = -1;

    private static volatile long lastTapUp = NO_TAP;
    private static volatile long lastTapHeld = -1;
    /** Where the last tap came up, in its window's pixels. */
    private static float lastTapX;
    private static float lastTapY;
    /** The activity the last finger went down on, which the tap's window belongs to. */
    private static volatile WeakReference<Activity> lastActivity = new WeakReference<>(null);
    private static long downTime;
    private static int slop = -1;
    private static boolean tracking;
    private static boolean moved;
    private static float downX;
    private static float downY;

    private TapClock() { }

    /** The hook, first thing in IgFragmentActivity.dispatchTouchEvent. */
    public static void touch(Activity activity, MotionEvent event) {
        try {
            if (event == null) return;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                HookStatus.bound(FamilyNames.TAP_TO_PLAY, "tap clock");
                if (lastActivity.get() != activity) lastActivity = new WeakReference<>(activity);
            }
            record(action, event.getX(), event.getY(), event.getEventTime(), slop(activity));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "tap clock", failure);
        }
    }

    static synchronized void record(int action, float x, float y, long time, int touchSlop) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                moved = false;
                downX = x;
                downY = y;
                downTime = time;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger makes a pinch or a zoom, not a tap.
                invalidateTap();
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking && beyond(x, y, touchSlop)) invalidateTap();
                break;
            case MotionEvent.ACTION_UP:
                if (tracking && !moved && !beyond(x, y, touchSlop)) {
                    lastTapHeld = time - downTime;
                    lastTapX = x;
                    lastTapY = y;
                    lastTapUp = time;
                } else if (tracking) {
                    invalidateTap();
                }
                tracking = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                invalidateTap();
                tracking = false;
                break;
            default:
                break;
        }
    }

    private static void invalidateTap() {
        lastTapUp = NO_TAP;
        lastTapHeld = -1;
        if (!moved) TapToPlay.nonTapGesture();
        moved = true;
    }

    private static boolean beyond(float x, float y, int touchSlop) {
        float dx = x - downX;
        float dy = y - downY;
        return dx * dx + dy * dy > (float) touchSlop * touchSlop;
    }

    /** The touch slop in pixels, read once from the first activity that asks. */
    private static int slop(Activity activity) {
        int forTests = slopForTests;
        if (forTests >= 0) return forTests;
        if (slop < 0 && activity != null) slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        return Math.max(slop, 0);
    }

    /** How long ago the last tap ended, or -1 when none has, or the clock reads earlier than it. */
    static long msSinceTap(long now) {
        long at = lastTapUp;
        if (at == NO_TAP || now < at) return -1;
        return now - at;
    }

    /** Whether a finger is down right now and has moved further than the touch slop: a drag. */
    static synchronized boolean dragging() {
        return tracking && moved;
    }

    /** How long the finger stayed down for the last tap, or -1 when there's none. */
    static long heldMs() {
        return lastTapUp == NO_TAP ? -1 : lastTapHeld;
    }

    /**
     * Where the tap that came up at [tapAt] landed, in its window's pixels, or null when that's no
     * longer the last tap.
     */
    @Nullable
    static synchronized float[] pointOf(long tapAt) {
        if (lastTapUp == NO_TAP || lastTapUp != tapAt) return null;
        return new float[] { lastTapX, lastTapY };
    }

    /** The window the last tap landed on, its activity's decor view, or null when that's gone. */
    @Nullable
    static View window() {
        Activity activity = lastActivity.get();
        return activity == null ? null : activity.getWindow().peekDecorView();
    }

    static synchronized void forget() {
        lastTapUp = NO_TAP;
        lastTapHeld = -1;
        lastActivity = new WeakReference<>(null);
        tracking = false;
        moved = false;
        slop = -1;
    }
}
