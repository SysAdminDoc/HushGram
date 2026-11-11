/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
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

    /** A canvas that notes each rounded rectangle drawn on it: left, top, right, bottom and both radii. */
    private static final class Recording extends Canvas {
        final List<float[]> rects = new ArrayList<>();
        final List<Paint> paints = new ArrayList<>();

        @Override
        public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
            rects.add(new float[] {rect.left, rect.top, rect.right, rect.bottom, rx, ry});
            paints.add(paint);
        }
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

    @Test
    public void aPauseMeansStandard() {
        Settings.SEEK_BAR_SHAPE.save(SeekShape.WAVE);
        assertEquals(SeekShape.WAVE, SeekShapes.wanted());
        assertTrue(SeekShapes.changed());
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(SeekShape.STANDARD, SeekShapes.wanted());
    }
}
