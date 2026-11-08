/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The Instagram Plus card over a chat's hold menu goes black at night and keeps its fill by day (#543). */
@RunWith(RobolectricTestRunner.class)
public class PeekCardTest {
    private static final long BLACK = 0xff000000L << 32;

    private static long compose(int argb) {
        return ((long) argb) << 32;
    }

    @Test
    public void aDarkGrayGoesBlack() {
        for (int gray : new int[]{0xff2b3036, 0xff23262b, 0xff262626, 0xff000000}) {
            assertEquals(BLACK, PeekCard.color(compose(gray)));
        }
    }

    /** A see-through dark gray would still show the blur behind the card, so it goes solid black. */
    @Test
    public void aSeeThroughDarkGrayGoesSolidBlack() {
        for (int gray : new int[]{0xf22b3036, 0x80363636, 0x1a000000}) {
            assertEquals(BLACK, PeekCard.color(compose(gray)));
        }
    }

    @Test
    public void lightFillsStay() {
        for (int light : new int[]{0xffffffff, 0xf2ffffff, 0x26ffffff, 0xffefefef}) {
            assertEquals(compose(light), PeekCard.color(compose(light)));
        }
    }

    @Test
    public void aColoredFillStays() {
        for (int colored : new int[]{0xff3a0000, 0xff002b5c, 0xff4a6b82}) {
            assertEquals(compose(colored), PeekCard.color(compose(colored)));
        }
    }

    /** Nothing at all and a color outside sRGB, whose low half names its color space, stay as they are. */
    @Test
    public void clearAndOtherSpacesStay() {
        assertEquals(0L, PeekCard.color(0L));
        long displayP3 = compose(0xff2b3036) | 6;
        assertEquals(displayP3, PeekCard.color(displayP3));
    }
}
