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
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        boolean pauses = true;
        SeekSheet sheet;

        @Override public SeekSheet sheet(Object of) {
            return sheet;
        }

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

        @Override public boolean pause(Object of) {
            calls.add("pause");
            return pauses;
        }

        @Override public boolean play(Object of) {
            calls.add("play");
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
        /** Whether it scrolls the way Instagram's feed list does, so a SeekBar in it waits for a sideways move. */
        boolean scrolls;

        Feed(Context context) {
            super(context);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            keptOut = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }

        @Override
        public boolean shouldDelayChildPressedState() {
            return scrolls;
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
        Settings.FEED_SEEK_BUTTON.resetToDefault();
        Settings.FEED_SEEK_BAR_LOOK.resetToDefault();
        Settings.FEED_SEEK_BAR_ON_EDGE.resetToDefault();
        Settings.SEEK_BAR_SHAPE.resetToDefault();
        Settings.FEED_SEEK_SHAPE.resetToDefault();
        Settings.SEEK_PREVIEW.resetToDefault();
        Settings.FEED_SEEK_TIME.resetToDefault();
        Settings.FEED_SEEK_TIME_LEFT.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedSeekBar.resetForTests();
        SeekPreview.resetForTests();
        Settings.SEEK_BAR_SHAPE.resetToDefault();
        Settings.FEED_SEEK_SHAPE.resetToDefault();
        Settings.SEEK_PREVIEW.resetToDefault();
        Settings.FEED_SEEK_TIME.resetToDefault();
        Settings.FEED_SEEK_TIME_LEFT.resetToDefault();
        TabSwipe.forgetForTests();
        Settings.FEED_SEEK_BAR.resetToDefault();
        Settings.FEED_SEEK_BUTTON.resetToDefault();
        Settings.FEED_SEEK_BAR_LOOK.resetToDefault();
        Settings.FEED_SEEK_BAR_ON_EDGE.resetToDefault();
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

    /** The toggle button (#437) is a small view after the video, and its tap shows or hides the bar. */
    @Test
    public void theToggleButtonShowsAndHidesTheBar() {
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_BUTTON.defaultValue);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        assertEquals("the bar stays hidden while its own switch is off", View.GONE, bar.getVisibility());
        FeedSeekBar.Toggle button = null;
        for (int at = 0; at < first.getChildCount(); at++) if (first.getChildAt(at) instanceof FeedSeekBar.Toggle) button = (FeedSeekBar.Toggle) first.getChildAt(at);
        assertNotNull("the video has a button", button);
        assertTrue("Instagram's icons stay drawn over it", first.indexOfChild(button) < first.indexOfChild(firstIcons));
        assertFalse(button.barShown());

        assertTrue(button.performClick());
        assertTrue(Settings.FEED_SEEK_BAR.savedValue());
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(button.barShown());

        assertTrue(button.performClick());
        assertFalse(Settings.FEED_SEEK_BAR.savedValue());
        assertEquals(View.GONE, bar.getVisibility());

        Settings.FEED_SEEK_BUTTON.save(false);
        tick();
        assertNull("the button goes with its switch", button.getParent());
    }

    /** #471: Seek bar look's choice reaches the bar as it follows the video. */
    @Test
    public void theBarTakesSeekBarLook() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        GlassSeekBar bar = (GlassSeekBar) bar();
        assertEquals("solid to start, as the bar always was", OverlayLook.SOLID, bar.look());

        Settings.FEED_SEEK_BAR_LOOK.save(OverlayLook.SEE_THROUGH);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals(OverlayLook.SEE_THROUGH, bar.look());
        assertSame("the same bar, restyled", bar, FeedSeekBar.barForTests());

        Settings.FEED_SEEK_BAR_LOOK.save(OverlayLook.SOLID);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertEquals(OverlayLook.SOLID, bar.look());
    }

    @Test
    public void withTheButtonOffTheBarKeepsToItself() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        for (int at = 0; at < first.getChildCount(); at++) assertFalse(first.getChildAt(at) instanceof FeedSeekBar.Toggle);
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

    /**
     * #567: on the bottom edge the bar is only as tall as its round handle, so a scroll that starts low
     * on the video lands on the video, and the button moves down with it. Flipping it back restores both.
     */
    @Test
    public void onTheEdgeTheBarIsOnlyAsTallAsItsHandle() {
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_BAR_ON_EDGE.defaultValue);
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        float density = bar.getResources().getDisplayMetrics().density;
        int roomy = Math.round(FeedSeekBar.HEIGHT_DP * density);
        assertEquals(roomy, bar.getLayoutParams().height);
        FeedSeekBar.Toggle button = null;
        for (int at = 0; at < first.getChildCount(); at++) if (first.getChildAt(at) instanceof FeedSeekBar.Toggle) button = (FeedSeekBar.Toggle) first.getChildAt(at);
        assertNotNull(button);
        int gap = Math.round(4 * density);
        assertEquals(roomy + gap, ((FrameLayout.LayoutParams) button.getLayoutParams()).bottomMargin);

        Settings.FEED_SEEK_BAR_ON_EDGE.save(true);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertSame("the same bar, made thinner", bar, FeedSeekBar.barForTests());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
        int handle = bar.getThumb() != null ? bar.getThumb().getIntrinsicHeight() : 0;
        int edge = Math.max(handle, Math.round(FeedSeekBar.EDGE_MIN_DP * density));
        assertEquals(edge, params.height);
        assertTrue("thinner than the roomy bar", params.height < roomy);
        assertEquals("flush with the bottom", 0, params.bottomMargin);
        assertEquals(Gravity.BOTTOM, params.gravity);
        assertEquals(edge + gap, ((FrameLayout.LayoutParams) button.getLayoutParams()).bottomMargin);

        Settings.FEED_SEEK_BAR_ON_EDGE.save(false);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertEquals(roomy, bar.getLayoutParams().height);
        assertEquals(roomy + gap, ((FrameLayout.LayoutParams) button.getLayoutParams()).bottomMargin);
    }

    /**
     * A feed post as Instagram 456 lays it out (#794): the video's frame two levels under the post, and
     * beside it a row along the video's bottom holding a tags button at the start and the sound button,
     * in a group of its own, at the end.
     */
    private static final class Post {
        final FrameLayout root;
        final FrameLayout frame;
        final View video;
        final FrameLayout row;
        final View tags;
        final int sound = View.generateViewId();

        Post(Activity activity) {
            root = new FrameLayout(activity);
            FrameLayout holder = new FrameLayout(activity);
            frame = new FrameLayout(activity);
            video = new View(activity);
            frame.addView(video, new FrameLayout.LayoutParams(720, 400));
            holder.addView(frame, new FrameLayout.LayoutParams(720, 400));
            root.addView(holder, new FrameLayout.LayoutParams(720, 400));
            FrameLayout overlay = new FrameLayout(activity);
            row = new FrameLayout(activity);
            tags = new View(activity);
            FrameLayout.LayoutParams tagsAt = new FrameLayout.LayoutParams(64, 64, Gravity.START | Gravity.CENTER_VERTICAL);
            tagsAt.setMarginStart(12);
            row.addView(tags, tagsAt);
            FrameLayout soundGroup = new FrameLayout(activity);
            soundGroup.setClickable(true);
            LinearLayout padded = new LinearLayout(activity);
            View icon = new View(activity);
            icon.setId(sound);
            FeedSeekBar.idForTests(activity, FeedSeekBar.SOUND_BUTTON, sound);
            padded.addView(icon, new LinearLayout.LayoutParams(48, 48));
            soundGroup.addView(padded, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            row.addView(soundGroup, new FrameLayout.LayoutParams(116, 88, Gravity.END));
            overlay.addView(row, new FrameLayout.LayoutParams(720, 88, Gravity.BOTTOM));
            root.addView(overlay, new FrameLayout.LayoutParams(720, 400));
        }
    }

    private Post post() {
        Post post = new Post(controller.get());
        feed.addView(post.root, 0, new LinearLayout.LayoutParams(720, 400));
        stand.plays(player, post.video);
        idle();
        assertEquals("laid out", 88, post.row.getHeight());
        return post;
    }

    private static FrameLayout.LayoutParams buttonAt() {
        View button = FeedSeekBar.buttonForTests();
        assertNotNull("the video has a button", button);
        return (FrameLayout.LayoutParams) button.getLayoutParams();
    }

    /**
     * #794: while the bar shows, Instagram's row of tags and sound buttons is lifted clear of it, and the
     * toggle sits in that row, past the tags button and level with both, so nothing covers anything.
     */
    @Test
    public void theBarLiftsInstagramsButtonsAndTheToggleLinesUpWithThem() {
        Post post = post();
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        SeekBar bar = bar();
        assertSame(post.frame, bar.getParent());
        int lift = FeedSeekBar.barHeight(post.video, bar);
        assertTrue(lift > 0);
        assertEquals("the row sits over the bar", -lift, post.row.getTranslationY(), 0f);
        assertEquals("the tags button keeps its place in the row", 0f, post.tags.getTranslationY(), 0f);
        float density = post.video.getResources().getDisplayMetrics().density;
        int size = Math.round(FeedSeekBar.BUTTON_DP * density);
        int gap = Math.round(FeedSeekBar.BUTTON_GAP_DP * density);
        FrameLayout.LayoutParams at = buttonAt();
        assertEquals("past the tags button", 12 + 64 + gap, at.getMarginStart());
        assertEquals("level with the row's buttons, lifted with them", 88 - 44 + lift - size / 2, at.bottomMargin);

        assertTrue(FeedSeekBar.buttonForTests().performClick());
        assertFalse(Settings.FEED_SEEK_BAR.savedValue());
        assertEquals(View.GONE, bar.getVisibility());
        assertEquals("with the bar hidden the row is back", 0f, post.row.getTranslationY(), 0f);
        at = buttonAt();
        assertEquals(12 + 64 + gap, at.getMarginStart());
        assertEquals("still level with the row", 88 - 44 - size / 2, at.bottomMargin);

        assertTrue(FeedSeekBar.buttonForTests().performClick());
        assertEquals(-lift, post.row.getTranslationY(), 0f);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals("lifted once, not again on each tick", -lift, post.row.getTranslationY(), 0f);
    }

    /** #794: the row goes back where it was when the bar moves to another video or goes, and only then. */
    @Test
    public void instagramsButtonsGoBackWhenTheBarMovesOnOrGoes() {
        Post post = post();
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        int lift = FeedSeekBar.barHeight(post.video, bar());
        assertEquals(-lift, post.row.getTranslationY(), 0f);

        stand.plays(player, secondVideo);
        FeedSeekBar.progress(player, 4000, 15000);
        assertSame(second, bar().getParent());
        assertEquals("the post the bar left has its buttons back", 0f, post.row.getTranslationY(), 0f);

        stand.plays(player, post.video);
        FeedSeekBar.progress(player, 5000, 15000);
        assertEquals(-lift, post.row.getTranslationY(), 0f);
        FeedSeekBar.resetForTests();
        assertEquals("a bar taken away puts them back too", 0f, post.row.getTranslationY(), 0f);

        FeedSeekBar.playerForTests(stand);
        FeedSeekBar.idForTests(controller.get(), FeedSeekBar.SOUND_BUTTON, post.sound);
        FeedSeekBar.progress(player, 6000, 15000);
        assertEquals(-lift, post.row.getTranslationY(), 0f);
        post.row.setTranslationY(-5f);
        Settings.FEED_SEEK_BAR.save(false);
        FeedSeekBar.progress(player, 7000, 15000);
        assertEquals("a row Instagram moved itself stays where Instagram put it", -5f, post.row.getTranslationY(), 0f);
    }

    /** No row of buttons around the video, as on a post laid out some other way: the toggle keeps its corner. */
    @Test
    public void withoutInstagramsRowTheToggleKeepsItsCorner() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        float density = firstVideo.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams at = buttonAt();
        assertEquals(Math.round(8 * density), at.getMarginStart());
        assertEquals(FeedSeekBar.barHeight(firstVideo, bar()) + Math.round(4 * density), at.bottomMargin);
    }

    /** #472: like Instagram's own scrubber, the video pauses under the finger and plays on from where it lands. */
    @Test
    public void aDragPausesTheVideoThenSeeksItAndPlaysItOn() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        Listener drag = new Listener(bar);

        drag.start();
        assertEquals(Collections.singletonList("pause"), stand.calls);
        bar.setProgress(12000);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals("a drag isn't overwritten by the video", 12000, bar.getProgress());
        drag.stop();

        assertEquals(Arrays.asList("pause", "seek 12000", "play"), stand.calls);
        tick();
        assertEquals(12000, bar.getProgress());
    }

    /** A player whose pause stub wasn't filled is seeked but never played, so a video nobody paused is left alone. */
    @Test
    public void aDragThatPausedNothingPlaysNothing() {
        Settings.FEED_SEEK_BAR.save(true);
        stand.pauses = false;
        FeedSeekBar.progress(player, 3000, 15000);
        Listener drag = new Listener(bar());
        drag.start();
        bar().setProgress(9000);
        drag.stop();
        assertEquals(Arrays.asList("pause", "seek 9000"), stand.calls);
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
    public void aScrollThatStartsOnTheBarGoesBackToTheFeed() {
        feed.scrolls = true;
        Settings.FEED_SEEK_BAR.save(true);
        View mainList = mainTabs();
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        int progress = bar.getProgress();
        int slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();

        touch(bar, MotionEvent.ACTION_DOWN, 100, 10);
        assertTrue(feed.keptOut);
        touch(bar, MotionEvent.ACTION_MOVE, 102, 10 - slop / 2f);
        assertTrue("a move inside the slop keeps the bar's claim", feed.keptOut);
        touch(bar, MotionEvent.ACTION_MOVE, 104, 10 - slop - 4);
        assertFalse("the feed takes the scroll", feed.keptOut);
        assertTrue("and so do the main tabs", TabSwipe.input(mainList, 1));
        touch(bar, MotionEvent.ACTION_CANCEL, 104, 10 - slop - 4);
        assertEquals("nothing seeked", progress, bar.getProgress());
    }

    @Test
    public void aSidewaysDragOnTheBarKeepsItsClaim() {
        feed.scrolls = true;
        Settings.FEED_SEEK_BAR.save(true);
        View mainList = mainTabs();
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        int slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();

        touch(bar, MotionEvent.ACTION_DOWN, 100, 10);
        touch(bar, MotionEvent.ACTION_MOVE, 100 + slop * 3, 10 + slop / 2f);
        assertTrue(feed.keptOut);
        touch(bar, MotionEvent.ACTION_MOVE, 100 + slop * 3, 10 + slop * 4);
        assertTrue("a drag under way stays the bar's when the finger drifts down", feed.keptOut);
        assertFalse(TabSwipe.input(mainList, 1));
        touch(bar, MotionEvent.ACTION_UP, 100 + slop * 3, 10 + slop * 4);
        assertTrue(TabSwipe.input(mainList, 1));
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
        touch(view, action, 100, 10);
    }

    private static void touch(View view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
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
    public void theBarHidingMidDragSeeksNothingButPlaysTheVideoOn() {
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
        assertEquals(Arrays.asList("pause", "play"), stand.calls);
    }

    /** #586: a shape takes over the bar as it follows the video, and Standard hands it back. */
    @Test
    public void theBarTakesTheChosenShapeAndGivesItBack() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        assertFalse(bar.getProgressDrawable() instanceof SeekShapeDrawable);

        Settings.SEEK_BAR_SHAPE.save(SeekShape.ROUNDED);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertSame("the same bar, reshaped", bar, FeedSeekBar.barForTests());
        assertTrue(bar.getProgressDrawable() instanceof SeekShapeDrawable);
        assertEquals("a shape has no round handle", 0, bar.getThumb().getIntrinsicWidth());

        Settings.SEEK_BAR_SHAPE.save(SeekShape.STANDARD);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertFalse(bar.getProgressDrawable() instanceof SeekShapeDrawable);
    }

    /** #567: the thin line lies on the edge with no switch, and a shape with a tall handle gets room for it there. */
    @Test
    public void aLineSitsOnTheEdgeAndATallHandleGetsRoom() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.SEEK_BAR_SHAPE.save(SeekShape.LINE);
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        SeekBar bar = bar();
        float density = bar.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(FeedSeekBar.EDGE_MIN_DP * density), bar.getLayoutParams().height);
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.LINE, true));

        Settings.SEEK_BAR_SHAPE.save(SeekShape.WAVE);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals("off the edge it keeps its room", Math.round(FeedSeekBar.HEIGHT_DP * density), bar.getLayoutParams().height);
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.WAVE, false));

        Settings.FEED_SEEK_BAR_ON_EDGE.save(true);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertEquals(Math.round(FeedSeekBar.EDGE_TALL_DP * density), bar.getLayoutParams().height);
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.WAVE, true));
    }

    /** #567: Feed seek bar shape gives feed videos a thin line while every other bar stays standard, and Same follows it again. */
    @Test
    public void theFeedKeepsAShapeOfItsOwn() {
        Settings.FEED_SEEK_BAR.save(true);
        assertEquals(FeedSeekShape.SAME, Settings.FEED_SEEK_SHAPE.defaultValue);
        Settings.FEED_SEEK_SHAPE.save(FeedSeekShape.LINE);
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        SeekBar bar = bar();
        float density = bar.getResources().getDisplayMetrics().density;
        assertEquals(SeekShape.STANDARD, Settings.SEEK_BAR_SHAPE.get());
        assertEquals("reels and stories keep the standard bar", SeekShape.STANDARD, SeekShapes.wanted());
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.LINE, true));
        assertEquals("the line lies on the edge", Math.round(FeedSeekBar.EDGE_MIN_DP * density), bar.getLayoutParams().height);

        Settings.FEED_SEEK_SHAPE.save(FeedSeekShape.SAME);
        Settings.SEEK_BAR_SHAPE.save(SeekShape.ROUNDED);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertTrue("Same follows Seek bar shape", ((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.ROUNDED, false));
        assertEquals(Math.round(FeedSeekBar.HEIGHT_DP * density), bar.getLayoutParams().height);

        Settings.FEED_SEEK_SHAPE.save(FeedSeekShape.STANDARD);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertFalse("Standard for the feed alone", bar.getProgressDrawable() instanceof SeekShapeDrawable);
        assertEquals(SeekShape.ROUNDED, SeekShapes.wanted());
    }

    /**
     * #711: Time on the seek bar puts the time played and the length over the bar's middle, follows the
     * video and a drag, flips to the time left with a tap, and goes with the bar or its switch.
     */
    @Test
    public void theTimeSitsOverTheBarAndATapShowsWhatsLeft() {
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_TIME.defaultValue);
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_TIME_LEFT.defaultValue);
        assertEquals(Settings.FEED_SEEK_TIME, Settings.FEED_SEEK_TIME_LEFT.getParentSettings().get(0));
        assertFalse(Settings.FEED_SEEK_TIME.rebootApp);
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        SeekBar bar = bar();
        int children = first.getChildCount();
        assertNull("off to start", FeedSeekBar.timeForTests());
        assertNull(FeedSeekBar.shownTime());

        Settings.FEED_SEEK_TIME.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);
        assertSame(first, time.getParent());
        assertEquals(children + 1, first.getChildCount());
        assertTrue("over the bar", first.indexOfChild(time) > first.indexOfChild(bar));
        assertEquals("0:03 / 0:15", time.getText().toString());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);
        float density = bar.getResources().getDisplayMetrics().density;
        assertEquals(bar.getLayoutParams().height + Math.round(FeedSeekBar.TIME_GAP_DP * density), params.bottomMargin);
        idle();
        assertSame(time, FeedSeekBar.shownTime());

        FeedSeekBar.progress(player, 4000, 15000);
        assertEquals("it follows the video", "0:04 / 0:15", time.getText().toString());
        assertTrue(time.performClick());
        assertTrue(Settings.FEED_SEEK_TIME_LEFT.get());
        assertEquals("a tap shows what's left", "-0:11 / 0:15", time.getText().toString());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + FeedSeekBar.COUNT_TIME_FLIPS + " 1"));

        SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
        new Listener(bar).start();
        bar.setProgress(9000);
        listener.onProgressChanged(bar, 9000, true);
        assertEquals("it follows a drag", "-0:06 / 0:15", time.getText().toString());
        FeedSeekBar.progress(player, 4000, 15000);
        assertEquals("and the finger beats the paused video", "-0:06 / 0:15", time.getText().toString());
        new Listener(bar).stop();
        assertTrue(time.performClick());
        assertFalse(Settings.FEED_SEEK_TIME_LEFT.get());
        assertEquals("0:09 / 0:15", time.getText().toString());

        Settings.FEED_SEEK_BAR.save(false);
        tick();
        assertNull("it goes with the bar", FeedSeekBar.shownTime());
        assertNull(time.getParent());

        Settings.FEED_SEEK_BAR.save(true);
        tick();
        assertNotNull("and comes back with it", FeedSeekBar.timeForTests());
        Settings.FEED_SEEK_TIME.save(false);
        tick();
        assertNull("its switch off takes it away", FeedSeekBar.timeForTests());
        assertEquals(children, first.getChildCount());
    }

    /** #711: the time reads as the reels' does, the end counted as the end, and an hour or more gets its own place. */
    @Test
    public void theTimeReadsPlayedOrLeftOverTheLength() {
        assertEquals("0:03 / 0:15", FeedSeekBar.timeText(3000, 15000, false));
        assertEquals("-0:12 / 0:15", FeedSeekBar.timeText(3000, 15000, true));
        assertEquals("0:15 / 0:15", FeedSeekBar.timeText(16000, 15000, false));
        assertEquals("-0:00 / 0:15", FeedSeekBar.timeText(16000, 15000, true));
        assertEquals("0:00 / 0:15", FeedSeekBar.timeText(-500, 15000, false));
        assertEquals("1:05 / 1:02:05", FeedSeekBar.timeText(65000, 3725000, false));
    }

    /** #711: HushGram paused, the time goes with the bar, and nothing flips. */
    @Test
    public void aPauseTakesTheTimeAwayToo() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_TIME.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        tick();
        assertNull(FeedSeekBar.timeForTests());
        assertNull(time.getParent());
        time.performClick();
        assertFalse(Settings.FEED_SEEK_TIME_LEFT.savedValue());
    }

    /** #567: dragging shows the time above the finger; the player moving on its own doesn't. */
    @Test
    public void onlyADragShowsThePreview() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.SEEK_PREVIEW.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
        listener.onProgressChanged(bar, 4000, false);
        assertNull("nothing while it plays", SeekPreview.cardForTests());
        listener.onProgressChanged(bar, 4000, true);
        assertNull("nothing before the drag starts", SeekPreview.cardForTests());

        bar.setPressed(true);
        new Listener(bar).start();
        listener.onProgressChanged(bar, 5000, true);
        assertNotNull(SeekPreview.cardForTests());
        assertEquals("0:05 / 0:15", SeekPreview.timeForTests());
    }

    /** #567: most feed posts have no picture strip, so the paused video itself follows the drag, a move at a time. */
    @Test
    public void aPostWithNoStripShowsTheMomentInTheVideo() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.SEEK_PREVIEW.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
        bar.setPressed(true);
        new Listener(bar).start();
        listener.onProgressChanged(bar, 5000, true);
        listener.onProgressChanged(bar, 6000, true);
        listener.onProgressChanged(bar, 7000, true);
        assertEquals("the first move at once, the others wait", Arrays.asList("pause", "seek 5000"), stand.calls);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.FOLLOW_MS));
        assertEquals("then where the finger is by then", Arrays.asList("pause", "seek 5000", "seek 7000"), stand.calls);

        listener.onProgressChanged(bar, 8000, true);
        bar.setProgress(8000);
        new Listener(bar).stop();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.FOLLOW_MS));
        assertEquals("letting go seeks and plays, and a move still waiting doesn't come after",
                Arrays.asList("pause", "seek 5000", "seek 7000", "seek 8000", "play"), stand.calls);
    }

    /** A post with a strip shows its picture on the card instead, and the video stays where it was paused. */
    @Test
    public void aPostWithAStripLeavesTheVideoStill() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.SEEK_PREVIEW.save(true);
        Map<String, Object> info = new HashMap<>();
        info.put("sprite_urls", Collections.singletonList("https://example.invalid/sprite.jpg"));
        info.put("thumbnail_width", 100);
        info.put("thumbnail_height", 124);
        info.put("thumbnails_per_row", 15);
        info.put("max_thumbnails_per_sprite", 105);
        info.put("thumbnail_duration", 1.1d);
        stand.sheet = SeekSheet.of(info);
        assertNotNull(stand.sheet);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
        bar.setPressed(true);
        new Listener(bar).start();
        listener.onProgressChanged(bar, 5000, true);
        listener.onProgressChanged(bar, 9000, true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.FOLLOW_MS));
        assertEquals(Collections.singletonList("pause"), stand.calls);
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
