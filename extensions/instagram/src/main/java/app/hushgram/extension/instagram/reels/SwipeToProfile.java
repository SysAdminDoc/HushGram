/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Swipe left to open the profile" patch.
 *
 * <p>Older Instagram builds opened the creator's profile when you swiped left on a reel. Now the
 * swipe goes to the Friends tab, or nowhere. Before it moves, the Reels viewer asks one check
 * whether a swipe from a reel to its side panel is allowed, and for an ordinary reel the last gate
 * of that check is a call that is usually false. The patch hands that call's answer to {@link #allow}
 * right after Instagram reads it. While the switch is on the answer is yes, and Instagram's own
 * panel picker then chooses the profile panel for an ordinary reel, the way it used to.
 *
 * <p>The profile loads into the swipe container's right panel. On accounts with the new tab bar the
 * swipe setup never builds that panel, and elsewhere Instagram keeps it off on Reels unless its
 * server says otherwise. The patch hands the setup's new-tab-bar answer to {@link #leaveOutRightPanel},
 * which has the setup build the panel while the switch is on. It calls {@link #tabChanged} right
 * after the setup and at the end of each tab change, which turns the panel on for the Reels tab and
 * turns a panel HushGram had built off everywhere else, and {@link #tabChanging} at the start of the
 * next, which turns off only what HushGram turned on, so Instagram picks the other tabs' panels as
 * it always does.
 *
 * <p>With the panel on, the container still gives the swipe away. Before it starts its own drag it
 * walks the views under the finger and yields to any that can scroll sideways, and Instagram's walk
 * counts a pager whose input is off unless a server flag says otherwise. The main tabs' pager and the
 * Reels and Friends pager can always scroll. The patch hands that walk to {@link #childScrolls}:
 * on the Reels tab with the switch on, a swipe left gets HushGram's walk, which passes over every
 * sideways pager and the list inside it, and turns a passed pager's input off so it can't start a
 * drag of its own. The reels themselves sit in an up-and-down pager, and that pager and everything
 * inside it, a carousel included, get Instagram's own walk. A swipe right gets Instagram's walk too,
 * so the tabs' pager still slides it to Home.
 *
 * <p>The tabs' pager starts its drag at the touch slop, half the distance the container waits for,
 * so it would take a swipe left before the container's walk runs. The patch hands the container's
 * call setting that pager's input as a touch starts to {@link #pagerInput}, which notes the pager
 * and passes the call on, and calls {@link #touched} first in the container's touch check, which
 * sees each move before the pager does. Once a lone finger has gone left by the touch slop, it holds
 * the pager until the next touch starts, which gives it back. The next tab change gives every held
 * pager its input back.
 *
 * <p>Once the panel has slid in, Instagram opens the profile in a screen of its own on top of Reels,
 * and that screen has no swipe back. The patch calls {@link #profileResumed} and {@link #profilePaused}
 * from the profile's own resume and pause, and {@link #closesProfile} first in every Instagram
 * screen's touch dispatch. While the profile a left swipe on a reel just opened is showing and the
 * switch is on, a lone finger going mostly right by the touch slop, from a spot where nothing under it
 * can scroll left, is HushGram's: the views under it get a cancel, and lifting it far enough to the
 * right goes back, which closes the profile onto the reel. The profile's grid still slides between
 * its own tabs, since past the first tab it can scroll left. A profile screen Instagram builds again
 * later, after a theme change say, wasn't opened by the swipe, so it keeps only Back.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer comes back and the panel stays as Instagram left it.
 * Touch, tab and profile hooks run on the main thread.
 */
public final class SwipeToProfile {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";
    /** The step a failed panel turn is reported under. */
    static final String PANEL = "panel turn";
    /** The step a failed walk or pager hold is reported under. */
    static final String PAGER = "pager hold";
    /** The step a failed right swipe closing the profile is reported under. */
    static final String CLOSE = "profile close";

    /** How soon after a left swipe on a reel the profile it opens has to show to count as opened by it. */
    static final long OPENS_WITHIN_MS = 5000;

    private static final int IDLE = 0;
    /** The touch went down on the profile the swipe opened; a move right may still make it a close. */
    private static final int ARMED = 1;
    /** The touch is a right swipe closing the profile; lifting it far enough goes back. */
    private static final int CLOSING = 2;
    /** The touch was a close until a second finger landed; the rest of it is swallowed. */
    private static final int SWALLOWING = 3;

    /** What the tab and touch hooks need of Instagram, reached through the stubs the patch writes. */
    interface Panels {
        Object controller(Object host);

        Object container(Object controller);

        Object tab(Object host);

        Object reelsTab();

        Object panel(Object container);

        Object rightView(Object controller);

        boolean panelOn(Object panel);

        void turn(Object container, Object panel, boolean on);

        boolean stockScrolls(View view, int dx, int x, int y, boolean checkView, boolean skipOff);

        boolean pager(Object view);

        boolean inputOn(Object pager);

        void input(Object pager, boolean on);
    }

    private static final Panels INSTAGRAM = new Panels() {
        @Override
        public Object controller(Object host) {
            return SwipeToProfileTargets.controller(host);
        }

        @Override
        public Object container(Object controller) {
            return SwipeToProfileTargets.container(controller);
        }

        @Override
        public Object tab(Object host) {
            return SwipeToProfileTargets.tab(host);
        }

        @Override
        public Object reelsTab() {
            return SwipeToProfileTargets.reelsTab();
        }

        @Override
        public Object panel(Object container) {
            return SwipeToProfileTargets.panel(container);
        }

        @Override
        public Object rightView(Object controller) {
            return SwipeToProfileTargets.rightView(controller);
        }

        @Override
        public boolean panelOn(Object panel) {
            return SwipeToProfileTargets.panelOn(panel);
        }

        @Override
        public void turn(Object container, Object panel, boolean on) {
            SwipeToProfileTargets.turn(container, panel, on);
        }

        @Override
        public boolean stockScrolls(View view, int dx, int x, int y, boolean checkView, boolean skipOff) {
            return SwipeToProfileTargets.childScrolls(view, dx, x, y, checkView, skipOff);
        }

        @Override
        public boolean pager(Object view) {
            return SwipeToProfileTargets.pager(view);
        }

        @Override
        public boolean inputOn(Object pager) {
            return SwipeToProfileTargets.inputOn(pager);
        }

        @Override
        public void input(Object pager, boolean on) {
            SwipeToProfileTargets.input(pager, on);
        }
    };

    private static volatile boolean logged;
    private static volatile boolean loggedPanel;
    private static volatile boolean loggedPager;
    private static volatile boolean loggedClose;
    private static volatile String lastClaim;

    /** Whether the last tab change landed on Reels. */
    private static volatile boolean onReels;

    /** Whether the last swipe setup built the right panel only because HushGram asked it to. */
    private static volatile boolean builtRight;

    /** The panel HushGram turned on and its container, until the next tab change turns it off. */
    private static WeakReference<Object> turnedPanel;
    private static WeakReference<Object> turnedContainer;

    /** The pagers HushGram turned the input off on, until the next tab change turns it back on. */
    private static final List<WeakReference<Object>> heldPagers = new ArrayList<>();

    /** The tabs' pager, as the container last set its input at a touch's start. */
    private static WeakReference<Object> tabsPager;

    /** Where across the screen the current touch went down. */
    private static float downX;

    /** Whether the current touch has had only one finger down so far. */
    private static boolean oneFinger;

    /** Whether a left swipe on a reel went on to the profile, and when, in uptime milliseconds. */
    private static boolean swipePending;
    private static long swipedAt;

    /** The profile a left swipe on a reel opened. */
    private static WeakReference<Object> swipedProfile;

    /** The profile showing now, between its resume and its pause. */
    private static WeakReference<Object> shownProfile;

    /** Where a right swipe closing the profile is in its touch, and where that touch went down. */
    private static int closing;
    private static float closeX;
    private static float closeY;

    private SwipeToProfile() {
    }

    /**
     * Called with Instagram's answer to whether this swipe may go on to the side panel, non-zero for
     * yes. Returns true while the switch is on, and notes the swipe for {@link #profileResumed},
     * otherwise the answer as it came. Never throws.
     */
    public static boolean allow(int stock) {
        return allow(stock != 0, SwipeToProfile::switchedOn);
    }

    static boolean allow(boolean stock, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
            boolean open = on.getAsBoolean();
            // Noted when Instagram allows the swipe itself too, so the profile it opens closes the same way.
            if (open) swiped();
            if (open && !stock && !logged) {
                logged = true;
                Logger.printDebug(() -> "SwipeToProfile: let a swipe on a reel go to its profile");
            }
            return stock || open;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, SWITCH, t);
            return stock;
        }
    }

    /**
     * Called in the swipe setup with Instagram's answer to whether the account has the new tab bar,
     * non-zero for yes, which leaves the right panel out, and the swipe controller. Returns false
     * while the switch is on and the layout has a view for the panel, so the setup builds it, and
     * remembers that until the next setup, such as one for an account on the old tab bar. Otherwise
     * returns the answer as it came. Read once per setup, so turning the switch on takes a restart.
     * Never throws.
     */
    public static boolean leaveOutRightPanel(int stock, Object controller) {
        return leaveOutRightPanel(stock != 0, controller, INSTAGRAM, SwipeToProfile::switchedOn);
    }

    static boolean leaveOutRightPanel(boolean stock, Object controller, Panels panels, BooleanSupplier on) {
        builtRight = false;
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
            if (!stock || !on.getAsBoolean() || panels.rightView(controller) == null) return stock;
            builtRight = true;
            Logger.printDebug(() -> "SwipeToProfile: had the swipe setup build the right panel");
            return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PANEL, t);
            return stock;
        }
    }

    /**
     * Called first thing in each tab change, before Instagram picks the next tab's panels. Turns the
     * profile panel off if HushGram turned it on and it's still on. Never throws.
     */
    public static void tabChanging(Object host) {
        tabChanging(INSTAGRAM);
    }

    static void tabChanging(Panels panels) {
        onReels = false;
        release(panels);
        try {
            Object panel = turnedPanel == null ? null : turnedPanel.get();
            Object container = turnedContainer == null ? null : turnedContainer.get();
            turnedPanel = null;
            turnedContainer = null;
            if (panel != null && container != null && panels.panelOn(panel)) panels.turn(container, panel, false);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PANEL, t);
        }
    }

    /**
     * Called right after the swipe setup and at each return of a tab change. Notes whether the tab is
     * Reels, and there with the switch on, turns the profile panel on when Instagram left it off, and
     * remembers it. Anywhere else, or with the switch off, a panel only HushGram had built is turned
     * off, since it starts on and Instagram doesn't expect it. Never throws.
     */
    public static void tabChanged(Object host) {
        tabChanged(host, INSTAGRAM, SwipeToProfile::switchedOn);
    }

    static void tabChanged(Object host, Panels panels, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
            Object tab = panels.tab(host);
            onReels = tab != null && tab == panels.reelsTab();
            boolean open = onReels && on.getAsBoolean();
            if (!open && !builtRight) return;
            Object controller = panels.controller(host);
            Object container = controller == null ? null : panels.container(controller);
            Object panel = container == null ? null : panels.panel(container);
            if (panel == null || panels.panelOn(panel) == open) return;
            if (!open) {
                panels.turn(container, panel, false);
                return;
            }
            panels.turn(container, panel, true);
            turnedPanel = new WeakReference<>(panel);
            turnedContainer = new WeakReference<>(container);
            if (!loggedPanel) {
                loggedPanel = true;
                Logger.printDebug(() -> "SwipeToProfile: turned the Reels profile panel on");
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PANEL, t);
        }
    }

    /**
     * Called in place of the swipe container's walk of the views under the finger, which answers
     * whether one of them, or {@code view} itself when {@code checkView}, can scroll sideways against
     * {@code dx}, with (x, y) in {@code view}'s coordinates. On Reels with the switch on, a swipe
     * left (negative {@code dx}) gets HushGram's walk, which goes the same way but passes over every
     * sideways pager and the list inside it, and holds a passed pager that still takes swipes. An
     * up-and-down pager and what's inside it get Instagram's walk. A swipe right gets Instagram's walk
     * and leaves held pagers as they are, mid-swipe. With the switch off Instagram walks them all, and
     * a pager HushGram held gets its input back. Never throws on HushGram's account. {@code checkView}
     * and {@code skipOff} come as Instagram's registers hold them, non-zero for yes.
     */
    public static boolean childScrolls(View view, int dx, int x, int y, int checkView, int skipOff) {
        return childScrolls(view, dx, x, y, checkView != 0, skipOff != 0, INSTAGRAM, SwipeToProfile::reelsOpen);
    }

    static boolean childScrolls(View view, int dx, int x, int y, boolean checkView, boolean skipOff, Panels panels, BooleanSupplier on) {
        boolean open = false;
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
            open = on.getAsBoolean();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, SWITCH, t);
        }
        if (open) {
            if (dx < 0) {
                try {
                    return scrolls(view, dx, x, y, checkView, skipOff, panels);
                } catch (Throwable t) {
                    HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PAGER, t);
                    release(panels);
                }
            }
        } else {
            release(panels);
        }
        return panels.stockScrolls(view, dx, x, y, checkView, skipOff);
    }

    /**
     * Instagram's walk as 452 has it, children first, last child first, except that a sideways pager
     * and the list inside it never count as scrolling. An up-and-down pager, the reels' own, goes to
     * Instagram's walk whole, so a carousel in a reel still takes its swipe.
     */
    private static boolean scrolls(View view, int dx, int x, int y, boolean checkView, boolean skipOff, Panels panels) {
        boolean pager = panels.pager(view);
        if (pager && !view.canScrollHorizontally(1) && !view.canScrollHorizontally(-1)) {
            return panels.stockScrolls(view, dx, x, y, checkView, skipOff);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int left = x + group.getScrollX();
            int top = y + group.getScrollY();
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                float childX = child.getX();
                float childY = child.getY();
                if (left < childX || left >= childX + child.getWidth() || top < childY || top >= childY + child.getHeight()) continue;
                if (scrolls(child, dx, left - (int) childX, top - (int) childY, true, skipOff, panels)) return true;
            }
        }
        if (pager) {
            hold(view, panels);
            return false;
        }
        if (panels.pager(view.getParent())) return false;
        if (!checkView || !view.canScrollHorizontally(-dx)) return false;
        String claim = view.getClass().getName();
        if (!claim.equals(lastClaim)) {
            lastClaim = claim;
            Logger.printDebug(() -> "SwipeToProfile: a " + claim + " under the finger takes the swipe");
        }
        return true;
    }

    /**
     * Called in place of the swipe container's own call turning the tabs' pager input on or off as a
     * touch starts. Notes the pager for {@link #touched}, which may hold it later in the touch, and
     * gives it what Instagram asked for, which ends any hold from the touch before. The call to the
     * pager is Instagram's own, so it fails the way Instagram's would; nothing else here throws.
     * {@code wanted} is non-zero for on.
     */
    public static void pagerInput(Object pager, int wanted) {
        pagerInput(pager, wanted != 0, INSTAGRAM);
    }

    static void pagerInput(Object pager, boolean wanted, Panels panels) {
        if (pager == null) return;
        HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
        tabsPager = new WeakReference<>(pager);
        forget(pager);
        panels.input(pager, wanted);
    }

    /**
     * Called first thing in the swipe container's touch check, with each event before the container's
     * children see it. At a touch's start, notes where the finger went down and gives the tabs' pager
     * back its input if HushGram held it, since Instagram doesn't set it on every touch. On Reels with
     * the switch on, once a lone finger has gone left by the touch slop, which is where the tabs'
     * pager would start its own drag, holds the tabs' pager until the next touch starts, so the
     * container can take the swipe to the profile panel. A swipe right, one that hasn't gone that
     * far, or a second finger leaves the pager on, so it still slides to Home. Never throws.
     */
    public static void touched(View container, MotionEvent event) {
        touched(container, event, INSTAGRAM, SwipeToProfile::reelsOpen);
    }

    static void touched(View container, MotionEvent event, Panels panels, BooleanSupplier on) {
        Object pager;
        try {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                oneFinger = true;
                letGo(panels);
                return;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) oneFinger = false;
            if (action != MotionEvent.ACTION_MOVE || !oneFinger) return;
            pager = tabsPager == null ? null : tabsPager.get();
            if (pager == null || downX - event.getRawX() < ViewConfiguration.get(container.getContext()).getScaledTouchSlop()) return;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PAGER, t);
            return;
        }
        try {
            if (!on.getAsBoolean()) return;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, SWITCH, t);
            return;
        }
        swiped();
        try {
            hold(pager, panels);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PAGER, t);
        }
    }

    /** Notes that a left swipe on a reel just went on to the profile, for {@link #profileResumed}. */
    private static void swiped() {
        swipePending = true;
        swipedAt = SystemClock.uptimeMillis();
    }

    /**
     * Called first thing in the profile's resume. Remembers it as the profile showing, and as the one
     * the swipe opened when a left swipe on a reel went on to the profile at most
     * {@link #OPENS_WITHIN_MS} ago and no touch has gone down on the profile it opened since. Never
     * throws.
     */
    public static void profileResumed(Object profile) {
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
            if (swipePending && SystemClock.uptimeMillis() - swipedAt <= OPENS_WITHIN_MS) {
                swipedProfile = new WeakReference<>(profile);
            }
            shownProfile = new WeakReference<>(profile);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, CLOSE, t);
        }
    }

    /** Called first thing in the profile's pause. It's no longer the profile showing. Never throws. */
    public static void profilePaused(Object profile) {
        if (shownProfile != null && shownProfile.get() == profile) shownProfile = null;
    }

    /**
     * Called first thing in every Instagram screen's touch dispatch, with each touch event before any
     * view sees it. Answers true for an event HushGram took, which the screen then answers as handled.
     * A touch is HushGram's once it goes down on the profile a left swipe on a reel opened, while that
     * profile shows and the switch is on, from a spot where nothing under the finger can scroll left,
     * and its lone finger then goes right by the touch slop, at least twice as far as up or down. The
     * views under it then get a cancel, and lifting it a tenth of the screen's width or more right of
     * where it went down goes back, closing the profile. A second finger swallows the rest of that
     * touch without going back. Every other touch is Instagram's. Never throws.
     */
    public static boolean closesProfile(Activity activity, MotionEvent event) {
        return closesProfile(activity, event, SwipeToProfile::switchedOn);
    }

    static boolean closesProfile(Activity activity, MotionEvent event, BooleanSupplier on) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            closing = IDLE;
            Object profile = shownProfile == null ? null : shownProfile.get();
            if (profile == null || swipedProfile == null || swipedProfile.get() != profile) return false;
            // A touch on it ends the swipe's window, so a profile opened from it, by a mention say, doesn't take its place.
            swipePending = false;
            try {
                HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
                if (!on.getAsBoolean()) return false;
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, SWITCH, t);
                return false;
            }
        } else if (closing == IDLE) {
            return false;
        }
        try {
            if (action == MotionEvent.ACTION_DOWN) {
                closing = ARMED;
                closeX = event.getX();
                closeY = event.getY();
                return false;
            }
            if (closing == ARMED) return claims(activity, event, action);
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                closing = SWALLOWING;
            } else if (action == MotionEvent.ACTION_UP) {
                boolean back = closing == CLOSING && event.getX() - closeX >= closeDistance(activity);
                closing = IDLE;
                if (back) activity.getWindow().getDecorView().post(() -> goBack(activity));
            } else if (action == MotionEvent.ACTION_CANCEL) {
                closing = IDLE;
            }
            return true;
        } catch (Throwable t) {
            closing = IDLE;
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, CLOSE, t);
            return false;
        }
    }

    /**
     * A move of an armed touch: once it's gone right by the touch slop and at least twice as far as
     * up or down, from a spot where nothing under the finger can scroll left, the touch becomes a
     * close, the views under it get a cancel and this answers true. Any other move past the slop, a
     * second finger or the touch ending gives the touch back. The views are asked here and not as the
     * touch went down: a Compose view answers for the last touch that went down on it, and only once
     * it was handed this one does it answer for this one.
     */
    private static boolean claims(Activity activity, MotionEvent event, int action) {
        if (action != MotionEvent.ACTION_MOVE) {
            closing = IDLE;
            return false;
        }
        float dx = event.getX() - closeX;
        float dy = Math.abs(event.getY() - closeY);
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        if (Math.abs(dx) < slop && dy < slop) return false;
        if (dx < slop || dx < 2 * dy || scrollsLeft(activity.getWindow().getDecorView(), closeX, closeY)) {
            closing = IDLE;
            return false;
        }
        closing = CLOSING;
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            activity.getWindow().superDispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
        if (!loggedClose) {
            loggedClose = true;
            Logger.printDebug(() -> "SwipeToProfile: took a right swipe to close the profile the reel opened");
        }
        return true;
    }

    /** How far right of where it went down a close has to lift to go back. */
    private static float closeDistance(Activity activity) {
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        return Math.max(2 * slop, activity.getResources().getDisplayMetrics().widthPixels / 10f);
    }

    /** Goes back, closing the profile, unless it stopped showing or its screen went away since the lift. */
    @SuppressWarnings("deprecation")
    private static void goBack(Activity activity) {
        try {
            Object profile = shownProfile == null ? null : shownProfile.get();
            boolean showing = profile != null && swipedProfile != null && swipedProfile.get() == profile;
            if (showing && !activity.isFinishing() && !activity.isDestroyed()) activity.onBackPressed();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, CLOSE, t);
        }
    }

    /** Whether {@code view}, or a shown view under (x, y) in its coordinates, can scroll left. */
    private static boolean scrollsLeft(View view, float x, float y) {
        if (view.canScrollHorizontally(-1)) return true;
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
            if (scrollsLeft(child, left - childX, top - childY)) return true;
        }
        return false;
    }

    /** Turns a pager's input off when it's on, and remembers it to turn back on. */
    private static void hold(Object pager, Panels panels) {
        if (!panels.inputOn(pager)) return;
        panels.input(pager, false);
        remember(pager);
    }

    private static void remember(Object pager) {
        for (int i = heldPagers.size() - 1; i >= 0; i--) {
            Object held = heldPagers.get(i).get();
            if (held == pager) return;
            if (held == null) heldPagers.remove(i);
        }
        heldPagers.add(new WeakReference<>(pager));
        if (!loggedPager) {
            loggedPager = true;
            Logger.printDebug(() -> "SwipeToProfile: held a pager so the swipe reaches the profile panel");
        }
    }

    /** Gives the tabs' pager its input back if HushGram held it. */
    private static void letGo(Panels panels) {
        Object pager = tabsPager == null ? null : tabsPager.get();
        if (pager == null || !held(pager)) return;
        forget(pager);
        panels.input(pager, true);
    }

    private static boolean held(Object pager) {
        for (WeakReference<Object> reference : heldPagers) {
            if (reference.get() == pager) return true;
        }
        return false;
    }

    private static void forget(Object pager) {
        for (int i = heldPagers.size() - 1; i >= 0; i--) {
            Object held = heldPagers.get(i).get();
            if (held == null || held == pager) heldPagers.remove(i);
        }
    }

    /** Gives each pager HushGram held its input back. */
    private static void release(Panels panels) {
        if (heldPagers.isEmpty()) return;
        List<WeakReference<Object>> held = new ArrayList<>(heldPagers);
        heldPagers.clear();
        for (WeakReference<Object> reference : held) {
            Object pager = reference.get();
            if (pager == null) continue;
            try {
                panels.input(pager, true);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PAGER, t);
            }
        }
    }

    /** Forgets that a setup built the right panel, which pager the tabs use and which profile a swipe opened, as a new process would. */
    static void forgetSetUpForTests() {
        builtRight = false;
        tabsPager = null;
        downX = 0;
        oneFinger = false;
        swipePending = false;
        swipedAt = 0;
        swipedProfile = null;
        shownProfile = null;
        closing = IDLE;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.REEL_SWIPE_TO_PROFILE.get();
    }

    private static boolean reelsOpen() {
        return reelsOpen(SwipeToProfile::switchedOn);
    }

    /** Whether the last tab change landed on Reels and the switch is on. */
    static boolean reelsOpen(BooleanSupplier on) {
        return onReels && on.getAsBoolean();
    }
}
