/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsSeekBar;

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
 * <p>It holds whatever the patch's switch says, since it only gives back taps that were the
 * buttons' to begin with. Paused, before the settings are read, or when anything goes wrong, every
 * press goes to Instagram's listener.
 */
public final class ReelSeekTouch {
    /** Below this a view counts as faded out, the way Clean up Reels hides the buttons. */
    private static final float SEEN_ALPHA = 0.05f;

    private static volatile boolean loggedPass;
    /** Set while the glass tab bar hands the strip a press that went down over the bar. Main thread only. */
    private static boolean barPress;

    private ReelSeekTouch() {
    }

    /**
     * Hands {@code event} to {@code strip} for the glass tab bar, which catches the presses that land
     * where the strip runs over its top (#526). Those were meant for the seek bar, so the gate keeps
     * them for Instagram's listener even with a tab under the point. Answers what the strip answered.
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

        Gate(View.OnTouchListener instagram) {
            this.instagram = instagram;
        }

        @Override
        public boolean onTouch(View strip, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) passing = !barPress && givesWay(strip, event);
            return !passing && instagram.onTouch(strip, event);
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
     * takes in the whole strip, like the reel page, stays behind it, and a point on a seek bar stays the
     * strip's. Every area is read in the same global coordinates, the strip's point moved there by the
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
            View taker = taker(child, pointX, pointY);
            if (taker == null) continue;
            Rect area = new Rect();
            taker.getGlobalVisibleRect(area);
            boolean button = !(taker instanceof AbsSeekBar) && taker.isEnabled() && !area.contains(stripArea);
            return button ? taker : null;
        }
        return null;
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
