/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.shared.SettingsContextRule;

/**
 * The hook state a report carries per family: how often Instagram reached a hook, what its
 * lookups found, and which lookups found nothing or more than one candidate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HookStatusTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    @After
    public void clear() {
        HookStatus.clear();
    }

    @Test
    public void aLineCountsRunsFindsAmbiguitiesAndMisses() {
        HookStatus.invoked("Reels");
        HookStatus.invoked("Reels");
        HookStatus.invoked("Reels");
        HookStatus.bound("Reels", "section item list");
        HookStatus.ambiguous("Reels", "field", "X.abc", "source", 2);
        HookStatus.missingMember("Reels", "field", "X.def", "items");

        // The miss that 'missing' counts is the one named, though the ambiguous lookup came first.
        assertEquals(Arrays.asList(
                "Reels: invoked 3, 1 found, 1 ambiguous, 1 missing. First missing: field X.def#items"),
                HookStatus.report());
        assertTrue("an ambiguous lookup is a finding a report has to carry", HookStatus.anyMissing());
    }

    /** With nothing missing, the lookup the line names is an ambiguous one, and it says so. */
    @Test
    public void aFamilyWhoseOnlyFindingIsAmbiguousDoesNotCallItMissing() {
        HookStatus.invoked("Download any reel");
        HookStatus.ambiguous("Download any reel", "field", "X.params", "com.facebook.video.engine.api.VideoDataSource", 2);

        assertEquals(Arrays.asList("Download any reel: invoked 1, 0 found, 1 ambiguous, 0 missing. First ambiguous: "
                        + "a single field X.params#com.facebook.video.engine.api.VideoDataSource (found 2)"),
                HookStatus.report());
    }

    /** A failed cast names its two types, so a report shows which anchor met the wrong object (#125). */
    @Test
    public void aFailedCastNamesItsTypesAndOtherFailuresKeepTheirMessageOut() {
        HookStatus.threw("Story", "story header",
                new ClassCastException("X.0Jrg cannot be cast to X.00uB"));
        HookStatus.threw("Clock", "tap clock", new IllegalStateException("someone's caption"));
        HookStatus.threw("Long", "long cast", new ClassCastException(repeat('a', HookStatus.MAX_CAST_MESSAGE + 50)));

        List<String> missing = HookStatus.missing("Story");
        assertEquals(Arrays.asList("a working 'story header' hook (it threw "
                + "java.lang.ClassCastException: X.0Jrg cannot be cast to X.00uB)"), missing);
        assertEquals(Arrays.asList("a working 'tap clock' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing("Clock"));
        assertEquals(Arrays.asList("a working 'long cast' hook (it threw java.lang.ClassCastException: "
                + repeat('a', HookStatus.MAX_CAST_MESSAGE) + "...)"), HookStatus.missing("Long"));
    }

    private static String repeat(char c, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(c);
        return text.toString();
    }

    /** A family that only ran has a line too, and a line with nothing wrong in it is no finding. */
    @Test
    public void aFamilyThatOnlyRanIsListedAndIsNoFinding() {
        HookStatus.invoked("Browser");
        assertEquals(Arrays.asList("Browser: invoked 1, 0 found, 0 missing"), HookStatus.report());
        assertEquals(false, HookStatus.anyMissing());
    }

    @Test
    public void anAmbiguityIsRecordedOnceAndKeptApartFromAMiss() {
        for (int i = 0; i < 5; i++) HookStatus.ambiguous("Reels", "field", "X.abc", "source", 2);
        HookStatus.missingMember("Reels", "field", "X.abc", "source");

        List<String> details = HookStatus.missing("Reels");
        assertEquals(details.toString(), 2, details.size());
        assertEquals("a single field X.abc#source (found 2)", details.get(0));
        assertEquals("field X.abc#source", details.get(1));
    }

    @Test
    public void runsCountedBeforeAClearComeBackAddedToTheOnesSince() {
        HookStatus.invoked("Feed");
        HookStatus.invoked("Feed");
        HookStatus.Snapshot before = HookStatus.snapshotAndClear();
        HookStatus.invoked("Feed");

        HookStatus.restore(before);
        assertEquals(Arrays.asList("Feed: invoked 3, 0 found, 0 missing"), HookStatus.report());
    }

    /**
     * What a hook decided, counted under a fixed name, goes on its family's line in the order it
     * was first counted. A family whose only runs are decisions still gets a line, which is what a
     * reel sidebar built without its button needs: the tap counter never runs there.
     */
    @Test
    public void countedDecisionsAreNamedOnTheFamilyLine() {
        HookStatus.counted("Download any reel", "UDD sidebar with Download");
        HookStatus.counted("Download any reel", "FbShorts sidebar");
        HookStatus.counted("Download any reel", "UDD sidebar with Download");

        assertEquals(Arrays.asList("Download any reel: invoked 0, 0 found, 0 missing. "
                        + "Counted: UDD sidebar with Download 2, FbShorts sidebar 1"),
                HookStatus.report());
        assertEquals("a count is no finding", false, HookStatus.anyMissing());
        assertEquals(Arrays.asList("Download any reel: invoked 0, 0 found, 0 missing. "
                        + "Counted: UDD sidebar with Download 2, FbShorts sidebar 1 (paused)"),
                HookStatus.report(" (paused)"));
    }

    /** A family keeps sixteen names at most, so a caller that passes varying text can't grow the report. */
    @Test
    public void aFamilyCountsSixteenNamesAtMost() {
        for (int i = 0; i < 40; i++) HookStatus.counted("Reels", "kind " + i);
        String line = HookStatus.report().get(0);
        assertTrue(line, line.contains("kind 15 1"));
        assertTrue(line, !line.contains("kind 16"));
    }

    @Test
    public void countsTakenBeforeAClearComeBackAddedToTheOnesSince() {
        HookStatus.counted("Reels", "shown");
        HookStatus.counted("Reels", "shown");
        HookStatus.Snapshot before = HookStatus.snapshotAndClear();
        HookStatus.counted("Reels", "off");
        HookStatus.counted("Reels", "shown");

        HookStatus.restore(before);
        assertEquals(Arrays.asList("Reels: invoked 0, 0 found, 0 missing. Counted: shown 3, off 1"),
                HookStatus.report());
    }

    /** Hooks call it before their own guard, so nothing it is handed may throw into the host. */
    @Test
    public void countingADecisionNeverThrows() {
        HookStatus.counted(null, "shown");
        HookStatus.counted("Reels", null);
        assertEquals(Arrays.asList(), HookStatus.report());
    }

    /** Hooks call it before their own guard, so nothing it is handed may throw into the host. */
    @Test
    public void countingARunNeverThrows() {
        HookStatus.invoked(null);
        HookStatus.invoked("");
        assertEquals(Arrays.asList(": invoked 1, 0 found, 0 missing"), HookStatus.report());
    }
}
