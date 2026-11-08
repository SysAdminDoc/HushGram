/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Double tap to skip on feed videos (#562): with its switch on, a double tap on the left third of the
 * video playing in a feed moves it back 10 seconds and on the right third ahead 10, with the pause,
 * seek and play Instagram's scrubber makes, and the answer true keeps Instagram from liking the post.
 * The middle third, another post, the switch off, a pause or a failure leave the double tap to
 * Instagram.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedDoubleTapSkipTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    /** The post's media frame, the view the hook hands over, holding the video's frame and its cover. */
    private FrameLayout post;
    private FrameLayout video;
    private FrameLayout otherPost;
    private final Object player = new Object();
    private final Stand stand = new Stand();

    /** The feed's player as the stubs reach it. */
    private static final class Stand implements FeedSeekBar.Player {
        final List<String> calls = new ArrayList<>();
        View showing;
        boolean pauses = true;
        boolean viewThrows;

        @Override public View view(Object of) {
            if (viewThrows) throw new IllegalStateException("no holder");
            return showing;
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
    }

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        FeedSeekBar.resetForTests();
        FeedDoubleTapSkip.resetForTests();
        FeedSeekBar.playerForTests(stand);
        LinearLayout feed = new LinearLayout(activity);
        feed.setOrientation(LinearLayout.VERTICAL);
        post = new FrameLayout(activity);
        video = new FrameLayout(activity);
        View cover = new View(activity);
        video.addView(cover, new FrameLayout.LayoutParams(720, 400));
        post.addView(video, new FrameLayout.LayoutParams(720, 400));
        otherPost = new FrameLayout(activity);
        feed.addView(post, new LinearLayout.LayoutParams(720, 400));
        feed.addView(otherPost, new LinearLayout.LayoutParams(720, 400));
        activity.setContentView(feed, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stand.showing = cover;
        idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.FEED_DOUBLE_TAP_SKIP.resetToDefault();
        Settings.FEED_SEEK_BAR.resetToDefault();
        Settings.FEED_SEEK_BUTTON.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedDoubleTapSkip.resetForTests();
        FeedSeekBar.resetForTests();
        Settings.FEED_DOUBLE_TAP_SKIP.resetToDefault();
        Settings.FEED_SEEK_BAR.resetToDefault();
        Settings.FEED_SEEK_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** A double tap at [fraction] of the way across [view] and halfway down it, in screen terms. */
    private static MotionEvent tapAt(View view, float fraction) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        float x = at[0] + view.getWidth() * fraction;
        float y = at[1] + view.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
    }

    /** The switch on, and the player at [position] of a [duration] long video, through the progress hook. */
    private void playing(int position, int duration) {
        Settings.FEED_DOUBLE_TAP_SKIP.save(true);
        FeedSeekBar.progress(player, position, duration);
        idle();
    }

    @Test
    public void offToStartLeavesTheDoubleTapToInstagram() {
        assertEquals(Boolean.FALSE, Settings.FEED_DOUBLE_TAP_SKIP.defaultValue);
        FeedSeekBar.progress(player, 3000, 60000);
        idle();
        assertFalse(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        assertEquals(Collections.emptyList(), stand.calls);
        assertNull(FeedDoubleTapSkip.flashForTests());
    }

    /** The seek bar's own switches are off: the double tap works without the bar or its button. */
    @Test
    public void theRightThirdGoesAheadTenSeconds() {
        playing(3000, 60000);
        assertFalse(Settings.FEED_SEEK_BAR.get());
        assertTrue("Instagram doesn't like the post", FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        assertEquals(Arrays.asList("pause", "seek 13000", "play"), stand.calls);
        FeedDoubleTapSkip.Flash flash = FeedDoubleTapSkip.flashForTests();
        assertNotNull("the arrows show", flash);
        assertEquals("on the right", 1, flash.side);
        assertTrue("over the video", flash.getParent() == video);
        assertFalse("the skip was counted", HookStatus.snapshot().isEmpty());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedDoubleTapSkip.FLASH_MS));
        assertNull("the arrows go", FeedDoubleTapSkip.flashForTests());
        assertEquals("only the cover is left", 1, video.getChildCount());
    }

    @Test
    public void theLeftThirdGoesBackTenSeconds() {
        playing(25000, 60000);
        assertTrue(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.1f)));
        assertEquals(Arrays.asList("pause", "seek 15000", "play"), stand.calls);
        assertEquals("on the left", -1, FeedDoubleTapSkip.flashForTests().side);
    }

    @Test
    public void theMiddleThirdStillLikesThePost() {
        playing(25000, 60000);
        assertFalse(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.5f)));
        assertEquals(Collections.emptyList(), stand.calls);
        assertNull(FeedDoubleTapSkip.flashForTests());
    }

    @Test
    public void aSkipStaysInsideTheVideo() {
        playing(4000, 60000);
        assertTrue(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.1f)));
        playing(55000, 60000);
        assertTrue(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        assertEquals(Arrays.asList("pause", "seek 0", "play", "pause", "seek 59999", "play"), stand.calls);
    }

    /**
     * Two quick double taps go 20 seconds, the second from where the first landed, before the player says
     * so. The first one's arrows start their fade, which moves the clock a frame, and the video plays on
     * through that time, so the second lands that much further.
     */
    @Test
    public void aSecondDoubleTapGoesOnFromTheFirst() {
        playing(3000, 60000);
        MotionEvent first = tapAt(video, 0.9f);
        long firstAt = SystemClock.uptimeMillis();
        assertTrue(FeedDoubleTapSkip.doubleTap(post, first));
        MotionEvent second = tapAt(video, 0.9f);
        long played = SystemClock.uptimeMillis() - firstAt;
        assertTrue("the taps came close together", played < FeedDoubleTapSkip.FRESH_MS);
        assertTrue(FeedDoubleTapSkip.doubleTap(post, second));
        assertEquals(Arrays.asList("pause", "seek 13000", "play", "pause", "seek " + (23000 + played), "play"), stand.calls);
        assertNotNull("one set of arrows", FeedDoubleTapSkip.flashForTests());
        assertEquals("one set of arrows", 2, video.getChildCount());
    }

    /** The video went on playing since the player last said where it was. */
    @Test
    public void theTimeSinceTheLastProgressCounts() {
        playing(3000, 60000);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        assertEquals(Arrays.asList("pause", "seek 13400", "play"), stand.calls);
    }

    /** A player that wasn't playing isn't started by the skip. */
    @Test
    public void aPausedVideoStaysPaused() {
        playing(3000, 60000);
        stand.pauses = false;
        assertTrue(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        assertEquals(Arrays.asList("pause", "seek 13000"), stand.calls);
    }

    @Test
    public void anotherPostKeepsItsLike() {
        playing(3000, 60000);
        assertFalse("a post that isn't the playing video", FeedDoubleTapSkip.doubleTap(otherPost, tapAt(otherPost, 0.9f)));
        assertFalse("its frame with a tap on the video", FeedDoubleTapSkip.doubleTap(otherPost, tapAt(video, 0.9f)));
        assertEquals(Collections.emptyList(), stand.calls);
    }

    @Test
    public void aVideoOffScreenOrWithoutLengthKeepsTheLike() {
        playing(3000, 0);
        assertFalse("no length yet", FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        playing(3000, 60000);
        video.setVisibility(View.GONE);
        assertFalse("not shown", FeedDoubleTapSkip.doubleTap(post, tapAt(post, 0.9f)));
        video.setVisibility(View.VISIBLE);
        stand.showing = null;
        assertFalse("no cover", FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        assertEquals(Collections.emptyList(), stand.calls);
    }

    @Test
    public void pausedHushGramLeavesItToInstagram() {
        playing(3000, 60000);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        assertEquals(Collections.emptyList(), stand.calls);
    }

    @Test
    public void aFailureLeavesItToInstagram() {
        playing(3000, 60000);
        stand.viewThrows = true;
        assertFalse(FeedDoubleTapSkip.doubleTap(post, tapAt(video, 0.9f)));
        assertTrue("the failure is reported", String.join("\n", HookStatus.report()).contains(FeedDoubleTapSkip.HOOK));
        assertFalse("and nothing else breaks", FeedDoubleTapSkip.doubleTap(null, null));
    }

    @Test
    public void whereASkipLands() {
        assertEquals(13000, FeedDoubleTapSkip.target(3000, 1, 60000));
        assertEquals(0, FeedDoubleTapSkip.target(3000, -1, 60000));
        assertEquals(59999, FeedDoubleTapSkip.target(55000, 1, 60000));
        assertEquals(0, FeedDoubleTapSkip.target(Integer.MIN_VALUE, -1, 60000));
        assertEquals(59999, FeedDoubleTapSkip.target(Integer.MAX_VALUE, 1, 60000));
    }
}
