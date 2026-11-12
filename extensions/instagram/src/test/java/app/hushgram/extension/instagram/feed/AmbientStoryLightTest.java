/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.instagram.ui.widget.framelayout.MediaFrameLayout;

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
import org.robolectric.annotation.GraphicsMode;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Ambient light in stories (#420): a story's frame holds its photo or video, and a picture that doesn't fill
 * the screen gets its bands filled with a glow in its own colors.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AmbientStoryLightTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int WIDTH = 300, HEIGHT = 640;

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    /** Instagram's frame around a story's photo or video, the size of the screen. */
    private FrameLayout frame;
    private ImageView photo;
    private int storyFrameId;
    private int top = Color.BLUE, bottom = Color.RED;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        AmbientLight.resetForTests();
        storyFrameId = View.generateViewId();
        AmbientLight.storyIdForTests(activity, storyFrameId);
        AmbientLight.samplerForTests((owner, source, inWindow, into, done) -> {
            int[] pixels = new int[into.getWidth() * into.getHeight()];
            for (int i = 0; i < pixels.length; i++) pixels[i] = i < pixels.length / 2 ? top : bottom;
            into.setPixels(pixels, 0, into.getWidth(), 0, 0, into.getWidth(), into.getHeight());
            done.accept(true);
        });
        root = new FrameLayout(activity);
        frame = new FrameLayout(activity);
        frame.setId(storyFrameId);
        photo = new ImageView(activity);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(photo, new FrameLayout.LayoutParams(WIDTH, HEIGHT, Gravity.CENTER));
        root.addView(frame, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        activity.setContentView(root);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        reset();
        HookStatus.clear();
    }

    private static void reset() {
        Settings.AMBIENT_LIGHT.resetToDefault();
        Settings.AMBIENT_LIGHT_STORIES.resetToDefault();
        Settings.AMBIENT_LIGHT_SPREAD.resetToDefault();
        Settings.AMBIENT_LIGHT_SPEED.resetToDefault();
    }

    @After
    public void restore() {
        AmbientLight.resetForTests();
        reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private void frame() {
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
    }

    private void advance(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }

    private void laidOut() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void storiesOn() {
        Settings.AMBIENT_LIGHT_STORIES.save(true);
        AmbientLight.watch(activity);
        frame();
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** A picture [width] by [height]. The stand-in sampler decides the colors the glow takes. */
    private Drawable picture(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawColor(Color.GRAY);
        return new BitmapDrawable(activity.getResources(), bitmap);
    }

    private Bitmap glowOnly() {
        Bitmap drawn = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Drawable background = frame.getBackground();
        assertTrue(String.valueOf(background), background instanceof AmbientLight.Glow);
        background.draw(new Canvas(drawn));
        return drawn;
    }

    /** [view]'s glow drawn alone, the size of [view]. */
    private static Bitmap drawn(View view) {
        Drawable background = view.getBackground();
        assertTrue(String.valueOf(background), background instanceof AmbientLight.Glow);
        Bitmap drawn = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        background.draw(new Canvas(drawn));
        return drawn;
    }

    private static String hex(int color) {
        return Integer.toHexString(color);
    }

    @Test
    public void offToStartLeavesAStoryAlone() {
        photo.setImageDrawable(picture(90, 160));
        laidOut();

        AmbientLight.watch(activity);
        frame();

        assertFalse(AmbientLight.listeningForTests(activity));
        assertEquals(0, AmbientLight.litForTests());
        assertNull(frame.getBackground());
    }

    @Test
    public void aPhotoThatIsTooTallForTheScreenGlowsAboveAndBelowIt() {
        photo.setImageDrawable(picture(90, 160));
        laidOut();

        storiesOn();

        assertEquals(1, AmbientLight.litForTests());
        Rect lit = AmbientLight.litRectForTests(photo);
        float scale = Math.min(WIDTH / 90f, HEIGHT / 160f);
        int shown = Math.round(160 * scale);
        assertEquals(0, lit.left);
        assertEquals(WIDTH, lit.right);
        assertEquals((HEIGHT - shown) / 2f, lit.top, 2f);
        assertEquals(HEIGHT - (HEIGHT - shown) / 2f, lit.bottom, 2f);

        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap drawn = glowOnly();
        int above = drawn.getPixel(WIDTH / 2, lit.top - 8);
        assertTrue("the band above shows the picture's top: " + hex(above), Color.blue(above) > 60 && Color.red(above) < 30);
        int below = drawn.getPixel(WIDTH / 2, lit.bottom + 8);
        assertTrue("the band below, its bottom: " + hex(below), Color.red(below) > 60 && Color.blue(below) < 30);
        assertEquals("nothing behind the picture", 0, Color.alpha(drawn.getPixel(WIDTH / 2, HEIGHT / 2)));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AmbientLight.COUNT_STORIES + " 1"));
    }

    @Test
    public void aNarrowPictureGlowsAtTheSidesToo() {
        frame.removeView(photo);
        frame.addView(photo, new FrameLayout.LayoutParams(200, HEIGHT, Gravity.CENTER));
        photo.setImageDrawable(picture(200, HEIGHT));
        laidOut();

        storiesOn();

        assertEquals(new Rect(50, 0, 250, HEIGHT), AmbientLight.litRectForTests(photo));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        int left = glowOnly().getPixel(30, HEIGHT / 4);
        assertTrue("the band at the left shows the picture's top: " + hex(left), Color.alpha(left) > 40 && Color.blue(left) > 60);
    }

    @Test
    public void aPictureThatFillsTheScreenHasNoBands() {
        photo.setImageDrawable(picture(WIDTH, HEIGHT));
        laidOut();

        storiesOn();

        assertEquals(1, AmbientLight.litForTests());
        assertTrue(AmbientLight.litRectForTests(photo).isEmpty());
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        assertEquals(0, Color.alpha(glowOnly().getPixel(WIDTH / 2, 2)));
    }

    /**
     * Instagram's story viewer stops at the status bar and the gesture bar, and the black under them
     * stayed black (#487). The view round it that spans the window lights those bands too, in the
     * picture's edge colors, and never over the stage, which draws its own part.
     */
    @Test
    public void aStoryBetweenTheSystemBarsGlowsUnderThemToo() {
        // Instagram's story viewer draws from the window's top edge, with no action bar over it.
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        laidOut();
        View window = activity.getWindow().getDecorView();
        int width = window.getWidth(), height = window.getHeight();
        int[] rootAt = new int[2];
        root.getLocationInWindow(rootAt);
        assertEquals("the test's root spans the window from " + rootAt[1], height, root.getHeight());
        int statusBar = height / 25, gestureBar = height / 20;
        int stage = height - statusBar - gestureBar;
        root.removeView(frame);
        frame.removeView(photo);
        frame.addView(photo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout main = new FrameLayout(activity);
        main.addView(frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams between = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        between.topMargin = statusBar;
        between.bottomMargin = gestureBar;
        root.addView(main, between);
        photo.setImageDrawable(picture(width, stage));
        laidOut();

        storiesOn();

        assertEquals(1, AmbientLight.litForTests());
        assertTrue("the picture fills its stage, which has no bands of its own", AmbientLight.litRectForTests(photo).isEmpty());
        assertEquals(new Rect(0, statusBar, width, statusBar + stage), AmbientLight.hostLitForTests(photo));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap bands = drawn(root);
        int statusBand = bands.getPixel(width / 2, statusBar / 2);
        assertTrue("under the status bar, the picture's top: " + hex(statusBand),
                Color.alpha(statusBand) > 20 && Color.blue(statusBand) > 60 && Color.red(statusBand) < 30);
        int gestureBand = bands.getPixel(width / 2, height - gestureBar / 2);
        assertTrue("under the gesture bar, its bottom: " + hex(gestureBand),
                Color.alpha(gestureBand) > 20 && Color.red(gestureBand) > 60 && Color.blue(gestureBand) < 30);
        assertEquals("nothing over the stage", 0, Color.alpha(bands.getPixel(width / 2, height / 2)));
        assertEquals("nor just inside its edge", 0, Color.alpha(bands.getPixel(width / 2, statusBar + 2)));

        Settings.AMBIENT_LIGHT_STORIES.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull("the root has its own background back", root.getBackground());
        assertNull(frame.getBackground());
        assertEquals(0, AmbientLight.litForTests());
    }

    @Test
    public void aStoryThatSpansTheWindowLeavesWhatHoldsItAlone() {
        photo.setImageDrawable(picture(90, 160));
        laidOut();

        storiesOn();

        assertEquals(1, AmbientLight.litForTests());
        assertTrue(AmbientLight.hostLitForTests(photo).isEmpty());
        assertNull(root.getBackground());
    }

    @Test
    public void theGlowFollowsTheNextPhotoAndTheNextVideo() {
        photo.setImageDrawable(picture(90, 160));
        laidOut();
        storiesOn();
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        int before = AmbientLight.copiesTakenForTests(photo);

        // The next story's photo, a different color, in the same view.
        top = Color.GREEN;
        photo.setImageDrawable(picture(120, 160));
        laidOut();
        advance(AmbientSpeed.MEDIUM.sampleMillis);
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        assertTrue(AmbientLight.copiesTakenForTests(photo) > before);
        Rect lit = AmbientLight.litRectForTests(photo);
        int above = glowOnly().getPixel(WIDTH / 2, lit.top - 8);
        assertTrue("faded to the new top: " + hex(above), Color.green(above) > 60 && Color.blue(above) < 30);

        // A video story takes the frame over: the photo's light goes out and the video's comes in.
        photo.setVisibility(View.GONE);
        TextureView video = new TextureView(activity);
        frame.addView(video, new FrameLayout.LayoutParams(WIDTH, 400, Gravity.CENTER));
        laidOut();
        frame();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("one light, the video's", 1, AmbientLight.litForTests());
        assertEquals(new Rect(0, 120, WIDTH, 520), AmbientLight.litRectForTests(video));
        assertNull(AmbientLight.litRectForTests(photo));
    }

    @Test
    public void aVideoStoryOnASurfaceViewGlowsFromItsOwnPicture() {
        // #492: Instagram can still play a story's video on a SurfaceView, with its poster photo left
        // in the frame. The video wins, and its copy is asked of the video's own view.
        photo.setImageDrawable(picture(90, 160));
        SurfaceView video = new SurfaceView(activity);
        frame.addView(video, new FrameLayout.LayoutParams(WIDTH, 400, Gravity.CENTER));
        View[] asked = new View[1];
        AmbientLight.samplerForTests((owner, source, inWindow, into, done) -> {
            asked[0] = source;
            int[] pixels = new int[into.getWidth() * into.getHeight()];
            for (int i = 0; i < pixels.length; i++) pixels[i] = i < pixels.length / 2 ? top : bottom;
            into.setPixels(pixels, 0, into.getWidth(), 0, 0, into.getWidth(), into.getHeight());
            done.accept(true);
        });
        laidOut();

        storiesOn();
        advance(AmbientSpeed.MEDIUM.fadeMillis);

        assertEquals("one light, the video's", 1, AmbientLight.litForTests());
        assertEquals(new Rect(0, 120, WIDTH, 520), AmbientLight.litRectForTests(video));
        assertNull(AmbientLight.litRectForTests(photo));
        assertSame(video, asked[0]);
        int above = glowOnly().getPixel(WIDTH / 2, 120 - 8);
        assertTrue("the band above shows the video's top: " + hex(above), Color.blue(above) > 60 && Color.red(above) < 30);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AmbientLight.COUNT_SURFACE_STORIES + " 1"));
    }

    @Test
    public void turningItOffOrPausingTakesTheGlowAway() {
        photo.setImageDrawable(picture(90, 160));
        laidOut();
        storiesOn();
        assertTrue(frame.getBackground() instanceof AmbientLight.Glow);

        Settings.AMBIENT_LIGHT_STORIES.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(frame.getBackground());
        assertEquals(0, AmbientLight.litForTests());

        storiesOn();
        assertTrue(frame.getBackground() instanceof AmbientLight.Glow);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertNull("paused, Instagram's own screen", frame.getBackground());
        assertEquals(0, AmbientLight.litForTests());
    }

    @Test
    public void thePostSwitchAloneLeavesStoriesDarkAndTheStorySwitchAloneLeavesPostsDark() {
        photo.setImageDrawable(picture(90, 160));
        laidOut();
        Settings.AMBIENT_LIGHT.save(true);
        AmbientLight.watch(activity);
        frame();
        assertEquals("posts and reels only", 0, AmbientLight.litForTests());
        assertNull(frame.getBackground());

        Settings.AMBIENT_LIGHT.save(false);
        AmbientLight.resetForTests();
        AmbientLight.storyIdForTests(activity, storyFrameId);
        FrameLayout row = new FrameLayout(activity);
        MediaFrameLayout post = new MediaFrameLayout(activity);
        row.addView(post, new FrameLayout.LayoutParams(WIDTH, 200));
        root.addView(row, new FrameLayout.LayoutParams(WIDTH, 300));
        laidOut();
        storiesOn();
        assertFalse(AmbientLight.litRectForTests(post) != null);
    }
}
