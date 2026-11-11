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
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
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
        Settings.ROUNDED_STORY_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
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

    /** [view] drawn whole, children and overlay included, the size of [view]. */
    private static Bitmap shown(View view) {
        Bitmap drawn = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(drawn));
        return drawn;
    }

    /**
     * A grey picture [width] by [height] with its corners cut round to [radius] and clear past them, as
     * Rounded corners on stories leaves a story's frame. The frame's own cut is an outline clip, which
     * a drawing on a bitmap leaves out.
     */
    private Drawable roundedPicture(int width, int height, int radius) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.GRAY);
        new Canvas(bitmap).drawRoundRect(0, 0, width, height, radius, radius, paint);
        return new BitmapDrawable(activity.getResources(), bitmap);
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

    /**
     * #630: Instagram's media layout round a story's frame paints black, and Rounded corners on stories
     * cut the frame's corners through to that black, so the card's corners stood out dark against the
     * glow round it. The layout draws the glow over its black in those corners, and only there.
     */
    @Test
    public void aRoundedStoryCardsCornersShowTheGlowRoundIt() {
        Settings.ROUNDED_STORY_CORNERS.save(true);
        Settings.ROUNDED_CORNER_SIZE.save(CornerSize.LARGE);
        int radius = Math.round(CornerSize.LARGE.dp * activity.getResources().getDisplayMetrics().density);
        int card = 300;
        root.removeView(frame);
        frame.removeView(photo);
        frame.addView(photo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout layout = new FrameLayout(activity);
        layout.setBackgroundColor(Color.BLACK);
        layout.addView(frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout stage = new FrameLayout(activity);
        stage.addView(layout, new FrameLayout.LayoutParams(WIDTH, card));
        root.addView(stage, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        // Rounded corners on stories cuts the frame round by its outline. Drawing to a bitmap leaves
        // outlines out, so the picture is cut the same way.
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);
        photo.setImageDrawable(roundedPicture(WIDTH, card, radius));
        laidOut();

        storiesOn();
        advance(AmbientSpeed.MEDIUM.fadeMillis);

        assertEquals(new Rect(0, 0, WIDTH, card), AmbientLight.litRectForTests(photo));
        Bitmap drawn = shown(stage);
        int bottomCorner = drawn.getPixel(1, card - 2);
        assertTrue("the card's bottom corner shows the glow below it, the picture's bottom: " + hex(bottomCorner),
                Color.red(bottomCorner) > 60 && Color.blue(bottomCorner) < 30);
        int topCorner = drawn.getPixel(WIDTH - 2, 1);
        assertTrue("its top corner, the picture's top: " + hex(topCorner), Color.blue(topCorner) > 60 && Color.red(topCorner) < 30);
        assertEquals("the card itself is left alone", Color.GRAY, drawn.getPixel(radius + 2, card - 2));
        assertEquals(Color.GRAY, drawn.getPixel(WIDTH / 2, card / 2));

        frame.setClipToOutline(false);
        frame();
        assertEquals("a frame whose corners aren't cut covers them itself", Color.BLACK, shown(stage).getPixel(1, card - 2));
        frame.setClipToOutline(true);
        frame();
        assertTrue("cut again, they glow again", Color.red(shown(stage).getPixel(1, card - 2)) > 60);

        Settings.AMBIENT_LIGHT_STORIES.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("off, the layout's own black again", Color.BLACK, shown(stage).getPixel(1, card - 2));
    }

    /**
     * #630: Instagram's rounded layout round a story's frame paints its corners solid black over the
     * story, with Rounded corners on stories off too. Its setCornerRadius tells AmbientLight the radius,
     * and the glow covers those corners, until it says 0.
     */
    @Test
    public void theCornersAStoryCardsLayoutPaintsShowTheGlowRoundIt() {
        int radius = 15, card = 300;
        root.removeView(frame);
        frame.removeView(photo);
        frame.addView(photo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        CuttingLayout layout = new CuttingLayout(activity);
        layout.addView(frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout stage = new FrameLayout(activity);
        stage.addView(layout, new FrameLayout.LayoutParams(WIDTH, card));
        root.addView(stage, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        photo.setImageDrawable(picture(WIDTH, card));
        layout.setCornerRadius(radius);
        laidOut();

        storiesOn();
        advance(AmbientSpeed.MEDIUM.fadeMillis);

        assertFalse(Settings.ROUNDED_STORY_CORNERS.get());
        Bitmap drawn = shown(stage);
        int bottomCorner = drawn.getPixel(1, card - 2);
        assertTrue("the card's bottom corner shows the glow below it, the picture's bottom: " + hex(bottomCorner),
                Color.red(bottomCorner) > 60 && Color.blue(bottomCorner) < 30);
        int topCorner = drawn.getPixel(WIDTH - 2, 1);
        assertTrue("its top corner, the picture's top: " + hex(topCorner), Color.blue(topCorner) > 60 && Color.red(topCorner) < 30);
        assertEquals("the card itself is left alone", Color.GRAY, drawn.getPixel(radius + 2, card - 2));
        assertEquals(Color.GRAY, drawn.getPixel(WIDTH / 2, card / 2));

        AmbientLight.cornerRadius(layout, 0);
        frame();
        assertEquals("a layout whose radius isn't known keeps its own corners", Color.BLACK, shown(stage).getPixel(1, card - 2));
        layout.setCornerRadius(radius);
        frame();
        assertTrue("told again, they glow again", Color.red(shown(stage).getPixel(1, card - 2)) > 60);
    }

    /**
     * Instagram's RoundedCornerFrameLayout as 454 has it: it paints its corners solid black over what it
     * holds, and its setCornerRadius tells AmbientLight the radius first, as the patch makes it do.
     */
    private static final class CuttingLayout extends FrameLayout {
        private final Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int radius;

        CuttingLayout(Context context) {
            super(context);
            black.setColor(Color.BLACK);
            // On a phone the layout's overlay is drawn over its children with or without a background.
            // Drawn to a bitmap, a layout that skips drawing itself leaves its overlay out.
            setWillNotDraw(false);
        }

        void setCornerRadius(int radius) {
            AmbientLight.cornerRadius(this, radius);
            this.radius = radius;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (radius <= 0) return;
            Path corners = new Path();
            corners.setFillType(Path.FillType.EVEN_ODD);
            corners.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            corners.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius, Path.Direction.CW);
            canvas.drawPath(corners, black);
        }
    }

    /**
     * #630: the layout round Instagram's story viewer holds it below the status bar and paints the strip
     * above it black itself, over the glow the view spanning the window draws there. The layout now
     * draws the glow in that strip, over its own black and nowhere over the story, so the story's
     * progress bar and header stay on top.
     */
    @Test
    public void theStripAboveAStoryThatItsLayoutPaintsGlowsToo() {
        stripAboveTheStory(false);
    }

    /**
     * #630: Instagram also puts a bare one pixel view at the top of a layout round the viewer. Counted as
     * the layout's highest child it left no strip, and the black behind showed as a line above the story.
     */
    @Test
    public void aBareLineAtTheTopOfTheLayoutLeavesTheStripLit() {
        stripAboveTheStory(true);
    }

    /** The highest child that shows something ends the strip, and a bare view showing nothing doesn't. */
    @Test
    public void onlyAChildThatShowsSomethingEndsTheStrip() {
        FrameLayout group = new FrameLayout(activity);
        View line = new View(activity);
        group.addView(line, new FrameLayout.LayoutParams(WIDTH, 1));
        View story = new View(activity);
        story.setBackgroundColor(Color.GRAY);
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(WIDTH, 100);
        below.topMargin = 40;
        group.addView(story, below);
        sized(group);
        assertEquals("under a bare line", 40, AmbientLight.stripUnder(group));

        line.setBackgroundColor(Color.WHITE);
        assertEquals("under a line with a color", 0, AmbientLight.stripUnder(group));

        group.removeView(line);
        View drawing = new View(activity) {
            @Override
            protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.WHITE);
            }
        };
        group.addView(drawing, 0, new FrameLayout.LayoutParams(WIDTH, 1));
        sized(group);
        assertEquals("under a line that draws itself", 0, AmbientLight.stripUnder(group));
    }

    private static void sized(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, 200);
    }

    /** The story below the status bar in a layout painting the strip above it black, with a bare line at the layout's top or not. */
    private void stripAboveTheStory(boolean bareLine) {
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        laidOut();
        View window = activity.getWindow().getDecorView();
        int width = window.getWidth(), height = window.getHeight();
        assertEquals(height, root.getHeight());
        int statusBar = height / 25, gestureBar = height / 20;
        int stage = height - statusBar - gestureBar;
        root.removeView(frame);
        frame.removeView(photo);
        frame.addView(photo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        View progress = new View(activity);
        progress.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams across = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 4);
        across.topMargin = 2;
        frame.addView(progress, across);
        FrameLayout main = new FrameLayout(activity);
        main.addView(frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout layout = new FrameLayout(activity);
        layout.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        below.topMargin = statusBar;
        if (bareLine) layout.addView(new View(activity), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1));
        layout.addView(main, below);
        root.addView(layout, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height - gestureBar));
        photo.setImageDrawable(picture(width, stage));
        laidOut();

        storiesOn();
        advance(AmbientSpeed.MEDIUM.fadeMillis);

        assertEquals(new Rect(0, statusBar, width, statusBar + stage), AmbientLight.hostLitForTests(photo));
        Bitmap drawn = shown(root);
        int strip = drawn.getPixel(width / 2, statusBar - 3);
        assertTrue("the strip above the story, the picture's top: " + hex(strip), Color.blue(strip) > 60 && Color.red(strip) < 30);
        assertEquals("the progress bar stays on top", Color.WHITE, drawn.getPixel(width / 2, statusBar + 4));
        int gestureBand = drawn.getPixel(width / 2, height - gestureBar / 2);
        assertTrue("under the gesture bar, its bottom: " + hex(gestureBand),
                Color.alpha(gestureBand) > 20 && Color.red(gestureBand) > 60 && Color.blue(gestureBand) < 30);

        Settings.AMBIENT_LIGHT_STORIES.save(false);
        AmbientLight.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("off, the layout's own black again", Color.BLACK, shown(root).getPixel(width / 2, statusBar - 3));
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

    /**
     * Instagram's frame round a story is a kind of its post frame. With Ambient light on too, the post
     * search took it and lit it as a post, with nothing under the status bar and nothing over the
     * frame's corners, and Ambient light alone lit stories it should leave dark (#630).
     */
    @Test
    public void aStoryFrameThatIsAlsoAPostFrameGlowsOnlyAsAStory() {
        MediaFrameLayout storyFrame = new MediaFrameLayout(activity);
        storyFrame.setId(storyFrameId);
        root.removeView(frame);
        frame.removeView(photo);
        storyFrame.addView(photo, new FrameLayout.LayoutParams(WIDTH, HEIGHT, Gravity.CENTER));
        root.addView(storyFrame, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        photo.setImageDrawable(picture(90, 160));
        laidOut();
        Settings.AMBIENT_LIGHT.save(true);
        AmbientLight.watch(activity);
        frame();
        laidOut();
        assertEquals("posts and reels only", 0, AmbientLight.litForTests());

        storiesOn();

        assertNull("not lit as a post", AmbientLight.litRectForTests(storyFrame));
        assertNotNull("lit as a story", AmbientLight.litRectForTests(photo));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AmbientLight.COUNT_STORIES + " 1"));
    }

    /**
     * A tall status bar left Instagram's story viewer under nine tenths of the window, so the stage
     * went to a view round the bars, with no host for the bands and no back for the corners (#630).
     * The viewer spans what the bars leave, so it takes the stage.
     */
    @Test
    public void theViewerBetweenTallBarsStillTakesTheStage() throws Exception {
        WindowInsets bars = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 128, 0, 48)).build()
                : WindowInsets.class.getConstructor(Rect.class).newInstance(new Rect(0, 128, 0, 48));
        FrameLayout window = new FrameLayout(activity) {
            @Override
            public WindowInsets getRootWindowInsets() {
                return bars;
            }
        };
        FrameLayout viewer = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        View media = new View(activity);
        card.addView(media, new FrameLayout.LayoutParams(720, 1280));
        viewer.addView(card, new FrameLayout.LayoutParams(720, 1280));
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(720, 1424);
        below.topMargin = 128;
        window.addView(viewer, below);
        window.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, 720, 1600);

        assertSame(viewer, AmbientLight.storyStageOf(media, window));
    }
}
