/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.content.pm.PackageInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.SettingsJson;

/** A read-only native override snapshot. This class has no writer or cache-reload operation; see OverrideImport. */
public final class OverrideExchange {
    public static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_PARAMETERS = 65536, MAX_OVERRIDES = 4096;
    private static final String HOST = "com.instagram.android";
    /** Instagram's own section for experiment overrides, beside the config ones. No typed writer reaches it. */
    private static final String EXPERIMENTS = "_qe_overrides_";
    private static final String NULL = "__NULL_VALUE__";
    private static final String LABEL = "[1-9][0-9]{0,6}:[^:]*";
    private static final SettingsJson.Limits LIMITS = new SettingsJson.Limits(5, 16384, 10000, MAX_OVERRIDES, MAX_BYTES);
    private OverrideExchange() {}

    public static final class Parameter {
        public final int config, index, type;
        public final String configName, name;
        public final long nativeId;
        public Parameter(int config, int index, String configName, String name, int type, long nativeId) {
            this.config = config; this.index = index; this.configName = configName;
            this.name = name; this.type = type; this.nativeId = nativeId;
        }
    }

    /**
     * What a file or the store holds for this build: the overrides that fit it, and how many
     * overrides of Instagram's own writing it leaves out because this build has no such parameter
     * or types it otherwise.
     */
    public static final class Checked {
        public final int fits, leftOut;
        Checked(int fits, int leftOut) { this.fits = fits; this.leftOut = leftOut; }
    }

    /** Instagram's own file, well formed, but holding no override this build has. */
    public static final class NothingFits extends IOException {
        NothingFits() { super("No override in the file fits this build"); }
    }

    public static final class Snapshot {
        private final String version, hash;
        private final long code;
        private final Map<Long, Parameter> parameters = new TreeMap<>();
        private final Map<Integer, String> configs = new HashMap<>();
        /** The store's overrides this build has, which is all an export, a plan or a restore point reads. */
        private final String overrides;
        /** The store's experiment section as it reads, "[]" when it has none. */
        private final String experiments;
        private final int leftOut;
        /** Set by capture only: the resolved store, its manager and its bytes (null when absent). */
        File file;
        Object manager;
        byte[] raw;

        Snapshot(String version, long code, List<Parameter> schema, byte[] nativeBytes) throws IOException {
            if (version == null || version.isEmpty() || version.length() > 64 || code <= 0 || schema == null
                    || schema.isEmpty() || schema.size() > MAX_PARAMETERS) throw invalid();
            this.version = version;
            this.code = code;
            Set<Long> ids = new HashSet<>();
            for (Parameter parameter : schema) {
                if (parameter == null || parameter.config <= 0 || parameter.config >= 0x100000
                        || parameter.index < 0 || parameter.index >= 0x4000 || parameter.type < 1 || parameter.type > 4
                        || parameter.nativeId <= 0 || !name(parameter.configName) || !name(parameter.name)
                        || !ids.add(parameter.nativeId) || parameters.put(key(parameter.config, parameter.index), parameter) != null) throw invalid();
                String previous = configs.put(parameter.config, parameter.configName);
                if (previous != null && !previous.equals(parameter.configName)) throw invalid();
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (Parameter parameter : parameters.values()) {
                    JSONArray record = new JSONArray().put(parameter.config).put(parameter.index).put(parameter.configName)
                            .put(parameter.name).put(parameter.type).put(parameter.nativeId);
                    digest.update(record.toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
                StringBuilder identity = new StringBuilder();
                for (byte value : digest.digest()) identity.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                hash = identity.toString();
                JSONObject nativeValues = parse(nativeBytes);
                // App data outlives an update, so the store may hold overrides a build before this
                // one wrote. No typed writer reaches those, so they're counted and left as they are.
                // The snapshot keeps only what fits: a config this build lacks with nothing under it
                // counts as nothing left out, but the strict check a change or an import goes
                // through would refuse it, so it stays out of the overrides the snapshot carries.
                JSONObject fitting = new JSONObject();
                leftOut = checkOverrides(nativeValues, this, null, false, true, fitting).leftOut;
                overrides = fitting.toString();
                JSONArray held = nativeValues.optJSONArray(EXPERIMENTS);
                experiments = held == null ? "[]" : held.toString();
            } catch (Exception failure) { throw invalid(); }
        }

        /** How many overrides the store holds that this build has no parameter for, or types otherwise. */
        public int leftOut() { return leftOut; }
    }

    /** Obtain only the current signed-in manager, its own file resolver and its typed records. */
    public static Snapshot capture(Activity activity) throws IOException {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) throw invalid();
        try {
            HookStatus.invoked(FamilyNames.DEVELOPER_OPTIONS);
            if (!HOST.equals(activity.getPackageName())) throw invalid();
            Object manager = DeveloperOptions.getOverrideStoreNative(activity);
            if (manager == null) throw invalid();
            File resolved = DeveloperOptions.getOverrideFileNative(manager);
            if (resolved == null || !resolved.isAbsolute()) throw invalid();
            File file = resolved.getCanonicalFile();
            String privateRoot = activity.getFilesDir().getCanonicalPath() + File.separator;
            if (!file.getPath().startsWith(privateRoot) || (file.exists() && !file.isFile())) throw invalid();
            List<?> nativeSchema = DeveloperOptions.getOverrideSchemaNative(manager);
            if (nativeSchema == null || nativeSchema.isEmpty() || nativeSchema.size() > MAX_PARAMETERS) throw invalid();
            List<Parameter> parameters = new ArrayList<>(nativeSchema.size());
            for (Object entry : nativeSchema) parameters.add(DeveloperOptions.getOverrideParameterNative(entry));
            PackageInfo host = activity.getPackageManager().getPackageInfo(HOST, 0);
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            boolean present = file.exists();
            if (present) {
                try (InputStream input = new FileInputStream(file)) { bytes = read(input); }
            }
            // Instagram's own build, under Change version code too, so a file follows the build it fits.
            Snapshot snapshot = new Snapshot(host.versionName, VersionCode.unraised(host.getLongVersionCode()), parameters, bytes);
            snapshot.file = file;
            snapshot.manager = manager;
            snapshot.raw = present ? bytes : null;
            return snapshot;
        } catch (Throwable failure) {
            // Native exceptions can carry a session path or value. Never retain their causes.
            throw invalid();
        }
    }

