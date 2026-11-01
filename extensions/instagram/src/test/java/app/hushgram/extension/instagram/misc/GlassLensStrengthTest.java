/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.*;

import org.junit.Test;

/** The refraction slider's percent turned into the bend the lens and the rim draw (#391). */
public class GlassLensStrengthTest {
    @Test public void thePercentScalesTheBendAndStaysInTheSlidersRange() {
        assertEquals(0f, GlassLens.scale(0), 0f);
        assertEquals(1f, GlassLens.scale(100), 0f);
        assertEquals(2f, GlassLens.scale(200), 0f);
        assertEquals("below the range", 0f, GlassLens.scale(-40), 0f);
        assertEquals("above the range", 2f, GlassLens.scale(900), 0f);
    }

    @Test public void theRimBandGrowsWithTheStrengthAndNeverPastItsCap() {
        assertEquals("no bend, no band", 0f, GlassLens.bandShare(0), 0f);
        assertEquals("standard is the band the rim has always had", GlassLens.RIM_BAND, GlassLens.bandShare(100), 0f);
        assertTrue(GlassLens.bandShare(150) > GlassLens.bandShare(100));
        assertTrue(GlassLens.bandShare(200) <= GlassLens.MAX_RIM_BAND);
    }
}
