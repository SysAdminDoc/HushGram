/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the tab bar as you scroll on Instagram's own bar (#343): with Glass tab bar off, a scroll down
 * slides the bar away and the screens run to the bottom in its place, with no strip left behind; once
 * it's back, the screens stop above it again. Off or paused, the bar and the screens are Instagram's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w1080dp-h1920dp")
public class PlainScrollHideTest {
    private static final int BAR_ID = 0x7f0b0131;
    private static final int CONTENT_ID = 0x7f0b0132;
    private static final int SHADOW_ID = 0x7f0b0133;
    private static final int REELS_ID = 0x7f0b0134;
    private static final int BAR_HEIGHT = 150;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<GlassScrollHideTest.Probe> controller;
    private GlassScrollHideTest.Probe activity;
    private FrameLayout holder;
    private FrameLayout content;
    private FrameLayout screen;
    private GlassScrollHideTest.FakeList list;
    private View shadow;
    private LinearLayout bar;
    private FrameLayout viewer;
    private final Map<String, Integer> before = new java.util.HashMap<>();
    private long clock;

    @Before public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.GLASS_TAB_BAR.save(false);
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(true);
        controller = Robolectric.buildActivity(GlassScrollHideTest.Probe.class).setup();
        activity = controller.get();
        holder = new FrameLayout(activity);
        content = new FrameLayout(activity);
        content.setId(CONTENT_ID);
        screen = new FrameLayout(activity);
        list = new GlassScrollHideTest.FakeList(activity);
        screen.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams screenParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        screenParams.bottomMargin = BAR_HEIGHT;
        content.addView(screen, screenParams);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        contentParams.bottomMargin = BAR_HEIGHT;
        holder.addView(content, contentParams);
        shadow = new View(activity);
        shadow.setId(SHADOW_ID);
        FrameLayout.LayoutParams shadowParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2,
                Gravity.BOTTOM);
        shadowParams.bottomMargin = BAR_HEIGHT;
        holder.addView(shadow, shadowParams);
        bar = new LinearLayout(activity);
        bar.setId(BAR_ID);
        holder.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT, Gravity.BOTTOM));
        activity.setContentView(holder);
        name(GlassTabBar.TAB_BAR, BAR_ID);
        name(GlassTabBar.CONTENT, CONTENT_ID);
        name(GlassTabBar.TAB_BAR_SHADOW, SHADOW_ID);
        name(GlassTabBar.SWIPE_PAGER, 0);
        layout();
        clock = SystemClock.uptimeMillis();
        HookStatus.clear();
    }

    @After public void tearDown() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            for (Map.Entry<String, Integer> entry : before.entrySet()) {
                if (entry.getValue() == null) ids.remove(entry.getKey());
                else ids.put(entry.getKey(), entry.getValue());
            }
        }
        controller.close();
        HookStatus.clear();
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.resetToDefault();
        Settings.GLASS_TAB_BAR_REELS_HIDE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        GlassTabBar.resetForTests();
    }

    private void name(String name, int id) {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            if (!before.containsKey(name)) before.put(name, ids.get(name));
            ids.put(name, id);
        }
    }

    private void layout() {
        holder.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        holder.layout(0, 0, 1080, 1920);
    }

    private PlainScrollHide applied() {
        GlassTabBar.apply(activity);
        PlainScrollHide found = PlainScrollHide.of(bar);
        assertNotNull("the switch on and glass off give Instagram's bar a hider", found);
        return found;
    }

    private void touch(GlassScrollHide hide, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(clock, clock, action, x, y, 0);
        clock += 16;
        try {
            hide.onTouch(event);
        } finally {
            event.recycle();
        }
    }

    /** A finger down at the list's middle that drags [by] pixels (negative is up) and lifts. */
    private void drag(GlassScrollHide hide, float by) {
        touch(hide, MotionEvent.ACTION_DOWN, 500, 900);
        for (int i = 1; i <= 10; i++) touch(hide, MotionEvent.ACTION_MOVE, 500, 900 + by * i / 10f);
        touch(hide, MotionEvent.ACTION_UP, 500, 900 + by);
    }

    private static void settle() {
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
    }

    private int margin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
    }

    @Test public void scrollingDownSlidesTheBarAwayAndTheScreensTakeItsRoom() {
        GlassScrollHide hide = applied().hide;
        list.awayFromTop = true;
        drag(hide, -300);
        assertTrue(hide.isHidden());
        assertEquals("the screens' holder runs to the bottom", 0, margin(content));
        assertEquals("and so does the screen in it", 0, margin(screen));
        assertEquals("the shadow line doesn't stay behind", View.INVISIBLE, shadow.getVisibility());
        settle();
        assertTrue(hide.isOffScreen());
    }

    @Test public void scrollingUpBringsItBackAndTheScreensStopAboveItOnceItsBack() {
        GlassScrollHide hide = applied().hide;
        list.awayFromTop = true;
        drag(hide, -300);
        settle();
        drag(hide, 300);
        assertFalse(hide.isHidden());
        assertEquals("nothing shows through while the bar slides back", 0, margin(content));
        settle();
        assertEquals(0f, bar.getTranslationY(), 0.5f);
        assertEquals(BAR_HEIGHT, margin(content));
        assertEquals(BAR_HEIGHT, margin(screen));
        assertEquals(View.VISIBLE, shadow.getVisibility());
    }

    @Test public void aListAtItsTopLeavesTheBarAndTheScreensAlone() {
        GlassScrollHide hide = applied().hide;
        list.awayFromTop = false;
        drag(hide, -300);
        assertFalse(hide.isHidden());
        assertEquals(BAR_HEIGHT, margin(content));
        assertEquals(BAR_HEIGHT, margin(screen));
    }

    /** A margin Instagram set while the bar was away is Instagram's, and a screen that never stopped at the bar stays as it was. */
    @Test public void onlyTheMarginsItTookAreGivenBack() {
        View other = new View(activity);
        content.addView(other, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        GlassScrollHide hide = applied().hide;
        list.awayFromTop = true;
        drag(hide, -300);
        ViewGroup.MarginLayoutParams changed = (ViewGroup.MarginLayoutParams) screen.getLayoutParams();
        changed.bottomMargin = 37;
        screen.setLayoutParams(changed);
        hide.restoreNow();
        assertEquals(BAR_HEIGHT, margin(content));
        assertEquals(37, margin(screen));
        assertEquals(0, margin(other));
    }

    /** A reel opened from a post has Instagram hide its bar itself, with its own margins: those stay. */
    @Test public void aBarInstagramHidKeepsTheMarginsInstagramGave() {
        GlassScrollHide hide = applied().hide;
        list.awayFromTop = true;
        drag(hide, -300);
        bar.setVisibility(View.GONE);
        hide.restoreNow();
        assertEquals(0, margin(content));
        assertEquals(0, margin(screen));
        assertEquals("the shadow this hid comes back all the same", View.VISIBLE, shadow.getVisibility());
    }

    @Test public void pausingPutsTheBarAndTheScreensBackAtOnce() {
        GlassScrollHide hide = applied().hide;
        list.awayFromTop = true;
        drag(hide, -300);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        touch(hide, MotionEvent.ACTION_DOWN, 500, 900);
        assertFalse(hide.isHidden());
        assertEquals(0f, bar.getTranslationY(), 0.5f);
        assertEquals(BAR_HEIGHT, margin(content));
        assertEquals(BAR_HEIGHT, margin(screen));
    }

    @Test public void theScreenGoingToTheBackgroundPutsEverythingBack() {
        GlassScrollHide hide = applied().hide;
        list.awayFromTop = true;
        drag(hide, -300);
        GlassTabBar.restoreAll();
        assertFalse(hide.isHidden());
        assertEquals(BAR_HEIGHT, margin(content));
        assertEquals(BAR_HEIGHT, margin(screen));
    }

    @Test public void withTheSwitchOffInstagramsBarGetsNoHider() {
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(false);
        GlassTabBar.apply(activity);
        assertNull(PlainScrollHide.of(bar));
    }

    @Test public void theSwitchStandsOnItsOwnWithTheGlassOff() {
        assertTrue("Instagram's own bar hides too", Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.isAvailable());
        Settings.GLASS_TAB_BAR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.isAvailable());
    }

    /** Puts a Reels viewer on the screen, stopping above the bar as the Reels tab's does. */
    private void showReels() {
        viewer = new FrameLayout(activity);
        viewer.setId(REELS_ID);
        screen.addView(viewer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        name(GlassTabBar.REELS_ROOT, REELS_ID);
        layout();
    }

    /** On a Reels viewer with only the wait picked, [delay], and the scroll switch off. */
    private GlassScrollHide waitingOnReels(GlassReelsHide delay) {
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(false);
        Settings.GLASS_TAB_BAR_REELS_HIDE.save(delay);
        showReels();
        return applied().hide;
    }

    private static void idle(long millis) {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS);
    }

    private void assertTheScreensStopAboveTheBar() {
        assertEquals(BAR_HEIGHT, margin(content));
        assertEquals(BAR_HEIGHT, margin(screen));
        assertEquals(View.VISIBLE, shadow.getVisibility());
    }

    /** #343: the wait picked on the Reels row works on Instagram's own bar, and the reel leaves no strip. */
    @Test public void onReelsThePickedWaitSlidesTheBarAwayAndTheScreensTakeItsRoom() {
        GlassScrollHide hide = waitingOnReels(GlassReelsHide.THREE_SECONDS);
        idle(2900);
        assertFalse(hide.isHidden());
        assertTheScreensStopAboveTheBar();
        idle(200);
        assertTrue(hide.isHidden());
        assertEquals("the screens' holder runs to the bottom", 0, margin(content));
        assertEquals("and so does the screen in it", 0, margin(screen));
        assertEquals("the shadow line doesn't stay behind", View.INVISIBLE, shadow.getVisibility());
        settle();
        assertTrue(hide.isOffScreen());
        assertEquals("only moved, never hidden", View.VISIBLE, bar.getVisibility());
    }

    @Test public void aSwipeOnAReelLeavesTheBarAwayAndTheScreensAsTheyAre() {
        GlassScrollHide hide = waitingOnReels(GlassReelsHide.TWO_SECONDS);
        idle(2100);
        settle();
        for (int swipe : new int[] {-600, 600}) {
            drag(hide, swipe);
            settle();
            assertTrue(hide.isHidden());
            assertEquals(0, margin(content));
            assertEquals(0, margin(screen));
        }
    }

    @Test public void aTapWhereTheBarSitsBringsItBackAndTheScreensStopAboveItOnceItsBack() {
        GlassScrollHide hide = waitingOnReels(GlassReelsHide.TWO_SECONDS);
        idle(2100);
        settle();
        touch(hide, MotionEvent.ACTION_DOWN, 500, 1900);
        touch(hide, MotionEvent.ACTION_UP, 500, 1900);
        assertFalse(hide.isHidden());
        assertEquals("nothing shows through while the bar slides back", 0, margin(content));
        settle();
        assertEquals(0f, bar.getTranslationY(), 0.5f);
        assertTheScreensStopAboveTheBar();
    }

    @Test public void leavingReelsBringsTheBarBackAndTheScreensStopAboveIt() {
        GlassScrollHide hide = waitingOnReels(GlassReelsHide.TWO_SECONDS);
        idle(2100);
        assertTrue(hide.isHidden());
        viewer.setVisibility(View.GONE);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        settle();
        assertTheScreensStopAboveTheBar();
    }

    @Test public void theBackgroundPutsTheBarAndTheScreensBackAfterAWait() {
        GlassScrollHide hide = waitingOnReels(GlassReelsHide.TWO_SECONDS);
        idle(2100);
        assertTrue(hide.isHidden());
        GlassTabBar.restoreAll();
        assertFalse(hide.isHidden());
        assertTheScreensStopAboveTheBar();
    }

    @Test public void withOnlyTheWaitPickedAListScrollLeavesTheBar() {
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(false);
        Settings.GLASS_TAB_BAR_REELS_HIDE.save(GlassReelsHide.TWO_SECONDS);
        GlassScrollHide hide = applied().hide;
        list.awayFromTop = true;
        drag(hide, -300);
        assertFalse(hide.isHidden());
        idle(10_000);
        assertFalse("and the wait is for reels", hide.isHidden());
        assertTheScreensStopAboveTheBar();
    }

    @Test public void withTheWaitOnNeverReelsKeepTheirBarWhateverTheScrollSwitch() {
        showReels();
        GlassScrollHide hide = applied().hide;
        idle(10_000);
        assertFalse(hide.isHidden());
        drag(hide, -600);
        assertFalse(hide.isHidden());
        assertTheScreensStopAboveTheBar();
    }

    @Test public void pausedTheWaitHidesNothing() {
        GlassScrollHide hide = waitingOnReels(GlassReelsHide.TWO_SECONDS);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        idle(10_000);
        assertFalse(hide.isHidden());
        assertEquals(0f, bar.getTranslationY(), 0.5f);
        assertTheScreensStopAboveTheBar();
    }
}
