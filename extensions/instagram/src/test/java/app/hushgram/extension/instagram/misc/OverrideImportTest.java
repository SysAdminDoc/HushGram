/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.system.OsConstants;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import app.hushgram.extension.instagram.settings.OverrideDocumentsTest;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = OverrideImportTest.NativeTable.class)
public class OverrideImportTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    public static final byte[] NATIVE = ("{\"123:config\":[\"0: enabled: true\",\"1: limit: 5\"],"
            + "\"456:other\":[\"1: nullable: __NULL_VALUE__\"]}").getBytes(StandardCharsets.UTF_8);
    /** Every directory sync, as the sorted file names the directory held at that moment. */
    public static final List<List<String>> syncs = new ArrayList<>();
    public static IOException syncFailure;
    public interface StorageObserver { void reached(String boundary, File file) throws IOException; }
    public static StorageObserver storageObserver;
    public static final List<String> boundaries = new ArrayList<>();
    private ActivityController<OverrideDocumentsTest.HostActivity> host;
    private Activity activity;

    @Before public void prepare() throws Exception {
        NativeTable.reset();
        useTestTiming();
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        install();
        host = Robolectric.buildActivity(OverrideDocumentsTest.HostActivity.class).setup();
        activity = host.get();
        NativeTable.file = store("mobileconfig");
    }

    @After public void close() {
        restoreTiming();
        Settings.ALLOW_OVERRIDE_IMPORT.resetToDefault();
        if (host != null) host.close();
    }

    /** A short settle window, and directory syncs recorded instead of reaching Os, which isn't native here. */
    public static void useTestTiming() {
        OverrideImport.settleMillis = 200;
        syncs.clear();
        syncFailure = null;
        storageObserver = null;
        boundaries.clear();
        OverrideImport.directorySync = new OverrideImport.DirectorySync() {
            @Override public void sync(File directory) throws IOException {
                String[] names = directory.list();
                List<String> listed = names == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(names));
                java.util.Collections.sort(listed);
                syncs.add(listed);
                if (syncFailure != null) throw syncFailure;
            }
            @Override public void reached(String boundary, File file) throws IOException {
                boundaries.add(boundary + ":" + file.getName());
                if (storageObserver != null) storageObserver.reached(boundary, file);
            }
        };
    }

    public static void restoreTiming() {
        OverrideImport.settleMillis = 3000;
        OverrideImport.directorySync = OverrideImport::syncDirectory;
        syncFailure = null;
        storageObserver = null;
    }

    private static byte[] selectedMarker(String selected, byte[] document) throws Exception {
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(document)) {
            digest.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return ("1\n385511871\n" + selected + "\n" + digest).getBytes(StandardCharsets.UTF_8);
    }

    public static void install() {
        PackageInfo info = new PackageInfo();
        info.packageName = "com.instagram.android";
        info.versionName = "449.0.0.52.84";
        info.setLongVersionCode(385511871);
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = info.packageName;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(info);
    }

    /** A session store under the host's files, holding NATIVE, the way the resolver names it. */
    public static File store(Activity activity, String name) throws IOException {
        File file = new File(activity.getFilesDir(), name + "/session-store/mc_overrides.json");
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        Files.write(file.toPath(), NATIVE);
        return file;
    }
    private File store(String name) throws IOException { return store(activity, name); }

    /** The saved copy, pending copy, replaced copy or marker HushGram keeps for the current store. */
    public static File saved(Activity activity, String suffix) throws Exception {
        StringBuilder name = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(
                NativeTable.file.getCanonicalPath().getBytes(StandardCharsets.UTF_8))) {
            name.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return new File(new File(activity.getFilesDir(), OverrideImport.DIRECTORY), name + suffix);
    }
    private File saved(String suffix) throws Exception { return saved(activity, suffix); }

    private byte[] exported() throws Exception { return OverrideExchange.export(OverrideExchange.capture(activity)); }
    private byte[] document(String... records) throws Exception {
        JSONObject file = new JSONObject(new String(exported(), StandardCharsets.UTF_8));
        JSONObject overrides = new JSONObject();
        for (String record : records) {
            String label = record.substring(0, record.indexOf('|'));
            JSONArray list = overrides.optJSONArray(label);
            if (list == null) overrides.put(label, list = new JSONArray());
            list.put(record.substring(record.indexOf('|') + 1));
        }
        file.put("overrides", overrides);
        NativeTable.captures = 0;
        return file.toString().getBytes(StandardCharsets.UTF_8);
    }
    public static Map<String, String> semantic(File file) throws Exception {
        Map<String, String> values = new TreeMap<>();
        if (!file.exists()) return values;
        JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        for (java.util.Iterator<String> labels = root.keys(); labels.hasNext();) {
            String label = labels.next();
            JSONArray records = root.getJSONArray(label);
            for (int i = 0; i < records.length(); i++) {
                String[] parts = records.getString(i).split(": ", 3);
                values.put(label + "/" + parts[0] + "/" + parts[1], parts[2]);
            }
        }
        return values;
    }
    public static Map<String, String> original() {
        Map<String, String> values = new TreeMap<>();
        values.put("123:config/0/enabled", "true");
        values.put("123:config/1/limit", "5");
        values.put("456:other/1/nullable", "__NULL_VALUE__");
        return values;
    }
    private byte[] bytes(String suffix) throws Exception { return Files.readAllBytes(saved(suffix).toPath()); }
    private void untouched(byte[] expected) throws Exception {
        assertArrayEquals(expected, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(0, NativeTable.writes);
        assertEquals(0, NativeTable.tableCalls);
        assertFalse(saved(".json").exists());
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".armed").exists());
    }
    private byte[] limit(int limit) throws Exception {
        return document("123:config|0: enabled: false", "123:config|1: limit: " + limit, "456:other|1: nullable: __NULL_VALUE__");
    }

    @Test public void aMatchingFileMakesNoNativeCallAndKeepsTheStoreByteIdentical() throws Exception {
        for (byte[] same : new byte[][]{ exported(), document("123:config|1: limit: +5", "123:config|0: enabled: true",
                "456:other|1: nullable: __NULL_VALUE__") }) {
            NativeTable.captures = 0;
            OverrideImport.Result result = OverrideImport.apply(activity, same);
            assertEquals(OverrideImport.Outcome.UNCHANGED, result.outcome);
            // The first read and the settled read.
            assertEquals(2, NativeTable.captures);
            untouched(NATIVE);
        }
    }

    @Test public void aValidImportMakesOnlyTypedWritesKeepsThePreviousCopyAndNeedsARestart() throws Exception {
        byte[] previous = exported();
        byte[] file = document("123:config|0: enabled: false", "123:config|2: label: a: b", "456:other|0: ratio: 0.5",
                "456:other|1: nullable: __NULL_VALUE__");
        OverrideImport.Result result = OverrideImport.apply(activity, file);
        assertEquals(OverrideImport.Outcome.APPLIED, result.outcome);
        assertEquals(4, result.changes);
        assertEquals(Arrays.asList("bool " + NativeTable.id(1, 1) + " false", "remove " + NativeTable.id(2, 2) + " null",
                "string " + NativeTable.id(3, 3) + " a: b", "double " + NativeTable.id(4, 4) + " 0.5"), NativeTable.log);
        Map<String, String> expected = new TreeMap<>();
        expected.put("123:config/0/enabled", "false");
        expected.put("123:config/2/label", "a: b");
        expected.put("456:other/0/ratio", "0.5");
        expected.put("456:other/1/nullable", "__NULL_VALUE__");
        assertEquals(expected, semantic(NativeTable.file));
        assertArrayEquals(previous, bytes(".json"));
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".armed").exists());
    }

    @Test public void malformedOversizedDuplicatedMismatchedAndUnknownFilesChangeNothing() throws Exception {
        JSONObject host = new JSONObject(new String(exported(), StandardCharsets.UTF_8));
        host.getJSONObject("host").put("code", 1);
        JSONObject schema = new JSONObject(new String(exported(), StandardCharsets.UTF_8));
        schema.getJSONObject("schema").put("sha256", new String(new char[64]).replace('\0', '0'));
        String duplicateKey = new String(document("123:config|0: enabled: false"), StandardCharsets.UTF_8)
                .replace("\"overrides\":{", "\"overrides\":{\"123:config\":[\"0: enabled: true\"],");
        List<byte[]> files = Arrays.asList(
                "bad".getBytes(StandardCharsets.UTF_8), new byte[OverrideExchange.MAX_BYTES + 1], new byte[0],
                host.toString().getBytes(StandardCharsets.UTF_8), schema.toString().getBytes(StandardCharsets.UTF_8),
                document("123:config|0: enabled: false", "123:config|0: enabled: true"),
                duplicateKey.getBytes(StandardCharsets.UTF_8),
                document("999:nope|0: enabled: true"), document("123:renamed|0: enabled: true"),
                document("123:config|0: renamed: true"), document("123:config|9: enabled: true"),
                document("123:config|1: limit: many"), document("123:config|0: enabled: yes"),
                document("456:other|0: ratio: NaN"),
                // A null override has no typed writer, so neither setting nor clearing one may move it.
                document("123:config|0: enabled: true", "456:other|1: nullable: text"),
                document("123:config|0: enabled: true", "123:config|1: limit: 5"));
        for (byte[] file : files) {
            NativeTable.captures = 0;
            assertThrows(IOException.class, () -> OverrideImport.apply(activity, file));
            untouched(NATIVE);
        }
    }

    @Test public void aTypeTheDecoderDisagreesWithOrAMissingNativeTableRefusesBeforeWriting() throws Exception {
        byte[] file = limit(5);
        NativeTable.typeShift = 1;
        assertThrows(IOException.class, () -> OverrideImport.apply(activity, file));
        untouched(NATIVE);
        NativeTable.typeShift = 0;
        NativeTable.table = false;
        NativeTable.tableCalls = 0;
        assertThrows(IOException.class, () -> OverrideImport.apply(activity, file));
        assertArrayEquals(NATIVE, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(0, NativeTable.writes);
        assertFalse(saved(".json").exists());
    }

    @Test public void aSessionOrStoreChangeAfterTheFirstReadRefusesBeforeWriting() throws Exception {
        byte[] edited = "{\"123:config\":[\"0: enabled: true\",\"1: limit: 6\"],\"456:other\":[\"1: nullable: __NULL_VALUE__\"]}"
                .getBytes(StandardCharsets.UTF_8);
        // Capture 2 is the settled read, capture 3 the commit boundary.
        for (int at : new int[]{2, 3}) {
            Files.write(NativeTable.file.toPath(), NATIVE);
            byte[] file = limit(5);
            NativeTable.changeAt = at;
            NativeTable.secondManager = new Object();
            assertThrows(IOException.class, () -> OverrideImport.apply(activity, file));
            untouched(NATIVE);
            NativeTable.secondManager = null;
            NativeTable.captures = 0;
            NativeTable.onChange = () -> {
                try { Files.write(NativeTable.file.toPath(), edited); } catch (IOException failure) { throw new AssertionError(failure); }
            };
            assertThrows(OverrideImport.StoreChanging.class, () -> OverrideImport.apply(activity, file));
            untouched(edited);
            NativeTable.onChange = null;
        }
    }

    @Test public void permissionRevokedWhileSettlingOrAtCommitRefusesBeforeAnyMutation() throws Exception {
        for (int at : new int[]{2, 3}) for (boolean pause : new boolean[]{false, true}) {
            NativeTable.reset(NativeTable.file);
            byte[] file = limit(5);
            NativeTable.changeAt = at;
            NativeTable.onChange = () -> {
                if (pause) {
                    BaseSettings.PAUSED.save(true);
                    PauseForTests.pause(HushgramPause.Reason.SWITCH);
                } else Settings.ALLOW_OVERRIDE_IMPORT.save(false);
            };
            try {
                assertThrows(OverrideImport.NotAllowed.class, () -> OverrideImport.apply(activity, file));
                untouched(NATIVE);
                assertFalse(saved(".replaced.json").exists());
            } finally {
                NativeTable.onChange = null;
                Settings.ALLOW_OVERRIDE_IMPORT.save(true);
                BaseSettings.PAUSED.save(false);
                PauseForTests.resume();
            }
        }
    }

    @Test public void permissionRevokedDuringRestoreOrDiscardPreservesTheSavedCopy() throws Exception {
        byte[] previous = exported();
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(5)).outcome);
        byte[] current = Files.readAllBytes(NativeTable.file.toPath());
        for (int at : new int[]{1, 2, 3}) for (boolean pause : new boolean[]{false, true}) {
            NativeTable.reset(NativeTable.file);
            NativeTable.changeAt = at;
            NativeTable.onChange = () -> {
                if (pause) {
                    BaseSettings.PAUSED.save(true);
                    PauseForTests.pause(HushgramPause.Reason.SWITCH);
                } else Settings.ALLOW_OVERRIDE_IMPORT.save(false);
            };
            try {
                if (at == 1) assertThrows(OverrideImport.NotAllowed.class, () -> OverrideImport.discard(activity));
                else assertThrows(OverrideImport.NotAllowed.class, () -> OverrideImport.restore(activity));
                assertArrayEquals(current, Files.readAllBytes(NativeTable.file.toPath()));
                assertArrayEquals(previous, bytes(".json"));
                assertEquals(0, NativeTable.writes);
                assertEquals(0, NativeTable.tableCalls);
                assertFalse(saved(".pending.json").exists());
                assertFalse(saved(".replaced.json").exists());
                assertFalse(saved(".armed").exists());
            } finally {
                NativeTable.onChange = null;
                Settings.ALLOW_OVERRIDE_IMPORT.save(true);
                BaseSettings.PAUSED.save(false);
                PauseForTests.resume();
            }
        }
    }

    /**
     * Instagram's editor puts a change in its table at once and in the file later. An import
     * right after such an edit must see it land before planning, not write over it.
     */
    @Test public void anEditInstagramHasntWrittenToTheFileYetIsSeenBeforeAnyWrite() throws Exception {
        byte[] file = limit(5);
        byte[] edited = "{\"123:config\":[\"0: enabled: true\",\"1: limit: 6\"],\"456:other\":[\"1: nullable: __NULL_VALUE__\"]}"
                .getBytes(StandardCharsets.UTF_8);
        NativeTable.lagged = edited;
        NativeTable.lagMillis = 100;
        assertThrows(OverrideImport.StoreChanging.class, () -> OverrideImport.apply(activity, file));
        untouched(edited);
    }

    @Test public void aWriteFailureOrAStoreThatDoesntKeepTheChangeIsPutBack() throws Exception {
        byte[] file = limit(7);
        NativeTable.throwAt = 2;
        OverrideImport.Result result = OverrideImport.apply(activity, file);
        assertEquals(OverrideImport.Outcome.ROLLED_BACK, result.outcome);
        assertEquals(original(), semantic(NativeTable.file));
        assertEquals(Arrays.asList("bool " + NativeTable.id(1, 1) + " false", "long " + NativeTable.id(2, 2) + " 7",
                "long " + NativeTable.id(2, 2) + " 5", "bool " + NativeTable.id(1, 1) + " true"), NativeTable.log);
        // A rolled-back import leaves no restore point of its own.
        assertFalse(saved(".json").exists());
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".armed").exists());

        NativeTable.reset(NativeTable.file);
        Files.write(NativeTable.file.toPath(), NATIVE);
        NativeTable.keep = false;
        result = OverrideImport.apply(activity, limit(5));
        assertEquals(OverrideImport.Outcome.ROLLED_BACK, result.outcome);
        assertArrayEquals(NATIVE, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(2, NativeTable.writes);
        assertFalse(saved(".armed").exists());
    }

    @Test public void aFailedImportKeepsThePreviousRestorePoint() throws Exception {
        byte[] first = exported();
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(5)).outcome);
        assertArrayEquals(first, bytes(".json"));

        NativeTable.keep = false;
        assertEquals(OverrideImport.Outcome.ROLLED_BACK, OverrideImport.apply(activity, limit(7)).outcome);
        assertArrayEquals(first, bytes(".json"));
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".armed").exists());

        // The marker can't be written: nothing is, and the earlier copy stays.
        NativeTable.keep = true;
        NativeTable.writes = 0;
        Files.createDirectories(saved(".armed.tmp").toPath().resolve("blocker"));
        assertThrows(IOException.class, () -> OverrideImport.apply(activity, limit(7)));
        assertEquals(0, NativeTable.writes);
        assertArrayEquals(first, bytes(".json"));
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".armed").exists());
    }

    @Test public void anImportThatStoppedPartwayRestoresFromItsOwnPendingCopy() throws Exception {
        byte[] first = exported();
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(5)).outcome);
        byte[] beforeStop = exported();
        Map<String, String> held = semantic(NativeTable.file);
        // What an import that stopped after its first write leaves: its copy, the marker, a half-written store.
        Files.write(saved(".pending.json").toPath(), beforeStop);
        Files.write(saved(".armed").toPath(), selectedMarker("pending", beforeStop));
        Files.write(NativeTable.file.toPath(), ("{\"123:config\":[\"0: enabled: false\",\"1: limit: 9\"],"
                + "\"456:other\":[\"1: nullable: __NULL_VALUE__\"]}").getBytes(StandardCharsets.UTF_8));
        assertThrows(OverrideImport.RestoreFirst.class, () -> OverrideImport.apply(activity, limit(5)));
        assertNotEquals(new String(first, StandardCharsets.UTF_8), new String(beforeStop, StandardCharsets.UTF_8));

        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertEquals(held, semantic(NativeTable.file));
        assertArrayEquals(beforeStop, bytes(".json"));
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".armed").exists());
    }

    @Test public void anUnconfirmedPutBackArmsRestoreAndBlocksImportsUntilItRuns() throws Exception {
        byte[] previous = exported();
        byte[] file = limit(7);
        NativeTable.throwAt = 2;
        NativeTable.failFrom = 3;
        OverrideImport.Result failed = OverrideImport.apply(activity, file);
        assertEquals(OverrideImport.Outcome.UNRECOVERED, failed.outcome);
        assertTrue(failed.blocked);
        assertTrue(saved(".armed").exists());
        assertArrayEquals(previous, bytes(".json"));
        assertFalse(saved(".pending.json").exists());
        Map<String, String> broken = semantic(NativeTable.file);
        assertEquals("false", broken.get("123:config/0/enabled"));

        NativeTable.reset(NativeTable.file);
        byte[] bytes = Files.readAllBytes(NativeTable.file.toPath());
        assertThrows(OverrideImport.RestoreFirst.class, () -> OverrideImport.apply(activity, file));
        assertArrayEquals(bytes, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(0, NativeTable.writes);
        assertArrayEquals(previous, bytes(".json"));

        OverrideImport.Result restored = OverrideImport.restore(activity);
        assertEquals(OverrideImport.Outcome.APPLIED, restored.outcome);
        assertEquals(original(), semantic(NativeTable.file));
        assertFalse(saved(".armed").exists());
        assertArrayEquals(previous, bytes(".json"));
        assertEquals(OverrideImport.Outcome.UNCHANGED, OverrideImport.restore(activity).outcome);
    }

    /**
     * A null override Instagram's writer dropped can't be put back by any typed writer. Restore
     * puts back the rest and says so instead of claiming there's no copy, and Discard lets
     * imports run again.
     */
    @Test public void restoreReportsANullItCantPutBackAndDiscardUnblocksImports() throws Exception {
        byte[] previous = exported();
        NativeTable.dropNulls = true;
        NativeTable.failFrom = 3;
        byte[] file = document("123:config|0: enabled: false", "123:config|1: limit: 5", "456:other|0: ratio: 0.5",
                "456:other|1: nullable: __NULL_VALUE__");
        assertEquals(OverrideImport.Outcome.UNRECOVERED, OverrideImport.apply(activity, file).outcome);
        Map<String, String> broken = semantic(NativeTable.file);
        assertEquals("false", broken.get("123:config/0/enabled"));
        assertEquals("0.5", broken.get("456:other/0/ratio"));
        assertFalse(broken.containsKey("456:other/1/nullable"));
        assertArrayEquals(previous, bytes(".json"));

        NativeTable.dropNulls = false;
        NativeTable.failFrom = -1;
        OverrideImport.Result restored = OverrideImport.restore(activity);
        assertEquals(OverrideImport.Outcome.PARTIAL, restored.outcome);
        assertEquals(2, restored.changes);
        assertEquals(1, restored.skipped);
        assertTrue(restored.blocked);
        assertFalse(saved(".replaced.json").exists());
        Map<String, String> back = original();
        back.remove("456:other/1/nullable");
        assertEquals(back, semantic(NativeTable.file));
        assertTrue(saved(".armed").exists());
        assertThrows(OverrideImport.RestoreFirst.class, () -> OverrideImport.apply(activity, file));

        // Nothing more can be put back, so another Restore writes nothing and says the same.
        NativeTable.writes = 0;
        restored = OverrideImport.restore(activity);
        assertEquals(OverrideImport.Outcome.PARTIAL, restored.outcome);
        assertEquals(0, restored.changes);
        assertEquals(0, NativeTable.writes);
        assertFalse(saved(".replaced.json").exists());

        byte[] store = Files.readAllBytes(NativeTable.file.toPath());
        assertTrue(OverrideImport.discard(activity));
        assertArrayEquals(store, Files.readAllBytes(NativeTable.file.toPath()));
        assertFalse(saved(".json").exists());
        assertFalse(saved(".armed").exists());
        assertFalse(OverrideImport.discard(activity));
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity,
                document("123:config|0: enabled: false", "123:config|1: limit: 5")).outcome);
    }

    @Test public void aMarkerFromAnotherInstagramBuildKeepsBlockingUntilDiscarded() throws Exception {
        JSONObject older = new JSONObject(new String(exported(), StandardCharsets.UTF_8));
        older.getJSONObject("host").put("code", 1);
        Files.createDirectories(saved(".armed").getParentFile().toPath());
        Files.write(saved(".json").toPath(), older.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(saved(".armed").toPath(), "1".getBytes(StandardCharsets.UTF_8));
        byte[] file = limit(5);
        assertThrows(OverrideImport.RestoreFirst.class, () -> OverrideImport.apply(activity, file));
        assertThrows(OverrideImport.SavedCopyDoesntFit.class, () -> OverrideImport.restore(activity));
        assertEquals(0, NativeTable.writes);
        assertTrue(saved(".armed").exists());

        assertTrue(OverrideImport.discard(activity));
        assertArrayEquals(NATIVE, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, file).outcome);
        assertFalse(saved(".armed").exists());
    }

    @Test public void offPausedOrUnreadyNothingReadsOrWritesTheStore() throws Exception {
        byte[] file = limit(5);
        Runnable refused = () -> {
            assertThrows(OverrideImport.NotAllowed.class, () -> OverrideImport.apply(activity, file));
            assertThrows(OverrideImport.NotAllowed.class, () -> OverrideImport.restore(activity));
            assertThrows(OverrideImport.NotAllowed.class, () -> OverrideImport.discard(activity));
        };
        Settings.ALLOW_OVERRIDE_IMPORT.save(false);
        refused.run();
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try { refused.run(); }
        finally {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(refused);
        assertEquals(0, NativeTable.captures);
        untouched(NATIVE);
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, file).outcome);
    }

    @Test public void aRestoreThatRollsBackLeavesTheMarkerAsItWas() throws Exception {
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(5)).outcome);
        NativeTable.keep = false;
        OverrideImport.Result result = OverrideImport.restore(activity);
        assertEquals(OverrideImport.Outcome.ROLLED_BACK, result.outcome);
        assertFalse(result.blocked);
        assertFalse(saved(".armed").exists());
        NativeTable.keep = true;
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(6)).outcome);

        // An armed store stays armed.
        Files.write(saved(".armed").toPath(), "385511871".getBytes(StandardCharsets.UTF_8));
        NativeTable.keep = false;
        result = OverrideImport.restore(activity);
        assertEquals(OverrideImport.Outcome.ROLLED_BACK, result.outcome);
        assertTrue(result.blocked);
        assertTrue(saved(".armed").exists());
    }

    @Test public void restoreSavesWhatItReplacesFirst() throws Exception {
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(5)).outcome);
        assertFalse(saved(".replaced.json").exists());
        byte[] imported = exported();
        boolean[] savedBeforeWriting = {false};
        storageObserver = (boundary, file) -> {
            if ("fileSynced".equals(boundary) && file.getName().endsWith(".replaced.json")) {
                assertEquals(1, NativeTable.writes);
                assertArrayEquals(imported, Files.readAllBytes(new File(file.getParentFile(), file.getName() + ".tmp").toPath()));
                savedBeforeWriting[0] = true;
            }
        };
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertTrue(savedBeforeWriting[0]);
        assertFalse(saved(".replaced.json").exists());
        assertEquals(original(), semantic(NativeTable.file));
    }

    @Test public void aPostPromotionSyncFailureKeepsRecoveryArmedAndReportsTheAppliedChanges() throws Exception {
        String backup = saved(".json").getName();
        String marker = saved(".armed").getName();
        String pending = saved(".pending.json").getName();
        OverrideImport.directorySync = directory -> {
            List<String> names = Arrays.asList(directory.list());
            if (names.contains(backup) && names.contains(marker) && !names.contains(pending)) {
                throw new IOException("promotion sync failed");
            }
        };
        OverrideImport.Result result = OverrideImport.apply(activity, limit(7));
        assertEquals(OverrideImport.Outcome.APPLIED, result.outcome);
        assertEquals(2, result.changes);
        assertTrue("Recovery must stay visible after a failed durable promotion", result.blocked);
        assertTrue(saved(".armed").isFile());
        assertEquals("7", semantic(NativeTable.file).get("123:config/1/limit"));
        useTestTiming();
        NativeTable.reset(NativeTable.file);
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertEquals(original(), semantic(NativeTable.file));
    }

    @Test public void aBackupSelectedMarkerNeverRestoresADifferentStalePendingCopy() throws Exception {
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(7)).outcome);
        Files.write(saved(".pending.json").toPath(), limit(9));
        byte[] selected = bytes(".json");
        Files.write(saved(".armed").toPath(), selectedMarker("backup", selected));
        NativeTable.reset(NativeTable.file);
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertEquals(original(), semantic(NativeTable.file));
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".replaced.json").exists());
        assertFalse(saved(".armed").exists());
    }

    @Test public void aLegacyMarkerRefusesTwoDifferentCopiesAndAcceptsOnlyAnUnambiguousCopy() throws Exception {
        byte[] first = exported();
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(7)).outcome);
        byte[] later = exported();
        Files.write(saved(".pending.json").toPath(), later);
        Files.write(saved(".armed").toPath(), "385511871".getBytes(StandardCharsets.UTF_8));
        NativeTable.reset(NativeTable.file);
        assertThrows(OverrideImport.SavedCopyDoesntFit.class, () -> OverrideImport.restore(activity));
        assertEquals(0, NativeTable.writes);
        assertArrayEquals(first, bytes(".json"));
        assertArrayEquals(later, bytes(".pending.json"));

        Files.write(saved(".pending.json").toPath(), first);
        assertFalse(OverrideImport.restore(activity).blocked);
        assertEquals(original(), semantic(NativeTable.file));
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".armed").exists());

        assertTrue(OverrideImport.discard(activity));
        Files.write(saved(".pending.json").toPath(), first);
        Files.write(saved(".armed").toPath(), "385511871".getBytes(StandardCharsets.UTF_8));
        assertEquals(OverrideImport.Outcome.UNCHANGED, OverrideImport.restore(activity).outcome);
        assertArrayEquals(first, bytes(".json"));
        assertFalse(saved(".pending.json").exists());
    }

    @Test public void anInvalidSelectedCopyOrMarkerNeverFallsBackToAnotherCopy() throws Exception {
        byte[] first = exported();
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(7)).outcome);
        byte[] later = exported();
        Files.write(saved(".pending.json").toPath(), later);
        String valid = new String(selectedMarker("backup", first), StandardCharsets.UTF_8);
        String[] invalid = {new String(selectedMarker("backup", later), StandardCharsets.UTF_8),
                valid.replaceFirst("1\n", "2\n"), valid.replace("385511871", "385511872"),
                valid.replace("backup", "../pending"), valid + "\nunknown\n-"};
        NativeTable.reset(NativeTable.file);
        for (String marker : invalid) {
            Files.write(saved(".armed").toPath(), marker.getBytes(StandardCharsets.UTF_8));
            assertThrows(OverrideImport.SavedCopyDoesntFit.class, () -> OverrideImport.restore(activity));
            assertEquals(0, NativeTable.writes);
            assertArrayEquals(first, bytes(".json"));
            assertArrayEquals(later, bytes(".pending.json"));
        }
    }

    private static final class Stopped extends Error {}

    /** A fresh public call after a fault reconstructs Store solely from the surviving files. */
    private void restartAndRestore() throws Exception {
        storageObserver = null;
        NativeTable.reset(NativeTable.file);
        try {
            OverrideImport.Result restored = OverrideImport.restore(activity);
            assertFalse("A resumed recovery must finish cleanup", restored.blocked);
        } catch (OverrideImport.NothingSaved expected) {
            assertEquals("An import without a durable copy must have made no native change", original(), semantic(NativeTable.file));
        }
        assertEquals(original(), semantic(NativeTable.file));
        OverrideImport.discard(activity);
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".replaced.json").exists());
        assertFalse(saved(".armed").exists());
    }

    @Test public void everyImportStorageBoundarySurvivesAFailedOperationAndAProcessRestart() throws Exception {
        OverrideImport.settleMillis = 1;
        byte[] document = limit(7);
        assertFalse(OverrideImport.apply(activity, document).blocked);
        List<String> points = new ArrayList<>(boundaries);
        restartAndRestore();
        assertTrue(points.stream().anyMatch(point -> point.startsWith("fileSynced:")));
        assertTrue(points.stream().anyMatch(point -> point.startsWith("moved:")));
        assertTrue(points.contains("moved:" + saved(".settled").getName()));
        for (boolean crash : new boolean[]{false, true}) {
            for (int stop = 0; stop < points.size(); stop++) {
                NativeTable.reset(NativeTable.file);
                int boundary = stop;
                int[] reached = {0};
                storageObserver = (name, file) -> {
                    if (reached[0]++ == boundary) {
                        if (crash) throw new Stopped();
                        throw new IOException("controlled storage failure");
                    }
                };
                try {
                    OverrideImport.Result result = OverrideImport.apply(activity, document);
                    assertEquals(OverrideImport.Outcome.APPLIED, result.outcome);
                    assertTrue("Failed cleanup must be visible at " + points.get(stop), result.blocked);
                } catch (IOException | Stopped expected) {
                    // Before a native write, refusal is safe. Abrupt exit skips every cleanup catch.
                }
                assertTrue("The requested boundary must run: " + points.get(stop), reached[0] > stop);
                restartAndRestore();
            }
        }
    }

    @Test public void everyRestoreStorageBoundaryPreservesItsSelectedCopyUntilRecoveryFinishes() throws Exception {
        OverrideImport.settleMillis = 1;
        byte[] document = limit(7);
        assertFalse(OverrideImport.apply(activity, document).blocked);
        boundaries.clear();
        assertFalse(OverrideImport.restore(activity).blocked);
        List<String> points = new ArrayList<>(boundaries);
        OverrideImport.discard(activity);
        for (boolean crash : new boolean[]{false, true}) {
            for (int stop = 0; stop < points.size(); stop++) {
                NativeTable.reset(NativeTable.file);
                assertFalse(OverrideImport.apply(activity, document).blocked);
                NativeTable.reset(NativeTable.file);
                int boundary = stop;
                int[] reached = {0};
                storageObserver = (name, file) -> {
                    if (reached[0]++ == boundary) {
                        if (crash) throw new Stopped();
                        throw new IOException("controlled storage failure");
                    }
                };
                try {
                    OverrideImport.Result result = OverrideImport.restore(activity);
                    assertEquals(OverrideImport.Outcome.APPLIED, result.outcome);
                    assertTrue("Failed cleanup must be visible at " + points.get(stop), result.blocked);
                } catch (IOException | Stopped expected) {}
                assertTrue("The requested boundary must run: " + points.get(stop), reached[0] > stop);
                assertTrue("Restore must retain its selected backup", saved(".json").isFile());
                restartAndRestore();
            }
        }
    }

    @Test public void everyDiscardBoundaryIsRetryableWithoutChangingNativeOverrides() throws Exception {
        OverrideImport.settleMillis = 1;
        byte[] document = limit(7);
        assertFalse(OverrideImport.apply(activity, document).blocked);
        Files.write(saved(".pending.json").toPath(), document);
        Files.write(saved(".replaced.json").toPath(), document);
        for (String suffix : new String[]{".json.tmp", ".pending.json.tmp", ".replaced.json.tmp", ".armed.tmp"}) {
            Files.write(saved(suffix).toPath(), new byte[]{1});
        }
        boundaries.clear();
        assertTrue(OverrideImport.discard(activity));
        List<String> points = new ArrayList<>(boundaries);
        byte[] nativeBytes = Files.readAllBytes(NativeTable.file.toPath());
        for (boolean crash : new boolean[]{false, true}) {
            for (int stop = 0; stop < points.size(); stop++) {
                Files.write(saved(".json").toPath(), document);
                Files.write(saved(".pending.json").toPath(), document);
                Files.write(saved(".replaced.json").toPath(), document);
                for (String suffix : new String[]{".json.tmp", ".pending.json.tmp", ".replaced.json.tmp", ".armed.tmp"}) {
                    Files.write(saved(suffix).toPath(), new byte[]{1});
                }
                int boundary = stop;
                int[] reached = {0};
                storageObserver = (name, file) -> {
                    if (reached[0]++ == boundary) {
                        if (crash) throw new Stopped();
                        throw new IOException("controlled storage failure");
                    }
                };
                if (crash) assertThrows(Stopped.class, () -> OverrideImport.discard(activity));
                else assertThrows(IOException.class, () -> OverrideImport.discard(activity));
                assertTrue("The requested boundary must run: " + points.get(stop), reached[0] > stop);
                storageObserver = null;
                NativeTable.reset(NativeTable.file);
                OverrideImport.discard(activity);
                assertArrayEquals(nativeBytes, Files.readAllBytes(NativeTable.file.toPath()));
                assertEquals(0, NativeTable.writes);
                assertFalse(saved(".json").exists());
                assertFalse(saved(".pending.json").exists());
                assertFalse(saved(".replaced.json").exists());
                for (String suffix : new String[]{".json.tmp", ".pending.json.tmp", ".replaced.json.tmp", ".armed.tmp"}) {
                    assertFalse(saved(suffix).exists());
                }
                assertFalse(saved(".armed").exists());
            }
        }
    }

    @Test public void everyRollbackStorageBoundaryKeepsTheOlderBackupAndResumesItsJournal() throws Exception {
        OverrideImport.settleMillis = 1;
        byte[] older = exported();
        byte[] first = limit(5);
        byte[] second = document("123:config|0: enabled: true", "123:config|1: limit: 7",
                "456:other|1: nullable: __NULL_VALUE__");
        assertFalse(OverrideImport.apply(activity, first).blocked);
        boundaries.clear();
        NativeTable.reset(NativeTable.file);
        NativeTable.throwAt = 2;
        assertEquals(OverrideImport.Outcome.ROLLED_BACK, OverrideImport.apply(activity, second).outcome);
        List<String> points = new ArrayList<>(boundaries);
        for (boolean crash : new boolean[]{false, true}) {
            for (int stop = 0; stop < points.size(); stop++) {
                storageObserver = null;
                NativeTable.reset(NativeTable.file);
                OverrideImport.discard(activity);
                Files.write(NativeTable.file.toPath(), NATIVE);
                assertFalse(OverrideImport.apply(activity, first).blocked);
                Map<String, String> before = semantic(NativeTable.file);
                NativeTable.reset(NativeTable.file);
                NativeTable.throwAt = 2;
                int boundary = stop;
                int[] reached = {0};
                storageObserver = (name, file) -> {
                    if (reached[0]++ == boundary) {
                        if (crash) throw new Stopped();
                        throw new IOException("controlled rollback storage failure");
                    }
                };
                try {
                    OverrideImport.Result result = OverrideImport.apply(activity, second);
                    assertTrue(result.outcome == OverrideImport.Outcome.ROLLED_BACK || result.outcome == OverrideImport.Outcome.UNRECOVERED);
                    assertTrue("Failed rollback cleanup must remain visible at " + points.get(stop), result.blocked);
                } catch (IOException | Stopped expected) {}
                assertTrue("The requested boundary must run: " + points.get(stop), reached[0] > stop);
                assertArrayEquals("A failed import must retain the older backup", older, bytes(".json"));
                boolean undoJournal = saved(".armed").isFile() && new String(bytes(".armed"), StandardCharsets.UTF_8).contains("\nrollback\n");
                storageObserver = null;
                NativeTable.reset(NativeTable.file);
                assertFalse(OverrideImport.restore(activity).blocked);
                Map<String, String> restored = semantic(NativeTable.file);
                assertTrue("Recovery may restore only the interrupted import's original values or the older saved point",
                        before.equals(restored) || original().equals(restored));
                if (undoJournal) assertArrayEquals("An interrupted rollback must preserve the older restore point", older, bytes(".json"));
            }
        }
    }

    @Test public void permissionRevokedAfterStagingRefusesTheFirstTypedWrite() throws Exception {
        for (boolean restoring : new boolean[]{false, true}) for (boolean pause : new boolean[]{false, true})
                for (boolean recovering : new boolean[]{false, true}) {
            if (recovering && !restoring) continue;
            storageObserver = null;
            OverrideImport.discard(activity);
            Files.write(NativeTable.file.toPath(), NATIVE);
            NativeTable.reset(NativeTable.file);
            if (recovering) storageObserver = (boundary, file) -> {
                if (boundary.equals("beforeMove") && file.getName().endsWith(".settled")) throw new IOException("leave Restore armed");
            };
            assertEquals(recovering, OverrideImport.apply(activity, limit(5)).blocked);
            storageObserver = null;
            byte[] previous = bytes(".json"), current = Files.readAllBytes(NativeTable.file.toPath());
            byte[] journal = recovering ? bytes(".armed") : null;
            byte[] next = limit(7);
            NativeTable.reset(NativeTable.file);
            storageObserver = (boundary, file) -> {
                if (boundary.equals("moved") && file.getName().endsWith(restoring ? ".replaced.json" : ".armed")) {
                    if (pause) {
                        BaseSettings.PAUSED.save(true);
                        PauseForTests.pause(HushgramPause.Reason.SWITCH);
                    } else Settings.ALLOW_OVERRIDE_IMPORT.save(false);
                }
            };
            try {
                assertThrows(OverrideImport.NotAllowed.class, () -> {
                    if (restoring) OverrideImport.restore(activity); else OverrideImport.apply(activity, next);
                });
                assertEquals(0, NativeTable.writes);
                assertArrayEquals(current, Files.readAllBytes(NativeTable.file.toPath()));
                assertArrayEquals(previous, bytes(".json"));
                assertFalse(saved(".pending.json").exists());
                assertFalse(saved(".replaced.json").exists());
                if (recovering) assertArrayEquals(journal, bytes(".armed"));
                else assertFalse(saved(".armed").exists());
            } finally {
                storageObserver = null;
                Settings.ALLOW_OVERRIDE_IMPORT.save(true);
                BaseSettings.PAUSED.save(false);
                PauseForTests.resume();
            }
        }
    }

    @Test public void discardDoesNotParseAnyDamagedRecoveryRecord() throws Exception {
        byte[] corrupt = new byte[2 * 1024 * 1024];
        Arrays.fill(corrupt, (byte) 'x');
        for (String suffix : new String[]{".armed", ".settled"}) {
            Files.createDirectories(saved(suffix).getParentFile().toPath());
            Files.write(saved(suffix).toPath(), corrupt);
            Files.write(saved(".json").toPath(), corrupt);
            assertTrue(OverrideImport.discard(activity));
            assertFalse(saved(".json").exists());
            assertFalse(saved(".armed").exists());
            untouched(NATIVE);
        }
    }

    @Test public void discardCanReplaceALoneDamagedTerminalRecordWithoutChangingOverrides() throws Exception {
        File terminal = saved(".settled");
        Files.createDirectories(terminal.getParentFile().toPath());
        for (byte[] corrupt : new byte[][]{"not a journal".getBytes(StandardCharsets.UTF_8), new byte[2 * 1024 * 1024]}) {
            Files.write(terminal.toPath(), corrupt);
            assertFalse(saved(".armed").exists());
            assertFalse(saved(".json").exists());
            assertTrue(OverrideImport.discard(activity));
            untouched(NATIVE);
            assertFalse(saved(".armed").exists());
            assertFalse(OverrideImport.discard(activity));
            OverrideImport.Result unchanged = OverrideImport.apply(activity, exported());
            assertFalse(unchanged.blocked);
            assertEquals(0, unchanged.changes);
            untouched(NATIVE);
        }
    }

    @Test public void discardCanRecoverALoneTerminalAfterInspectionIoFailure() throws Exception {
        File terminal = saved(".settled");
        Files.createDirectories(terminal.getParentFile().toPath());
        Files.write(terminal.toPath(), "1\n385511871\nnone\n-\ncomplete\n-".getBytes(StandardCharsets.UTF_8));
        boolean[] failInspection = {true};
        storageObserver = (boundary, file) -> {
            if (failInspection[0] && boundary.equals("beforeDirectorySync")) {
                failInspection[0] = false;
                throw new IOException("terminal inspection failed");
            }
        };
        try {
            assertTrue(OverrideImport.discard(activity));
            untouched(NATIVE);
            assertFalse(saved(".armed").exists());
            assertFalse(OverrideImport.discard(activity));
            OverrideImport.Result unchanged = OverrideImport.apply(activity, exported());
            assertFalse(unchanged.blocked);
            assertEquals(0, unchanged.changes);
        } finally { storageObserver = null; }
    }

    @Test public void persistentIoFailureAfterTheFinalMarkerMoveKeepsImportsBlocked() throws Exception {
        for (boolean discarding : new boolean[]{false, true}) {
            syncFailure = null;
            storageObserver = null;
            OverrideImport.discard(activity);
            Files.write(NativeTable.file.toPath(), NATIVE);
            NativeTable.reset(NativeTable.file);
            if (discarding) assertFalse(OverrideImport.apply(activity, limit(5)).blocked);
            storageObserver = (boundary, file) -> {
                if ((boundary.equals("deleted") && file.getName().endsWith(".armed")) ||
                        (boundary.equals("moved") && file.getName().endsWith(".settled"))) {
                    syncFailure = new IOException("persistent EIO after final marker transition");
                }
            };
            if (discarding) assertThrows(IOException.class, () -> OverrideImport.discard(activity));
            else assertTrue(OverrideImport.apply(activity, limit(5)).blocked);
            storageObserver = null;
            byte[] unchanged = exported();
            NativeTable.reset(NativeTable.file);
            assertThrows(OverrideImport.RestoreFirst.class, () -> OverrideImport.apply(activity, unchanged));
            assertEquals(0, NativeTable.writes);
            syncFailure = null;
            if (!discarding) assertFalse(OverrideImport.restore(activity).blocked);
            OverrideImport.discard(activity);
            assertFalse(OverrideImport.apply(activity, exported()).blocked);
        }
    }

    @Test public void discardCanRemoveAnOversizedCorruptBackupWithoutReadingIt() throws Exception {
        File backup = saved(".json");
        Files.createDirectories(backup.getParentFile().toPath());
        byte[] corrupt = new byte[2 * 1024 * 1024];
        Arrays.fill(corrupt, (byte) 'x');
        Files.write(backup.toPath(), corrupt);
        assertTrue(OverrideImport.discard(activity));
        assertFalse(backup.exists());
        untouched(NATIVE);
    }

    @Test public void everyMoveIntoTheDirectoryIsFollowedByADirectorySync() throws Exception {
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(5)).outcome);
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertTrue(boundaries.stream().anyMatch(point -> point.startsWith("deleted:")));
        assertTrue(boundaries.stream().anyMatch(point -> point.startsWith("directoryCreated:")));
        for (int i = 0; i < boundaries.size(); i++) {
            String point = boundaries.get(i);
            if (point.startsWith("moved:") || point.startsWith("deleted:") || point.startsWith("directoryCreated:")) {
                String directory = point.startsWith("directoryCreated:") ? activity.getFilesDir().getName() : OverrideImport.DIRECTORY;
                assertEquals(point, "beforeDirectorySync:" + directory, boundaries.get(i + 1));
                assertEquals(point, "directorySynced:" + directory, boundaries.get(i + 2));
            }
        }
    }

    @Test public void aDirectorySyncThatFailsRefusesBeforeAnyWrite() throws Exception {
        syncFailure = new IOException("EIO");
        assertThrows(IOException.class, () -> OverrideImport.apply(activity, limit(5)));
        assertEquals(1, syncs.size());
        assertArrayEquals(NATIVE, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(0, NativeTable.writes);
        assertFalse(saved(".json").exists());
        assertFalse(saved(".pending.json").exists());
        assertFalse(saved(".armed").exists());
        // Only a filesystem that can't sync a directory at all is let through.
        assertTrue(OverrideImport.syncUnsupported(OsConstants.EINVAL));
        assertTrue(OverrideImport.syncUnsupported(OsConstants.EROFS));
        assertFalse(OverrideImport.syncUnsupported(OsConstants.EIO));
        assertFalse(OverrideImport.syncUnsupported(OsConstants.ENOSPC));
    }

    @Test public void restoreNeedsASavedCopyFromThisSameStore() throws Exception {
        assertThrows(OverrideImport.NothingSaved.class, () -> OverrideImport.restore(activity));
        untouched(NATIVE);
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(5)).outcome);
        NativeTable.file = store("mobileconfig_qce");
        NativeTable.reset(NativeTable.file);
        assertThrows(OverrideImport.NothingSaved.class, () -> OverrideImport.restore(activity));
        untouched(NATIVE);
    }

    @Test public void theSessionlessQceStoreIsImportedAndRestoredThroughItsOwnFile() throws Exception {
        File plain = NativeTable.file;
        NativeTable.file = store("mobileconfig_qce");
        byte[] previous = exported();
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.apply(activity, limit(5)).outcome);
        assertEquals("false", semantic(NativeTable.file).get("123:config/0/enabled"));
        assertArrayEquals(NATIVE, Files.readAllBytes(plain.toPath()));
        assertArrayEquals(previous, bytes(".json"));
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertEquals(original(), semantic(NativeTable.file));
    }

    /**
     * A document can't hold more overrides than the limit, so the changes past it come from both
     * sides: the store's overrides the document leaves out and the document's the store doesn't have.
     */
    @Test public void moreChangesThanTheLimitRefuseBeforeWriting() throws Exception {
        int half = OverrideImport.MAX_CHANGES / 2 + 1;
        List<OverrideExchange.Parameter> many = new ArrayList<>(NativeTable.SCHEMA);
        for (int i = 0; i < half; i++) {
            many.add(new OverrideExchange.Parameter(789, i, "kept", "p" + i, 1, NativeTable.id(1, 100 + i)));
            many.add(new OverrideExchange.Parameter(790, i, "new", "p" + i, 1, NativeTable.id(1, 100 + half + i)));
        }
        NativeTable.schema = many;
        JSONObject held = new JSONObject(new String(NATIVE, StandardCharsets.UTF_8));
        JSONArray kept = new JSONArray(), added = new JSONArray();
        for (int i = 0; i < half; i++) { kept.put(i + ": p" + i + ": true"); added.put(i + ": p" + i + ": true"); }
        byte[] store = held.put("789:kept", kept).toString().getBytes(StandardCharsets.UTF_8);
        Files.write(NativeTable.file.toPath(), store);
        JSONObject file = new JSONObject(new String(exported(), StandardCharsets.UTF_8));
        file.getJSONObject("overrides").remove("789:kept");
        file.getJSONObject("overrides").put("790:new", added);
        NativeTable.captures = 0;
        assertThrows(IOException.class, () -> OverrideImport.apply(activity, file.toString().getBytes(StandardCharsets.UTF_8)));
        untouched(store);
    }

    /** Instagram's own mc_overrides.json, with its empty names, imports and restores like an export. */
    @Test public void instagramsOwnFileImportsAndRestores() throws Exception {
        byte[] own = ("{\"123:\":[\"0: : false\",\"1: : 7\"],\"456:\":[\"1: : __NULL_VALUE__\"],"
                + "\"_qe_overrides_\":[]}").getBytes(StandardCharsets.UTF_8);
        NativeTable.captures = 0;
        OverrideImport.Result result = OverrideImport.apply(activity, own);
        assertEquals(OverrideImport.Outcome.APPLIED, result.outcome);
        assertEquals(2, result.changes);
        assertEquals("false", semantic(NativeTable.file).get("123:config/0/enabled"));
        assertEquals("7", semantic(NativeTable.file).get("123:config/1/limit"));
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertEquals(original(), semantic(NativeTable.file));
    }

    /**
     * Instagram's file from an older build: what this build has is imported and the rest is left
     * out and counted. Restore still puts the store back as it was. A file with nothing this build
     * has changes nothing.
     */
    @Test public void anInstagramFileFromAnotherBuildImportsWhatThisBuildHas() throws Exception {
        byte[] own = ("{\"123:\":[\"0: : false\",\"1: : 7\",\"9: : true\",\"2: : x\"],\"456:\":[\"1: : __NULL_VALUE__\",\"0: : many\"],"
                + "\"999:\":[\"0: : true\"],\"_qe_overrides_\":[]}").getBytes(StandardCharsets.UTF_8);
        NativeTable.captures = 0;
        OverrideImport.Result result = OverrideImport.apply(activity, own);
        assertEquals(OverrideImport.Outcome.APPLIED, result.outcome);
        assertEquals(3, result.changes);
        assertEquals(3, result.leftOut);
        Map<String, String> imported = semantic(NativeTable.file);
        assertEquals("false", imported.get("123:config/0/enabled"));
        assertEquals("7", imported.get("123:config/1/limit"));
        assertEquals("x", imported.get("123:config/2/label"));
        assertFalse(imported.containsKey("456:other/0/ratio"));
        assertEquals(OverrideImport.Outcome.APPLIED, OverrideImport.restore(activity).outcome);
        assertEquals(original(), semantic(NativeTable.file));

        byte[] store = Files.readAllBytes(NativeTable.file.toPath());
        NativeTable.writes = 0;
        NativeTable.tableCalls = 0;
        assertThrows(OverrideExchange.NothingFits.class, () -> OverrideImport.apply(activity,
                "{\"999:\":[\"0: : true\"],\"123:\":[\"9: : true\"]}".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(store, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(0, NativeTable.writes);
        assertEquals(0, NativeTable.tableCalls);
    }

    /**
     * App data outlives an update, so the store can hold overrides this build has no parameter for.
     * They're left as they are: an import changes only what this build has and Restore puts that
     * back, whether Instagram's writer keeps the leftovers when it rewrites its file or drops them.
     */
    @Test public void aStoreHoldingLeftoversImportsAndRestoresWhenTheWriterKeepsThem() throws Exception { leftovers(false); }
    @Test public void aStoreHoldingLeftoversImportsAndRestoresWhenTheWriterDropsThem() throws Exception { leftovers(true); }

    private void leftovers(boolean dropped) throws Exception {
        byte[] store = ("{\"123:config\":[\"0: enabled: true\",\"1: limit: 5\",\"9: : true\"],"
                + "\"456:other\":[\"1: nullable: __NULL_VALUE__\"],\"999:\":[\"0: : true\"]}").getBytes(StandardCharsets.UTF_8);
        Files.write(NativeTable.file.toPath(), store);
        NativeTable.dropForeign = dropped;
        assertEquals(2, OverrideExchange.capture(activity).leftOut());
        byte[] before = exported();
        assertEquals(new JSONObject(new String(NATIVE, StandardCharsets.UTF_8)).toString(),
                new JSONObject(new String(before, StandardCharsets.UTF_8)).getJSONObject("overrides").toString());

        OverrideImport.Result result = OverrideImport.apply(activity, limit(7));
        assertEquals(OverrideImport.Outcome.APPLIED, result.outcome);
        assertEquals(2, result.changes);
        assertFalse(result.blocked);
        Map<String, String> imported = semantic(NativeTable.file);
        assertEquals(dropped ? null : "true", imported.remove("123:config/9/"));
        assertEquals(dropped ? null : "true", imported.remove("999:/0/"));
        Map<String, String> expected = original();
        expected.put("123:config/0/enabled", "false");
        expected.put("123:config/1/limit", "7");
        assertEquals(expected, imported);
        assertArrayEquals(before, bytes(".json"));

        OverrideImport.Result restored = OverrideImport.restore(activity);
        assertEquals(OverrideImport.Outcome.APPLIED, restored.outcome);
        assertEquals(2, restored.changes);
        assertFalse(restored.blocked);
        Map<String, String> back = semantic(NativeTable.file);
        assertEquals(dropped ? null : "true", back.remove("123:config/9/"));
        assertEquals(dropped ? null : "true", back.remove("999:/0/"));
        assertEquals(original(), back);
    }

    /**
     * Stands in for the patched bridge: the reader's session store, file and schema, and a native
     * table that persists each typed write into that file the way Instagram's native writer would.
     */
    @Implements(value = DeveloperOptions.class, isInAndroidSdk = false)
    public static class NativeTable {
        public static final List<OverrideExchange.Parameter> SCHEMA = Arrays.asList(
                new OverrideExchange.Parameter(123, 0, "config", "enabled", 1, id(1, 1)),
                new OverrideExchange.Parameter(123, 1, "config", "limit", 2, id(2, 2)),
                new OverrideExchange.Parameter(123, 2, "config", "label", 3, id(3, 3)),
                new OverrideExchange.Parameter(456, 0, "other", "ratio", 4, id(4, 4)),
                new OverrideExchange.Parameter(456, 1, "other", "nullable", 3, id(3, 5)));
        public static final Object MANAGER = new Object(), TABLE = new Object();
        public static File file;
        public static List<OverrideExchange.Parameter> schema;
        /** From capture changeAt on, the session answers this manager, and onChange runs at that capture. */
        public static Object secondManager;
        public static Runnable onChange;
        /** Written to the file lagMillis after the first capture, as Instagram's editor flushes its table later. */
        public static byte[] lagged;
        public static long lagMillis;
        private static long laggedDue;
        public static int captures, writes, tableCalls, throwAt, failFrom, typeShift, changeAt;
        /**
         * keep: writes reach the file. dropNulls: rewriting a config drops its null overrides.
         * dropForeign: a write leaves the file holding only overrides the schema has.
         */
        public static boolean keep, table, dropNulls, dropForeign;
        public static final List<String> log = new ArrayList<>();

        public static long id(int type, int serial) { return ((long) type << 48) | serial; }
        public static void reset() { reset(null); }
        public static void reset(File store) {
            file = store; schema = SCHEMA; secondManager = null; onChange = null; lagged = null; lagMillis = 0; laggedDue = 0;
            captures = 0; writes = 0; tableCalls = 0; throwAt = -1; failFrom = -1; typeShift = 0; changeAt = 2;
            keep = true; table = true; dropNulls = false; dropForeign = false; log.clear();
        }

        private static void land() {
            if (lagged == null || laggedDue == 0 || System.nanoTime() - laggedDue < 0) return;
            try { Files.write(file.toPath(), lagged); } catch (IOException failure) { throw new AssertionError(failure); }
            lagged = null;
        }

        @Implementation protected static Object getOverrideStoreNative(Object activity) {
            captures++;
            if (captures == 1 && lagged != null) laggedDue = System.nanoTime() + lagMillis * 1_000_000L;
            if (captures == changeAt && onChange != null) onChange.run();
            return captures >= changeAt && secondManager != null ? secondManager : MANAGER;
        }
        @Implementation protected static File getOverrideFileNative(Object manager) { land(); return file; }
        @Implementation protected static List<?> getOverrideSchemaNative(Object manager) { return schema; }
        @Implementation protected static OverrideExchange.Parameter getOverrideParameterNative(Object parameter) {
            return (OverrideExchange.Parameter) parameter;
        }
        @Implementation protected static Object getOverrideTableNative(Object manager) {
            tableCalls++;
            assertTrue(manager == MANAGER || manager == secondManager);
            return table ? TABLE : null;
        }
        @Implementation protected static int getOverrideTypeNative(long id) { return (int) ((id >>> 48) & 0x3f) + typeShift; }
        @Implementation protected static int setOverrideBooleanNative(Object table, long id, int value) {
            return write(table, "bool", id, value == 1 ? "true" : "false");
        }
        @Implementation protected static int setOverrideLongNative(Object table, long id, long value) {
            return write(table, "long", id, Long.toString(value));
        }
        @Implementation protected static int setOverrideDoubleNative(Object table, long id, double value) {
            return write(table, "double", id, Double.toString(value));
        }
        @Implementation protected static int setOverrideStringNative(Object table, long id, String value) {
            return write(table, "string", id, value);
        }
        @Implementation protected static int removeOverrideNative(Object table, long id) { return write(table, "remove", id, null); }

        private static int write(Object target, String kind, long id, String value) {
            assertSame(TABLE, target);
            land();
            writes++;
            log.add(kind + " " + id + " " + value);
            if (writes == throwAt) throw new IllegalStateException("controlled native failure");
            if (failFrom > 0 && writes >= failFrom) return 0;
            if (!keep) return 1;
            try {
                OverrideExchange.Parameter parameter = null;
                for (OverrideExchange.Parameter candidate : schema) if (candidate.nativeId == id) parameter = candidate;
                assertNotNull(parameter);
                JSONObject root = new JSONObject(file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "{}");
                String label = parameter.config + ":" + parameter.configName;
                JSONArray before = root.optJSONArray(label), after = new JSONArray();
                if (before != null) for (int i = 0; i < before.length(); i++) {
                    String record = before.getString(i);
                    if (record.startsWith(parameter.index + ": ") || (dropNulls && record.endsWith(": __NULL_VALUE__"))) continue;
                    after.put(record);
                }
                if (value != null) after.put(parameter.index + ": " + parameter.name + ": " + value);
                if (after.length() == 0) root.remove(label);
                else root.put(label, after);
                if (dropForeign) dropForeign(root);
                Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
                return 1;
            } catch (Exception failure) { throw new AssertionError(failure); }
        }

        private static void dropForeign(JSONObject root) throws Exception {
            List<String> labels = new ArrayList<>();
            for (java.util.Iterator<String> keys = root.keys(); keys.hasNext();) labels.add(keys.next());
            for (String label : labels) {
                int config = Integer.parseInt(label.substring(0, label.indexOf(':')));
                JSONArray records = root.getJSONArray(label), kept = new JSONArray();
                for (int i = 0; i < records.length(); i++) {
                    String record = records.getString(i);
                    int index = Integer.parseInt(record.substring(0, record.indexOf(':')));
                    for (OverrideExchange.Parameter candidate : schema) {
                        if (candidate.config == config && candidate.index == index) { kept.put(record); break; }
                    }
                }
                if (kept.length() == 0) root.remove(label);
                else root.put(label, kept);
            }
        }
    }
}
