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

import android.app.Activity;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
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

/** When Match the video's frame rate asks for a video's frame rate and a refresh rate it divides, and when it takes them back (#868). */
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
    /** What the screen can run and the mode it's on now, a Galaxy S25 at 1080 x 2340 and 60 Hz unless a test says. */
    private RefreshRate.Mode[] modes = s25();
    private int currentMode = 10;

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
        VideoFrameRate.modes = (decor, current) -> {
            current[0] = currentMode;
            return modes;
        };
        controller = Robolectric.buildActivity(Activity.class).setup();
        screen = new FrameLayout(controller.get());
        controller.get().setContentView(screen);
        watched = new VideoFrameRate.Watched(screen, controller.get().getWindow());
    }

    @After
    public void restore() {
        if (controller != null) controller.close();
        VideoFrameRate.resetForTests();
        Settings.MATCH_VIDEO_FRAME_RATE.resetToDefault();
        Settings.HIGHEST_REFRESH_RATE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /**
     * A Galaxy S25's modes in the order SurfaceFlinger lists them, 1440 x 3120 first, then 1080 x 2340.
     * Android numbers display modes from 1 (0 is no preference), one past SurfaceFlinger's own ids.
     */
    private static RefreshRate.Mode[] s25() {
        return new RefreshRate.Mode[]{
                new RefreshRate.Mode(1, 1440, 3120, 120.00001f),
                new RefreshRate.Mode(3, 1440, 3120, 60.000004f),
                new RefreshRate.Mode(5, 1440, 3120, 30.000002f),
                new RefreshRate.Mode(8, 1080, 2340, 120.00001f),
                new RefreshRate.Mode(9, 1080, 2340, 80f),
                new RefreshRate.Mode(10, 1080, 2340, 60.000004f),
                new RefreshRate.Mode(11, 1080, 2340, 48.000004f),
                new RefreshRate.Mode(12, 1080, 2340, 30.000002f),
                new RefreshRate.Mode(13, 1080, 2340, 24.000002f),
                new RefreshRate.Mode(14, 1080, 2340, 10f),
        };
    }

    private WindowManager.LayoutParams window() {
        return controller.get().getWindow().getAttributes();
    }

    /** Sets the window's own request, the way Instagram or Use the highest refresh rate would. */
    private void windowAsks(int mode, float refresh) {
        WindowManager.LayoutParams params = window();
        params.preferredDisplayModeId = mode;
        params.preferredRefreshRate = refresh;
        controller.get().getWindow().setAttributes(params);
    }

    private void assertWindow(String what, int mode, float refresh) {
        assertEquals(what + ": the mode", mode, window().preferredDisplayModeId);
        assertEquals(what + ": the rate", refresh, window().preferredRefreshRate, 0f);
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

    /** The slowest mode at the current size whose rate is a whole multiple of the video's, 60 Hz or more. */
    @Test
    public void picksTheSlowestMultipleAtTheCurrentSize() {
        RefreshRate.Mode[] s25 = s25();
        assertEquals("30 fps on 1080 x 2340: its 60 Hz, not 1440 x 3120's", 10, VideoFrameRate.modeFor(30f, 10, s25));
        assertEquals("from 120 Hz too", 10, VideoFrameRate.modeFor(30f, 8, s25));
        assertEquals("on 1440 x 3120, that size's 60 Hz", 3, VideoFrameRate.modeFor(30f, 1, s25));
        assertEquals("24 fps passes over 48 Hz for 120", 8, VideoFrameRate.modeFor(24f, 10, s25));
        assertEquals("24 fps on 1440 x 3120", 1, VideoFrameRate.modeFor(24f, 3, s25));
        assertEquals(10, VideoFrameRate.modeFor(60f, 10, s25));
        assertEquals("no mode fits 25 fps", 0, VideoFrameRate.modeFor(25f, 10, s25));
        assertEquals("a mode the display doesn't list", 0, VideoFrameRate.modeFor(30f, 99, s25));
        assertEquals(0, VideoFrameRate.modeFor(30f, 10, null));
        RefreshRate.Mode[] plain = {new RefreshRate.Mode(1, 1080, 2400, 60f), new RefreshRate.Mode(2, 1080, 2400, 120f)};
        assertEquals("a 60 and 120 Hz panel runs a 30 fps reel at 60", 1, VideoFrameRate.modeFor(30f, 2, plain));
        assertEquals(2, VideoFrameRate.modeFor(24f, 1, plain));

        assertEquals(75f, VideoFrameRate.refreshFor(25f), 0f);
        assertEquals(60f, VideoFrameRate.refreshFor(30f), 0f);
        assertEquals(72f, VideoFrameRate.refreshFor(24f), 0f);
        assertEquals(60f, VideoFrameRate.refreshFor(60f), 0f);
        assertEquals(96f, VideoFrameRate.refreshFor(48f), 0f);
    }

    /** A 30 fps reel asks its window for 60 Hz at the size it's on, once, and the pause gives the window back what it had. */
    @Test
    public void aPlayingReelAsksItsWindowAndAPauseGivesItBack() {
        windowAsks(0, 90f);
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        assertWindow("playing", 10, 90f);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(VideoFrameRate.WINDOW_ASKED + " 1"));

        pause(reel);
        run(VideoFrameRate.STOPPED_AFTER_MS + 50);
        assertWindow("paused", 0, 90f);
        run(1000);
        assertWindow("nothing more while paused", 0, 90f);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(VideoFrameRate.WINDOW_ASKED + " 1"));
    }

    /** A 24 fps film asks for 120 Hz, and a 25 fps clip, which no S25 mode fits, asks for 75 Hz by rate. */
    @Test
    public void aFilmAsksForTheModeItDividesAndAnOddRateAsksByRate() {
        TextureView reel = video();
        play(reel, 24);
        run(1500);
        assertWindow("24 fps", 8, 0f);
        clips.put(reel, new long[]{2 * NS_PER_SECOND, Math.round(NS_PER_SECOND / 25.0)});
        run(1500);
        assertWindow("25 fps", 0, 75f);
        pause(reel);
        run(VideoFrameRate.STOPPED_AFTER_MS + 50);
        assertWindow("paused", 0, 0f);
    }

    /** With no modes to read, the window is asked by rate alone. */
    @Test
    public void unreadableModesAskByRate() {
        modes = null;
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        assertWindow("playing", 0, 60f);
    }

    /** Turning the switch off, pausing HushGram, and the activity pausing or closing give the window back what it had. */
    @Test
    public void offPausedOrGoneGivesTheWindowBack() {
        Window window = controller.get().getWindow();
        VideoFrameRate.track(window);
        View decor = window.getDecorView();
        watched = VideoFrameRate.watchedForTests(decor);
        assertNotNull(watched);
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        assertWindow("playing", 10, 0f);

        Settings.MATCH_VIDEO_FRAME_RATE.save(false);
        run(FRAME_MS);
        assertWindow("off", 0, 0f);
        Settings.MATCH_VIDEO_FRAME_RATE.save(true);
        run(1000);
        assertWindow("on again", 10, 0f);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        run(FRAME_MS);
        assertWindow("HushGram paused", 0, 0f);
        PauseForTests.resume();
        run(1000);
        assertWindow("HushGram on again", 10, 0f);

        VideoFrameRate.quiet(decor);
        assertWindow("activity paused", 0, 0f);
        run(1000);
        assertWindow("back on screen", 10, 0f);
        VideoFrameRate.release(decor);
        assertWindow("activity closed", 0, 0f);
        assertNull(VideoFrameRate.watchedForTests(decor));
    }

    /** While Use the highest refresh rate is on the window is left to it: nothing is asked, and a request held goes back. */
    @Test
    public void theHighestRefreshRateComesFirst() {
        Settings.HIGHEST_REFRESH_RATE.save(true);
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        assertWindow("never asked", 0, 0f);
        assertEquals("the view still asks", 30f, lastVote(reel), 0f);

        Settings.HIGHEST_REFRESH_RATE.save(false);
        run(FRAME_MS);
        assertWindow("asked once it's off", 10, 0f);
        Settings.HIGHEST_REFRESH_RATE.save(true);
        run(FRAME_MS);
        assertWindow("given back as it comes on", 0, 0f);

        Settings.HIGHEST_REFRESH_RATE.save(false);
        run(FRAME_MS);
        assertWindow("asked again", 10, 0f);
        // Flipped on in HushGram settings, it asks the window for the fastest mode at once.
        Settings.HIGHEST_REFRESH_RATE.save(true);
        windowAsks(8, 0f);
        run(1000);
        assertWindow("its 120 Hz stays", 8, 0f);
    }

    /** A window something else asked since keeps that request once the video stops. */
    @Test
    public void aWindowAskedSinceKeepsThatRequest() {
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        assertWindow("playing", 10, 0f);
        windowAsks(5, 0f);
        pause(reel);
        run(VideoFrameRate.STOPPED_AFTER_MS + 50);
        assertWindow("the later request", 5, 0f);
    }

    /** A display that can't be read leaves the window as Instagram has it from then on, and Hook status says so. The view still asks. */
    @Test
    public void aFailureLeavesTheWindowAlone() {
        windowAsks(0, 90f);
        VideoFrameRate.modes = (decor, current) -> {
            throw new IllegalStateException("no display");
        };
        TextureView reel = video();
        play(reel, 30);
        run(1000);
        assertWindow("untouched", 0, 90f);
        assertEquals(30f, lastVote(reel), 0f);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("a working 'video refresh rate' hook (it threw java.lang.IllegalStateException)"));

        VideoFrameRate.modes = (decor, current) -> {
            current[0] = currentMode;
            return modes;
        };
        clips.put(reel, new long[]{2 * NS_PER_SECOND, Math.round(NS_PER_SECOND / 24.0)});
        run(1500);
        assertWindow("still untouched", 0, 90f);
    }
}
