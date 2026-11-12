/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * "Slide across the tabs" for the glass tab bar (#136): a finger that goes down on the pill and moves
 * sideways carries the highlight with it, and lifting it opens the tab it ended on, the way the tab bar
 * on an iPhone does. With Glass tab bar off, Instagram's own bar slides the same way ({@link PlainTabSlide}, #201).
 *
 * <p>It changes no Instagram code and looks for no class of Instagram's. Like "Hide the tab bar as you
 * scroll", it wraps the window's callback so it sees each touch before the screen does. A touch that
 * starts on the pill goes on to Instagram as it is, so a tap still opens its tab and a long press still
 * does what it did. It turns into a slide only once the finger has gone sideways past the touch slop, and
 * further sideways than up or down. Instagram then gets a cancel, as when the system takes a gesture away,
 * so the tab under the finger neither clicks nor long presses, and the rest of the gesture stays here. A
 * finger held still on a tab can slide after the hold too, unless that tab has a long press of its own: a
 * hold past the long press time on one of those is Instagram's.
 *
 * <p>While it slides, the highlight follows the finger ({@link Host#follow}), with a tick for each tab it
 * crosses when Haptics on the tab bar is on. Lifting the finger clicks the tab under it, or the nearest
 * one, unless that tab is already open. A cancel from the system clicks nothing. Either way the highlight
 * goes back to the selected tab, sliding as it does after a tap.
 *
 * <p>Paused, it passes every touch on and starts nothing. Anything that throws ends the slide, is counted
 * in Diagnostics, and the touch goes on to Instagram.
 */
final class GlassTabSlide {
    /** Counted in the glass family's Diagnostics line. */
    static final String COUNT_OPENED = "Tab opened by a slide";
    static final String COUNT_NO_CLICK = "Slide ended on a tab that takes no click";
    static final String COUNT_WATCHING = "tab slides watching";

    /** What a slide needs from the glass it moves. */
    interface Host {
        /** Whether [x], [y], in the bar's own pixels, are on the pill. */
        boolean onPill(float x, float y);

        /** Puts the highlight's middle at [x], in the bar's own pixels, or sends it back to the selected tab for NaN. */
        void follow(float x);
    }

    static final int IDLE = 0;
    /** A finger went down on the pill and may still turn into a slide. Its touches go on to Instagram. */
    static final int WATCHING = 1;
    /** Sliding: Instagram had its cancel, and the rest of the gesture is kept from it. */
    static final int SLIDING = 2;
    /** Not a slide: the rest of the gesture is Instagram's. */
    static final int REFUSED = 3;
    /** A slide stopped by Pause. Instagram already had its cancel, so the rest of the gesture goes nowhere. */
    static final int DROPPED = 4;

    /** The watcher in each window's callbacks, held weakly both ways so a closed window takes it along. */
    private static final WeakHashMap<Window, WeakReference<Watcher>> watchers = new WeakHashMap<>();

    private final ViewGroup bar;
    private final Host host;
    private final BooleanSupplier running;
    private final float slop;
    private final int[] place = new int[2];
    private int state = IDLE;
    private float downX, downY;
    /** The bar's left edge on the screen as the finger went down. The bar can't move while a finger slides. */
    private float barLeft;
    /** Whether the tab under the finger as it went down has a long press of its own. */
    private boolean tabHolds;

    /**
     * @param bar     the tab bar, whose children are the tabs
     * @param host    the glass that draws the highlight
     * @param running whether slides are allowed right now: false while HushGram is paused
     */
    GlassTabSlide(ViewGroup bar, Host host, BooleanSupplier running) {
        this.bar = bar;
        this.host = host;
        this.running = running;
        this.slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();
    }

    /** Where the gesture under way stands: {@link #IDLE}, {@link #WATCHING}, {@link #SLIDING} and so on. */
    int state() {
        return state;
    }

    /**
     * Wraps [window]'s callback so [slide] sees its touches. Once per window: a window already wrapped for
     * an earlier bar (one Instagram rebuilt) hands its touches to [slide] instead.
     */
    static void install(Window window, GlassTabSlide slide) {
        WeakReference<Watcher> known = watchers.get(window);
        Watcher watcher = known == null ? null : known.get();
        if (watcher != null) {
            if (watcher.slide != slide) {
                watcher.slide.giveUp();
                watcher.slide = slide;
            }
            return;
        }
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        watcher = new Watcher(inner, slide);
        window.setCallback((Window.Callback) Proxy.newProxyInstance(GlassTabSlide.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class}, watcher));
        watchers.put(window, new WeakReference<>(watcher));
    }

    /** The callback [callback] passes calls on to when it's a slide watcher, else [callback]. */
    static Window.Callback innerOf(Window.Callback callback) {
        if (callback != null && Proxy.isProxyClass(callback.getClass())
                && Proxy.getInvocationHandler(callback) instanceof Watcher) {
            return ((Watcher) Proxy.getInvocationHandler(callback)).inner;
        }
        return callback;
    }

    /**
     * Passes every call on to the callback it wraps, whatever the call is, so a method added in a newer
     * Android or a callback the app swaps in behaves as it did. Only the touches of a slide are kept back.
     */
    private static final class Watcher implements InvocationHandler {
        final Window.Callback inner;
        /** The bar's slide now: a rebuilt bar takes over the window's watcher. Main thread only. */
        GlassTabSlide slide;

        Watcher(Window.Callback inner, GlassTabSlide slide) {
            this.inner = inner;
            this.slide = slide;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName())) {
                boolean kept;
                try {
                    kept = slide.onTouch((MotionEvent) args[0], inner);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "tab slide", failure);
                    slide.giveUp();
                    kept = false;
                }
                if (kept) return Boolean.TRUE;
            }
            try {
                return method.invoke(inner, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }

    /**
     * Follows [event] and answers whether it's kept from Instagram. [inner] is the callback Instagram's
     * touches go on to. It gets a cancel as a slide starts.
     */
    boolean onTouch(MotionEvent event, Window.Callback inner) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // A slide whose end never came lets the highlight go back first.
            if (state == SLIDING) host.follow(Float.NaN);
            state = IDLE;
            if (!running.getAsBoolean() || !barStill()) return false;
            bar.getLocationOnScreen(place);
            float x = event.getRawX() - place[0];
            float y = event.getRawY() - place[1];
            if (!host.onPill(x, y)) return false;
            View tab = tabAt(x);
            tabHolds = tab != null && tab.isLongClickable();
            barLeft = place[0];
            downX = event.getRawX();
            downY = event.getRawY();
            state = WATCHING;
            return false;
        }
        boolean ends = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        switch (state) {
            case WATCHING:
                if (ends || action == MotionEvent.ACTION_POINTER_DOWN || !running.getAsBoolean()) {
                    state = ends ? IDLE : REFUSED;
                    return false;
                }
                if (action != MotionEvent.ACTION_MOVE) return false;
                float dx = Math.abs(event.getRawX() - downX);
                float dy = Math.abs(event.getRawY() - downY);
                if (dx < slop || dx <= dy) {
                    // Up or down first: a swipe on the bar, never a slide.
                    if (dy >= slop) state = REFUSED;
                    return false;
                }
                if (tabHolds && event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
                    // The tab's own long press has had its turn.
                    state = REFUSED;
                    return false;
                }
                start(event, inner);
                return true;
            case SLIDING:
                if (!running.getAsBoolean()) {
                    state = ends ? IDLE : DROPPED;
                    host.follow(Float.NaN);
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    host.follow(event.getRawX() - barLeft);
                } else if (ends) {
                    state = IDLE;
                    if (action == MotionEvent.ACTION_UP) open(event.getRawX() - barLeft);
                    host.follow(Float.NaN);
                }
                return true;
            case DROPPED:
                if (ends) state = IDLE;
                return true;
            case REFUSED:
                if (ends) state = IDLE;
                return false;
            default:
                return false;
        }
    }

    /** Ends whatever was under way after a failure, so the rest of the gesture is Instagram's. */
    void giveUp() {
        boolean sliding = state == SLIDING;
        state = REFUSED;
        if (!sliding) return;
        try {
            host.follow(Float.NaN);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "tab slide", failure);
        }
    }

    /** Whether the bar is where it lives and shown: not slid away by a scroll or on Reels, and not hidden. */
    private boolean barStill() {
        return bar.isAttachedToWindow() && bar.isShown() && Math.abs(bar.getTranslationY()) < 1f;
    }

    /** Instagram's own touch handling of the gesture ends the way it does when the system takes a gesture away. */
    private void start(MotionEvent event, Window.Callback inner) {
        state = SLIDING;
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            inner.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
        host.follow(event.getRawX() - barLeft);
    }

    /** Clicks the tab at [x], in the bar's pixels, unless it's already open. */
    private void open(float x) {
        View tab = tabAt(x);
        if (tab == null || tab.isSelected()) return;
        if (tab.performClick()) HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_OPENED);
        else HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_NO_CLICK);
    }

    /** The shown tab at [x], in the bar's pixels, or the nearest one when [x] is past the ends, or null with none. */
    @Nullable
    View tabAt(float x) {
        View nearest = null;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE || tab.getWidth() <= 0) continue;
            float left = tab.getLeft() + tab.getTranslationX();
            float right = tab.getRight() + tab.getTranslationX();
            float off = x < left ? left - x : x >= right ? x - right : 0f;
            if (off < best) {
                best = off;
                nearest = tab;
            }
        }
        return nearest;
    }
}
