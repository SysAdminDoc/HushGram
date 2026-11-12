/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Takes the See-through top bar behind the status bar (#462). Instagram lays Home out below the
 * status bar: a parent at the window's top moves the panel holding Home down by the status bar's
 * height, so nothing scrolls behind the clock and the glass stopped at the header's top edge.
 *
 * <p>While the glass is on, the feed views drawn before the header are pulled up by that gap, the list
 * in each is padded by it so the first post starts where it did, and every parent from the header's up
 * to the one at the window's top stops clipping. The posts then run on behind the status bar and the
 * glass reaches up over them. Nothing moves unless the gap is exactly the status bar's height, and
 * everything is put back when the glass comes off. Main thread only.
 */
final class GlassReach {
    /** How many parents up from the header's the one at the window's top is looked for. */
    private static final int DEPTH = 16;
    /** How deep into a feed view the list it scrolls is looked for. */
    private static final int LIST_DEPTH = 4;

    /** The status bar's height over [view]'s window. Swapped in tests. */
    static ToIntFunction<View> insets = GlassReach::statusBar;

    /** The parents told not to clip, with whether they clipped their children and their padding. */
    private final Map<ViewGroup, boolean[]> unclipped = new HashMap<>();
    /** The feed views pulled up, with their own top margin. */
    private final Map<View, Integer> pulled = new HashMap<>();
    /** The lists padded, with their own top padding and whether they clipped to it. */
    private final Map<View, Object[]> padded = new HashMap<>();
    /** The parent at the window's top, while the feed reaches it. */
    @Nullable private View frame;
    /** How far the feed was pulled up, 0 while it isn't. */
    int gap;

    static int statusBar(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        return insets == null ? 0 : insets.getSystemWindowInsetTop();
    }

    /**
     * Pulls the views [source] draws before its child at [index] up behind the status bar, once. Says
     * how far, 0 when Home isn't laid out below the status bar or nothing could be pulled.
     */
    int reach(ViewGroup source, int index) {
        if (gap > 0) return gap;
        int status = insets.applyAsInt(source);
        if (status <= 0 || index <= 0) return 0;
        View top = frameOf(source, status);
        if (top == null) return 0;
        List<View> feeds = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            View child = source.getChildAt(i);
            if (child.getTop() == 0 && child.getHeight() == source.getHeight() && child.getHeight() > 0
                    && child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) feeds.add(child);
        }
        if (feeds.isEmpty()) return 0;
        for (View at = source; ; at = (View) at.getParent()) {
            unclip((ViewGroup) at);
            if (at == top) break;
        }
        for (View feed : feeds) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) feed.getLayoutParams();
            pulled.put(feed, params.topMargin);
            params.topMargin -= status;
            feed.setLayoutParams(params);
            View list = listIn(feed);
            if (list != null) pad(list, status);
        }
        frame = top;
        gap = status;
        return gap;
    }

    /**
     * The parent of [source] at the window's top, when [source] starts exactly [status] below it and
     * every view between them is laid out from their parent's top. Null otherwise.
     */
    @Nullable
    private static View frameOf(ViewGroup source, int status) {
        int[] at = new int[2];
        source.getLocationInWindow(at);
        if (at[1] != status) return null;
        View view = source;
        for (int depth = 0; depth < DEPTH; depth++) {
            ViewParent up = view.getParent();
            if (!(up instanceof ViewGroup)) return null;
            view = (View) up;
            view.getLocationInWindow(at);
            if (at[1] == 0) return view;
            if (at[1] != status) return null;
        }
        return null;
    }

    /** The list [feed] scrolls: the deepest view as big as it with children of its own. */
    @Nullable
    private static View listIn(View feed) {
        View found = null;
        View at = feed;
        for (int depth = 0; depth < LIST_DEPTH && at instanceof ViewGroup; depth++) {
            ViewGroup group = (ViewGroup) at;
            View next = null;
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                View child = group.getChildAt(i);
                if (child.getTop() == 0 && child.getHeight() == feed.getHeight() && child.getWidth() == feed.getWidth()
                        && child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                    next = child;
                    break;
                }
            }
            if (next == null) break;
            found = next;
            at = next;
        }
        return found;
    }

    private void unclip(ViewGroup group) {
        if (unclipped.containsKey(group)) return;
        unclipped.put(group, new boolean[] {group.getClipChildren(), group.getClipToPadding()});
        group.setClipChildren(false);
        if (group.getPaddingTop() > 0) group.setClipToPadding(false);
    }

    private void pad(View list, int status) {
        boolean clips = !(list instanceof ViewGroup) || ((ViewGroup) list).getClipToPadding();
        padded.put(list, new Object[] {list.getPaddingTop(), clips});
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop() + status, list.getPaddingRight(), list.getPaddingBottom());
        if (list instanceof ViewGroup) ((ViewGroup) list).setClipToPadding(false);
    }

    /** How far [header]'s top is below the window's top, through the parent at the window's top. */
    int headerTop(View header) {
        View top = frame;
        float y = 0f;
        View at = header;
        for (int depth = 0; top != null && at != top && depth <= DEPTH; depth++) {
            y += at.getY();
            ViewParent up = at.getParent();
            if (!(up instanceof View)) return Integer.MIN_VALUE;
            at = (View) up;
            if (at != top) y -= at.getScrollY();
        }
        return at == top ? Math.round(y) : Integer.MIN_VALUE;
    }

    /** Puts everything back as Instagram had it. */
    void retreat() {
        for (Map.Entry<View, Integer> entry : pulled.entrySet()) {
            View feed = entry.getKey();
            if (!(feed.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) feed.getLayoutParams();
            params.topMargin = entry.getValue();
            feed.setLayoutParams(params);
        }
        for (Map.Entry<View, Object[]> entry : padded.entrySet()) {
            View list = entry.getKey();
            list.setPadding(list.getPaddingLeft(), (Integer) entry.getValue()[0], list.getPaddingRight(), list.getPaddingBottom());
            if (list instanceof ViewGroup) ((ViewGroup) list).setClipToPadding((Boolean) entry.getValue()[1]);
        }
        for (Map.Entry<ViewGroup, boolean[]> entry : unclipped.entrySet()) {
            entry.getKey().setClipChildren(entry.getValue()[0]);
            entry.getKey().setClipToPadding(entry.getValue()[1]);
        }
        pulled.clear();
        padded.clear();
        unclipped.clear();
        frame = null;
        gap = 0;
    }
}
