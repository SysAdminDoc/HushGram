/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The colour and size choices Glass tab bar makes from Instagram's bar colour and the screen's width. */
@RunWith(RobolectricTestRunner.class)
public class GlassTabBarTest {
    @Test
    public void blackAndWhiteBarsReadAsDarkAndLight() {
        assertTrue(GlassTabBar.isDark(Color.BLACK));
        assertTrue(GlassTabBar.isDark(0xff0c1014));
        assertFalse(GlassTabBar.isDark(Color.WHITE));
    }

    @Test
    public void darkGlassIsTheBarsOwnDarkUnderHalfOverTheBlurAndLiftedWithout() {
        int blurred = GlassTabBar.tint(Color.BLACK, true);
        assertEquals(0x70, Color.alpha(blurred));
        assertEquals(0, Color.red(blurred));
        int plain = GlassTabBar.tint(Color.BLACK, false);
        assertEquals(0xe6, Color.alpha(plain));
        assertTrue(Color.red(plain) > 0);
    }

    @Test
    public void lightGlassKeepsItsColourAndIsMoreOpaqueWithoutBlur() {
        assertEquals(0xffffff, GlassTabBar.tint(Color.WHITE, true) & 0xffffff);
        assertTrue(Color.alpha(GlassTabBar.tint(Color.WHITE, false)) > Color.alpha(GlassTabBar.tint(Color.WHITE, true)));
    }

    @Test
    public void highlightAndEdgeAreWhiteOnDarkAndGreyOrBlackOnLight() {
        assertEquals(0xff, Color.red(GlassTabBar.highlight(Color.BLACK)));
        assertEquals(0x78, Color.red(GlassTabBar.highlight(Color.WHITE)));
        assertEquals(0xff, Color.red(GlassTabBar.edge(Color.BLACK)));
        assertEquals(0x00, Color.red(GlassTabBar.edge(Color.WHITE)));
    }

    /** A 411dp phone (1080px at 2.625) keeps 13% clear each side: a pill about 74% as wide as the screen. */
    @Test
    public void aPhoneKeepsThirteenPercentClearEachSide() {
        int outer = GlassTabBar.outerPx(1080, 2.625f);
        assertEquals(140, outer, 1);
        assertEquals(0.74f, (1080 - 2f * outer) / 1080f, 0.01f);
    }

    @Test
    public void aNarrowScreenKeepsAtLeastSixteenDp() {
        assertEquals(Math.round(16 * 3f), GlassTabBar.outerPx(200, 3f));
    }

    @Test
    public void aWideScreenNeverGrowsThePillPastFourHundredTwentyDp() {
        float density = 2f;
        int width = 2400;
        int outer = GlassTabBar.outerPx(width, density);
        assertEquals(420 * density, width - 2f * outer, 1f);
    }
}
