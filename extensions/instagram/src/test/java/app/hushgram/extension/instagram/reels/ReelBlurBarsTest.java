/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The blurred backdrop behind a reel with bars: put only behind a page that has bars, taken off
 * when the switch goes off or HushGram pauses, and its bitmaps recycled when the page goes. The
 * stand-in video has no surface, so the frame comes from a test source; how a real TextureView
 * answers needs a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelBlurBarsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final int VIDEO_HEIGHT = 608;

    private ActivityController<Activity> controller;
    private Activity activity;
    private WindowPager pager;
    private FrameLayout list;
    private FrameLayout page;
    private TextureView video;
    private final ColorDrawable black = new ColorDrawable(Color.BLACK);
    private final List<Bitmap> frames = new ArrayList<>();
    private int asked;

    @Before
    public void open() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.BLUR_REEL_BARS.save(true);
        HookStatus.clear();
        ReelBlurBars.reset();
        ReelBlurBars.source = (view, reuse, width, height) -> {
            asked++;
            Bitmap bitmap = reuse != null && reuse.getWidth() == width && reuse.getHeight() == height
                    ? reuse : Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(200, 80, 40));
            if (!frames.contains(bitmap)) frames.add(bitmap);
            return bitmap;
        };
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        pager = new WindowPager(activity);
        list = new FrameLayout(activity);
        page = pageWith(VIDEO_HEIGHT);
        pager.addView(list, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        list.addView(page, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        activity.setContentView(pager, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layout();
    }

    @After
    public void close() throws Exception {
        ReelBlurBars.reset();
        controller.close();
        Settings.BLUR_REEL_BARS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A pager whose window visibility a test sets, since a stopped activity's window can't be modelled directly. */
    private static final class WindowPager extends FrameLayout {
        int windowVisibility = View.VISIBLE;

        WindowPager(Activity activity) {
            super(activity);
        }

        @Override
        public int getWindowVisibility() {
            return windowVisibility;
        }
    }

    /** What the framework does before it draws a frame: the window's observer asks each pre-draw listener. */
    private void preDraw() throws Exception {
        java.lang.reflect.Method dispatch = android.view.ViewTreeObserver.class.getDeclaredMethod("dispatchOnPreDraw");
        dispatch.setAccessible(true);
        dispatch.invoke(pager.getViewTreeObserver());
    }

    /** What the framework does when the window gains or loses focus. */
    private void windowFocus(boolean hasFocus) throws Exception {
        java.lang.reflect.Method dispatch = android.view.ViewTreeObserver.class.getDeclaredMethod("dispatchOnWindowFocusChange", boolean.class);
        dispatch.setAccessible(true);
        dispatch.invoke(pager.getViewTreeObserver(), hasFocus);
    }

    /** A page that is a full-size container with a black background and a video of [videoHeight] in the middle. */
    private FrameLayout pageWith(int videoHeight) {
        FrameLayout container = new FrameLayout(activity);
        container.setBackground(black);
        video = new TextureView(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(WIDTH, videoHeight);
        params.topMargin = (HEIGHT - videoHeight) / 2;
        container.addView(video, params);
        return container;
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, WIDTH, HEIGHT);
    }

    private static void advance(long millis) {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS);
    }

    private String report() {
        return HookStatus.report().toString();
    }

    private boolean backdropShows() {
        return page.getBackground() instanceof ReelBlurBars.BackdropDrawable;
    }


    @Test
    public void aReelWithBarsGetsABlurredBackdropBehindIt() {
        ReelBlurBars.pager(pager);
        advance(100);
        assertFalse("nothing before the scroll has settled", backdropShows());

        advance(300);

        assertTrue(backdropShows());
        assertEquals(1, ReelBlurBars.backdropCount());
        assertNotNull(((ReelBlurBars.BackdropDrawable) page.getBackground()).bitmap());
        assertTrue(report(), report().contains(ReelBlurBars.DRAWN));
        assertTrue(HookStatus.missing(FamilyNames.REEL_BLUR_BARS).toString(), HookStatus.missing(FamilyNames.REEL_BLUR_BARS).isEmpty());
    }

    @Test
    public void theCopyIsSmallAndStaysOneBitmapAsItRefreshes() {
        ReelBlurBars.pager(pager);
        advance(400);
        int first = asked;
        assertTrue(first >= 1);
        assertEquals(WIDTH / ReelBlurBars.SCALE, frames.get(0).getWidth());
        assertEquals(VIDEO_HEIGHT / ReelBlurBars.SCALE, frames.get(0).getHeight());

        advance(3000);

        assertTrue("about once a second, not per frame: " + asked, asked >= first + 2 && asked <= first + 4);
        assertEquals("the same bitmap is reused", 1, frames.size());
    }

    @Test
    public void aFullScreenReelIsLeftAlone() {
        list.removeView(page);
        page = pageWith(HEIGHT);
        list.addView(page, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layout();

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());
        assertEquals(0, asked);
        assertTrue(report(), report().contains(ReelBlurBars.FULL_SCREEN));
    }

    @Test
    public void aVideoScaledSmallerByItsTransformCountsAsHavingBars() {
        list.removeView(page);
        page = pageWith(HEIGHT);
        Matrix half = new Matrix();
        half.setScale(0.5f, 0.5f, WIDTH / 2f, HEIGHT / 2f);
        video.setTransform(half);
        list.addView(page, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layout();

        ReelBlurBars.pager(pager);
        advance(400);

        assertTrue(backdropShows());
    }

    @Test
    public void aPageOffScreenGetsNothing() {
        FrameLayout away = pageWith(VIDEO_HEIGHT);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(WIDTH, HEIGHT);
        params.topMargin = HEIGHT;
        list.addView(away, params);
        layout();

        ReelBlurBars.pager(pager);
        advance(400);

        assertTrue(backdropShows());
        assertSame("the page below the screen keeps its black", black, away.getBackground());
        assertEquals(1, ReelBlurBars.backdropCount());
    }

    @Test
    public void turningTheSwitchOffGivesBackTheBlackAndRecyclesTheCopy() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(backdropShows());
        Bitmap copy = frames.get(0);

        Settings.BLUR_REEL_BARS.save(false);
        advance(1100);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());
        assertTrue(copy.isRecycled());
    }

    @Test
    public void pausingGivesBackTheBlack() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(backdropShows());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        advance(1100);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test
    public void aPageThatDetachesRecyclesItsCopyAndGetsItsBackgroundBack() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(backdropShows());
        Bitmap copy = frames.get(0);

        list.removeView(page);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());
        assertTrue(copy.isRecycled());
    }

    @Test
    public void aPageThatBecomesFullScreenGivesTheBackdropUp() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(backdropShows());

        ViewGroup.LayoutParams params = video.getLayoutParams();
        params.height = HEIGHT;
        ((FrameLayout.LayoutParams) params).topMargin = 0;
        video.setLayoutParams(params);
        layout();
        advance(1100);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());
    }

    @Test
    public void noFrameYetLeavesTheBlack() {
        ReelBlurBars.source = (view, reuse, width, height) -> null;

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        assertTrue(report(), report().contains(ReelBlurBars.NO_FRAME));
    }

    @Test
    public void aFrameOfNothingButBlackLeavesTheBlack() {
        ReelBlurBars.source = (view, reuse, width, height) -> {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
            return bitmap;
        };

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        assertTrue(report(), report().contains(ReelBlurBars.NO_FRAME));
    }

    @Test
    public void withTheSwitchOffTheHandOverWatchesNothing() {
        Settings.BLUR_REEL_BARS.save(false);

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        assertEquals(0, asked);
    }

    @Test
    public void somethingThatIsNotAPagerIsIgnored() {
        ReelBlurBars.pager(null);
        ReelBlurBars.pager(new Object());
        advance(1500);

        assertEquals(0, asked);
        assertTrue(HookStatus.missing(FamilyNames.REEL_BLUR_BARS).toString(), HookStatus.missing(FamilyNames.REEL_BLUR_BARS).isEmpty());
    }

    @Test
    public void aFailureLeavesTheBlackAndIsReported() {
        ReelBlurBars.source = (view, reuse, width, height) -> {
            throw new IllegalStateException("surface went away");
        };

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        String missing = HookStatus.missing(FamilyNames.REEL_BLUR_BARS).toString();
        assertTrue(missing, missing.contains("'" + ReelBlurBars.SCAN + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void aHandOverThatThrowsIsReported() {
        ReelBlurBars.pager(pager, () -> {
            throw new IllegalStateException("settings went away");
        });

        String missing = HookStatus.missing(FamilyNames.REEL_BLUR_BARS).toString();
        assertTrue(missing, missing.contains("'" + ReelBlurBars.HAND_OVER + "'"));
        assertEquals(0, ReelBlurBars.backdropCount());
    }

    @Test
    public void blackEdgesAreTrimmedButNeverMoreThanThreeQuartersOfASide() {
        int[] pixels = new int[10 * 8];
        java.util.Arrays.fill(pixels, Color.BLACK);
        for (int y = 2; y < 6; y++) for (int x = 1; x < 9; x++) pixels[y * 10 + x] = Color.rgb(90, 90, 90);

        Rect kept = ReelBlurBars.trim(pixels, 10, 8);

        assertEquals(new Rect(1, 2, 9, 6), kept);
        java.util.Arrays.fill(pixels, Color.BLACK);
        assertNull(ReelBlurBars.trim(pixels, 10, 8));
        java.util.Arrays.fill(pixels, Color.rgb(90, 90, 90));
        assertEquals(new Rect(0, 0, 10, 8), ReelBlurBars.trim(pixels, 10, 8));
    }

    @Test
    public void theBlurSoftensAHardEdgeAndKeepsItOpaque() {
        int[] edge = new int[8 * 4];
        for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) edge[y * 8 + x] = x < 4 ? Color.WHITE : Color.rgb(20, 20, 20);

        int[] blurred = ReelBlurBars.boxBlur(edge, 8, 4, ReelBlurBars.BLUR_RADIUS, true);

        int left = Color.red(blurred[3]);
        int right = Color.red(blurred[4]);
        assertTrue("the edge is no longer hard: " + left + " " + right, left < 255 && right > 20);
        assertTrue(left > right);
        assertEquals(0xFF, Color.alpha(blurred[0]));
        assertEquals("a flat area stays flat", 255, Color.red(blurred[0]));
    }

    @Test
    public void aDetachedPagerLeavesNothingOnTheWindow() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertEquals(1, ReelBlurBars.listenerCount());

        ((ViewGroup) pager.getParent()).removeView(pager);

        assertEquals(0, ReelBlurBars.listenerCount());
        assertEquals(0, ReelBlurBars.backdropCount());
    }

    @Test
    public void aPagerThatIsNotInFrontStopsTheLoopAndAScrollWakesIt() {
        ReelBlurBars.pager(pager);
        advance(400);
        asked = 0;
        advance(0);

        pager.setVisibility(View.GONE);
        advance(1100);
        int whileHidden = ReelBlurBars.rounds;
        advance(5000);
        assertEquals("the loop does not run while hidden", whileHidden, ReelBlurBars.rounds);
        assertEquals(0, asked);

        pager.setVisibility(View.VISIBLE);
        layout();
        advance(400);
        advance(1100);
        assertTrue("it runs again once it is back in front", ReelBlurBars.rounds > whileHidden);
        assertTrue(asked > 0);
    }

    @Test
    public void aPagerIsInFrontOnlyInAVisibleWindow() {
        assertTrue(ReelBlurBars.inFront(true, true, View.VISIBLE));
        assertFalse("detached", ReelBlurBars.inFront(false, true, View.VISIBLE));
        assertFalse("hidden by a parent", ReelBlurBars.inFront(true, false, View.VISIBLE));
        assertFalse("window invisible", ReelBlurBars.inFront(true, true, View.INVISIBLE));
        assertFalse("window gone, as in a stopped activity", ReelBlurBars.inFront(true, true, View.GONE));
    }

    /** The activity stops: the pager is still shown to its parents, but its window is gone and the 1 s scan must stop. */
    @Test
    public void aStoppedWindowStopsTheLoopAndComingBackWakesIt() throws Exception {
        ReelBlurBars.pager(pager);
        advance(400);
        advance(1100);
        asked = 0;

        pager.windowVisibility = View.GONE;
        assertTrue("the parents still say shown", pager.isShown());
        advance(1100);
        int whileStopped = ReelBlurBars.rounds;
        advance(10_000);
        assertEquals("the loop does not run in the background", whileStopped, ReelBlurBars.rounds);
        assertEquals(0, asked);

        pager.windowVisibility = View.VISIBLE;
        windowFocus(true);
        advance(400);
        advance(1100);
        assertTrue("it runs again once the window is back", ReelBlurBars.rounds > whileStopped);
        assertTrue(asked > 0);
    }

    /** INVISIBLE to VISIBLE invalidates but lays nothing out, so the next draw is what wakes the loop. */
    @Test
    public void aPagerThatTurnsVisibleAgainWakesWithoutALayout() throws Exception {
        ReelBlurBars.pager(pager);
        advance(400);
        advance(1100);

        pager.setVisibility(View.INVISIBLE);
        advance(1100);
        int whileInvisible = ReelBlurBars.rounds;
        advance(5000);
        assertEquals("the loop stopped", whileInvisible, ReelBlurBars.rounds);

        pager.setVisibility(View.VISIBLE);
        preDraw();
        advance(400);
        advance(1100);
        assertTrue("the next draw woke it", ReelBlurBars.rounds > whileInvisible);
    }

    /** Draws while the pager is still out of front change nothing, and a frame at a time doesn't push the first look back. */
    @Test
    public void drawsWhileNotInFrontLeaveItAsleepAndDrawsAfterwardsDoNotDelayIt() throws Exception {
        ReelBlurBars.pager(pager);
        advance(400);
        advance(1100);
        pager.windowVisibility = View.GONE;
        advance(1100);
        int asleepAt = ReelBlurBars.rounds;

        preDraw();
        advance(1500);
        assertEquals("a draw in a stopped window wakes nothing", asleepAt, ReelBlurBars.rounds);

        pager.windowVisibility = View.VISIBLE;
        for (int frame = 0; frame < 40; frame++) {
            preDraw();
            advance(16);
        }
        assertTrue("one look happened while frames kept coming", ReelBlurBars.rounds > asleepAt);
    }

    /** An awake loop has no use for the window's pre-draw callbacks, so a pager in front adds none to every frame. */
    @Test
    public void thePreDrawListenerIsOnlyOnWhileTheLoopIsAsleep() throws Exception {
        ReelBlurBars.pager(pager);
        advance(400);
        advance(1100);
        assertEquals("awake: none", 0, ReelBlurBars.drawListenerCount());

        pager.setVisibility(View.INVISIBLE);
        advance(1100);
        assertEquals("asleep: one", 1, ReelBlurBars.drawListenerCount());

        pager.setVisibility(View.VISIBLE);
        preDraw();
        assertEquals("woken: gone again", 0, ReelBlurBars.drawListenerCount());
        advance(400);
        advance(1100);
        assertEquals(0, ReelBlurBars.drawListenerCount());

        pager.setVisibility(View.INVISIBLE);
        advance(1100);
        assertEquals(1, ReelBlurBars.drawListenerCount());
        ((ViewGroup) pager.getParent()).removeView(pager);
        assertEquals("detached: nothing left on the window", 0, ReelBlurBars.drawListenerCount());
    }
}
