/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsSeekBar;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Swipe left for Messages, part of the "Stop swipe to create" patch (#639).
 *
 * <p>Instagram used to open Messages on a swipe left across Home. Now its main tabs sit in one
 * sideways pager, so that swipe moves to the next tab, or does nothing with {@code Stop swiping
 * between tabs} on. Every touch on an Instagram screen passes through the activity's touch dispatch
 * before any view sees it, and the patch hands each one to {@link #touch} there.
 *
 * <p>While the switch is on, a touch going down on Home's own list, with the Home tab showing in the
 * tabs' screen, is watched. Once its lone finger goes left by the touch slop, at least twice as far
 * as up or down, from a spot where nothing in the list takes a left swipe (a carousel or the stories
 * row that can still move that way, or a seek bar), the touch is HushGram's: the views under it get a
 * cancel, so the pager never slides, and lifting the finger a tenth of the screen's width or more
 * left of where it went down clicks Messages' button on the tab bar, or the one at the top of Home
 * on the layout that has it there. Instagram's own tab change keeps Back going to Home. A second
 * finger swallows the rest of that touch without opening anything.
 *
 * <p>Every other touch is Instagram's, and the hook fails open: with the switch off, HushGram paused,
 * the settings not read yet or anything thrown, the touch goes on as it came. Main thread only.
 */
public final class HomeLeftSwipe {
    /** The tabs' own screen, by the name Instagram keeps for it. */
    static final String MAIN_TABS = "MainTabActivity";

    /** Home's list, by the name it keeps in Instagram. */
    static final String HOME_LIST = "MainFeedRecyclerView";

    static final String TAB_BAR = SwipeToCreate.TAB_BAR;
    static final String FEED_TAB = "feed_tab";
    static final String DIRECT_TAB = "direct_tab";

    /** Messages' button at the top of Home, on the bar layout that has one there. */
    static final String HEADER_INBOX = "action_bar_inbox_button";

    /** What a swipe that opened Messages is counted under. */
    static final String COUNT_OPENED = "Swipe left on Home opened Messages";

    private static final String SWITCH = "left swipe switch";
    private static final String SWIPE = "left swipe";
    private static final String OPEN = "left swipe open";

    static final int IDLE = 0;
    /** Went down on Home's list; not a left swipe yet. */
    static final int ARMED = 1;
    /** HushGram's left swipe, until the finger lifts. */
    static final int SWIPING = 2;
    /** A second finger came down on the swipe; the rest of the touch is swallowed. */
    static final int SWALLOWING = 3;

    /** Looks up an id by its name in Instagram's resources. Swapped in tests, where Instagram's ids don't exist. */
    interface Ids {
        int of(Context context, String name);
    }

    static volatile Ids ids = HomeLeftSwipe::lookUp;

    private static final Map<String, Integer> IDS = new HashMap<>();

    private static int state = IDLE;
    private static float downX;
    private static float downY;
    /** Home's list the touch went down on, and where on it, in its own coordinates. */
    private static WeakReference<View> list = new WeakReference<>(null);
    private static float listX;
    private static float listY;
    private static boolean logged;

    private HomeLeftSwipe() {
    }

    /**
     * Called first thing in every Instagram screen's touch dispatch, or right after Tap to play's
     * call, with each touch event before any view sees it. Answers true for an event HushGram took,
     * which the screen then answers as handled. Never throws.
     */
    public static boolean touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        return touch(activity, event, HomeLeftSwipe::switchedOn);
    }

    static boolean touch(@Nullable Activity activity, @Nullable MotionEvent event, BooleanSupplier on) {
        if (activity == null || event == null) return false;
        try {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                reset();
                arm(activity, event, on);
                return false;
            }
            if (state == IDLE) return false;
            if (state == ARMED) return claims(activity, event, action);
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                state = SWALLOWING;
            } else if (action == MotionEvent.ACTION_UP) {
                boolean open = state == SWIPING && downX - event.getX() >= openDistance(activity);
                reset();
                if (open) activity.getWindow().getDecorView().post(() -> openMessages(activity));
            } else if (action == MotionEvent.ACTION_CANCEL) {
                reset();
            }
            return true;
        } catch (Throwable failure) {
            reset();
            HookStatus.threw(FamilyNames.SWIPE_TO_CREATE, SWIPE, failure);
            return false;
        }
    }

    /**
     * A touch going down. Arms when the switch is on, the screen is the tabs' own with Home's tab
     * picked, and the finger is on Home's list.
     */
    private static void arm(Activity activity, MotionEvent event, BooleanSupplier on) {
        try {
            if (!on.getAsBoolean()) return;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SWIPE_TO_CREATE, SWITCH, failure);
            return;
        }
        if (!MAIN_TABS.equals(activity.getClass().getSimpleName())) return;
        HookStatus.invoked(FamilyNames.SWIPE_TO_CREATE);
        View decor = activity.getWindow().getDecorView();
        if (!homeTabPicked(activity, decor)) return;
        float[] at = new float[2];
        View home = homeListUnder(decor, event.getX(), event.getY(), at);
        if (home == null) return;
        state = ARMED;
        downX = event.getX();
        downY = event.getY();
        list = new WeakReference<>(home);
        listX = at[0];
        listY = at[1];
    }

    /**
     * A move of an armed touch: once it's gone left by the touch slop and at least twice as far as up
     * or down, from a spot on Home's list where nothing takes a left swipe, the touch becomes a swipe,
     * the views under it get a cancel and this answers true. Any other move past the slop, a second
     * finger or the touch ending gives the touch back. The views are asked here and not as the touch
     * went down: a Compose view answers for the last touch that went down on it, and only once it was
     * handed this one does it answer for this one.
     */
    private static boolean claims(Activity activity, MotionEvent event, int action) {
        if (action != MotionEvent.ACTION_MOVE) {
            reset();
            return false;
        }
        float dx = downX - event.getX();
        float dy = Math.abs(event.getY() - downY);
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        if (Math.abs(dx) < slop && dy < slop) return false;
        View home = list.get();
        if (dx < slop || dx < 2 * dy || home == null || !home.isShown() || anyUnder(home, listX, listY, HomeLeftSwipe::takesLeftSwipe)) {
            reset();
            return false;
        }
        state = SWIPING;
        cancelViews(activity, event);
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "HomeLeftSwipe: took a left swipe on Home for Messages");
        }
        return true;
    }

    /** Whether Home's button on the tab bar is the picked one. */
    private static boolean homeTabPicked(Activity activity, View decor) {
        View home = SwipeToCreate.buttonIn(decor, ids.of(activity, TAB_BAR), ids.of(activity, FEED_TAB));
        return home != null && home.isSelected();
    }

    /**
     * Home's list under (x, y) in {@code view}'s coordinates, shown, with {@code at} set to the point
     * in the list's coordinates. Null when the point isn't on it.
     */
    @Nullable
    static View homeListUnder(View view, float x, float y, float[] at) {
        if (named(view, HOME_LIST)) {
            if (!view.isShown()) return null;
            at[0] = x;
            at[1] = y;
            return view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        float left = x + group.getScrollX();
        float top = y + group.getScrollY();
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            float childX = left - child.getX();
            float childY = top - child.getY();
            if (childX < 0 || childX >= child.getWidth() || childY < 0 || childY >= child.getHeight()) continue;
            View home = homeListUnder(child, childX, childY, at);
            if (home != null) return home;
        }
        return null;
    }

    /** Whether a view takes a left swipe itself: it scrolls that way, or it's a seek bar. */
    private static boolean takesLeftSwipe(View view) {
        return view.canScrollHorizontally(1) || view instanceof AbsSeekBar;
    }

    /** Whether {@code view}, or a shown view under (x, y) in its coordinates, passes {@code test}. */
    private static boolean anyUnder(View view, float x, float y, Predicate<View> test) {
        if (test.test(view)) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        float left = x + group.getScrollX();
        float top = y + group.getScrollY();
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            float childX = child.getX();
            float childY = child.getY();
            if (left < childX || left >= childX + child.getWidth() || top < childY || top >= childY + child.getHeight()) continue;
            if (anyUnder(child, left - childX, top - childY, test)) return true;
        }
        return false;
    }

    /** Hands the screen's views a cancel for the touch HushGram just took. */
    private static void cancelViews(Activity activity, MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            activity.getWindow().superDispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    /** How far left of where it went down a swipe has to lift to open Messages. */
    private static float openDistance(Activity activity) {
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        return Math.max(2 * slop, activity.getResources().getDisplayMetrics().widthPixels / 10f);
    }

    /** Clicks Messages' button, unless the screen went away since the lift. */
    private static void openMessages(Activity activity) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            View button = messagesButton(activity);
            if (button == null) {
                Logger.printDebug(() -> "HomeLeftSwipe: no Messages button on this screen");
                return;
            }
            if (button.performClick()) {
                HookStatus.counted(FamilyNames.SWIPE_TO_CREATE, COUNT_OPENED);
                Logger.printDebug(() -> "HomeLeftSwipe: a left swipe on Home opened Messages");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SWIPE_TO_CREATE, OPEN, failure);
        }
    }

    /** Messages' button on the tab bar, else the shown one at the top of Home, else null. */
    @Nullable
    static View messagesButton(Activity activity) {
        View root = activity.getWindow().getDecorView();
        View tab = SwipeToCreate.buttonIn(root, ids.of(activity, TAB_BAR), ids.of(activity, DIRECT_TAB));
        if (tab != null) return tab;
        int header = ids.of(activity, HEADER_INBOX);
        View inbox = header == 0 ? null : root.findViewById(header);
        return inbox != null && inbox.isShown() ? inbox : null;
    }

    private static boolean named(View view, String name) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().equals(name)) return true;
        }
        return false;
    }

    private static int lookUp(Context context, String name) {
        synchronized (IDS) {
            Integer found = IDS.get(name);
            if (found == null) {
                found = context.getResources().getIdentifier(name, "id", Utils.resourcePackage(context));
                IDS.put(name, found);
            }
            return found;
        }
    }

    private static void reset() {
        state = IDLE;
        list = new WeakReference<>(null);
    }

    /** Where the touch stands. Tests only. */
    static int stateForTests() {
        return state;
    }

    /** Forgets the touch and the ids looked up. Tests only. */
    static void forgetForTests() {
        reset();
        logged = false;
        ids = HomeLeftSwipe::lookUp;
        synchronized (IDS) {
            IDS.clear();
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.SWIPE_LEFT_MESSAGES.get();
    }
}
