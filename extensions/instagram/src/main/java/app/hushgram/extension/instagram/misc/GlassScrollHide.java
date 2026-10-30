/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.download.HoldDownload;
import app.hushgram.extension.instagram.reels.BurnInGuard;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * "Hide the tab bar as you scroll" for the glass tab bar: scrolling a list down slides the pill off the
 * bottom of the screen, and scrolling up, reaching the top or changing tab brings it back.
 *
 * <p>It changes no Instagram code and looks for no class of Instagram's. The direction comes from the
 * finger: the window's callback is wrapped so it sees every touch before the screen does and passes each
 * on untouched. A drag has to be mostly vertical and go further than {@link #SLOP_DP} before it counts, and
 * it flips only when the finger turns back by as much, so a jitter never makes the bar flicker. Which list
 * is moving comes from the view under the finger when it went down: the innermost view there that can
 * scroll vertically at all ({@link View#canScrollVertically}, which every scrolling view answers, whatever
 * its class). The bar only hides when that list is already away from its top, so a drag on a screen that
 * isn't a list, or a pull at the top of one, never hides it. Nothing inside a Reels viewer hides it either.
 *
 * <p>The bar is only ever moved with a translation, never resized and never hidden, so nothing under it
 * shifts: with "Show content behind the tab bar" off, the strip it leaves is the window's own background.
 * On Instagram's own bar, with the glass off, a {@link Room} gives that strip to the screens instead (#343).
 *
 * <p>It can't strand the bar. While the bar is away a short timer looks at the list that hid it, and
 * brings the bar back as soon as that list is at its top, no longer on screen, gone, or the feature has been
 * paused. A tab change, Back, the screen going to the background and the bar leaving the window do the same
 * at once. The touch wrapper does nothing while HushGram is paused.
 *
 * <p>"Hide the tab bar on Reels" (#170) is a second way out with a setting of its own, since no scroll hides
 * the bar on a reel. There a wait does it: it starts over when a touch ends, on Back and when the screen
 * comes to the front, and if it runs out with the Reels viewer on screen and no finger down, the bar slides
 * away the same way. A swipe to another reel leaves it away, whichever way the finger goes. A tap where the
 * bar sits brings it back as the finger lifts, and the wait starts again then. A drag there is left to the
 * reel, whose seek bar sits in the bar's place while it's away (#221), and the bar stays away. While the bar
 * is away for a reel the timer looks at the viewer in place of a list, and brings the bar back as soon as
 * the viewer isn't the screen showing. The wait is one delayed call, dropped whenever the bar is put back
 * at once, and never made with the choice on Never.
 *
 * <p>With the glass bar's liquid look (#65) and this switch off, a scroll down shrinks the bar to its compact
 * row through a {@link Shrinker} where it would have slid away. Everything that brings a slid bar back grows
 * it again, and so does a finger put down on the row.
 */
final class GlassScrollHide {
    /** How far a drag must go, and how far it must turn back to flip, in dp. */
    static final int SLOP_DP = 24;
    static final long SLIDE_MS = 220;
    /** How often the list that hid the bar is looked at while the bar is away. */
    static final long WATCH_MS = 120;
    /** Past this share of the content's width and height, a video is a full-screen pager, which keeps its bar. */
    static final float FULL_SCREEN_SHARE = 0.75f;
    /** A Reels viewer is the screen showing across {@link #FULL_SCREEN_SHARE} of the window's width and this share of its height. */
    static final float REELS_HEIGHT_SHARE = 0.5f;
    /** Counted names in the glass family's Diagnostics line. */
    static final String COUNT_HID = "scroll hid the bar";
    static final String COUNT_SHOWED = "scroll showed the bar";
    static final String COUNT_RESTORED = "bar restored by the guard";
    static final String COUNT_REELS_HID = "reels hid the bar";
    static final String COUNT_TAPPED_BACK = "tap brought the bar back";
    static final String COUNT_SHRANK = "scroll shrank the bar";

    /** What shrinks the bar to a compact row where a scroll would slide it away: the liquid look (#65). */
    interface Shrinker {
        /** Shrinks the bar, or grows it back when not [on], at once when [now]. Says whether it did. */
        boolean shrink(boolean on, boolean now);
    }

    /**
     * What hands the bar's room to the screens while a scroll has it away, so Instagram's own bar leaves no
     * empty strip behind it (#343). Told as the bar starts to slide away, and again once it's all the way back.
     */
    interface Room {
        void away(boolean away);
    }

    /** What a drag says about the bar. */
    static final int KEEP = 0;
    static final int HIDE = 1;
    static final int SHOW = -1;

    /**
     * One finger's drag, reduced to a direction. Plain numbers in and out, so it is tested without a screen.
     */
    static final class Drag {
        private boolean active;
        private boolean abandoned;
        private float downX, downY, extreme;
        private int direction = KEEP;

        void start(float x, float y) {
            active = true;
            abandoned = false;
            downX = x;
            downY = y;
            extreme = y;
            direction = KEEP;
        }

        void cancel() {
            active = false;
            direction = KEEP;
        }

        boolean isActive() {
            return active && !abandoned;
        }

        /**
         * The direction the drag has settled on after the finger reached [x], [y]: {@link #HIDE} for a finger
         * moving up (the list scrolls down), {@link #SHOW} for one moving down, {@link #KEEP} until it has
         * gone [slop], or when it went sideways first.
         */
        int move(float x, float y, float slop) {
            if (!active || abandoned) return KEEP;
            if (direction == KEEP) {
                float dy = y - downY;
                if (Math.abs(dy) < slop) return KEEP;
                if (Math.abs(x - downX) > Math.abs(dy)) {
                    abandoned = true;
                    return KEEP;
                }
                direction = dy < 0 ? HIDE : SHOW;
                extreme = y;
                return direction;
            }
            if (direction == HIDE) {
                extreme = Math.min(extreme, y);
                if (y - extreme >= slop) {
                    direction = SHOW;
                    extreme = y;
                }
            } else {
                extreme = Math.max(extreme, y);
                if (extreme - y >= slop) {
                    direction = HIDE;
                    extreme = y;
                }
            }
            return direction;
        }
    }

    private final View bar;
    private final BooleanSupplier running;
    /** Whether a scroll hides the bar: "Hide the tab bar as you scroll". */
    private final boolean onScroll;
    /** How long the Reels screen goes untouched before the bar slides away, or 0 for never. */
    private final long reelsMillis;
    private final float density;
    private final float slop;
    /** How far a finger may move and still be a tap, the system's own touch slop. */
    private final float touchSlop;
    private final Drag drag = new Drag();
    private final Rect rect = new Rect();
    private final Rect other = new Rect();
    private final int[] place = new int[2];
    @Nullable private WeakReference<View> scroller;
    /** Whether this drag's list is still to be found, at its first move, under where the finger went down. */
    private boolean findScrollerAtMove;
    private int downX, downY;
    /** What the bar is away for, a list or a Reels viewer: the one the timer watches until the bar is back. */
    @Nullable private WeakReference<View> guard;
    private boolean hidden;
    /** Whether the wait on Reels hid the bar, not a scroll. A scroll back up doesn't show it then. */
    private boolean byReels;
    /**
     * Whether this moved the bar and hasn't put it all the way back yet. Instagram may move its own
     * bar, so a restore only undoes what this did.
     */
    private boolean moved;
    /** Whether this drag has been refused for a full-screen video, so the screen isn't walked again until the next one. */
    private boolean refused;
    /** Whether a window's watcher hands this its touches. Without them nothing could bring the bar back, so no wait starts. */
    private boolean installed;
    private boolean fingerDown;
    /**
     * Whether the finger down went where the bar sits while it's away for a reel and hasn't moved off as a
     * drag yet, so its lift is a tap that brings the bar back.
     */
    private boolean tapAtHome;
    private float homeDownX, homeDownY;
    /** What shrinks the bar in place of a scroll sliding it away, or null. The wait on Reels still slides it. */
    @Nullable private final Shrinker shrinker;
    /** What takes the bar's room while it's away, or null. */
    @Nullable private final Room room;
    /** Whether [room] has the bar's room now. */
    private boolean roomGiven;
    /** Whether a scroll shrank the bar, in place of sliding it away. */
    private boolean shrunk;
    private final Runnable watch = this::watchScroller;
    private final Runnable reelsWait = this::waitedOnReels;

    /**
     * Hides on a scroll only.
     *
     * @param bar     the tab bar
     * @param running whether the feature is allowed right now: false while HushGram is paused
     */
    GlassScrollHide(View bar, BooleanSupplier running) {
        this(bar, running, true, 0L);
    }

    /**
     * @param bar         the tab bar
     * @param running     whether the feature is allowed right now: false while HushGram is paused
     * @param onScroll    whether scrolling a list down hides the bar
     * @param reelsMillis how long the Reels screen goes untouched before the bar slides away, or 0 for never
     */
    GlassScrollHide(View bar, BooleanSupplier running, boolean onScroll, long reelsMillis) {
        this(bar, running, onScroll, reelsMillis, null);
    }

    /**
     * @param shrinker what shrinks the bar to a compact row where a scroll would slide it away, or null to slide it
     */
    GlassScrollHide(View bar, BooleanSupplier running, boolean onScroll, long reelsMillis, @Nullable Shrinker shrinker) {
        this(bar, running, onScroll, reelsMillis, shrinker, null);
    }

    /**
     * @param room what takes the bar's room while a scroll has it away, or null to leave the screens as they are
     */
    GlassScrollHide(View bar, BooleanSupplier running, boolean onScroll, long reelsMillis, @Nullable Shrinker shrinker,
            @Nullable Room room) {
        this.bar = bar;
        this.room = room;
        this.running = running;
        this.onScroll = onScroll;
        this.reelsMillis = Math.max(0L, reelsMillis);
        this.shrinker = shrinker;
        this.density = bar.getResources().getDisplayMetrics().density;
        this.touchSlop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();
        this.slop = Math.max(SLOP_DP * density, touchSlop);
    }

    boolean isHidden() {
        return hidden;
    }

    /** Whether the bar has slid all the way off, so nothing needs drawing behind it. A shrunk bar is still on screen. */
    boolean isOffScreen() {
        return hidden && !shrunk && bar.getTranslationY() >= travel() - 1f;
    }

    /** Whether a scroll shrank the bar to its compact row, in place of sliding it away. */
    boolean isShrunk() {
        return shrunk;
    }

    private float travel() {
        return Math.max(bar.getHeight(), 48 * density) + 8 * density;
    }

    /**
     * Wraps [window]'s callback so [hide] sees its touches and Back keys. Once per window: a window
     * already wrapped for an earlier bar (one Instagram rebuilt) hands its touches to [hide] instead,
     * after putting the earlier bar back. The wait on Reels starts here, and the earlier bar's is over.
     */
    static void install(Window window, GlassScrollHide hide) {
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        // Hold to download's, Burn-in guard's and the tab slide's watchers may have wrapped this one
        // since, in any order, so they're looked through.
        for (Window.Callback seen = inner; seen != null; ) {
            if (Proxy.isProxyClass(seen.getClass()) && Proxy.getInvocationHandler(seen) instanceof Watcher) {
                Watcher watcher = (Watcher) Proxy.getInvocationHandler(seen);
                if (watcher.hide != hide) {
                    watcher.hide.restoreNow();
                    watcher.hide.installed = false;
                    watcher.hide = hide;
                }
                hide.installed = true;
                hide.arm();
                return;
            }
            Window.Callback next = GlassTabSlide.innerOf(BurnInGuard.innerOf(HoldDownload.innerOf(seen)));
            if (next == seen) break;
            seen = next;
        }
        window.setCallback((Window.Callback) Proxy.newProxyInstance(GlassScrollHide.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class}, new Watcher(inner, hide)));
        hide.installed = true;
        hide.arm();
    }

    /**
     * Passes every call on to the callback it wraps, whatever the call is, so a method added in a newer Android
     * or a callback the app swaps in behaves as it did. It looks at touches and the Back key on the way.
     */
    private static final class Watcher implements InvocationHandler {
        private final Window.Callback inner;
        /** The bar's hider now: a rebuilt bar takes over the window's watcher. Touched on the main thread only. */
        private GlassScrollHide hide;

        Watcher(Window.Callback inner, GlassScrollHide hide) {
            this.inner = inner;
            this.hide = hide;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1) {
                try {
                    if (args[0] instanceof MotionEvent && "dispatchTouchEvent".equals(method.getName())) {
                        hide.onTouch((MotionEvent) args[0]);
                    } else if (args[0] instanceof KeyEvent && "dispatchKeyEvent".equals(method.getName())
                            && ((KeyEvent) args[0]).getKeyCode() == KeyEvent.KEYCODE_BACK) {
                        hide.onBack();
                    }
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "scroll hide", failure);
                    hide.restoreNow();
                }
            }
            try {
                return method.invoke(inner, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }

    void onTouch(MotionEvent event) {
        if (!running.getAsBoolean()) {
            if (hidden) restoreNow();
            disarm();
            fingerDown = false;
            tapAtHome = false;
            drag.cancel();
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fingerDown = true;
                disarm();
                refused = false;
                tapAtHome = false;
                if (byReels && atHome(event)) {
                    // Away for a reel, and a finger where the bar sits. The reel's seek bar is there now (#221),
                    // so only a tap wants the bar back, at its lift. Moving the reel up under a finger that
                    // went down on the seek bar lost the drag. That is all this touch does.
                    drag.cancel();
                    tapAtHome = true;
                    homeDownX = event.getRawX();
                    homeDownY = event.getRawY();
                    return;
                }
                if (shrunk && onBar(event)) {
                    // A finger on the compact row wants the whole bar back. The touch still goes on to the tab under it.
                    drag.cancel();
                    slideBack(COUNT_TAPPED_BACK);
                    return;
                }
                if (!onScroll || !bar.isAttachedToWindow() || onBar(event)) {
                    drag.cancel();
                    return;
                }
                // The list is looked for at the first move. A Compose list answers canScrollVertically
                // from the last down it was handed, and this runs before the window hands it this one,
                // so on a screen just opened it said it couldn't scroll (#65, 451's Activity tab).
                scroller = null;
                findScrollerAtMove = true;
                downX = Math.round(event.getRawX());
                downY = Math.round(event.getRawY());
                drag.start(event.getRawX(), event.getRawY());
                break;
            case MotionEvent.ACTION_MOVE:
                if (tapAtHome && Math.hypot(event.getRawX() - homeDownX, event.getRawY() - homeDownY) > touchSlop) {
                    tapAtHome = false;
                }
                if (!drag.isActive()) return;
                if (findScrollerAtMove) {
                    findScrollerAtMove = false;
                    View found = findScroller(bar.getRootView(), downX, downY, 0);
                    scroller = found == null ? null : new WeakReference<>(found);
                }
                int direction = drag.move(event.getRawX(), event.getRawY(), slop);
                if (direction == SHOW) {
                    refused = false;
                    // A bar away for a reel stays away whichever way the reels are swiped.
                    if (!byReels) show();
                } else if (direction == HIDE) {
                    hideIfScrolled();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                drag.cancel();
                tapAtHome = false;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag.cancel();
                fingerDown = false;
                if (tapAtHome && event.getActionMasked() == MotionEvent.ACTION_UP) slideBack(COUNT_TAPPED_BACK);
                tapAtHome = false;
                arm();
                break;
            default:
                break;
        }
    }

    private boolean onBar(MotionEvent event) {
        return bar.getGlobalVisibleRect(rect) && rect.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }

    /**
     * Whether the touch is where the bar sits when nothing has moved it: its place in its parent, on screen,
     * without its own translation. A bar slid away takes no touch there itself.
     */
    private boolean atHome(MotionEvent event) {
        if (!bar.isAttachedToWindow() || !(bar.getParent() instanceof View)) return false;
        View parent = (View) bar.getParent();
        parent.getLocationOnScreen(place);
        int left = place[0] + bar.getLeft() - parent.getScrollX();
        int top = place[1] + bar.getTop() - parent.getScrollY();
        rect.set(left, top, left + bar.getWidth(), top + bar.getHeight());
        return rect.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }

    /** Back brings the bar back, and starts the wait on Reels over. */
    void onBack() {
        show();
        arm();
    }

    /**
     * Starts the wait on Reels over: after a touch, after Back, and as the bar's screen comes to the front.
     * Nothing waits with the choice on Never, without a window's watcher, while HushGram is paused, or
     * while the bar is already away for a reel.
     */
    void arm() {
        if (reelsMillis <= 0 || !installed) return;
        bar.removeCallbacks(reelsWait);
        if (byReels || !running.getAsBoolean()) return;
        bar.postDelayed(reelsWait, reelsMillis);
    }

    private void disarm() {
        if (reelsMillis > 0) bar.removeCallbacks(reelsWait);
    }

    /** The wait ran out: the bar slides away if a reel is what's showing and nothing is touching the screen. */
    private void waitedOnReels() {
        try {
            if (hidden || fingerDown || !running.getAsBoolean() || !bar.isAttachedToWindow() || !bar.isShown()) return;
            View viewer = reelsOnScreen();
            if (viewer == null) return;
            slideAway(new WeakReference<>(viewer), true, COUNT_REELS_HID);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "reels hide", failure);
            restoreNow();
        }
    }

    /** The Reels viewer ({@link GlassTabBar#REELS_ROOT}) that is the screen showing, or null. */
    @Nullable
    private View reelsOnScreen() {
        int reels = GlassTabBar.id(bar.getContext(), GlassTabBar.REELS_ROOT);
        return reels == 0 ? null : findReels(bar.getRootView(), reels, 0);
    }

    @Nullable
    private View findReels(View view, int reels, int depth) {
        if (view == bar || view.getVisibility() != View.VISIBLE || depth > 60) return null;
        if (view.getId() == reels) return reelsShowing(view) ? view : null;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View inside = findReels(group.getChildAt(i), reels, depth + 1);
            if (inside != null) return inside;
        }
        return null;
    }

    /**
     * Whether [viewer] is the screen showing: in the window, shown, and across most of it. A viewer kept
     * beside the screen in a pager says it's shown too, so where it is counts.
     */
    private boolean reelsShowing(View viewer) {
        if (!viewer.isAttachedToWindow() || !viewer.isShown() || !viewer.getGlobalVisibleRect(other)) return false;
        View root = bar.getRootView();
        return other.width() >= root.getWidth() * FULL_SCREEN_SHARE
                && other.height() >= root.getHeight() * REELS_HEIGHT_SHARE;
    }

    /**
     * The innermost view under the point that holds others and can scroll up or down, or null. The bar's own
     * branch is skipped. A text cut short says it can scroll down too, though no finger scrolls it, and as the
     * innermost it would stand in for the list (#65: Activity's rows on 451 never shrank the bar).
     */
    @Nullable
    private View findScroller(View view, int x, int y, int depth) {
        if (view == bar || view.getVisibility() != View.VISIBLE || depth > 60) return null;
        if (!view.getGlobalVisibleRect(other) || !other.contains(x, y)) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View inside = findScroller(group.getChildAt(i), x, y, depth + 1);
                if (inside != null) return inside;
            }
        }
        return view instanceof ViewGroup && (view.canScrollVertically(-1) || view.canScrollVertically(1)) ? view : null;
    }

    private void hideIfScrolled() {
        if (hidden || refused || !bar.isAttachedToWindow()) return;
        View list = scroller == null ? null : scroller.get();
        // A list still at its top has not been scrolled down, however far a finger has gone.
        if (list == null || !list.isShown() || !list.canScrollVertically(-1)) return;
        if (inReels(list) || fullScreenVideo()) {
            refused = true;
            return;
        }
        slideAway(scroller, false, COUNT_HID);
    }

    /**
     * Slides the bar off the bottom and starts the timer that watches [held], the list or the Reels viewer
     * the bar is away for. Only its translation changes. With a shrinker, a scroll shrinks the bar to its
     * compact row instead, and when it can't, the bar stays as it is for the rest of this drag.
     */
    private void slideAway(@Nullable WeakReference<View> held, boolean reels, String counted) {
        if (!reels && shrinker != null) {
            if (!shrinker.shrink(true, false)) {
                refused = true;
                return;
            }
            shrunk = true;
            counted = COUNT_SHRANK;
        } else {
            moved = true;
            bar.animate().cancel();
            bar.animate().translationY(travel()).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).start();
            giveRoom(true);
        }
        guard = held;
        byReels = reels;
        hidden = true;
        bar.removeCallbacks(watch);
        bar.postDelayed(watch, WATCH_MS);
        HookStatus.counted(FamilyNames.GLASS_TAB_BAR, counted);
    }

    /** Brings the bar back, sliding. Always allowed, and does nothing when it never left. */
    void show() {
        slideBack(COUNT_SHOWED);
    }

    private void slideBack(String counted) {
        if (!hidden) return;
        hidden = false;
        byReels = false;
        bar.removeCallbacks(watch);
        if (shrunk) {
            shrunk = false;
            shrinker.shrink(false, false);
            HookStatus.counted(FamilyNames.GLASS_TAB_BAR, counted);
            return;
        }
        bar.animate().cancel();
        // The screens keep the room until the bar covers it again, so nothing shows through as it slides back.
        bar.animate().translationY(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    moved = hidden;
                    if (!hidden) giveRoom(false);
                }).start();
        HookStatus.counted(FamilyNames.GLASS_TAB_BAR, counted);
    }

    private void giveRoom(boolean away) {
        if (room == null || roomGiven == away) return;
        roomGiven = away;
        try {
            room.away(away);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "scroll room", failure);
        }
    }

    /**
     * Brings the bar back this instant: the screen is leaving, the bar is leaving, or something went
     * wrong. Does nothing to a bar this never moved, which Instagram may have moved itself. The wait on
     * Reels is dropped with it, until the next touch or the screen coming back starts one.
     */
    void restoreNow() {
        bar.removeCallbacks(watch);
        disarm();
        hidden = false;
        byReels = false;
        fingerDown = false;
        if (shrunk) {
            shrunk = false;
            shrinker.shrink(false, true);
        }
        giveRoom(false);
        if (!moved) return;
        moved = false;
        bar.animate().cancel();
        if (bar.getTranslationY() != 0f) bar.setTranslationY(0f);
    }

    private void watchScroller() {
        try {
            if (!hidden) return;
            View list = guard == null ? null : guard.get();
            boolean fine = running.getAsBoolean() && bar.isAttachedToWindow() && list != null
                    && (byReels ? reelsShowing(list)
                    : list.isAttachedToWindow() && list.isShown() && list.canScrollVertically(-1));
            if (fine) {
                bar.postDelayed(watch, WATCH_MS);
                return;
            }
            HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_RESTORED);
            show();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "scroll hide", failure);
            restoreNow();
        }
    }

    /**
     * Whether [list] is inside a Reels viewer ({@link GlassTabBar#REELS_ROOT}). Swiping to the next reel is
     * not scrolling a list, and the viewer stops at the bar's top, so a bar slid away there leaves an empty
     * strip. Going by the video's size alone missed a reel that doesn't fill the screen, a photo reel and one
     * still loading, so the bar hid on some reels and stayed on others (#170).
     */
    private boolean inReels(View list) {
        int reels = GlassTabBar.id(bar.getContext(), GlassTabBar.REELS_ROOT);
        if (reels == 0) return false;
        for (Object at = list; at instanceof View; at = ((View) at).getParent()) {
            if (((View) at).getId() == reels) return true;
        }
        return false;
    }

    /**
     * Whether a video fills the screen under the bar: a SurfaceView or TextureView that is on screen and
     * covers most of the content's width and height. That is a full-screen video pager outside the Reels
     * viewer (a feed video is about as wide but a lot shorter), where the bar should stay too.
     */
    private boolean fullScreenVideo() {
        View root = bar.getParent() instanceof View ? (View) bar.getParent() : bar.getRootView();
        int width = root.getWidth();
        int height = root.getHeight();
        return width > 0 && height > 0 && coveringVideo(root, width, height, 0);
    }

    private boolean coveringVideo(View view, int width, int height, int depth) {
        if (view == bar || view.getVisibility() != View.VISIBLE || depth > 60) return false;
        if (view instanceof SurfaceView || view instanceof TextureView) {
            return view.getGlobalVisibleRect(other)
                    && other.width() >= width * FULL_SCREEN_SHARE && other.height() >= height * FULL_SCREEN_SHARE;
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            if (coveringVideo(group.getChildAt(i), width, height, depth + 1)) return true;
        }
        return false;
    }
}
