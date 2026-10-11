/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class OverrideExchangeTest {
    private static final byte[] NATIVE = bytes("{\"123:config\":[\"0: enabled: true\",\"1: count: -9223372036854775808\",\"2: text: left: right\\nnext\",\"3: ratio: 1.25\",\"4: nullable: __NULL_VALUE__\"]}");

    private static java.util.List<OverrideExchange.Parameter> schema() {
        return Arrays.asList(parameter(0, "enabled", 1), parameter(1, "count", 2), parameter(2, "text", 3),
                parameter(3, "ratio", 4), parameter(4, "nullable", 3));
    }

    private static OverrideExchange.Parameter parameter(int index, String name, int type) {
        return new OverrideExchange.Parameter(123, index, "config", name, type, 1000 + index);
    }

    private static OverrideExchange.Snapshot snapshot(byte[] nativeBytes) throws IOException {
        return new OverrideExchange.Snapshot("449.0.0.52.84", 385511871, schema(), nativeBytes);
    }

    @Test public void roundTripPreservesAllNativeTypesNullAndLiteralStringDelimiters() throws Exception {
        OverrideExchange.Snapshot current = snapshot(NATIVE);
        byte[] file = OverrideExchange.export(current);
        JSONObject root = new JSONObject(new String(file, StandardCharsets.UTF_8));
        assertEquals(1, root.getInt("format"));
        assertEquals("com.instagram.android", root.getJSONObject("host").getString("package"));
        assertEquals(385511871, root.getJSONObject("host").getLong("code"));
        assertEquals(5, root.getJSONObject("schema").getInt("parameters"));
        assertEquals(new JSONObject(new String(NATIVE, StandardCharsets.UTF_8)).toString(), root.getJSONObject("overrides").toString());
        assertEquals(5, OverrideExchange.validate(file, current).fits);
        assertEquals(5, root.length());
        assertEquals(3, root.getJSONObject("host").length());
    }

    @Test public void schemaIdentityDoesNotDependOnNativeIterationOrderButBindsEveryRole() throws Exception {
        java.util.List<OverrideExchange.Parameter> reversed = new java.util.ArrayList<>(schema());
        Collections.reverse(reversed);
        OverrideExchange.Snapshot current = snapshot(NATIVE);
        byte[] file = OverrideExchange.export(current);
        assertEquals(5, OverrideExchange.validate(file, new OverrideExchange.Snapshot("449.0.0.52.84", 385511871, reversed, NATIVE)).fits);
        reversed.set(0, new OverrideExchange.Parameter(123, 4, "config", "nullable", 3, 99999));
        refused(() -> OverrideExchange.validate(file, new OverrideExchange.Snapshot("449.0.0.52.84", 385511871, reversed, NATIVE)));
    }

    @Test public void malformedDuplicateAndOversizedNativeRecordsRefuseBeforeExport() throws Exception {
        String[] inputs = {
                "{\"123:config\":[],\"123:config\":[]}",
                "{\"123:config\":[\"0: enabled: true\",\"0: enabled: false\"]}",
                "{\"999:\":[\"0: : true\",\"0: : false\"]}",
                "{\"123:config\":[true]}", "{\"123:config\":true}", "{\"999:\":[true]}", "{\"999:\":true}",
                "{\"0:config\":[]}", "{\"123:config\":[\"16384: enabled: true\"]}",
                "{\"123:config\":[\"-1: enabled: true\"]}", "{\"123:config\":[\"0: enabled\"]}", "{\"999:\":[\"0: true\"]}", "{} {}"
        };
        for (String input : inputs) refused(() -> snapshot(bytes(input)));
        refused(() -> snapshot(new byte[OverrideExchange.MAX_BYTES + 1]));
        refused(() -> snapshot(new byte[]{(byte) 0xc3, 0x28}));
    }

    /**
     * App data outlives an update, so the store can hold overrides this build has no parameter for,
     * or types otherwise. They're counted and stay out of the export and the values, and the rest
     * reads as it did.
     */
    @Test public void aStoreHoldingOverridesThisBuildLacksStillCapturesWithoutThem() throws Exception {
        String[] leftovers = {
                "{\"123:other\":[\"0: enabled: true\"]}", "{\"123:config\":[\"0: unknown: true\"]}",
                "{\"123:config\":[\"0: enabled: 1\"]}", "{\"123:config\":[\"1: count: 9223372036854775808\"]}",
                "{\"123:config\":[\"3: ratio: NaN\"]}", "{\"123:config\":[\"3: ratio: 1e9999\"]}",
                "{\"123:config\":[\"9: : true\"]}", "{\"999:\":[\"0: : true\"]}", "{\"1048576:config\":[\"0: : true\"]}",
        };
        for (String store : leftovers) {
            OverrideExchange.Snapshot held = snapshot(bytes(store));
            assertEquals(store, 1, held.leftOut());
            JSONObject exported = new JSONObject(new String(OverrideExchange.export(held), StandardCharsets.UTF_8));
            assertEquals(store, 0, exported.getJSONObject("overrides").length());
            assertTrue(store, OverrideExchange.values(bytes(store), held).isEmpty());
            assertEquals(store, 0, OverrideExchange.validate(OverrideExchange.export(held), held).fits);
        }
        byte[] mixed = bytes("{\"123:\":[\"0: : true\",\"9: : true\",\"1: : x\"],\"999:\":[\"0: : true\"],"
                + "\"456:\":[],\"_qe_overrides_\":[\"kept as it is\"]}");
        OverrideExchange.Snapshot current = snapshot(mixed);
        assertEquals(3, current.leftOut());
        byte[] export = OverrideExchange.export(current);
        assertEquals(new JSONObject("{\"123:\":[\"0: : true\"],\"_qe_overrides_\":[\"kept as it is\"]}").toString(),
                new JSONObject(new String(export, StandardCharsets.UTF_8)).getJSONObject("overrides").toString());
        assertEquals(1, OverrideExchange.validate(export, current).fits);
        assertEquals(Collections.singletonMap(OverrideExchange.key(123, 0), "true"), OverrideExchange.values(mixed, current));
        assertEquals(0, snapshot(NATIVE).leftOut());
    }

    /**
     * A config this build doesn't know with nothing under it (Instagram leaves an empty list when a
     * config's last override goes, and an update can drop the config) counts as nothing left out,
     * yet it isn't this build's to carry: the export leaves it out, and the document a change builds
     * from the snapshot still passes the strict check an import runs. Before, the snapshot kept the
     * store's text whole whenever nothing was counted, so Export wrote a file Import refused and
     * every Recommended flags change failed on such a store.
     */
    @Test public void anUnknownConfigWithNothingUnderItStaysOutOfTheSnapshot() throws Exception {
        String[] stores = {"{\"999:\":[]}", "{\"123:other\":[]}", "{\"123:config\":[\"0: enabled: true\"],\"999:\":[]}"};
        for (String store : stores) {
            OverrideExchange.Snapshot held = snapshot(bytes(store));
            assertEquals(store, 0, held.leftOut());
            JSONObject exported = new JSONObject(new String(OverrideExchange.export(held), StandardCharsets.UTF_8));
            assertTrue(store, !exported.getJSONObject("overrides").has("999:") && !exported.getJSONObject("overrides").has("123:other"));
            int fits = store.contains("enabled") ? 1 : 0;
            assertEquals(store, fits, OverrideExchange.validate(OverrideExchange.export(held), held).fits);
            byte[] changed = OverrideExchange.withOverrides(held, Collections.singletonMap(OverrideExchange.key(123, 0), "false"));
            assertEquals(store, 1, OverrideExchange.validate(changed, held).fits);
        }
    }

    @Test public void exactHostSchemaAndEnvelopeAreRequiredDuringValidation() throws Exception {
        OverrideExchange.Snapshot current = snapshot(NATIVE);
        byte[] file = OverrideExchange.export(current);
        refused(() -> OverrideExchange.validate(file, new OverrideExchange.Snapshot("450", 385511871, schema(), NATIVE)));
        refused(() -> OverrideExchange.validate(file, new OverrideExchange.Snapshot("449.0.0.52.84", 385511872, schema(), NATIVE)));
        JSONObject root = new JSONObject(new String(file, StandardCharsets.UTF_8));
        root.getJSONObject("host").put("account", "unwanted");
        final byte[] extra = bytes(root.toString());
        refused(() -> OverrideExchange.validate(extra, current));
        root = new JSONObject(new String(file, StandardCharsets.UTF_8));
        root.getJSONObject("overrides").getJSONArray("123:config").put("0: enabled: false");
        final byte[] duplicate = bytes(root.toString());
        refused(() -> OverrideExchange.validate(duplicate, current));
        String duplicateFormat = new String(file, StandardCharsets.UTF_8).replace("\"format\":1", "\"format\":1,\"format\":1");
        refused(() -> OverrideExchange.validate(bytes(duplicateFormat), current));
        assertArrayEquals(file, OverrideExchange.export(current));
    }

    @Test public void changedValidOverridesAreValidatedOnlyWithoutChangingTheSnapshot() throws Exception {
        OverrideExchange.Snapshot current = snapshot(NATIVE);
        byte[] before = OverrideExchange.export(current);
        JSONObject root = new JSONObject(new String(OverrideExchange.export(current), StandardCharsets.UTF_8));
        root.put("overrides", new JSONObject().put("123:config", new JSONArray().put("0: enabled: false")));
        assertEquals(1, OverrideExchange.validate(bytes(root.toString()), current).fits);
        assertArrayEquals(before, OverrideExchange.export(current));
    }

    @Test public void duplicateSchemaAndUnusableNamesOrTypesFailClosed() throws Exception {
        for (java.util.List<OverrideExchange.Parameter> entries : Arrays.asList(
                Arrays.asList(parameter(0, "enabled", 1), parameter(0, "enabled", 1)),
                Collections.singletonList(parameter(0, "enabled", 9)),
                Collections.singletonList(new OverrideExchange.Parameter(123, 0, "wrong:name", "enabled", 1, 1000)))) {
            refused(() -> new OverrideExchange.Snapshot("449", 1, entries, bytes("{}")));
        }
    }

    /** Instagram's own file: no envelope, empty names, an experiment section. */
    @Test public void instagramsOwnFileIsHeldToTheSchemaByIndexGivenNamesAndType() throws Exception {
        OverrideExchange.Snapshot current = snapshot(NATIVE);
        checked(3, 0, "{\"123:\":[\"0: : false\",\"1: : 7\",\"2: text: a: b\"],\"_qe_overrides_\":[]}", current);
        checked(1, 0, "{\"123:config\":[\"3: : 0.5\"]}", current);
        checked(0, 0, "{}", current);
        String[] refusedFiles = {
                "{\"_qe_overrides_\":[\"7: : true\"]}", "{\"_qe_overrides_\":{}}", "{\"other\":[]}", "{\"123:\":true}",
                "{\"123:\":[\"0: : true\",\"0: : false\"]}", "{\"999:\":[\"0: : true\",\"0: : true\"]}", "{\"999:\":[true]}",
                "{\"999:\":[\"0: true\"]}", "{\"123:\":[\"16384: : true\"]}",
        };
        for (String file : refusedFiles) refused(() -> OverrideExchange.validate(bytes(file), current));
    }

    /**
     * Instagram keeps its file across app updates, so one from an older build can hold configs,
     * parameters or types this build doesn't have. Those are left out and counted, and only the
     * rest is collected. A HushGram export names its build, so nothing in one is left out.
     */
    @Test public void anInstagramFileFromAnotherBuildLeavesOutWhatThisBuildDoesntHave() throws Exception {
        OverrideExchange.Snapshot current = snapshot(NATIVE);
        String file = "{\"123:\":[\"0: : false\",\"9: : true\",\"1: : 1.5\",\"2: renamed: x\"],\"999:\":[\"0: : true\"],"
                + "\"123:other\":[\"3: : 0.5\"],\"_qe_overrides_\":[]}";
        checked(1, 5, file, current);
        java.util.Map<Long, String> values = new java.util.TreeMap<>();
        OverrideExchange.validated(bytes(file), current, values);
        assertEquals(Collections.singletonMap(OverrideExchange.key(123, 0), "false"), values);
        for (String nothing : new String[] {"{\"123:other\":[\"0: : true\"]}", "{\"123:\":[\"0: unknown: true\"]}",
                "{\"123:\":[\"0: : 1\"]}", "{\"123:\":[\"9: : true\"]}", "{\"999:\":[\"0: : true\"]}"}) {
            assertThrows(nothing, OverrideExchange.NothingFits.class, () -> OverrideExchange.validate(bytes(nothing), current));
        }
        JSONObject root = new JSONObject(new String(OverrideExchange.export(current), StandardCharsets.UTF_8));
        root.put("overrides", new JSONObject(file));
        final byte[] export = bytes(root.toString());
        refused(() -> OverrideExchange.validate(export, current));
    }

    /**
     * Instagram writes its store with empty names and may keep experiment overrides beside them. An
     * import never changes those, so a document may carry the store's own, as its export and a
     * restore point do, but no others.
     */
    @Test public void aStoreWithEmptyNamesAndExperimentsStillCaptures() throws Exception {
        byte[] store = bytes("{\"123:\":[\"0: : true\"],\"_qe_overrides_\":[\"kept as it is\"]}");
        OverrideExchange.Snapshot current = snapshot(store);
        assertEquals(1, OverrideExchange.validate(bytes("{\"123:\":[\"0: : true\"]}"), current).fits);
        assertEquals(1, OverrideExchange.validate(OverrideExchange.export(current), current).fits);
        assertEquals(1, OverrideExchange.validate(store, current).fits);
        refused(() -> OverrideExchange.validate(bytes("{\"123:\":[\"0: : true\"],\"_qe_overrides_\":[\"another\"]}"), current));
    }

    @Test public void overridesFilesAreToldApartFromEverythingElse() throws Exception {
        assertTrue(OverrideExchange.isOverridesFile(OverrideExchange.export(snapshot(NATIVE))));
        assertTrue(OverrideExchange.isOverridesFile(NATIVE));
        assertTrue(OverrideExchange.isOverridesFile(bytes("{\"70831:\":[\"15: : true\"],\"_qe_overrides_\":[]}")));
        for (String other : new String[] {"{\"project\":\"HushGram\",\"schema\":1,\"settings\":{}}", "{}",
                "{\"_qe_overrides_\":[]}", "{\"70831:\":[],\"hushgram_hide_ads\":[]}", "{\"70831:\":{}}", "[]", "not json"}) {
            assertFalse(other, OverrideExchange.isOverridesFile(bytes(other)));
        }
        assertFalse(OverrideExchange.isOverridesFile(null));
    }

    @Test public void readsAreBoundedAndZeroProgressCannotLoop() throws Exception {
        assertArrayEquals(NATIVE, OverrideExchange.read(new ByteArrayInputStream(NATIVE)));
        refused(() -> OverrideExchange.read(new ByteArrayInputStream(new byte[OverrideExchange.MAX_BYTES + 1])));
        refused(() -> OverrideExchange.read(new ByteArrayInputStream(NATIVE) {
            @Override public int read(byte[] buffer, int start, int count) { return 0; }
        }));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void checked(int fits, int leftOut, String file, OverrideExchange.Snapshot snapshot) throws IOException {
        OverrideExchange.Checked checked = OverrideExchange.validate(bytes(file), snapshot);
        assertEquals(file, fits, checked.fits);
        assertEquals(file, leftOut, checked.leftOut);
    }
    private interface Work { void run() throws Exception; }
    private static void refused(Work work) throws Exception {
        try { work.run(); fail("input accepted"); }
        catch (IOException expected) { assertEquals("Invalid or unavailable native overrides", expected.getMessage()); }
    }
}
