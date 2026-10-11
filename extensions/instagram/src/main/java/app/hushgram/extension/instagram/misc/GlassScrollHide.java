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
 * isn't a list, or a pull at the top of one, never hides it.
 *
 * <p>The bar is only ever moved with a translation, never resized and never hidden, so nothing under it
 * shifts: with "Show content behind the tab bar" off, the strip it leaves is the window's own background.
 *
 * <p>It can't strand the bar. While the bar is away a short timer looks at the list that hid it, and
 * brings the bar back as soon as that list is at its top, no longer on screen, gone, or the feature has been
 * paused. A tab change, Back, the screen going to the background and the bar leaving the window do the same
 * at once. The touch wrapper does nothing while HushGram is paused.
 */
final class GlassScrollHide {
    /** How far a drag must go, and how far it must turn back to flip, in dp. */
    static final int SLOP_DP = 24;
    static final long SLIDE_MS = 220;
    /** How often the list that hid the bar is looked at while the bar is away. */
    static final long WATCH_MS = 120;
    /** Past this share of the content's width and height, a video is a full-screen pager (Reels), which keeps its bar. */
    static final float FULL_SCREEN_SHARE = 0.75f;
    /** Counted names in the glass family's Diagnostics line. */
    static final String COUNT_HID = "scroll hid the bar";
    static final String COUNT_SHOWED = "scroll showed the bar";
    static final String COUNT_RESTORED = "bar restored by the guard";

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
    private final float density;
    private final float slop;
    private final Drag drag = new Drag();
    private final Rect rect = new Rect();
    private final Rect other = new Rect();
    @Nullable private WeakReference<View> scroller;
    /** The list that hid the bar: the one the timer watches until the bar is back. */
    @Nullable private WeakReference<View> guard;
    private boolean hidden;
    /**
     * Whether this moved the bar and hasn't put it all the way back yet. Instagram may move its own
     * bar, so a restore only undoes what this did.
     */
    private boolean moved;
    /** Whether this drag has been refused for a full-screen video, so the screen isn't walked again until the next one. */
    private boolean refused;
    private final Runnable watch = this::watchScroller;

    /**
     * @param bar     the tab bar
     * @param running whether the feature is allowed right now: false while HushGram is paused
     */
    GlassScrollHide(View bar, BooleanSupplier running) {
        this.bar = bar;
        this.running = running;
        this.density = bar.getResources().getDisplayMetrics().density;
        this.slop = Math.max(SLOP_DP * density, ViewConfiguration.get(bar.getContext()).getScaledTouchSlop());
    }

    boolean isHidden() {
        return hidden;
    }

    /** Whether the bar has slid all the way off, so nothing needs drawing behind it. */
    boolean isOffScreen() {
        return hidden && bar.getTranslationY() >= travel() - 1f;
    }

    private float travel() {
        return Math.max(bar.getHeight(), 48 * density) + 8 * density;
    }

    /**
     * Wraps [window]'s callback so [hide] sees its touches and Back keys. Once per window: a window
     * already wrapped for an earlier bar (one Instagram rebuilt) hands its touches to [hide] instead,
     * after putting the earlier bar back.
     */
    static void install(Window window, GlassScrollHide hide) {
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        if (Proxy.isProxyClass(inner.getClass()) && Proxy.getInvocationHandler(inner) instanceof Watcher) {
            Watcher watcher = (Watcher) Proxy.getInvocationHandler(inner);
            if (watcher.hide != hide) {
                watcher.hide.restoreNow();
                watcher.hide = hide;
            }
            return;
        }
        window.setCallback((Window.Callback) Proxy.newProxyInstance(GlassScrollHide.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class}, new Watcher(inner, hide)));
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
                        hide.show();
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
            drag.cancel();
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                refused = false;
                if (!bar.isAttachedToWindow() || onBar(event)) {
                    drag.cancel();
                    return;
                }
                View found = findScroller(bar.getRootView(), Math.round(event.getRawX()), Math.round(event.getRawY()), 0);
                scroller = found == null ? null : new WeakReference<>(found);
                drag.start(event.getRawX(), event.getRawY());
                break;
            case MotionEvent.ACTION_MOVE:
                if (!drag.isActive()) return;
                int direction = drag.move(event.getRawX(), event.getRawY(), slop);
                if (direction == SHOW) {
                    refused = false;
                    show();
                } else if (direction == HIDE) {
                    hideIfScrolled();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag.cancel();
                break;
            default:
                break;
        }
    }

    private boolean onBar(MotionEvent event) {
        return bar.getGlobalVisibleRect(rect) && rect.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }

    /**
     * The innermost view under the point that can scroll up or down, or null. The bar's own branch is skipped.
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
        return view.canScrollVertically(-1) || view.canScrollVertically(1) ? view : null;
    }

    private void hideIfScrolled() {
        if (hidden || refused || !bar.isAttachedToWindow()) return;
        View list = scroller == null ? null : scroller.get();
        // A list still at its top has not been scrolled down, however far a finger has gone.
        if (list == null || !list.isShown() || !list.canScrollVertically(-1)) return;
        if (fullScreenVideo()) {
            refused = true;
            return;
        }
        guard = scroller;
        hidden = true;
        moved = true;
        bar.animate().cancel();
        bar.animate().translationY(travel()).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).start();
        bar.removeCallbacks(watch);
        bar.postDelayed(watch, WATCH_MS);
        HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_HID);
    }

    /** Brings the bar back, sliding. Always allowed, and does nothing when it never left. */
    void show() {
        if (!hidden) return;
        hidden = false;
        bar.removeCallbacks(watch);
        bar.animate().cancel();
        bar.animate().translationY(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> moved = hidden).start();
        HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_SHOWED);
    }

    /**
     * Brings the bar back this instant: the screen is leaving, the bar is leaving, or something went
     * wrong. Does nothing to a bar this never moved, which Instagram may have moved itself.
     */
    void restoreNow() {
        bar.removeCallbacks(watch);
        hidden = false;
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
                    && list.isAttachedToWindow() && list.isShown() && list.canScrollVertically(-1);
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
     * Whether a video fills the screen under the bar: a SurfaceView or TextureView that is on screen and
     * covers most of the content's width and height. That is Reels' pager (a feed video is about as wide but a
     * lot shorter), where swiping to the next reel is not scrolling a list and the bar should stay.
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
