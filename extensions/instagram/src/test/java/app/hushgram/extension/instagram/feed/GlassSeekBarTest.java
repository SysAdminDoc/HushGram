/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The seek bars' looks (#471): solid white as they always were, fainter when see-through, and white on a
 * capsule of glass when frosted, which takes a deeper shade where there's no blur to draw (before
 * Android 12, or on a software canvas as here).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37})
public class GlassSeekBarTest {
    private static final int WIDTH = 400, HEIGHT = 60, PAD = 40;

    private ActivityController<Activity> controller;
    private GlassSeekBar bar;
    private float density;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        bar = new GlassSeekBar(controller.get(), "Seek bar on feed videos", "test");
        density = bar.getResources().getDisplayMetrics().density;
        bar.setPadding(PAD, 0, PAD, 0);
        bar.setMax(100);
        bar.setProgress(50);
        bar.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, WIDTH, HEIGHT);
    }

    @After
    public void restore() {
        controller.pause().stop().destroy();
    }

    private Bitmap drawn() {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(bitmap));
        return bitmap;
    }

    private void assertColours(int progress, int thumb, int track) {
        assertEquals(progress, bar.getProgressTintList().getDefaultColor());
        assertEquals(thumb, bar.getThumbTintList().getDefaultColor());
        assertEquals(track, bar.getProgressBackgroundTintList().getDefaultColor());
    }

    @Test
    public void eachLookHasItsColours() {
        assertEquals("solid to start, as the bars always were", OverlayLook.SOLID, bar.look());
        assertColours(Color.WHITE, Color.WHITE, GlassSeekBar.SOLID_TRACK);

        bar.look(OverlayLook.SEE_THROUGH);
        assertEquals(OverlayLook.SEE_THROUGH, bar.look());
        assertColours(GlassSeekBar.SEE_THROUGH_PROGRESS, GlassSeekBar.SEE_THROUGH_THUMB, GlassSeekBar.SEE_THROUGH_TRACK);

        bar.look(OverlayLook.FROSTED);
        assertColours(Color.WHITE, Color.WHITE, GlassSeekBar.FROSTED_TRACK);

        bar.look(null);
        assertEquals("no choice keeps the look it has", OverlayLook.FROSTED, bar.look());

        bar.look(OverlayLook.SOLID);
        assertColours(Color.WHITE, Color.WHITE, GlassSeekBar.SOLID_TRACK);
    }

    @Test
    public void theCapsuleReachesPastTheTrackAndSitsInTheMiddle() {
        RectF capsule = bar.capsule();
        float reach = GlassSeekBar.CAPSULE_REACH_DP * density;
        assertEquals(PAD - reach, capsule.left, 0.01f);
        assertEquals(WIDTH - PAD + reach, capsule.right, 0.01f);
        assertEquals(Math.min(HEIGHT, GlassSeekBar.CAPSULE_DP * density), capsule.height(), 0.01f);
        assertEquals(HEIGHT / 2f, capsule.centerY(), 0.01f);
    }

    @Test
    public void onlyTheFrostedLookDrawsACapsuleAndWithNoBlurItTakesTheDeeperShade() {
        int x = Math.round(PAD - GlassSeekBar.CAPSULE_REACH_DP * density / 2f);
        int middle = HEIGHT / 2;
        assertEquals("a solid bar draws nothing past the end of its track", 0, Color.alpha(drawn().getPixel(x, middle)));
        bar.look(OverlayLook.SEE_THROUGH);
        assertEquals("nor does a see-through one", 0, Color.alpha(drawn().getPixel(x, middle)));

        bar.look(OverlayLook.FROSTED);
        Bitmap frosted = drawn();
        assertEquals(Color.alpha(GlassSeekBar.FROST_TINT_UNBLURRED), Color.alpha(frosted.getPixel(x, middle)), 2);
        assertEquals("the capsule stays round the track", 0, Color.alpha(frosted.getPixel(x, 1)));
        assertEquals(0, Color.alpha(frosted.getPixel(1, middle)));
    }
}
