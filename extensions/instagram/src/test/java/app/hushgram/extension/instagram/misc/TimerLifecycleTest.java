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
import android.content.Context;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The glass bar, its scroll hide and the Saved tab keep no timer or listener running once there's
 * nothing for it to do: with the switch off, while HushGram is paused, when the screen goes away and
 * once what it waited for has happened (#129). The glass bar's wait on Reels (#170) is held to the same.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w1080dp-h1920dp")
public class TimerLifecycleTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int BAR_ID = 0x7f0b0101;
    private static final int REELS_ID = 0x7f0b0170;
    private static final int INFO_ID = 0x7f0b0171;
    private static final int SCRUBBER_ID = 0x7f0b0172;
    private static final int MEDIA_ID = 0x7f0b0173;
    private static final int FOOTERS_ID = 0x7f0b0174;
    private static final int REELS_TAB_ID = 0x7f0b0175;

    /** A list whose position the test sets. A group, like every list a scroll moves: a lone view is never taken for one. */
    static final class FakeList extends FrameLayout {
        boolean awayFromTop = true;

        FakeList(Context context) {
            super(context);
        }

        @Override public boolean canScrollVertically(int direction) {
            return direction < 0 ? awayFromTop : true;
        }
    }

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private FakeList list;
    private View bar;
    private final AtomicBoolean running = new AtomicBoolean(true);
    /** How many times the scroll hide asked whether it may run: once a touch, and once each look of its watch. */
    private final AtomicInteger asked = new AtomicInteger();
    private GlassScrollHide hide;
    private long clock;

    @Before public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        list = new FakeList(activity);
        bar = new View(activity);
        root.addView(list, new FrameLayout.LayoutParams(1080, 1800));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(1080, 150);
        barParams.topMargin = 1770;
        root.addView(bar, barParams);
        activity.setContentView(root, new FrameLayout.LayoutParams(1080, 1920));
        layout();
        hide = new GlassScrollHide(bar, () -> {
            asked.incrementAndGet();
            return running.get();
        });
        clock = SystemClock.uptimeMillis();
    }

    @After public void tearDown() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.remove(GlassTabBar.TAB_BAR);
            ids.remove(GlassTabBar.REELS_ROOT);
            ids.remove(GlassTabBar.REEL_INFO);
            ids.remove(GlassTabBar.SCRUBBER);
            ids.remove(GlassTabBar.REEL_MEDIA);
            ids.remove(GlassTabBar.REEL_FOOTERS);
        }
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_REELS_HIDE.resetToDefault();
        Settings.SAVED_ON_PROFILE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        GlassTabBar.resetForTests();
        controller.close();
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
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

    /** Scrolls the list down with a finger, so the bar slides away and its watch starts. */
    private void hideTheBar() {
        touch(MotionEvent.ACTION_DOWN, 500, 900);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 900 - 30 * i);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isHidden());
        int before = asked.get();
        idle(1000);
        assertTrue("the watch looks at the list while the bar is away", asked.get() > before + 3);
    }

    /** Lets [millis] pass on the main thread and says how many times the scroll hide asked whether it may run. */
    private int askedOver(long millis) {
        int before = asked.get();
        idle(millis);
        return asked.get() - before;
    }

    private static void idle(long millis) {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS);
    }

    /** How many listeners of the kind [field] names the window's observer holds, from the observer's own list. */
    private int windowListeners(String field) {
        Object listeners = ReflectionHelpers.getField(activity.getWindow().getDecorView().getViewTreeObserver(), field);
        if (listeners == null) return 0;
        Integer size = ReflectionHelpers.callInstanceMethod(listeners, "size");
        return size;
    }

    @Test public void theScrollHideWatchStopsOnceTheListIsBackAtItsTop() {
        hideTheBar();
        list.awayFromTop = false;
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        assertEquals("no look after the bar came back", 0, askedOver(5000));
    }

    @Test public void theScrollHideWatchStopsWhenHushGramIsPaused() {
        hideTheBar();
        running.set(false);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        assertEquals("no look while paused", 0, askedOver(5000));
    }

    @Test public void theScrollHideWatchStopsWhenTheBarLeavesTheWindow() {
        hideTheBar();
        root.removeView(bar);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        assertEquals("no look at a bar that left", 0, askedOver(5000));
    }

    @Test public void theScrollHideWatchStopsWhenTheScreenGoesToTheBackground() {
        hideTheBar();
        // What the glass bar does when its activity pauses.
        hide.restoreNow();
        assertFalse(hide.isHidden());
        assertEquals("no look once restored", 0, askedOver(5000));
    }

    /**
     * The bar's frame listener, which also runs its 500 ms search for a missing view, is never put on
     * a window with the switch off or HushGram paused, and comes off with the bar.
     */
    @Test public void theGlassBarListensToNoFrameWhileOffOrPausedAndStopsWithTheBar() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.put(GlassTabBar.TAB_BAR, BAR_ID);
        }
        root.removeView(bar);
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setId(BAR_ID);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
        params.topMargin = 1770;
        root.addView(tabs, params);
        layout();
        int frames = windowListeners("mOnPreDrawListeners");

        Settings.GLASS_TAB_BAR.resetToDefault();
        assertFalse(Settings.GLASS_TAB_BAR.get());
        GlassTabBar.apply(activity);
        assertEquals("off", frames, windowListeners("mOnPreDrawListeners"));

        Settings.GLASS_TAB_BAR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        GlassTabBar.apply(activity);
        assertEquals("paused", frames, windowListeners("mOnPreDrawListeners"));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        GlassTabBar.apply(activity);
        assertEquals("on, the bar listens to the window's frames", frames + 1, windowListeners("mOnPreDrawListeners"));

        root.removeView(tabs);
        assertEquals("and stops when the bar leaves the window", frames, windowListeners("mOnPreDrawListeners"));
    }

    /** Puts a tab bar Instagram's id names where the plain bar was, for the glass to restyle. */
    private LinearLayout namedTabBar() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.put(GlassTabBar.TAB_BAR, BAR_ID);
        }
        root.removeView(bar);
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setId(BAR_ID);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
        params.topMargin = 1770;
        root.addView(tabs, params);
        layout();
        return tabs;
    }

    /**
     * Selects a Reels tab in [tabs], where Instagram hides its bar for a paused reel and the viewer keeps
     * the pill's slot. With another tab selected the hidden bar gives the slot back (#347).
     */
    private void selectReelsTab(LinearLayout tabs) {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.put(GlassTabBar.REELS_TAB, REELS_TAB_ID);
        }
        View reelsTab = new View(activity);
        reelsTab.setId(REELS_TAB_ID);
        tabs.addView(reelsTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        reelsTab.setSelected(true);
        layout();
    }

    /** Puts a Reels viewer over the list, down to the bar's top. */
    private FrameLayout showReels() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.put(GlassTabBar.REELS_ROOT, REELS_ID);
        }
        FrameLayout viewer = new FrameLayout(activity);
        viewer.setId(REELS_ID);
        root.addView(viewer, 1, new FrameLayout.LayoutParams(1080, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        return viewer;
    }

    private static int bottomMargin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
    }

    /** A reel in a Reels viewer, shaped as Instagram 451 builds one. */
    private static final class Reel {
        FrameLayout viewer;
        LinearLayout column;
        FrameLayout gesture;
        View media;
        FrameLayout layer;
        FrameLayout infoBand;
        FrameLayout scrubBand;
        LinearLayout footers;
    }

    /**
     * Puts a Reels viewer over the list: a column of the reel, which takes what the footer under it
     * leaves, and the footer, empty. In the reel, the video and over it a layer of controls, with the
     * caption and the seek bar each in a band of its own.
     */
    private Reel showReel() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.put(GlassTabBar.REELS_ROOT, REELS_ID);
            ids.put(GlassTabBar.REEL_INFO, INFO_ID);
            ids.put(GlassTabBar.SCRUBBER, SCRUBBER_ID);
            ids.put(GlassTabBar.REEL_MEDIA, MEDIA_ID);
            ids.put(GlassTabBar.REEL_FOOTERS, FOOTERS_ID);
        }
        int match = ViewGroup.LayoutParams.MATCH_PARENT;
        Reel reel = new Reel();
        reel.viewer = new FrameLayout(activity);
        reel.viewer.setId(REELS_ID);
        reel.column = new LinearLayout(activity);
        reel.column.setOrientation(LinearLayout.VERTICAL);
        reel.viewer.addView(reel.column, new FrameLayout.LayoutParams(match, match));
        reel.gesture = new FrameLayout(activity);
        reel.column.addView(reel.gesture, new LinearLayout.LayoutParams(match, 0, 1f));
        FrameLayout page = new FrameLayout(activity);
        reel.gesture.addView(page, new FrameLayout.LayoutParams(match, match));
        reel.media = new View(activity);
        reel.media.setId(MEDIA_ID);
        page.addView(reel.media, new FrameLayout.LayoutParams(match, match));
        reel.layer = new FrameLayout(activity);
        page.addView(reel.layer, new FrameLayout.LayoutParams(match, match));
        reel.infoBand = new FrameLayout(activity);
        reel.layer.addView(reel.infoBand, new FrameLayout.LayoutParams(match, match));
        View info = new View(activity);
        info.setId(INFO_ID);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(900, 300, Gravity.BOTTOM);
        infoParams.bottomMargin = 100;
        reel.infoBand.addView(info, infoParams);
        reel.scrubBand = new FrameLayout(activity);
        reel.layer.addView(reel.scrubBand, new FrameLayout.LayoutParams(match, match));
        View scrubber = new View(activity);
        scrubber.setId(SCRUBBER_ID);
        reel.scrubBand.addView(scrubber, new FrameLayout.LayoutParams(match, 30, Gravity.BOTTOM));
        reel.footers = new LinearLayout(activity);
        reel.footers.setOrientation(LinearLayout.VERTICAL);
        reel.footers.setId(FOOTERS_ID);
        reel.column.addView(reel.footers, new LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(reel.viewer, 1, new FrameLayout.LayoutParams(1080, match));
        layout();
        return reel;
    }

    /** That the reel's caption and seek bar are raised [by] over the pill, and the video and the layer over it stay put. */
    private static void assertRaised(Reel reel, int by) {
        assertEquals("the caption clears the pill", by, raisedBy(reel.infoBand));
        assertEquals("and so does the seek bar", by, raisedBy(reel.scrubBand));
        assertEquals("the video stays put", 0f, reel.media.getTranslationY(), 0f);
        assertEquals(0, reel.media.getScrollY());
        assertEquals("and so does the layer of controls", 0f, reel.layer.getTranslationY(), 0f);
        assertEquals(0, reel.layer.getScrollY());
    }

    /** How far up [band] shows what it holds: its content's scroll, less its own translation. */
    private static int raisedBy(View band) {
        return Math.round(band.getScrollY() - band.getTranslationY());
    }

    /**
     * Litho ending an animation of [view], a view it mounted in [lithoView]: it puts the view at [y]
     * inside [lithoView], adding up where the views on the way sit, translations and all, but not their scrolls.
     */
    private static void lithoPlaces(View view, View lithoView, float y) {
        float parentY = 0f;
        for (View at = (View) view.getParent(); at != lithoView; at = (View) at.getParent()) parentY += at.getY();
        view.setY(y - parentY);
    }

    /** Where [view]'s layout puts it inside [lithoView]. */
    private static float laidOutIn(View view, View lithoView) {
        float y = 0f;
        for (View at = view; at != lithoView; at = (View) at.getParent()) y += at.getTop();
        return y;
    }

    private static int topOnScreen(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[1];
    }

    /** The middle of [view] on the screen, where it sits now. */
    private static float[] middleOf(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new float[]{at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 2f};
    }

    /** A tap at [spot], through the window, as the wait on Reels reads it. */
    private void tapAt(float[] spot) {
        float x = spot[0], y = spot[1];
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0);
        try {
            activity.getWindow().getCallback().dispatchTouchEvent(down);
            activity.getWindow().getCallback().dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    /**
     * #221, #280: under a floating pill the Reels viewer stops at the top of the pill's slot and keeps
     * that one size while the wait on Reels slides the pill away and a tap brings it back, with nothing
     * of the reel raised. Grown and shrunk with the pill, the video rescaled and the caption dropped
     * (#221). Run on behind the pill, the reel sat half the pill's height lower, with a wide black band
     * over it (#280).
     */
    @Test public void aReelStopsAtThePillsSlotAndKeepsItsSizeWhileThePillSlidesAwayAndBack() {
        LinearLayout tabs = namedTabBar();
        Reel reel = showReel();
        Settings.GLASS_TAB_BAR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_FLOAT.get());
        Settings.GLASS_TAB_BAR_REELS_HIDE.save(GlassReelsHide.TWO_SECONDS);
        GlassTabBar.apply(activity);
        idle(500);
        int pill = tabs.getLayoutParams().height;
        assertTrue(pill > 0);
        assertEquals("the reel stops at the pill's slot", pill, bottomMargin(reel.viewer));
        layout();
        int height = reel.media.getHeight();
        assertEquals(1920 - pill, height);
        assertRaised(reel, 0);
        float[] spot = middleOf(tabs);

        idle(2500);
        assertTrue("the pill slid away", tabs.getTranslationY() > 50f);
        idle(500);
        layout();
        assertEquals("the reel keeps its margin", pill, bottomMargin(reel.viewer));
        assertEquals("and its size", height, reel.media.getHeight());
        assertRaised(reel, 0);

        tapAt(spot);
        idle(500);
        layout();
        assertEquals("the pill is back", 0f, tabs.getTranslationY(), 0f);
        assertEquals(pill, bottomMargin(reel.viewer));
        assertEquals(height, reel.media.getHeight());
        assertRaised(reel, 0);
        GlassTabBar.restoreAll();
    }

    /**
     * #221: with the screens run behind the gesture handle, the reel stops above the pill and the
     * navigation bar's height Instagram was answered 0 for, whether the pill shows or not. Run to the
     * bottom edge, the reel's seek bar sat in the handle's reach and took no touches.
     */
    @Test public void aReelStopsAboveThePillAndTheGestureHandleWhetherThePillShowsOrNot() {
        LinearLayout tabs = namedTabBar();
        Reel reel = showReel();
        Settings.GLASS_TAB_BAR.save(true);
        Settings.GLASS_TAB_BAR_REELS_HIDE.save(GlassReelsHide.TWO_SECONDS);
        GlassTabBar.apply(activity);
        GlassTabBar.nowInFront(activity);
        assertEquals("the screens run behind the handle", 0, GlassTabBar.navBarHeight(63));
        // Instagram lays its screens out again with the answer; the next frame gives the pill its room.
        idle(GlassTabBar.REELS_LOOK_MS * 2);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        idle(500);
        int pill = tabs.getLayoutParams().height;
        assertEquals("the pill made room for the handle", 150 + 63, pill);
        assertEquals("the reel stops above the pill and the handle", pill, bottomMargin(reel.viewer));
        assertRaised(reel, 0);
        float[] spot = middleOf(tabs);

        idle(2500);
        assertTrue("the pill slid away", tabs.getTranslationY() > 50f);
        idle(500);
        assertEquals("the reel stays where it was", pill, bottomMargin(reel.viewer));
        assertRaised(reel, 0);

        tapAt(spot);
        idle(500);
        assertEquals("the pill is back", 0f, tabs.getTranslationY(), 0f);
        assertEquals(pill, bottomMargin(reel.viewer));
        assertRaised(reel, 0);
        GlassTabBar.restoreAll();
    }

    /**
     * #285: with Full screen Reels on, HushGram hides the navigation bar on Reels and Instagram is answered
     * 0 for it there, so the pill gave up the bar's room and dropped by the bar's height with every switch
     * to Reels, then rose again on Home. While the bar is hidden the pill keeps that room, and the reel
     * keeps its margin and its size. A bar that simply isn't there leaves no room.
     */
    @Test public void thePillKeepsItsHeightWhileFullScreenHidesTheNavigationBar() {
        LinearLayout tabs = namedTabBar();
        Reel reel = showReel();
        Settings.GLASS_TAB_BAR.save(true);
        GlassTabBar.apply(activity);
        GlassTabBar.nowInFront(activity);
        assertEquals("Home, with the bar showing", 0, GlassTabBar.navBarHeight(63));
        idle(GlassTabBar.REELS_LOOK_MS * 2);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        idle(500);
        layout();
        int pill = tabs.getLayoutParams().height;
        assertEquals(150 + 63, pill);
        assertEquals(pill, bottomMargin(reel.viewer));
        int height = reel.viewer.getHeight();

        // Reels hides the bar: Instagram is answered 0 for the height it offers, then offers its 0 back.
        assertEquals(0, GlassTabBar.navBarHeight(0, 63));
        idle(GlassTabBar.REELS_LOOK_MS * 2);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        assertEquals("the pill kept its height", pill, tabs.getLayoutParams().height);
        assertEquals("and the reel its margin", pill, bottomMargin(reel.viewer));
        assertEquals("and its size", height, reel.viewer.getHeight());

        assertEquals("no bar at all", 0, GlassTabBar.navBarHeight(0));
        idle(GlassTabBar.REELS_LOOK_MS * 2);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        assertEquals(150, tabs.getLayoutParams().height);
        assertEquals(150, bottomMargin(reel.viewer));
        GlassTabBar.restoreAll();
    }

    /**
     * #222: Instagram pads its screens by the navigation bar's height it last had: 0 under a floating
     * pill, the real height with the keyboard up or its bar hidden for a paused reel. The Reels viewer's
     * margin makes up the difference, so its bottom edge and its size stay put whichever answer Instagram
     * had, and the margin lands in the same layout as Instagram's new padding: when Instagram's screens are
     * told the heights ({@link GlassTabBar#heightsTold}), or else in the very next frame, not after the
     * wait between looks. A viewer sized twice for each change could stop on its cover with its sound
     * playing on.
     */
    @Test public void aReelKeepsItsSizeWhicheverNavigationBarHeightInstagramPadsItsScreensBy() {
        LinearLayout tabs = namedTabBar();
        selectReelsTab(tabs);
        Reel reel = showReel();
        // Instagram's screens' container, which it pads by the navigation bar's height it keeps.
        FrameLayout panel = new FrameLayout(activity);
        root.removeView(reel.viewer);
        panel.addView(reel.viewer, new FrameLayout.LayoutParams(1080, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(panel, 1, new FrameLayout.LayoutParams(1080, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        Settings.GLASS_TAB_BAR.save(true);
        GlassTabBar.apply(activity);
        GlassTabBar.nowInFront(activity);
        assertEquals("the screens run behind the handle", 0, GlassTabBar.navBarHeight(63));
        idle(GlassTabBar.REELS_LOOK_MS * 2);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        idle(500);
        layout();
        int pill = tabs.getLayoutParams().height;
        assertEquals(150 + 63, pill);
        assertEquals(pill, bottomMargin(reel.viewer));
        int height = reel.viewer.getHeight();
        assertEquals(1920 - pill, height);

        // The keyboard comes up with no sheet: Instagram gets the height and pads its screens by it.
        assertEquals(63, GlassTabBar.navBarHeightOverTheKeyboard(63));
        padBy(panel, 63);
        GlassTabBar.heightsTold();
        assertEquals("the viewer gives up what Instagram now keeps, in the same pass", 150, bottomMargin(reel.viewer));
        layout();
        assertEquals("and keeps its size", height, reel.viewer.getHeight());

        // The keyboard goes.
        assertEquals(0, GlassTabBar.navBarHeight(63));
        padBy(panel, 0);
        GlassTabBar.heightsTold();
        assertEquals(pill, bottomMargin(reel.viewer));
        layout();
        assertEquals(height, reel.viewer.getHeight());

        // Instagram hides its bar for a paused reel, and the pill no longer takes the height.
        tabs.setVisibility(View.GONE);
        assertEquals(63, GlassTabBar.navBarHeight(63));
        padBy(panel, 63);
        GlassTabBar.heightsTold();
        assertEquals("the reel stays above the slot with the bar hidden", 150, bottomMargin(reel.viewer));
        layout();
        assertEquals(height, reel.viewer.getHeight());

        // The bar is back, and Instagram's screens pick the 0 up with nothing told: the next frame has it.
        tabs.setVisibility(View.VISIBLE);
        assertEquals(0, GlassTabBar.navBarHeight(63));
        padBy(panel, 0);
        layout();
        assertTrue("the frame laid out at the wrong size is held back", tabs.getViewTreeObserver().dispatchOnPreDraw());
        assertEquals("the margin follows at once", pill, bottomMargin(reel.viewer));
        layout();
        assertEquals(height, reel.viewer.getHeight());
        assertFalse("a frame that changes nothing goes ahead", tabs.getViewTreeObserver().dispatchOnPreDraw());
        GlassTabBar.restoreAll();
    }

    private static void padBy(View panel, int bottom) {
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) panel.getLayoutParams();
        margins.bottomMargin = bottom;
        panel.setLayoutParams(margins);
    }

    /**
     * #260: Instagram hides its bar for a paused reel and shows the reel's controls in a footer under it,
     * shrinking the reel to make room. Under a floating pill the footer lies over the reel's bottom
     * instead, the frame Instagram laid out shrunk is held back, and the caption and seek bar go up by the
     * footer's height. Before, the reel shrank and the viewer then grew back a frame later, so the video
     * jumped twice. The viewer keeps its margin with the bar hidden.
     */
    @Test public void aPausedReelsFooterLiesOverTheReelInsteadOfShrinkingIt() {
        LinearLayout tabs = namedTabBar();
        selectReelsTab(tabs);
        Reel reel = showReel();
        Settings.GLASS_TAB_BAR.save(true);
        GlassTabBar.apply(activity);
        idle(500);
        int pill = tabs.getLayoutParams().height;
        layout();
        int height = reel.media.getHeight();
        assertRaised(reel, 0);

        tabs.setVisibility(View.GONE);
        View controls = new View(activity);
        reel.footers.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
        layout();
        assertEquals("Instagram shrinks the reel over the footer", height - 120, reel.media.getHeight());
        assertTrue("that frame is held back", tabs.getViewTreeObserver().dispatchOnPreDraw());
        layout();
        assertEquals("the footer lies over the reel", -120, ((ViewGroup.MarginLayoutParams) reel.footers.getLayoutParams()).topMargin);
        assertEquals("which keeps its size", height, reel.media.getHeight());
        assertEquals("the footer sits on the reel's bottom", reel.column.getHeight() - 120, reel.footers.getTop());
        assertEquals("the viewer's margin stays", pill, bottomMargin(reel.viewer));
        assertRaised(reel, 120);
        assertEquals("the footer itself stays down", 0f, reel.footers.getTranslationY(), 0f);
        assertFalse("a frame that changes nothing goes ahead", tabs.getViewTreeObserver().dispatchOnPreDraw());

        // Played again: the footer empties and the bar comes back.
        reel.footers.removeView(controls);
        tabs.setVisibility(View.VISIBLE);
        layout();
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        assertEquals(0, ((ViewGroup.MarginLayoutParams) reel.footers.getLayoutParams()).topMargin);
        assertEquals(height, reel.media.getHeight());
        assertRaised(reel, 0);
        GlassTabBar.restoreAll();
    }

    /** Shows a paused reel's controls, [tall] high, in the reel's footer, laid over the reel, and returns them. */
    private View pauseWithControls(LinearLayout tabs, Reel reel, int tall) {
        View controls = new View(activity);
        reel.footers.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tall));
        layout();
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        return controls;
    }

    /**
     * Instagram hands a reel's views on to other reels once they come off the window. One raised over a
     * paused reel's footer is put back as it comes off, so it never carries the lift to where it's
     * mounted next, and it's raised again once back on a reel. A translation Instagram gives one is left
     * to Instagram.
     */
    @Test public void aRaisedViewIsPutBackAsItComesOffAndInstagramsOwnMoveStays() {
        LinearLayout tabs = namedTabBar();
        Reel reel = showReel();
        Settings.GLASS_TAB_BAR.save(true);
        GlassTabBar.apply(activity);
        idle(500);
        assertRaised(reel, 0);
        int by = 120;
        pauseWithControls(tabs, reel, by);
        assertRaised(reel, by);

        reel.layer.removeView(reel.infoBand);
        assertEquals("put back as it came off", 0, raisedBy(reel.infoBand));
        assertEquals(0, reel.infoBand.getScrollY());
        reel.layer.addView(reel.infoBand, 0);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        assertRaised(reel, by);

        reel.scrubBand.setTranslationY(-40f);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("Instagram's move stays", -40f, reel.scrubBand.getTranslationY(), 0f);
        assertEquals("and the seek bar stays up", by + 40, raisedBy(reel.scrubBand));
        reel.scrubBand.setTranslationY(0f);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        assertRaised(reel, by);

        reel.scrubBand.scrollTo(0, 25);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("Instagram's scroll stays", 25, reel.scrubBand.getScrollY());
        assertEquals("and the seek bar is raised by moving it", by + 25, raisedBy(reel.scrubBand));
        reel.scrubBand.scrollTo(0, 0);
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        assertRaised(reel, by);
        assertEquals(0f, reel.scrubBand.getTranslationY(), 0f);
        GlassTabBar.restoreAll();
    }

    /**
     * #221: a reel's caption and seek bar stay clear of a paused reel's footer after Instagram animates
     * them, as it does when a sheet over the reel closes. Instagram's Litho puts an animated view back
     * where its layout says by adding up the translations of the views it sits in, so a band raised by
     * translation had its caption pushed back down. A band is raised by scrolling its content instead,
     * and one too short for that is translated only for the rest.
     */
    @Test public void aReelsCaptionStaysClearOfItsFooterAfterInstagramAnimatesIt() {
        LinearLayout tabs = namedTabBar();
        Reel reel = showReel();
        // A seek bar's band only a little taller than the seek bar, as Instagram's is.
        reel.scrubBand.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80, Gravity.BOTTOM));
        layout();
        Settings.GLASS_TAB_BAR.save(true);
        GlassTabBar.apply(activity);
        idle(500);
        int by = 120;
        pauseWithControls(tabs, reel, by);
        assertRaised(reel, by);
        View info = reel.infoBand.findViewById(INFO_ID);
        View page = (View) reel.layer.getParent();
        int raisedTo = topOnScreen(info);
        assertEquals(topOnScreen(reel.media) + Math.round(laidOutIn(info, page)) - by, raisedTo);

        lithoPlaces(info, page, laidOutIn(info, page));
        tabs.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("the caption stays clear of the footer", raisedTo, topOnScreen(info));
        assertEquals("the caption's band scrolls", 0f, reel.infoBand.getTranslationY(), 0f);
        assertEquals("the seek bar's band scrolls as far as it has room", 50, reel.scrubBand.getScrollY());
        assertEquals("and moves for the rest", -(by - 50), reel.scrubBand.getTranslationY(), 0f);
        GlassTabBar.restoreAll();
    }

    /**
     * With a time picked for Reels and the scroll switch off, the glass bar reads the window's touches
     * and slides away once a reel has gone that long untouched. Its screen going to the background puts it
     * back with nothing left waiting, and coming to the front starts the wait over, since a reel that plays
     * on untouched would never start another.
     */
    @Test public void theGlassBarsWaitOnReelsEndsWithItsScreenAndStartsOverWhenItComesBack() {
        LinearLayout tabs = namedTabBar();
        showReels();
        Window.Callback before = activity.getWindow().getCallback();
        Settings.GLASS_TAB_BAR.save(true);
        assertFalse(Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.get());
        Settings.GLASS_TAB_BAR_REELS_HIDE.save(GlassReelsHide.TWO_SECONDS);
        GlassTabBar.apply(activity);
        assertNotSame("the wait needs the window's touches", before, activity.getWindow().getCallback());
        idle(1900);
        assertEquals("not before the wait is up", 0f, tabs.getTranslationY(), 0f);
        idle(600);
        assertTrue("the bar slid away", tabs.getTranslationY() > 50f);

        // What the glass bar does when its activity pauses.
        GlassTabBar.restoreAll();
        assertEquals(0f, tabs.getTranslationY(), 0f);
        idle(10_000);
        assertEquals("nothing waits in the background", 0f, tabs.getTranslationY(), 0f);

        // And what it does when the activity resumes with the bar it already restyled.
        GlassTabBar.apply(activity);
        idle(2500);
        assertTrue("the wait started over", tabs.getTranslationY() > 50f);
        GlassTabBar.restoreAll();
        assertEquals(0f, tabs.getTranslationY(), 0f);
    }

    /** With the scroll switch off and the wait on Never, the glass bar reads no touches and waits for nothing. */
    @Test public void theGlassBarReadsNoTouchesWithNoHidePicked() {
        LinearLayout tabs = namedTabBar();
        showReels();
        Window.Callback before = activity.getWindow().getCallback();
        Settings.GLASS_TAB_BAR.save(true);
        assertFalse(Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.get());
        assertEquals(GlassReelsHide.NEVER, Settings.GLASS_TAB_BAR_REELS_HIDE.get());
        GlassTabBar.apply(activity);
        assertSame("no watcher over the window's callback", before, activity.getWindow().getCallback());
        idle(10_000);
        assertEquals(0f, tabs.getTranslationY(), 0f);
    }

    /** The Saved tab puts no layout listener on a screen with the switch off or HushGram paused. */
    @Test public void theSavedTabWatchesNoScreenWhileOffOrPaused() {
        int layouts = windowListeners("mOnGlobalLayoutListeners");

        Settings.SAVED_ON_PROFILE.resetToDefault();
        assertFalse(Settings.SAVED_ON_PROFILE.get());
        ProfileSaved.watch(activity);
        assertEquals("off", layouts, windowListeners("mOnGlobalLayoutListeners"));

        Settings.SAVED_ON_PROFILE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfileSaved.watch(activity);
        assertEquals("paused", layouts, windowListeners("mOnGlobalLayoutListeners"));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ProfileSaved.watch(activity);
        assertEquals("on, the screen is watched", layouts + 1, windowListeners("mOnGlobalLayoutListeners"));
        ProfileSaved.watch(activity);
        assertEquals("once", layouts + 1, windowListeners("mOnGlobalLayoutListeners"));
    }
}
