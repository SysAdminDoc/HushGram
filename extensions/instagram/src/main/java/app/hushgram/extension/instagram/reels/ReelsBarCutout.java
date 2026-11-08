/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Part of Full screen Reels (#525): while HushGram has the status bar hidden, a reels viewer's top
 * bar ({@code ClipsViewerActionBar}, with the Reels title, the Friends tab and the buttons) moves up
 * to the top edge, where a punch hole camera sits on top of it. Each bar whose row would start
 * inside the camera cutout's band is lowered just below it.
 *
 * <p>It's lowered the way Instagram lowers it on its own edge to edge screens: padding on top, and a
 * bar of fixed height made that much taller. Its background still runs to the top edge, the reel
 * under it stays full screen, and the title, tabs and buttons Instagram slides away over a reel still
 * slide off the top. A bar that already clears the cutout, or a phone without one, is left alone.
 *
 * <p>When the status bar comes back, the bar gets its own padding and height back, if nothing else
 * changed them since. Padding Instagram gives the bar itself while lowered (when its own insets
 * change) is taken as the bar's padding from then on. The bars are searched for after a layout only,
 * at most as often as {@link FullScreenBars} looks at the screen. Anything thrown leaves the bar as
 * Instagram has it.
 */
final class ReelsBarCutout implements ViewTreeObserver.OnGlobalLayoutListener {
    static final String COUNT_LOWERED = "Reels top bar lowered below the camera cutout";
    /** The most views looked at in one search, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = 4000;

    /** The padding and height a bar had before HushGram lowered it by {@code by}. */
    private static final class Lowered {
        final int padding;
        final int height;
        final int by;

        Lowered(int padding, int height, int by) {
            this.padding = padding;
            this.height = height;
            this.by = by;
        }

        /** Whether [bar] still has the padding and height HushGram gave it. */
        boolean kept(View bar) {
            if (bar.getPaddingTop() != padding + by) return false;
            ViewGroup.LayoutParams params = bar.getLayoutParams();
            return height <= 0 || (params != null && params.height == height + by);
        }
    }

    /** Held weakly so a closed viewer's bar can go. Main thread only. */
    private final WeakHashMap<View, Lowered> lowered = new WeakHashMap<>();
    private WeakReference<View> watched = new WeakReference<>(null);
    private boolean dirty = true;

    /** How far below the window's top edge the camera cutout reaches, 0 for none or before Android 9. */
    static int safeTop(View decor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0;
        WindowInsets insets = decor.getRootWindowInsets();
        DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
        return cutout == null ? 0 : Math.max(0, cutout.getSafeInsetTop());
    }

    @Override public void onGlobalLayout() {
        // A new bar, or one Instagram padded again, only ever comes with a layout.
        dirty = true;
    }

    /**
     * Each look at [decor]'s screen: with the status bar hidden ([statusHidden]) and a cutout
     * [safeTop] tall, lowers the bars that start inside it; otherwise puts back every bar it lowered.
     */
    void follow(View decor, boolean statusHidden, int safeTop) {
        try {
            if (!statusHidden || safeTop <= 0) {
                putBack();
                return;
            }
            watch(decor);
            if (!dirty) return;
            dirty = false;
            for (View bar : bars(decor)) lower(bar, decor, safeTop);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "top bar cutout", failure);
        }
    }

    /** Gives each bar HushGram lowered its own padding and height back, and stops listening. */
    void putBack() {
        try {
            unwatch();
            for (Map.Entry<View, Lowered> entry : lowered.entrySet()) {
                View bar = entry.getKey();
                if (bar != null && entry.getValue().kept(bar)) set(bar, entry.getValue().padding, entry.getValue().height);
            }
            lowered.clear();
            dirty = true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "top bar cutout put back", failure);
        }
    }

    /** How many bars are lowered now, for tests. */
    int loweredCount() {
        return lowered.size();
    }

    private void watch(View decor) {
        if (watched.get() == decor) return;
        unwatch();
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.addOnGlobalLayoutListener(this);
        watched = new WeakReference<>(decor);
        dirty = true;
    }

    private void unwatch() {
        View decor = watched.get();
        watched = new WeakReference<>(null);
        if (decor == null) return;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
    }

    /** The reels viewers' top bars in [root], past what's gone, by the class or the id Instagram gives them. */
    static List<View> bars(View root) {
        List<View> found = new ArrayList<>();
        int barId = FullScreenBars.id(root.getContext(), ReelsTopBar.BAR_ID);
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int seen = 0;
        while (!pending.isEmpty() && seen++ < MAX_VIEWS) {
            View view = pending.poll();
            if (view.getVisibility() == View.GONE) continue;
            if (ReelsTopBar.isBarClass(view) || (barId != 0 && view.getId() == barId)) {
                found.add(view);
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
        }
        return found;
    }

    /**
     * Where [view] is laid out below [decor]'s top, without the translations Instagram slides it with
     * or a burn-in shift moves the screen by, so neither makes the bar move again.
     */
    static int layoutTop(View view, View decor) {
        int top = 0;
        View at = view;
        while (at != null && at != decor) {
            top += at.getTop();
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return top;
    }

    private void lower(View bar, View decor, int safeTop) {
        Lowered was = lowered.get(bar);
        if (was != null && !was.kept(bar)) {
            // Instagram padded or sized the bar itself since: that's the bar's own from now on.
            lowered.remove(bar);
            was = null;
        }
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        int padding = was == null ? bar.getPaddingTop() : was.padding;
        int height = was == null ? (params == null ? 0 : params.height) : was.height;
        int by = Math.max(0, safeTop - layoutTop(bar, decor) - padding);
        if (was != null && was.by == by) return;
        if (by == 0) {
            if (was != null) set(bar, padding, height);
            lowered.remove(bar);
            return;
        }
        set(bar, padding + by, height > 0 ? height + by : height);
        lowered.put(bar, new Lowered(padding, height, by));
        HookStatus.invoked(FamilyNames.FULL_SCREEN_BARS);
        HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_LOWERED);
    }

    private static void set(View bar, int padding, int height) {
        bar.setPaddingRelative(bar.getPaddingStart(), padding, bar.getPaddingEnd(), bar.getPaddingBottom());
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params != null && height > 0 && params.height != height) {
            params.height = height;
            bar.setLayoutParams(params);
        }
    }
}
