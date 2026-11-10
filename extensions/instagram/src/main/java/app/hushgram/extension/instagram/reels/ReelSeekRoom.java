/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;

import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keeps a reel's seek bar out of the strip at the window's foot where a swipe up leaves the app (#450, #385).
 *
 * <p>A viewer that runs to the window's foot has its seek bar on the very last pixels: Instagram's own screen
 * for a reel opened from a profile, or any reel once the tab bar has given up its room. A drag along it then
 * can swipe home. The floating glass pill raises a reel's controls past that strip for the viewers it knows of,
 * but nothing did for the rest. So each time the bar's position changes, at most every {@link #LOOK_MS}, the
 * view holding the bar goes up by however far the bar still reaches into the strip. A bar that's clear of it,
 * as anything already raised is, gets nothing, and one that stops needing it goes back.
 *
 * <p>The holder is moved rather than the bar, so the bar's time label, which follows what the views around the
 * bar do, comes along. Where the holder sits in a short row, the row goes instead: Instagram draws the times it
 * shows during a drag beside the holder, and left behind they'd end up under the raised bar (#567). Off when
 * HushGram is paused or the settings aren't ready, when anything lifted is put back.
 */
final class ReelSeekRoom {
    /** How often, at most, a playing bar is measured. */
    static final long LOOK_MS = 300;

    /** A parent over this share of the window is the page around the bar, not a row of its controls. */
    static final int ROW_SHARE = 4;

    /** The step a failure is reported under. */
    static final String STEP = "seek bar room";

    /** What's been done to one holder. */
    private static final class Held {
        /** How far this has the holder up. */
        int lift;
        /** The holder's translation as this last left it, to tell when Instagram has rewritten it. */
        float wrote;
        long lookedAt;
    }

    /** The holders moved or looked at, held weakly. Main thread only. */
    private static final WeakHashMap<View, Held> HELD = new WeakHashMap<>();
    private static final int[] AT = new int[2];

    private ReelSeekRoom() {
    }

    /** How far up a bar whose bottom is at [bottom] goes to clear a [strip] at the foot of a window [window] tall. */
    static int liftFor(int bottom, int window, int strip) {
        if (window <= 0 || strip <= 0) return 0;
        return Math.max(0, Math.min(strip, bottom - (window - strip)));
    }

    /** Called with every change of a reel seek bar's position. Never throws. */
    static void keep(View bar) {
        try {
            if (bar == null || !(bar.getParent() instanceof View)) return;
            View holder = lifted((View) bar.getParent(), bar.getRootView().getHeight());
            if (!due(holder, SystemClock.uptimeMillis())) return;
            boolean ready = Utils.settingsReady() && bar.isShown();
            int bottom = 0, window = 0, strip = 0;
            if (ready) {
                bar.getLocationInWindow(AT);
                bottom = AT[1] + bar.getHeight();
                window = bar.getRootView().getHeight();
                strip = strip(bar);
            }
            keep(holder, ready, bottom, window, strip);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, STEP, failure);
        }
    }

    /** The view to move for a bar in [holder] on a window [window] tall: the short row it sits in, or else itself. */
    static View lifted(View holder, int window) {
        if (!(holder.getParent() instanceof View)) return holder;
        View row = (View) holder.getParent();
        int height = row.getHeight();
        return height > 0 && height * ROW_SHARE <= window ? row : holder;
    }

    /** Whether it's time to measure this holder's bar again, and notes that it's being done now if so. */
    static boolean due(View holder, long now) {
        Held held = HELD.get(holder);
        if (held == null) {
            held = new Held();
            HELD.put(holder, held);
        } else if (now - held.lookedAt < LOOK_MS) {
            return false;
        }
        held.lookedAt = now;
        return true;
    }

    /** With [ready] false anything lifted goes back. [bottom] is the bar's bottom in the window as it is now. */
    static void keep(View holder, boolean ready, int bottom, int window, int strip) {
        Held held = HELD.get(holder);
        if (held == null) return;
        if (!ready) {
            if (held.lift != 0) move(holder, held, 0);
            return;
        }
        // Instagram laid the holder out again and took the lift away with it.
        if (held.lift != 0 && holder.getTranslationY() != held.wrote) held.lift = 0;
        move(holder, held, liftFor(bottom + held.lift, window, strip));
    }

    private static void move(View holder, Held held, int lift) {
        if (lift == held.lift) return;
        held.wrote = holder.getTranslationY() + held.lift - lift;
        holder.setTranslationY(held.wrote);
        held.lift = lift;
    }

    /**
     * How tall the strip at the window's foot is: the navigation bar at its full height whether it shows or
     * not, or the area the system keeps for its gestures where that's taller, as with the gesture hint hidden.
     */
    private static int strip(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Math.max(insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures()).bottom);
        }
        return insets.getStableInsetBottom();
    }
}
