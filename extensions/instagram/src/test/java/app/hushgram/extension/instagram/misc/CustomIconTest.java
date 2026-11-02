/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;

/** The framing math and the size bound of the custom app icon (#442). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CustomIconTest {
    @Test
    public void aBigPictureIsDecodedUnderTheBound() {
        assertEquals(1, CustomIcon.sampleSize(1000, 800, CustomIcon.MAX_EDGE));
        assertEquals(1, CustomIcon.sampleSize(1536, 10, CustomIcon.MAX_EDGE));
        assertEquals(2, CustomIcon.sampleSize(3000, 2000, CustomIcon.MAX_EDGE));
        assertEquals(8, CustomIcon.sampleSize(12000, 9000, CustomIcon.MAX_EDGE));
        assertEquals(1, CustomIcon.sampleSize(0, 0, CustomIcon.MAX_EDGE));
        for (int edge : new int[] {1537, 4000, 9999, 40000}) {
            int sample = CustomIcon.sampleSize(edge, edge / 2, CustomIcon.MAX_EDGE);
            assertTrue(edge / sample <= CustomIcon.MAX_EDGE);
        }
    }

    @Test
    public void aStartingFrameCoversTheSquareAndIsCentered() {
        CustomIcon.Frame frame = new CustomIcon.Frame(400, 200, 100f);
        assertEquals(0.5f, frame.scale, 0.0001f);
        assertEquals(0f, frame.y, 0.0001f);
        assertEquals(-50f, frame.x, 0.0001f);
    }

    @Test
    public void panningNeverUncoversTheSquare() {
        CustomIcon.Frame frame = new CustomIcon.Frame(400, 200, 100f);
        frame.pan(500f, 500f);
        assertEquals(0f, frame.x, 0.0001f);
        assertEquals(0f, frame.y, 0.0001f);
        frame.pan(-5000f, -5000f);
        assertEquals(100f - 400 * 0.5f, frame.x, 0.0001f);
        assertEquals(0f, frame.y, 0.0001f);
    }

    @Test
    public void zoomStaysInRangeAndKeepsThePointUnderTheFingers() {
        CustomIcon.Frame frame = new CustomIcon.Frame(400, 400, 100f);
        frame.zoom(0.1f, 50f, 50f);
        assertEquals("never zooms out past covering", frame.minScale, frame.scale, 0.0001f);
        frame.zoom(100f, 50f, 50f);
        assertEquals(frame.minScale * CustomIcon.MAX_ZOOM, frame.scale, 0.0001f);
        CustomIcon.Frame other = new CustomIcon.Frame(400, 400, 100f);
        other.zoom(2f, 25f, 75f);
        float pictureX = (25f - other.x) / other.scale;
        float pictureY = (75f - other.y) / other.scale;
        assertEquals(100f, pictureX, 1.5f);
        assertEquals(300f, pictureY, 1.5f);
        assertTrue(other.x <= 0f && other.y <= 0f);
        assertTrue(other.x + 400 * other.scale >= 100f && other.y + 400 * other.scale >= 100f);
    }

    @Test
    public void theRenderIsASquareOfTheAskedSize() {
        Bitmap picture = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888);
        picture.eraseColor(Color.RED);
        CustomIcon.Frame frame = new CustomIcon.Frame(60, 30, 100f);
        Bitmap out = CustomIcon.render(picture, frame, 108);
        assertEquals(108, out.getWidth());
        assertEquals(108, out.getHeight());
        assertEquals(Color.RED, out.getPixel(54, 54));
        assertEquals(Color.RED, out.getPixel(2, 2));
    }

    @Test
    public void decodeShrinksAHugePictureAndRefusesJunk() throws Exception {
        Bitmap huge = Bitmap.createBitmap(4000, 3000, Bitmap.Config.RGB_565);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        huge.compress(Bitmap.CompressFormat.PNG, 100, png);
        java.io.File file = java.io.File.createTempFile("icon", ".png");
        try {
            java.nio.file.Files.write(file.toPath(), png.toByteArray());
            Bitmap got = CustomIcon.decode(RuntimeEnvironment.getApplication(), Uri.fromFile(file));
            assertTrue(got != null && Math.max(got.getWidth(), got.getHeight()) <= CustomIcon.MAX_EDGE);
        } finally {
            file.delete();
        }
        assertNull(CustomIcon.decode(RuntimeEnvironment.getApplication(), Uri.parse("file:///no/such/picture.png")));
    }
}
