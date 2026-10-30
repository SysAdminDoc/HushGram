/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Color;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Slide across the tabs on Instagram's own tab bar (#201): with Glass tab bar off, the switch on wraps the
 * window like the glass slide does, a light box follows the finger over the tabs, and lifting it opens the
 * tab under it. Off, with glass on, or paused, the bar is Instagram's alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w1080dp-h1920dp")
public class PlainTabSlideTest {
    private static final int BAR_ID = 0x7f0b0122;
    /** The room Instagram keeps under its tabs for the navigation bar. */
    private static final int NAV_ROOM = 40;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<GlassTabSlideTest.Probe> controller;
    private GlassTabSlideTest.Probe activity;
    private LinearLayout bar;
    private final List<Integer> clicked = new ArrayList<>();
    private Integer previousId;
    private final int[] place = new int[2];
    private long down;
    private long clock;

    @Before public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.GLASS_TAB_BAR.save(false);
        Settings.GLASS_TAB_BAR_SLIDE.save(true);
        controller = Robolectric.buildActivity(GlassTabSlideTest.Probe.class).setup();
        activity = controller.get();
        // Not a FrameLayout, so the glass would leave this bar alone even with its switch on.
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(new View(activity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bar = new LinearLayout(activity);
        bar.setId(BAR_ID);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.BLACK);
        bar.setPadding(0, 0, 0, NAV_ROOM);
        for (int i = 0; i < 5; i++) {
            View tab = new View(activity);
            int index = i;
            tab.setOnClickListener(view -> {
                clicked.add(index);
                for (int child = 0; child < bar.getChildCount(); child++) bar.getChildAt(child).setSelected(child == index);
            });
            bar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        bar.getChildAt(0).setSelected(true);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 190));
        activity.setContentView(root);
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) { previousId = ids.put(GlassTabBar.TAB_BAR, BAR_ID); }
        layout();
        clock = SystemClock.uptimeMillis();
        HookStatus.clear();
    }

    @After public void tearDown() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            if (previousId == null) ids.remove(GlassTabBar.TAB_BAR);
            else ids.put(GlassTabBar.TAB_BAR, previousId);
        }
        controller.close();
        HookStatus.clear();
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_SLIDE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        GlassTabBar.resetForTests();
    }

    private void layout() {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        bar.getLocationOnScreen(place);
    }

    private float middle(int index) {
        View tab = bar.getChildAt(index);
        return (tab.getLeft() + tab.getRight()) / 2f;
    }

    /** The middle of the tabs' row, above the navigation room. */
    private float row() {
        return (bar.getHeight() - NAV_ROOM) / 2f;
    }

    /** Sends a touch at [x], [y] in the bar's pixels through the window's callback, the way the system does. */
    private void send(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) down = clock;
        MotionEvent event = MotionEvent.obtain(down, clock, action, place[0] + x, place[1] + y, 0);
        clock += 16;
        try {
            activity.getWindow().getCallback().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Test public void withGlassOffASlideCarriesABoxOverTheTabsAndOpensTheTabItLiftsOver() {
        Window.Callback before = activity.getWindow().getCallback();
        GlassTabBar.apply(activity);
        PlainTabSlide plain = PlainTabSlide.of(bar);
        assertNotNull("Instagram's own bar got a slide", plain);
        assertNotSame("the window's touches go through the slide", before, activity.getWindow().getCallback());
        int tab = bar.getChildAt(0).getWidth();

        send(MotionEvent.ACTION_DOWN, middle(0), row());
        send(MotionEvent.ACTION_MOVE, middle(0) + 40, row());
        assertTrue(plain.shown());
        Rect box = plain.highlight.getBounds();
        assertEquals("the box sits on the finger", middle(0) + 40, box.exactCenterX(), 1f);
        assertEquals("a tab wide, less its inset", tab - 2 * PlainTabSlide.INSET_DP, box.width());
        assertEquals(PlainTabSlide.INSET_DP, box.top);
        assertEquals("it stays clear of the navigation room", bar.getHeight() - NAV_ROOM - PlainTabSlide.INSET_DP,
                box.bottom);
        assertEquals("over the black bar, a light box", PlainTabSlide.ON_DARK,
                plain.highlight.getColor().getDefaultColor());

        send(MotionEvent.ACTION_MOVE, middle(3), row());
        assertEquals(middle(3), plain.highlight.getBounds().exactCenterX(), 1f);
        send(MotionEvent.ACTION_MOVE, bar.getWidth() - 2, row());
        assertEquals("past the last tab it stops at the bar's end", bar.getWidth() - PlainTabSlide.INSET_DP,
                plain.highlight.getBounds().right);
        assertTrue("nothing opens while the finger is down", clicked.isEmpty());

        send(MotionEvent.ACTION_UP, middle(3), row());
        assertEquals("the tab it lifted over opens", Arrays.asList(3), clicked);
        assertTrue(bar.getChildAt(3).isSelected());
        assertFalse("the box goes once the finger lifts", plain.shown());
        assertEquals("Instagram had the down and one cancel, nothing more",
                Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), activity.actions);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(GlassTabSlide.COUNT_OPENED + " 1"));
    }

    @Test public void aTapAndATouchInTheNavigationRoomStayInstagrams() {
        GlassTabBar.apply(activity);
        PlainTabSlide plain = PlainTabSlide.of(bar);
        assertNotNull(plain);
        assertTrue(plain.onPill(middle(2), row()));
        assertFalse("the navigation room isn't the tabs' row", plain.onPill(middle(2), bar.getHeight() - NAV_ROOM / 2f));

        send(MotionEvent.ACTION_DOWN, middle(2), row());
        send(MotionEvent.ACTION_UP, middle(2), row());
        assertEquals(GlassTabSlide.IDLE, plain.slide.state());
        // The click a tap makes is posted, so let it run.
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a tap opens its tab, Instagram's way", Arrays.asList(2), clicked);
        clicked.clear();
        send(MotionEvent.ACTION_DOWN, middle(0), bar.getHeight() - NAV_ROOM / 2f);
        send(MotionEvent.ACTION_MOVE, middle(3), bar.getHeight() - NAV_ROOM / 2f);
        send(MotionEvent.ACTION_UP, middle(3), bar.getHeight() - NAV_ROOM / 2f);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(plain.shown());
        assertTrue("no slide opened anything", clicked.isEmpty());
        assertEquals("every touch reached Instagram", Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), activity.actions);
    }

    @Test public void aLightBarGetsADarkBox() {
        bar.setBackgroundColor(Color.WHITE);
        GlassTabBar.apply(activity);
        PlainTabSlide plain = PlainTabSlide.of(bar);
        assertNotNull(plain);
        plain.follow(middle(1));
        assertEquals(PlainTabSlide.ON_LIGHT, plain.highlight.getColor().getDefaultColor());
        plain.follow(Float.NaN);
        assertFalse(plain.shown());
    }

    @Test public void aSecondResumeKeepsTheSameSlide() {
        GlassTabBar.apply(activity);
        PlainTabSlide first = PlainTabSlide.of(bar);
        Window.Callback wrapped = activity.getWindow().getCallback();
        GlassTabBar.apply(activity);
        assertSame(first, PlainTabSlide.of(bar));
        assertSame("the window is wrapped once", wrapped, activity.getWindow().getCallback());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(GlassTabSlide.COUNT_WATCHING + " 1"));
    }

    @Test public void theSwitchOffOrGlassOnLeavesInstagramsBarAlone() {
        Window.Callback before = activity.getWindow().getCallback();
        Settings.GLASS_TAB_BAR_SLIDE.save(false);
        GlassTabBar.apply(activity);
        assertNull(PlainTabSlide.of(bar));
        assertSame(before, activity.getWindow().getCallback());

        Settings.GLASS_TAB_BAR_SLIDE.save(true);
        Settings.GLASS_TAB_BAR.save(true);
        GlassTabBar.apply(activity);
        assertNull("with glass on, the slide is the glass's", PlainTabSlide.of(bar));
        assertSame(before, activity.getWindow().getCallback());
    }

    @Test public void pausedASlideIsInstagrams() {
        GlassTabBar.apply(activity);
        PlainTabSlide plain = PlainTabSlide.of(bar);
        assertNotNull(plain);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        send(MotionEvent.ACTION_DOWN, middle(0), row());
        send(MotionEvent.ACTION_MOVE, middle(3), row());
        send(MotionEvent.ACTION_UP, middle(3), row());
        assertFalse(plain.shown());
        assertTrue(clicked.isEmpty());
        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
                activity.actions);

        // Pause is read per touch, like the glass's slide, so lifting it needs no restart.
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        send(MotionEvent.ACTION_DOWN, middle(0), row());
        send(MotionEvent.ACTION_MOVE, middle(0) + 40, row());
        send(MotionEvent.ACTION_MOVE, middle(3), row());
        send(MotionEvent.ACTION_UP, middle(3), row());
        assertEquals("unpaused, the same slide opens the tab again", Arrays.asList(3), clicked);
    }
}
