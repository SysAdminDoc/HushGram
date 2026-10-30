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
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.instagram.reels.BurnInGuardForTests;
import app.hushgram.extension.shared.diagnostics.HookStatus;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The drag reading, the hide and the ways back for Glass tab bar's "Hide the tab bar as you scroll", and
 * the wait, the hide and the ways back for its "Hide the tab bar on Reels" (#170).
 */
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
    static final class FakeList extends FrameLayout {
        boolean awayFromTop;
        boolean canGoOn = true;
        /** Like a Compose list: it knows what it can scroll only once the window has handed it the down. */
        boolean needsDown;
        boolean sawDown;

        FakeList(Context context) {
            super(context);
        }

        @Override public boolean canScrollVertically(int direction) {
            if (needsDown && !sawDown) return false;
            return direction < 0 ? awayFromTop : canGoOn;
        }
    }

    /** A row's text cut short: it says it can scroll down, and never moves. */
    static final class CutText extends View {
        CutText(Context context) {
            super(context);
        }

        @Override public boolean canScrollVertically(int direction) {
            return direction > 0;
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
    /** The id the Reels tests give the Reels viewer's name, the viewer they add, and what the name had before. */
    private static final int REELS_ID = 0x7f0a0170;
    private FrameLayout viewer;
    private boolean reelsNamed;
    private Integer reelsIdBefore;

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
        if (reelsNamed) {
            Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
            synchronized (ids) {
                if (reelsIdBefore == null) ids.remove(GlassTabBar.REELS_ROOT);
                else ids.put(GlassTabBar.REELS_ROOT, reelsIdBefore);
            }
        }
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

    /** #65 on 451: a drag starting on a row's cut-short text still scrolls the list it sits in. */
    @Test public void aTextCutShortUnderTheFingerIsNotTheList() {
        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(1080, 300);
        rowParams.topMargin = 750;
        list.addView(new CutText(activity), rowParams);
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        list.awayFromTop = true;
        drag(-300);
        assertTrue(hide.isHidden());
    }

    /** #65 on 451: Activity is a Compose list, which answers only after the window hands it the down. */
    @Test public void aListThatAnswersOnlyAfterTheDownStillHides() {
        list.needsDown = true;
        list.awayFromTop = true;
        touch(MotionEvent.ACTION_DOWN, 500, 900);
        list.sawDown = true;
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 900 - 300 * i / 10f);
        assertTrue(hide.isHidden());
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

    /** A restore only undoes what this did: a bar Instagram moved itself stays where Instagram put it. */
    @Test public void aRestoreLeavesABarItNeverMovedAlone() {
        bar.setTranslationY(200f);
        hide.restoreNow();
        assertEquals(200f, bar.getTranslationY(), 0f);
        bar.setTranslationY(0f);

        // Hidden and shown again, all the way: a later move of Instagram's is left alone too.
        list.awayFromTop = true;
        drag(-300);
        settle();
        touch(MotionEvent.ACTION_UP, 500, 600);
        touch(MotionEvent.ACTION_DOWN, 500, 600);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 600 + 30 * i);
        settle();
        assertEquals(0f, bar.getTranslationY(), 0.5f);
        bar.setTranslationY(50f);
        hide.restoreNow();
        assertEquals(50f, bar.getTranslationY(), 0f);
    }

    /** A bar Instagram rebuilds in the same window takes over the window's watcher, and the old one is put back. */
    @Test public void aRebuiltBarTakesOverTheWindowsWatcher() {
        Window window = activity.getWindow();
        GlassScrollHide.install(window, hide);
        Window.Callback wrapped = window.getCallback();
        list.awayFromTop = true;
        drag(-300);
        settle();
        assertTrue(hide.isHidden());

        View rebuilt = new View(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
        params.topMargin = 1770;
        root.addView(rebuilt, params);
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        GlassScrollHide next = new GlassScrollHide(rebuilt, running::get);
        GlassScrollHide.install(window, next);
        assertSame("still wrapped once", wrapped, window.getCallback());
        assertFalse(hide.isHidden());
        assertEquals(0f, bar.getTranslationY(), 0f);

        wrapped.dispatchTouchEvent(MotionEvent.obtain(clock, clock, MotionEvent.ACTION_DOWN, 500, 900, 0));
        for (int i = 1; i <= 10; i++) {
            clock += 16;
            wrapped.dispatchTouchEvent(MotionEvent.obtain(clock, clock, MotionEvent.ACTION_MOVE, 500, 900 - 30 * i, 0));
        }
        assertTrue("the rebuilt bar gets the touches", next.isHidden());
        assertFalse(hide.isHidden());
    }

    /**
     * Burn-in guard's watcher wrapped over the glass one is looked through: a rebuilt bar still takes
     * over the one glass watcher instead of wrapping a second, and touches through both reach it.
     */
    @Test public void aRebuiltBarLooksThroughBurnInGuardsWatcher() {
        Window window = activity.getWindow();
        try {
            GlassScrollHide.install(window, hide);
            BurnInGuardForTests.watch(window);
            Window.Callback outer = window.getCallback();

            View rebuilt = new View(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
            params.topMargin = 1770;
            root.addView(rebuilt, params);
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 1920);
            GlassScrollHide next = new GlassScrollHide(rebuilt, running::get);
            GlassScrollHide.install(window, next);
            assertSame("no second glass watcher", outer, window.getCallback());

            list.awayFromTop = true;
            outer.dispatchTouchEvent(MotionEvent.obtain(clock, clock, MotionEvent.ACTION_DOWN, 500, 900, 0));
            for (int i = 1; i <= 10; i++) {
                clock += 16;
                outer.dispatchTouchEvent(MotionEvent.obtain(clock, clock, MotionEvent.ACTION_MOVE, 500, 900 - 30 * i, 0));
            }
            assertTrue("the rebuilt bar gets the touches", next.isHidden());
            assertFalse(hide.isHidden());
            assertEquals("each touch reached the activity once", 11, activity.touches);
        } finally {
            BurnInGuardForTests.reset();
        }
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

    /** #170: the bar hid on a reel whose video didn't fill the screen and stayed on the ones that did. */
    @Test public void nothingInsideAReelsViewerHidesTheBarWhateverItsVideo() {
        int reelsId = 0x7f0a0170;
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        Integer before;
        synchronized (ids) { before = ids.put(GlassTabBar.REELS_ROOT, reelsId); }
        try {
            FrameLayout viewer = new FrameLayout(activity);
            viewer.setId(reelsId);
            FakeList pager = new FakeList(activity);
            pager.awayFromTop = true;
            viewer.addView(pager, new FrameLayout.LayoutParams(1080, 1770));
            root.addView(viewer, 1, new FrameLayout.LayoutParams(1080, 1770));
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 1920);
            drag(-300);
            assertFalse("a photo reel has no video view to go by", hide.isHidden());
            touch(MotionEvent.ACTION_UP, 500, 600);
            // The same pager anywhere else is a list like any other.
            viewer.setId(View.NO_ID);
            drag(-300);
            assertTrue(hide.isHidden());
        } finally {
            synchronized (ids) {
                if (before == null) ids.remove(GlassTabBar.REELS_ROOT);
                else ids.put(GlassTabBar.REELS_ROOT, before);
            }
        }
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

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
    }

    private static void idle(long millis) {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS);
    }

    /** A finger down and up again in one place. */
    private void tap(float x, float y) {
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_UP, x, y);
    }

    /** Puts a Reels viewer over the list, down to the bar's top, with a pager of reels inside it. */
    private void addReelsViewer() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) { reelsIdBefore = ids.put(GlassTabBar.REELS_ROOT, REELS_ID); }
        reelsNamed = true;
        viewer = new FrameLayout(activity);
        viewer.setId(REELS_ID);
        FakeList pager = new FakeList(activity);
        pager.awayFromTop = true;
        viewer.addView(pager, new FrameLayout.LayoutParams(1080, 1770));
        root.addView(viewer, 1, new FrameLayout.LayoutParams(1080, 1770));
        layout();
    }

    /**
     * On the Reels screen, with a hider that waits [millis] there and has the window's touches, as the
     * glass bar gives it. The wait starts as it's given them.
     */
    private void onReels(boolean onScroll, long millis) {
        addReelsViewer();
        hide = new GlassScrollHide(bar, running::get, onScroll, millis);
        GlassScrollHide.install(activity.getWindow(), hide);
    }

    /** Moves the Reels viewer [left] pixels across, the way a pager keeps a tab beside the screen. */
    private void slideViewerTo(int left) {
        ((FrameLayout.LayoutParams) viewer.getLayoutParams()).leftMargin = left;
        viewer.requestLayout();
        layout();
    }

    @Test public void onReelsTheBarSlidesAwayOnceTheWaitIsUpAndNotBefore() {
        onReels(false, 3000);
        tap(500, 900);
        idle(2900);
        assertFalse(hide.isHidden());
        idle(200);
        assertTrue(hide.isHidden());
        settle();
        assertTrue(bar.getTranslationY() > 100f);
        assertTrue(hide.isOffScreen());
        // Only moved, as a scroll moves it: its size and place in the layout are untouched.
        assertEquals(150, bar.getHeight());
        assertEquals(1770, bar.getTop());
        assertEquals(View.VISIBLE, bar.getVisibility());
    }

    @Test public void theWaitHidesNothingOffReels() {
        onReels(false, 2000);
        // A screen as big that isn't the Reels viewer.
        viewer.setId(View.NO_ID);
        tap(500, 900);
        idle(5000);
        assertFalse(hide.isHidden());
        // The viewer left under another screen.
        viewer.setId(REELS_ID);
        viewer.setVisibility(View.INVISIBLE);
        tap(500, 900);
        idle(5000);
        assertFalse(hide.isHidden());
        // The Reels tab kept beside the screen in a pager: shown by its own account, but not on screen.
        viewer.setVisibility(View.VISIBLE);
        slideViewerTo(1080);
        tap(500, 900);
        idle(5000);
        assertFalse(hide.isHidden());
        // And half way in, mid swipe.
        slideViewerTo(540);
        tap(500, 900);
        idle(5000);
        assertFalse(hide.isHidden());
        // Back on screen it's a reel again.
        slideViewerTo(0);
        tap(500, 900);
        idle(2100);
        assertTrue(hide.isHidden());
    }

    @Test public void aTouchStartsTheWaitOverAndAFingerHeldDownHoldsItOff() {
        onReels(false, 3000);
        tap(500, 900);
        idle(2000);
        tap(500, 900);
        idle(2000);
        assertFalse("the first wait would be up by now", hide.isHidden());
        touch(MotionEvent.ACTION_DOWN, 500, 900);
        idle(10_000);
        assertFalse("nothing waits while a finger is down", hide.isHidden());
        touch(MotionEvent.ACTION_UP, 500, 900);
        idle(2900);
        assertFalse(hide.isHidden());
        idle(200);
        assertTrue(hide.isHidden());
    }

    /** #170: once away on Reels it stays away whichever way the reels are swiped. */
    @Test public void swipesBetweenReelsLeaveItAwayWhicheverWayTheyGo() {
        onReels(true, 2000);
        idle(2100);
        assertTrue(hide.isHidden());
        settle();
        // A finger moving down, to the reel before: on a list this is what brings the bar back.
        drag(300);
        touch(MotionEvent.ACTION_UP, 500, 1200);
        assertTrue(hide.isHidden());
        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isHidden());
        tap(500, 900);
        idle(10_000);
        assertTrue(hide.isHidden());
        assertTrue(hide.isOffScreen());
    }

    @Test public void aTapWhereTheBarSitsBringsItBackAndTheWaitTakesItAwayAgain() {
        HookStatus.clear();
        try {
            onReels(true, 2000);
            idle(2100);
            settle();
            assertTrue(hide.isOffScreen());
            touch(MotionEvent.ACTION_DOWN, 500, 1850);
            idle(10_000);
            assertTrue("a finger down there may be on the reel's seek bar, so it waits for the lift (#221)",
                    hide.isOffScreen());
            touch(MotionEvent.ACTION_UP, 500, 1850);
            assertFalse("the lift of a tap brings it back", hide.isHidden());
            settle();
            assertEquals(0f, bar.getTranslationY(), 0.5f);
            idle(1500);
            assertFalse("the wait starts when the finger lifts", hide.isHidden());
            idle(200);
            assertTrue(hide.isHidden());
            String report = HookStatus.report().toString();
            assertTrue(report, report.contains(GlassScrollHide.COUNT_REELS_HID + " 2"));
            assertTrue(report, report.contains(GlassScrollHide.COUNT_TAPPED_BACK + " 1"));
            assertFalse(report, report.contains(GlassScrollHide.COUNT_SHOWED));
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * #221: while the bar is away for a reel, the reel's seek bar sits where the bar was. A drag there is the
     * seek bar's, so the bar stays away and the reel doesn't move under the finger. A tap still brings it back.
     */
    @Test public void aDragWhereTheBarSitsIsTheSeekBarsAndLeavesItAway() {
        HookStatus.clear();
        try {
            onReels(true, 2000);
            idle(2100);
            settle();
            assertTrue(hide.isOffScreen());
            touch(MotionEvent.ACTION_DOWN, 300, 1850);
            for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 300 + 50 * i, 1850);
            touch(MotionEvent.ACTION_UP, 800, 1850);
            settle();
            assertTrue("a drag on the seek bar leaves the bar away", hide.isOffScreen());
            idle(10_000);
            assertTrue(hide.isOffScreen());
            // A finger that drifts less than a tap's slop is still a tap.
            touch(MotionEvent.ACTION_DOWN, 500, 1850);
            touch(MotionEvent.ACTION_MOVE, 502, 1851);
            touch(MotionEvent.ACTION_UP, 502, 1851);
            assertFalse(hide.isHidden());
            String report = HookStatus.report().toString();
            assertTrue(report, report.contains(GlassScrollHide.COUNT_TAPPED_BACK + " 1"));
        } finally {
            HookStatus.clear();
        }
    }

    /** Where the bar sits is the way back from a reel only. A bar a scroll hid comes back the ways it always has. */
    @Test public void aTouchWhereTheBarSitsLeavesABarAScrollHidAlone() {
        onReels(true, 2000);
        viewer.setVisibility(View.GONE);
        layout();
        list.awayFromTop = true;
        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        settle();
        assertTrue(hide.isOffScreen());
        tap(500, 1850);
        assertTrue(hide.isHidden());
        // The wait leaves a list alone too, and scrolling back up still shows the bar.
        idle(10_000);
        assertTrue(hide.isHidden());
        touch(MotionEvent.ACTION_DOWN, 500, 600);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 600 + 30 * i);
        assertFalse(hide.isHidden());
        touch(MotionEvent.ACTION_UP, 500, 900);
        idle(10_000);
        assertFalse("a list is not a reel", hide.isHidden());
    }

    @Test public void leavingReelsBringsTheBarBack() {
        onReels(false, 2000);
        idle(2100);
        assertTrue(hide.isHidden());
        // Another screen takes the viewer's place.
        viewer.setVisibility(View.GONE);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        settle();
        assertEquals(0f, bar.getTranslationY(), 0.5f);
        // Swiped across to the tab beside it.
        viewer.setVisibility(View.VISIBLE);
        layout();
        tap(500, 900);
        idle(2100);
        assertTrue(hide.isHidden());
        slideViewerTo(1080);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        // Taken out of the window.
        slideViewerTo(0);
        tap(500, 900);
        idle(2100);
        assertTrue(hide.isHidden());
        root.removeView(viewer);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        idle(10_000);
        assertFalse(hide.isHidden());
    }

    @Test public void pausingBringsItBackAndNothingWaitsWhilePaused() {
        onReels(false, 2000);
        idle(2100);
        assertTrue(hide.isHidden());
        running.set(false);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        settle();
        assertEquals(0f, bar.getTranslationY(), 0.5f);
        tap(500, 900);
        idle(10_000);
        assertFalse(hide.isHidden());
        // Paused part way through a wait: the wait runs out and moves nothing.
        running.set(true);
        tap(500, 900);
        idle(1000);
        running.set(false);
        idle(5000);
        assertFalse(hide.isHidden());
        running.set(true);
        idle(10_000);
        assertFalse("nothing waits until the next touch", hide.isHidden());
        tap(500, 900);
        idle(2100);
        assertTrue(hide.isHidden());
    }

    @Test public void backBringsItBackAndStartsTheWaitOver() {
        onReels(false, 2000);
        Window.Callback wrapped = activity.getWindow().getCallback();
        idle(2100);
        assertTrue(hide.isHidden());
        wrapped.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        assertEquals(1, activity.keys);
        assertFalse(hide.isHidden());
        // Back closed something over the reel, which is still the screen showing.
        idle(1900);
        assertFalse(hide.isHidden());
        idle(200);
        assertTrue(hide.isHidden());
        // Back left Reels.
        wrapped.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        viewer.setVisibility(View.GONE);
        idle(10_000);
        assertFalse(hide.isHidden());
    }

    /** The glass bar puts the bar back as its activity pauses, and starts the wait as it comes to the front. */
    @Test public void theScreenLeavingDropsTheWaitAndComingBackStartsOne() {
        onReels(false, 2000);
        tap(500, 900);
        idle(1000);
        hide.restoreNow();
        idle(10_000);
        assertFalse("the wait went with the screen", hide.isHidden());
        hide.arm();
        idle(1900);
        assertFalse(hide.isHidden());
        idle(200);
        assertTrue(hide.isHidden());
        settle();
        hide.restoreNow();
        assertFalse(hide.isHidden());
        assertEquals(0f, bar.getTranslationY(), 0f);
        idle(10_000);
        assertFalse(hide.isHidden());
    }

    /** With the choice on Never nothing is ever left waiting, whatever asks for a wait. */
    @Test public void neverLeavesNothingWaiting() {
        addReelsViewer();
        AtomicInteger asked = new AtomicInteger();
        hide = new GlassScrollHide(bar, () -> {
            asked.incrementAndGet();
            return true;
        }, true, 0L);
        GlassScrollHide.install(activity.getWindow(), hide);
        tap(500, 900);
        hide.onBack();
        hide.arm();
        int before = asked.get();
        idle(60_000);
        assertFalse(hide.isHidden());
        assertEquals("something looked after the touch was over", before, asked.get());
    }

    /** A wait is one delayed look. Off Reels it finds nothing and leaves nothing running behind it. */
    @Test public void aWaitThatFindsNoReelLooksOnceAndStops() {
        addReelsViewer();
        viewer.setId(View.NO_ID);
        AtomicInteger asked = new AtomicInteger();
        hide = new GlassScrollHide(bar, () -> {
            asked.incrementAndGet();
            return true;
        }, false, 2000);
        GlassScrollHide.install(activity.getWindow(), hide);
        tap(500, 900);
        int before = asked.get();
        idle(2100);
        assertEquals("the wait looked once", before + 1, asked.get());
        idle(60_000);
        assertEquals("and nothing looked again", before + 1, asked.get());
        assertFalse(hide.isHidden());
    }

    /** Without the window's touches nothing could bring the bar back, so nothing waits to hide it. */
    @Test public void aHiderNoWindowHandsItsTouchesNeverWaits() {
        addReelsViewer();
        hide = new GlassScrollHide(bar, running::get, false, 2000);
        tap(500, 900);
        hide.arm();
        idle(10_000);
        assertFalse(hide.isHidden());
    }

    @Test public void aRebuiltBarTakesOverTheWaitAndTheOldBarsIsOver() {
        onReels(false, 2000);
        Window window = activity.getWindow();
        idle(2100);
        assertTrue(hide.isHidden());

        View rebuilt = new View(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
        params.topMargin = 1770;
        root.addView(rebuilt, params);
        layout();
        GlassScrollHide next = new GlassScrollHide(rebuilt, running::get, false, 2000);
        GlassScrollHide.install(window, next);
        assertFalse(hide.isHidden());
        assertEquals(0f, bar.getTranslationY(), 0f);
        hide.arm();
        idle(2100);
        assertFalse("the old bar's hider gets no touches now, so it waits for nothing", hide.isHidden());
        assertTrue("the rebuilt bar's wait started as it took over", next.isHidden());
    }

    /** The scroll switch off and a wait picked: a scroll on a list moves nothing, and only a reel is waited on. */
    @Test public void withTheScrollSwitchOffAScrollHidesNothing() {
        onReels(false, 2000);
        viewer.setVisibility(View.GONE);
        layout();
        list.awayFromTop = true;
        drag(-300);
        assertFalse(hide.isHidden());
        touch(MotionEvent.ACTION_UP, 500, 600);
        idle(10_000);
        assertFalse(hide.isHidden());
    }

    /** A shrinker that notes each call and refuses to shrink while [allowed] is false. */
    private static GlassScrollHide.Shrinker noting(java.util.List<String> calls, AtomicBoolean allowed) {
        return (on, now) -> {
            if (on && !allowed.get()) return false;
            calls.add((on ? "shrink" : "grow") + (now ? " now" : ""));
            return true;
        };
    }

    /**
     * With the liquid look (#65) a scroll down shrinks the bar to its compact row where it would slide away. The
     * bar itself never moves, and scrolling back up, a finger on the row, reaching the top and a restore grow it.
     */
    @Test public void aShrinkerShrinksTheBarWhereAScrollWouldSlideItAway() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        hide = new GlassScrollHide(bar, running::get, true, 0L, noting(calls, new AtomicBoolean(true)));
        list.awayFromTop = true;
        drag(-300);
        assertTrue(hide.isHidden());
        assertTrue(hide.isShrunk());
        settle();
        assertEquals("the bar isn't slid", 0f, bar.getTranslationY(), 0f);
        assertFalse("a shrunk bar is still on screen", hide.isOffScreen());
        assertEquals(java.util.List.of("shrink"), calls);

        touch(MotionEvent.ACTION_UP, 500, 600);
        touch(MotionEvent.ACTION_DOWN, 500, 600);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 600 + 30 * i);
        assertFalse("scrolling back up grows it", hide.isShrunk());
        assertEquals(java.util.List.of("shrink", "grow"), calls);
        touch(MotionEvent.ACTION_UP, 500, 900);

        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isShrunk());
        touch(MotionEvent.ACTION_DOWN, 500, 1800);
        assertFalse("a finger on the row grows it", hide.isShrunk());
        assertFalse(hide.isHidden());
        touch(MotionEvent.ACTION_UP, 500, 1800);

        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isShrunk());
        list.awayFromTop = false;
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse("reaching the top grows it", hide.isShrunk());

        list.awayFromTop = true;
        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isShrunk());
        hide.restoreNow();
        assertFalse(hide.isShrunk());
        assertEquals("a restore grows it at once", "grow now", calls.get(calls.size() - 1));
        assertEquals(0f, bar.getTranslationY(), 0f);
    }

    /** A bar that can't shrink, with Search open say, stays as it is for the rest of the drag, and never slides. */
    @Test public void aShrinkerThatRefusesLeavesTheBarAlone() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        hide = new GlassScrollHide(bar, running::get, true, 0L, noting(calls, new AtomicBoolean(false)));
        list.awayFromTop = true;
        drag(-300);
        assertFalse(hide.isHidden());
        assertFalse(hide.isShrunk());
        settle();
        assertEquals(0f, bar.getTranslationY(), 0f);
        assertTrue(calls.isEmpty());
    }

    /** Pause grows a shrunk bar at once at the next touch, as it brings a slid one back. */
    @Test public void pausingGrowsAShrunkBarAtOnce() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        hide = new GlassScrollHide(bar, running::get, true, 0L, noting(calls, new AtomicBoolean(true)));
        list.awayFromTop = true;
        drag(-300);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isShrunk());
        running.set(false);
        touch(MotionEvent.ACTION_DOWN, 500, 900);
        assertFalse(hide.isShrunk());
        assertEquals("grow now", calls.get(calls.size() - 1));
    }

    /** The wait on Reels still slides the bar away with a shrinker given: only a scroll shrinks it. */
    @Test public void onReelsTheWaitSlidesTheBarEvenWithAShrinker() {
        addReelsViewer();
        java.util.List<String> calls = new java.util.ArrayList<>();
        hide = new GlassScrollHide(bar, running::get, true, 2000L, noting(calls, new AtomicBoolean(true)));
        GlassScrollHide.install(activity.getWindow(), hide);
        tap(500, 900);
        idle(2100);
        assertTrue(hide.isHidden());
        assertFalse(hide.isShrunk());
        settle();
        assertTrue(bar.getTranslationY() > 100f);
        assertTrue(calls.isEmpty());
    }
}