    public static byte[] export(Snapshot snapshot) throws IOException {
        if (snapshot == null) throw invalid();
        return document(snapshot, snapshot.overrides);
    }

    /**
     * {@link #export} keeping only what an import can't take away and put back: overrides holding
     * Instagram's null value, and its experiment section. Imported, it takes every other override away.
     */
    static byte[] exportReset(Snapshot snapshot) throws IOException {
        if (snapshot == null) throw invalid();
        try {
            JSONObject all = new JSONObject(snapshot.overrides), kept = new JSONObject();
            for (java.util.Iterator<String> labels = all.keys(); labels.hasNext();) {
                String label = labels.next();
                if (EXPERIMENTS.equals(label)) {
                    kept.put(label, all.get(label));
                    continue;
                }
                JSONArray records = all.getJSONArray(label), nulls = new JSONArray();
                for (int i = 0; i < records.length(); i++) {
                    String record = records.getString(i);
                    if (record.endsWith(": " + NULL)) nulls.put(record);
                }
                if (nulls.length() > 0) kept.put(label, nulls);
            }
            return document(snapshot, kept.toString());
        } catch (JSONException failure) { throw invalid(); }
    }

    private static byte[] document(Snapshot snapshot, String overrides) throws IOException {
        try {
            JSONObject host = new JSONObject().put("package", HOST).put("version", snapshot.version).put("code", snapshot.code);
            JSONObject schema = new JSONObject().put("sha256", snapshot.hash).put("parameters", snapshot.parameters.size());
            byte[] file = new JSONObject().put("project", "HushGram-overrides").put("format", 1).put("host", host)
                    .put("schema", schema).put("overrides", new JSONObject(overrides)).toString().getBytes(StandardCharsets.UTF_8);
            if (file.length > MAX_BYTES) throw invalid();
            return file;
        } catch (JSONException failure) { throw invalid(); }
    }

    /** Return only counts. Validation has no path to a native writer, file output or cache reload. */
    public static Checked validate(byte[] file, Snapshot snapshot) throws IOException {
        return validated(file, snapshot, null);
    }

