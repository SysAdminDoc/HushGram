/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Hide rows in Instagram's settings" patch (#235, #250): leaves the rows the user
 * picks off Instagram's own Settings and activity screen.
 *
 * <p>Instagram 452 builds that screen on the phone, in Compose, from row models. Each row that opens
 * another screen carries an id from one enum whose constants keep their real names (ARCHIVE,
 * CLOSE_FRIENDS, INSTAGRAM_PLUS and about 240 more), and one suspend method turns a row model into
 * the row the screen draws, answering null for a row that shouldn't show. The patch asks
 * {@link #hidden} first thing in that method with the row model, and a yes answers null there, so
 * Instagram drops the row the way it drops one it doesn't want. Every row it does make goes past
 * {@link #built}, which keeps the row's id and its title as the screen showed it, so the list in
 * HushGram settings offers the rows this phone has, in the user's language, and saves the ids.
 *
 * <p>Rows with a switch carry another kind of id and aren't offered. Fails open: with nothing
 * saved, HushGram paused, the settings not read yet or anything thrown, every row shows.
 */
public final class SettingsRows {
    /** What's counted for each row left off Instagram's settings. */
    static final String LEFT_OFF = "rows left off Instagram's settings";

    /** How many rows the list keeps. Instagram's enum has about 250, most of which never show. */
    static final int MOST_KEPT = 400;

    /** A row id as Instagram's enum names it. */
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** The patch's stubs, which tests swap for a stand-in. */
    interface Rows {
        Object idOfModel(Object model);

        Object idOfRow(Object row);

        Object titleOfRow(Object row);

        String text(Resources resources, Object title);
    }

    static final Rows PATCHED = new Rows() {
        @Override
        public Object idOfModel(Object model) {
            return SettingsRows.idOfModel(model);
        }

        @Override
        public Object idOfRow(Object row) {
            return SettingsRows.idOfRow(row);
        }

        @Override
        public Object titleOfRow(Object row) {
            return SettingsRows.titleOfRow(row);
        }

        @Override
        public String text(Resources resources, Object title) {
            return SettingsRows.text(resources, title);
        }
    };

    static volatile Rows access = PATCHED;

    /** The rows seen, by id name, each with its title. Read from the setting once. Guarded by {@link #LOCK}. */
    @Nullable
    private static LinkedHashMap<String, String> seen;

    /** Whether a save of {@link #seen} is already on its way. Guarded by {@link #LOCK}. */
    private static boolean saving;

    private static final Object LOCK = new Object();

    private SettingsRows() {
    }

    /** Rewritten by the patch to answer the id of Instagram's row model [model]. Never called as written. */
    public static Object idOfModel(Object model) {
        return null;
    }

    /** Rewritten by the patch to answer the id of the row [row] Instagram made. Never called as written. */
    public static Object idOfRow(Object row) {
        return null;
    }

    /** Rewritten by the patch to answer the title model of the row [row]. Never called as written. */
    public static Object titleOfRow(Object row) {
        return null;
    }

    /** Rewritten by the patch to render Instagram's title model [title] to text. Never called as written. */
    public static String text(Resources resources, Object title) {
        return null;
    }

    /**
     * Injected first thing in the method that turns a row model into a row. True when the row's id
     * is one the user picked, and the method then answers null. Null [model] is the method coming
     * back after a wait, already past this, and answers false. Never throws, and never waits for
     * the settings.
     */
    public static boolean hidden(@Nullable Object model) {
        if (model == null) return false;
        try {
            HookStatus.invoked(FamilyNames.SETTINGS_ROWS);
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return false;
            String saved = Settings.HIDDEN_SETTINGS_ROWS.get();
            if (saved.isEmpty()) return false;
            String name = nameOf(access.idOfModel(model));
            if (name == null || !parse(saved).contains(name)) return false;
            HookStatus.counted(FamilyNames.SETTINGS_ROWS, LEFT_OFF);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SETTINGS_ROWS, "settings row", failure);
            return false;
        }
    }

    /**
     * Injected right after the method makes a row. Keeps the row's id and title for the list, and
     * saves once a moment later when either is new. Never throws.
     */
    public static void built(@Nullable Object row) {
        if (row == null) return;
        try {
            if (!Utils.settingsReady()) return;
            String name = nameOf(access.idOfRow(row));
            if (name == null) return;
            String title = null;
            Object model = access.titleOfRow(row);
            Context context = Utils.getContext();
            if (model != null && context != null) title = clean(access.text(context.getResources(), model));
            if (remember(name, title)) Utils.runOnMainThreadDelayed(SettingsRows::saveSeen, 1500);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SETTINGS_ROWS, "settings row title", failure);
        }
    }

    /** Keeps [title] for [name]. True when a save should be asked for, false when nothing changed or one is coming. */
    static boolean remember(String name, @Nullable String title) {
        synchronized (LOCK) {
            Map<String, String> rows = seen();
            String had = rows.get(name);
            boolean known = rows.containsKey(name);
            if (known && (title == null || title.equals(had))) return false;
            if (!known && rows.size() >= MOST_KEPT) return false;
            rows.put(name, title != null ? title : had != null ? had : "");
            if (saving) return false;
            saving = true;
            return true;
        }
    }

    static void saveSeen() {
        String text;
        synchronized (LOCK) {
            saving = false;
            text = joinSeen(seen());
        }
        if (!text.equals(Settings.SEEN_SETTINGS_ROWS.savedValue())) Settings.SEEN_SETTINGS_ROWS.save(text);
    }

    private static LinkedHashMap<String, String> seen() {
        if (seen == null) seen = parseSeen(Settings.SEEN_SETTINGS_ROWS.savedValue());
        return seen;
    }

    /** Forgets what was read, so a test or an import reads the setting again. */
    static void reset() {
        synchronized (LOCK) {
            seen = null;
            saving = false;
        }
    }

    /**
     * The rows the list offers, as id names with their titles: each row seen on this phone in the
     * order the screen first showed it, then any hidden one not seen here (from a backup), titled
     * from its name.
     */
    public static List<String[]> offered() {
        List<String[]> rows = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        synchronized (LOCK) {
            for (Map.Entry<String, String> row : seen().entrySet()) {
                rows.add(new String[]{row.getKey(), label(row.getKey(), row.getValue())});
                names.add(row.getKey());
            }
        }
        for (String name : parse(Settings.HIDDEN_SETTINGS_ROWS.savedValue())) {
            if (names.add(name)) rows.add(new String[]{name, label(name, null)});
        }
        return rows;
    }

    /** [title], or a title made from the id [name] when Instagram's wasn't kept: ACCOUNT_PRIVACY reads Account privacy. */
    static String label(String name, @Nullable String title) {
        if (title != null && !title.isEmpty()) return title;
        String words = name.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        if (words.isEmpty()) return name;
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    @Nullable
    private static String nameOf(@Nullable Object id) {
        if (!(id instanceof Enum<?>)) return null;
        String name = ((Enum<?>) id).name();
        return NAME.matcher(name).matches() ? name : null;
    }

    /** [text] on one line, without the tabs that split the saved list, or null when there's nothing. */
    @Nullable
    static String clean(@Nullable String text) {
        if (text == null) return null;
        String line = text.replaceAll("[\\t\\r\\n]+", " ").trim();
        return line.isEmpty() ? null : line;
    }

    /** The id names in [saved], without blanks, repeats or anything that isn't one, in name order. */
    public static Set<String> parse(@Nullable String saved) {
        Set<String> names = new TreeSet<>();
        if (saved == null) return names;
        for (String part : saved.split(",")) {
            String name = part.trim();
            if (NAME.matcher(name).matches()) names.add(name);
        }
        return names;
    }

    /** [hidden] as the saved value, in name order. */
    public static String join(Set<String> hidden) {
        return String.join(",", new TreeSet<>(hidden));
    }

    /** The saved list of rows seen: a line each, the id name, a tab and the title. */
    static LinkedHashMap<String, String> parseSeen(@Nullable String saved) {
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        if (saved == null) return rows;
        for (String line : saved.split("\n")) {
            int tab = line.indexOf('\t');
            String name = tab < 0 ? line : line.substring(0, tab);
            if (!NAME.matcher(name).matches() || rows.size() >= MOST_KEPT) continue;
            String title = tab < 0 ? null : clean(line.substring(tab + 1));
            rows.put(name, title == null ? "" : title);
        }
        return rows;
    }

    static String joinSeen(Map<String, String> rows) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> row : rows.entrySet()) {
            if (text.length() > 0) text.append('\n');
            text.append(row.getKey()).append('\t').append(row.getValue());
        }
        return text.toString();
    }
}
