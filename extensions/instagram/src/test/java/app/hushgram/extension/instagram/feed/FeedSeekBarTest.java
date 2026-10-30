/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Seek bar on feed videos (#389): the video a feed player is playing gets a bar along its bottom
 * that follows it, moves with the player to its next video and goes when the video does, and
 * letting go of a drag seeks; the switch off, a pause or unfilled stubs show none.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedSeekBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Feed feed;
    private FrameLayout first;
    private FrameLayout second;
    private View firstVideo;
    private View secondVideo;
    private View firstIcons;
    private final Object player = new Object();
    private final Stand stand = new Stand();

    /** The feed's player as the stubs reach it. */
    private final class Stand implements FeedSeekBar.Player {
        final List<Object> owners = new ArrayList<>();
        final List<View> showing = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        int reads;
        boolean viewThrows;

        @Override public View view(Object of) {
            reads++;
            if (viewThrows) throw new IllegalStateException("no holder");
            int at = owners.indexOf(of);
            return at < 0 ? null : showing.get(at);
        }

        @Override public boolean seekTo(Object of, int positionMs) {
            calls.add("seek " + positionMs);
            return true;
        }

        void plays(Object owner, View video) {
            int at = owners.indexOf(owner);
            if (at < 0) {
                owners.add(owner);
                showing.add(video);
            } else {
                showing.set(at, video);
            }
        }
    }

    /** The feed's list, noting when a child asks it not to take a touch. */
    private static final class Feed extends LinearLayout {
        boolean keptOut;

        Feed(Context context) {
            super(context);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            keptOut = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        FeedSeekBar.resetForTests();
        TabSwipe.forgetForTests();
        FeedSeekBar.playerForTests(stand);
        feed = new Feed(activity);
        feed.setOrientation(LinearLayout.VERTICAL);
        first = new FrameLayout(activity);
        second = new FrameLayout(activity);
        firstVideo = new View(activity);
        secondVideo = new View(activity);
        firstIcons = new View(activity);
        first.addView(firstVideo, new FrameLayout.LayoutParams(720, 400));
        first.addView(firstIcons, new FrameLayout.LayoutParams(96, 96, Gravity.BOTTOM | Gravity.END));
        second.addView(secondVideo, new FrameLayout.LayoutParams(720, 400));
        feed.addView(first, new LinearLayout.LayoutParams(720, 400));
        feed.addView(second, new LinearLayout.LayoutParams(720, 400));
        activity.setContentView(feed, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stand.plays(player, firstVideo);
        idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.FEED_SEEK_BAR.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedSeekBar.resetForTests();
        TabSwipe.forgetForTests();
        Settings.FEED_SEEK_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tick() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.TICK_MS));
    }

    private static SeekBar bar() {
        SeekBar bar = FeedSeekBar.barForTests();
        assertNotNull("the video has a bar", bar);
        return bar;
    }

    @Test
    public void offToStartAddsNothing() {
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        assertNull(FeedSeekBar.barForTests());
        assertEquals("only the video and its icons", 2, first.getChildCount());
    }

    @Test
    public void aFeedVideoGetsABarAlongItsBottomThatFollowsIt() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        assertSame("in the frame holding the video", first, bar.getParent());
        assertEquals("just after the video", first.indexOfChild(firstVideo) + 1, first.indexOfChild(bar));
        assertTrue("Instagram's icons stay drawn over the bar", first.indexOfChild(bar) < first.indexOfChild(firstIcons));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
        assertEquals(Gravity.BOTTOM, params.gravity);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(15000, bar.getMax());
        assertEquals(3000, bar.getProgress());

        FeedSeekBar.progress(player, 9000, 15000);
        assertEquals(9000, bar.getProgress());
        assertSame("one bar per player", bar, FeedSeekBar.barForTests());
        assertEquals(3, first.getChildCount());
        assertTrue(stand.calls.isEmpty());
    }

    @Test
    public void lettingGoOfADragSeeksTheVideo() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        Listener drag = new Listener(bar);

        drag.start();
        bar.setProgress(12000);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals("a drag isn't overwritten by the video", 12000, bar.getProgress());
        drag.stop();

        assertEquals(Collections.singletonList("seek 12000"), stand.calls);
        tick();
        assertEquals(12000, bar.getProgress());
    }

    @Test
    public void aVideoOffScreenHidesItsBarThenTakesItAway() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();

        first.setVisibility(View.GONE);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
        first.setVisibility(View.VISIBLE);
        tick();
        assertEquals("back on screen in time keeps the bar", View.VISIBLE, bar.getVisibility());

        first.setVisibility(View.GONE);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.GONE_MS + FeedSeekBar.TICK_MS * 2));
        assertNull(FeedSeekBar.barForTests());
        assertNull(bar.getParent());
        int reads = stand.reads;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.TICK_MS * 8));
        assertEquals("a bar taken away stops ticking", reads, stand.reads);

        first.setVisibility(View.VISIBLE);
        FeedSeekBar.progress(player, 5000, 15000);
        assertSame("the player playing again brings a bar back", first, bar().getParent());
    }

    @Test
    public void aPlayingVideosHiddenCoverKeepsItsBar() {
        // The stub hands over the video's cover, which Instagram hides once the video plays.
        Settings.FEED_SEEK_BAR.save(true);
        firstVideo.setVisibility(View.GONE);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        assertSame(first, bar.getParent());
        assertEquals(first.indexOfChild(firstVideo) + 1, first.indexOfChild(bar));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.GONE_MS + FeedSeekBar.TICK_MS * 2));
        assertSame("still there while the video plays", bar, FeedSeekBar.barForTests());
        assertEquals(View.VISIBLE, bar.getVisibility());
    }

    @Test
    public void aFingerOnTheBarKeepsTheFeedFromTakingTheDrag() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        touch(bar, MotionEvent.ACTION_DOWN);
        assertTrue("the feed and the tab pager leave the drag to the bar", feed.keptOut);
    }

    @Test
    public void aFingerOnTheBarHoldsTheMainTabsUntilItLifts() {
        // The main tabs' list takes a sideways drag whatever the bar asks its parents, Stop swiping
        // between tabs off or not.
        Settings.FEED_SEEK_BAR.save(true);
        View mainList = mainTabs();
        assertTrue(TabSwipe.input(mainList, 1));
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();

        touch(bar, MotionEvent.ACTION_DOWN);
        assertFalse("held while the finger is down", TabSwipe.input(mainList, 1));
        touch(bar, MotionEvent.ACTION_MOVE);
        assertFalse(TabSwipe.input(mainList, 1));
        touch(bar, MotionEvent.ACTION_UP);
        assertTrue("let go when it lifts", TabSwipe.input(mainList, 1));

        touch(bar, MotionEvent.ACTION_DOWN);
        touch(bar, MotionEvent.ACTION_CANCEL);
        assertTrue("let go when the touch is taken away", TabSwipe.input(mainList, 1));

        touch(bar, MotionEvent.ACTION_DOWN);
        FeedSeekBar.resetForTests();
        assertTrue("let go when the bar goes away", TabSwipe.input(mainList, 1));
    }

    @Test
    public void withTheSwitchOffAFingerOnTheBarLeavesTheMainTabs() {
        Settings.FEED_SEEK_BAR.save(true);
        View mainList = mainTabs();
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        Settings.FEED_SEEK_BAR.save(false);
        touch(bar, MotionEvent.ACTION_DOWN);
        assertTrue(TabSwipe.input(mainList, 1));
    }

    /** The main tabs' pager, handed to TabSwipe the way Instagram's setup does; returns its list. */
    private View mainTabs() {
        FrameLayout pager = new FrameLayout(controller.get());
        View list = new View(controller.get());
        pager.addView(list);
        TabSwipe.mainPager(pager);
        return list;
    }

    private static void touch(View view, int action) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, 100, 10, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test
    public void theFeedHandingThePlayerAnotherVideoMovesTheBar() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        stand.plays(player, secondVideo);
        tick();
        assertSame(bar, FeedSeekBar.barForTests());
        assertSame("the bar follows the player's video", second, bar.getParent());
        assertEquals(second.indexOfChild(secondVideo) + 1, second.indexOfChild(bar));
        assertEquals("the old video's frame is as Instagram left it", 2, first.getChildCount());
    }

    @Test
    public void anotherPlayerTakesTheBarOver() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar old = bar();
        Object next = new Object();
        stand.plays(next, secondVideo);
        FeedSeekBar.progress(next, 1000, 6000);
        SeekBar bar = bar();
        assertNull("the old player's bar goes", old.getParent());
        assertSame(second, bar.getParent());
        assertEquals(6000, bar.getMax());
        new Listener(old).stop();
        assertTrue("the old bar seeks nothing", stand.calls.isEmpty());
    }

    @Test
    public void aVideoWithNoLengthYetOrOutsideAFrameGetsNoBar() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 0, 0);
        assertNull(FeedSeekBar.barForTests());
        FeedSeekBar.progress(player, 0, 15000);
        assertSame(first, bar().getParent());

        FeedSeekBar.resetForTests();
        FeedSeekBar.playerForTests(stand);
        stand.plays(player, first);
        FeedSeekBar.progress(player, 0, 15000);
        tick();
        assertNull("a view whose parent isn't a frame", FeedSeekBar.barForTests());
    }

    @Test
    public void unfilledStubsShowNothing() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.playerForTests(FeedSeekBar.PATCHED);
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        assertNull(FeedSeekBar.barForTests());
        assertEquals(2, first.getChildCount());
    }

    @Test
    public void aStubThrowingLeavesTheVideoAlone() {
        Settings.FEED_SEEK_BAR.save(true);
        stand.viewThrows = true;
        FeedSeekBar.progress(player, 3000, 15000);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertNull(FeedSeekBar.barForTests());
        assertEquals(2, first.getChildCount());
    }

    @Test
    public void switchingOffOrPausingHushgramHidesTheBarAndADragDoesNothing() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();

        Settings.FEED_SEEK_BAR.save(false);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
        new Listener(bar).start();
        new Listener(bar).stop();
        assertTrue(stand.calls.isEmpty());

        Settings.FEED_SEEK_BAR.save(true);
        tick();
        assertEquals(View.VISIBLE, bar.getVisibility());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
    }

    @Test
    public void theBarHidingMidDragSeeksNothing() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        Listener drag = new Listener(bar);
        drag.start();
        first.setVisibility(View.GONE);
        tick();
        first.setVisibility(View.VISIBLE);
        tick();
        drag.stop();
        assertTrue(stand.calls.isEmpty());
    }

    /** The bar's own drag callbacks, as a finger on it gives them. */
    private static final class Listener {
        private final SeekBar bar;

        Listener(SeekBar bar) {
            this.bar = bar;
        }

        void start() {
            SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
            if (listener != null) listener.onStartTrackingTouch(bar);
        }

        void stop() {
            SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
            if (listener != null) listener.onStopTrackingTouch(bar);
        }
    }
}