    /**
     * {@link #validate}, also collecting each override's value by parameter key into values. A
     * HushGram export has to match this build and schema exactly. Instagram's own mc_overrides.json
     * names neither, and often no config or parameter either, so each of its overrides is held to
     * this build's schema by its config and index, by any name it does give, and by its value's type.
     * Instagram keeps that file across app updates, so it can hold overrides from an older build. One
     * this build doesn't have is left out and counted, and a file with none it has is refused.
     */
    static Checked validated(byte[] file, Snapshot snapshot, Map<Long, String> values) throws IOException {
        if (snapshot == null) throw invalid();
        try {
            JSONObject root = parse(file);
            if (!root.has("project")) {
                Checked checked = checkOverrides(root, snapshot, values, true, true, null);
                if (checked.fits == 0 && checked.leftOut > 0) throw new NothingFits();
                return checked;
            }
            if (root.length() != 5 || !"HushGram-overrides".equals(root.get("project")) || !(root.get("format") instanceof Integer)
                    || root.getInt("format") != 1 || !(root.get("host") instanceof JSONObject) || !(root.get("schema") instanceof JSONObject)
                    || !(root.get("overrides") instanceof JSONObject)) throw invalid();
            JSONObject host = root.getJSONObject("host"), schema = root.getJSONObject("schema");
            if (host.length() != 3 || !HOST.equals(host.get("package")) || !snapshot.version.equals(host.get("version"))
                    || !(host.get("code") instanceof Integer || host.get("code") instanceof Long) || host.getLong("code") != snapshot.code
                    || schema.length() != 2 || !snapshot.hash.equals(schema.get("sha256")) || !(schema.get("parameters") instanceof Integer)
                    || schema.getInt("parameters") != snapshot.parameters.size()) throw invalid();
            return checkOverrides(root.getJSONObject("overrides"), snapshot, values, true, false, null);
        } catch (JSONException | IllegalArgumentException failure) { throw invalid(); }
    }

    /**
     * Whether a file is an overrides document, a HushGram export or Instagram's own file, so a
     * settings import handed one can say which import it belongs to. Nothing is checked against a
     * schema here.
     */
    public static boolean isOverridesFile(byte[] file) {
        if (file == null) return false;
        try {
            JSONObject root = parse(file);
            if (root.has("project")) return "HushGram-overrides".equals(root.opt("project"));
            boolean config = false;
            for (java.util.Iterator<String> keys = root.keys(); keys.hasNext();) {
                String label = keys.next();
                if (!(root.get(label) instanceof JSONArray)) return false;
                if (label.matches(LABEL)) config = true;
                else if (!EXPERIMENTS.equals(label)) return false;
            }
            return config;
        } catch (Exception failure) { return false; }
    }

