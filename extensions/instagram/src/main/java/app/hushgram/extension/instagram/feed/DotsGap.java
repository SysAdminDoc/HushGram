/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Closes the band Instagram leaves over a carousel post's buttons for its page dots, while Page dots
 * on a pill stands in for them (#527).
 *
 * <p>In 454 a carousel's button row is 20 dp taller than a single post's: the dots sit in a band along
 * its top and the buttons start under it. With the dots faded under the pill that band is an empty gap
 * between the photo and the buttons. The row is its own item in the feed's list, so it's pulled up by
 * the band with a negative top margin, and the list lays the rest of the feed out after it as usual.
 * The dots, faded out, then sit over the photo's bottom edge, where they take no taps.
 *
 * <p>A row keeps its lift while it's in the window, even when Instagram takes its dots out as their
 * band scrolls past the top. It gets its own margin back when the list lets go of it, when it's seen
 * holding another post with no dots, when the switch goes off or HushGram is paused. Main thread only.
 */
final class DotsGap {
    static final String COUNT_CLOSED = "carousel dot gaps closed";
    /** How far up from the dots their list row is looked for. */
    static final int ROW_DEPTH = 6;
    /** How deep into a row its dots are looked for. */
    static final int DOTS_DEPTH = 4;
    /** The most a row is pulled up, in dp. Instagram's band is 20. */
    static final float MOST_LIFT = 40;

    /** Each row pulled up, with the top margin it had. Weak, so a row the list drops goes with it. */
    private static final Map<View, Integer> lifted = new WeakHashMap<>();
    private static final Restorer RESTORER = new Restorer();
    /** The id the last dots closed carried, so a row's dots are known by it as well as by their class. */
    private static int dotsId;

    private DotsGap() {
    }

    /** Pulls the list row holding [dots] up by the band over its buttons, if it isn't already. */
    static void close(View dots) {
        if (dots.getId() != View.NO_ID) dotsId = dots.getId();
        ViewParent up = dots.getParent();
        if (!(up instanceof ViewGroup)) return;
        int band = band((ViewGroup) up, dots);
        float most = MOST_LIFT * dots.getResources().getDisplayMetrics().density;
        if (band <= 0 || band > most) return;
        View row = rowOf(dots);
        if (row == null || !(row.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        Integer before = lifted.get(row);
        int stock = before != null ? before : margins.topMargin;
        if (margins.topMargin == stock - band) return;
        if (before == null) {
            lifted.put(row, stock);
            row.addOnAttachStateChangeListener(RESTORER);
            HookStatus.counted(FamilyNames.CAROUSEL_ARROWS, COUNT_CLOSED);
        }
        margins.topMargin = stock - band;
        row.setLayoutParams(margins);
    }

    /**
     * The empty band over the buttons in [row]: how far down the highest of the views beside [dots]
     * starts, no further than the dots reach. Zero when something starts at the top.
     */
    static int band(ViewGroup row, View dots) {
        int top = Integer.MAX_VALUE;
        for (int i = 0, n = row.getChildCount(); i < n; i++) {
            View child = row.getChildAt(i);
            if (child == dots || child.getVisibility() != View.VISIBLE || child.getHeight() <= 0) continue;
            top = Math.min(top, child.getTop());
        }
        if (top == Integer.MAX_VALUE) return 0;
        return Math.max(0, Math.min(top, dots.getBottom()));
    }

    /** The view holding [dots] that is an item of a list, or null when there's none close enough. */
    @Nullable
    static View rowOf(View dots) {
        View at = dots;
        for (int depth = 0; depth < ROW_DEPTH; depth++) {
            ViewParent up = at.getParent();
            if (!(up instanceof ViewGroup)) return null;
            if (GlassReach.isRecycler((View) up)) return at;
            at = (View) up;
        }
        return null;
    }

    /**
     * Gives back the margin of each row that no longer needs its lift: all of them with the switch off
     * or HushGram paused, and one whose top is in its list and holds no dots, so it's another post now.
     * Above the list's top Instagram takes dots out on its own, so a row there keeps its lift.
     */
    static void check() {
        if (lifted.isEmpty()) return;
        boolean wanted = Utils.settingsReady() && Settings.CAROUSEL_DOTS_PILL.get();
        for (View row : new ArrayList<>(lifted.keySet())) {
            if (row == null) continue;
            if (!wanted || (inList(row) && !holdsDots(row, 0))) open(row, true);
        }
    }

    private static boolean inList(View row) {
        ViewParent up = row.getParent();
        return up instanceof View && row.getTop() >= 0 && row.getTop() < ((View) up).getHeight();
    }

    /** Gives every lifted row its own margin back. */
    static void openAll() {
        for (View row : new ArrayList<>(lifted.keySet())) {
            if (row != null) open(row, true);
        }
        lifted.clear();
    }

    private static void open(View row, boolean layout) {
        Integer stock = lifted.remove(row);
        row.removeOnAttachStateChangeListener(RESTORER);
        if (stock == null || !(row.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        if (margins.topMargin == stock) return;
        margins.topMargin = stock;
        // A row the list let go of is measured again when it comes back, so it needs no layout now.
        if (layout) row.setLayoutParams(margins);
    }

    private static boolean holdsDots(View view, int depth) {
        if (CarouselArrows.isDots(view, dotsId)) return true;
        if (!(view instanceof ViewGroup) || depth >= DOTS_DEPTH) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            if (holdsDots(group.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    static boolean liftedForTests(View row) {
        return lifted.containsKey(row);
    }

    static void resetForTests() {
        lifted.clear();
        dotsId = 0;
    }

    /** Gives a row its margin back as the list lets go of it, before it holds another post. */
    private static final class Restorer implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            try {
                open(view, false);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CAROUSEL_ARROWS, CarouselArrows.HOOK + " dot gap", failure);
            }
        }
    }
}
