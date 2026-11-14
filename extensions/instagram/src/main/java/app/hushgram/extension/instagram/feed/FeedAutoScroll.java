/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.media.TapToPlayScope;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Auto scroll in Home" patch (#660): Home moves on to the next post by itself.
 *
 * <p>Home's list is Instagram's MainFeedRecyclerView, and each of its constructors hands the new
 * list to {@link #attach} as it returns. From then on, while the list is on screen and its window
 * has focus, a short timer looks at it. A post starts where its profile header is drawn, so the
 * next header below the top of the list is where the next post starts, and a move slides the list
 * until that header sits at the top, the way a short swipe would. Without a header further down
 * (a row of suggestions, say) it moves most of a screen.
 *
 * <p>A photo or a carousel moves on once it has sat still for the time picked under the switch
 * ({@link Settings#FEED_AUTO_SCROLL_SECONDS}). A post with a video in it waits for the video
 * instead. Instagram plays feed videos round and round, and its feed player hears from the player
 * underneath each time the video starts over ({@link #looped}) or ends ({@link #ended}), so the
 * first of those after the post settled moves it on. A video that never reports one moves on after
 * {@link #VIDEO_CAP_MS}. With Tap to play holding feed videos, a video post nobody tapped waits the
 * picked time like a photo, and one that was tapped waits for its video.
 *
 * <p>Every touch on an Instagram screen passes through {@link #touch} first. While a finger is
 * down nothing moves and a move already sliding stops, and the wait starts over once the last
 * finger lifts. The list also waits while it's moving on its own, while less than half of it is
 * on screen, and while something drawn over the middle of it (comments, a sheet, a menu) covers
 * it. Another window, a dialog or the app going to the background takes its focus, which stops
 * the timer until focus comes back.
 *
 * <p>Off, paused, before the settings are read, or when anything goes wrong, nothing moves and
 * Instagram's list is left as it is. The timer stops while the switch is off and starts again when
 * the list's window gets focus back or a finger lifts, so turning it on needs no restart.
 */
public final class FeedAutoScroll {
    static final String HOOK = "feed auto scroll";
    static final String COUNT_MOVED = "feed posts moved on";
    /** The profile header at the top of every post, the same view Hide the follow button reads. */
    static final String HEADER = FeedFollowButton.HEADER;

    /** How often a list on screen is looked at. */
    static final long TICK_MS = 250;
    /** How often a list that's hidden or covered is looked at again. */
    static final long IDLE_MS = 1000;
    /** How long the list must sit still before anything moves it. */
    static final long SETTLE_MS = 500;
    /** The longest a video post waits for its video to start over or end. */
    static final long VIDEO_CAP_MS = 200_000;
    /** How long one move takes. */
    static final long SLIDE_MS = 450;
    /** A finger down for longer than this is taken as a lift Instagram kept from the activity. */
    static final long FINGER_STALE_MS = 60_000;
    /** Of the list's height, how far a move goes when there's no header further down. */
    static final float PAGE = 0.85f;
    /** How far below the top of the list a header may sit and still count as the post shown. */
    static final int SLOP_DP = 8;

    static final int SCROLL_STATE_IDLE = 0;
    static final int SCROLL_STATE_SETTLING = 2;

    static final ResourceIdCache IDS = new ResourceIdCache();

    /** Lists a constructor handed in, held weakly so a list Instagram drops can go. */
    private static final Set<View> SEEN = Collections.newSetFromMap(new WeakHashMap<>());
    /** Runners of the lists attached to a window, main thread only. */
    private static final Set<Runner> ATTACHED = new LinkedHashSet<>();

    /** Uptime of the last time a feed video started over or ended. */
    static volatile long videoEventAt = Long.MIN_VALUE;
    static volatile boolean fingerDown;
    /** Uptime of the last touch event while a finger was down. */
    static volatile long fingerAt;
    /** Uptime of the last lift of the last finger. */
    static volatile long liftedAt = Long.MIN_VALUE;

    private FeedAutoScroll() {
    }

    /**
     * Injected before every return of each MainFeedRecyclerView constructor, with the new list.
     * Sets it up to be looked at once it's attached to a window. Never throws.
     */
    public static void attach(@Nullable View list) {
        try {
            HookStatus.invoked(FamilyNames.FEED_AUTO_SCROLL);
            if (list == null) return;
            // A list can be inflated off the main thread, so the set is guarded.
            synchronized (SEEN) {
                if (!SEEN.add(list)) return;
            }
            Runner runner = new Runner(list);
            list.addOnAttachStateChangeListener(runner);
            if (list.isAttachedToWindow()) runner.onViewAttachedToWindow(list);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_AUTO_SCROLL, "attach", failure);
        }
    }

    /**
     * Injected first thing in the feed video player's callback for a video starting over, with
     * the player and Instagram's count. Never throws.
     */
    public static void looped(@Nullable Object player, int count) {
        videoEvent("looped");
    }

    /** Injected first thing in the feed video player's callback for a video that ended. Never throws. */
    public static void ended(@Nullable Object player) {
        videoEvent("ended");
    }

    private static void videoEvent(String name) {
        try {
            HookStatus.invoked(FamilyNames.FEED_AUTO_SCROLL);
            if (!wanted()) return;
            videoEventAt = SystemClock.uptimeMillis();
            Utils.runOnMainThread(FeedAutoScroll::lookNow);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_AUTO_SCROLL, name, failure);
        }
    }

    /**
     * Injected first thing in IgFragmentActivity's dispatchTouchEvent, which every touch on an
     * Instagram screen passes through. Only reads the event, and never throws.
     */
    public static void touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            HookStatus.invoked(FamilyNames.FEED_AUTO_SCROLL);
            if (event == null || !wanted()) return;
            long now = SystemClock.uptimeMillis();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    fingerDown = true;
                    fingerAt = now;
                    for (Runner runner : new ArrayList<>(ATTACHED)) runner.stopSlide();
                    break;
                case MotionEvent.ACTION_MOVE:
                    fingerAt = now;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    fingerDown = false;
                    liftedAt = now;
                    for (Runner runner : new ArrayList<>(ATTACHED)) runner.restart(now);
                    break;
                default:
                    break;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_AUTO_SCROLL, "touch", failure);
        }
    }

    static boolean wanted() {
        return Utils.settingsReady() && Settings.FEED_AUTO_SCROLL.get();
    }

    private static void lookNow() {
        for (Runner runner : new ArrayList<>(ATTACHED)) runner.schedule(0);
    }

    /**
     * Whether the post shown moves on now. Nothing moves until the list has sat still for
     * {@link #SETTLE_MS}. A post waiting for its video moves on at the first start over or end
     * reported after it settled, or after {@link #VIDEO_CAP_MS}; any other post after [dwellMs].
     */
    static boolean decide(long now, long settledAt, boolean waitsForVideo, long videoEventAt, long dwellMs) {
        long still = now - settledAt;
        if (still < SETTLE_MS) return false;
        if (waitsForVideo) return videoEventAt > settledAt || still >= VIDEO_CAP_MS;
        return still >= Math.max(dwellMs, SETTLE_MS);
    }

    /**
     * Of the header tops [tops] (from the top of the list), the post shown and the next one: the
     * last header at or above [align] + [slop], else {@link Integer#MIN_VALUE}, and the first below
     * it, else {@link Integer#MAX_VALUE}.
     */
    static int[] split(int[] tops, int align, int slop) {
        int[] sorted = tops.clone();
        Arrays.sort(sorted);
        int current = Integer.MIN_VALUE;
        int next = Integer.MAX_VALUE;
        for (int top : sorted) {
            if (top <= align + slop) {
                current = top;
            } else {
                next = top;
                break;
            }
        }
        return new int[]{current, next};
    }

    /** How far a move goes: to the next header at [next], or [PAGE] of the list's [inner] height. */
    static int distance(int next, int align, int inner) {
        if (next != Integer.MAX_VALUE) return next - align;
        return (int) (inner * PAGE);
    }

    /** Whether Tap to play holds feed videos, so one nobody tapped never plays to its end. */
    static boolean tapToPlayHolds() {
        try {
            if (!Settings.TAP_TO_PLAY.get()) return false;
            TapToPlayScope scope = Settings.TAP_TO_PLAY_SCOPE.get();
            return scope == null || scope.covers(false);
        } catch (Throwable unknown) {
            return false;
        }
    }

    /** The post shown: where the next one starts, and whether a video is part of this one. */
    static final class Posts {
        final int next;
        final boolean video;

        Posts(int next, boolean video) {
            this.next = next;
            this.video = video;
        }
    }

    /** Reads the headers in [list], or null when this build has no header id. */
    @Nullable
    static Posts read(View list) {
        if (!(list instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) list;
        int id = IDS.resolve(list.getResources(), Utils.resourcePackage(list.getContext()), HEADER, false);
        if (id == 0) {
            HookStatus.missingViewId(FamilyNames.FEED_AUTO_SCROLL, HEADER);
            return null;
        }
        int[] listAt = new int[2];
        list.getLocationInWindow(listAt);
        int[] at = new int[2];
        int[] tops = new int[group.getChildCount()];
        int found = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View header = group.getChildAt(i).findViewById(id);
            if (header == null || !header.isShown()) continue;
            header.getLocationInWindow(at);
            tops[found++] = at[1] - listAt[1];
        }
        int align = list.getPaddingTop();
        int[] shown = split(Arrays.copyOf(tops, found), align, slop(list));
        int from = shown[0] == Integer.MIN_VALUE ? align : shown[0];
        int to = shown[1] == Integer.MAX_VALUE ? list.getHeight() : shown[1];
        return new Posts(shown[1], hasVideo(group, listAt[1], from, to));
    }

    private static int slop(View list) {
        return Math.round(SLOP_DP * list.getResources().getDisplayMetrics().density);
    }

    /** Whether a video surface on screen sits at least half inside [from, to) of the list. */
    private static boolean hasVideo(ViewGroup group, int listTop, int from, int to) {
        int[] at = new int[2];
        ArrayList<View> pending = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
        while (!pending.isEmpty()) {
            View view = pending.remove(pending.size() - 1);
            if (view.getVisibility() != View.VISIBLE) continue;
            if (view instanceof ViewGroup) {
                ViewGroup inner = (ViewGroup) view;
                for (int i = 0; i < inner.getChildCount(); i++) pending.add(inner.getChildAt(i));
                continue;
            }
            if (!isVideoSurface(view) || view.getHeight() <= 0) continue;
            view.getLocationInWindow(at);
            int top = at[1] - listTop;
            int bottom = top + view.getHeight();
            int inside = Math.min(bottom, to) - Math.max(top, from);
            if (inside * 2 >= view.getHeight()) return true;
        }
        return false;
    }

    private static boolean isVideoSurface(View view) {
        if (view instanceof SurfaceView) return view.isShown();
        return view instanceof TextureView && view.isShown() && view.getAlpha() > 0f && ((TextureView) view).isAvailable();
    }

    /** Whether at least half of [list] is on screen in a visible window. */
    static boolean onScreen(View list, Rect visible) {
        if (!list.isShown() || list.getWindowVisibility() != View.VISIBLE || list.getHeight() <= 0) return false;
        return list.getGlobalVisibleRect(visible) && visible.height() * 2 >= list.getHeight();
    }

    private static final int NONE = 0;
    private static final int LIST = 1;
    private static final int COVER = 2;

    /** Whether something drawn above [list] covers the middle of its [visible] part. */
    static boolean covered(View list, Rect visible) {
        return topAt(list.getRootView(), visible.centerX(), visible.centerY(), list, new int[2]) == COVER;
    }

    /** What's drawn on top at (x, y) in window coordinates, walking [view]'s children from the top. */
    private static int topAt(View view, int x, int y, View list, int[] at) {
        if (view == list) return LIST;
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return NONE;
        view.getLocationInWindow(at);
        if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) return NONE;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                int found = topAt(group.getChildAt(i), x, y, list, at);
                if (found != NONE) return found;
            }
        }
        return paints(view) ? COVER : NONE;
    }

    /** Whether [view] draws anything: a background that isn't clear, or content of its own. */
    static boolean paints(View view) {
        Drawable background = view.getBackground();
        if (background != null) {
            boolean clear = background instanceof ColorDrawable
                    && (Color.alpha(((ColorDrawable) background).getColor()) == 0 || background.getAlpha() == 0);
            if (!clear) return true;
        }
        if (view instanceof ViewGroup || view.getClass() == View.class) return false;
        if (view instanceof ImageView) return ((ImageView) view).getDrawable() != null;
        if (view instanceof TextView) return !TextUtils.isEmpty(((TextView) view).getText());
        return true;
    }

    /** One Home list, looked at on the main thread while it's attached. */
    static final class Runner implements Runnable, View.OnAttachStateChangeListener,
            ViewTreeObserver.OnWindowFocusChangeListener {
        final View list;
        private final Rect visible = new Rect();
        long settledAt;
        @Nullable private View anchor;
        private int anchorTop;
        @Nullable ValueAnimator slide;
        @Nullable private String waiting;
        @Nullable private Method getScrollState;
        @Nullable private Method setScrollState;
        private boolean scrollStateRead;

        Runner(View list) {
            this.list = list;
            this.settledAt = SystemClock.uptimeMillis();
        }

        void schedule(long delay) {
            list.removeCallbacks(this);
            list.postDelayed(this, delay);
        }

        void stop() {
            list.removeCallbacks(this);
            stopSlide();
        }

        /** The last finger lifted at [now]: the wait starts over. */
        void restart(long now) {
            settledAt = now;
            if (list.isAttachedToWindow()) schedule(TICK_MS);
        }

        void stopSlide() {
            ValueAnimator running = slide;
            if (running != null) running.cancel();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            try {
                ATTACHED.add(this);
                ViewTreeObserver observer = list.getViewTreeObserver();
                if (observer.isAlive()) observer.addOnWindowFocusChangeListener(this);
                settledAt = SystemClock.uptimeMillis();
                schedule(TICK_MS);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_AUTO_SCROLL, "list attached", failure);
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            try {
                ATTACHED.remove(this);
                ViewTreeObserver observer = list.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(this);
                stop();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_AUTO_SCROLL, "list detached", failure);
            }
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            try {
                if (hasFocus) {
                    settledAt = SystemClock.uptimeMillis();
                    schedule(TICK_MS);
                } else {
                    stop();
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_AUTO_SCROLL, "focus", failure);
            }
        }

        @Override
        public void run() {
            try {
                look();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_AUTO_SCROLL, HOOK, failure);
                stop();
            }
        }

        private void look() {
            // Off, detached or out of focus: stop until focus, a lift or a reattach starts it again.
            if (!wanted() || !list.isAttachedToWindow() || !list.hasWindowFocus()) {
                waitFor(null);
                stop();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (slide != null) {
                schedule(TICK_MS);
                return;
            }
            if (fingerDown && now - fingerAt < FINGER_STALE_MS) {
                hold(now, "a finger", TICK_MS);
                return;
            }
            if (!onScreen(list, visible)) {
                hold(now, "off screen", IDLE_MS);
                return;
            }
            if (covered(list, visible)) {
                hold(now, "covered", IDLE_MS);
                return;
            }
            if (scrollState() != SCROLL_STATE_IDLE) {
                hold(now, "moving", TICK_MS);
                return;
            }
            Posts posts = read(list);
            if (posts == null) {
                hold(now, "no header id", IDLE_MS);
                return;
            }
            View first = ((ViewGroup) list).getChildCount() > 0 ? ((ViewGroup) list).getChildAt(0) : null;
            int firstTop = first == null ? 0 : first.getTop();
            if (first != anchor || firstTop != anchorTop) {
                anchor = first;
                anchorTop = firstTop;
                settledAt = now;
            }
            waitFor(null);
            boolean waitsForVideo = posts.video && (!tapToPlayHolds() || liftedAt >= settledAt);
            long dwell = Math.max(1, Settings.FEED_AUTO_SCROLL_SECONDS.get()) * 1000L;
            if (decide(now, settledAt, waitsForVideo, videoEventAt, dwell)) {
                int inner = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
                int by = distance(posts.next, list.getPaddingTop(), inner);
                if (by > 0 && list.canScrollVertically(1)) {
                    slide(by);
                    HookStatus.counted(FamilyNames.FEED_AUTO_SCROLL, COUNT_MOVED);
                } else {
                    settledAt = now;
                }
            }
            schedule(TICK_MS);
        }

        private void hold(long now, String why, long next) {
            settledAt = now;
            waitFor(why);
            schedule(next);
        }

        private void waitFor(@Nullable String why) {
            if (TextUtils.equals(why, waiting)) return;
            waiting = why;
            if (why != null) Logger.printInfo(() -> "Feed auto scroll: waiting, " + why);
        }

        /** Slides the list [by] pixels down its content, reporting the move as Instagram's own fling settling. */
        private void slide(int by) {
            ValueAnimator animator = ValueAnimator.ofInt(0, by);
            animator.setDuration(SLIDE_MS);
            animator.setInterpolator(new DecelerateInterpolator());
            int[] done = {0};
            animator.addUpdateListener(running -> {
                try {
                    int to = (Integer) running.getAnimatedValue();
                    int step = to - done[0];
                    if (step != 0) {
                        done[0] = to;
                        list.scrollBy(0, step);
                    }
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.FEED_AUTO_SCROLL, "slide", failure);
                    running.cancel();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator ended) {
                    if (slide != ended) return;
                    slide = null;
                    settledAt = SystemClock.uptimeMillis();
                    scrollState(SCROLL_STATE_IDLE);
                }
            });
            slide = animator;
            scrollState(SCROLL_STATE_SETTLING);
            animator.start();
        }

        /** The list's scroll state, or idle when this build doesn't say. */
        private int scrollState() {
            findScrollState();
            Method read = getScrollState;
            if (read == null) return SCROLL_STATE_IDLE;
            try {
                Object state = read.invoke(list);
                return state instanceof Integer ? (Integer) state : SCROLL_STATE_IDLE;
            } catch (Throwable unread) {
                return SCROLL_STATE_IDLE;
            }
        }

        private void scrollState(int state) {
            findScrollState();
            Method write = setScrollState;
            if (write == null) return;
            try {
                write.invoke(list, state);
            } catch (Throwable unwritten) {
                Logger.printDebug(() -> "Feed auto scroll: scroll state not set");
            }
        }

        /** RecyclerView's getScrollState() and setScrollState(int), found once on the list's class or above. */
        private void findScrollState() {
            if (scrollStateRead) return;
            scrollStateRead = true;
            for (Class<?> type = list.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
                try {
                    if (getScrollState == null) {
                        Method read = type.getDeclaredMethod("getScrollState");
                        if (read.getReturnType() == int.class) {
                            read.setAccessible(true);
                            getScrollState = read;
                        }
                    }
                } catch (NoSuchMethodException notHere) {
                    // Declared further up.
                }
                try {
                    if (setScrollState == null) {
                        Method write = type.getDeclaredMethod("setScrollState", int.class);
                        write.setAccessible(true);
                        setScrollState = write;
                    }
                } catch (NoSuchMethodException notHere) {
                    // Declared further up.
                }
            }
            String owner = list.getClass().getName();
            if (getScrollState == null) {
                HookStatus.missingMember(FamilyNames.FEED_AUTO_SCROLL, "method", owner, "getScrollState");
            }
            if (setScrollState == null) {
                HookStatus.missingMember(FamilyNames.FEED_AUTO_SCROLL, "method", owner, "setScrollState");
            }
        }
    }

    /** The lists attached now, for tests. */
    static Set<Runner> attachedForTests() {
        return ATTACHED;
    }

    /** Forgets every list and touch, for tests. */
    static void resetForTests() {
        for (Runner runner : new ArrayList<>(ATTACHED)) runner.stop();
        ATTACHED.clear();
        synchronized (SEEN) {
            SEEN.clear();
        }
        IDS.clear();
        videoEventAt = Long.MIN_VALUE;
        fingerDown = false;
        fingerAt = 0;
        liftedAt = Long.MIN_VALUE;
    }
}
