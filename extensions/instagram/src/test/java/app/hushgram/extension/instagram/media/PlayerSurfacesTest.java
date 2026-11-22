/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The Surface Instagram's player draws a SurfaceView's video into (#462, #583, #801): remembered from
 * the hook until it goes away, and copied as the part the view shows.
 */
@RunWith(RobolectricTestRunner.class)
public class PlayerSurfacesTest {
    @After
    public void tearDown() {
        PlayerSurfaces.resetForTests();
    }

    /** The player's Surface is the one read for its SurfaceView while it's valid, and forgotten when it goes or is replaced. */
    @Test
    public void theHookedSurfaceIsReadUntilItGoes() {
        SurfaceView view = new SurfaceView(RuntimeEnvironment.getApplication());
        Surface first = new Surface(new SurfaceTexture(0));
        Surface second = new Surface(new SurfaceTexture(1));

        assertNull("nothing handed over yet", PlayerSurfaces.player(view));
        PlayerSurfaces.drawsInto(view, first);
        assertSame(first, PlayerSurfaces.player(view));

        PlayerSurfaces.drawsInto(view, second);
        assertSame("a new surface for the same view replaces the old", second, PlayerSurfaces.player(view));

        second.release();
        assertNull("a released surface is no longer read", PlayerSurfaces.player(view));

        PlayerSurfaces.drawsInto(view, first);
        PlayerSurfaces.drawsInto(view, null);
        assertNull("a null surface forgets the view", PlayerSurfaces.player(view));

        PlayerSurfaces.drawsInto(null, first);
        first.release();
    }

    /**
     * The player covers the view with its frame, centered, so the view shows the frame's middle: a
     * 9:16 reel on a taller screen loses its sides, a frame the view's shape is whole, and a view with no
     * size yet gets the whole frame.
     */
    @Test
    public void theViewShowsTheMiddleOfTheFrame() {
        assertEquals(new Rect(97, 0, 983, 1920), PlayerSurfaces.shownPart(1080, 1920, 1080, 2340));
        assertEquals(new Rect(0, 0, 1080, 1920), PlayerSurfaces.shownPart(1080, 1920, 540, 960));
        assertEquals(new Rect(0, 60, 1080, 1860), PlayerSurfaces.shownPart(1080, 1920, 1080, 1800));
        assertEquals(new Rect(0, 0, 720, 1280), PlayerSurfaces.shownPart(720, 1280, 0, 0));
        assertEquals(new Rect(0, 0, 0, 0), PlayerSurfaces.shownPart(0, 0, 1080, 2340));
    }

    /** Only the middle third of a frame three times the view's width lands in the copy. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void theCopyIsThePartTheViewShows() {
        Bitmap frame = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < 300; x++) {
            int color = x < 100 ? Color.RED : x < 200 ? Color.GREEN : Color.BLUE;
            for (int y = 0; y < 100; y++) frame.setPixel(x, y, color);
        }
        Bitmap into = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);

        PlayerSurfaces.drawShown(frame, 100, 100, into);

        assertEquals(Color.GREEN, into.getPixel(1, 1));
        assertEquals(Color.GREEN, into.getPixel(10, 10));
        assertEquals(Color.GREEN, into.getPixel(18, 18));
    }
}
