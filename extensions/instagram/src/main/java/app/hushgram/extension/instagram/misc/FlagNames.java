/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.Setting;

/**
 * Import flag names, part of the "Open developer options" patch.
 *
 * <p>Instagram's release builds ship MetaConfig without the names of its configs and parameters,
 * so its own editor lists each one as "_" and a number, and its search has little to match. A
 * name list the person picks is read here and kept in HushGram's own folder. Three formats are
 * read: Instagram's own id_name_mapping.json (a JSON array of "config:configName:index:name..."
 * strings, the format other Instagram mods' mapping files use), a JSON array of {"code", "name"}
 * objects where a code is a config number or "config::index", and HushGram's plain text, one
 * "config=name" or "config::index=name" per line with # comments.
 *
 * <p>The patch hands every row label Instagram's MetaConfig list builder makes to {@link #parameter}
 * and {@link #config}, which put the imported name in place of Instagram's "_" and number fallback.
 * A label Instagram named itself stays as it is. Only that screen's labels change: Instagram's
 * schema, its overrides file and anything it sends still carry the numbers, and its search still
 * matches a config's number, which it keeps on each row apart from the label.
 *
 * <p>With no list imported, the labels come from the names HushGram ships ({@link FlagNameData},
 * taken from piko's list for Instagram 447), unless "Use HushGram's setting names" is off. An
 * imported list is used on its own in place of the shipped one, and Remove brings the shipped one back.
 */
public final class FlagNames {
    private FlagNames() {
    }

    static final String DIRECTORY = "hushgram-flag-names";
    static final String FILE = "names.txt";
    /** Instagram's full mapping is about 1.5 MB; anything far past that isn't a name list. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    /** 450 has about 42,000 parameters in about 5,900 configs. */
    static final int MAX_NAMES = 200_000;
    static final int MAX_NAME = 256;
    /** Instagram's own bounds for a config number and a parameter index, as its override file reads them. */
    static final int CONFIG_LIMIT = 0x100000, INDEX_LIMIT = 0x4000;

    private static final String ROWS = "MetaConfig rows", ROWS_NAMED = "MetaConfig rows named",
            CONFIGS_NAMED = "MetaConfig configs named", ROWS_SHIPPED = "MetaConfig rows named from HushGram's list",
            CONFIGS_SHIPPED = "MetaConfig configs named from HushGram's list";

    /** A name list: config names by config number, parameter names by {@link #key}. */
    public static final class Names {
        final Map<Integer, String> configs;
        final Map<Long, String> parameters;
        /** Entries the file held that were repeated, malformed or out of Instagram's range. */
        public final int leftOut;
        /** Whether these are the names HushGram ships, and not a list the person imported. */
        final boolean shipped;

        Names(Map<Integer, String> configs, Map<Long, String> parameters, int leftOut) {
            this(configs, parameters, leftOut, false);
        }

        Names(Map<Integer, String> configs, Map<Long, String> parameters, int leftOut, boolean shipped) {
            this.configs = configs;
            this.parameters = parameters;
            this.leftOut = leftOut;
            this.shipped = shipped;
        }

        public int size() {
            return configs.size() + parameters.size();
        }

        @Nullable public String config(int config) {
            return configs.get(config);
        }

        @Nullable public String parameter(int config, int index) {
            return parameters.get(key(config, index));
        }
    }

    /** A file that isn't a name list in any format this reads. */
    public static final class Unreadable extends IOException {
        Unreadable() { super("Not a flag name list"); }
    }

    /** A name list that reads but names nothing. */
    public static final class Empty extends IOException {
        Empty() { super("The flag name list is empty"); }
    }

    /** How a hook reads one of Instagram's schema entries: through the patch's filled readers. */
    interface Entries {
        int config(Object entry);
        int index(Object entry);
    }

    static volatile Entries entries = new Entries() {
        @Override public int config(Object entry) { return DeveloperOptions.getFlagConfigNative(entry); }
        @Override public int index(Object entry) { return DeveloperOptions.getFlagIndexNative(entry); }
    };

    private static final Names NONE = new Names(Collections.emptyMap(), Collections.emptyMap(), 0);
    private static final Object LOCK = new Object();
    /** The imported list, read from HushGram's copy on first use; null until then, and empty when there is none. */
    @Nullable private static volatile Names loaded;
    /** The names HushGram ships, unpacked on first use; null until then, and empty if they wouldn't unpack. */
    @Nullable private static volatile Names unpacked;

    static long key(int config, int index) {
        return ((long) config << 14) | index;
    }