    public static byte[] read(InputStream input) throws IOException {
        if (input == null) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - bytes.size()));
            if (count == -1) return bytes.toByteArray();
            if (count <= 0) throw invalid();
            bytes.write(buffer, 0, count);
            if (bytes.size() > MAX_BYTES) throw invalid();
        }
    }

    /** The native file's override values by parameter key, for the overrides this build has. */
    static Map<Long, String> values(byte[] nativeBytes, Snapshot snapshot) throws IOException {
        Map<Long, String> values = new TreeMap<>();
        try { checkOverrides(parse(nativeBytes), snapshot, values, false, true, null); }
        catch (JSONException | RuntimeException failure) { throw invalid(); }
        return values;
    }

    /** The value type this build gives a parameter (1 bool, 2 long, 3 string, 4 double), or 0 when it has none there. */
    static int typeOf(Snapshot snapshot, int config, int index) {
        Parameter parameter = snapshot.parameters.get(key(config, index));
        return parameter == null ? 0 : parameter.type;
    }

    /**
     * {@link #export}'s document with these overrides set (a value) or taken away (null), by
     * parameter key. Every other override stays as the store holds it, so applying the result
     * through {@link OverrideImport} changes only these parameters.
     */
    static byte[] withOverrides(Snapshot snapshot, Map<Long, String> changes) throws IOException {
        if (snapshot == null || changes == null) throw invalid();
        try {
            JSONObject all = new JSONObject(snapshot.overrides);
            for (Map.Entry<Long, String> change : changes.entrySet()) {
                Parameter parameter = snapshot.parameters.get(change.getKey());
                if (parameter == null || change.getValue() != null && !fits(parameter.type, change.getValue())) throw invalid();
                // The store may label a config with or without its name, so keep whichever it uses.
                String label = null, prefix = parameter.config + ":";
                for (java.util.Iterator<String> labels = all.keys(); labels.hasNext();) {
                    String candidate = labels.next();
                    if (candidate.startsWith(prefix)) label = candidate;
                }
                if (label == null) label = prefix + parameter.configName;
                JSONArray before = all.optJSONArray(label), after = new JSONArray();
                if (before != null) {
                    for (int i = 0; i < before.length(); i++) {
                        String record = before.getString(i);
                        if (!record.startsWith(parameter.index + ": ")) after.put(record);
                    }
                }
                if (change.getValue() != null) after.put(parameter.index + ": " + parameter.name + ": " + change.getValue());
                if (after.length() > 0) all.put(label, after);
                else all.remove(label);
            }
            return document(snapshot, all.toString());
        } catch (JSONException failure) { throw invalid(); }
    }

    static Parameter parameter(Snapshot snapshot, long key) { return snapshot.parameters.get(key); }
    static boolean sameSchema(Snapshot one, Snapshot other) {
        return one.version.equals(other.version) && one.code == other.code && one.hash.equals(other.hash);
    }
    static long hostCode(Snapshot snapshot) { return snapshot.code; }

    private static JSONObject parse(byte[] bytes) throws IOException {
        try { return SettingsJson.parseObject(bytes, LIMITS); }
        catch (Exception failure) { throw invalid(); }
    }

    /**
     * Checks every override against the schema, by config and index, by its value's type, and by
     * its names wherever they aren't empty. Instagram writes its own file with empty names. Its
     * experiment section has no typed writer and an import never changes it, so a document may hold
     * one only when it's empty or the same as the store's, as in an export or a restore point; any
     * other is refused rather than imported in part. With leaveOut, an override that's well formed
     * but doesn't fit the schema is counted and left out instead of refusing the whole document.
     * fitting, when given, receives what's left: the experiment section and each config's overrides
     * that fit, in their order.
     */
    private static Checked checkOverrides(JSONObject values, Snapshot snapshot, Map<Long, String> collected, boolean document,
                                          boolean leaveOut, JSONObject fitting) throws IOException, JSONException {
        Set<Long> seen = new HashSet<>();
        int fits = 0, leftOut = 0;
        for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) {
            String label = keys.next();
            if (EXPERIMENTS.equals(label)) {
                if (!(values.get(label) instanceof JSONArray)) throw invalid();
                JSONArray experiments = values.getJSONArray(label);
                if (document && experiments.length() > 0 && !experiments.toString().equals(snapshot.experiments)) throw invalid();
                if (fitting != null) fitting.put(label, experiments);
                continue;
            }
            if (!label.matches(LABEL) || !(values.get(label) instanceof JSONArray)) throw invalid();
            int colon = label.indexOf(':');
            int config = Integer.parseInt(label.substring(0, colon));
            String configName = label.substring(colon + 1);
            boolean known = snapshot.configs.containsKey(config)
                    && (configName.isEmpty() || configName.equals(snapshot.configs.get(config)));
            if (!known && !leaveOut) throw invalid();
            JSONArray parameters = values.getJSONArray(label), kept = new JSONArray();
            for (int i = 0; i < parameters.length(); i++) {
                if (!(parameters.get(i) instanceof String)) throw invalid();
                String record = parameters.getString(i);
                String[] parts = record.split(": ", 3);
                if (parts.length != 3 || record.indexOf('\0') >= 0 || !parts[0].matches("0|[1-9][0-9]{0,4}")) throw invalid();
                int index = Integer.parseInt(parts[0]);
                if (index >= 0x4000 || !seen.add(key(config, index)) || seen.size() > MAX_OVERRIDES) throw invalid();
                Parameter parameter = known ? snapshot.parameters.get(key(config, index)) : null;
                String value = parts[2];
                if (parameter == null || !parts[1].isEmpty() && !parameter.name.equals(parts[1]) || !fits(parameter.type, value)) {
                    if (!leaveOut) throw invalid();
                    leftOut++;
                    continue;
                }
                if (collected != null) collected.put(key(config, index), value);
                kept.put(record);
                fits++;
            }
            if (fitting != null && known && (kept.length() > 0 || parameters.length() == 0)) fitting.put(label, kept);
        }
        return new Checked(fits, leftOut);
    }

    /** Whether a value reads as its decoder code's type, 1 bool, 2 long, 3 string, 4 finite double, or is Instagram's null. */
    private static boolean fits(int type, String value) {
        if (NULL.equals(value)) return true;
        switch (type) {
            case 1: return "true".equals(value) || "false".equals(value);
            case 2:
                if (!value.matches("[+-]?[0-9]+")) return false;
                try { Long.parseLong(value); return true; } catch (NumberFormatException failure) { return false; }
            case 3: return true;
            case 4:
                if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) return false;
                try { return Double.isFinite(Double.parseDouble(value)); } catch (NumberFormatException failure) { return false; }
            default: return false;
        }
    }

    static long key(int config, int index) { return ((long) config << 14) | index; }
    private static boolean name(String value) {
        return value != null && value.length() <= 256 && value.indexOf(':') < 0
                && value.chars().noneMatch(character -> character < 32 || character == 127);
    }
    private static IOException invalid() { return new IOException("Invalid or unavailable native overrides"); }
}
