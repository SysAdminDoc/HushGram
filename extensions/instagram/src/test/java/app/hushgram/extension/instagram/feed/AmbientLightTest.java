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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.Looper;
import android.view.Gravity;
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

    /** Instagram's rounded frame: its background setter wraps what it's given in a drawable of its own (#485). */
    static final class WrappingList extends LinearLayout {
        int given;

        WrappingList(Context context) {
            super(context);
        }

        @Override
        public void setBackground(Drawable background) {
            given++;
            super.setBackground(background == null ? null : new DrawableWrapper(background) {});
        }
    }

    @Test
    public void aStageThatWrapsItsBackgroundKeepsOneGlowFrameAfterFrame() {
        FrameLayout roundedRoot = new FrameLayout(activity);
        WrappingList rounded = new WrappingList(activity);
        rounded.setOrientation(LinearLayout.VERTICAL);
        rounded.setBackground(black);
        Drawable stock = rounded.getBackground();
        rounded.addView(new View(activity), new LinearLayout.LayoutParams(WIDTH, HEADER));
        FrameLayout holder = new FrameLayout(activity);
        MediaFrameLayout picture = new MediaFrameLayout(activity);
        holder.addView(picture, new FrameLayout.LayoutParams(WIDTH, MEDIA));
        rounded.addView(holder, new LinearLayout.LayoutParams(WIDTH, MEDIA));
        rounded.addView(new View(activity), new LinearLayout.LayoutParams(WIDTH, FOOTER));
        roundedRoot.addView(rounded, new FrameLayout.LayoutParams(WIDTH, HEADER + MEDIA + FOOTER));
        activity.setContentView(roundedRoot);
        root = roundedRoot;
        shadowOf(Looper.getMainLooper()).idle();

        on();
        assertEquals(1, AmbientLight.litForTests());
        Drawable worn = rounded.getBackground();
        int given = rounded.given;
        for (int i = 0; i < 30; i++) frame();

        assertSame("the stage keeps the glow it was given", worn, rounded.getBackground());
        assertEquals("and is never handed another", given, rounded.given);
        assertTrue(String.valueOf(worn), worn instanceof DrawableWrapper);
        Drawable glow = ((DrawableWrapper) worn).getDrawable();
        assertTrue(String.valueOf(glow), glow instanceof AmbientLight.Glow);
        assertSame("one glow over the stage's own background", stock, ((AmbientLight.Glow) glow).original);
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        int above = drawn(rounded).getPixel(WIDTH / 2, HEADER - 4);
        assertTrue("it glows through the wrapper: " + hex(above), Color.blue(above) > 80 && Color.red(above) < 20);

        Settings.AMBIENT_LIGHT.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();

        Drawable back = rounded.getBackground();
        assertTrue(String.valueOf(back), back instanceof DrawableWrapper);
        assertSame("off, the stage wraps its own background once, as before", black, ((DrawableWrapper) back).getDrawable());
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

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** [view]'s background drawn alone, the size of [view], sized first as a view sizes its background before drawing it. */
    private static Bitmap background(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.getBackground().setBounds(0, 0, view.getWidth(), view.getHeight());
        view.getBackground().draw(new Canvas(bitmap));
        return bitmap;
    }

    /** What a docked glass pill on [bar] paints over the bar's colour, alone. */
    private static Bitmap underPill(View bar) {
        Bitmap bitmap = Bitmap.createBitmap(bar.getWidth(), bar.getHeight(), Bitmap.Config.ARGB_8888);
        AmbientLight.drawUnderPill(new Canvas(bitmap), bar);
        return bitmap;
    }

    /**
     * The Reels tab stops a reel under the status bar and above Instagram's tab bar, and the black round
     * it stayed black (#487). The view round the reel that spans the window glows under the system bars,
     * and the tab bar, which covers that glow, draws the same one over its own colour: in its background,
     * or round a docked glass pill through what the pill paints there. A floating pill has the host's glow
     * showing round it, so it gets none of its own.
     */
    @Test
    public void aReelOnTheReelsTabGlowsUnderTheSystemBarsAndTheTabBar() {
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        root = host;
        // Instagram's Reels tab draws from the window's top edge, with no action bar over it.
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        shadowOf(Looper.getMainLooper()).idle();
        View window = activity.getWindow().getDecorView();
        int width = window.getWidth(), height = window.getHeight();
        assertEquals("the test's host spans the window", height, host.getHeight());
        int statusBar = height / 25, gestureBar = height / 20, barHeight = 50;
        int stage = height - statusBar - barHeight - gestureBar;
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        TextureView video = new TextureView(activity);
        container.addView(video, match());
        frame.addView(container, match());
        FrameLayout.LayoutParams between = match();
        between.topMargin = statusBar;
        between.bottomMargin = barHeight + gestureBar;
        host.addView(frame, between);
        View bar = new View(activity);
        int barId = View.generateViewId();
        bar.setId(barId);
        ColorDrawable barColor = new ColorDrawable(Color.BLACK);
        bar.setBackground(barColor);
        FrameLayout.LayoutParams under = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM);
        under.bottomMargin = gestureBar;
        host.addView(bar, under);
        AmbientLight.barIdForTests(activity, barId);
        shadowOf(Looper.getMainLooper()).idle();

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertTrue("the reel fills its stage", AmbientLight.litRectForTests(video).isEmpty());
        assertEquals(new Rect(0, statusBar, width, statusBar + stage), AmbientLight.hostLitForTests(video));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap bands = background(host);
        int status = bands.getPixel(width / 2, statusBar / 2);
        assertTrue("under the status bar, the reel's top: " + hex(status),
                Color.alpha(status) > 20 && Color.blue(status) > 60 && Color.red(status) < 30);
        assertEquals("nothing over the stage", 0, Color.alpha(bands.getPixel(width / 2, statusBar + stage / 2)));

        int barTop = statusBar + stage;
        assertTrue(String.valueOf(bar.getBackground()), bar.getBackground() instanceof AmbientLight.Glow);
        assertSame("the bar keeps its colour under the glow", barColor, ((AmbientLight.Glow) bar.getBackground()).original);
        int onBar = background(bar).getPixel(width / 2, 4);
        int hostThere = bands.getPixel(width / 2, barTop + 4);
        assertTrue("the bar shows the reel's bottom: " + hex(onBar), Color.red(onBar) > 40 && Color.blue(onBar) < 30);
        assertEquals("over black, as strong as the host's glow it covers: " + hex(onBar) + " over " + hex(hostThere),
                Color.alpha(hostThere), Color.red(onBar), 4);

        AmbientLight.wearsGlass = view -> view == bar;
        frame();
        assertSame("a docked pill's bar keeps its own background", barColor, bar.getBackground());
        int round = underPill(bar).getPixel(width / 2, 4);
        assertTrue("round the pill, the reel's bottom: " + hex(round), Color.alpha(round) > 40 && Color.red(round) > 60);

        AmbientLight.floatingGlass = view -> view == bar;
        frame();
        assertEquals("a floating pill has the host's glow round it", 0, Color.alpha(underPill(bar).getPixel(width / 2, 4)));
        assertSame(barColor, bar.getBackground());

        AmbientLight.wearsGlass = view -> false;
        AmbientLight.floatingGlass = view -> false;
        frame();
        assertTrue("Instagram's own bar again", bar.getBackground() instanceof AmbientLight.Glow);

        Settings.AMBIENT_LIGHT.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull("the host has its own background back", host.getBackground());
        assertSame("and the bar", barColor, bar.getBackground());
        assertEquals(0, AmbientLight.litForTests());
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
