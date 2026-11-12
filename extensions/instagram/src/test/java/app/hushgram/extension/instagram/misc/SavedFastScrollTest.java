/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Fast scroll in Saved (#644): a handle at the grid's right edge while Saved or a collection scrolls, that
 * drags the grid from top to end, fades when it stops, and leaves every other touch to the grid.
 */
@RunWith(RobolectricTestRunner.class)
public class SavedFastScrollTest {
    private static final int COLLECTION = 0x7f0b8101;
    private static final int TILE = 0x7f0b8102;
    /** How much of a grid shows, and how long a long one is: ten screens. */
    private static final int SHOWS = 400;
    private static final int LONG = 4000;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private FrameLayout frame;
    private ViewGroup decor;

    /** A Saved grid as its scroll bar sees it: [length] pixels long, moved only by scrollBy. */
    public static class Grid extends RecyclerView {
        final int length;
        int at;
        final List<Integer> intercepted = new ArrayList<>();

        public Grid(Context context, int length) {
            super(context);
            this.length = length;
        }

        @Override public int computeVerticalScrollOffset() {
            return at;
        }

        @Override public int computeVerticalScrollRange() {
            return length;
        }

        @Override public int computeVerticalScrollExtent() {
            return getHeight();
        }

        @Override public void scrollBy(int x, int y) {
            at = Math.max(0, Math.min(length - getHeight(), at + y));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            intercepted.add(event.getActionMasked());
            return false;
        }
    }

    @Before
    public void prepare() {
        GlassTabBar.idForTests("save_collection_viewpager", COLLECTION);
        GlassTabBar.idForTests("saved_collection_thumbnail", TILE);
        controller = Robolectric.buildActivity(Activity.class).setup();
        frame = new FrameLayout(controller.get());
        controller.get().setContentView(frame);
        decor = (ViewGroup) controller.get().getWindow().getDecorView();
        Settings.SAVED_FAST_SCROLL.save(true);
        SavedFastScroll.track(controller.get());
    }

    @After
    public void restore() {
        SavedFastScroll.release(controller.get());
        for (String name : MessagesLock.SAVED_PAGES) GlassTabBar.idForTests(name, null);
        controller.close();
        Settings.SAVED_FAST_SCROLL.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    /** A collection's page as 454 lays it out: a pager named for Saved holding the grid, [length] long. */
    private Grid collection(int length) {
        FrameLayout pager = new FrameLayout(controller.get());
        pager.setId(COLLECTION);
        Grid grid = new Grid(controller.get(), length);
        pager.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        frame.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        settle();
        return grid;
    }

    private void settle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private SavedFastScroll.Tracker tracker() {
        SavedFastScroll.Tracker tracker = SavedFastScroll.trackerForTests(decor);
        assertNotNull("the window is watched", tracker);
        return tracker;
    }

    /** Scrolls [grid] by [by] as a flick would, lets the window hear it, and lays out a new handle before the next frame. */
    private void flick(Grid grid, int by) {
        grid.scrollBy(0, by);
        tracker().scrolled();
        settle();
    }

    private SavedFastScroll.Handle handle() {
        SavedFastScroll.Handle handle = tracker().handleForTests();
        assertNotNull("a handle was put up", handle);
        return handle;
    }

    private boolean handleShows() {
        SavedFastScroll.Handle handle = tracker().handleForTests();
        return handle != null && handle.getVisibility() == View.VISIBLE;
    }

    private boolean touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            return decor.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void scrollingACollectionShowsTheHandleAtItsRightEdgeUntilItStops() {
        Grid grid = collection(LONG);
        tracker().look();
        assertFalse("nothing shows before a scroll", handleShows());

        flick(grid, 1800);
        SavedFastScroll.Handle handle = handle();
        assertTrue(handleShows());
        int[] at = new int[2];
        grid.getLocationInWindow(at);
        assertEquals("the track runs down the grid's right edge", at[0] + grid.getWidth(), handle.track.right);
        assertTrue("inside the grid's height", handle.track.top > at[1] && handle.track.bottom < at[1] + SHOWS);
        float share = (handle.top - handle.track.top) / (handle.track.height() - handle.length());
        assertEquals("halfway down the grid is halfway down the track", 0.5f, share, 0.01f);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SavedFastScroll.SHOW_FOR_MS - 100));
        assertTrue("still there just after the grid stops", handleShows());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SavedFastScroll.FADE_MS + 400));
        assertFalse("gone a moment later", handleShows());
    }

    @Test
    public void draggingTheHandleRunsTheGridToItsEndAndBackToTheTop() {
        Grid grid = collection(LONG);
        tracker().look();
        flick(grid, 400);
        SavedFastScroll.Handle handle = handle();
        float x = handle.track.right - 3;
        float y = handle.top + handle.length() / 2;

        assertTrue("the handle takes the finger", touch(MotionEvent.ACTION_DOWN, x, y));
        assertEquals("a running fling is stopped first", List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL),
                grid.intercepted);
        touch(MotionEvent.ACTION_MOVE, x, handle.track.bottom + 200);
        assertEquals("the bottom of the track is the end of the grid", LONG - SHOWS, grid.at);
        assertEquals(handle.track.bottom - handle.length(), handle.top, 0.5f);

        touch(MotionEvent.ACTION_MOVE, x, handle.track.top + (handle.track.height() - handle.length()) / 2f
                + handle.length() / 2);
        assertEquals("the middle of the track is the middle of the grid", (LONG - SHOWS) / 2f, grid.at, SHOWS / 20f);

        touch(MotionEvent.ACTION_MOVE, x, -500);
        assertEquals("the top of the track is the top", 0, grid.at);
        touch(MotionEvent.ACTION_UP, x, -500);
        assertFalse(handle.dragging);
        assertTrue("it stays while the finger has only just let go", handleShows());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SavedFastScroll.SHOW_FOR_MS + SavedFastScroll.FADE_MS + 400));
        assertFalse(handleShows());
    }

    /** A tap or drag anywhere but the handle is the grid's, shown or not. */
    @Test
    public void aTouchBesideTheHandleGoesOnToTheGrid() {
        Grid grid = collection(LONG);
        tracker().look();
        flick(grid, 400);
        SavedFastScroll.Handle handle = handle();
        float y = handle.top + handle.length() / 2;

        touch(MotionEvent.ACTION_DOWN, 40, y);
        touch(MotionEvent.ACTION_MOVE, 40, y - 30);
        touch(MotionEvent.ACTION_UP, 40, y - 30);
        assertEquals("the grid had the whole drag", List.of(30), grid.drags);
        assertTrue(grid.intercepted.isEmpty());

        touch(MotionEvent.ACTION_DOWN, handle.track.right - 3, handle.track.bottom - 2);
        touch(MotionEvent.ACTION_UP, handle.track.right - 3, handle.track.bottom - 2);
        assertEquals("the edge away from the handle is the grid's too", 2, grid.drags.size());

        handle.hideNow();
        touch(MotionEvent.ACTION_DOWN, handle.track.right - 3, y);
        touch(MotionEvent.ACTION_UP, handle.track.right - 3, y);
        assertEquals("a hidden handle takes nothing", 3, grid.drags.size());
    }

    @Test
    public void aGridOfAScreenOrTwoGetsNoHandle() {
        Grid grid = collection(SHOWS * 2);
        tracker().look();
        flick(grid, 200);
        assertFalse(handleShows());
    }

    @Test
    public void aGridOutsideSavedGetsNoHandle() {
        Grid grid = new Grid(controller.get(), LONG);
        frame.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        settle();
        tracker().look();
        flick(grid, 800);
        assertFalse(handleShows());
        assertNull(SavedFastScroll.savedList(decor));
    }

    /** Off or paused, a layout looks at nothing and a scroll shows nothing. On again, it works with no restart. */
    @Test
    public void withTheSwitchOffOrPausedNothingShows() {
        Settings.SAVED_FAST_SCROLL.save(false);
        Grid grid = collection(LONG);
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        flick(grid, 800);
        assertFalse("off", handleShows());
        Settings.SAVED_FAST_SCROLL.save(true);
        flick(grid, 800);
        assertFalse("the layouts while off found no grid", handleShows());

        Settings.SAVED_FAST_SCROLL.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        tracker().look();
        flick(grid, 800);
        assertFalse("paused", handleShows());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        tracker().look();
        flick(grid, 800);
        assertTrue("on again, no restart", handleShows());
    }

    /** Saved's first page names each collection's tile, not its grid: the grid is the one around them. */
    @Test
    public void savedsTilesLeadToTheGridAroundThem() {
        Grid grid = new Grid(controller.get(), LONG);
        for (int i = 0; i < 3; i++) {
            FrameLayout tile = new FrameLayout(controller.get());
            View thumbnail = new View(controller.get());
            thumbnail.setId(TILE);
            tile.addView(thumbnail, new FrameLayout.LayoutParams(100, 100));
            grid.addView(tile, new ViewGroup.LayoutParams(100, 100));
        }
        frame.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        settle();
        assertSame(grid, SavedFastScroll.savedList(decor));
    }

    /**
     * Saved opened from the menu sits in the tab bar's pager, itself a RecyclerView: the grid inside
     * Saved's page is the one followed, not the pager around it, and scrolling it shows the handle.
     */
    @Test
    public void savedInsideTheTabPagerFollowsItsOwnGrid() {
        Grid tabs = new Grid(controller.get(), SHOWS);
        FrameLayout pager = new FrameLayout(controller.get());
        pager.setId(COLLECTION);
        Grid grid = new Grid(controller.get(), LONG);
        pager.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        tabs.addView(pager, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        frame.addView(tabs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        settle();
        assertSame(grid, SavedFastScroll.savedList(decor));

        tracker().look();
        flick(grid, 1800);
        assertTrue(handleShows());
    }

    /** A collection's pager keeps its neighbours' grids laid out beside it; the one on screen is the one. */
    @Test
    public void thePagersGridOnScreenIsTheOneFollowed() {
        FrameLayout pager = new FrameLayout(controller.get());
        pager.setId(COLLECTION);
        Grid beside = new Grid(controller.get(), LONG);
        FrameLayout.LayoutParams off = new FrameLayout.LayoutParams(300, SHOWS);
        off.leftMargin = 2000;
        pager.addView(beside, off);
        Grid shown = new Grid(controller.get(), LONG);
        pager.addView(shown, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        frame.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        settle();
        assertSame(shown, SavedFastScroll.savedList(decor));
    }

    /** With Lock Saved's cover up there's no handle to give the grid away. */
    @Test
    public void lockSavedsCoverKeepsTheHandleAway() {
        Grid grid = collection(LONG);
        View cover = new View(controller.get());
        cover.setTag(MessagesLock.COVER_TAG);
        frame.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SHOWS));
        settle();
        assertNull(SavedFastScroll.savedList(decor));
        tracker().look();
        flick(grid, 800);
        assertFalse(handleShows());
    }

    /** Leaving Saved takes the handle away at once rather than leaving it to fade over the next screen. */
    @Test
    public void leavingSavedHidesTheHandleAtOnce() {
        Grid grid = collection(LONG);
        tracker().look();
        flick(grid, 800);
        assertTrue(handleShows());
        frame.removeAllViews();
        settle();
        tracker().look();
        assertFalse(handleShows());
    }
}
