/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Center the name on profiles (#140): the username at the top of a profile, yours or someone else's,
 * sits in the middle of the bar.
 *
 * <p>Instagram 450's profile bar ({@link #BAR_ID}) is a row of three: the buttons on the left, the
 * username's container ({@link #CONTAINER_ID}) and the buttons on the right. The name and its arrow
 * sit inside the container wherever its row puts them, so with more buttons on one side than the
 * other they end up off the middle. After each layout the container's shown children are moved
 * sideways together, by a translation, until their middle is the bar's, as far as the container
 * lets them go, so the name never runs under a button. The buttons don't move. A translation
 * changes no layout, and a tap on the name lands where it's drawn.
 *
 * <p>Center titles on other screens (#161), a switch of its own, does the same for the bar most other
 * screens share, like notifications ({@link #SHARED_BAR_ID}), which Instagram's one action bar class
 * builds: a title area ({@link #TITLE_AREA_ID}) holding the back button and the title's group, with
 * the bar's other buttons after it. The group holding the title ({@link #TITLE_ID}) is moved toward
 * the bar's middle, only as far as the room between the back button and the area's end, so a long
 * title stays where Instagram put it and shortened. Profile bars and Home's header are left to their
 * own switches. Either switch's moves are put back on their own when it goes off.
 *
 * <p>Logo and titles at the start (#310) moves both the other way, for accounts whose newer bars
 * center them: the profile name to its container's start, and a screen's title group up to the back
 * button, or the area's start without one. It wins over both centering switches, and its moves are
 * worked out and put back the same way.
 *
 * <p>No layout listener runs for nothing: with both switches off, HushGram paused or the settings not
 * read, an activity gets only a window focus listener, which hears a switch turned on once the
 * settings close. Off, a name is put back where Instagram had it, if nothing else moved it since.
 * Anything thrown leaves the bar as it is.
 */
public final class ProfileTitle {
    /** The profile's top bar, the same on all seven 450 builds. */
    static final String BAR_ID = "profile_action_bar";
    /** The username's container inside it. */
    static final String CONTAINER_ID = "action_bar_username_container";
    /** The top bar other screens share, the same on all seven 450 builds. */
    static final String SHARED_BAR_ID = "action_bar";
    /** Its title area: the back button, the title's group and anything else before the bar's buttons. */
    static final String TITLE_AREA_ID = "action_bar_new_title_container";
    /** The title inside the area. */
    static final String TITLE_ID = "action_bar_title";
    /** Home's header, which has Center the logo on Home's header. */
    static final String HOME_BAR_ID = "main_feed_action_bar";

    /** The most views looked at for bars in one layout. */
    static final int MAX_VIEWS = 3000;

    static final String COUNT_CENTERED = "Profile name centered";
    static final String COUNT_TITLE_CENTERED = "Screen title centered";
    static final String COUNT_AT_START = "Profile name at the start";
    static final String COUNT_TITLE_AT_START = "Screen title at the start";

    private static final HashMap<String, Integer> IDS = new HashMap<>();
    private static volatile boolean registered;
    /** Names this class moved: the translation they had, then the one written here. */
    private static final Map<View, float[]> MOVED = new WeakHashMap<>();
    /** Title groups on shared bars moved here, the same way. */
    private static final Map<View, float[]> MOVED_TITLES = new WeakHashMap<>();
    /** Name containers that clipped to their padding until a name was moved into it. */
    private static final Map<ViewGroup, Boolean> UNCLIPPED = new WeakHashMap<>();
    private static final Focus FOCUS = new Focus();
    private static final Layout LAYOUT = new Layout();

    /** The decor view whose observer has the listeners. */
    private static WeakReference<View> observed = new WeakReference<>(null);
    private static boolean layoutWatched;

    private ProfileTitle() {
    }

    /** Called as the application starts. Does nothing in a build without the tab bar patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.reelsTab()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "profile name start", failure);
        }
    }

    /**
     * Whether names are moved now: centered with their switch on, or put at the start with Logo and
     * titles at the start on, HushGram not paused and the settings read.
     */
    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused()
                && (Settings.CENTER_PROFILE_NAME.get() || Settings.TITLES_AT_START.get());
    }

    /** Whether other screens' titles are moved now, the same way under their own switch. */
    static boolean titlesWanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused()
                && (Settings.CENTER_SCREEN_TITLES.get() || Settings.TITLES_AT_START.get());
    }

    /** Whether names and titles go to the start rather than the middle. */
    static boolean atStart() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.TITLES_AT_START.get();
    }

    static int id(Context context, String name) {
        synchronized (IDS) {
            Integer found = IDS.get(name);
            if (found == null) {
                found = context.getResources().getIdentifier(name, "id", context.getPackageName());
                IDS.put(name, found);
            }
            return found;
        }
    }

    /** Tests give the bar and container ids of their own, since a test app has none of Instagram's. */
    static void idForTests(String name, int id) {
        synchronized (IDS) {
            IDS.put(name, id);
        }
    }

    /** As [activity] comes to the front: listens to it and looks once. */
    static void resumed(Activity activity) {
        try {
            leave();
            View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
            if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
            decor.getViewTreeObserver().addOnWindowFocusChangeListener(FOCUS);
            observed = new WeakReference<>(decor);
            refresh();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "profile name watch", failure);
        }
    }

    /** As [activity] leaves the front: takes its listeners off. Names stay where they are until it's back. */
    static void paused(Activity activity) {
        try {
            View decor = observed.get();
            if (decor != null && decor == peek(activity)) leave();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "profile name release", failure);
        }
    }

    @Nullable
    private static View peek(Activity activity) {
        return activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
    }

    private static void leave() {
        watchLayouts(false);
        View decor = observed.get();
        if (decor != null && decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnWindowFocusChangeListener(FOCUS);
        }
        observed = new WeakReference<>(null);
    }

    /**
     * Centers the names and titles in the window in front for each switch that's wanted, puts back
     * what the others moved, and listens to layouts only while one of them is wanted.
     */
    static void refresh() {
        View decor = observed.get();
        if (decor == null) return;
        try {
            boolean names = wanted();
            boolean titles = titlesWanted();
            if (!names) {
                putBack(MOVED);
                clipAgain();
            }
            if (!titles) putBack(MOVED_TITLES);
            if (!names && !titles) {
                watchLayouts(false);
                return;
            }
            watchLayouts(true);
            centerAll(decor, names, titles, atStart());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "profile name", failure);
        }
    }

    /**
     * Centers the name in each profile bar shown in [root] when [names], and the title on each shared
     * bar when [titles], or puts them at the start when [start]. Never looks inside a profile bar or
     * Home's header for a shared bar.
     */
    static void centerAll(View root, boolean names, boolean titles, boolean start) {
        int barId = id(root.getContext(), BAR_ID);
        int containerId = id(root.getContext(), CONTAINER_ID);
        int sharedId = id(root.getContext(), SHARED_BAR_ID);
        int areaId = id(root.getContext(), TITLE_AREA_ID);
        int titleId = id(root.getContext(), TITLE_ID);
        int homeId = id(root.getContext(), HOME_BAR_ID);
        // Most layouts are in windows with neither bar, which this answers quickly.
        boolean profiles = names && barId != 0 && containerId != 0 && root.findViewById(barId) != null;
        boolean screens = titles && sharedId != 0 && areaId != 0 && titleId != 0 && root.findViewById(areaId) != null;
        if (!profiles && !screens) return;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int seen = 0;
        while (!pending.isEmpty() && seen++ < MAX_VIEWS) {
            View view = pending.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            int id = view.getId();
            if (id != View.NO_ID && id == barId) {
                if (profiles) {
                    View container = view.findViewById(containerId);
                    if (container instanceof ViewGroup) center(view, (ViewGroup) container, start);
                }
                continue;
            }
            if (id != View.NO_ID && id == homeId) continue;
            if (screens && id != View.NO_ID && id == sharedId && view instanceof ViewGroup) {
                centerTitle((ViewGroup) view, areaId, titleId, start);
                // The shared bar sits near the top of the window: with no profile bar to look for,
                // the rest of the window has nothing more to do. With one, the walk goes on into
                // the shared bar too, since a profile's own bar can stand in a custom view there.
                if (!profiles) return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
        }
    }

    /**
     * Moves the group holding [bar]'s title toward the bar's middle, or its start when [start]. A bar
     * showing no title, a custom view in its place for one, gets back any group moved here earlier.
     */
    private static void centerTitle(ViewGroup bar, int areaId, int titleId, boolean start) {
        View found = bar.findViewById(areaId);
        if (!(found instanceof ViewGroup)) return;
        ViewGroup area = (ViewGroup) found;
        View title = area.getVisibility() == View.VISIBLE ? area.findViewById(titleId) : null;
        View group = title != null && title.getWidth() > 0 && shownWithin(title, area) ? childHolding(area, title) : null;
        for (int i = 0; i < area.getChildCount(); i++) {
            View child = area.getChildAt(i);
            if (child != group) restore(child, MOVED_TITLES);
        }
        if (group == null) return;
        if (shift(group, titleOffset(bar, area, group, start), MOVED_TITLES)) {
            HookStatus.counted(FamilyNames.REELS_TAB, start ? COUNT_TITLE_AT_START : COUNT_TITLE_CENTERED);
        }
    }

    /**
     * How far [group], the title's group in [area], moves so what it shows has its middle at [bar]'s
     * middle, kept between the shown views beside it in the area (the back button) and the area's
     * edges, which end where the bar's buttons start. Zero when the title already fills that room or
     * something in the area overlaps it. From layout positions, so it's the same however often asked.
     */
    static int titleOffset(View bar, ViewGroup area, View group) {
        return titleOffset(bar, area, group, false);
    }

    /**
     * As {@link #titleOffset(View, ViewGroup, View)}, or with [start] how far [group] moves so what it
     * shows begins at the start of that room: right after the back button, on the right in a right to
     * left bar.
     */
    static int titleOffset(View bar, ViewGroup area, View group, boolean start) {
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        if (group instanceof ViewGroup) {
            ViewGroup inside = (ViewGroup) group;
            for (int i = 0; i < inside.getChildCount(); i++) {
                View child = inside.getChildAt(i);
                if (child.getVisibility() == View.GONE || child.getWidth() <= 0) continue;
                first = Math.min(first, child.getLeft());
                last = Math.max(last, child.getRight());
            }
        }
        if (first > last) {
            first = 0;
            last = group.getWidth();
        }
        first += group.getLeft();
        last += group.getLeft();
        int low = 0;
        int high = area.getWidth();
        for (int i = 0; i < area.getChildCount(); i++) {
            View beside = area.getChildAt(i);
            if (beside == group || beside.getVisibility() == View.GONE || beside.getWidth() <= 0) continue;
            if (beside.getRight() <= group.getLeft()) low = Math.max(low, beside.getRight());
            else if (beside.getLeft() >= group.getRight()) high = Math.min(high, beside.getLeft());
            else return 0;
        }
        if (bar.getWidth() <= 0 || first < low || last > high) return 0;
        if (start) return rightToLeft(area) ? high - last : low - first;
        int at = leftIn(area, bar);
        if (at == Integer.MIN_VALUE) return 0;
        int wanted = bar.getWidth() / 2 - (at + (first + last) / 2);
        return Math.max(low - first, Math.min(high - last, wanted));
    }

    /** Whether [view] and everything above it up to [top] are shown. */
    private static boolean shownWithin(View view, View top) {
        View at = view;
        while (at != top) {
            if (at.getVisibility() != View.VISIBLE) return false;
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return false;
            at = (View) parent;
        }
        return true;
    }

    /** The child of [group] that is [view] or holds it, or null. */
    @Nullable
    private static View childHolding(ViewGroup group, View view) {
        View at = view;
        while (at.getParent() != group) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return null;
            at = (View) parent;
        }
        return at;
    }

    /**
     * How far [container]'s shown children move so their middle is [bar]'s middle, kept inside the
     * container. Worked out from where the layout put them, not where they're drawn, so it's the
     * same however often it's asked.
     */
    static int offset(View bar, ViewGroup container) {
        return offset(bar, container, false);
    }

    /**
     * As {@link #offset(View, ViewGroup)}, or with [start] how far they move to begin at the
     * container's start, its right edge in a right to left bar.
     */
    static int offset(View bar, ViewGroup container, boolean start) {
        int[] span = span(container);
        if (span == null || bar.getWidth() <= 0) return 0;
        int first = span[0];
        int last = span[1];
        if (start) return rightToLeft(container) ? container.getWidth() - last : -first;
        int at = leftIn(container, bar);
        if (at == Integer.MIN_VALUE) return 0;
        int wanted = bar.getWidth() / 2 - (at + (first + last) / 2);
        return Math.max(-first, Math.min(container.getWidth() - last, wanted));
    }

    /** The left of [container]'s first shown child and the right of its last, where the layout put them, or null with none. */
    @Nullable
    private static int[] span(ViewGroup container) {
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() == View.GONE || child.getWidth() <= 0) continue;
            first = Math.min(first, child.getLeft());
            last = Math.max(last, child.getRight());
        }
        return first > last ? null : new int[]{first, last};
    }

    private static boolean rightToLeft(View view) {
        return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private static void center(View bar, ViewGroup container, boolean start) {
        int offset = offset(bar, container, start);
        boolean moved = false;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            moved |= shift(child, offset, MOVED);
        }
        unclip(container, offset);
        if (moved) HookStatus.counted(FamilyNames.REELS_TAB, start ? COUNT_AT_START : COUNT_CENTERED);
    }

    /**
     * Lets [container] draw its children moved [offset] into its padding, which it would cut them
     * off at. The newer profile header pads the name's row on both sides to keep it in the middle,
     * so a name put at the start sits in that padding (#310). The container clips again once its
     * children are back inside, or put back.
     */
    private static void unclip(ViewGroup container, int offset) {
        int[] span = span(container);
        boolean outside = span != null && offset != 0 && (span[0] + offset < container.getPaddingLeft()
                || span[1] + offset > container.getWidth() - container.getPaddingRight());
        if (outside && container.getClipToPadding()) {
            container.setClipToPadding(false);
            UNCLIPPED.put(container, Boolean.TRUE);
        } else if (!outside && UNCLIPPED.remove(container) != null) {
            container.setClipToPadding(true);
        }
    }

    /** Has each container {@link #unclip} let draw into its padding clip to it again. */
    private static void clipAgain() {
        for (ViewGroup container : UNCLIPPED.keySet()) {
            if (container != null) container.setClipToPadding(true);
        }
        UNCLIPPED.clear();
    }

    /** Moves [view] [offset] from where it was before this class moved it, noting it in [moved]. Whether it moved now. */
    private static boolean shift(View view, int offset, Map<View, float[]> moved) {
        float x = view.getTranslationX();
        float[] held = moved.get(view);
        if (held == null) {
            held = new float[]{x, x};
            moved.put(view, held);
        } else if (x != held[1]) {
            // Something else moved it since; that's where it is now.
            held[0] = x;
        }
        held[1] = held[0] + offset;
        if (x == held[1]) return false;
        view.setTranslationX(held[1]);
        return true;
    }

    /** Puts [view] back if this class moved it and it's still where it was put. */
    private static void restore(View view, Map<View, float[]> moved) {
        float[] held = moved.remove(view);
        if (held != null && view.getTranslationX() == held[1]) view.setTranslationX(held[0]);
    }

    /** Puts back each view in [moved], if it's still where it was put. */
    private static void putBack(Map<View, float[]> moved) {
        if (moved.isEmpty()) return;
        for (Map.Entry<View, float[]> entry : moved.entrySet()) {
            View view = entry.getKey();
            float[] held = entry.getValue();
            if (view != null && view.getTranslationX() == held[1]) view.setTranslationX(held[0]);
        }
        moved.clear();
    }

    /** [view]'s left edge in [bar]'s coordinates, or MIN_VALUE when it isn't inside [bar]. */
    private static int leftIn(View view, View bar) {
        int left = 0;
        View at = view;
        while (at != bar) {
            left += at.getLeft();
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return Integer.MIN_VALUE;
            at = (View) parent;
        }
        return left;
    }

    private static void watchLayouts(boolean on) {
        if (on == layoutWatched) return;
        View decor = observed.get();
        if (on && decor == null) return;
        layoutWatched = on;
        if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
        if (on) decor.getViewTreeObserver().addOnGlobalLayoutListener(LAYOUT);
        else decor.getViewTreeObserver().removeOnGlobalLayoutListener(LAYOUT);
    }

    /** The window getting focus back: the settings closing, where the switch may have changed. */
    private static final class Focus implements ViewTreeObserver.OnWindowFocusChangeListener {
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            if (hasFocus) refresh();
        }
    }

    /** A layout: a profile bar shown, or its buttons changed. */
    private static final class Layout implements ViewTreeObserver.OnGlobalLayoutListener {
        @Override public void onGlobalLayout() {
            refresh();
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            resumed(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            paused(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    static boolean watchingLayoutsForTests() {
        return layoutWatched;
    }

    static void resetForTests() {
        leave();
        MOVED.clear();
        MOVED_TITLES.clear();
        UNCLIPPED.clear();
    }
}
