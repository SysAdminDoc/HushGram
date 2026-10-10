/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The drag reading, the hide and the ways back for Glass tab bar's "Hide the tab bar as you scroll". */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(qualifiers = "w1080dp-h1920dp")
public class GlassScrollHideTest {
    /** An activity that counts the touches that reach it through the wrapped window callback. */
    public static class Probe extends Activity {
        int touches;
        int keys;

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            touches++;
            return super.dispatchTouchEvent(event);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            keys++;
            return super.dispatchKeyEvent(event);
        }
    }

    /** A list whose position the test sets. */
    static final class FakeList extends View {
        boolean awayFromTop;
        boolean canGoOn = true;

        FakeList(Context context) {
            super(context);
        }

        @Override public boolean canScrollVertically(int direction) {
            return direction < 0 ? awayFromTop : canGoOn;
        }
    }

    private ActivityController<Probe> controller;
    private Probe activity;
    private FrameLayout root;
    private FakeList list;
    private View bar;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private GlassScrollHide hide;
    private long clock;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Probe.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        list = new FakeList(activity);
        bar = new View(activity);
        root.addView(list, new FrameLayout.LayoutParams(1080, 1800));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(1080, 150);
        barParams.topMargin = 1770;
        root.addView(bar, barParams);
        activity.setContentView(root, new FrameLayout.LayoutParams(1080, 1920));
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        hide = new GlassScrollHide(bar, running::get);
        clock = SystemClock.uptimeMillis();
    }

    @After public void tearDown() {
        controller.close();
    }

    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(clock, clock, action, x, y, 0);
        clock += 16;
        try {
            hide.onTouch(event);
        } finally {
            event.recycle();
        }
    }

    /** A finger down at the list's middle that drags [by] pixels (negative is up) in steps. */
    private void drag(float by) {
        touch(MotionEvent.ACTION_DOWN, 500, 900);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 900 + by * i / 10f);
    }

    private void settle() {
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
    }

    @Test public void aFingerMovingUpHidesAndOneMovingDownShows() {
        GlassScrollHide.Drag drag = new GlassScrollHide.Drag();
        drag.start(500, 900);
        assertEquals(GlassScrollHide.KEEP, drag.move(500, 890, 60));
        assertEquals(GlassScrollHide.HIDE, drag.move(500, 800, 60));
        assertEquals(GlassScrollHide.HIDE, drag.move(500, 700, 60));
        // A small turn back does not flip it, a turn of the slop does, and it flips again the other way.
        assertEquals(GlassScrollHide.HIDE, drag.move(500, 730, 60));
        assertEquals(GlassScrollHide.SHOW, drag.move(500, 761, 60));
        assertEquals(GlassScrollHide.SHOW, drag.move(500, 820, 60));
        assertEquals(GlassScrollHide.SHOW, drag.move(500, 790, 60));
        assertEquals(GlassScrollHide.HIDE, drag.move(500, 759, 60));
    }

    @Test public void aSidewaysDragIsNeverAScroll() {
        GlassScrollHide.Drag drag = new GlassScrollHide.Drag();
        drag.start(500, 900);
        assertEquals(GlassScrollHide.KEEP, drag.move(200, 860, 24));
        assertEquals(GlassScrollHide.KEEP, drag.move(200, 700, 24));
        assertFalse(drag.isActive());
        drag.start(500, 900);
        assertEquals(GlassScrollHide.HIDE, drag.move(490, 860, 24));
        drag.cancel();
        assertEquals(GlassScrollHide.KEEP, drag.move(490, 700, 24));
    }

    @Test public void scrollingAListDownHidesTheBarAndSlidesItOff() {
        list.awayFromTop = true;
        drag(-300);
        assertTrue(hide.isHidden());
        settle();
        assertTrue(bar.getTranslationY() > 100f);
        assertTrue(hide.isOffScreen());
        // The bar is only moved: its size and place in the layout are untouched.
        assertEquals(150, bar.getHeight());
        assertEquals(1770, bar.getTop());
    }

    @Test public void scrollingBackUpBringsItBack() {
        list.awayFromTop = true;
        drag(-300);
        settle();
        assertTrue(hide.isHidden());
        touch(MotionEvent.ACTION_UP, 500, 600);
        touch(MotionEvent.ACTION_DOWN, 500, 600);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 600 + 30 * i);
        assertFalse(hide.isHidden());
        settle();
        assertEquals(0f, bar.getTranslationY(), 0.5f);
    }

    @Test public void aListAtItsTopOrNotALongListNeverHides() {
        list.awayFromTop = false;
        drag(-300);
        assertFalse(hide.isHidden());
        touch(MotionEvent.ACTION_UP, 500, 600);
        // Nothing under the finger that scrolls at all.
        list.awayFromTop = true;
        list.canGoOn = false;
        FakeList still = list;
        root.removeView(still);
        View plain = new View(activity);
        root.addView(plain, 0, new FrameLayout.LayoutParams(1080, 1800));
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        drag(-300);
        assertFalse(hide.isHidden());
    }

    @Test public void aDragThatStartsOnTheBarIsNotAScroll() {
        list.awayFromTop = true;
        touch(MotionEvent.ACTION_DOWN, 500, 1800);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 1800 - 30 * i);
        assertFalse(hide.isHidden());
    }

    @Test public void reachingTheTopBringsTheBarBackWithoutAnotherTouch() {
        list.awayFromTop = true;
        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isHidden());
        // Still away from the top: it stays hidden however long the reader pauses.
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        assertTrue(hide.isHidden());
        list.awayFromTop = false;
        ShadowLooper.idleMainLooper(GlassScrollHide.WATCH_MS * 2, TimeUnit.MILLISECONDS);
        assertFalse(hide.isHidden());
        settle();
        assertEquals(0f, bar.getTranslationY(), 0.5f);
    }

    @Test public void aListThatLeavesTheScreenBringsTheBarBack() {
        list.awayFromTop = true;
        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isHidden());
        list.setVisibility(View.GONE);
        ShadowLooper.idleMainLooper(GlassScrollHide.WATCH_MS * 2, TimeUnit.MILLISECONDS);
        assertFalse(hide.isHidden());
        // And a list taken out of the window.
        list.setVisibility(View.VISIBLE);
        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isHidden());
        root.removeView(list);
        ShadowLooper.idleMainLooper(GlassScrollHide.WATCH_MS * 2, TimeUnit.MILLISECONDS);
        assertFalse(hide.isHidden());
    }

    @Test public void pausingBringsItBackAtOnceAndStopsReadingTouches() {
        list.awayFromTop = true;
        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isHidden());
        running.set(false);
        touch(MotionEvent.ACTION_DOWN, 500, 900);
        assertFalse(hide.isHidden());
        assertEquals(0f, bar.getTranslationY(), 0f);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 900 - 40 * i);
        assertFalse(hide.isHidden());
    }

    @Test public void restoreNowIsImmediateAndSafeToRepeat() {
        list.awayFromTop = true;
        drag(-300);
        settle();
        assertTrue(bar.getTranslationY() > 0f);
        hide.restoreNow();
        assertFalse(hide.isHidden());
        assertEquals(0f, bar.getTranslationY(), 0f);
        hide.restoreNow();
        hide.show();
        assertEquals(0f, bar.getTranslationY(), 0f);
    }

    @Test public void aVideoFillingTheScreenIsReelsAndKeepsTheBar() {
        list.awayFromTop = true;
        SurfaceView video = new SurfaceView(activity);
        root.addView(video, 1, new FrameLayout.LayoutParams(1080, 1800));
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        drag(-300);
        assertFalse(hide.isHidden());
        touch(MotionEvent.ACTION_UP, 500, 600);
        // A short video in a feed does not count.
        root.removeView(video);
        root.addView(video, 1, new FrameLayout.LayoutParams(1080, 900));
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        drag(-300);
        assertTrue(hide.isHidden());
    }

    @Test public void theWrappedWindowCallbackPassesEverythingOnAndShowsOnBack() {
        Window window = activity.getWindow();
        Window.Callback before = window.getCallback();
        GlassScrollHide.install(window, hide);
        Window.Callback wrapped = window.getCallback();
        assertFalse(before == wrapped);
        GlassScrollHide.install(window, hide);
        assertSame("wrapped once", wrapped, window.getCallback());

        list.awayFromTop = true;
        MotionEvent down = MotionEvent.obtain(clock, clock, MotionEvent.ACTION_DOWN, 500, 900, 0);
        wrapped.dispatchTouchEvent(down);
        for (int i = 1; i <= 10; i++) {
            clock += 16;
            wrapped.dispatchTouchEvent(MotionEvent.obtain(clock, clock, MotionEvent.ACTION_MOVE, 500, 900 - 30 * i, 0));
        }
        assertEquals(11, activity.touches);
        assertTrue(hide.isHidden());

        KeyEvent back = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        wrapped.dispatchKeyEvent(back);
        assertEquals(1, activity.keys);
        assertFalse(hide.isHidden());
        // Other keys do nothing to it.
        drag(-300);
        wrapped.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));
        assertTrue(hide.isHidden());
        // Window methods that take nothing or other arguments still reach the activity.
        assertEquals(before.onSearchRequested(), wrapped.onSearchRequested());
    }

    @Test public void anExceptionFromTheWrappedCallbackIsNotSwallowedOrChanged() {
        Window window = activity.getWindow();
        final IllegalStateException boom = new IllegalStateException("boom");
        Window.Callback inner = (Window.Callback) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Window.Callback.class}, (proxy, method, args) -> {
                    if ("dispatchTouchEvent".equals(method.getName())) throw boom;
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
        window.setCallback(inner);
        GlassScrollHide.install(window, hide);
        MotionEvent down = MotionEvent.obtain(clock, clock, MotionEvent.ACTION_DOWN, 500, 900, 0);
        try {
            window.getCallback().dispatchTouchEvent(down);
            throw new AssertionError("should have thrown");
        } catch (IllegalStateException failure) {
            assertSame(boom, failure);
        }
    }
}
