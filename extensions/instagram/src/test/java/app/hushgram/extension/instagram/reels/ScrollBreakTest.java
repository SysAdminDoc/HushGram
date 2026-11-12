/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Scroll break (#297): only time on the Home, Reels and Explore tabs counts, and when the choice is
 * up a break screen covers those three, with the tab bar showing through, for 5 minutes with a countdown.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ScrollBreakTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int VIEWER = 7301, BAR = 7302, REELS_TAB = 7303, HOME_TAB = 7304, SEARCH_TAB = 7305,
            PROFILE_TAB = 7306;
    private static final long SECOND = 1_000L, MINUTE = 60 * SECOND;
    private static final int BAR_HEIGHT = 50;

    private long now = 5_000_000L;
    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private ViewGroup bar;
    private View reelsTab, homeTab, searchTab, profileTab, viewer;

    @Before public void prepare() {
        Settings.SCROLL_BREAK.resetToDefault();
        PauseForTests.resume();
        ScrollBreak.forgetForTests();
        ScrollBreak.clock = () -> now;
        FullScreenBars.idForTests(FullScreenBars.VIEWER, VIEWER);
        FullScreenBars.idForTests(FullScreenBars.TAB_BAR, BAR);
        FullScreenBars.idForTests(FullScreenBars.REELS_TAB, REELS_TAB);
        FullScreenBars.idForTests(FullScreenBars.HOME_TAB, HOME_TAB);
        FullScreenBars.idForTests(FullScreenBars.SEARCH_TAB, SEARCH_TAB);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);
        root = new FrameLayout(activity);
        activity.setContentView(root);
        bar = new LinearLayout(activity);
        bar.setId(BAR);
        homeTab = tab(HOME_TAB);
        reelsTab = tab(REELS_TAB);
        searchTab = tab(SEARCH_TAB);
        profileTab = tab(PROFILE_TAB);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT, Gravity.BOTTOM));
        select(homeTab);
        layout();
    }

    @After public void close() {
        ScrollBreak.forgetForTests();
        controller.close();
        Settings.SCROLL_BREAK.resetToDefault();
        PauseForTests.resume();
    }

    private View tab(int id) {
        View tab = new View(activity);
        tab.setId(id);
        bar.addView(tab, new LinearLayout.LayoutParams(10, 40));
        return tab;
    }

    private void select(View tab) {
        for (View each : new View[] {homeTab, reelsTab, searchTab, profileTab}) each.setSelected(each == tab);
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    /** Instagram in front, the way it comes back from the background. */
    private void open() {
        ScrollBreak.started(now);
        ScrollBreak.resumed(activity);
        layout();
    }

    /** [ms] on whatever screen shows, a look each second. */
    private void spend(long ms) {
        for (long left = ms; left > 0; left -= SECOND) {
            now += Math.min(SECOND, left);
            ScrollBreak.tick();
            layout();
        }
    }

    private ScrollBreak.Cover cover() {
        return ScrollBreak.coverOn(activity);
    }

    @Test public void offCountsNothing() {
        open();
        spend(70 * MINUTE);
        assertNull(cover());
    }

    @Test public void tenMinutesOnHomeBringsAFiveMinuteBreakAboveTheTabBar() {
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        open();
        spend(10 * MINUTE - SECOND);
        assertNull("not yet", cover());
        spend(SECOND);
        ScrollBreak.Cover cover = cover();
        assertNotNull("ten minutes on Home", cover);
        assertEquals("5:00", cover.countdownText());
        assertEquals("it covers the whole window", H, cover.getHeight());
        assertEquals("but the tab bar shows through it", new Rect(0, H - BAR_HEIGHT, W, H), cover.leftOpen());
        assertTrue(String.valueOf(cover.getContentDescription()), String.valueOf(cover.getContentDescription())
                .contains("5:00"));

        spend(MINUTE);
        assertEquals("4:00", cover().countdownText());
        spend(4 * MINUTE - SECOND);
        assertEquals("0:01", cover().countdownText());
        spend(SECOND);
        assertNull("the break is over", cover());
        // The count started over with it.
        spend(10 * MINUTE - SECOND);
        assertNull(cover());
    }

    @Test public void otherTabsDontCountAndAreNeverCovered() {
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        select(profileTab);
        open();
        spend(30 * MINUTE);
        assertNull("the profile tab never counts", cover());

        select(homeTab);
        spend(10 * MINUTE);
        assertNotNull(cover());
        select(profileTab);
        spend(SECOND);
        assertNull("off the three tabs, nothing covers the screen", cover());
        spend(MINUTE);
        select(homeTab);
        spend(SECOND);
        assertEquals("the break kept running meanwhile", "3:58", cover().countdownText());
    }

    @Test public void reelsAndExploreCountWithHome() {
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        select(searchTab);
        open();
        spend(4 * MINUTE);
        select(reelsTab);
        spend(3 * MINUTE);
        // A reel opened from Explore fills the window with Explore still selected.
        select(searchTab);
        viewer = new FrameLayout(activity);
        viewer.setId(VIEWER);
        root.addView(viewer, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        spend(3 * MINUTE - SECOND);
        assertNull(cover());
        spend(SECOND);
        assertNotNull(cover());
    }

    @Test public void aLateLookCountsAFewSecondsAtMost() {
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        open();
        // The phone slept with Instagram open, and the next look came half an hour later.
        now += 30 * MINUTE;
        ScrollBreak.tick();
        spend(10 * MINUTE - ScrollBreak.MAX_STEP_MS - SECOND);
        assertNull(cover());
        spend(SECOND);
        assertNotNull(cover());
    }

    @Test public void fiveMinutesInTheBackgroundStartsTheCountOver() {
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        open();
        spend(9 * MINUTE);
        ScrollBreak.paused(activity);
        ScrollBreak.stopped(now);
        now += MINUTE;
        open();
        spend(MINUTE);
        assertNotNull("a minute away isn't a break", cover());

        ScrollBreak.forgetForTests();
        ScrollBreak.clock = () -> now;
        open();
        spend(9 * MINUTE);
        ScrollBreak.paused(activity);
        ScrollBreak.stopped(now);
        now += ScrollBreak.BREAK_MS;
        open();
        spend(9 * MINUTE);
        assertNull("five minutes away was the break", cover());
    }

    @Test public void turningItOffOrPausingTakesTheBreakScreenAway() {
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        open();
        spend(10 * MINUTE);
        assertNotNull(cover());
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        spend(SECOND);
        assertNull("paused", cover());
        PauseForTests.resume();
        Settings.SCROLL_BREAK.save(ScrollBreakTime.OFF);
        spend(SECOND);
        assertNull("off", cover());
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        spend(MINUTE);
        assertNull("off forgot the count", cover());
    }

    @Test public void theBreakScreenTakesEveryTouch() {
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        open();
        spend(10 * MINUTE);
        long at = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, W / 2f, H / 2f, 0);
        assertTrue(cover().dispatchTouchEvent(down));
        down.recycle();
    }

    /** A floating glass pill sits above the bottom with room at its sides: everything but the pill is covered. */
    @Test public void aFloatingTabBarIsTheOnlyThingLeftOpen() {
        FrameLayout.LayoutParams floating = new FrameLayout.LayoutParams(W - 40, BAR_HEIGHT, Gravity.BOTTOM);
        floating.leftMargin = 20;
        floating.bottomMargin = 30;
        bar.setLayoutParams(floating);
        Settings.SCROLL_BREAK.save(ScrollBreakTime.TEN_MINUTES);
        open();
        spend(10 * MINUTE);
        Rect pill = new Rect(20, H - 30 - BAR_HEIGHT, W - 20, H - 30);
        assertEquals(pill, cover().leftOpen());
        assertFalse("a tap on the pill goes to the tab bar", touch(pill.centerX(), pill.centerY()));
        assertTrue("beside the pill is covered", touch(5, pill.centerY()));
        assertTrue("under the pill is covered", touch(pill.centerX(), H - 10));
    }

    /** Whether the break screen takes a touch that starts at [x],[y]. */
    private boolean touch(int x, int y) {
        long at = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0);
        try {
            return cover().dispatchTouchEvent(down);
        } finally {
            down.recycle();
        }
    }

    @Test public void theCountdownRoundsUp() {
        assertEquals("5:00", ScrollBreak.clockText(ScrollBreak.BREAK_MS));
        assertEquals("5:00", ScrollBreak.clockText(ScrollBreak.BREAK_MS - 999));
        assertEquals("1:01", ScrollBreak.clockText(61 * SECOND));
        assertEquals("0:01", ScrollBreak.clockText(1));
        assertEquals("0:00", ScrollBreak.clockText(0));
    }
}
