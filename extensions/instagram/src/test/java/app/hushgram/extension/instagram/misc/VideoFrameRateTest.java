/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When Match the video's frame rate asks for a video's frame rate, and when it takes it back (#868). */
@RunWith(RobolectricTestRunner.class)
public class VideoFrameRateTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final long NS_PER_SECOND = 1_000_000_000L;
    /** The screen's frames, 120 a second, in milliseconds. */
    private static final long FRAME_MS = 8;

    /** Each video's next frame timestamp and how far apart its frames are, both in nanoseconds. */
    private final Map<TextureView, long[]> clips = new HashMap<>();
    private final List<Object[]> votes = new ArrayList<>();
    private ActivityController<Activity> controller;
    private FrameLayout screen;
    private VideoFrameRate.Watched watched;
    private long now = 10_000;

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.MATCH_VIDEO_FRAME_RATE.save(true);
        HookStatus.clear();
        VideoFrameRate.stamps = view -> {
            long[] clip = clips.get(view);
            return clip == null ? 0 : clip[0];
        };
        VideoFrameRate.voter = (view, rate) -> votes.add(new Object[]{view, rate});
        controller = Robolectric.buildActivity(Activity.class).setup();
        screen = new FrameLayout(controller.get());
        controller.get().setContentView(screen);
        watched = new VideoFrameRate.Watched(screen);
    }

    @After
    public void restore() {
        if (controller != null) controller.close();
        VideoFrameRate.resetForTests();
        Settings.MATCH_VIDEO_FRAME_RATE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private TextureView video() {
        TextureView view = new TextureView(controller.get());
        screen.addView(view, new FrameLayout.LayoutParams(1080, 1920));
        return view;
    }

    /** [view] plays at [perSecond] frames a second from now on, its first frame already shown. */
    private void play(TextureView view, double perSecond) {
        clips.put(view, new long[]{5 * NS_PER_SECOND, Math.round(NS_PER_SECOND / perSecond)});
    }

    /**
     * Draws the screen for [ms] milliseconds at 120 frames a second. Each video moves on to each
     * frame whose time has come, the way a TextureView shows the newest frame it has when the window
     * draws.
     */
    private void run(long ms) {
        long end = now + ms;
        long start = now;
        Map<TextureView, Long> began = new HashMap<>();
        for (Map.Entry<TextureView, long[]> clip : clips.entrySet()) began.put(clip.getKey(), clip.getValue()[0]);
        while (now < end) {
            now += FRAME_MS;
            for (Map.Entry<TextureView, long[]> clip : clips.entrySet()) {
                long[] state = clip.getValue();
                if (state[1] == 0) continue;
                long first = began.get(clip.getKey());
                long shown = (now - start) * 1_000_000L / state[1];
                state[0] = first + shown * state[1];
            }
            watched.frame(now);
        }
    }

    private void pause(TextureView view) {
        clips.get(view)[1] = 0;
    }

    private float lastVote(View view) {
        for (int i = votes.size() - 1; i >= 0; i--) if (votes.get(i)[0] == view) return (float) votes.get(i)[1];
        return 0;
    }

    private static long[] steps(double perSecond, int count, long... jitterNs) {
        long[] out = new long[count];
        long step = Math.round(NS_PER_SECOND / perSecond);
        for (int i = 0; i < count; i++) {
            long jitter = jitterNs.length == 0 ? 0 : jitterNs[i % jitterNs.length];
            out[i] = NS_PER_SECOND + i * step + (i == 0 || i == count - 1 ? 0 : jitter);
        }
        return out;
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.MATCH_VIDEO_FRAME_RATE.resetToDefault();
        assertFalse(Settings.MATCH_VIDEO_FRAME_RATE.get());
        assertFalse(Settings.MATCH_VIDEO_FRAME_RATE.rebootApp);
    }

    /** Usual video rates read as themselves, NTSC's included, through frames shown late or early. */
    @Test
    public void readsTheUsualVideoRates() {
        assertEquals(30f, VideoFrameRate.rateOf(steps(30, 31), 31), 0f);
        assertEquals(30f, VideoFrameRate.rateOf(steps(29.97, 31), 31), 0f);
        assertEquals(24f, VideoFrameRate.rateOf(steps(23.976, 31), 31), 0f);
        assertEquals(25f, VideoFrameRate.rateOf(steps(25, 31), 31), 0f);
        assertEquals(60f, VideoFrameRate.rateOf(steps(60, 31), 31), 0f);
        // A screen hopping to 40 Hz shows a 30 fps reel's frames 25 and 50 ms apart: still 30.
        assertEquals(30f, VideoFrameRate.rateOf(steps(30, 31, 8_000_000L, -8_000_000L), 31), 0f);
    }

    /** Too few frames, or a rate no video has, asks for nothing. */
    @Test
    public void tooFewFramesOrAnOddRateAskForNothing() {
        assertEquals(0f, VideoFrameRate.rateOf(steps(30, VideoFrameRate.MIN_FRAMES - 1), VideoFrameRate.MIN_FRAMES - 1), 0f);
        assertEquals(0f, VideoFrameRate.rateOf(steps(38, 31), 31), 0f);
        assertEquals(0f, VideoFrameRate.rateOf(steps(12, 31), 31), 0f);
        assertEquals(0f, VideoFrameRate.rateOf(new long[]{5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5}, 12), 0f);
    }

    /** A 30 fps reel gets 30 asked for on its own view, once, and the request goes when it pauses. */
    @Test
    public void aPlayingReelAsksForItsRateAndAPauseTakesItBack() {
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        assertEquals(30f, lastVote(reel), 0f);
        assertEquals("asked once, not every frame", 1, votes.size());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(VideoFrameRate.ASKED + " 1"));

        pause(reel);
        run(VideoFrameRate.STOPPED_AFTER_MS + 50);
        assertTrue("back to the default", Float.isNaN(lastVote(reel)));
        assertEquals(2, votes.size());
        run(1000);
        assertEquals("nothing more while paused", 2, votes.size());
    }

    /** A 24 fps clip after a 30 fps one on the same view asks again once its own frames add up. */
    @Test
    public void theNextClipAsksForItsOwnRate() {
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        assertEquals(30f, lastVote(reel), 0f);
        clips.put(reel, new long[]{2 * NS_PER_SECOND, Math.round(NS_PER_SECOND / 24.0)});
        run(1500);
        assertEquals(24f, lastVote(reel), 0f);
    }

    /** Turning the switch off, or pausing HushGram, takes the request back on the next frame. */
    @Test
    public void offOrPausedTakesTheRequestBack() {
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        Settings.MATCH_VIDEO_FRAME_RATE.save(false);
        run(FRAME_MS);
        assertTrue(Float.isNaN(lastVote(reel)));
        int after = votes.size();
        run(1000);
        assertEquals("off asks for nothing", after, votes.size());

        Settings.MATCH_VIDEO_FRAME_RATE.save(true);
        run(1000);
        assertEquals(30f, lastVote(reel), 0f);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        run(FRAME_MS);
        assertTrue(Float.isNaN(lastVote(reel)));
    }

    /** A video taken off the screen gives its request back, and one hidden from the start is never followed. */
    @Test
    public void aVideoThatGoesGivesItBackAndAHiddenOneIsLeftAlone() {
        TextureView reel = video();
        TextureView hidden = video();
        hidden.setVisibility(View.INVISIBLE);
        play(reel, 30);
        play(hidden, 30);
        run(1000);
        assertEquals(30f, lastVote(reel), 0f);
        assertEquals(0f, lastVote(hidden), 0f);

        screen.removeView(reel);
        run(FRAME_MS);
        assertTrue(Float.isNaN(lastVote(reel)));
        assertTrue("neither is followed now", watched.videos.isEmpty());
    }
}
