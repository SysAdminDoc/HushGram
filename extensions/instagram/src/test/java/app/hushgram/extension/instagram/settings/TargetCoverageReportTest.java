/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.shared.SettingsContextRule;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TargetCoverageReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void partialReportShowsCountsAndFixedMissingLabels() {
        String line = PatchFamily.coverageLine("1|2|3|alpha,beta,gamma|beta");
        assertTrue(line, line.contains("2/3 (partial); missing: beta"));
        assertTrue(line, line.contains("do not prove live endpoint suppression"));
        assertFalse(line, line.contains("http"));
    }

    @Test
    public void completeReportHasNoMissingTargets() {
        String line = PatchFamily.coverageLine("1|3|3|alpha,beta,gamma|");
        assertTrue(line, line.contains("3/3 (complete)"));
        assertFalse(line, line.contains("missing:"));
    }

    @Test
    public void invalidMetadataNeverClaimsCoverage() {
        for (String invalid : new String[]{"", "2|2|3|alpha,beta,gamma|beta", "1|0|3|alpha,beta,gamma|alpha,beta,gamma",
                "1|2|3|alpha,beta,gamma|unknown", "1|3|3|alpha,alpha,gamma|", "1|2|3|alpha,beta,gamma|beta,beta",
                "1|1|1|https://private/account|", "1|2|3|alpha,beta,gamma|", "1|4|3|alpha,beta,gamma|"}) {
            assertEquals(invalid, "patch target coverage unavailable", PatchFamily.coverageLine(invalid));
            assertEquals(invalid, "", PatchFamily.partialCoverageNote(invalid));
        }
    }

    /** Audit A03: an on switch whose build found only part of the patch says so on its own row. */
    @Test
    public void aPartialBuildSaysSoOnTheSwitchRow() {
        String summary = HushgramPreferenceFragment.withCoverage("Stops reports.",
                "1|5|7|builder,graph,mqtt,reports,pings,stream,setup|mqtt,pings");
        assertEquals("Stops reports. On this Instagram build it covers 5 of 7 routes. The diagnostic report lists the rest.",
                summary);
    }

    @Test
    public void aCompleteOrUnreadableBuildLeavesTheRowAlone() {
        assertEquals("Stops reports.", HushgramPreferenceFragment.withCoverage("Stops reports.",
                "1|7|7|builder,graph,mqtt,reports,pings,stream,setup|"));
        assertEquals("Stops reports.", HushgramPreferenceFragment.withCoverage("Stops reports.", ""));
    }
}
