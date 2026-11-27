/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * #826: Instagram's Reels tab icon is a 72 pixel picture, soft when drawn bigger, so it's drawn as a
 * path of the same shape. The points checked come from Instagram's own pictures,
 * `instagram_reels_tall_ufi_outline_24` and `instagram_reels_tall_ufi_filled_24` on 457: a box 57 by 69
 * of the 72 units with a 2 dp line, and a play triangle whose line runs from 21 to 27 on the left and
 * reaches 49 at its tip.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SharpReelsIconTest {
    private static final String OWNER = "com.instagram.android";
    /** Drawn twice the pictures' size, two pixels to a unit. */
    private static final int SIZE = 144;

    private Resources resources;
    private Bitmap outline;
    private Bitmap filled;

    @Before
    public void prepare() {
        resources = RuntimeEnvironment.getApplication().getResources();
        outline = solid(72);
        filled = solid(72);
        SharpReelsIcon.picturesForTests(resources, outline, filled);
    }

    @After
    public void restore() {
        SharpReelsIcon.resetForTests();
    }

    private static Bitmap solid(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        return bitmap;
    }

    /** Instagram's Reels tab picture: the outline, or the filled one while selected, each in a layer. */
    private StateListDrawable reels() {
        StateListDrawable picture = new StateListDrawable();
        picture.addState(new int[]{android.R.attr.state_selected},
                new LayerDrawable(new Drawable[]{new BitmapDrawable(resources, filled)}));
        picture.addState(new int[0], new LayerDrawable(new Drawable[]{new BitmapDrawable(resources, outline)}));
        return picture;
    }

    private static Bitmap draw(Drawable picture) {
        Bitmap shot = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        picture.setBounds(0, 0, SIZE, SIZE);
        picture.draw(new Canvas(shot));
        return shot;
    }

    /** The pixel at ([x], [y]) on the pictures' 72 unit grid. */
    private static int at(Bitmap shot, float x, float y) {
        return shot.getPixel((int) (x * SIZE / 72f), (int) (y * SIZE / 72f));
    }

    private static void assertInk(String what, Bitmap shot, float x, float y, int color) {
        assertEquals(what, Integer.toHexString(color), Integer.toHexString(at(shot, x, y)));
    }

    private static void assertClear(String what, Bitmap shot, float x, float y) {
        assertEquals(what, 0, Color.alpha(at(shot, x, y)));
    }

    @Test
    public void tellsInstagramsTwoPicturesThroughWhatHoldsThem() {
        StateListDrawable picture = reels();
        assertEquals(SharpReelsIcon.OUTLINED, SharpReelsIcon.kind(picture, resources, OWNER));
        picture.setState(new int[]{android.R.attr.state_selected});
        assertEquals(SharpReelsIcon.SOLID, SharpReelsIcon.kind(picture, resources, OWNER));
        assertEquals("wrapped", SharpReelsIcon.SOLID,
                SharpReelsIcon.kind(new InsetDrawable(new BitmapDrawable(resources, filled), 0), resources, OWNER));
        assertEquals("a copy, by its pixels", SharpReelsIcon.OUTLINED,
                SharpReelsIcon.kind(new BitmapDrawable(resources, outline.copy(Bitmap.Config.ARGB_8888, false)), resources, OWNER));

        Bitmap other = solid(72);
        other.setPixel(0, 0, Color.BLACK);
        assertEquals("another picture", SharpReelsIcon.NONE,
                SharpReelsIcon.kind(new BitmapDrawable(resources, other), resources, OWNER));
        assertEquals("more than one picture", SharpReelsIcon.NONE, SharpReelsIcon.kind(new LayerDrawable(new Drawable[]{
                new BitmapDrawable(resources, outline), new BitmapDrawable(resources, filled)}), resources, OWNER));
        assertEquals("no picture", SharpReelsIcon.NONE, SharpReelsIcon.kind(new ColorDrawable(Color.WHITE), resources, OWNER));
        assertNull(SharpReelsIcon.shown(null, 0));
    }

    @Test
    public void anAppWithoutThePicturesFindsNeither() {
        SharpReelsIcon.resetForTests();
        assertEquals(SharpReelsIcon.NONE, SharpReelsIcon.kind(reels(), resources, OWNER));
    }

    @Test
    public void theOutlineIsDrawnAsInstagramsShapeInItsColor() {
        StateListDrawable picture = reels();
        picture.setTint(Color.GREEN);
        Bitmap shot = draw(new SharpReelsIcon(picture, resources, OWNER));

        assertInk("the box's left line", shot, 9f, 36f, Color.GREEN);
        assertInk("the box's top line", shot, 34.5f, 4.5f, Color.GREEN);
        assertInk("the box's right line", shot, 60f, 36f, Color.GREEN);
        assertClear("left of the box", shot, 3f, 36f);
        assertClear("past the box's rounded corner", shot, 8f, 4f);
        assertClear("inside the box", shot, 15f, 36f);
        assertInk("the play triangle's left line", shot, 24f, 36f, Color.GREEN);
        assertInk("the play triangle's tip", shot, 46f, 36f, Color.GREEN);
        assertClear("inside the play triangle", shot, 33f, 36f);
        assertClear("past the play triangle's tip", shot, 52f, 36f);
    }

    @Test
    public void selectedTheFilledBoxIsDrawnWithThePlayTriangleCutOut() {
        StateListDrawable picture = reels();
        SharpReelsIcon sharp = new SharpReelsIcon(picture, resources, OWNER);
        sharp.setState(new int[]{android.R.attr.state_selected});
        Bitmap shot = draw(sharp);

        assertInk("the box", shot, 15f, 36f, Color.WHITE);
        assertInk("the box's edge", shot, 9f, 36f, Color.WHITE);
        assertClear("the play triangle", shot, 33f, 36f);
        assertClear("left of the box", shot, 3f, 36f);
        assertClear("past the box's rounded corner", shot, 8f, 4f);

        sharp.setState(new int[0]);
        assertClear("unselected, the outline's inside again", draw(sharp), 15f, 36f);
    }

    @Test
    public void anyOtherPictureIsDrawnAsItIs() {
        Bitmap other = solid(72);
        other.setPixel(0, 0, Color.BLACK);
        BitmapDrawable picture = new BitmapDrawable(resources, other);
        SharpReelsIcon sharp = new SharpReelsIcon(picture, resources, OWNER);
        assertSame(picture, sharp.getDrawable());

        Bitmap shot = draw(sharp);

        assertInk("Instagram's own picture", shot, 15f, 36f, Color.WHITE);
        assertInk("all of it", shot, 33f, 36f, Color.WHITE);
        assertTrue(sharp.getIntrinsicWidth() > 0);
    }
}
