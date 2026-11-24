/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsSeekBar;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Gives a reel's buttons back the taps Instagram's seek strip takes from them (#452).
 *
 * <p>Instagram 453 lays a plain, invisible view across the bottom of Reels, one step above the reel,
 * so a drag a little above the seek bar still seeks. Its height comes from the screen and the
 * reel's layout, and on some phones it reaches over the More button and the audio square right
 * above the bar, so a tap on either scrubs the reel instead. The "Keep a seek bar on Reels" patch
 * hands the strip's touch listener to {@link #listen}, which puts it behind a gate: a press that
 * lands on a button you can see, one the strip covers only part of, goes to that button, and every
 * other press goes to Instagram's listener as before. The strip isn't clickable, so once the gate
 * turns a press away, Android hands the whole gesture to the views under it.
 *
 * <p>The strip also runs over the top of Instagram's tab bar, its sibling: on a Galaxy S22 it covers the
 * top 28 px of the tabs. Stock Instagram
 * seeks from that band, so a drag there stays the seek bar's (#526). A tap there went to the seek bar too,
 * and the tab never opened (#855). A press that lands on a tab now goes to the tab bar while it's a tap or
 * a hold, and to Instagram's listener once it moves past the touch slop.
 *
 * <p>It holds whatever the patch's switch says, since it only gives back taps that were the
 * buttons' to begin with. Paused, before the settings are read, or when anything goes wrong, every
 * press goes to Instagram's listener.
 */
public final class ReelSeekTouch {
    /** Below this a view counts as faded out, the way Clean up Reels hides the buttons. */
    private static final float SEEN_ALPHA = 0.05f;
    /** Instagram's tab bar, whose top the strip runs over. */
    static final String TAB_BAR = "tab_bar";
    /** The tab bar's id, looked up once, or -1 before that. Tests set their own, since a test app has none of Instagram's. */
    static volatile int tabBarId = -1;

    private static volatile boolean loggedPass;
    /** Set while the glass tab bar hands the strip a press that went down over the bar. Main thread only. */
    private static boolean barPress;

    private ReelSeekTouch() {
    }

    /**
     * Hands {@code event} to {@code strip} for the glass tab bar, which catches the presses that land
     * where the strip runs over its top (#526). A drag there is the seek bar's, so the gate keeps it for
     * Instagram's listener even with a reel button under the point, and a tap on a tab is the tab's (#855).
     * Answers what the strip answered.
     */
    public static boolean fromTheBar(View strip, MotionEvent event) {
        barPress = true;
        try {
            return strip.dispatchTouchEvent(event);
        } finally {
            barPress = false;
        }
    }

    /**
     * Injected in place of Instagram's {@code strip.setOnTouchListener(instagram)} on the seek strip,
     * with the same two values. Sets the gate in front of Instagram's listener.
     */
    public static void listen(View strip, View.OnTouchListener instagram) {
        HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
        strip.setOnTouchListener(instagram == null ? null : new Gate(instagram));
    }

    /** Instagram's listener behind the check made at each press. */
    static final class Gate implements View.OnTouchListener {
        final View.OnTouchListener instagram;
        private boolean passing;
        /**
         * The tab bar a press went down on, on one of its tabs, where the strip runs over the bar (#855). The
         * press goes to the bar while it can still be a tap, and Instagram's listener only gets it once it
         * moves far enough to be a drag along the seek bar. Null for every other press.
         */
        @Nullable private ViewGroup tabBar;
        /** That press's first event, kept for Instagram's listener in case it turns into a drag. */
        @Nullable private MotionEvent press;

        Gate(View.OnTouchListener instagram) {
            this.instagram = instagram;
        }

        @Override
        public boolean onTouch(View strip, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                forget();
                passing = !barPress && givesWay(strip, event);
                if (!passing && toATab(strip, event)) return true;
            } else if (tabBar != null) {
                return onTheTab(strip, event, action);
            }
            return !passing && instagram.onTouch(strip, event);
        }

        /** Hands a press that went down on a tab under the strip to the tab bar, and answers whether it did. */
        private boolean toATab(View strip, MotionEvent event) {
            ViewGroup bar = tabBarUnder(strip, event);
            if (bar == null) return false;
            tabBar = bar;
            press = MotionEvent.obtain(event);
            if (!loggedTab) {
                loggedTab = true;
                Logger.printDebug(() -> "Reel seek strip: a press on a tab went to the tab bar");
            }
            HookStatus.counted(FamilyNames.REEL_SEEK_BAR, COUNT_TAB_PRESS);
            toTheBar(strip, bar, event);
            return true;
        }

        /**
         * The rest of a press that went down on a tab: the bar's while it stays a tap, so a tap opens the tab
         * and a hold is the tab's own. Moved past the touch slop it's a drag along the seek bar, as without
         * HushGram (#526): the bar hears it end and Instagram's listener gets the press and the drag.
         */
        private boolean onTheTab(View strip, MotionEvent event, int action) {
            ViewGroup bar = tabBar;
            MotionEvent first = press;
            if (action == MotionEvent.ACTION_MOVE && first != null && moved(strip, first, event)) {
                tabBar = null;
                press = null;
                try {
                    cancel(strip, bar, event);
                    instagram.onTouch(strip, first);
                } finally {
                    first.recycle();
                }
                return instagram.onTouch(strip, event);
            }
            toTheBar(strip, bar, event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) forget();
            return true;
        }

        private void forget() {
            tabBar = null;
            if (press != null) press.recycle();
            press = null;
        }
    }

    /** What a press on a tab under the strip is counted under (#855). */
    static final String COUNT_TAB_PRESS = "presses on a tab under the seek strip";
    private static volatile boolean loggedTab;

    /**
     * Instagram's tab bar beside {@code strip}, when the press {@code event} starts lands on one of its
     * tabs where the strip runs over the bar (#855), or null. Null too while paused or before the
     * settings are read, which leaves the press Instagram's.
     */
    @Nullable
    static ViewGroup tabBarUnder(View strip, MotionEvent event) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return null;
            ViewParent parent = strip.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            Rect stripArea = new Rect();
            Point offset = new Point();
            if (!strip.getGlobalVisibleRect(stripArea, offset)) return null;
            int x = offset.x + Math.round(event.getX());
            int y = offset.y + Math.round(event.getY());
            ViewGroup group = (ViewGroup) parent;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child == strip || !(child instanceof ViewGroup) || !onTheTabBar(child, x, y)) continue;
                // The bar's own padding or a gap between tabs is the strip's, as before.
                View tab = taker(child, x, y);
                return tab == null || tab == child || tab instanceof AbsSeekBar ? null : (ViewGroup) child;
            }
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek strip tab press", failure);
            return null;
        }
    }

    /** Whether {@code event} has gone past the touch slop from where {@code first} went down. */
    private static boolean moved(View strip, MotionEvent first, MotionEvent event) {
        int slop = ViewConfiguration.get(strip.getContext()).getScaledTouchSlop();
        return Math.hypot(event.getX() - first.getX(), event.getY() - first.getY()) > slop;
    }

    /** Hands {@code event}, in the strip's pixels, to {@code bar} in the bar's own. */
    static void toTheBar(View strip, ViewGroup bar, MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        try {
            // The two are siblings: out to their parent's pixels the way the parent hands a press in, then into
            // the bar's, its own slide or scale included.
            if (!strip.getMatrix().isIdentity()) copy.transform(strip.getMatrix());
            copy.offsetLocation(strip.getLeft() - bar.getLeft(), strip.getTop() - bar.getTop());
            if (!bar.getMatrix().isIdentity()) {
                Matrix inverse = new Matrix();
                if (bar.getMatrix().invert(inverse)) copy.transform(inverse);
            }
            bar.dispatchTouchEvent(copy);
        } finally {
            copy.recycle();
        }
    }

    /** Tells {@code bar} the press it had is over, the way Android does when a parent takes a gesture. */
    private static void cancel(View strip, ViewGroup bar, MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            toTheBar(strip, bar, cancel);
        } finally {
            cancel.recycle();
        }
    }

    /** Whether the press {@code event} starts lands on a button under the strip that should have it. */
    static boolean givesWay(View strip, MotionEvent event) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return false;
            View button = buttonAt(strip, event.getX(), event.getY());
            if (button == null) return false;
            if (!loggedPass) {
                loggedPass = true;
                Logger.printDebug(() -> "Reel seek strip: a press on " + button.getClass().getSimpleName()
                        + " went to it, not the seek bar");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek strip press", failure);
            return false;
        }
    }

    /**
     * The button under the strip's own point ({@code x}, {@code y}) among the views beside the strip and
     * theirs: one that's shown, enabled and tappable, that the strip covers only part of. A view that
     * takes in the whole strip, like the reel page, stays behind it, and a point on a seek bar or on the tab
     * bar stays the strip's. Every area is read in the same global coordinates, the strip's point moved there by the
     * strip's own offset.
     */
    static View buttonAt(View strip, float x, float y) {
        ViewParent parent = strip.getParent();
        if (!(parent instanceof ViewGroup)) return null;
        Rect stripArea = new Rect();
        Point offset = new Point();
        if (!strip.getGlobalVisibleRect(stripArea, offset)) return null;
        int pointX = offset.x + Math.round(x);
        int pointY = offset.y + Math.round(y);
        if (!stripArea.contains(pointX, pointY)) return null;
        ViewGroup group = (ViewGroup) parent;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child == strip) continue;
            if (onTheTabBar(child, pointX, pointY)) return null;
            View taker = taker(child, pointX, pointY);
            if (taker == null) continue;
            Rect area = new Rect();
            taker.getGlobalVisibleRect(area);
            boolean button = !(taker instanceof AbsSeekBar) && taker.isEnabled() && !area.contains(stripArea);
            return button ? taker : null;
        }
        return null;
    }

    /** Whether {@code view} is Instagram's tab bar, shown, with the global point ({@code x}, {@code y}) on it. */
    static boolean onTheTabBar(View view, int x, int y) {
        int id = view.getId();
        if (id == View.NO_ID || id != tabBarId(view.getContext()) || view.getVisibility() != View.VISIBLE) return false;
        Rect area = new Rect();
        return view.getGlobalVisibleRect(area) && area.contains(x, y);
    }

    private static int tabBarId(android.content.Context context) {
        int id = tabBarId;
        if (id == -1) {
            id = context.getResources().getIdentifier(TAB_BAR, "id", Utils.resourcePackage(context));
            tabBarId = id;
        }
        return id;
    }

    /**
     * The view under the point in {@code view} and its children that Android would hand the press to,
     * the topmost shown one that's tappable, or a seek bar there. Like Android, it stops at a tappable
     * view that's turned off, which takes the press and does nothing with it.
     */
    private static View taker(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() < SEEN_ALPHA) return null;
        Rect area = new Rect();
        if (!view.getGlobalVisibleRect(area) || !area.contains(x, y)) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View taker = taker(group.getChildAt(i), x, y);
                if (taker != null) return taker;
            }
        }
        return view instanceof AbsSeekBar || view.isClickable() || view.isLongClickable() ? view : null;
    }
}
