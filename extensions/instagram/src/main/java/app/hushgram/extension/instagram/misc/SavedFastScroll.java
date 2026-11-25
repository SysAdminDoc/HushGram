/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Fast scroll in Saved, part of the "Saved on your profile" patch (#644).
 *
 * <p>Saved and its collections are long grids with no way through them but flicking. While one of them
 * scrolls, a handle shows at its right edge, where a scroll bar would be, and fades a moment after it
 * stops. Dragging the handle moves the grid with it: the top of its track is the top of the grid and the
 * bottom is the end of what's loaded, as Android's own fast scrollers work. A grid shorter than
 * {@link #MIN_SCREENS} screens gets no handle, since a flick or two already reaches its end.
 *
 * <p>Nothing in Instagram's bytecode is touched. Saved's pages are found by the names Instagram gives
 * them ({@link MessagesLock#SAVED_PAGES}, the ones Lock Saved watches for), and the grid is the
 * RecyclerView that is the page, sits inside it, or holds it when the name is a tile's, found by the
 * class name Instagram keeps.
 * The handle is a view of its own over the window, so Instagram's layouts never see it. A touch that
 * misses it goes on to the grid as if it weren't there, and while it's hidden it takes no touches at all.
 * It moves the grid by scrollBy, the way AndroidX's own fast scroller does, so the rows in between are
 * laid out as it passes them.
 *
 * <p>Fast scroll on Home and Fast scroll in chats (#826) put the same handle on Home's feed, the list
 * Instagram keeps the class name of ({@link #HOME_LIST}), and on a chat's messages, the list it names
 * {@link #CHAT_LIST}. A chat's list runs from the newest message up, but its scroll bar reads it as it
 * shows, so the top of the track is the oldest message loaded and the bottom the newest. Lock Saved's and
 * Lock Messages' cover hides the handle on all three.
 *
 * <p>The switches are read on every look and every scroll, so they take effect without a restart. All
 * off or paused, a look costs a read of each and queues nothing.
 */
public final class SavedFastScroll {
    static final String TAG = "hushgram_saved_fast_scroll";
    /** The class Instagram's grids keep the name of. */
    static final String LIST_CLASS = "androidx.recyclerview.widget.RecyclerView";
    /** The class name Instagram keeps for Home's feed, as Hide the feed's left swipe and Glass reach find it. */
    static final String HOME_LIST = "MainFeedRecyclerView";
    /** The name Instagram gives a chat's list of messages. */
    static final String CHAT_LIST = "message_list";

    /** How often a window is looked through for Saved, at most. */
    private static final long LOOK_EVERY_MS = 600;
    /** How long the handle stays after the grid stops, and how long it takes to fade. */
    static final long SHOW_FOR_MS = 1500;
    static final long FADE_MS = 200;
    /** The shortest grid, in screens, that gets a handle. */
    static final int MIN_SCREENS = 3;

    /** The handle's length, its width, its width while held, and its gap from the edge, in dp. */
    private static final int THUMB_DP = 56;
    private static final int THIN_DP = 6;
    private static final int HELD_DP = 10;
    private static final int EDGE_DP = 4;
    /** How far from the edge, and above or below the handle, a finger still grabs it: it's thin and a fingertip isn't. */
    private static final int GRAB_WIDTH_DP = 48;
    private static final int GRAB_SLOP_DP = 16;
    /** Kept clear at both ends of the track, so the handle never sits on a corner or the tab bar's edge. */
    private static final int TRACK_INSET_DP = 8;

    /** What a scroll bar reads, by the names View gives them: how far down, how long, and how much shows. */
    private static final String[] METRICS = {
            "computeVerticalScrollOffset", "computeVerticalScrollRange", "computeVerticalScrollExtent",
    };
    private static final Method[] metricMethods = new Method[METRICS.length];

    private static volatile boolean registered;
    private static final WeakHashMap<View, Tracker> tracked = new WeakHashMap<>();

    private SavedFastScroll() {
    }

    /** Called as the application starts. Does nothing in a build that doesn't carry the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.savedOnProfile()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "fast scroll start", failure);
        }
    }

    /** Whether any of the three switches is on and HushGram isn't paused. */
    static boolean on() {
        return Utils.settingsReady() && (Settings.SAVED_FAST_SCROLL.get() || Settings.FAST_SCROLL_HOME.get()
                || Settings.FAST_SCROLL_CHATS.get());
    }

    /** Watches [activity]'s window for Saved's grids, once per window. */
    static void track(Activity activity) {
        try {
            if (activity.getWindow() == null) return;
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            Tracker tracker;
            synchronized (tracked) {
                if (tracked.containsKey(decor)) return;
                tracker = new Tracker((ViewGroup) decor);
                tracked.put(decor, tracker);
            }
            ViewTreeObserver observer = decor.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(tracker);
            observer.addOnScrollChangedListener(tracker);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "fast scroll watch", failure);
        }
    }

    /** Stops watching [activity]'s window. */
    static void release(Activity activity) {
        try {
            if (activity.getWindow() == null) return;
            View decor = activity.getWindow().getDecorView();
            Tracker tracker;
            synchronized (tracked) {
                tracker = tracked.remove(decor);
            }
            if (tracker == null) return;
            ViewTreeObserver observer = decor.getViewTreeObserver();
            observer.removeOnGlobalLayoutListener(tracker);
            observer.removeOnScrollChangedListener(tracker);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "fast scroll release", failure);
        }
    }

    /** The watch on [decor]'s window, for a test that lays it out or scrolls it by hand. */
    @Nullable
    static Tracker trackerForTests(View decor) {
        synchronized (tracked) {
            return tracked.get(decor);
        }
    }

    /**
     * The lists on screen in [window] whose switch is on: Saved's grid, Home's feed and a chat's messages.
     * Empty when a lock's cover is over the window. A chat can open over Home with Home's feed still laid
     * out under it, so the handle follows whichever of them moves rather than the biggest. The tab bar's
     * pager keeps the tabs not picked laid out off screen, and those aren't counted.
     */
    static List<ViewGroup> lists(ViewGroup window) {
        List<ViewGroup> found = new ArrayList<>();
        View cover = window.findViewWithTag(MessagesLock.COVER_TAG);
        if (cover != null && cover.isShown()) return found;
        if (Settings.SAVED_FAST_SCROLL.get()) {
            ViewGroup saved = savedList(window);
            if (saved != null) found.add(saved);
        }
        boolean home = Settings.FAST_SCROLL_HOME.get();
        int chat = Settings.FAST_SCROLL_CHATS.get() ? GlassTabBar.id(window.getContext(), CHAT_LIST) : 0;
        if (home || chat != 0) collectLists(window, home, chat, new Rect(), found);
        return found;
    }

    /**
     * Adds to [found] each list under [view] that shows and is Home's feed, when [home], or a chat's
     * messages, when [chat] is their id. The lists inside one, a post's carousel or the stories tray,
     * aren't walked.
     */
    private static void collectLists(View view, boolean home, int chat, Rect area, List<ViewGroup> found) {
        if (view.getVisibility() != View.VISIBLE || view instanceof Handle || !(view instanceof ViewGroup)) return;
        if (isList(view) && ((chat != 0 && view.getId() == chat) || (home && namedClass(view, HOME_LIST)))) {
            if (shownArea(view, area) > 0 && !found.contains(view)) found.add((ViewGroup) view);
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectLists(group.getChildAt(i), home, chat, area, found);
    }

    private static boolean namedClass(View view, String name) {
        for (Class<?> type = view.getClass(); type != null && type != ViewGroup.class; type = type.getSuperclass()) {
            if (type.getSimpleName().equals(name)) return true;
        }
        return false;
    }

    /**
     * The grid of the Saved page on screen in [window], or null when no Saved page shows or Lock Saved
     * covers it. Of the grids found, the one showing the most of itself wins: a collection's pager keeps
     * its neighbours' grids laid out off screen.
     */
    @Nullable
    static ViewGroup savedList(ViewGroup window) {
        String[] names = MessagesLock.SAVED_PAGES;
        int[] ids = new int[names.length];
        boolean any = false;
        for (int i = 0; i < names.length; i++) {
            ids[i] = GlassTabBar.id(window.getContext(), names[i]);
            any |= ids[i] != 0;
        }
        if (!any) return null;
        View cover = window.findViewWithTag(MessagesLock.COVER_TAG);
        if (cover != null && cover.isShown()) return null;
        List<ViewGroup> found = new ArrayList<>();
        Rect area = new Rect();
        collect(window, ids, null, area, found);
        ViewGroup best = null;
        long bestShown = 0;
        for (ViewGroup grid : found) {
            long shown = shownArea(grid, area);
            if (shown > bestShown) {
                best = grid;
                bestShown = shown;
            }
        }
        return best;
    }

    /**
     * Adds to [found] the grid of each Saved page under [view]: the page itself when it's a grid, the
     * biggest grid inside it, or, for a tile with no grid inside, the grid [around] it. Inside comes
     * first because Saved opened from the menu sits in the tab bar's pager, itself a RecyclerView. A
     * grid already found isn't walked again, so a grid of a hundred tiles that each carry a Saved name
     * costs one look, not a hundred.
     */
    private static void collect(View view, int[] ids, @Nullable ViewGroup around, Rect area, List<ViewGroup> found) {
        if (view.getVisibility() != View.VISIBLE || view instanceof Handle) return;
        if (around != null && found.contains(around)) return;
        boolean grid = isList(view);
        if (grid) around = (ViewGroup) view;
        if (named(view, ids)) {
            if (view.isShown() && view.getGlobalVisibleRect(area) && !area.isEmpty()) {
                ViewGroup list = grid ? (ViewGroup) view : biggestList(view, area);
                if (list == null) list = around;
                if (list != null && !found.contains(list)) found.add(list);
            }
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), ids, around, area, found);
    }

    private static boolean named(View view, int[] ids) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return false;
        for (int wanted : ids) {
            if (wanted == id) return true;
        }
        return false;
    }

    /** The grid under [view] that shows the most of itself, or null. A grid inside a grid isn't counted. */
    @Nullable
    private static ViewGroup biggestList(View view, Rect area) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        ViewGroup best = null;
        long bestShown = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            ViewGroup list = isList(child) ? (ViewGroup) child : biggestList(child, area);
            long shown = list == null ? 0 : shownArea(list, area);
            if (shown > bestShown) {
                best = list;
                bestShown = shown;
            }
        }
        return best;
    }

    private static long shownArea(View view, Rect area) {
        if (!view.isShown() || !view.getGlobalVisibleRect(area)) return 0;
        return (long) area.width() * area.height();
    }

    /** Whether [view] is one of Instagram's RecyclerViews, by the class name it keeps. */
    static boolean isList(View view) {
        if (!(view instanceof ViewGroup)) return false;
        for (Class<?> type = view.getClass(); type != null && type != ViewGroup.class; type = type.getSuperclass()) {
            if (type.getName().equals(LIST_CLASS)) return true;
        }
        return false;
    }

    /** How far down [grid] is scrolled, as its scroll bar sees it. */
    static int offset(View grid) throws ReflectiveOperationException {
        return metric(grid, 0);
    }

    /** How long [grid] is, as its scroll bar sees it: an estimate from the rows laid out, for a RecyclerView. */
    static int range(View grid) throws ReflectiveOperationException {
        return metric(grid, 1);
    }

    /** How much of [grid] shows. */
    static int extent(View grid) throws ReflectiveOperationException {
        return metric(grid, 2);
    }

    private static int metric(View grid, int which) throws ReflectiveOperationException {
        Method found = metricMethods[which];
        if (found == null) {
            found = View.class.getDeclaredMethod(METRICS[which]);
            found.setAccessible(true);
            metricMethods[which] = found;
        }
        return (Integer) found.invoke(grid);
    }

    /**
     * One window's watch: the lists it shows that a handle can follow, found on a look after a burst of
     * layouts, and the handle, moved on every scroll of the one that last moved.
     */
    static final class Tracker implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnScrollChangedListener {
        private final ViewGroup window;
        /** The lists found on the last look. */
        private final List<WeakReference<ViewGroup>> lists = new ArrayList<>();
        /**
         * Where each of them stood when last seen. A window's scroll listeners hear every view in it that
         * scrolls, so the handle shows only when one of these lists has moved.
         */
        private int[] seenAt = new int[0];
        /** The list the handle follows: the last of them to move. */
        private WeakReference<ViewGroup> list = new WeakReference<>(null);
        private WeakReference<View> tabBar = new WeakReference<>(null);
        @Nullable private Handle handle;
        private long last;
        private boolean pending;

        Tracker(ViewGroup window) {
            this.window = window;
        }

        @Override public void onGlobalLayout() {
            if (!on()) return;
            long now = SystemClock.uptimeMillis();
            if (now - last < LOOK_EVERY_MS) {
                // The last layout of a burst is the one that matters, so look once more after it.
                if (!pending) {
                    pending = true;
                    window.postDelayed(() -> {
                        pending = false;
                        last = SystemClock.uptimeMillis();
                        look();
                    }, LOOK_EVERY_MS);
                }
                return;
            }
            last = now;
            look();
        }

        @Override public void onScrollChanged() {
            scrolled();
        }

        /** Finds the lists on screen. Leaving the one the handle follows hides the handle at once. */
        void look() {
            try {
                if (!on()) return;
                List<ViewGroup> found = lists(window);
                if (!sameAs(found)) {
                    int[] at = new int[found.size()];
                    lists.clear();
                    for (int i = 0; i < at.length; i++) {
                        at[i] = offset(found.get(i));
                        lists.add(new WeakReference<>(found.get(i)));
                    }
                    seenAt = at;
                    ViewGroup followed = list.get();
                    if (followed != null && !found.contains(followed)) {
                        list = new WeakReference<>(null);
                        if (handle != null) handle.hideNow();
                    }
                }
                if (!found.isEmpty()) tabBar = new WeakReference<>(GlassTabBar.findShown(window, GlassTabBar.TAB_BAR));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "fast scroll look", failure);
            }
        }

        private boolean sameAs(List<ViewGroup> found) {
            if (found.size() != lists.size()) return false;
            for (int i = 0; i < found.size(); i++) {
                if (lists.get(i).get() != found.get(i)) return false;
            }
            return true;
        }

        /** Shows the handle where the list that moved now stands, or where the held one does. */
        void scrolled() {
            try {
                if (lists.isEmpty() || !on()) return;
                ViewGroup moved = null;
                for (int i = 0; i < lists.size(); i++) {
                    ViewGroup grid = lists.get(i).get();
                    if (!onScreen(grid)) continue;
                    int at = offset(grid);
                    if (at == seenAt[i]) continue;
                    seenAt[i] = at;
                    if (moved == null) moved = grid;
                }
                ViewGroup followed = list.get();
                if (followed != null && !onScreen(followed)) {
                    list = new WeakReference<>(null);
                    followed = null;
                    if (handle != null) handle.hideNow();
                }
                if (followed != null && handle != null && handle.dragging) moved = followed;
                if (moved == null) return;
                list = new WeakReference<>(moved);
                handle().follow(moved, tabBar.get());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "fast scroll", failure);
            }
        }

        private boolean onScreen(@Nullable ViewGroup grid) {
            return grid != null && grid.isAttachedToWindow() && grid.getRootView() == window && grid.isShown();
        }

        /** The handle over this window, put there the first time it's needed. */
        Handle handle() {
            Handle shown = handle;
            if (shown == null || shown.getParent() != window) {
                shown = new Handle(window.getContext());
                window.addView(shown, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                handle = shown;
            } else if (shown.getVisibility() != View.VISIBLE
                    && window.indexOfChild(shown) != window.getChildCount() - 1) {
                // Something was put over the window since; the handle goes back on top as it shows.
                shown.bringToFront();
            }
            return shown;
        }

        @Nullable
        Handle handleForTests() {
            return handle;
        }
    }

    /**
     * The handle: a rounded bar at the grid's right edge, light in dark mode and dark in light mode, with a
     * thin edge of the other so it reads over any photo. It spans the window so it can sit anywhere along
     * the grid, and takes only the touches that land on it.
     */
    static final class Handle extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF thumb = new RectF();
        /** Where the handle runs, in this view: the shown part of the grid inside its padding, above the tab bar. */
        final Rect track = new Rect();
        private final Rect seen = new Rect();
        private final int[] at = new int[2];
        private final float density;
        private final Runnable fade = this::fade;
        private WeakReference<ViewGroup> list = new WeakReference<>(null);
        private int range;
        private int extent;
        /** The handle's top, in this view. */
        float top;
        boolean dragging;
        /** How far below the handle's top the finger went down. */
        private float grabbedAt;

        Handle(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setTag(TAG);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(INVISIBLE);
            edge.setStyle(Paint.Style.STROKE);
        }

        float length() {
            return THUMB_DP * density;
        }

        /** Puts the handle where [grid]'s scroll stands and shows it, or hides it when the grid is too short for one. */
        void follow(ViewGroup grid, @Nullable View bar) throws ReflectiveOperationException {
            list = new WeakReference<>(grid);
            int offset = offset(grid);
            range = range(grid);
            extent = extent(grid);
            if (extent <= 0 || range < MIN_SCREENS * extent || !trackOf(grid, bar) || track.height() < 3 * length()) {
                hideNow();
                return;
            }
            if (!dragging) {
                int room = range - extent;
                float share = Math.max(0f, Math.min(1f, offset / (float) room));
                top = track.top + share * (track.height() - length());
                fadeLater();
            }
            show();
            invalidate();
        }

        /** Sets [track] from where [grid] shows, inside its padding and above a shown [bar]. False when none of it shows. */
        private boolean trackOf(ViewGroup grid, @Nullable View bar) {
            if (!grid.isShown() || !grid.getGlobalVisibleRect(seen)) return false;
            grid.getLocationInWindow(at);
            int upper = Math.max(seen.top, at[1] + grid.getPaddingTop());
            int lower = Math.min(seen.bottom, at[1] + grid.getHeight() - grid.getPaddingBottom());
            if (bar != null && bar.isShown() && bar.getHeight() > 0) {
                bar.getLocationInWindow(at);
                if (at[1] > upper) lower = Math.min(lower, at[1]);
            }
            getLocationInWindow(at);
            int inset = Math.round(TRACK_INSET_DP * density);
            track.set(seen.left - at[0], upper - at[1] + inset, seen.right - at[0], lower - at[1] - inset);
            return track.width() > 0 && track.height() > 0;
        }

        private void show() {
            if (getVisibility() == VISIBLE && getAlpha() == 1f) return;
            animate().cancel();
            setAlpha(1f);
            setVisibility(VISIBLE);
        }

        private void fadeLater() {
            removeCallbacks(fade);
            postDelayed(fade, SHOW_FOR_MS);
        }

        private void fade() {
            if (dragging || getVisibility() != VISIBLE) return;
            animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
                if (!dragging) setVisibility(INVISIBLE);
            });
        }

        void hideNow() {
            removeCallbacks(fade);
            animate().cancel();
            dragging = false;
            setVisibility(INVISIBLE);
        }

        /** Whether a finger at [x], [y] lands on the handle, give or take a fingertip. */
        boolean grabs(float x, float y) {
            if (getVisibility() != VISIBLE || track.isEmpty()) return false;
            float slop = GRAB_SLOP_DP * density;
            return x >= track.right - GRAB_WIDTH_DP * density && x <= track.right
                    && y >= top - slop && y <= top + length() + slop;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ViewGroup grid = list.get();
                        if (grid == null || !grabs(event.getX(), event.getY())) return false;
                        stopFling(grid);
                        dragging = true;
                        grabbedAt = event.getY() - top;
                        removeCallbacks(fade);
                        show();
                        invalidate();
                        HookStatus.counted(FamilyNames.SAVED_ON_PROFILE, "fast scroll drag");
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) return false;
                        drag(event.getY());
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) return false;
                        dragging = false;
                        invalidate();
                        fadeLater();
                        return true;
                    default:
                        return dragging;
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "fast scroll drag", failure);
                dragging = false;
                return false;
            }
        }

        /** Moves the handle under the finger at [y], and the grid to the same share of the way down. */
        private void drag(float y) throws ReflectiveOperationException {
            ViewGroup grid = list.get();
            if (grid == null) return;
            float room = track.height() - length();
            top = Math.max(track.top, Math.min(track.top + room, y - grabbedAt));
            jump(grid, room <= 0 ? 0f : (top - track.top) / room);
            invalidate();
        }

        /**
         * Scrolls [grid] to [share] of the way down. A RecyclerView's length is an estimate made from the rows
         * it has laid out, so at either end the grid is asked for a screen past it: the top of the track is
         * the top and the bottom is the last row loaded, wherever the estimate put them.
         */
        static void jump(ViewGroup grid, float share) throws ReflectiveOperationException {
            int shows = extent(grid);
            int target = Math.round(share * Math.max(0, range(grid) - shows));
            int by = target - offset(grid);
            if (share >= 1f) by += shows;
            else if (share <= 0f) by -= shows;
            if (by != 0) grid.scrollBy(0, by);
        }

        /** Stops a fling still running, the way a finger going down on the grid does. */
        private static void stopFling(ViewGroup grid) {
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0, 0, 0);
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            try {
                grid.onInterceptTouchEvent(down);
                grid.onInterceptTouchEvent(cancel);
            } finally {
                down.recycle();
                cancel.recycle();
            }
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            if (track.isEmpty()) return;
            float width = (dragging ? HELD_DP : THIN_DP) * density;
            float right = track.right - EDGE_DP * density;
            thumb.set(right - width, top, right, top + length());
            boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            fill.setColor(night ? 0xE6F2F2F2 : 0xCC3A3A3A);
            edge.setColor(night ? 0x59000000 : 0x59FFFFFF);
            edge.setStrokeWidth(density);
            float radius = width / 2f;
            canvas.drawRoundRect(thumb, radius, radius, fill);
            canvas.drawRoundRect(thumb, radius, radius, edge);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            track(activity);
        }

        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            release(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }
    }
}
