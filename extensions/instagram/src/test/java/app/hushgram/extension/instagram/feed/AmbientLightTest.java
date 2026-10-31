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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Ambient light (#416): a glow in a post's own colors around its picture, drawn as its stage's
 * background, and in the bands around a reel that doesn't fill its frame.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AmbientLightTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int WIDTH = 300, HEADER = 40, MEDIA = 200, FOOTER = 60, REEL = 360;
    private static final String FAMILY = "Ambient light";

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    /** The list of posts: the header, the row holding the picture, the buttons. */
    private LinearLayout list;
    private FrameLayout row;
    private MediaFrameLayout media;
    private final ColorDrawable black = new ColorDrawable(Color.BLACK);
    private int frameId;
    /** What the stand-in copies: the picture's top half and its bottom half. */
    private int top = Color.BLUE, bottom = Color.RED;
    private boolean copies = true;
    private int asked;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        AmbientLight.resetForTests();
        frameId = View.generateViewId();
        AmbientLight.idForTests(activity, frameId);
        AmbientLight.samplerForTests((owner, source, inWindow, into, done) -> {
            asked++;
            int[] pixels = new int[into.getWidth() * into.getHeight()];
            for (int i = 0; i < pixels.length; i++) pixels[i] = i < pixels.length / 2 ? top : bottom;
            into.setPixels(pixels, 0, into.getWidth(), 0, 0, into.getWidth(), into.getHeight());
            done.accept(copies);
        });

        root = new FrameLayout(activity);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(black);
        list.setPadding(0, 3, 0, 5);
        list.addView(new View(activity), new LinearLayout.LayoutParams(WIDTH, HEADER));
        // Instagram's row is as big as the picture, so the list is the stage.
        row = new FrameLayout(activity);
        media = new MediaFrameLayout(activity);
        row.addView(media, new FrameLayout.LayoutParams(WIDTH, MEDIA));
        list.addView(row, new LinearLayout.LayoutParams(WIDTH, MEDIA));
        list.addView(new View(activity), new LinearLayout.LayoutParams(WIDTH, FOOTER));
        root.addView(list, new FrameLayout.LayoutParams(WIDTH, 3 + HEADER + MEDIA + FOOTER + 5));
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        reset();
        HookStatus.clear();
    }

    private static void reset() {
        Settings.AMBIENT_LIGHT.resetToDefault();
        Settings.AMBIENT_LIGHT_SPREAD.resetToDefault();
        Settings.AMBIENT_LIGHT_SPEED.resetToDefault();
        Settings.BLUR_REEL_BARS.resetToDefault();
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
        root.getViewTreeObserver().dispatchOnPreDraw();
    }

    private void settle() {
        shadowOf(Looper.getMainLooper()).idle();
        frame();
    }

    /** A layout pass, then a frame, as when the window lays out again. */
    private void relayout() {
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        frame();
    }

    private void advance(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }

    /** Turns the switch on and lets the first copy in. */
    private void on() {
        Settings.AMBIENT_LIGHT.save(true);
        AmbientLight.watch(activity);
        frame();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static Bitmap drawn(View stage) {
        Bitmap bitmap = Bitmap.createBitmap(stage.getWidth(), stage.getHeight(), Bitmap.Config.ARGB_8888);
        stage.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static String hex(int color) {
        return Integer.toHexString(color);
    }

    @Test
    public void offToStartLeavesTheFeedAlone() {
        assertFalse(Settings.AMBIENT_LIGHT.get());

        AmbientLight.watch(activity);
        frame();

        assertFalse(AmbientLight.listeningForTests(activity));
        assertEquals(0, AmbientLight.litForTests());
        assertSame(black, list.getBackground());
        assertEquals(0, asked);
    }

    @Test
    public void aPostGlowsInItsOwnColorsAboveAndBelowItsPicture() {
        on();

        assertTrue(AmbientLight.listeningForTests(activity));
        assertEquals(1, AmbientLight.litForTests());
        Drawable background = list.getBackground();
        assertTrue(String.valueOf(background), background instanceof AmbientLight.Glow);
        assertSame("the list's own background is kept under the glow", black, ((AmbientLight.Glow) background).original);
        assertEquals("the padding stays", 3, list.getPaddingTop());
        assertEquals(5, list.getPaddingBottom());
        assertEquals(new Rect(0, 3 + HEADER, WIDTH, 3 + HEADER + MEDIA), AmbientLight.litRectForTests(media));
        assertEquals(1, asked);
        assertEquals(1, AmbientLight.copiesTakenForTests(media));
        int edge = AmbientLight.faceForTests(media, AmbientLight.SIDE / 2, AmbientLight.RING - 1);
        // A frame drawn a few milliseconds on may have started the fade.
        assertEquals("the first glow is in its own colors: " + hex(edge), Color.BLUE & 0xFFFFFF, edge & 0xFFFFFF);
        assertTrue("and starts all but clear: " + hex(edge), Color.alpha(edge) < AmbientLight.STRENGTH / 8);

        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap drawn = drawn(list);

        int above = drawn.getPixel(WIDTH / 2, 3 + HEADER - 4);
        assertTrue("the picture's top shows above it: " + hex(above), Color.blue(above) > 80 && Color.red(above) < 20);
        int below = drawn.getPixel(WIDTH / 2, 3 + HEADER + MEDIA + 4);
        assertTrue("its bottom below it: " + hex(below), Color.red(below) > 80 && Color.blue(below) < 20);
        int inside = drawn.getPixel(WIDTH / 2, 3 + HEADER + MEDIA / 2);
        assertTrue("nothing is drawn over the picture: " + hex(inside), Color.red(inside) < 10 && Color.blue(inside) < 10);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + AmbientLight.COUNT_LIT + " 1"));
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());
    }

    @Test
    public void theGlowFadesToANewPictureOnlyWhenItChanged() {
        on();
        advance(AmbientSpeed.MEDIUM.sampleMillis);
        assertEquals("asked again", 2, asked);
        assertEquals("the same picture leaves the glow as it was", 1, AmbientLight.copiesTakenForTests(media));

        top = Color.GREEN;
        advance(AmbientSpeed.MEDIUM.sampleMillis);
        assertEquals(2, AmbientLight.copiesTakenForTests(media));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap drawn = drawn(list);
        int above = drawn.getPixel(WIDTH / 2, 3 + HEADER - 4);
        assertTrue("faded to the new top: " + hex(above), Color.green(above) > 80 && Color.blue(above) < 20);
    }

    @Test
    public void slowLooksAtThePictureLessOften() {
        Settings.AMBIENT_LIGHT_SPEED.save(AmbientSpeed.SLOW);
        on();
        top = Color.GREEN;

        advance(AmbientSpeed.MEDIUM.sampleMillis);
        assertEquals("not yet", 1, asked);
        advance(AmbientSpeed.SLOW.sampleMillis - AmbientSpeed.MEDIUM.sampleMillis);
        assertEquals(2, asked);
        assertEquals(2, AmbientLight.copiesTakenForTests(media));
    }

    @Test
    public void aWiderSpreadReachesFurther() {
        on();
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        int y = 3 + HEADER + MEDIA + 40;
        int medium = Color.red(drawn(list).getPixel(WIDTH / 2, y));

        Settings.AMBIENT_LIGHT_SPREAD.save(AmbientSpread.WIDE);
        frame();
        int wide = Color.red(drawn(list).getPixel(WIDTH / 2, y));

        assertTrue(medium + " then " + wide, wide > medium + 15);
    }

    @Test
    public void turningItOffGivesTheListItsOwnBackgroundBack() {
        on();
        assertTrue(list.getBackground() instanceof AmbientLight.Glow);

        Settings.AMBIENT_LIGHT.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();

        assertSame(black, list.getBackground());
        assertEquals(3, list.getPaddingTop());
        assertEquals(0, AmbientLight.litForTests());
        int asked = this.asked;
        advance(AmbientSpeed.FAST.sampleMillis * 10);
        assertEquals("no more copies", asked, this.asked);
    }

    @Test
    public void aPostThatLeavesTheListPutsItsBackgroundBack() {
        on();

        list.removeView(row);
        settle();

        assertEquals(0, AmbientLight.litForTests());
        assertSame(black, list.getBackground());
    }

    @Test
    public void aBackgroundInstagramSetsLaterIsWrappedAfresh() {
        on();
        ColorDrawable white = new ColorDrawable(Color.WHITE);

        list.setBackground(white);
        frame();

        Drawable background = list.getBackground();
        assertTrue(String.valueOf(background), background instanceof AmbientLight.Glow);
        assertSame(white, ((AmbientLight.Glow) background).original);
        assertEquals(1, AmbientLight.litForTests());
    }

    @Test
    public void aCopyThatFailsLeavesNoGlowAndIsCounted() {
        copies = false;
        on();

        assertEquals(0, AmbientLight.copiesTakenForTests(media));
        assertEquals(0, AmbientLight.faceForTests(media, AmbientLight.SIDE / 2, 0));
        int above = drawn(list).getPixel(WIDTH / 2, 3 + HEADER - 4);
        assertTrue(hex(above), Color.blue(above) < 10);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AmbientLight.NO_COPY + " 1"));
    }

    @Test
    public void aPageMostlyOffScreenGetsNoGlow() {
        MediaFrameLayout next = new MediaFrameLayout(activity);
        row.addView(next, new FrameLayout.LayoutParams(WIDTH, MEDIA));
        next.setTranslationX(WIDTH * 0.75f);
        shadowOf(Looper.getMainLooper()).idle();

        on();

        assertEquals(2, AmbientLight.litForTests());
        assertFalse(AmbientLight.litRectForTests(media).isEmpty());
        Rect lit = AmbientLight.litRectForTests(next);
        assertNotNull(lit);
        assertTrue("a quarter shows: " + lit, lit.isEmpty());
    }

    @Test
    public void withoutTheReelFramesIdPostsStillGlowAndTheIdIsReported() {
        AmbientLight.resetForTests();
        AmbientLight.samplerForTests((owner, source, inWindow, into, done) -> done.accept(true));

        on();

        assertEquals(1, AmbientLight.litForTests());
        String missing = HookStatus.missing(FAMILY).toString();
        assertTrue(missing, missing.contains(AmbientLight.REEL_FRAME));
    }

    /** A reel as 453 shows it: the reel's frame, the container spanning it, and the video scaled inside. */
    private TextureView reel(float shown) {
        FrameLayout reelRoot = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        TextureView video = new TextureView(activity);
        container.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(container, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reelRoot.addView(frame, new FrameLayout.LayoutParams(WIDTH, REEL));
        activity.setContentView(reelRoot);
        root = reelRoot;
        shadowOf(Looper.getMainLooper()).idle();
        Matrix scale = new Matrix();
        scale.setScale(1f, shown, WIDTH / 2f, REEL / 2f);
        video.setTransform(scale);
        return video;
    }

    @Test
    public void aReelThatIsntTallEnoughGlowsInTheBandsAroundIt() {
        TextureView video = reel(0.5f);

        on();

        assertEquals(1, AmbientLight.litForTests());
        ViewGroup container = (ViewGroup) video.getParent();
        assertTrue(String.valueOf(container.getBackground()), container.getBackground() instanceof AmbientLight.Glow);
        assertNull("the container had no background of its own", ((AmbientLight.Glow) container.getBackground()).original);
        assertEquals(new Rect(0, REEL / 4, WIDTH, REEL * 3 / 4), AmbientLight.litRectForTests(video));
        assertEquals("copies taken", 1, AmbientLight.copiesTakenForTests(video));

        advance(AmbientSpeed.MEDIUM.fadeMillis);
        // The glow alone, as ReelBlurBarsTest draws its bars: a TextureView drawn without a GPU clears
        // all of its bounds, while a phone draws only the video where its transform puts it.
        Bitmap drawn = Bitmap.createBitmap(WIDTH, REEL, Bitmap.Config.ARGB_8888);
        container.getBackground().draw(new Canvas(drawn));
        int band = drawn.getPixel(WIDTH / 2, REEL / 4 - 4);
        assertTrue("the band above shows the reel's top: " + hex(band), Color.blue(band) > 80 && Color.alpha(band) > 80);
        int under = drawn.getPixel(WIDTH / 2, REEL * 3 / 4 + 4);
        assertTrue("the band below its bottom: " + hex(under), Color.red(under) > 80 && Color.alpha(under) > 80);
        assertEquals("nothing behind the video", 0, Color.alpha(drawn.getPixel(WIDTH / 2, REEL / 2)));

        Settings.AMBIENT_LIGHT.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(container.getBackground());
    }

    @Test
    public void aReelThatFillsItsFrameHasNothingToGlowIn() {
        TextureView video = reel(1f);

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertTrue(AmbientLight.litRectForTests(video).isEmpty());
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap drawn = Bitmap.createBitmap(WIDTH, REEL, Bitmap.Config.ARGB_8888);
        ((View) video.getParent()).getBackground().draw(new Canvas(drawn));
        assertEquals(0, Color.alpha(drawn.getPixel(WIDTH / 2, 2)));
    }

    @Test
    public void reelsAreLeftToBlurTheBarsWhileItsOn() {
        TextureView video = reel(0.5f);
        AmbientLight.blurInBuildForTests = true;
        Settings.BLUR_REEL_BARS.save(true);

        on();
        assertEquals(0, AmbientLight.litForTests());

        Settings.BLUR_REEL_BARS.save(false);
        relayout();
        assertEquals(1, AmbientLight.litForTests());

        Settings.BLUR_REEL_BARS.save(true);
        frame();
        assertEquals("turned on later, the reel's glow goes", 0, AmbientLight.litForTests());
        assertNull(((View) video.getParent()).getBackground());
    }
}
