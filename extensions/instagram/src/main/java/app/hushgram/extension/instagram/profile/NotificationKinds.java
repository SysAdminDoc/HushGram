/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The kinds of row Instagram's Notifications page has listed on this phone, each with a line from the
 * first row of that kind, for Hide notifications by type (#756). The server names each row's kind
 * (comment_like, user_followed, reply_to_comment_with_threading) and Instagram ships none of the names,
 * so the list is built from what arrives. The lines hold other people's names and words, so they stay in
 * a file of their own, never in a backup or a report.
 */
public final class NotificationKinds {
    /** The file the kinds are kept in, each kind's name to its line. */
    public static final String STORE = "hushgram_notification_kinds";

    /** The most kinds kept. Past it a new kind isn't listed, though one named in the setting still is. */
    static final int MOST = 80;

    /** The longest line kept for a kind. Past it the line is cut and ends in three dots. */
    static final int LONGEST_LINE = 120;

    /** What's counted when reading a row's kind or line goes wrong. */
    static final String KINDS = "notification kinds";

    /** The server's names for kinds: words with underscores between. */
    private static final Pattern KIND = Pattern.compile("[A-Za-z0-9_]{1,80}");

    /** A name in Instagram's notification markup, {name|color|bold|link|badge}; the page shows the name. */
    private static final Pattern MARKUP = Pattern.compile("\\{([^\\{\\}|]*)\\|[^\\{\\}]*\\}");

    private static final Object lock = new Object();

    /** Each kind to its line, in the order first seen. Null until read from the file. */
    private static Map<String, String> seen;

    /** The setting's text last parsed, and the kinds it named, so each row doesn't parse it again. */
    private static String parsedFrom;
    private static Set<String> parsed = Collections.emptySet();

    private NotificationKinds() {
    }

    /** The kinds named in [saved], the way the setting keeps them: names with commas between. */
    public static Set<String> parse(@Nullable String saved) {
        Set<String> kinds = new TreeSet<>();
        if (saved == null) return kinds;
        for (String part : saved.split(",")) {
            String kind = part.trim();
            if (KIND.matcher(kind).matches()) kinds.add(kind);
        }
        return kinds;
    }

    /** [kinds] as the setting saves them: in order, commas between. */
    public static String join(Set<String> kinds) {
        return String.join(",", new TreeSet<>(kinds));
    }

    /** Whether rows of [kind] leave the page now: named in Hide notifications by type, and HushGram not paused. */
    static boolean hidden(@Nullable String kind) {
        if (kind == null || !Utils.settingsReady()) return false;
        String saved = Settings.HIDDEN_NOTIFICATION_KINDS.get();
        if (saved.isEmpty()) return false;
        synchronized (lock) {
            if (!saved.equals(parsedFrom)) {
                parsed = parse(saved);
                parsedFrom = saved;
            }
            return parsed.contains(kind);
        }
    }

    /**
     * A row of [kind] was just parsed. A kind not kept yet is kept with the row's line, so the row is
     * read once per kind, not once per row. Never throws.
     */
    static void saw(@Nullable String kind, @Nullable Object row) {
        if (kind == null || row == null || !KIND.matcher(kind).matches()) return;
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            synchronized (lock) {
                Map<String, String> kept = kept(context);
                String line = kept.get(kind);
                if (line != null && !line.isEmpty()) return;
                if (line == null && kept.size() >= MOST) return;
                String found = line(row);
                if (line != null && found.isEmpty()) return;
                kept.put(kind, found);
                store(context).edit().putString(kind, found).apply();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, KINDS, failure);
        }
    }

    /**
     * The kinds to list, as {kind, label} by name: each kind seen here, labelled with its line and its
     * name under it, then the hidden kinds this phone hasn't seen, by name alone, so they can be shown again.
     */
    public static List<String[]> offered(Context context, Set<String> hidden) {
        Map<String, String> kept;
        synchronized (lock) {
            kept = new LinkedHashMap<>(kept(context));
        }
        List<String[]> offered = new ArrayList<>();
        for (String kind : new TreeSet<>(kept.keySet())) offered.add(new String[]{kind, label(kind, kept.get(kind))});
        for (String kind : hidden) {
            if (!kept.containsKey(kind)) offered.add(new String[]{kind, kind});
        }
        return offered;
    }

    /** How the list shows a kind: its line, with the server's name for it under that. Just the name without a line. */
    static String label(String kind, @Nullable String line) {
        return line == null || line.isEmpty() ? kind : line + "\n" + kind;
    }

    /**
     * The row's line, near enough: the longest plain text in the row or one level into it, which is
     * where the server puts the words the page shows. Empty when there's none.
     */
    static String line(Object row) {
        String best = longest(row, true, "").replace('\n', ' ').trim();
        if (best.length() > LONGEST_LINE) best = best.substring(0, LONGEST_LINE - 3).trim() + "...";
        return best;
    }

    private static String longest(Object holder, boolean deeper, String best) {
        for (Class<?> type = holder.getClass(); type != null && own(type); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(holder);
                } catch (Throwable unreadable) {
                    continue;
                }
                if (value instanceof String) {
                    String text = shown((String) value).trim();
                    if (text.length() > best.length() && plain(text)) best = text;
                } else if (deeper && value != null && own(value.getClass())) {
                    best = longest(value, false, best);
                }
            }
        }
        return best;
    }

    /** Whether a class is Instagram's own, whose fields can hold the row's words, and not the platform's. */
    private static boolean own(Class<?> type) {
        if (type.isArray() || type.isEnum()) return false;
        String name = type.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("android.")
                && !name.startsWith("androidx.") && !name.startsWith("kotlin.") && !name.startsWith("dalvik.");
    }

    /** [text] as the page shows it: each name in markup put back as the name alone. */
    static String shown(String text) {
        return text.indexOf('{') < 0 ? text : MARKUP.matcher(text).replaceAll("$1");
    }

    /** Whether [text] reads as words someone would see: spaces between words, and no link, markup or list of ids. */
    static boolean plain(String text) {
        return text.indexOf(' ') > 0 && text.length() <= 600 && !text.contains("://") && text.indexOf('{') < 0
                && text.indexOf('|') < 0 && text.indexOf('=') < 0;
    }

    private static Map<String, String> kept(Context context) {
        if (seen == null) {
            Map<String, String> read = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : store(context).getAll().entrySet()) {
                if (read.size() >= MOST) break;
                if (KIND.matcher(entry.getKey()).matches() && entry.getValue() instanceof String) {
                    read.put(entry.getKey(), (String) entry.getValue());
                }
            }
            seen = read;
        }
        return seen;
    }

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    /** Forgets what was read from the file, so a test's file is read again. */
    public static void forgetForTests() {
        synchronized (lock) {
            seen = null;
            parsedFrom = null;
            parsed = Collections.emptySet();
        }
    }
}
