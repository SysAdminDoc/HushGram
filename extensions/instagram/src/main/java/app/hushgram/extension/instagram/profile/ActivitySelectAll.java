/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Select all in Your activity (#762).
 *
 * <p>Your activity's photo and video grids (Posts, Reels, Likes and the rest) are Bloks screens. Tap
 * Select and each tile turns into a checkbox that Bloks describes as checkable, but Instagram has no way
 * to tick them all. Once a tile of a grid like that is ticked and Instagram's button under the grid
 * counts it ("Unlike (1)"), a Select all sits in the top bar beside Instagram's own Select. It ticks the
 * rest the way a tap does, scrolling the grid on to the end so the tiles Instagram loads further down
 * get ticked too. It only ticks: nothing is deleted or unliked until you press Instagram's own button.
 *
 * <p>Instagram keeps that count as a running number that each tap adds to, read when the tap lands and
 * written a moment later. Taps closer together than that lose their additions, and the count stays wrong
 * for as long as the screen is open, so the button could act on a different set than the ticks show
 * (seen on 457 with three ticks in one go: "Unlike (1)"). So a pass ticks one tile at a time and waits
 * for the tile to show ticked and the count to go up by exactly one before the next. When that doesn't
 * happen in time, Instagram turned the tick down or didn't count it, and the pass stops. Without a count
 * to check against there's no button.
 *
 * <p>A grid here is two or more checkable tiles side by side, so lists of radio buttons and the like on
 * other Bloks screens never get the button. The pass also stops at the end of the grid or when the grid
 * stops being a selection. Main thread.
 */
public final class ActivitySelectAll {
    static final String WHERE = "activity select all";
    static final String COUNT_TICKED = "tiles ticked";
    static final String BLOKS = "bloks_container";
    static final String ACTION_BAR = "action_bar";
    /** Tags the Select all button in the top bar. */
    static final String TAG = "hushgram_activity_select_all";

    /** How often a screen is looked at, at most. */
    static final long LOOK_EVERY_MS = 400;
    /** How long a pass waits for Instagram after a scroll, and between looks at the end of the grid. */
    static final long STEP_MS = 350;
    /** How often a pass looks whether Instagram took the last tick. */
    static final long TICK_MS = 150;
    /** How many looks a tick gets to show ticked and counted before the pass stops (1.5 seconds). */
    static final int TICK_WAITS = 10;
    /** How many waits at the end of the grid for Instagram to load more before the pass ends. */
    static final int IDLE_STEPS = 4;
    /** The most steps one pass takes, whatever the grid does. */
    static final int MAX_STEPS = 20_000;
    /** The full-width brackets Chinese and Japanese labels put a count in. */
    static final char WIDE_OPEN = (char) 0xFF08;
    static final char WIDE_CLOSE = (char) 0xFF09;

    /** Instagram's view ids for the Bloks screen and the top bar, looked up once. */
    static volatile int bloksId;
    static volatile int actionBarId;

    private static boolean registered;
    private static final WeakHashMap<Activity, Boolean> watched = new WeakHashMap<>();

    /** The pass going now, if any. */
    @Nullable static Pass running;

    private ActivitySelectAll() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.activitySelectAll()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACTIVITY_SELECT_ALL, WHERE, failure);
        }
    }

    static boolean on() {
        return Utils.settingsReady() && Settings.ACTIVITY_SELECT_ALL.get();
    }

    static void watch(Activity activity) {
        try {
            synchronized (watched) {
                if (watched.containsKey(activity)) return;
                watched.put(activity, Boolean.TRUE);
            }
            View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                private long last;
                private boolean pending;

                @Override public void onGlobalLayout() {
                    long now = SystemClock.uptimeMillis();
                    if (now - last < LOOK_EVERY_MS) {
                        // The last layout of a burst is the one that matters, so look once more after it.
                        if (!pending) {
                            pending = true;
                            decor.postDelayed(() -> {
                                pending = false;
                                last = SystemClock.uptimeMillis();
                                look(decor);
                            }, LOOK_EVERY_MS);
                        }
                        return;
                    }
                    last = now;
                    look(decor);
                }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACTIVITY_SELECT_ALL, WHERE, failure);
        }
    }

    /**
     * Puts Select all in the top bar while [root] shows a grid of checkable tiles and Instagram's count of
     * the ticked ones, with the switch on, and takes it out otherwise.
     */
    static void look(View root) {
        try {
            ViewGroup bar = actionBar(root);
            if (bar == null) return;
            View shown = bar.findViewWithTag(TAG);
            TextView anchor = anchor(bar);
            boolean wanted = false;
            if (on() && anchor != null) {
                List<View> tiles = tiles(root);
                wanted = isGrid(tiles) && count(root, tiles) != null;
            }
            if (wanted && shown == null) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                bar.addView(button(bar.getContext(), anchor), bar.indexOfChild(anchor), params);
                HookStatus.invoked(FamilyNames.ACTIVITY_SELECT_ALL);
            } else if (!wanted && shown != null) {
                bar.removeView(shown);
                Pass pass = running;
                if (pass != null && pass.root == root.getRootView()) pass.finish();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACTIVITY_SELECT_ALL, WHERE, failure);
        }
    }

    /** Instagram's top bar under [root], or null when the screen has none. */
    @Nullable
    static ViewGroup actionBar(View root) {
        if (actionBarId == 0) actionBarId = id(root.getContext(), ACTION_BAR);
        View bar = actionBarId == 0 ? null : root.findViewById(actionBarId);
        return bar instanceof LinearLayout && bar.isShown() ? (ViewGroup) bar : null;
    }

    /**
     * The text button Instagram put at the end of the top bar (Select on Your activity's grids), which
     * Select all goes in front of and takes its look from. Null when the bar has none.
     */
    @Nullable
    static TextView anchor(ViewGroup bar) {
        for (int i = bar.getChildCount() - 1; i >= 0; i--) {
            View child = bar.getChildAt(i);
            if (!(child instanceof TextView) || TAG.equals(child.getTag())) continue;
            if (child.getVisibility() != View.VISIBLE) continue;
            CharSequence text = ((TextView) child).getText();
            if (text != null && text.length() > 0) return (TextView) child;
        }
        return null;
    }

    static TextView button(Context context, TextView anchor) {
        TextView button = new TextView(context);
        button.setTag(TAG);
        button.setText(L10n.t("Select all"));
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.getTextSize());
        button.setTypeface(anchor.getTypeface());
        button.setTextColor(anchor.getTextColors());
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(anchor.getPaddingLeft(), 0, anchor.getPaddingRight(), 0);
        button.setOnClickListener(ActivitySelectAll::pressed);
        return button;
    }

    /** Select all was tapped: starts a pass over the grid, unless one is going. */
    static void pressed(View button) {
        try {
            if (running != null) return;
            Pass pass = new Pass(button.getRootView());
            running = pass;
            pass.run();
        } catch (Throwable failure) {
            running = null;
            HookStatus.threw(FamilyNames.ACTIVITY_SELECT_ALL, WHERE, failure);
        }
    }

    /** Every checkable tile in the Bloks screens under [root], outermost first. */
    static List<View> tiles(View root) {
        if (bloksId == 0) bloksId = id(root.getContext(), BLOKS);
        if (bloksId == 0) return Collections.emptyList();
        List<View> containers = new ArrayList<>();
        collect(root, bloksId, containers);
        List<View> out = new ArrayList<>();
        for (View container : containers) collectTiles(container, out);
        return out;
    }

    private static void collect(View view, int id, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.getId() == id) {
            out.add(view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0, n = group.getChildCount(); i < n; i++) collect(group.getChildAt(i), id, out);
    }

    private static void collectTiles(View view, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.isClickable() && checked(view) != null) {
            out.add(view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0, n = group.getChildCount(); i < n; i++) collectTiles(group.getChildAt(i), out);
    }

    /** Whether a tile is ticked, from what it tells accessibility; null when it isn't checkable. */
    @Nullable
    @SuppressWarnings("deprecation")
    static Boolean checked(View view) {
        AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
        try {
            return info.isCheckable() ? info.isChecked() : null;
        } finally {
            info.recycle();
        }
    }

    /**
     * Instagram's own count of the ticked tiles: the number its button under the grid shows in brackets,
     * "Unlike (3)" or "Delete (3)". Null when no such count shows (nothing ticked yet, or a screen that
     * doesn't count), or when the Bloks screen shows more than one different number like that.
     */
    @Nullable
    static Integer count(View root, List<View> tiles) {
        if (bloksId == 0) bloksId = id(root.getContext(), BLOKS);
        if (bloksId == 0) return null;
        List<View> containers = new ArrayList<>();
        collect(root, bloksId, containers);
        Integer[] found = new Integer[1];
        boolean[] mixed = new boolean[1];
        for (View container : containers) collectCount(container, tiles, found, mixed);
        return mixed[0] ? null : found[0];
    }

    @SuppressWarnings("deprecation")
    private static void collectCount(View view, List<View> tiles, Integer[] found, boolean[] mixed) {
        if (mixed[0] || view.getVisibility() != View.VISIBLE || tiles.contains(view)) return;
        AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
        Integer here;
        try {
            here = bracketed(info.getText());
            if (here == null) here = bracketed(info.getContentDescription());
        } finally {
            info.recycle();
        }
        if (here != null) {
            if (found[0] != null && !found[0].equals(here)) {
                mixed[0] = true;
                return;
            }
            found[0] = here;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0, n = group.getChildCount(); i < n; i++) collectCount(group.getChildAt(i), tiles, found, mixed);
    }

    /**
     * The number at the end of a label like "Unlike (3)", in any digits and in plain or full-width
     * brackets, or null when [text] isn't a word followed by a bracketed number.
     */
    @Nullable
    static Integer bracketed(@Nullable CharSequence text) {
        if (text == null) return null;
        String label = text.toString().trim();
        int end = label.length() - 1;
        if (end < 3) return null;
        char close = label.charAt(end);
        if (close != ')' && close != WIDE_CLOSE) return null;
        int open = Math.max(label.lastIndexOf('('), label.lastIndexOf(WIDE_OPEN));
        if (open < 1 || open >= end - 1 || label.substring(0, open).trim().isEmpty()) return null;
        int value = 0;
        for (int i = open + 1; i < end; i++) {
            int digit = Character.digit(label.charAt(i), 10);
            if (digit < 0 || value > 100_000) return null;
            value = value * 10 + digit;
        }
        return value;
    }

    /** Whether [tiles] make a grid: at least two of them side by side in one row. */
    static boolean isGrid(List<View> tiles) {
        int[] at = new int[2];
        List<int[]> seen = new ArrayList<>();
        for (View tile : tiles) {
            tile.getLocationInWindow(at);
            for (int[] other : seen) {
                if (other[1] == at[1] && other[0] != at[0]) return true;
            }
            seen.add(new int[]{at[0], at[1]});
        }
        return false;
    }

    /** The scrolling view [tile] sits in, below [root], or null when the grid doesn't scroll. */
    @Nullable
    static View scroller(View tile, View root) {
        for (ViewParent parent = tile.getParent(); parent instanceof View; parent = parent.getParent()) {
            View view = (View) parent;
            if (view.canScrollVertically(1) || view.canScrollVertically(-1)) return view;
            if (view == root) break;
        }
        return null;
    }

    private static int id(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", Utils.resourcePackage(context));
    }

    /**
     * One Select all: ticks the first unticked tile, waits until Instagram shows it ticked and counts it,
     * then the next, scrolling the grid on when every tile in view is ticked, until the grid ends.
     */
    static final class Pass implements Runnable {
        final View root;
        /** The tile ticked last, until Instagram shows it ticked and counted. */
        @Nullable private View waiting;
        /** Instagram's count once the tick on [waiting] lands. */
        private int expected;
        private int waits;
        private int steps;
        private int idle;
        int ticked;
        boolean done;

        Pass(View root) {
            this.root = root;
        }

        @Override public void run() {
            try {
                if (done) return;
                if (running != this || !root.isAttachedToWindow() || !on() || ++steps > MAX_STEPS) {
                    finish();
                    return;
                }
                List<View> tiles = tiles(root);
                if (!isGrid(tiles)) {
                    // Instagram left selection, or the screen went.
                    finish();
                    return;
                }
                Integer count = count(root, tiles);
                if (waiting != null) {
                    if (count != null && count == expected && Boolean.TRUE.equals(checked(waiting))) {
                        ticked++;
                        waiting = null;
                    } else if (++waits < TICK_WAITS) {
                        root.postDelayed(this, TICK_MS);
                        return;
                    } else {
                        // Instagram turned the tick down (it allows no more) or didn't count it. Stop
                        // while its count still matches what the pass did.
                        finish();
                        return;
                    }
                }
                if (count == null) {
                    // Nothing to check the next tick against.
                    finish();
                    return;
                }
                for (View tile : tiles) {
                    if (Boolean.FALSE.equals(checked(tile))) {
                        waiting = tile;
                        expected = count + 1;
                        waits = 0;
                        idle = 0;
                        tile.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
                        root.postDelayed(this, TICK_MS);
                        return;
                    }
                }
                // Every tile in view is ticked: on down the grid.
                View scroller = scroller(tiles.get(tiles.size() - 1), root);
                if (scroller != null && scroller.canScrollVertically(1)) {
                    idle = 0;
                    int row = tiles.get(0).getHeight();
                    scroller.scrollBy(0, Math.max(row, scroller.getHeight() - row));
                    root.postDelayed(this, STEP_MS);
                    return;
                }
                if (++idle < IDLE_STEPS) {
                    // At the end for now; Instagram may still be loading more.
                    root.postDelayed(this, STEP_MS);
                    return;
                }
                finish();
            } catch (Throwable failure) {
                finish();
                HookStatus.threw(FamilyNames.ACTIVITY_SELECT_ALL, WHERE, failure);
            }
        }

        void finish() {
            if (done) return;
            done = true;
            if (running == this) running = null;
            root.removeCallbacks(this);
            if (ticked > 0) HookStatus.counted(FamilyNames.ACTIVITY_SELECT_ALL, COUNT_TICKED);
            Logger.printDebug(() -> "Select all: ticked " + ticked + " tiles in " + steps + " steps");
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            watch(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
