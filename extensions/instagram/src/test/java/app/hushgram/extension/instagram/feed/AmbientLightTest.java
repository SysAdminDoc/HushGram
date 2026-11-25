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
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import org.robolectric.util.ReflectionHelpers;

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
        Settings.AMBIENT_LIGHT_BLEND.resetToDefault();
        Settings.ROUNDED_POST_CORNERS.resetToDefault();
        Settings.ROUNDED_REEL_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
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

    /** Reading a picture back is what costs, and a list on the move would only be read again (#510). */
    @Test
    public void aPictureOnTheMoveWaitsForItToStopBeforeItsCopied() {
        on();
        int first = asked;

        list.offsetTopAndBottom(-20);
        advance(AmbientSpeed.MEDIUM.sampleMillis);
        assertEquals("not while it moves", first, asked);

        advance(AmbientSpeed.MEDIUM.sampleMillis);
        assertEquals("once it stopped", first + 1, asked);
    }

    /** A photo doesn't change on its own, so it stops being read back at full speed (#510). */
    @Test
    public void aStillPhotoRestsBetweenCopiesUntilItChanges() {
        long sample = AmbientSpeed.MEDIUM.sampleMillis;
        on();
        for (int i = 1; i <= AmbientLight.STEADY; i++) {
            advance(sample);
            assertEquals("at the usual speed at first", 1 + i, asked);
        }
        int rested = asked;

        advance(sample);
        assertEquals("the first rest is twice as long", rested, asked);
        advance(sample);
        assertEquals(rested + 1, asked);
        advance(sample * 3);
        assertEquals("the next is twice that", rested + 1, asked);
        advance(sample);
        assertEquals(rested + 2, asked);

        top = Color.GREEN;
        for (long waited = 0; waited < AmbientLight.LONGEST_REST && AmbientLight.copiesTakenForTests(media) == 1; waited += sample) {
            advance(sample);
        }
        assertEquals("a change is still seen, at worst after the longest rest", 2, AmbientLight.copiesTakenForTests(media));
        int changed = asked;
        advance(sample);
        assertEquals("and the speed is back", changed + 1, asked);
    }

    @Test
    public void aVideoThatLooksTheSameKeepsItsSpeed() {
        media.addView(new TextureView(activity), match());
        shadowOf(Looper.getMainLooper()).idle();
        on();
        int first = asked;

        for (int i = 1; i <= AmbientLight.STEADY + 3; i++) {
            advance(AmbientSpeed.MEDIUM.sampleMillis);
            assertEquals("no rest for a video", first + i, asked);
        }
    }

    /** With Instagram in the background nothing was copied, but the tick still woke the app every few hundred ms (#341). */
    @Test
    public void theTickRestsWhileTheActivityIsStoppedAndComesBackWithIt() {
        View decor = activity.getWindow().getDecorView();
        // As Android leaves a stopped activity: its window's decor turned invisible.
        assertTickRestsWhileHidden(() -> decor.setVisibility(View.INVISIBLE), () -> decor.setVisibility(View.VISIBLE));
    }

    /** A window the system hid while every view in it still says it shows. */
    @Test
    public void theTickRestsWhileTheWindowIsHiddenAndComesBackWithIt() {
        Object window = ReflectionHelpers.callInstanceMethod(activity.getWindow().getDecorView(), "getViewRootImpl");
        assertTickRestsWhileHidden(() -> appVisibility(window, false), () -> appVisibility(window, true));
    }

    /** What the system tells a window as it hides or shows it. SDK 37's call takes a second, int argument. */
    static void appVisibility(Object window, boolean visible) {
        for (java.lang.reflect.Method method : window.getClass().getMethods()) {
            if (method.getName().equals("dispatchAppVisibility") && method.getParameterCount() == 2) {
                ReflectionHelpers.callInstanceMethod(window, "dispatchAppVisibility",
                        ReflectionHelpers.ClassParameter.from(boolean.class, visible), ReflectionHelpers.ClassParameter.from(int.class, 0));
                return;
            }
        }
        ReflectionHelpers.callInstanceMethod(window, "dispatchAppVisibility", ReflectionHelpers.ClassParameter.from(boolean.class, visible));
    }

    private void assertTickRestsWhileHidden(Runnable hide, Runnable show) {
        long sample = AmbientSpeed.MEDIUM.sampleMillis;
        media.addView(new TextureView(activity), match());
        shadowOf(Looper.getMainLooper()).idle();
        on();
        advance(sample);
        hide.run();
        advance(sample);
        assertTrue("the window is hidden", !media.isShown() || media.getWindowVisibility() != View.VISIBLE);
        int ticks = AmbientLight.ticksForTests(), copies = asked;

        advance(sample * 20);
        assertEquals("nothing copied", copies, asked);
        assertEquals("and nothing woke the app", ticks, AmbientLight.ticksForTests());
        assertEquals("the light stays for when it shows again", 1, AmbientLight.litForTests());

        show.run();
        settle();
        advance(sample * 3);
        assertTrue("copies again once it shows: " + copies + " then " + asked, asked >= copies + 2);
    }

    /** Photos and stickers in a chat aren't posts, and they glowed like posts (#510). */
    @Test
    public void aChatsPhotosAndStickersDontGlow() {
        int chat = View.generateViewId();
        list.setId(chat);
        AmbientLight.chatIdForTests(activity, chat);

        on();

        assertEquals(0, AmbientLight.litForTests());
        assertSame(black, list.getBackground());
        assertEquals(0, asked);
    }

    /** The picture's edge against the glow read as a hard line, and Blend post edges softens it (#510). */
    @Test
    public void blendPostEdgesFadesThePicturesEdgesIntoItsGlow() {
        on();
        frame();
        assertNull("off to start", AmbientLight.edgesForTests(media));

        Settings.AMBIENT_LIGHT_BLEND.save(true);
        frame();

        Drawable edges = AmbientLight.edgesForTests(media);
        assertNotNull(edges);
        // A software draw of a group that draws nothing itself skips its overlay, so this draws it alone.
        Bitmap drawn = Bitmap.createBitmap(WIDTH, MEDIA, Bitmap.Config.ARGB_8888);
        edges.draw(new Canvas(drawn));
        int strength = 255 - AmbientLight.STRENGTH;
        int atTop = drawn.getPixel(WIDTH / 2, 0);
        assertEquals("at the edge, as strong as the glow lets through: " + hex(atTop), strength, Color.alpha(atTop), 8);
        assertEquals("in the page's color: " + hex(atTop), 0, Color.red(atTop));
        int atBottom = drawn.getPixel(WIDTH / 2, MEDIA - 1);
        assertEquals("and the bottom: " + hex(atBottom), strength, Color.alpha(atBottom), 8);
        assertEquals("gone by the middle", 0, Color.alpha(drawn.getPixel(WIDTH / 2, MEDIA / 2)));
        assertEquals("a picture as wide as the list has no glow beside it, so no strip there",
                0, Color.alpha(drawn.getPixel(0, MEDIA / 2)));

        Settings.AMBIENT_LIGHT_BLEND.save(false);
        frame();
        assertNull(AmbientLight.edgesForTests(media));
    }

    @Test
    public void aRoundedPicturesCornersShowTheGlowNotThePage() {
        on();
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        // The stand-in draws nothing, so whatever would show through its corners reads here.
        int topEdge = 3 + HEADER + 1, bottomEdge = 3 + HEADER + MEDIA - 2, deeper = 3 + HEADER + 12;
        Bitmap square = drawn(list);
        assertEquals("a square picture has nothing under it", Color.BLACK, square.getPixel(WIDTH / 2, topEdge));

        Settings.ROUNDED_CORNER_SIZE.save(CornerSize.SMALL);
        Settings.ROUNDED_POST_CORNERS.save(true);
        frame();
        Bitmap small = drawn(list);
        assertTrue("the picture's top color: " + hex(small.getPixel(WIDTH / 2, topEdge)),
                Color.blue(small.getPixel(WIDTH / 2, topEdge)) > 60 && Color.red(small.getPixel(WIDTH / 2, topEdge)) == 0);
        assertTrue("and its bottom color: " + hex(small.getPixel(WIDTH / 2, bottomEdge)),
                Color.red(small.getPixel(WIDTH / 2, bottomEdge)) > 60 && Color.blue(small.getPixel(WIDTH / 2, bottomEdge)) == 0);
        assertEquals("no deeper than the corner", Color.BLACK, small.getPixel(WIDTH / 2, deeper));
        assertEquals("nor across the middle", Color.BLACK, small.getPixel(WIDTH / 2, 3 + HEADER + MEDIA / 2));

        Settings.ROUNDED_CORNER_SIZE.save(CornerSize.LARGE);
        frame();
        assertTrue("a bigger corner reaches further", Color.blue(drawn(list).getPixel(WIDTH / 2, deeper)) > 60);

        Settings.ROUNDED_POST_CORNERS.save(false);
        frame();
        assertEquals("off puts it back", Color.BLACK, drawn(list).getPixel(WIDTH / 2, topEdge));
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
    public void aFrameOnePixelTallerThanThePictureIsNotItsStage() {
        // On 454 the zoom container often rounds up a pixel over the picture, and the glow had no room (#580).
        row.getLayoutParams().height = MEDIA + 1;
        list.getLayoutParams().height = 3 + HEADER + MEDIA + 1 + FOOTER + 5;
        row.requestLayout();
        list.requestLayout();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(MEDIA + 1, row.getHeight());

        on();

        assertEquals("the glow goes on the list, around the header and the buttons",
                new Rect(0, 3 + HEADER, WIDTH, 3 + HEADER + MEDIA), AmbientLight.litRectForTests(media));
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
    public void aPageThatPeekShrinksGlowsAroundWhereItShows() {
        // Peek at the next carousel page scales and shifts the page, and its row stays as big (#585).
        row.setPivotX(WIDTH / 2f);
        row.setPivotY(MEDIA / 2f);
        row.setScaleX(0.8f);
        row.setScaleY(0.8f);
        row.setTranslationX(-6);
        shadowOf(Looper.getMainLooper()).idle();

        on();

        assertEquals("lit where the page shows, not across the whole row",
                new Rect(24, 3 + HEADER + 20, 264, 3 + HEADER + 180), AmbientLight.litRectForTests(media));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap drawn = drawn(list);
        int above = drawn.getPixel(WIDTH / 2, 3 + HEADER + 16);
        assertTrue("the row above the page glows: " + hex(above), Color.blue(above) > 80 && Color.red(above) < 20);
        int inside = drawn.getPixel(WIDTH / 2, 3 + HEADER + MEDIA / 2);
        assertTrue("nothing is drawn over the page: " + hex(inside), Color.red(inside) < 10 && Color.blue(inside) < 10);
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

    /**
     * A reel page spanning the window, as a reel opened from a profile shows under its title bar, has
     * no host past its stage. It fills the bands round the video like one opened from a link, where
     * the spread alone left a thin glow round it (#689).
     */
    @Test
    public void aReelWithNoHostFillsItsBandsWhateverTheSpread() {
        Settings.AMBIENT_LIGHT_SPREAD.save(AmbientSpread.NARROW);
        FrameLayout page = new FrameLayout(activity);
        activity.setContentView(page);
        root = page;
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        TextureView video = new TextureView(activity);
        container.addView(video, match());
        frame.addView(container, match());
        page.addView(frame, match());
        shadowOf(Looper.getMainLooper()).idle();
        int width = page.getWidth(), height = page.getHeight();
        assertEquals("the page spans the window", activity.getWindow().getDecorView().getHeight(), height);
        Matrix scale = new Matrix();
        scale.setScale(1f, 0.5f, width / 2f, height / 2f);
        video.setTransform(scale);

        on();
        advance(AmbientSpeed.MEDIUM.fadeMillis);

        assertEquals(1, AmbientLight.litForTests());
        assertTrue("no host round this reel", AmbientLight.hostLitForTests(video).isEmpty());
        assertEquals(new Rect(0, Math.round(height / 4f), width, Math.round(height * 3 / 4f)), AmbientLight.litRectForTests(video));
        Bitmap drawn = background(page);
        // Half again Narrow's reach above the video, past where its glow ended.
        int past = AmbientSpread.NARROW.dp * 3 / 2;
        assertTrue("the bands are deeper than that", height / 4 > past + 4);
        int far = drawn.getPixel(width / 2, height / 4 - past);
        assertTrue("the band glows far from the video: " + hex(far), Color.alpha(far) > 20 && Color.blue(far) > 20);
        int below = drawn.getPixel(width / 2, height * 3 / 4 + past);
        assertTrue("and below it: " + hex(below), Color.alpha(below) > 20 && Color.red(below) > 20);
    }

    /**
     * A reel opened from a link fills the window, in a page the size of its frame. Rounded corners on
     * Reels clips the frame to the video, so a glow on the frame or inside it never showed (#580).
     */
    @Test
    public void aReelGlowsOutsideTheFrameRoundedCornersClip() {
        FrameLayout reelRoot = new FrameLayout(activity);
        FrameLayout page = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        frame.setClipToOutline(true);
        FrameLayout container = new FrameLayout(activity);
        TextureView video = new TextureView(activity);
        container.addView(video, match());
        frame.addView(container, match());
        page.addView(frame, match());
        reelRoot.addView(page, new FrameLayout.LayoutParams(WIDTH, REEL));
        activity.setContentView(reelRoot);
        root = reelRoot;
        shadowOf(Looper.getMainLooper()).idle();
        Matrix scale = new Matrix();
        scale.setScale(1f, 0.5f, WIDTH / 2f, REEL / 2f);
        video.setTransform(scale);

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertTrue(String.valueOf(page.getBackground()), page.getBackground() instanceof AmbientLight.Glow);
        assertNull(frame.getBackground());
        assertNull(container.getBackground());
        assertEquals(new Rect(0, REEL / 4, WIDTH, REEL * 3 / 4), AmbientLight.litRectForTests(video));

        advance(AmbientSpeed.MEDIUM.fadeMillis);
        int band = background(page).getPixel(WIDTH / 2, REEL / 4 - 4);
        assertTrue("the band above shows the reel's top: " + hex(band), Color.alpha(band) > 80);
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

    /**
     * A photo reel: the reel's frame holding the profile picture over the reel and the photo, whose
     * picture is half the frame's height, letterboxed in its middle.
     */
    private ImageView photoReel() {
        FrameLayout reelRoot = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        ImageView avatar = new ImageView(activity);
        avatar.setImageBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888));
        container.addView(avatar, new FrameLayout.LayoutParams(32, 32));
        ImageView photo = new ImageView(activity);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setImageBitmap(Bitmap.createBitmap(WIDTH, REEL / 2, Bitmap.Config.ARGB_8888));
        container.addView(photo, match());
        frame.addView(container, match());
        reelRoot.addView(frame, new FrameLayout.LayoutParams(WIDTH, REEL));
        activity.setContentView(reelRoot);
        root = reelRoot;
        shadowOf(Looper.getMainLooper()).idle();
        return photo;
    }

    /** A cover in a video's frame with no size of its own hasn't loaded, so it isn't taken for a photo reel. */
    @Test
    public void aVideoFramesCoverWithNoSizeStaysDark() {
        ImageView cover = photoReel();
        cover.setImageDrawable(new ColorDrawable(Color.GRAY));
        shadowOf(Looper.getMainLooper()).idle();

        on();

        assertEquals(0, AmbientLight.litForTests());
    }

    /** A photo reel has no video to light, and it stayed dark (#689). Its picture glows in the bands round it. */
    @Test
    public void aPhotoReelGlowsInTheBandsAroundItsPicture() {
        ImageView photo = photoReel();

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertEquals(new Rect(0, REEL / 4, WIDTH, REEL * 3 / 4), AmbientLight.litRectForTests(photo));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        ViewGroup container = (ViewGroup) photo.getParent();
        assertTrue(String.valueOf(container.getBackground()), container.getBackground() instanceof AmbientLight.Glow);
        Bitmap drawn = Bitmap.createBitmap(WIDTH, REEL, Bitmap.Config.ARGB_8888);
        container.getBackground().draw(new Canvas(drawn));
        int band = drawn.getPixel(WIDTH / 2, REEL / 4 - 4);
        assertTrue("the band above shows the photo's top: " + hex(band), Color.blue(band) > 80 && Color.alpha(band) > 80);
        int under = drawn.getPixel(WIDTH / 2, REEL * 3 / 4 + 4);
        assertTrue("the band below its bottom: " + hex(under), Color.red(under) > 80 && Color.alpha(under) > 80);
        assertEquals("nothing behind the photo", 0, Color.alpha(drawn.getPixel(WIDTH / 2, REEL / 2)));
    }

    /**
     * A photo reel as 454 lays it out: the reel's view, then the photo's own view centred in it, the
     * frame the photo's size inside that, holding the photo cropped to fill it and an empty video
     * container. [photoHeight] is the photo's height, [layers] the views of the reel's size round it.
     */
    private ImageView photoReel454(int photoHeight, int layers) {
        FrameLayout reelRoot = new FrameLayout(activity);
        FrameLayout outer = reelRoot;
        for (int i = 0; i < layers; i++) {
            FrameLayout layer = new FrameLayout(activity);
            outer.addView(layer, i == 0 ? new FrameLayout.LayoutParams(WIDTH, REEL) : match());
            outer = layer;
        }
        FrameLayout content = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        ImageView photo = new ImageView(activity);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setImageBitmap(Bitmap.createBitmap(WIDTH, photoHeight, Bitmap.Config.ARGB_8888));
        frame.addView(photo, match());
        frame.addView(new FrameLayout(activity), match());
        content.addView(frame, match());
        outer.addView(content, new FrameLayout.LayoutParams(WIDTH, photoHeight, Gravity.CENTER));
        activity.setContentView(reelRoot);
        root = reelRoot;
        shadowOf(Looper.getMainLooper()).idle();
        return photo;
    }

    /**
     * A photo reel's frame, and the view round it, are the photo's size on 454, so a glow there had
     * nowhere to show. The reel's own view takes it, and the bands above and below the photo glow (#689).
     */
    @Test
    public void aPhotoReelSizedToItsPhotoGlowsOnTheReelAroundIt() {
        ImageView photo = photoReel454(REEL / 2, 1);
        ViewGroup content = (ViewGroup) photo.getParent().getParent();
        ViewGroup reel = (ViewGroup) content.getParent();

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertTrue(String.valueOf(reel.getBackground()), reel.getBackground() instanceof AmbientLight.Glow);
        assertNull("the photo's own view leaves the glow no room", content.getBackground());
        assertEquals(new Rect(0, REEL / 4, WIDTH, REEL * 3 / 4), AmbientLight.litRectForTests(photo));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap drawn = background(reel);
        int band = drawn.getPixel(WIDTH / 2, REEL / 4 - 4);
        assertTrue("the band above shows the photo's top: " + hex(band), Color.blue(band) > 80 && Color.alpha(band) > 80);
        int under = drawn.getPixel(WIDTH / 2, REEL * 3 / 4 + 4);
        assertTrue("the band below its bottom: " + hex(under), Color.red(under) > 80 && Color.alpha(under) > 80);
        assertEquals("nothing behind the photo", 0, Color.alpha(drawn.getPixel(WIDTH / 2, REEL / 2)));
    }

    /** A photo filling the reel has no band to glow in, and its stage stays inside the reel. */
    @Test
    public void aPhotoReelFillingTheReelHasNothingToGlowIn() {
        ImageView photo = photoReel454(REEL, 3);
        ViewGroup content = (ViewGroup) photo.getParent().getParent();

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertTrue(AmbientLight.litRectForTests(photo).isEmpty());
        assertTrue(String.valueOf(content.getBackground()), content.getBackground() instanceof AmbientLight.Glow);
        assertNull("the reel's outermost view wears no glow", root.getChildAt(0).getBackground());
    }

    /**
     * A carousel reel's video as 456 lays it out: the reel's page, then the video's frame and the view
     * round it, both the video's own size, centred in the page.
     */
    private TextureView carouselVideo(int height) {
        FrameLayout reelRoot = new FrameLayout(activity);
        FrameLayout page = new FrameLayout(activity);
        reelRoot.addView(page, new FrameLayout.LayoutParams(WIDTH, REEL));
        FrameLayout content = new FrameLayout(activity);
        int carouselVideo = View.generateViewId();
        AmbientLight.reelIdForTests(activity, AmbientLight.CAROUSEL_VIDEO, carouselVideo);
        content.setId(carouselVideo);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        TextureView video = new TextureView(activity);
        container.addView(video, match());
        frame.addView(container, match());
        content.addView(frame, match());
        page.addView(content, new FrameLayout.LayoutParams(WIDTH, height, Gravity.CENTER));
        activity.setContentView(reelRoot);
        root = reelRoot;
        shadowOf(Looper.getMainLooper()).idle();
        return video;
    }

    /** A carousel's video fills its frame and the view round it, which left it nowhere to glow (#796). The page takes it. */
    @Test
    public void aCarouselsVideoGlowsOnThePageAroundIt() {
        TextureView video = carouselVideo(REEL / 2);
        ViewGroup content = (ViewGroup) video.getParent().getParent().getParent();
        ViewGroup page = (ViewGroup) content.getParent();

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertNull("the video's own view leaves the glow no room", content.getBackground());
        assertGlowsAboveAndBelow(page, video);
    }

    /**
     * A photo reel as 456 lays it out: the reel's page, then one photo's frame the photo's own size,
     * or with [carousel] a carousel page's photo that is its own frame. Neither has the video frame's id.
     * The picture is drawn as Instagram draws it, through a drawable that fills the view and has no
     * size of its own.
     */
    private ImageView photoReel456(boolean carousel) {
        int photoFrame = View.generateViewId();
        AmbientLight.reelIdForTests(activity, carousel ? ReelCorners.CAROUSEL_PHOTO : ReelCorners.PHOTO_FRAME, photoFrame);
        FrameLayout reelRoot = new FrameLayout(activity);
        FrameLayout page = new FrameLayout(activity);
        reelRoot.addView(page, new FrameLayout.LayoutParams(WIDTH, REEL));
        ImageView photo = new ImageView(activity);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ColorDrawable sizeless = new ColorDrawable(Color.GRAY);
        assertEquals(-1, sizeless.getIntrinsicWidth());
        photo.setImageDrawable(sizeless);
        FrameLayout.LayoutParams centred = new FrameLayout.LayoutParams(WIDTH, REEL / 2, Gravity.CENTER);
        if (carousel) {
            photo.setId(photoFrame);
            page.addView(photo, centred);
        } else {
            FrameLayout frame = new FrameLayout(activity);
            frame.setId(photoFrame);
            frame.addView(photo, match());
            page.addView(frame, centred);
        }
        activity.setContentView(reelRoot);
        root = reelRoot;
        shadowOf(Looper.getMainLooper()).idle();
        return photo;
    }

    /** A one photo reel sits in a frame of its own on 456, not the video's, and stayed dark (#796). */
    @Test
    public void aPhotoReelInItsOwnFrameGlowsOnThePageAroundIt() {
        ImageView photo = photoReel456(false);

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertGlowsAboveAndBelow((ViewGroup) root.getChildAt(0), photo);
    }

    /** A photo carousel's page is the photo's own view, and stayed dark (#796). */
    @Test
    public void aPhotoCarouselsPageGlowsOnThePageAroundIt() {
        ImageView photo = photoReel456(true);

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertGlowsAboveAndBelow((ViewGroup) root.getChildAt(0), photo);
    }

    /** [stage] wears the glow, and its bands above and below [media], the middle of the reel, show its top and bottom. */
    private void assertGlowsAboveAndBelow(ViewGroup stage, View media) {
        assertTrue(String.valueOf(stage.getBackground()), stage.getBackground() instanceof AmbientLight.Glow);
        assertEquals(new Rect(0, REEL / 4, WIDTH, REEL * 3 / 4), AmbientLight.litRectForTests(media));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap drawn = background(stage);
        int band = drawn.getPixel(WIDTH / 2, REEL / 4 - 4);
        assertTrue("the band above shows the top: " + hex(band), Color.blue(band) > 80 && Color.alpha(band) > 80);
        int under = drawn.getPixel(WIDTH / 2, REEL * 3 / 4 + 4);
        assertTrue("the band below the bottom: " + hex(under), Color.red(under) > 80 && Color.alpha(under) > 80);
        assertEquals("nothing behind the picture", 0, Color.alpha(drawn.getPixel(WIDTH / 2, REEL / 2)));
    }

    /**
     * Instagram's player can still put a reel on a SurfaceView while a switch here asks for
     * TextureViews, and that reel stayed dark, black bands and all (#796). It's lit, and its copy is
     * asked of the SurfaceView.
     */
    @Test
    public void aReelOnASurfaceViewGlowsInItsBands() {
        FrameLayout reelRoot = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        SurfaceView video = new SurfaceView(activity);
        container.addView(video, new FrameLayout.LayoutParams(WIDTH, REEL / 2, Gravity.CENTER));
        frame.addView(container, match());
        reelRoot.addView(frame, new FrameLayout.LayoutParams(WIDTH, REEL));
        activity.setContentView(reelRoot);
        root = reelRoot;
        shadowOf(Looper.getMainLooper()).idle();
        View[] asked = new View[1];
        AmbientLight.samplerForTests((owner, source, inWindow, into, done) -> {
            asked[0] = source;
            int[] pixels = new int[into.getWidth() * into.getHeight()];
            for (int i = 0; i < pixels.length; i++) pixels[i] = i < pixels.length / 2 ? top : bottom;
            into.setPixels(pixels, 0, into.getWidth(), 0, 0, into.getWidth(), into.getHeight());
            done.accept(true);
        });

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertSame(video, asked[0]);
        assertGlowsAboveAndBelow(container, video);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AmbientLight.COUNT_SURFACE_REELS + " 1"));
    }

    /** A video starting over the photo it showed first takes the glow, and the photo's goes out. */
    @Test
    public void aVideoStartingOverItsCoverTakesTheGlow() {
        ImageView cover = photoReel();
        ViewGroup container = (ViewGroup) cover.getParent();
        TextureView video = new TextureView(activity);
        video.setVisibility(View.GONE);
        container.addView(video, match());
        shadowOf(Looper.getMainLooper()).idle();

        on();
        assertNotNull("the cover glows while the video loads", AmbientLight.litRectForTests(cover));

        video.setVisibility(View.VISIBLE);
        shadowOf(Looper.getMainLooper()).idle();
        relayout();

        assertEquals(1, AmbientLight.litForTests());
        assertNotNull(AmbientLight.litRectForTests(video));
        assertNull("the cover's light is out", AmbientLight.litRectForTests(cover));

        cover.setVisibility(View.GONE);
        video.setVisibility(View.GONE);
        shadowOf(Looper.getMainLooper()).idle();
        relayout();
        assertEquals("nothing shows, nothing glows", 0, AmbientLight.litForTests());
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

    /**
     * On 454 the Reels tab holds a reel 18 views below the view that spans the window, further up than
     * the light looked, so the bands under the status bar and the gesture bar stayed black (#722).
     */
    @Test
    public void aReelFarBelowTheViewThatSpansTheWindowStillLightsTheBands() {
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        root = host;
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        shadowOf(Looper.getMainLooper()).idle();
        View window = activity.getWindow().getDecorView();
        int width = window.getWidth(), height = window.getHeight();
        int statusBar = height / 25, gestureBar = height / 20;
        int stage = height - statusBar - gestureBar;
        FrameLayout between = new FrameLayout(activity);
        FrameLayout.LayoutParams margins = match();
        margins.topMargin = statusBar;
        margins.bottomMargin = gestureBar;
        host.addView(between, margins);
        ViewGroup deepest = between;
        for (int i = 0; i < 20; i++) {
            FrameLayout level = new FrameLayout(activity);
            deepest.addView(level, match());
            deepest = level;
        }
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        TextureView video = new TextureView(activity);
        container.addView(video, match());
        frame.addView(container, match());
        deepest.addView(frame, match());
        shadowOf(Looper.getMainLooper()).idle();

        on();

        assertEquals(1, AmbientLight.litForTests());
        assertEquals(new Rect(0, statusBar, width, statusBar + stage), AmbientLight.hostLitForTests(video));
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        Bitmap bands = background(host);
        int status = bands.getPixel(width / 2, statusBar / 2);
        assertTrue("under the status bar, the reel's top: " + hex(status),
                Color.alpha(status) > 20 && Color.blue(status) > Color.red(status));
        int gesture = bands.getPixel(width / 2, height - gestureBar / 2);
        assertTrue("under the gesture bar, the reel's bottom: " + hex(gesture),
                Color.alpha(gesture) > 20 && Color.red(gesture) > Color.blue(gesture));
    }

    /**
     * Rounded corners on Reels cuts the reel's frame round, and the black behind it showed in its
     * corners against the glow under the system bars (#722). The view the frame sits in draws the glow
     * there, and only there.
     */
    @Test
    public void aRoundedReelsCornersShowTheGlowNotTheBlackBehindThem() {
        Settings.ROUNDED_REEL_CORNERS.save(true);
        Settings.ROUNDED_CORNER_SIZE.save(CornerSize.LARGE);
        int radius = Math.round(CornerSize.LARGE.dp * activity.getResources().getDisplayMetrics().density);
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        root = host;
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        shadowOf(Looper.getMainLooper()).idle();
        int height = activity.getWindow().getDecorView().getHeight();
        int statusBar = height / 25, gestureBar = height / 20;
        int stage = height - statusBar - gestureBar;
        FrameLayout page = new FrameLayout(activity);
        page.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams margins = match();
        margins.topMargin = statusBar;
        margins.bottomMargin = gestureBar;
        host.addView(page, margins);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        // Drawn to a bitmap, the outline's cut is left out, so the corners show what's behind the
        // video there: the page's black, or the glow over it.
        frame.setClipToOutline(true);
        FrameLayout container = new FrameLayout(activity);
        container.addView(new TextureView(activity), match());
        frame.addView(container, match());
        page.addView(frame, match());
        shadowOf(Looper.getMainLooper()).idle();

        on();
        advance(AmbientSpeed.MEDIUM.fadeMillis);

        assertEquals(1, AmbientLight.litForTests());
        int width = page.getWidth();
        Bitmap drawnPage = drawn(page);
        int topCorner = drawnPage.getPixel(1, 1);
        assertTrue("the reel's top corner shows the glow above it, the reel's top: " + hex(topCorner),
                Color.blue(topCorner) > 60 && Color.red(topCorner) < 30);
        int bottomCorner = drawnPage.getPixel(width - 2, stage - 2);
        assertTrue("its bottom corner, the reel's bottom: " + hex(bottomCorner),
                Color.red(bottomCorner) > 60 && Color.blue(bottomCorner) < 30);
        // A TextureView drawn without a GPU clears its own area, so nothing glowing there reads as clear.
        assertUnlit("the reel itself is left alone", drawnPage.getPixel(radius + 2, 1));
        assertUnlit("the reel's middle too", drawnPage.getPixel(width / 2, stage / 2));

        frame.setClipToOutline(false);
        frame();
        assertUnlit("a frame whose corners aren't cut covers them itself", drawn(page).getPixel(1, 1));
        frame.setClipToOutline(true);
        frame();
        assertTrue("cut again, they glow again", Color.blue(drawn(page).getPixel(1, 1)) > 60);

        Settings.AMBIENT_LIGHT.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertUnlit("off, nothing glows in the corner", drawn(page).getPixel(1, 1));
    }

    private static void assertUnlit(String what, int pixel) {
        assertTrue(what + ": " + hex(pixel), Color.red(pixel) < 30 && Color.green(pixel) < 30 && Color.blue(pixel) < 30);
    }

    /**
     * A reel opened from the feed stops its comment bar over the reel, and the strip under that bar
     * glowed, so the bar looked cut off from what's below it (#510). The host's glow stops at the bar.
     */
    @Test
    public void nothingGlowsUnderAReelsCommentBar() {
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        root = host;
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        shadowOf(Looper.getMainLooper()).idle();
        View window = activity.getWindow().getDecorView();
        int width = window.getWidth(), height = window.getHeight();
        int statusBar = height / 25, gestureBar = height / 20, footerHeight = 50;
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        container.addView(new TextureView(activity), match());
        frame.addView(container, match());
        FrameLayout.LayoutParams between = match();
        between.topMargin = statusBar;
        between.bottomMargin = gestureBar;
        host.addView(frame, between);
        View footer = new View(activity);
        int footerId = View.generateViewId();
        footer.setId(footerId);
        footer.setVisibility(View.GONE);
        FrameLayout.LayoutParams over = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, footerHeight, Gravity.BOTTOM);
        over.bottomMargin = gestureBar;
        host.addView(footer, over);
        AmbientLight.footerIdForTests(activity, footerId);
        shadowOf(Looper.getMainLooper()).idle();

        on();
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        int strip = height - gestureBar / 2;
        int without = Color.alpha(background(host).getPixel(width / 2, strip));
        assertTrue("with no bar shown, the strip glows: " + without, without > 20);

        footer.setVisibility(View.VISIBLE);
        shadowOf(Looper.getMainLooper()).idle();
        relayout();

        Bitmap bands = background(host);
        assertEquals("nothing under the bar", 0, Color.alpha(bands.getPixel(width / 2, strip)));
        int status = bands.getPixel(width / 2, statusBar / 2);
        assertTrue("the band above the reel still glows: " + hex(status), Color.alpha(status) > 20);

        footer.setVisibility(View.GONE);
        shadowOf(Looper.getMainLooper()).idle();
        relayout();
        assertTrue("the bar gone, the strip glows again",
                Color.alpha(background(host).getPixel(width / 2, strip)) > 20);
    }

    /**
     * 456 sits a reel's video a little in from the top and bottom of its page, 12 px each on a 720 px
     * wide phone. That's over 98% of the page, so the page drew no glow of its own, and the host leaves
     * the page to it: those rims stayed black above and below the video (#796).
     */
    @Test
    public void theRimsBetweenAReelAndItsPageGlow() {
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        root = host;
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        shadowOf(Looper.getMainLooper()).idle();
        View window = activity.getWindow().getDecorView();
        int width = window.getWidth(), height = window.getHeight();
        int statusBar = height / 25, gestureBar = height / 20;
        FrameLayout page = new FrameLayout(activity);
        FrameLayout.LayoutParams between = match();
        between.topMargin = statusBar;
        between.bottomMargin = gestureBar;
        host.addView(page, between);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        page.addView(frame, match());
        int pageHeight = height - statusBar - gestureBar, rim = pageHeight / 100;
        assertTrue("a rim wider than the rounding: " + rim, rim > AmbientLight.ROUNDING);
        FrameLayout container = new FrameLayout(activity);
        TextureView video = new TextureView(activity);
        container.addView(video, match());
        FrameLayout.LayoutParams inset = match();
        inset.topMargin = rim;
        inset.bottomMargin = rim;
        frame.addView(container, inset);
        shadowOf(Looper.getMainLooper()).idle();

        on();
        advance(AmbientSpeed.MEDIUM.fadeMillis);

        assertTrue(String.valueOf(page.getBackground()), page.getBackground() instanceof AmbientLight.Glow);
        assertEquals(new Rect(0, rim, width, pageHeight - rim), AmbientLight.litRectForTests(video));
        Bitmap drawn = background(page);
        int above = drawn.getPixel(width / 2, rim / 2);
        assertTrue("the rim above the video glows: " + hex(above), Color.alpha(above) > 20);
        int below = drawn.getPixel(width / 2, pageHeight - rim / 2 - 1);
        assertTrue("the rim below it: " + hex(below), Color.alpha(below) > 20);
        assertEquals("nothing behind the video", 0, Color.alpha(drawn.getPixel(width / 2, pageHeight / 2)));
    }

    /**
     * With the comment box hidden and its room kept, the bar under a reel opened from Explore shows
     * nothing of its own, and its black covered the glow (#796). It wears the glow like the tab bar,
     * and the strip under it glows too, until the comment box shows again.
     */
    @Test
    public void anEmptyCommentBarsRoomGlowsUntilItsBoxShowsAgain() {
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        root = host;
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        shadowOf(Looper.getMainLooper()).idle();
        View window = activity.getWindow().getDecorView();
        int width = window.getWidth(), height = window.getHeight();
        int statusBar = height / 25, gestureBar = height / 20, footerHeight = 50;
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        FrameLayout container = new FrameLayout(activity);
        container.addView(new TextureView(activity), match());
        frame.addView(container, match());
        FrameLayout.LayoutParams between = match();
        between.topMargin = statusBar;
        between.bottomMargin = gestureBar + footerHeight;
        host.addView(frame, between);
        FrameLayout footer = new FrameLayout(activity);
        int footerId = View.generateViewId();
        footer.setId(footerId);
        ColorDrawable footerBlack = new ColorDrawable(Color.BLACK);
        footer.setBackground(footerBlack);
        View commentBox = new View(activity);
        commentBox.setVisibility(View.INVISIBLE);
        footer.addView(commentBox, match());
        FrameLayout.LayoutParams under = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, footerHeight, Gravity.BOTTOM);
        under.bottomMargin = gestureBar;
        host.addView(footer, under);
        AmbientLight.footerIdForTests(activity, footerId);
        shadowOf(Looper.getMainLooper()).idle();

        on();
        advance(AmbientSpeed.MEDIUM.fadeMillis);
        frame();

        assertTrue(String.valueOf(footer.getBackground()), footer.getBackground() instanceof AmbientLight.Glow);
        int room = background(footer).getPixel(width / 2, footerHeight / 2);
        assertTrue("the room glows over its black: " + hex(room), Color.red(room) > 20);
        int strip = height - gestureBar - footerHeight + 4;
        int behind = Color.alpha(background(host).getPixel(width / 2, strip));
        assertTrue("and the band behind it isn't cut at its top: " + behind, behind > 20);

        commentBox.setVisibility(View.VISIBLE);
        shadowOf(Looper.getMainLooper()).idle();
        relayout();

        assertSame("the comment box showing, the bar keeps its own black", footerBlack, footer.getBackground());
        assertEquals("and nothing glows under it", 0, Color.alpha(background(host).getPixel(width / 2, strip)));
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
