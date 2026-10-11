/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.*;
import android.app.Activity;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.settings.OverrideDocumentsTest;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** The Recommended flags switches: what a build offers, where each stands, and the one change a choice makes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = OverrideImportTest.NativeTable.class)
public class RecommendedFlagsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final String STORE = "{\"500:cfg_a\":[\"1: second: false\"],\"800:cfg_d\":[\"0: other: true\"]}";
    private static final long FIRST = OverrideImportTest.NativeTable.id(1, 10), SECOND = OverrideImportTest.NativeTable.id(1, 11),
            GATE = OverrideImportTest.NativeTable.id(1, 13);
    private ActivityController<OverrideDocumentsTest.HostActivity> host;
    private Activity activity;
    /** On sets 500:0 and 500:1 true and 600:0 false. 700:0 is a string, 900:0 isn't in the build, 500:2 is a long. */
    private final RecommendedFlags.Flag mixed = new RecommendedFlags.Flag("Mixed", "Sets three", new int[]{500, 500, 600},
            new int[]{0, 1, 0}, new boolean[]{true, true, false});
    private final List<RecommendedFlags.Flag> candidates = Arrays.asList(
            mixed,
            new RecommendedFlags.Flag("Text", "A string", new int[]{700}, new int[]{0}, new boolean[]{true}),
            new RecommendedFlags.Flag("Gone", "Not in this build", new int[]{900}, new int[]{0}, new boolean[]{true}),
            new RecommendedFlags.Flag("Count", "First is a number", new int[]{500, 500}, new int[]{2, 0}, new boolean[]{true, true}));

    @Before public void prepare() throws Exception {
        OverrideImportTest.NativeTable.reset();
        OverrideImportTest.useTestTiming();
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        OverrideImportTest.install();
        host = Robolectric.buildActivity(OverrideDocumentsTest.HostActivity.class).setup();
        activity = host.get();
        OverrideImportTest.NativeTable.file = OverrideImportTest.store(activity, "mobileconfig");
        OverrideImportTest.NativeTable.schema = Arrays.asList(
                new OverrideExchange.Parameter(500, 0, "cfg_a", "first", 1, FIRST),
                new OverrideExchange.Parameter(500, 1, "cfg_a", "second", 1, SECOND),
                new OverrideExchange.Parameter(500, 2, "cfg_a", "count", 2, OverrideImportTest.NativeTable.id(2, 12)),
                new OverrideExchange.Parameter(600, 0, "cfg_b", "gate", 1, GATE),
                new OverrideExchange.Parameter(700, 0, "cfg_c", "word", 3, OverrideImportTest.NativeTable.id(3, 14)),
                new OverrideExchange.Parameter(800, 0, "cfg_d", "other", 1, OverrideImportTest.NativeTable.id(1, 15)));
        Files.write(OverrideImportTest.NativeTable.file.toPath(), STORE.getBytes(StandardCharsets.UTF_8));
    }

    @After public void close() {
        OverrideImportTest.restoreTiming();
        Settings.ALLOW_OVERRIDE_IMPORT.resetToDefault();
        if (host != null) host.close();
    }

    private RecommendedFlags.Page page() throws IOException {
        return RecommendedFlags.load(OverrideExchange.capture(activity), candidates);
    }

    private JSONObject file() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(OverrideImportTest.NativeTable.file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void onlyTheSwitchesWhoseFirstParameterIsABooleanHereAreOffered() throws Exception {
        RecommendedFlags.Page page = page();
        assertEquals(1, page.flags.size());
        assertSame(mixed, page.flags.get(0));
        // One of its three parameters is set, to the Off value, so it's neither On nor Off nor untouched.
        assertEquals(RecommendedFlags.Choice.OTHER, page.choices.get(0));
    }

    @Test public void turningASwitchOnChangesOnlyItsParametersAndLeavesTheRestAlone() throws Exception {
        OverrideImport.Result result = RecommendedFlags.set(activity, mixed, RecommendedFlags.Choice.ON);
        assertEquals(OverrideImport.Outcome.APPLIED, result.outcome);
        assertEquals(Arrays.asList("bool " + FIRST + " true", "bool " + SECOND + " true", "bool " + GATE + " false"),
                OverrideImportTest.NativeTable.log);
        JSONObject stored = file();
        assertEquals("[\"0: first: true\",\"1: second: true\"]", sorted(stored.getJSONArray("500:cfg_a")));
        assertEquals("[\"0: gate: false\"]", stored.getJSONArray("600:cfg_b").toString());
        assertEquals("[\"0: other: true\"]", stored.getJSONArray("800:cfg_d").toString());
        assertEquals(RecommendedFlags.Choice.ON, page().choices.get(0));
    }

    @Test public void offIsTheOppositeOfOnAndInstagramsOwnTakesTheOverridesAway() throws Exception {
        assertEquals(OverrideImport.Outcome.APPLIED, RecommendedFlags.set(activity, mixed, RecommendedFlags.Choice.OFF).outcome);
        assertEquals(RecommendedFlags.Choice.OFF, page().choices.get(0));
        assertEquals("[\"0: gate: true\"]", file().getJSONArray("600:cfg_b").toString());
        assertEquals(OverrideImport.Outcome.APPLIED, RecommendedFlags.set(activity, mixed, RecommendedFlags.Choice.DEFAULT).outcome);
        assertEquals(RecommendedFlags.Choice.DEFAULT, page().choices.get(0));
        JSONObject stored = file();
        assertFalse(stored.has("500:cfg_a"));
        assertFalse(stored.has("600:cfg_b"));
        assertEquals("[\"0: other: true\"]", stored.getJSONArray("800:cfg_d").toString());
    }

    @Test public void aChoiceSavesThePreviousOverridesFirstSoRestoreBringsThemBack() throws Exception {
        RecommendedFlags.set(activity, mixed, RecommendedFlags.Choice.ON);
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertEquals(RecommendedFlags.Choice.OTHER, page().choices.get(0));
        assertEquals("[\"1: second: false\"]", file().getJSONArray("500:cfg_a").toString());
    }

    @Test public void choosingTheStateItAlreadyHasMakesNoNativeWrite() throws Exception {
        RecommendedFlags.set(activity, mixed, RecommendedFlags.Choice.ON);
        OverrideImportTest.NativeTable.log.clear();
        assertEquals(OverrideImport.Outcome.UNCHANGED, RecommendedFlags.set(activity, mixed, RecommendedFlags.Choice.ON).outcome);
        assertTrue(OverrideImportTest.NativeTable.log.isEmpty());
    }

    @Test public void withImportingOffNothingIsReadOrWritten() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(false);
        assertThrows(OverrideImport.NotAllowed.class, () -> RecommendedFlags.set(activity, mixed, RecommendedFlags.Choice.ON));
        assertTrue(OverrideImportTest.NativeTable.log.isEmpty());
        assertTrue(file().has("500:cfg_a"));
        assertFalse(file().has("600:cfg_b"));
    }

    @Test public void aSwitchThisBuildDoesntOfferIsRefusedAndOtherIsNotAChoice() throws Exception {
        assertThrows(IOException.class, () -> RecommendedFlags.set(activity, candidates.get(1), RecommendedFlags.Choice.ON));
        assertThrows(IOException.class, () -> RecommendedFlags.set(activity, mixed, RecommendedFlags.Choice.OTHER));
        assertTrue(OverrideImportTest.NativeTable.log.isEmpty());
    }

    @Test public void theCatalogueLeadsWithTheStoryStyleKeepsOurNamesUniqueAndLeavesOutInstagramPlus() {
        List<RecommendedFlags.Flag> flags = RecommendedFlags.catalogue();
        assertEquals("85030::1", RecommendedFlags.codes(flags.get(0)).get(0));
        assertEquals("126445::0", RecommendedFlags.codes(flags.get(1)).get(0));
        assertEquals("119728::0", RecommendedFlags.codes(flags.get(2)).get(0));
        Set<String> titles = new HashSet<>(), codes = new HashSet<>();
        for (RecommendedFlags.Flag flag : flags) {
            assertTrue(flag.title, titles.add(flag.title));
            assertFalse(flag.description.isEmpty());
            for (String code : RecommendedFlags.codes(flag)) {
                assertTrue(code, codes.add(code));
                assertFalse("Instagram Plus fonts are a ban risk", code.startsWith("125004"));
            }
        }
        // The two reports with our own rows.
        assertTrue(codes.containsAll(Arrays.asList("91245::0", "98437::0", "111955::0", "122670::0", "121368::0")));
    }

    private static String sorted(JSONArray records) throws Exception {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) list.add(records.getString(i));
        java.util.Collections.sort(list);
        JSONArray out = new JSONArray();
        for (String record : list) out.put(record);
        return out.toString();
    }
}
