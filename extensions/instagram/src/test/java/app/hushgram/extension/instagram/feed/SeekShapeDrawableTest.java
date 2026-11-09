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
}
