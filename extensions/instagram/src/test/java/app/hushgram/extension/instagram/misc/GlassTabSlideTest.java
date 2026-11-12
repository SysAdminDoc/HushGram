/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Slide across the tabs (#136): which touches turn into a slide, what Instagram still gets, where the
 * highlight goes and which tab opens when the finger lifts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w1080dp-h1920dp")
public class GlassTabSlideTest {
    /** An activity that notes each touch that reaches it, the way Instagram's screen would get it. */
    public static class Probe extends Activity {
        final List<Integer> actions = new ArrayList<>();

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            actions.add(event.getActionMasked());
            return super.dispatchTouchEvent(event);
        }
    }

    /** The glass, reduced to the pill's place and where it was told the highlight goes. */
    private static final class Pill implements GlassTabSlide.Host {
        final List<Float> follows = new ArrayList<>();
        boolean fails;

        @Override public boolean onPill(float x, float y) {
            return x >= 20 && x < 1060 && y >= 10 && y < 140;
        }

        @Override public void follow(float x) {
            if (fails) throw new IllegalStateException("boom");
            follows.add(x);
        }

        float last() {
            return follows.get(follows.size() - 1);
        }
    }

    private ActivityController<Probe> controller;
    private Probe activity;
    private FrameLayout root;
    private LinearLayout bar;
    private final List<Integer> clicked = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Pill pill = new Pill();
    private GlassTabSlide slide;
    private final int[] place = new int[2];
    private long downTime;
    private long clock;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Probe.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        bar = newBar();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
        params.topMargin = 1770;
        root.addView(bar, params);
        activity.setContentView(root, new FrameLayout.LayoutParams(1080, 1920));
        layout();
        slide = new GlassTabSlide(bar, pill, running::get);
        clock = SystemClock.uptimeMillis();
        HookStatus.clear();
    }

    @After public void tearDown() {
        HookStatus.clear();
        controller.close();
    }

    private LinearLayout newBar() {
        LinearLayout made = new LinearLayout(activity);
        made.setOrientation(LinearLayout.HORIZONTAL);
        made.setPadding(20, 10, 20, 10);
        for (int i = 0; i < 5; i++) {
            View tab = new View(activity);
            int index = i;
            tab.setOnClickListener(view -> {
                clicked.add(index);
                for (int child = 0; child < made.getChildCount(); child++) made.getChildAt(child).setSelected(child == index);
            });
            made.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        made.getChildAt(0).setSelected(true);
        return made;
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        bar.getLocationOnScreen(place);
    }

    /** The middle of tab [index], in the bar's pixels. */
    private float middle(int index) {
        View tab = bar.getChildAt(index);
        return (tab.getLeft() + tab.getRight()) / 2f;
    }

    /** A touch at [x], [y] in the bar's pixels, as the screen would report it. */
    private MotionEvent event(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) downTime = clock;
        MotionEvent event = MotionEvent.obtain(downTime, clock, action, place[0] + x, place[1] + y, 0);
        clock += 16;
        return event;
    }

    /** Hands a touch to the slide directly, with the activity as the callback Instagram's touches go on to. */
    private boolean touch(int action, float x, float y) {
        MotionEvent event = event(action, x, y);
        try {
            return slide.onTouch(event, activity);
        } finally {
            event.recycle();
        }
    }

    /** Sends a touch through the window's callback, the way the system does. */
    private void dispatch(int action, float x, float y) {
        MotionEvent event = event(action, x, y);
        try {
            activity.getWindow().getCallback().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Test public void aTapStaysInstagrams() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, middle(2), 75));
        assertEquals(GlassTabSlide.WATCHING, slide.state());
        assertFalse("a jitter under the slop is still a tap", touch(MotionEvent.ACTION_MOVE, middle(2) + 3, 76));
        assertFalse(touch(MotionEvent.ACTION_UP, middle(2) + 3, 76));
        assertEquals(GlassTabSlide.IDLE, slide.state());
        assertTrue("the highlight was never moved", pill.follows.isEmpty());
        assertTrue("Instagram had no cancel", activity.actions.isEmpty());
        assertTrue(clicked.isEmpty());
    }

    @Test public void aSidewaysSlideCarriesTheHighlightAndOpensTheTabItEndsOn() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, middle(0), 75));
        assertTrue("past the slop, sideways, it's a slide", touch(MotionEvent.ACTION_MOVE, middle(0) + 40, 78));
        assertEquals(GlassTabSlide.SLIDING, slide.state());
        assertEquals("Instagram's own handling of the finger is cancelled once",
                Arrays.asList(MotionEvent.ACTION_CANCEL), activity.actions);
        assertEquals(middle(0) + 40, pill.last(), 0.01f);
        assertTrue(touch(MotionEvent.ACTION_MOVE, middle(3), 90));
        assertEquals("the highlight follows the finger across the tabs", middle(3), pill.last(), 0.01f);
        assertTrue(touch(MotionEvent.ACTION_UP, middle(3), 90));
        assertEquals("the tab it ended on opens", Arrays.asList(3), clicked);
        assertTrue(bar.getChildAt(3).isSelected());
        assertTrue("the highlight goes back to the selected tab", Float.isNaN(pill.last()));
        assertEquals(GlassTabSlide.IDLE, slide.state());
        assertEquals("nothing more reached Instagram", 1, activity.actions.size());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(GlassTabSlide.COUNT_OPENED + " 1"));
    }

    @Test public void liftingOverTheOpenTabOpensNothing() {
        touch(MotionEvent.ACTION_DOWN, middle(0), 75);
        assertTrue(touch(MotionEvent.ACTION_MOVE, middle(2), 75));
        assertTrue(touch(MotionEvent.ACTION_MOVE, middle(0), 75));
        assertTrue(touch(MotionEvent.ACTION_UP, middle(0), 75));
        assertTrue("the open tab isn't clicked again", clicked.isEmpty());
        assertTrue(Float.isNaN(pill.last()));
    }

    @Test public void aCancelFromTheSystemOpensNothing() {
        touch(MotionEvent.ACTION_DOWN, middle(1), 75);
        assertTrue(touch(MotionEvent.ACTION_MOVE, middle(4), 75));
        assertTrue(touch(MotionEvent.ACTION_CANCEL, middle(4), 75));
        assertTrue(clicked.isEmpty());
        assertTrue(Float.isNaN(pill.last()));
        assertEquals(GlassTabSlide.IDLE, slide.state());
    }

    @Test public void pastTheEndsTheNearestTabOpens() {
        touch(MotionEvent.ACTION_DOWN, middle(1), 75);
        assertTrue(touch(MotionEvent.ACTION_MOVE, 1075, 75));
        assertTrue(touch(MotionEvent.ACTION_UP, 1075, 75));
        assertEquals(Arrays.asList(4), clicked);
        assertSame(bar.getChildAt(0), slide.tabAt(-30));
        assertSame(bar.getChildAt(2), slide.tabAt(middle(2)));
    }

    @Test public void upOrDownFirstIsNoSlide() {
        touch(MotionEvent.ACTION_DOWN, middle(2), 75);
        assertFalse(touch(MotionEvent.ACTION_MOVE, middle(2) + 5, 40));
        assertEquals(GlassTabSlide.REFUSED, slide.state());
        assertFalse("once refused, a later sideways move stays Instagram's", touch(MotionEvent.ACTION_MOVE, middle(4), 40));
        assertFalse(touch(MotionEvent.ACTION_UP, middle(4), 40));
        assertTrue(pill.follows.isEmpty());
        assertTrue(activity.actions.isEmpty());
        assertTrue(clicked.isEmpty());
        assertEquals(GlassTabSlide.IDLE, slide.state());
    }

    /** A hold on a tab with a long press of its own is Instagram's. On any other tab a hold can still slide. */
    @Test public void aHoldOnATabWithItsOwnLongPressStaysInstagrams() {
        bar.getChildAt(2).setOnLongClickListener(view -> true);
        long hold = ViewConfiguration.getLongPressTimeout() + 50;

        touch(MotionEvent.ACTION_DOWN, middle(2), 75);
        clock += hold;
        assertFalse(touch(MotionEvent.ACTION_MOVE, middle(4), 75));
        assertEquals(GlassTabSlide.REFUSED, slide.state());
        touch(MotionEvent.ACTION_UP, middle(4), 75);
        assertTrue(clicked.isEmpty());
        assertTrue(activity.actions.isEmpty());

        touch(MotionEvent.ACTION_DOWN, middle(2), 75);
        assertTrue("slid before its long press, it's a slide", touch(MotionEvent.ACTION_MOVE, middle(4), 75));
        touch(MotionEvent.ACTION_UP, middle(4), 75);
        assertEquals(Arrays.asList(4), clicked);

        touch(MotionEvent.ACTION_DOWN, middle(1), 75);
        clock += hold;
        assertTrue("a tab with no long press slides after a hold", touch(MotionEvent.ACTION_MOVE, middle(3), 75));
        touch(MotionEvent.ACTION_UP, middle(3), 75);
        assertEquals(Arrays.asList(4, 3), clicked);
    }

    @Test public void offThePillOrWithTheBarAwayNothingSlides() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 5, 75));
        assertEquals(GlassTabSlide.IDLE, slide.state());
        assertFalse(touch(MotionEvent.ACTION_MOVE, middle(3), 75));
        touch(MotionEvent.ACTION_UP, middle(3), 75);

        bar.setTranslationY(150);
        assertFalse(touch(MotionEvent.ACTION_DOWN, middle(1), 75));
        assertFalse("a bar slid away by a scroll doesn't slide", touch(MotionEvent.ACTION_MOVE, middle(3), 75));
        touch(MotionEvent.ACTION_UP, middle(3), 75);
        bar.setTranslationY(0);

        bar.setVisibility(View.GONE);
        assertFalse(touch(MotionEvent.ACTION_DOWN, middle(1), 75));
        assertFalse(touch(MotionEvent.ACTION_MOVE, middle(3), 75));
        assertTrue(pill.follows.isEmpty());
        assertTrue(clicked.isEmpty());
    }

    @Test public void aSecondFingerBeforeTheSlideLeavesItToInstagram() {
        touch(MotionEvent.ACTION_DOWN, middle(1), 75);
        assertFalse(touch(MotionEvent.ACTION_POINTER_DOWN, middle(1), 75));
        assertFalse(touch(MotionEvent.ACTION_MOVE, middle(3), 75));
        assertTrue(pill.follows.isEmpty());
    }

    @Test public void pausedEveryTouchGoesOnAndAPauseMidSlideOpensNothing() {
        running.set(false);
        assertFalse(touch(MotionEvent.ACTION_DOWN, middle(0), 75));
        assertFalse(touch(MotionEvent.ACTION_MOVE, middle(3), 75));
        assertFalse(touch(MotionEvent.ACTION_UP, middle(3), 75));
        assertTrue(pill.follows.isEmpty());

        running.set(true);
        touch(MotionEvent.ACTION_DOWN, middle(0), 75);
        assertTrue(touch(MotionEvent.ACTION_MOVE, middle(2), 75));
        running.set(false);
        assertTrue("Instagram had its cancel, so the rest goes nowhere", touch(MotionEvent.ACTION_MOVE, middle(3), 75));
        assertEquals(GlassTabSlide.DROPPED, slide.state());
        assertTrue(Float.isNaN(pill.last()));
        assertTrue(touch(MotionEvent.ACTION_UP, middle(3), 75));
        assertTrue(clicked.isEmpty());
        assertEquals(GlassTabSlide.IDLE, slide.state());
    }

    @Test public void theWindowsCallbackKeepsOnlyTheSlideFromInstagram() {
        Window window = activity.getWindow();
        Window.Callback before = window.getCallback();
        GlassTabSlide.install(window, slide);
        Window.Callback wrapped = window.getCallback();
        assertNotSame(before, wrapped);
        GlassTabSlide.install(window, slide);
        assertSame("wrapped once", wrapped, window.getCallback());
        assertSame(before, GlassTabSlide.innerOf(wrapped));

        dispatch(MotionEvent.ACTION_DOWN, middle(2), 75);
        dispatch(MotionEvent.ACTION_UP, middle(2), 75);
        assertEquals("a tap reaches Instagram whole", Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), activity.actions);

        activity.actions.clear();
        dispatch(MotionEvent.ACTION_DOWN, middle(0), 75);
        dispatch(MotionEvent.ACTION_MOVE, middle(1), 75);
        dispatch(MotionEvent.ACTION_MOVE, middle(4), 75);
        dispatch(MotionEvent.ACTION_UP, middle(4), 75);
        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), activity.actions);
        assertTrue(clicked.contains(4));
        // Window methods that take nothing or other arguments still reach the activity.
        assertEquals(before.onSearchRequested(), wrapped.onSearchRequested());
    }

    /** A rebuilt bar takes over the window's one slide watcher, and the scroll hider looks through it. */
    @Test public void aRebuiltBarTakesOverTheWatcherAndTheScrollHiderLooksThroughIt() {
        Window window = activity.getWindow();
        GlassScrollHide hide = new GlassScrollHide(bar, running::get);
        GlassScrollHide.install(window, hide);
        GlassTabSlide.install(window, slide);
        Window.Callback outer = window.getCallback();

        LinearLayout rebuilt = newBar();
        root.removeView(bar);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
        params.topMargin = 1770;
        root.addView(rebuilt, params);
        bar = rebuilt;
        layout();
        Pill next = new Pill();
        GlassTabSlide nextSlide = new GlassTabSlide(rebuilt, next, running::get);
        GlassTabSlide.install(window, nextSlide);
        GlassScrollHide.install(window, new GlassScrollHide(rebuilt, running::get));
        assertSame("no second watcher of either kind", outer, window.getCallback());

        dispatch(MotionEvent.ACTION_DOWN, middle(0), 75);
        dispatch(MotionEvent.ACTION_MOVE, middle(3), 75);
        assertEquals("the rebuilt bar gets the slide", middle(3), next.last(), 0.01f);
        assertTrue("the old bar's glass hears nothing", pill.follows.isEmpty());
        dispatch(MotionEvent.ACTION_UP, middle(3), 75);
    }

    /** Anything that throws is counted, and the gesture is Instagram's from there on. */
    @Test public void aFailureGivesTheGestureBackToInstagram() {
        GlassTabSlide.install(activity.getWindow(), slide);
        pill.fails = true;
        dispatch(MotionEvent.ACTION_DOWN, middle(0), 75);
        dispatch(MotionEvent.ACTION_MOVE, middle(2), 75);
        dispatch(MotionEvent.ACTION_MOVE, middle(3), 75);
        dispatch(MotionEvent.ACTION_UP, middle(3), 75);
        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), activity.actions);
        assertTrue(clicked.isEmpty());
        assertEquals(GlassTabSlide.IDLE, slide.state());
        String missing = String.valueOf(HookStatus.missing(FamilyNames.GLASS_TAB_BAR));
        assertTrue(missing, missing.contains("'tab slide' hook (it threw java.lang.IllegalStateException)"));
    }
}
