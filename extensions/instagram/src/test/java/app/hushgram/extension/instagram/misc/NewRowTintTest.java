/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** A dark theme's new row tint goes black and every other color stays (#374). */
@RunWith(RobolectricTestRunner.class)
public class NewRowTintTest {
    private static final long BLACK = 0xff000000L << 32;

    private static long compose(long argb) {
        return argb << 32;
    }

    /** Prism's dark tint is white at 15 percent, which reads #262626 over black. */
    @Test
    public void prismsDarkTintGoesBlack() {
        assertEquals(BLACK, NewRowTint.color(compose(0x26ffffffL)));
    }

    @Test
    public void theOlderDarkThemesGrayGoesBlack() {
        assertEquals(BLACK, NewRowTint.color(compose(0xff363636L)));
    }

    /** The light themes' blues, so light mode keeps its highlight. */
    @Test
    public void lightModesBluesStay() {
        for (long blue : new long[]{0xffe0f1ffL, 0xffeaf5ffL, 0xff0095f6L}) {
            assertEquals(compose(blue), NewRowTint.color(compose(blue)));
        }
    }

    @Test
    public void otherColorsStay() {
        for (long color : new long[]{0xffffffffL, 0x33ffffffL, 0xff262626L, 0L}) {
            assertEquals(compose(color), NewRowTint.color(compose(color)));
        }
    }

    /** The low half of a Compose color names its color space, so that isn't sRGB's and stays. */
    @Test
    public void aColorInAnotherSpaceStays() {
        long wide = compose(0x26ffffffL) | 5L;
        assertEquals(wide, NewRowTint.color(wide));
    }
}
