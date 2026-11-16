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

    /**
     * #705: with no finger the lens bends as it always has, a held finger bends it harder, and however hard,
     * centre and rim together never pull the picture past {@link GlassLens#MAX_PULL}, so it can't turn inside out.
     */
    @Test public void aHeldFingerBendsTheLensHarderWithinItsCap() {
        for (int percent : new int[]{0, 50, 100, 150, 200}) {
            float bend = GlassLens.scale(percent);
            assertEquals(percent + "%", GlassLens.CENTRE_MAGNIFY * bend, GlassLens.centre(bend, 1f), 0f);
            assertEquals(percent + "%", GlassLens.RIM_BEND * bend, GlassLens.rimBend(bend, 1f), 0f);
            float full = 1f + GlassLens.HOLD_BEND;
            if (percent > 0) {
                assertTrue(percent + "% held bends harder",
                        GlassLens.centre(bend, full) + GlassLens.rimBend(bend, full)
                                > GlassLens.centre(bend, 1f) + GlassLens.rimBend(bend, 1f));
            }
            for (float boost : new float[]{1f, 1.5f, full, 10f}) {
                float pull = GlassLens.centre(bend, boost) + GlassLens.rimBend(bend, boost);
                assertTrue(percent + "% at " + boost + ": " + pull, pull <= GlassLens.MAX_PULL + 1e-6f);
                assertTrue(GlassLens.rimBend(bend, boost) >= 0f);
            }
        }
    }
}