    /**
     * Reads a picked file. Throws {@link Unreadable} for anything that isn't a name list, and
     * {@link Empty} for a list that names nothing. A repeated id keeps its first name, and each
     * repeat, malformed entry or number past Instagram's range is counted in {@link Names#leftOut}.
     */
    public static Names parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new Unreadable();
        String text = utf8(bytes);
        String body = text.trim();
        Builder names = new Builder();
        if (body.startsWith("[")) {
            JSONArray array;
            try {
                array = new JSONArray(body);
            } catch (JSONException failure) {
                throw new Unreadable();
            }
            if (array.length() > MAX_NAMES) throw new Unreadable();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof String) names.mapping((String) item);
                else if (item instanceof JSONObject) names.listed((JSONObject) item);
                else names.leftOut++;
            }
        } else if (body.startsWith("{")) {
            // An overrides file or settings export, not a name list.
            throw new Unreadable();
        } else {
            for (String line : text.split("\n", -1)) names.line(line);
        }
        return names.build();
    }

    private static final class Builder {
        final Map<Integer, String> configs = new HashMap<>();
        final Map<Long, String> parameters = new HashMap<>();
        int leftOut;

        /** One of Instagram's own "config:configName:index:name..." strings. */
        void mapping(String entry) throws IOException {
            String[] parts = entry.split(":", -1);
            int config = parts.length >= 2 && parts.length % 2 == 0 ? config(parts[0]) : -1;
            if (config < 0) { leftOut++; return; }
            // Instagram leaves a config's name empty when it doesn't know it; that isn't an error.
            String configName = parts[1].trim();
            if (!configName.isEmpty()) {
                if (name(configName)) add(config, configName);
                else leftOut++;
            }
            for (int at = 2; at < parts.length; at += 2) {
                int index = index(parts[at]);
                String name = parts[at + 1].trim();
                if (index < 0 || !name(name)) leftOut++;
                else add(config, index, name);
            }
        }

        /** A {"code": "config" or "config::index", "name": ...} object. */
        void listed(JSONObject entry) throws IOException {
            Object code = entry.opt("code");
            Object value = entry.opt("name");
            String name = value instanceof String ? ((String) value).trim() : "";
            if (!name(name)) { leftOut++; return; }
            String id = code instanceof String ? ((String) code).trim()
                    : code instanceof Integer || code instanceof Long ? String.valueOf(code) : null;
            if (id == null || !id(id, name)) leftOut++;
        }

        /** One line of HushGram's own text: config=name or config::index=name. */
        void line(String line) throws IOException {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
            int equals = trimmed.indexOf('=');
            String name = equals < 0 ? "" : trimmed.substring(equals + 1).trim();
            if (equals < 0 || !name(name) || !id(trimmed.substring(0, equals).trim(), name)) leftOut++;
        }

        /** Adds a name under "config" or "config::index", or answers false for an id that isn't one. */
        boolean id(String id, String name) throws IOException {
            int split = id.indexOf("::");
            if (split < 0) {
                int config = config(id);
                if (config < 0) return false;
                add(config, name);
                return true;
            }
            int config = config(id.substring(0, split));
            int index = index(id.substring(split + 2));
            if (config < 0 || index < 0) return false;
            add(config, index, name);
            return true;
        }

        void add(int config, String name) throws IOException {
            if (configs.containsKey(config)) leftOut++;
            else { configs.put(config, name); bound(); }
        }

        void add(int config, int index, String name) throws IOException {
            long key = key(config, index);
            if (parameters.containsKey(key)) leftOut++;
            else { parameters.put(key, name); bound(); }
        }

        void bound() throws IOException {
            if (configs.size() + parameters.size() > MAX_NAMES) throw new Unreadable();
        }

        Names build() throws IOException {
            if (configs.isEmpty() && parameters.isEmpty()) {
                // A list of nothing reads as empty; entries that were all unreadable don't.
                if (leftOut > 0) throw new Unreadable();
                throw new Empty();
            }
            return new Names(Collections.unmodifiableMap(configs), Collections.unmodifiableMap(parameters), leftOut);
        }
    }

    /** A config number as Instagram writes one, or -1. */
    private static int config(String digits) {
        int value = number(digits, 7);
        return value > 0 && value < CONFIG_LIMIT ? value : -1;
    }

    /** A parameter index as Instagram writes one, or -1. */
    private static int index(String digits) {
        int value = number(digits, 5);
        return value >= 0 && value < INDEX_LIMIT ? value : -1;
    }

    /** Decimal digits without a leading zero (except "0" itself), at most [width] of them, or -1. */
    private static int number(String digits, int width) {
        int length = digits.length();
        if (length == 0 || length > width || (length > 1 && digits.charAt(0) == '0')) return -1;
        int value = 0;
        for (int i = 0; i < length; i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /** A name fit to show and to keep one per line: not empty, not too long, no control or line characters. */
    private static boolean name(String value) {
        if (value.isEmpty() || value.length() > MAX_NAME || value.trim().isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127 || c == '\u0085' || c == '\u2028' || c == '\u2029') return false;
        }
        return true;
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException failure) {
            throw new Unreadable();
        }
    }

    /** Reads at most {@link #MAX_BYTES} from a picked document; a longer one is refused as unreadable. */
    public static byte[] read(InputStream input) throws IOException {
        if (input == null) throw new Unreadable();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        for (int read; (read = input.read(buffer)) != -1;) {
            total += read;
            if (total > MAX_BYTES) throw new Unreadable();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * Reads a picked file and, when it names something, keeps it as HushGram's copy in place of
     * any earlier one and puts it in use. A file that can't be read or names nothing changes nothing,
     * and so does one whose copy would come out past {@link #MAX_BYTES}: the copy is read back under
     * that limit, and repeats each config number on every line, so it can outgrow the file it came from.
     */
    public static Names importNames(Context context, byte[] bytes) throws IOException {
        Names names = parse(bytes);
        byte[] copy = text(names).getBytes(StandardCharsets.UTF_8);
        if (copy.length > MAX_BYTES) throw new Unreadable();
        synchronized (LOCK) {
            replace(store(context), copy);
            loaded = names;
        }
        Logger.printInfo(() -> "Flag names: imported " + names.size() + ", left out " + names.leftOut);
        return names;
    }

    /** Forgets HushGram's copy. Answers whether there was one. */
    public static boolean clear(Context context) throws IOException {
        synchronized (LOCK) {
            File file = store(context);
            boolean had = file.isFile();
            File temporary = new File(file.getParentFile(), FILE + ".tmp");
            if (temporary.exists() && !temporary.delete()) throw new IOException("Couldn't remove " + temporary.getName());
            if (had && !file.delete()) throw new IOException("Couldn't remove " + file.getName());
            loaded = NONE;
            return had;
        }
    }

    /** How many names are in use, reading the imported copy or unpacking the shipped names first if need be. */
    public static int count() {
        return names().size();
    }

    static File store(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), FILE);
    }

    /** HushGram's copy in its own text format, sorted, each config's name before its parameters'. */
    static String text(Names names) {
        TreeMap<Long, String> lines = new TreeMap<>();
        for (Map.Entry<Integer, String> config : names.configs.entrySet()) {
            // A config's own line sorts just before its parameter 0.
            lines.put(key(config.getKey(), 0) * 2, config.getKey() + "=" + config.getValue());
        }
        for (Map.Entry<Long, String> parameter : names.parameters.entrySet()) {
            long key = parameter.getKey();
            lines.put(key * 2 + 1, (key >>> 14) + "::" + (key & (INDEX_LIMIT - 1)) + "=" + parameter.getValue());
        }
        StringBuilder text = new StringBuilder("# HushGram flag names: config=name or config::index=name, one per line.\n");
        for (String line : lines.values()) text.append(line).append('\n');
        return text.toString();
    }

    private static void replace(File target, byte[] bytes) throws IOException {
        File directory = target.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())) {
            throw new IOException("Couldn't make " + DIRECTORY);
        }
        File temporary = new File(directory, FILE + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            if (temporary.exists() && !temporary.delete()) Logger.printInfo(() -> "Flag names: couldn't remove the partial copy");
            throw failure instanceof IOException ? (IOException) failure : new IOException(failure);
        }
    }

    /**
     * The names in use: the imported list when there is one, else the shipped names while
     * "Use HushGram's setting names" is on, else none.
     */
    static Names names() {
        Names imported = imported();
        if (imported.size() > 0) return imported;
        return Settings.USE_FLAG_NAMES.get() ? shipped() : NONE;
    }

    /** The names HushGram ships, unpacked once. Names that won't unpack are no names, and say so once. */
    static Names shipped() {
        Names current = unpacked;
        if (current != null) return current;
        synchronized (LOCK) {
            if (unpacked != null) return unpacked;
            Names read = NONE;
            try {
                read = unpack(FlagNameData.PACKED);
                Logger.printInfo(() -> "Flag names: unpacked HushGram's list");
            } catch (IOException | RuntimeException failure) {
                HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "flag names", failure);
            }
            unpacked = read;
            return read;
        }
    }

    /**
     * Unpacks the list scripts/gen-flag-names.py wrote: base64 pieces joined, inflated, then a word
     * count, the words, and a line a config. See that script for the format.
     */
    static Names unpack(String[] pieces) throws IOException {
        StringBuilder joined = new StringBuilder();
        for (String piece : pieces) joined.append(piece);
        byte[] packed = Base64.getDecoder().decode(joined.toString());
        Inflater inflater = new Inflater();
        ByteArrayOutputStream text = new ByteArrayOutputStream(1 << 20);
        try {
            inflater.setInput(packed);
            byte[] buffer = new byte[64 * 1024];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new Unreadable();
                if (text.size() + read > MAX_BYTES) throw new Unreadable();
                text.write(buffer, 0, read);
            }
        } catch (DataFormatException failure) {
            throw new Unreadable();
        } finally {
            inflater.end();
        }
        String[] lines = text.toString("UTF-8").split("\n", -1);
        int count = Integer.parseInt(lines[0]);
        String[] words = new String[count];
        System.arraycopy(lines, 1, words, 0, count);
        Builder names = new Builder();
        int config = 0;
        for (int at = 1 + count; at < lines.length; at++) {
            if (lines[at].isEmpty()) continue;
            String[] fields = lines[at].split(":", -1);
            if (fields.length < 2 || fields.length % 2 != 0) throw new Unreadable();
            config += Integer.parseInt(fields[0]);
            names.add(config, name(words, fields[1]));
            int index = 0;
            for (int field = 2; field < fields.length; field += 2) {
                index += Integer.parseInt(fields[field]);
                names.add(config, index, name(words, fields[field + 1]));
            }
        }
        Names built = names.build();
        return new Names(built.configs, built.parameters, 0, true);
    }

    /** A name from its dot-joined hexadecimal word numbers. */
    private static String name(String[] words, String numbers) {
        StringBuilder name = new StringBuilder();
        int from = 0;
        while (true) {
            int dot = numbers.indexOf('.', from);
            if (name.length() > 0 || from > 0) name.append('_');
            name.append(words[Integer.parseInt(numbers.substring(from, dot < 0 ? numbers.length() : dot), 16)]);
            if (dot < 0) return name.toString();
            from = dot + 1;
        }
    }

    /** The imported list, read once from HushGram's copy. No copy, or one that won't read, is no names. */
    private static Names imported() {
        Names current = loaded;
        if (current != null) return current;
        Context context = Utils.getContext();
        if (context == null) return NONE;
        synchronized (LOCK) {
            if (loaded != null) return loaded;
            Names read = NONE;
            File file = store(context);
            if (file.isFile()) {
                try (InputStream input = new FileInputStream(file)) {
                    read = parse(read(input));
                } catch (IOException | RuntimeException failure) {
                    Logger.printInfo(() -> "Flag names: HushGram's copy didn't read, so none are in use");
                }
            }
            loaded = read;
            return read;
        }
    }

    /** Forgets what's in use, so the next use reads HushGram's copy again. */
    static void forgetForTests() {
        loaded = null;
        unpacked = null;
    }

    /**
     * Injected where Instagram's MetaConfig list builder makes a row, with the row's schema entry
     * and its parameter label. Answers the imported name in place of Instagram's "_" and index
     * fallback, or the label as it was. Never throws.
     */
    public static String parameter(Object entry, String label) {
        try {
            HookStatus.counted(FamilyNames.DEVELOPER_OPTIONS, ROWS);
            if (label == null || Setting.isPaused() || !Utils.settingsReady()) return label;
            Names names = names();
            if (names.parameters.isEmpty()) return label;
            if (!unnamed(label)) return label;
            Entries reader = entries;
            String name = names.parameter(reader.config(entry), reader.index(entry));
            if (name == null) return label;
            HookStatus.counted(FamilyNames.DEVELOPER_OPTIONS, ROWS_NAMED);
            if (names.shipped) HookStatus.counted(FamilyNames.DEVELOPER_OPTIONS, ROWS_SHIPPED);
            return name;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "flag names", failure);
            return label;
        }
    }

    /** The same for the row's config label, which Instagram's fallback makes from the config number. */
    public static String config(Object entry, String label) {
        try {
            if (label == null || Setting.isPaused() || !Utils.settingsReady()) return label;
            Names names = names();
            if (names.configs.isEmpty()) return label;
            if (!unnamed(label)) return label;
            String name = names.config(entries.config(entry));
            if (name == null) return label;
            HookStatus.counted(FamilyNames.DEVELOPER_OPTIONS, CONFIGS_NAMED);
            if (names.shipped) HookStatus.counted(FamilyNames.DEVELOPER_OPTIONS, CONFIGS_SHIPPED);
            return name;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "flag names", failure);
            return label;
        }
    }

    /**
     * Whether [label] is one Instagram uses for an entry it has no name for: its own "_" and number
     * fallback, a bare number, or nothing, the same shapes Instagram's name loader treats as unnamed.
     * A real name stays as it is.
     */
    static boolean unnamed(String label) {
        int length = label.length();
        int start = length > 0 && label.charAt(0) == '_' ? 1 : 0;
        if (length - start > 10) return false;
        for (int i = start; i < length; i++) {
            char c = label.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
