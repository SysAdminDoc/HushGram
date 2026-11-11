/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;

import java.util.Collections;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.misc.GlassTabBar;
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
 * shows during a drag beside the holder, and left behind they'd end up under the raised bar (#567). The reel's
 * name, caption and buttons sit just over that row, so they go up as far ({@link #along}), or the bar would
 * land on the caption (#633). Instagram's touch strip for the bar comes too ({@link #touches}). Off when
 * HushGram is paused or the settings aren't ready, when anything lifted is put back.
 */
final class ReelSeekRoom {
    /** How often, at most, a playing bar is measured. */
    static final long LOOK_MS = 300;

    /** A parent over this share of the window is the page around the bar, not a row of its controls. */
    static final int ROW_SHARE = 4;

    /** The view holding a reel's name and caption. */
    static final String INFO = "clips_media_info_component";

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
    /** The scroll this gave each view that came along with a holder ({@link #along}). Main thread only. */
    private static final WeakHashMap<View, Integer> SCROLLED = new WeakHashMap<>();
    private static final int[] AT = new int[2];
    private static final Rect RECT = new Rect();

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
            along(holder, FullScreenBars.id(bar.getContext(), INFO));
            if (ready) {
                touches(bar, holder, FullScreenBars.id(bar.getContext(), GlassTabBar.TOUCH_STRIP),
                        dp(bar, GlassTabBar.STRIP_BELOW_DP), FullScreenBars.systemStripTop(bar));
            }
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
     * Raises each other child of [holder]'s parent that holds the reel's name and caption ([info]) as far as
     * the holder is up, so the raised bar keeps its place under them. Their content scrolls rather than the
     * views moving, as under the glass pill (#221): Litho puts a child it animates back where its layout says,
     * counting the translations of the views it's in but not their scrolls. It never scrolls past the highest
     * child's top, which would cut that child off. A view whose scroll is neither 0 nor the last one this gave
     * is someone else's and is left to them.
     */
    static void along(View holder, int info) {
        if (info == 0 || !(holder.getParent() instanceof ViewGroup)) return;
        Held held = HELD.get(holder);
        int lift = held == null ? 0 : held.lift;
        ViewGroup row = (ViewGroup) holder.getParent();
        for (int i = 0; i < row.getChildCount(); i++) {
            View band = row.getChildAt(i);
            if (band == holder || !(band instanceof ViewGroup) || band.findViewById(info) == null) continue;
            scroll(band, Math.min(lift, GlassTabBar.roomAbove((ViewGroup) band)));
        }
    }

    private static void scroll(View band, int by) {
        Integer ours = SCROLLED.get(band);
        int now = band.getScrollY();
        if (ours == null ? now != 0 : now != ours) {
            SCROLLED.remove(band);
            return;
        }
        if (by <= 0) {
            if (ours != null) {
                SCROLLED.remove(band);
                band.scrollTo(band.getScrollX(), 0);
            }
            return;
        }
        SCROLLED.put(band, by);
        if (now != by) band.scrollTo(band.getScrollX(), by);
    }

    /**
     * Keeps Instagram's touch strip for the seek bar ([stripId]) on [bar] while [holder] has it up, so a drag
     * along the bar where it now shows seeks (#633). Instagram measured the strip for the bar where it was, so
     * left there it took drags over the caption while one on the bar reached the reel. It runs [below] past
     * the bar, as over Instagram's own, but never past [floor] ({@link GlassTabBar#stripOnTheReel}), which
     * the glass pill keeps to as well, so the two never pull it apart.
     */
    static void touches(View bar, View holder, int stripId, int below, int floor) {
        Held held = HELD.get(holder);
        if (stripId == 0 || held == null || held.lift == 0) return;
        View strip = touchStrip(bar, stripId);
        if (strip != null) GlassTabBar.stripOnTheReel(strip, Collections.singletonList(bar), 0, below, floor, RECT);
    }

    /** Instagram's touch strip, [stripId]: a child of one of [bar]'s parents, beside the screens the reel is on. */
    private static View touchStrip(View bar, int stripId) {
        for (ViewParent parent = bar.getParent(); parent instanceof ViewGroup; parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getId() == stripId) return child;
            }
        }
        return null;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    /**
     * How tall the strip at the window's foot is: the navigation bar at its full height whether it shows or
     * not, or the area the system keeps for its gestures where that's taller ({@link #strip(int, int, int)}).
     */
    static int strip(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return strip(insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures()).bottom,
                    FullScreenBars.unreportedNavHeight());
        }
        return strip(insets.getStableInsetBottom(), 0, FullScreenBars.unreportedNavHeight());
    }

    /**
     * The strip from the navigation bar's height [nav] and the system's gesture area [gestures]: the taller.
     * A Samsung with its gesture hint hidden reports neither, though a swipe up from there still leaves the
     * app, and its curved corners cut off the ends of a bar on the edge (#633): then [unreported], the height
     * the bar is taken to have ({@link FullScreenBars#unreportedNavHeight}).
     */
    static int strip(int nav, int gestures, int unreported) {
        int measured = Math.max(nav, gestures);
        return measured > 0 ? measured : Math.max(0, unreported);
    }
}
