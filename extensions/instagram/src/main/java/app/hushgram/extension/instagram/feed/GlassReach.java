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

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Takes the See-through top bar behind the status bar (#462). Instagram lays Home out one of two ways.
 * Mostly a parent at the window's top moves the panel holding Home down by the status bar's height, so
 * nothing scrolls behind the clock and the glass stopped at the header's top edge. Some phones get Home's
 * feed running up behind the status bar already, with only the header below it, and there the glass
 * stopped at the header's top edge over the posts.
 *
 * <p>In the first, the view drawn before the header that holds Home's list is pulled up by that gap, the
 * list is padded by it so the first post starts where it did, and every parent from the header's up to
 * the one at the window's top stops clipping. The posts then run on behind the status bar and the glass
 * reaches up over them. In the second nothing moves, only the parents stop clipping so the glass gets
 * up there. Nothing moves unless the gap is exactly the status bar's height, and only for Home's own
 * list: another tab in the same panel or beside Home's page in the tab pager, Reels full screen or the
 * header gone puts everything back until Home is showing again, and so does the glass coming off. Main
 * thread only.
 */
final class GlassReach {
    /** How many parents up from the header's the one at the window's top is looked for. */
    private static final int DEPTH = 16;
    /** How deep into a feed view Home's list is looked for: through a tab pager, its page and the page's wrappers. */
    private static final int LIST_DEPTH = 8;
    /** Home's list, by the name it keeps in Instagram. */
    private static final String HOME_LIST = "MainFeedRecyclerView";

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
    /** Home's list and the panel holding the header, while the glass reaches up, and where that panel sat. */
    @Nullable private View home;
    @Nullable private View panel;
    private int panelTop;
    /** How far the glass reaches up behind the status bar, 0 while it doesn't. */
    int gap;

    static int statusBar(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        return insets == null ? 0 : insets.getSystemWindowInsetTop();
    }

    /**
     * Takes Home's feed in [source], drawn before its child at [index], up behind the status bar, or
     * finds Instagram already ran it up there, and keeps every parent from [header]'s up to the window's
     * top from clipping. A parent still clipping [header] would cut the glass off at its top edge, with
     * the feed running sharp behind the clock above it (#513). Says how far the glass reaches up, 0
     * while Home isn't showing under a header just below the status bar.
     */
    int reach(View header, ViewGroup source, int index) {
        if (gap > 0) {
            if (holds(header, source)) {
                // A parent can get its clip back, from Instagram or a layout it sets again.
                for (ViewGroup group : unclipped.keySet()) {
                    if (group.getClipChildren()) group.setClipChildren(false);
                }
                return gap;
            }
            // Laid out as it was pulled until the next frame, so Home is looked for again then.
            retreat();
            return 0;
        }
        // Home's page stays laid out beside the tab on screen in Instagram's tab pager: pulled up there, it
        // was let go on the next frame and pulled up again, redrawing the whole window every frame.
        if (!header.isShown() || !across(header)) return 0;
        int status = insets.applyAsInt(source);
        if (status <= 0 || index <= 0) return 0;
        View top = frameOf(source, status);
        if (top != null && pull(header, source, index, status, top)) return gap;
        top = alreadyBehind(header, source, index, status);
        if (top == null) return 0;
        unclipUpTo(header, top);
        hold(top, source, status);
        return gap;
    }

    /**
     * Pulls the view in [source] before [index] that holds Home's list up by [status] and pads the list
     * by it, with every parent from [header]'s up to [top] unclipped. False when none holds Home's list:
     * another tab is in the panel, or the tab pager holding Home isn't there yet.
     */
    private boolean pull(View header, ViewGroup source, int index, int status, View top) {
        for (int i = 0; i < index; i++) {
            View feed = source.getChildAt(i);
            if (feed.getTop() != 0 || feed.getHeight() != source.getHeight() || feed.getHeight() <= 0
                    || !(feed.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
            View list = homeIn(feed);
            if (list == null) continue;
            unclipUpTo(header, top);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) feed.getLayoutParams();
            pulled.put(feed, params.topMargin);
            params.topMargin -= status;
            feed.setLayoutParams(params);
            pad(list, status);
            hold(top, source, status);
            home = list;
            return true;
        }
        return false;
    }

    /**
     * The parent at the window's top, when Instagram itself runs Home's list, in a view [source] draws
     * before [index], up behind the status bar while [header] sits below it. Null otherwise.
     */
    @Nullable
    private View alreadyBehind(View header, ViewGroup source, int index, int status) {
        int[] at = new int[2];
        header.getLocationInWindow(at);
        int headerTop = at[1];
        if (headerTop <= 0 || headerTop > status) return null;
        View list = null;
        for (int i = 0; i < index && list == null; i++) {
            View feed = source.getChildAt(i);
            if (!feed.isShown() || feed.getWidth() != source.getWidth()) continue;
            feed.getLocationInWindow(at);
            if (at[1] <= 0 && at[1] + feed.getHeight() > headerTop) list = homeIn(feed);
        }
        if (list == null) return null;
        View view = header;
        for (int depth = 0; depth < DEPTH + GlassTopBar.HOST_DEPTH; depth++) {
            ViewParent up = view.getParent();
            if (!(up instanceof ViewGroup)) return null;
            view = (View) up;
            view.getLocationInWindow(at);
            if (at[1] <= 0) {
                home = list;
                return view;
            }
        }
        return null;
    }

    private void hold(View top, ViewGroup source, int status) {
        int[] at = new int[2];
        source.getLocationInWindow(at);
        frame = top;
        panel = source;
        panelTop = at[1];
        gap = status;
    }

    /**
     * Whether the glass still reaches up over Home: [header] showing in the same panel, the panel where
     * it was, and Home's list showing across the screen, not a tab beside it in a pager.
     */
    private boolean holds(View header, ViewGroup source) {
        View list = home;
        if (list == null || source != panel || !header.isShown() || !list.isShown()) return false;
        int[] at = new int[2];
        source.getLocationInWindow(at);
        return at[1] == panelTop && across(list);
    }

    /** Whether [view] sits across the screen, and not off to one side as a page beside the one showing. */
    private static boolean across(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return Math.abs(at[0]) < Math.max(1, view.getWidth() / 2);
    }

    private void unclipUpTo(View header, View top) {
        for (View at = (View) header.getParent(); ; at = (View) at.getParent()) {
            unclip((ViewGroup) at);
            if (at == top) break;
        }
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

    /**
     * Home's list in [feed], which on Home's first frame has no posts in it yet: down through the views
     * filling their parent from its corner, so through a tab pager only into the page on screen. Null
     * when another tab is showing. Padding a view around the list instead would leave the list, and the
     * posts it clips, below the status bar.
     */
    @Nullable
    static View homeIn(View feed) {
        View at = feed;
        for (int depth = 0; depth <= LIST_DEPTH && at instanceof ViewGroup; depth++) {
            if (named(at, HOME_LIST)) return at;
            ViewGroup group = (ViewGroup) at;
            View next = null;
            for (int i = 0, n = group.getChildCount(); i < n && next == null; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && child.getLeft() - group.getScrollX() == 0
                        && child.getTop() == 0 && child.getWidth() == group.getWidth()
                        && child.getHeight() == group.getHeight() && child.getHeight() > 0) next = child;
            }
            at = next;
        }
        return null;
    }

    /** Whether [view] is a RecyclerView or a subclass of one, whatever Instagram named the subclass. */
    static boolean isRecycler(View view) {
        return named(view, "RecyclerView");
    }

    private static boolean named(View view, String name) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().equals(name)) return true;
        }
        return false;
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
        // The parent at the window's top is up to DEPTH above the host, itself up to HOST_DEPTH above the header.
        for (int depth = 0; top != null && at != top && depth <= DEPTH + GlassTopBar.HOST_DEPTH; depth++) {
            y += at.getY();
            ViewParent up = at.getParent();
            if (!(up instanceof View)) return Integer.MIN_VALUE;
            at = (View) up;
            if (at != top) y -= at.getScrollY();
        }
        return at == top ? Math.round(y) : Integer.MIN_VALUE;
    }

    /** Each parent of [view] up to the window's top, its class, height and whether it clips, for the debug log. */
    static String parents(View view) {
        StringBuilder found = new StringBuilder();
        ViewParent up = view.getParent();
        for (int depth = 0; up instanceof ViewGroup && depth <= DEPTH + GlassTopBar.HOST_DEPTH; depth++) {
            ViewGroup group = (ViewGroup) up;
            found.append(group.getClass().getSimpleName()).append(' ').append(group.getHeight())
                    .append(group.getClipChildren() ? " clips, " : ", ");
            up = group.getParent();
        }
        return found.toString();
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
        home = null;
        panel = null;
        gap = 0;
    }

    /** Whether the glass reaches up by moving Home's feed, not over Instagram's own, for the debug log. */
    boolean pulledUp() {
        return !pulled.isEmpty();
    }
}
