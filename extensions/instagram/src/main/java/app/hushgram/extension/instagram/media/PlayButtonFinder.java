/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;

/**
 * The play button of the feed video under a tap.
 *
 * <p>A tap on a feed video itself, rather than on its play button, starts it through Instagram's
 * autoplay, so the button's click never runs and {@link PlayButtons} never hears of it: the video
 * played under its play button (#229). Instagram 451 mounts that button with Litho as a clickable
 * image view, square, in the middle of the video's frame, inside the post's {@code video_container},
 * next to a larger empty host that isn't clickable and draws nothing, with the video's sound button
 * and overlays around them. This finds it from where the tap landed: the outermost
 * {@code video_container} under the tap, then the clickable square view with no children in it whose
 * middle sits in the middle of a parent the tap also landed in, so a carousel gives the page that was
 * tapped. Nothing found means nothing is hidden.
 */
final class PlayButtonFinder {
    /** How far a button's middle may sit from its parent's middle, as a share of the parent's size. */
    static final float OFF_MIDDLE = 0.1f;

    /** How much a button's width and height may differ, as a share of the larger. */
    static final float OFF_SQUARE = 0.1f;

    private static volatile int containerId;

    private PlayButtonFinder() { }

    /** The button under the tap at ([x], [y]) in [window]'s pixels, or null. */
    @Nullable
    static View find(View window, float x, float y) {
        int id = containerId;
        if (id == 0) {
            id = window.getResources().getIdentifier("video_container", "id", window.getContext().getPackageName());
            containerId = id;
        }
        return find(window, x, y, id);
    }

    /** The button under the tap at ([x], [y]), inside a view with the id [container]. */
    @Nullable
    static View find(View window, float x, float y, int container) {
        if (container == 0) return null;
        View frame = outermost(window, container, x, y);
        if (frame == null) return null;
        View best = null;
        float bestOff = Float.MAX_VALUE;
        ArrayDeque<View> todo = new ArrayDeque<>();
        todo.push(frame);
        while (!todo.isEmpty()) {
            View view = todo.pop();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) todo.push(group.getChildAt(i));
                continue;
            }
            if (view == frame || !view.isClickable()) continue;
            float off = offMiddle(view, x, y);
            if (off >= 0 && off < bestOff) {
                best = view;
                bestOff = off;
            }
        }
        return best;
    }

    /** The first shown view with the id [container] under ([x], [y]), looking from [window] down. */
    @Nullable
    private static View outermost(View window, int container, float x, float y) {
        ArrayDeque<View> todo = new ArrayDeque<>();
        todo.push(window);
        while (!todo.isEmpty()) {
            View view = todo.pop();
            if (view.getVisibility() != View.VISIBLE || !contains(view, x, y)) continue;
            if (view.getId() == container) return view.isShown() ? view : null;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) todo.push(group.getChildAt(i));
            }
        }
        return null;
    }

    /**
     * How far [button]'s middle is from its parent's, as a share of the parent's size, or -1 when
     * it isn't a play button's shape and place: square, between an eighth and half its parent's
     * width, in the parent's middle, and the tap at ([x], [y]) inside that parent.
     */
    static float offMiddle(View button, float x, float y) {
        ViewParent parentOf = button.getParent();
        if (!(parentOf instanceof View)) return -1;
        View parent = (View) parentOf;
        int width = button.getWidth();
        int height = button.getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        if (width <= 0 || height <= 0 || parentWidth <= 0 || parentHeight <= 0) return -1;
        if (Math.abs(width - height) > OFF_SQUARE * Math.max(width, height)) return -1;
        if (width * 8 < parentWidth || width * 2 > parentWidth || height * 2 > parentHeight) return -1;
        if (!contains(parent, x, y)) return -1;
        float offX = Math.abs(button.getLeft() + width / 2f - parentWidth / 2f) / parentWidth;
        float offY = Math.abs(button.getTop() + height / 2f - parentHeight / 2f) / parentHeight;
        if (offX > OFF_MIDDLE || offY > OFF_MIDDLE) return -1;
        return Math.max(offX, offY);
    }

    private static boolean contains(View view, float x, float y) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
    }

    /** Uses [id] for {@code video_container}, or looks it up again for 0. For tests. */
    static void useContainerId(int id) {
        containerId = id;
    }
}
