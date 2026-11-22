/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StoryProgressBar;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Seek bar shape (#586): a shape takes over a bar's drawables, and going back to standard hands them back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SeekShapesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private SeekBar bar;

    @Before
    public void createBar() {
        Settings.SEEK_BAR_SHAPE.resetToDefault();
        bar = new SeekBar(RuntimeEnvironment.getApplication());
        bar.setPadding(16, 3, 24, 5);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SEEK_BAR_SHAPE.resetToDefault();
        SeekShapes.resetForTests();
        StoryProgressBar.resetForTests();
    }

    @Test
    public void standardToStartLeavesTheBarAlone() {
        assertEquals(SeekShape.STANDARD, Settings.SEEK_BAR_SHAPE.defaultValue);
        Drawable track = bar.getProgressDrawable();
        Drawable handle = bar.getThumb();
        SeekShapes.apply(bar, SeekShapes.wanted(), OverlayLook.SOLID, false);
        assertSame(track, bar.getProgressDrawable());
        assertSame(handle, bar.getThumb());
    }

    @Test
    public void everyShapeTakesOverAndGivesBack() {
        Drawable track = bar.getProgressDrawable();
        Drawable handle = bar.getThumb();
        int offset = bar.getThumbOffset();
        for (SeekShape shape : new SeekShape[] {SeekShape.LINE, SeekShape.ROUNDED, SeekShape.EXPRESSIVE, SeekShape.WAVE}) {
            SeekShapes.apply(bar, shape, OverlayLook.SOLID, false);
            assertTrue(shape + " draws the track", ((SeekShapeDrawable) bar.getProgressDrawable()).draws(shape, false));
            assertNotSame(shape + " takes the handle", handle, bar.getThumb());
            assertEquals(shape + " has no handle to lay out around", 0, bar.getThumb().getIntrinsicWidth());

            SeekShapes.apply(bar, SeekShape.STANDARD, OverlayLook.SOLID, false);
            assertSame(shape + " hands the track back", track, bar.getProgressDrawable());
            assertSame(shape + " hands the handle back", handle, bar.getThumb());
            assertEquals(shape + " hands the offset back", offset, bar.getThumbOffset());
            assertEquals(16, bar.getPaddingLeft());
            assertEquals(24, bar.getPaddingRight());
        }
    }

    /** It's called on every tick, so an unchanged shape must keep its drawable, or a held spring would start over. */
    @Test
    public void theSameShapeKeepsItsDrawable() {
        SeekShapes.apply(bar, SeekShape.ROUNDED, OverlayLook.SOLID, false);
        Drawable first = bar.getProgressDrawable();
        SeekShapes.apply(bar, SeekShape.ROUNDED, OverlayLook.SEE_THROUGH, false);
        assertSame(first, bar.getProgressDrawable());
        SeekShapes.apply(bar, SeekShape.ROUNDED, OverlayLook.SOLID, true);
        assertNotSame("moving to the edge is a new drawing", first, bar.getProgressDrawable());
    }

    @Test
    public void aLineOnTheEdgeRunsEdgeToEdge() {
        SeekShapes.apply(bar, SeekShape.LINE, OverlayLook.SOLID, true);
        assertEquals(0, bar.getPaddingLeft());
        assertEquals(0, bar.getPaddingRight());
        assertEquals("only the sides change", 3, bar.getPaddingTop());
        assertEquals(5, bar.getPaddingBottom());

        SeekShapes.apply(bar, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertEquals("a shape with a handle keeps the handle's room", 16, bar.getPaddingLeft());
        assertEquals(24, bar.getPaddingRight());

        SeekShapes.restore(bar);
        assertEquals(16, bar.getPaddingLeft());
        assertEquals(24, bar.getPaddingRight());
    }

    @Test
    public void restoringABarWithNoShapeDoesNothing() {
        Drawable track = bar.getProgressDrawable();
        SeekShapes.restore(bar);
        assertSame(track, bar.getProgressDrawable());
        assertEquals(16, bar.getPaddingLeft());
    }

    /**
     * A canvas that notes each rounded rectangle drawn on it (left, top, right, bottom and both radii,
     * and the color it was drawn in), and in [order] each rectangle and path in turn.
     */
    private static final class Recording extends Canvas {
        final List<float[]> rects = new ArrayList<>();
        final List<Paint> paints = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        final List<String> order = new ArrayList<>();

        @Override
        public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
            rects.add(new float[] {rect.left, rect.top, rect.right, rect.bottom, rx, ry});
            paints.add(paint);
            colors.add(paint.getColor());
            order.add("rect");
        }

        @Override
        public void drawPath(Path path, Paint paint) {
            order.add("wave");
        }
    }

    /**
     * #796: Instagram draws the playing story segment's whole track and then its played part over
     * it, and the wave's gaps showed the straight track running on under it. The wave now holds that
     * track back and draws only its rest past the played part, in the track's own color, before the
     * wave. A played segment and one to come draw at once, as before.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theWaveLeavesTheTrackOutFromUnderThePlayedPart() {
        storyProgress(SeekShape.WAVE);
        Recording canvas = new Recording();
        Paint paint = new Paint();

        paint.setColor(0xFFFFFFFF);
        SeekShapes.storySegment(canvas, new RectF(0, 20, 100, 24), 2f, 2f, paint);
        assertEquals("a played segment waves at once", Arrays.asList("wave"), canvas.order);

        paint.setColor(0x99FFFFFF);
        RectF rect = new RectF(110, 20, 210, 24);
        SeekShapes.storySegment(canvas, rect, 2f, 2f, paint);
        assertEquals("the playing segment's track waits for its played part", 1, canvas.order.size());

        paint.setColor(0xFFFFFFFF);
        rect.right = 150f;
        SeekShapes.storySegment(canvas, rect, 2f, 2f, paint);
        assertEquals(Arrays.asList("wave", "rect", "wave"), canvas.order);
        assertEquals("the track starts where the wave stops", 150f, canvas.rects.get(0)[0], 0f);
        assertEquals("and runs to the segment's end", 210f, canvas.rects.get(0)[2], 0f);
        assertEquals("in the track's own color", 0x99FFFFFF, (int) canvas.colors.get(0));

        paint.setColor(0x99FFFFFF);
        SeekShapes.storySegment(canvas, new RectF(220, 20, 320, 24), 2f, 2f, paint);
        assertEquals("a segment to come draws at once", 4, canvas.order.size());
        assertEquals(220f, canvas.rects.get(1)[0], 0f);
        assertEquals(320f, canvas.rects.get(1)[2], 0f);

        SeekShapes.storyBarDrawn(canvas);
        assertEquals("nothing's left to draw at the end", 4, canvas.order.size());
    }

    /** #796: right to left the played part fills from the segment's right end, and the rest stays on its left. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aBarFillingFromTheRightKeepsItsTrackOnTheLeft() {
        storyProgress(SeekShape.WAVE);
        Recording canvas = new Recording();
        Paint paint = new Paint();
        paint.setColor(0x99FFFFFF);
        RectF rect = new RectF(110, 20, 210, 24);
        SeekShapes.storySegment(canvas, rect, 2f, 2f, paint);
        paint.setColor(0xFFFFFFFF);
        rect.left = 170f;
        SeekShapes.storySegment(canvas, rect, 2f, 2f, paint);
        assertEquals(Arrays.asList("rect", "wave"), canvas.order);
        assertEquals(110f, canvas.rects.get(0)[0], 0f);
        assertEquals(170f, canvas.rects.get(0)[2], 0f);
    }

    /** #796: a track whose played part never comes is drawn whole when the bar's onDraw ends, and a just started one whole too. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aHeldTrackStillDrawsWhole() {
        View progress = storyProgress(SeekShape.WAVE);
        Recording canvas = new Recording();
        Paint paint = new Paint();
        paint.setColor(0x99FFFFFF);
        SeekShapes.storySegment(canvas, new RectF(110, 20, 210, 24), 2f, 2f, paint);
        assertTrue(canvas.order.isEmpty());
        SeekShapes.storyBarDrawn(canvas);
        assertEquals(Arrays.asList("rect"), canvas.order);
        assertEquals(110f, canvas.rects.get(0)[0], 0f);
        assertEquals(210f, canvas.rects.get(0)[2], 0f);

        SeekShapes.storyBar(progress);
        SeekShapes.storySegment(canvas, new RectF(110, 20, 210, 24), 2f, 2f, paint);
        paint.setColor(0xFFFFFFFF);
        SeekShapes.storySegment(canvas, new RectF(110, 20, 110, 24), 2f, 2f, paint);
        assertEquals("nothing played yet leaves the whole track", 2, canvas.rects.size());
        assertEquals(110f, canvas.rects.get(1)[0], 0f);
        assertEquals(210f, canvas.rects.get(1)[2], 0f);
    }

    /** #796: until the bar's onDraw is known to say it's done, and on any other shape, every segment draws at once. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nothingIsHeldWithoutTheBarsEndOrOffTheWave() {
        FrameLayout header = new FrameLayout(RuntimeEnvironment.getApplication());
        View progress = new View(RuntimeEnvironment.getApplication());
        header.addView(progress);
        Settings.SEEK_BAR_SHAPE.save(SeekShape.WAVE);
        SeekShapes.storyBar(progress);
        Recording canvas = new Recording();
        Paint paint = new Paint();
        paint.setColor(0x99FFFFFF);
        SeekShapes.storySegment(canvas, new RectF(110, 20, 210, 24), 2f, 2f, paint);
        assertEquals("a build without the end call draws as before", 1, canvas.rects.size());

        storyProgress(SeekShape.ROUNDED);
        SeekShapes.storySegment(canvas, new RectF(110, 20, 210, 24), 2f, 2f, paint);
        assertEquals(2, canvas.rects.size());
    }

    /** A story progress bar in its header, the wave's end call seen once, and a frame started in [shape]. */
    private static View storyProgress(SeekShape shape) {
        FrameLayout header = new FrameLayout(RuntimeEnvironment.getApplication());
        View progress = new View(RuntimeEnvironment.getApplication());
        header.addView(progress);
        Settings.SEEK_BAR_SHAPE.save(shape);
        SeekShapes.storyBarDrawn(new Canvas());
        SeekShapes.storyBar(progress);
        return progress;
    }

    /** #630: on standard each story segment is the very call Instagram made, and its header keeps clipping. */
    @Test
    public void storySegmentsStayInstagramsOnStandard() {
        FrameLayout header = new FrameLayout(RuntimeEnvironment.getApplication());
        View progress = new View(RuntimeEnvironment.getApplication());
        header.addView(progress);
        SeekShapes.storyBar(progress);
        assertTrue(header.getClipChildren());

        Recording canvas = new Recording();
        Paint paint = new Paint();
        SeekShapes.storySegment(canvas, new RectF(10, 20, 110, 24), 2f, 3f, paint);
        assertEquals(1, canvas.rects.size());
        assertArrayEquals(new float[] {10, 20, 110, 24, 2, 3}, canvas.rects.get(0), 0f);
        assertSame(paint, canvas.paints.get(0));
    }

    /** #630: a shape draws each segment at its own thickness around Instagram's middle, past the bar's height, and standard clips again. */
    @Test
    public void aShapeTakesOverStorySegmentsAndGivesTheHeaderBack() {
        FrameLayout header = new FrameLayout(RuntimeEnvironment.getApplication());
        View progress = new View(RuntimeEnvironment.getApplication());
        header.addView(progress);
        float density = progress.getResources().getDisplayMetrics().density;
        RectF rect = new RectF(10, 20, 110, 24);
        Paint paint = new Paint();

        Settings.SEEK_BAR_SHAPE.save(SeekShape.ROUNDED);
        SeekShapes.storyBar(progress);
        assertFalse("the bar may draw past its few pixels", header.getClipChildren());
        Recording canvas = new Recording();
        SeekShapes.storySegment(canvas, rect, 2f, 3f, paint);
        float thick = SeekShapeDrawable.ROUNDED_DP * density;
        assertArrayEquals(new float[] {10, 22 - thick / 2f, 110, 22 + thick / 2f, thick / 2f, thick / 2f}, canvas.rects.get(0), 0.001f);
        assertSame(paint, canvas.paints.get(0));
        assertEquals("Instagram's rectangle is left as it was", new RectF(10, 20, 110, 24), rect);

        Settings.SEEK_BAR_SHAPE.save(SeekShape.LINE);
        SeekShapes.storyBar(progress);
        SeekShapes.storySegment(canvas, rect, 2f, 3f, paint);
        thick = SeekShapeDrawable.LINE_DP * density;
        assertArrayEquals("a thin line has square ends", new float[] {10, 22 - thick / 2f, 110, 22 + thick / 2f, 0f, 0f}, canvas.rects.get(1), 0.001f);

        Settings.SEEK_BAR_SHAPE.save(SeekShape.STANDARD);
        SeekShapes.storyBar(progress);
        assertTrue("the header clips again", header.getClipChildren());
        SeekShapes.storySegment(canvas, rect, 2f, 3f, paint);
        assertArrayEquals(new float[] {10, 20, 110, 24, 2, 3}, canvas.rects.get(2), 0f);
    }

    /**
     * #689: Thicker story progress bar stretches the bar's view, and with it every segment a shape
     * draws, so a bar three times thicker drew the wave three times as tall, over the name under it.
     * #775: undoing the whole stretch left a shape exactly as thin as without the switch. A shape's
     * track now grows by what the stretch adds to Instagram's 2 px segment (4 px at three times,
     * 2 px at twice), and its waves and ends stay their own size.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xxhdpi")
    public void aShapeGrowsLikeTheStandardBarOnAThickerStoryBar() {
        FrameLayout header = new FrameLayout(RuntimeEnvironment.getApplication());
        View progress = new View(RuntimeEnvironment.getApplication());
        header.addView(progress);
        for (SeekShape shape : new SeekShape[] {SeekShape.WAVE, SeekShape.ROUNDED, SeekShape.LINE}) {
            for (float stretch : new float[] {3f, 2f}) {
                Settings.SEEK_BAR_SHAPE.save(shape);
                int plain = drawnHeight(progress);
                StoryProgressBar.stretchForTests(progress, stretch);
                assertEquals(stretch, progress.getScaleY(), 0f);
                int thicker = drawnHeight(progress);
                StoryProgressBar.resetForTests();
                progress.setScaleY(1f);
                assertTrue(shape + " draws something: " + plain, plain > 0);
                int added = Math.round(2 * (stretch - 1f));
                assertEquals(shape + " at " + stretch + " grows by what the standard bar grows by, not "
                        + stretch + " times", plain + added, thicker, 1);
            }
        }
    }

    /**
     * How many rows of pixels a story segment covers, drawn 160 px long around y 100 on a canvas the
     * bar's view stretches about that middle, as a view's vertical scale does before its onDraw.
     */
    private static int drawnHeight(View progress) {
        SeekShapes.storyBar(progress);
        Bitmap bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(1f, progress.getScaleY(), 0f, 100f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        SeekShapes.storySegment(canvas, new RectF(20, 99, 180, 101), 2f, 2f, paint);
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 0x80) continue;
                if (top < 0) top = y;
                bottom = y;
                break;
            }
        }
        return top < 0 ? 0 : bottom - top + 1;
    }

    /** #630: the seek bar of a video opened from a chat takes the shape once Instagram has built it, and standard leaves it alone. */
    @Test
    public void aChatVideosSeekBarTakesTheShape() {
        Drawable track = bar.getProgressDrawable();
        SeekShapes.chatVideo(bar);
        assertSame(track, bar.getProgressDrawable());

        Settings.SEEK_BAR_SHAPE.save(SeekShape.WAVE);
        SeekShapes.chatVideo(bar);
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.WAVE, false));
        assertEquals("not moved to the edge", 16, bar.getPaddingLeft());
    }

    /**
     * #450: a reel's bar is 28 px in a 45 px frame on a phone, so the wave's tall handle needs the frame
     * and the view round it to let it draw past their edges, no further up, and standard clips again.
     */
    @Test
    @Config(qualifiers = "xxhdpi")
    public void aShortBarLetsTheWaveDrawPastItAndGivesTheClippingBack() {
        FrameLayout screen = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout host = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout frame = new FrameLayout(RuntimeEnvironment.getApplication());
        frame.setPadding(0, 6, 0, 0);
        SeekBar reel = new SeekBar(RuntimeEnvironment.getApplication());
        // Instagram pads its track down to the bar's bottom.
        reel.setPadding(0, 24, 0, 0);
        frame.addView(reel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 28, Gravity.BOTTOM));
        host.addView(frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 45, Gravity.BOTTOM));
        screen.addView(host, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 600));
        lay(screen);

        SeekShapes.apply(reel, SeekShape.WAVE, OverlayLook.SOLID, true);
        SeekShapeDrawable wave = (SeekShapeDrawable) reel.getProgressDrawable();
        int top = reel.getPaddingTop() + wave.getBounds().top - wave.spill()[0];
        assertTrue("the shape reaches above the frame: " + top, reel.getTop() + top < 0);
        assertFalse("the frame lets it draw past its edges", frame.getClipChildren());
        assertFalse("and past its padding", frame.getClipToPadding());
        assertFalse("so does the view round the frame", host.getClipChildren());
        assertTrue("which has the room, so nothing further up changes", screen.getClipChildren());
        assertTrue(host.getClipToPadding());

        SeekShapes.apply(reel, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertFalse("the next tick keeps it", host.getClipChildren());

        // A taller frame has the room itself, so the view round it clips again.
        frame.getLayoutParams().height = 120;
        lay(screen);
        SeekShapes.apply(reel, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertFalse(frame.getClipChildren());
        assertTrue(host.getClipChildren());

        SeekShapes.apply(reel, SeekShape.STANDARD, OverlayLook.SOLID, true);
        assertTrue("standard clips again", frame.getClipChildren());
        assertTrue(frame.getClipToPadding());
        assertTrue(host.getClipChildren());
        assertTrue(screen.getClipChildren());
    }

    /** A reel's 28 px bar along the bottom of a 45 px frame, as Instagram lays it out, in [parent]. */
    private static SeekBar reelBar(FrameLayout parent, FrameLayout frame) {
        frame.setPadding(0, 6, 0, 0);
        SeekBar reel = new SeekBar(RuntimeEnvironment.getApplication());
        reel.setPadding(0, 24, 0, 0);
        frame.addView(reel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 28, Gravity.BOTTOM));
        parent.addView(frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 45, Gravity.BOTTOM));
        return reel;
    }

    /**
     * #450: two bars sharing the view round them both need it to let them draw past. The first bar back
     * on standard leaves it open for the second, and it clips again once neither needs it.
     */
    @Test
    @Config(qualifiers = "xxhdpi")
    public void aViewTwoBarsShareClipsAgainOnlyOnceNeitherNeedsIt() {
        FrameLayout screen = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout host = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout firstFrame = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout secondFrame = new FrameLayout(RuntimeEnvironment.getApplication());
        SeekBar first = reelBar(host, firstFrame);
        SeekBar second = reelBar(host, secondFrame);
        screen.addView(host, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 600));
        lay(screen);

        SeekShapes.apply(first, SeekShape.WAVE, OverlayLook.SOLID, true);
        SeekShapes.apply(second, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertFalse(host.getClipChildren());

        SeekShapes.apply(first, SeekShape.STANDARD, OverlayLook.SOLID, true);
        assertTrue("the first bar's own frame clips again", firstFrame.getClipChildren());
        assertFalse("the second bar still needs the shared view", host.getClipChildren());
        assertFalse(secondFrame.getClipChildren());

        SeekShapes.apply(second, SeekShape.STANDARD, OverlayLook.SOLID, true);
        assertTrue("neither needs it now", host.getClipChildren());
        assertTrue(secondFrame.getClipChildren());
        assertTrue(screen.getClipChildren());
    }

    /**
     * #450: a bar dropped with its reel, never going back to standard, doesn't keep the view it shared
     * open: once the other bar is back on standard, that view clips again.
     */
    @Test
    @Config(qualifiers = "xxhdpi")
    public void aDroppedBarLeavesTheViewItSharedToClipAgain() {
        FrameLayout screen = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout host = new FrameLayout(RuntimeEnvironment.getApplication());
        SeekBar first = reelBar(host, new FrameLayout(RuntimeEnvironment.getApplication()));
        SeekBar second = reelBar(host, new FrameLayout(RuntimeEnvironment.getApplication()));
        screen.addView(host, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 600));
        lay(screen);
        SeekShapes.apply(first, SeekShape.WAVE, OverlayLook.SOLID, true);
        SeekShapes.apply(second, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertFalse(host.getClipChildren());

        WeakReference<SeekBar> dropped = new WeakReference<>(first);
        host.removeViewAt(0);
        first = null;
        for (int i = 0; i < 50 && dropped.get() != null; i++) {
            System.gc();
            System.runFinalization();
        }
        assertNull("the first reel's bar is gone", dropped.get());

        SeekShapes.apply(second, SeekShape.STANDARD, OverlayLook.SOLID, true);
        assertTrue("no bar needs the shared view now", host.getClipChildren());
    }

    /** #450: a view opened only for a bar that's gone since clips again once another bar makes its room. */
    @Test
    @Config(qualifiers = "xxhdpi")
    public void aViewOpenedOnlyForAGoneBarClipsAgain() {
        FrameLayout screen = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout host = new FrameLayout(RuntimeEnvironment.getApplication());
        SeekBar first = reelBar(host, new FrameLayout(RuntimeEnvironment.getApplication()));
        screen.addView(host, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 600));
        lay(screen);
        SeekShapes.apply(first, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertFalse(host.getClipChildren());

        WeakReference<SeekBar> dropped = new WeakReference<>(first);
        host.removeViewAt(0);
        first = null;
        for (int i = 0; i < 50 && dropped.get() != null; i++) {
            System.gc();
            System.runFinalization();
        }
        assertNull("the reel's bar is gone", dropped.get());

        FrameLayout otherScreen = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout otherHost = new FrameLayout(RuntimeEnvironment.getApplication());
        SeekBar next = reelBar(otherHost, new FrameLayout(RuntimeEnvironment.getApplication()));
        otherScreen.addView(otherHost, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 600));
        lay(otherScreen);
        SeekShapes.apply(next, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertTrue("no bar needs the old view now", host.getClipChildren());
        assertFalse("the new bar has its room", otherHost.getClipChildren());
    }

    /** #450: a view Instagram already lets draw past its edges is left that way when the bar goes back to standard. */
    @Test
    @Config(qualifiers = "xxhdpi")
    public void aViewInstagramLeftOpenStaysOpen() {
        FrameLayout screen = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout host = new FrameLayout(RuntimeEnvironment.getApplication());
        host.setClipChildren(false);
        SeekBar reel = reelBar(host, new FrameLayout(RuntimeEnvironment.getApplication()));
        screen.addView(host, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 600));
        lay(screen);

        SeekShapes.apply(reel, SeekShape.WAVE, OverlayLook.SOLID, true);
        SeekShapes.apply(reel, SeekShape.STANDARD, OverlayLook.SOLID, true);

        assertFalse("Instagram's own choice stands", host.getClipChildren());
    }

    /** #450: the walk stops before a view that scrolls, like the reels' pager, so its pages keep their edges. */
    @Test
    @Config(qualifiers = "xxhdpi")
    public void theWalkStopsBeforeAViewThatScrolls() {
        FrameLayout pager = new FrameLayout(RuntimeEnvironment.getApplication()) {
            @Override
            public boolean canScrollVertically(int direction) {
                return true;
            }
        };
        FrameLayout frame = new FrameLayout(RuntimeEnvironment.getApplication());
        SeekBar reel = reelBar(pager, frame);
        lay(pager);

        SeekShapes.apply(reel, SeekShape.WAVE, OverlayLook.SOLID, true);

        assertFalse("the bar's frame lets it draw past", frame.getClipChildren());
        assertTrue("the pager still clips its pages", pager.getClipChildren());
        assertTrue(pager.getClipToPadding());
    }

    /**
     * #450: a reels pager holding one reel can't scroll yet, so the walk opens it, and once the next reel
     * loads and it can, the next tick gives it its clipping back and keeps the bar's frame open.
     */
    @Test
    @Config(qualifiers = "xxhdpi")
    public void aPagerThatStartsScrollingLaterClipsAgain() {
        boolean[] more = {false};
        FrameLayout pager = new FrameLayout(RuntimeEnvironment.getApplication()) {
            @Override
            public boolean canScrollVertically(int direction) {
                return more[0] && direction > 0;
            }
        };
        FrameLayout frame = new FrameLayout(RuntimeEnvironment.getApplication());
        SeekBar reel = reelBar(pager, frame);
        lay(pager);

        SeekShapes.apply(reel, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertFalse("one reel: the pager doesn't scroll, so it lets the bar draw past", pager.getClipChildren());

        more[0] = true;
        SeekShapes.apply(reel, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertTrue("the next reel loaded: the pager clips its pages again", pager.getClipChildren());
        assertFalse("the bar's frame still lets it draw past", frame.getClipChildren());

        SeekShapes.apply(reel, SeekShape.WAVE, OverlayLook.SOLID, true);
        assertTrue("and stays that way", pager.getClipChildren());
        SeekShapes.apply(reel, SeekShape.STANDARD, OverlayLook.SOLID, true);
        assertTrue(frame.getClipChildren());
        assertTrue(pager.getClipChildren());
    }

    /** #450: a bar with the room for its shape leaves the views round it as they were. */
    @Test
    @Config(qualifiers = "xxhdpi")
    public void aBarWithRoomLeavesTheClippingAlone() {
        FrameLayout frame = new FrameLayout(RuntimeEnvironment.getApplication());
        SeekBar tall = new SeekBar(RuntimeEnvironment.getApplication());
        frame.addView(tall, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 96, Gravity.BOTTOM));
        lay(frame);
        for (SeekShape shape : new SeekShape[] {SeekShape.LINE, SeekShape.ROUNDED, SeekShape.EXPRESSIVE, SeekShape.WAVE}) {
            SeekShapes.apply(tall, shape, OverlayLook.SOLID, true);
            assertTrue(shape + " fits", frame.getClipChildren());
            SeekShapes.apply(tall, shape, OverlayLook.SOLID, false);
            assertTrue(shape + " fits in the middle", frame.getClipChildren());
        }
    }

    private static void lay(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 600);
    }

    @Test
    public void aPauseMeansStandard() {
        Settings.SEEK_BAR_SHAPE.save(SeekShape.WAVE);
        assertEquals(SeekShape.WAVE, SeekShapes.wanted());
        assertTrue(SeekShapes.changed());
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(SeekShape.STANDARD, SeekShapes.wanted());
    }
}
