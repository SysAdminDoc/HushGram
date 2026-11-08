/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** A note's bubble goes black at night and keeps its fill by day (#543). */
@RunWith(RobolectricTestRunner.class)
public class NoteBubbleTest {
    private static final int BLACK = 0xff000000;

    /** Prism's gray 08, the night value of notes_bubble_background on 454. */
    @Test
    public void theNightGrayGoesBlack() {
        assertEquals(BLACK, NoteBubble.color(0xff2b3036));
    }

    @Test
    public void otherDarkGraysGoBlackToo() {
        for (int gray : new int[]{0xff262626, 0xff363636, 0xff1c1e21, BLACK}) {
            assertEquals(BLACK, NoteBubble.color(gray));
        }
    }

    /** The day theme's white and the lighter grays stay, so a light inbox looks the same. */
    @Test
    public void lightFillsStay() {
        for (int light : new int[]{0xffffffff, 0xffefefef, 0xff8e8e8e, 0xff2b30ff}) {
            assertEquals(light, NoteBubble.color(light));
        }
    }

    /** A note's own dark color isn't the theme's gray, so it keeps it. */
    @Test
    public void aDarkColorStays() {
        for (int dark : new int[]{0xff3a0000, 0xff002b5c, 0xff1a3d1a}) {
            assertEquals(dark, NoteBubble.color(dark));
        }
    }

    /** A see-through fill shows what's under it, so it stays as it is. */
    @Test
    public void aSeeThroughFillStays() {
        for (int clear : new int[]{0x26ffffff, 0x802b3036, 0}) {
            assertEquals(clear, NoteBubble.color(clear));
        }
    }

    /** The inbox's Compose notes row on 454 fills a note with Prism's gray 08, which goes black. */
    @Test
    public void theInboxRowsGrayGoesBlack() {
        assertEquals(compose(BLACK), NoteBubble.card(compose(0xff2b3036)));
        assertEquals(compose(BLACK), NoteBubble.card(compose(0xff262626)));
    }

    /** The day theme's white, a note's own color and a see-through fill come back as they went in. */
    @Test
    public void theInboxRowKeepsEveryOtherFill() {
        for (int kept : new int[]{0xffffffff, 0xffefefef, 0xff3a0000, 0xff002b5c, 0x802b3036, 0}) {
            assertEquals(compose(kept), NoteBubble.card(compose(kept)));
        }
    }

    /** A color outside sRGB carries its space in the low half, and stays as it is. */
    @Test
    public void aColorOutsideSrgbStays() {
        long wide = compose(0xff2b3036) | 0x5L;
        assertEquals(wide, NoteBubble.card(wide));
    }

    private static long compose(int argb) {
        return ((long) argb) << 32;
    }
}
