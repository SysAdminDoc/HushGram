/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

/** The drawing of each seek bar shape (#586), on a 100 by 32 dp track at density 2. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SeekShapeDrawableTest {
    private static final int PLAYED = 0xFFFFFFFF;
    private static final int REST = 0xFF808080;
    private static final int NOTHING = 0;
    private static final int HALF = SeekShapeDrawable.LEVEL_MAX / 2;

    private static SeekShapeDrawable drawable(SeekShape shape, boolean flush, int level) {
        SeekShapeDrawable drawable = new SeekShapeDrawable(shape, flush, 2f, PLAYED, REST);
        drawable.setBounds(0, 0, 200, 64);
        drawable.setLevel(level);
        return drawable;
    }

    private static Bitmap drawn(SeekShapeDrawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(200, 64, Bitmap.Config.ARGB_8888);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    @Test
    public void theLevelSetsHowFarThePlayedPartGoes() {
        assertEquals(0f, drawable(SeekShape.LINE, false, 0).playedEnd(), 0.01f);
        assertEquals(100f, drawable(SeekShape.LINE, false, HALF).playedEnd(), 0.01f);
        assertEquals(200f, drawable(SeekShape.LINE, false, SeekShapeDrawable.LEVEL_MAX).playedEnd(), 0.01f);
    }

    @Test
    public void thePlayedPartAndTheRestHaveTheirOwnColors() {
        Bitmap bitmap = drawn(drawable(SeekShape.ROUNDED, false, HALF));
        assertEquals(PLAYED, bitmap.getPixel(50, 32));
        assertEquals(REST, bitmap.getPixel(150, 32));
    }

    @Test
    public void aBarSitsInTheMiddleOrOnTheBottomEdge() {
        Bitmap middle = drawn(drawable(SeekShape.LINE, false, HALF));
        assertEquals(PLAYED, middle.getPixel(50, 32));
        assertEquals(NOTHING, middle.getPixel(50, 62));

        Bitmap edge = drawn(drawable(SeekShape.LINE, true, HALF));
        assertEquals(PLAYED, edge.getPixel(50, 62));
        assertEquals(NOTHING, edge.getPixel(50, 32));
    }

    /** The handle is 16 dp tall and 4 wide with 3 dp of air each side, and a dot marks the bar's end. */
    @Test
    public void expressiveSplitsTheBarWithATallHandle() {
        Bitmap bitmap = drawn(drawable(SeekShape.EXPRESSIVE, false, HALF));
        assertEquals("the bar before it", PLAYED, bitmap.getPixel(80, 32));
        assertEquals("the air before it", NOTHING, bitmap.getPixel(93, 32));
        assertEquals("the handle", PLAYED, bitmap.getPixel(100, 32));
        assertEquals("the handle is taller than the bar", PLAYED, bitmap.getPixel(100, 20));
        assertEquals("the bar isn't", NOTHING, bitmap.getPixel(80, 20));
        assertEquals("the air after it", NOTHING, bitmap.getPixel(107, 32));
        assertEquals("the bar after it", REST, bitmap.getPixel(120, 32));
        assertEquals("the end dot", PLAYED, bitmap.getPixel(194, 32));
    }

    /** One wave is 24 dp long and swings 3 dp each way around the middle. */
    @Test
    public void theWaveSwingsOnlyWhereItsPlayed() {
        Bitmap bitmap = drawn(drawable(SeekShape.WAVE, false, HALF));
        assertEquals("below the middle a quarter wave along", PLAYED, bitmap.getPixel(15, 38));
        assertEquals("so not on the middle there", NOTHING, bitmap.getPixel(15, 32));
        assertEquals("above it three quarters along", PLAYED, bitmap.getPixel(39, 26));
        assertEquals("the rest is a straight bar", REST, bitmap.getPixel(150, 32));
        assertEquals(NOTHING, bitmap.getPixel(150, 38));
    }

    /** On the edge a tall handle has to stay inside the track, so the middle is lifted by half of its held height. */
    @Test
    public void aTallHandleOnTheEdgeStaysInside() {
        Bitmap bitmap = drawn(drawable(SeekShape.WAVE, true, HALF));
        assertEquals("the handle reaches near the bottom", PLAYED, bitmap.getPixel(100, 58));
        assertEquals("and stops above it, with room to grow", NOTHING, bitmap.getPixel(100, 62));
    }

    @Test
    public void holdingSpringsThickerAndLettingGoEasesBack() {
        SeekShapeDrawable drawable = drawable(SeekShape.LINE, true, HALF);
        assertEquals(SeekShapeDrawable.LINE_DP * 2f, drawable.thickness(), 0.01f);

        drawable.setState(new int[] {android.R.attr.state_pressed});
        assertTrue(drawable.held());
        settle();
        assertEquals(1f, drawable.expansion(), 0.01f);
        assertEquals(SeekShapeDrawable.LINE_HELD_DP * 2f, drawable.thickness(), 0.01f);

        drawable.setState(new int[0]);
        assertFalse(drawable.held());
        settle();
        assertEquals(0f, drawable.expansion(), 0.01f);
        assertEquals(SeekShapeDrawable.LINE_DP * 2f, drawable.thickness(), 0.01f);
    }

    @Test
    public void itAsksForRoomButNotASizeOfItsOwn() {
        SeekShapeDrawable drawable = drawable(SeekShape.ROUNDED, false, 0);
        assertEquals("a SeekBar's track may grow to this", 64, drawable.getMinimumHeight());
        assertEquals("a bar that wraps its content measures as before", -1, drawable.getIntrinsicHeight());
    }

    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
    }

    /** A story segment where Instagram draws one 160 by 2 pixels around y 32 (#630), at density 2. */
    private static Bitmap segment(SeekShape shape, Paint paint) {
        Bitmap bitmap = Bitmap.createBitmap(200, 64, Bitmap.Config.ARGB_8888);
        RectF rect = new RectF(20, 31, 180, 33);
        SeekShapeDrawable.segment(new Canvas(bitmap), rect, paint, shape, 2f, new RectF(), new Path());
        assertEquals("Instagram's rectangle is left as it was", new RectF(20, 31, 180, 33), rect);
        return bitmap;
    }

    private static Paint paint(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        return paint;
    }

    /** #630: a story segment keeps its length and takes the shape's thickness around its middle, square ended for the line. */
    @Test
    public void aStorySegmentTakesTheShapesThickness() {
        Bitmap line = segment(SeekShape.LINE, paint(PLAYED));
        assertEquals("2 dp thick", PLAYED, line.getPixel(100, 30));
        assertEquals(PLAYED, line.getPixel(100, 33));
        assertEquals(NOTHING, line.getPixel(100, 28));
        assertEquals("a square end", PLAYED, line.getPixel(20, 30));
        assertEquals("as long as Instagram's", NOTHING, line.getPixel(18, 32));
        assertEquals(NOTHING, line.getPixel(181, 32));

        Bitmap rounded = segment(SeekShape.ROUNDED, paint(PLAYED));
        assertEquals("6 dp thick", PLAYED, rounded.getPixel(100, 27));
        assertEquals(PLAYED, rounded.getPixel(100, 36));
        assertEquals(NOTHING, rounded.getPixel(100, 24));
        assertEquals("a round end", NOTHING, rounded.getPixel(20, 26));
    }

    /** #630: the wave draws the played segment wavy, its waves counted from the screen's edge, and the rest flat. */
    @Test
    public void theWaveWavesThePlayedSegmentAndLaysTheRestFlat() {
        Paint played = paint(PLAYED);
        Bitmap wavy = segment(SeekShape.WAVE, played);
        assertEquals("a crest below the middle at x 108", PLAYED, wavy.getPixel(108, 38));
        assertEquals(NOTHING, wavy.getPixel(108, 27));
        assertEquals("a trough above it at x 84", PLAYED, wavy.getPixel(84, 26));
        assertEquals(NOTHING, wavy.getPixel(84, 37));
        assertEquals("the paint is handed back as it came", Paint.Style.FILL, played.getStyle());
        assertEquals(Paint.Cap.BUTT, played.getStrokeCap());
        assertEquals(0f, played.getStrokeWidth(), 0f);

        Bitmap flat = segment(SeekShape.WAVE, paint(0x80FFFFFF));
        assertEquals("the rest is a flat 3 dp bar", 0x80, Color.alpha(flat.getPixel(108, 32)));
        assertEquals(0x80, Color.alpha(flat.getPixel(84, 32)));
        assertEquals(NOTHING, flat.getPixel(108, 38));
    }
}
