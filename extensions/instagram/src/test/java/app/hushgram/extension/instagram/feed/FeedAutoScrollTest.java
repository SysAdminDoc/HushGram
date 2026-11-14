/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Auto scroll in Home (#660): a photo moves on after the time picked, a video post when its video
 * starts over or ends, a finger or anything drawn over Home holds it, and off, paused or without the
 * window's focus nothing moves.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedAutoScrollTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int WIDTH = 300, HEIGHT = 360, HEADER = 40, MEDIA = 200, POST = HEADER + MEDIA;

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private FakeList list;

    /**
     * Stands in for Home's list: a column of posts that scrolls, with RecyclerView's scroll state.
     * Robolectric never shows an activity's window, so its window visibility is set here, as
     * ReelBlurBarsTest's pager does.
     */
    static final class FakeList extends LinearLayout {
        int scrollState;
        int scrolled;
        boolean more = true;
        int windowVisibility = View.VISIBLE;
        final List<Integer> states = new ArrayList<>();

        FakeList(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }

        @Override
        public int getWindowVisibility() {
            return windowVisibility;
        }

        public int getScrollState() {
            return scrollState;
        }

        public void setScrollState(int state) {
            scrollState = state;
            states.add(state);
        }

        @Override
        public void scrollBy(int x, int y) {
            scrolled += y;
            super.scrollBy(x, y);
        }

        @Override
        public boolean canScrollVertically(int direction) {
            return direction > 0 ? more : scrolled > 0;
        }
    }

    @Before
    public void prepare() {
        reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedAutoScroll.resetForTests();
    }

    @After
    public void restore() {
        FeedAutoScroll.resetForTests();
        reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        if (controller != null) controller.pause().stop().destroy();
    }

    private static void reset() {
        Settings.FEED_AUTO_SCROLL.resetToDefault();
        Settings.FEED_AUTO_SCROLL_SECONDS.resetToDefault();
        Settings.TAP_TO_PLAY.resetToDefault();
        Settings.TAP_TO_PLAY_SCOPE.resetToDefault();
    }

    private void on(int seconds) {
        Settings.FEED_AUTO_SCROLL.save(true);
        Settings.FEED_AUTO_SCROLL_SECONDS.save(seconds);
    }

    /**
     * Home with one post per entry, each a profile header over its picture, or over a video when the
     * entry is true. The list goes to its constructor's hook before it's on screen, the way the
     * patched constructors hand it over, and the window then gets the focus.
     */
    private void build(boolean withHeaderId, boolean... videos) {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        if (withHeaderId) {
            FeedAutoScroll.IDS.putForTests(Utils.resourcePackage(activity), FeedAutoScroll.HEADER, View.generateViewId());
        }
        int headerId = FeedAutoScroll.IDS.resolve(activity.getResources(), Utils.resourcePackage(activity), FeedAutoScroll.HEADER, false);
        list = new FakeList(activity);
        for (boolean video : videos) {
            LinearLayout post = new LinearLayout(activity);
            post.setOrientation(LinearLayout.VERTICAL);
            View header = new View(activity);
            header.setId(headerId == 0 ? View.NO_ID : headerId);
            post.addView(header, new LinearLayout.LayoutParams(WIDTH, HEADER));
            View media = video ? new SurfaceView(activity) : new View(activity);
            media.setBackground(new ColorDrawable(Color.GRAY));
            post.addView(media, new LinearLayout.LayoutParams(WIDTH, MEDIA));
            list.addView(post, new LinearLayout.LayoutParams(WIDTH, POST));
        }
        FeedAutoScroll.attach(list);
        root = new FrameLayout(activity);
        root.addView(list, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        controller.windowFocusChanged(true);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void show(boolean... videos) {
        build(true, videos);
    }

    private void showWithoutHeaderId(boolean... videos) {
        build(false, videos);
    }

    private static void advance(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private void touch(int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 100, 100, 0);
        try {
            FeedAutoScroll.touch(activity, event);
        } finally {
            event.recycle();
        }
    }

    private static String report() {
        return HookStatus.report().toString();
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertEquals(Boolean.FALSE, Settings.FEED_AUTO_SCROLL.defaultValue);
        assertFalse(Settings.FEED_AUTO_SCROLL.rebootApp);
        assertEquals(Integer.valueOf(6), Settings.FEED_AUTO_SCROLL_SECONDS.defaultValue);
        assertFalse(Settings.FEED_AUTO_SCROLL_SECONDS.rebootApp);
    }

    /** Nothing moves until the list sat still, then a photo after the time picked, never sooner than the settle. */
    @Test
    public void aPhotoMovesOnOnceItSatStillForTheTimePicked() {
        assertFalse("still moving", FeedAutoScroll.decide(1_400, 1_000, false, Long.MIN_VALUE, 3_000));
        assertFalse(FeedAutoScroll.decide(3_999, 1_000, false, Long.MIN_VALUE, 3_000));
        assertTrue(FeedAutoScroll.decide(4_000, 1_000, false, Long.MIN_VALUE, 3_000));
        assertFalse("never before the settle", FeedAutoScroll.decide(1_499, 1_000, false, Long.MIN_VALUE, 100));
        assertTrue(FeedAutoScroll.decide(1_500, 1_000, false, Long.MIN_VALUE, 100));
        assertFalse("a video event means nothing to a photo", FeedAutoScroll.decide(2_000, 1_000, false, 1_900, 3_000));
    }

    /** A video post waits for its video to start over or end after the post settled, or for the cap. */
    @Test
    public void aVideoPostWaitsForItsVideo() {
        assertFalse("no event yet", FeedAutoScroll.decide(60_000, 1_000, true, Long.MIN_VALUE, 3_000));
        assertFalse("an event from the post before", FeedAutoScroll.decide(60_000, 1_000, true, 900, 3_000));
        assertFalse("an event at the moment it settled", FeedAutoScroll.decide(60_000, 1_000, true, 1_000, 3_000));
        assertTrue(FeedAutoScroll.decide(60_000, 1_000, true, 1_200, 3_000));
        assertFalse("still moving", FeedAutoScroll.decide(1_400, 1_000, true, 1_200, 3_000));
        assertFalse(FeedAutoScroll.decide(1_000 + FeedAutoScroll.VIDEO_CAP_MS - 1, 1_000, true, Long.MIN_VALUE, 3_000));
        assertTrue("the cap", FeedAutoScroll.decide(1_000 + FeedAutoScroll.VIDEO_CAP_MS, 1_000, true, Long.MIN_VALUE, 3_000));
    }

    @Test
    public void theHeadersSayWhichPostIsShownAndWhereTheNextStarts() {
        assertArrayEquals(new int[]{0, 240}, FeedAutoScroll.split(new int[]{480, 0, 240}, 0, 8));
        assertArrayEquals("a header a little below the top is still the post shown",
                new int[]{6, 246}, FeedAutoScroll.split(new int[]{6, 246}, 0, 8));
        assertArrayEquals("a post scrolled part way", new int[]{-100, 140}, FeedAutoScroll.split(new int[]{140, -100}, 0, 8));
        assertArrayEquals("padding moves the top", new int[]{20, 260}, FeedAutoScroll.split(new int[]{20, 260}, 20, 8));
        assertArrayEquals("only a header below", new int[]{Integer.MIN_VALUE, 50}, FeedAutoScroll.split(new int[]{50}, 0, 8));
        assertArrayEquals("no header", new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}, FeedAutoScroll.split(new int[0], 0, 8));
        assertArrayEquals("nothing below", new int[]{-30, Integer.MAX_VALUE}, FeedAutoScroll.split(new int[]{-30}, 0, 8));
    }

    @Test
    public void aMoveGoesToTheNextHeaderOrMostOfAScreen() {
        assertEquals(240, FeedAutoScroll.distance(240, 0, HEIGHT));
        assertEquals(240, FeedAutoScroll.distance(260, 20, HEIGHT));
        assertEquals((int) (HEIGHT * FeedAutoScroll.PAGE), FeedAutoScroll.distance(Integer.MAX_VALUE, 0, HEIGHT));
    }

    /** Comments, a sheet or a menu over Home draw something, and a clear frame doesn't. */
    @Test
    public void onlyWhatDrawsCountsAsCovering() {
        Context context = RuntimeEnvironment.getApplication();
        View plain = new View(context);
        assertFalse(FeedAutoScroll.paints(plain));
        plain.setBackground(new ColorDrawable(Color.TRANSPARENT));
        assertFalse(FeedAutoScroll.paints(plain));
        plain.setBackground(new ColorDrawable(Color.BLACK));
        assertTrue(FeedAutoScroll.paints(plain));
        assertFalse(FeedAutoScroll.paints(new FrameLayout(context)));
        ImageView image = new ImageView(context);
        assertFalse(FeedAutoScroll.paints(image));
        image.setImageDrawable(new ColorDrawable(Color.RED));
        assertTrue(FeedAutoScroll.paints(image));
        TextView text = new TextView(context);
        assertFalse(FeedAutoScroll.paints(text));
        text.setText("Comments");
        assertTrue(FeedAutoScroll.paints(text));
        assertTrue("a view that draws itself", FeedAutoScroll.paints(new View(context) {
        }));
    }

    /** Every hook counts its call, and off nothing a hook sees is kept. */
    @Test
    public void offTheHooksOnlyCount() {
        show(false, false);
        touch(MotionEvent.ACTION_DOWN);
        FeedAutoScroll.looped(null, 1);
        FeedAutoScroll.ended(null);
        FeedAutoScroll.attach(null);

        assertFalse(FeedAutoScroll.fingerDown);
        assertEquals(Long.MIN_VALUE, FeedAutoScroll.videoEventAt);
        String report = report();
        assertTrue(report, report.contains(FamilyNames.FEED_AUTO_SCROLL + ": invoked 5"));
        advance(10_000);
        assertEquals(0, list.scrolled);
    }

    @Test
    public void pausedAndUnreadyReadAsOff() {
        on(3);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", FeedAutoScroll.wanted());
        show(false, false);
        touch(MotionEvent.ACTION_DOWN);
        FeedAutoScroll.ended(null);
        assertFalse(FeedAutoScroll.fingerDown);
        assertEquals(Long.MIN_VALUE, FeedAutoScroll.videoEventAt);
        advance(10_000);
        assertEquals(0, list.scrolled);

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("unready", FeedAutoScroll.wanted()));
        assertTrue("on again once resumed and ready", FeedAutoScroll.wanted());
    }

    /** A finger down holds every list, and the wait starts over when the last one lifts. */
    @Test
    public void aTouchHoldsUntilTheFingerLifts() {
        on(3);
        show(false, false, false);
        advance(2_000);
        touch(MotionEvent.ACTION_DOWN);
        assertTrue(FeedAutoScroll.fingerDown);
        advance(6_000);
        assertEquals("held while the finger is down", 0, list.scrolled);
        touch(MotionEvent.ACTION_MOVE);
        touch(MotionEvent.ACTION_UP);
        assertFalse(FeedAutoScroll.fingerDown);
        assertEquals(SystemClock.uptimeMillis(), FeedAutoScroll.liftedAt);
        advance(2_500);
        assertEquals("the wait started over at the lift", 0, list.scrolled);
        advance(1_500);
        assertEquals(POST, list.scrolled);

        touch(MotionEvent.ACTION_POINTER_DOWN);
        assertTrue("a second finger holds too", FeedAutoScroll.fingerDown);
        touch(MotionEvent.ACTION_CANCEL);
        assertFalse("a cancel lifts", FeedAutoScroll.fingerDown);
        FeedAutoScroll.touch(activity, null);
    }

    /** A photo moves on after the time picked, right to the next post's header, as a settling fling. */
    @Test
    public void aPhotoPostMovesOnToTheNextHeader() {
        on(3);
        show(false, false, false);
        advance(3_000);
        assertEquals("not before the time picked", 0, list.scrolled);
        advance(1_000);
        assertEquals("the next header is at the top", POST, list.scrolled);
        assertEquals(Arrays.asList(FeedAutoScroll.SCROLL_STATE_SETTLING, FeedAutoScroll.SCROLL_STATE_IDLE), list.states);
        assertEquals(FeedAutoScroll.SCROLL_STATE_IDLE, list.scrollState);
        String report = report();
        assertTrue(report, report.contains(FeedAutoScroll.COUNT_MOVED + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.FEED_AUTO_SCROLL).toString(),
                HookStatus.missing(FamilyNames.FEED_AUTO_SCROLL).isEmpty());

        advance(4_000);
        assertEquals("and on to the one after", 2 * POST, list.scrolled);
    }

    /** The time is read at each post, so a change shows at once. */
    @Test
    public void aLongerTimeWaitsLonger() {
        on(10);
        show(false, false);
        advance(9_000);
        assertEquals(0, list.scrolled);
        advance(2_000);
        assertEquals(POST, list.scrolled);
    }

    @Test
    public void aVideoPostMovesOnWhenItsVideoStartsOverOrEnds() {
        on(3);
        show(true, false, true, false);
        advance(8_000);
        assertEquals("a video post doesn't go by the time", 0, list.scrolled);
        FeedAutoScroll.looped(null, 1);
        assertEquals(SystemClock.uptimeMillis(), FeedAutoScroll.videoEventAt);
        advance(1_000);
        assertEquals(POST, list.scrolled);

        advance(4_000);
        assertEquals("the photo after it goes by the time", 2 * POST, list.scrolled);
        advance(8_000);
        assertEquals("the next video waits again", 2 * POST, list.scrolled);
        FeedAutoScroll.ended(null);
        advance(1_000);
        assertEquals(3 * POST, list.scrolled);
    }

    /** With Tap to play holding feed videos, a video nobody tapped never ends, so it waits the time picked. */
    @Test
    public void withTapToPlayAnUntouchedVideoWaitsTheTimePicked() {
        Settings.TAP_TO_PLAY.save(true);
        on(3);
        show(true, true, false);
        advance(4_500);
        assertEquals(POST, list.scrolled);

        touch(MotionEvent.ACTION_DOWN);
        touch(MotionEvent.ACTION_UP);
        advance(8_000);
        assertEquals("a video tapped to play waits for its end", POST, list.scrolled);
        FeedAutoScroll.ended(null);
        advance(1_000);
        assertEquals(2 * POST, list.scrolled);
    }

    /** Comments, a sheet or a menu over the middle of Home hold it, and it carries on once they close. */
    @Test
    public void somethingOverHomeHoldsIt() {
        on(3);
        show(false, false);
        View sheet = new View(activity);
        sheet.setBackground(new ColorDrawable(Color.WHITE));
        root.addView(sheet, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        advance(8_000);
        assertEquals(0, list.scrolled);
        root.removeView(sheet);
        advance(5_000);
        assertEquals(POST, list.scrolled);
    }

    /** Another window or the app in the background takes the focus, and the timer waits for it to come back. */
    @Test
    public void withoutTheFocusNothingMoves() {
        on(3);
        show(false, false);
        controller.windowFocusChanged(false);
        advance(10_000);
        assertEquals(0, list.scrolled);
        controller.windowFocusChanged(true);
        advance(4_000);
        assertEquals(POST, list.scrolled);
    }

    /** Turned on while Home is open, it starts at the next lift or when the window gets its focus back. */
    @Test
    public void turningItOnNeedsNoRestart() {
        show(false, false);
        advance(10_000);
        assertEquals(0, list.scrolled);
        on(3);
        touch(MotionEvent.ACTION_DOWN);
        touch(MotionEvent.ACTION_UP);
        advance(4_000);
        assertEquals(POST, list.scrolled);

        Settings.FEED_AUTO_SCROLL.save(false);
        advance(10_000);
        assertEquals("off stops it", POST, list.scrolled);
    }

    @Test
    public void theLastPostStays() {
        on(3);
        show(false);
        list.more = false;
        advance(10_000);
        assertEquals(0, list.scrolled);
        assertTrue(list.states.isEmpty());
    }

    /** A build without the header id holds and says which id it missed. */
    @Test
    public void withoutTheHeaderIdNothingMoves() {
        on(3);
        showWithoutHeaderId(false, false, false);
        advance(10_000);
        assertEquals(0, list.scrolled);
        assertFalse(HookStatus.missing(FamilyNames.FEED_AUTO_SCROLL).isEmpty());
    }

    @Test
    public void aListIsTakenOnceAndLetGoWhenItLeavesTheWindow() {
        on(3);
        show(false, false);
        FeedAutoScroll.attach(list);
        assertEquals(1, FeedAutoScroll.attachedForTests().size());
        ((ViewGroup) list.getParent()).removeView(list);
        assertTrue(FeedAutoScroll.attachedForTests().isEmpty());
        advance(10_000);
        assertEquals(0, list.scrolled);
    }

    /** A window that isn't showing (the app in the background, a screen on top) holds it until it shows again. */
    @Test
    public void aHiddenWindowHoldsIt() {
        on(3);
        show(false, false);
        list.windowVisibility = View.GONE;
        advance(8_000);
        assertEquals(0, list.scrolled);
        list.windowVisibility = View.VISIBLE;
        advance(5_000);
        assertEquals(POST, list.scrolled);
    }
}
