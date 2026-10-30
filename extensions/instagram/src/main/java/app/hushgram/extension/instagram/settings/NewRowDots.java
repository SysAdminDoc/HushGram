/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.settings.Setting;

/**
 * A small dot beside a setting this build added since the settings were last opened, and beside
 * the row that opens the category it's in, so a long page shows what's new (#274).
 *
 * <p>There is no list of which release added what. The keys of the rows the person has already been
 * shown are kept in a file of their own, apart from the settings, so a backup neither exports nor
 * imports them. The first time the settings open with none stored, every row there now counts as
 * seen: a fresh install never shows a dot. A row whose key isn't in the set is new. Leaving a
 * expanded category (or closing the settings) adds its rows, so a dot shows for the visit that
 * reveals it and is gone by the next. With the categories off, the one long list is the page.
 *
 * <p>Only rows that are a saved setting count. A row that comes and goes on its own would
 * otherwise read as new each time it came back. Anything that goes wrong shows no dot.
 */
final class NewRowDots {
    static final String FILE = "hushgram_new_dots";
    static final String SEEN = "seen";
    /** Marks the dot's tag on a title, so a test can find it. */
    static final String TAG = "hushgram_new_dot";

    /** The page showing now, which the rows ask while they bind. Null when there is none. */
    @Nullable private static volatile NewRowDots current;

    private final SharedPreferences store;
    private final Set<String> seen = new HashSet<>();
    /** Each category opener row and the category it opens. */
    private final Map<Preference, PreferenceCategory> openers = new HashMap<>();
    private final Map<PreferenceCategory, List<Preference>> pages;
    /** A search lists rows from every category, so none of them is shown as new there. */
    private boolean searching;

    /** Reads what's been seen, or marks everything in [pages] seen when nothing is stored yet. */
    private NewRowDots(Context context, Map<PreferenceCategory, List<Preference>> pages) {
        this.pages = pages;
        store = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Set<String> stored = null;
        try {
            stored = store.getStringSet(SEEN, null);
        } catch (RuntimeException unreadable) {
            // A value of another kind is no set: start again, as on a first open.
        }
        if (stored != null) {
            seen.addAll(stored);
            return;
        }
        for (List<Preference> rows : pages.values()) addKeys(rows);
        save();
    }

    /** Starts the dots for a page built from [pages], or none when that can't be done. */
    static void start(Context context, Map<PreferenceCategory, List<Preference>> pages) {
        try {
            current = new NewRowDots(context, pages);
        } catch (RuntimeException unavailable) {
            current = null;
        }
    }

    /** The page is gone: its rows no longer ask. */
    static void stop() {
        current = null;
    }

    /** Remembers which category [opener] opens, so it shows a dot while that category has a new row. */
    static void opens(Preference opener, PreferenceCategory category) {
        NewRowDots dots = current;
        if (dots != null) dots.openers.put(opener, category);
    }

    /** Whether a search is on, which shows no dots. */
    static void searching(boolean on) {
        NewRowDots dots = current;
        if (dots != null) dots.searching = on;
    }

    /** The person has been through [category]'s page: its rows are no longer new. */
    static void left(PreferenceCategory category) {
        try {
            NewRowDots dots = current;
            if (dots == null || dots.searching) return;
            List<Preference> rows = dots.pages.get(category);
            if (rows != null) dots.leave(rows);
        } catch (RuntimeException ignored) {
            // The dot stays a visit longer.
        }
    }

    /** The settings are closing with every row on one list (or none open): all that was listed is seen. */
    static void leftEverything() {
        try {
            NewRowDots dots = current;
            if (dots == null || dots.searching) return;
            for (List<Preference> rows : dots.pages.values()) dots.leave(rows);
        } catch (RuntimeException ignored) {
            // The dots stay a visit longer.
        }
    }

    private static boolean counts(Preference row) {
        String key = row.getKey();
        return key != null && Setting.getSettingFromPath(key) != null;
    }

    private void addKeys(List<Preference> rows) {
        for (Preference row : rows) if (counts(row)) seen.add(row.getKey());
    }

    private void leave(List<Preference> rows) {
        int before = seen.size();
        addKeys(rows);
        if (seen.size() != before) save();
    }

    private void save() {
        try {
            store.edit().putStringSet(SEEN, new HashSet<>(seen)).apply();
        } catch (RuntimeException ignored) {
            // The dot comes back next time.
        }
    }

    private boolean isNew(Preference row) {
        return counts(row) && !seen.contains(row.getKey());
    }

    private boolean fresh(Preference row) {
        if (searching) return false;
        if (row instanceof HushgramPreferenceFragment.Heading
                && ((HushgramPreferenceFragment.Heading) row).expanded) return false;
        PreferenceCategory category = openers.get(row);
        if (category != null) {
            List<Preference> rows = pages.get(category);
            if (rows == null) return false;
            for (Preference each : rows) if (isNew(each)) return true;
            return false;
        }
        return isNew(row);
    }

    /** Paints [row]'s dot, or takes the old one off, as the page that is showing says. */
    static void paint(View row, Preference preference, int accent) {
        boolean fresh = false;
        try {
            NewRowDots dots = current;
            fresh = dots != null && dots.fresh(preference);
        } catch (RuntimeException failed) {
            // No dot.
        }
        paint(row, fresh, accent);
    }

    /**
     * Puts the dot after [row]'s title when [fresh], and takes any earlier one off. The dot is part
     * of the title's text line, so it follows the reading direction, wraps with the title at a large
     * text size and can't leave the row.
     */
    private static void paint(View row, boolean fresh, int accent) {
        try {
            TextView title = row.findViewById(android.R.id.title);
            if (title == null) return;
            boolean had = TAG.equals(title.getTag());
            if (!fresh) {
                if (had) {
                    title.setCompoundDrawablesRelative(null, null, null, null);
                    title.setTag(null);
                    title.setContentDescription(null);
                }
                return;
            }
            float density = row.getResources().getDisplayMetrics().density;
            int size = Math.round(8 * density);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            dot.setBounds(0, 0, size, size);
            title.setCompoundDrawablesRelative(null, null, dot, null);
            title.setCompoundDrawablePadding(Math.round(8 * density));
            title.setTag(TAG);
            title.setContentDescription(title.getText() + ", " + L10n.t("New"));
        } catch (RuntimeException ignored) {
            // No dot.
        }
    }
}
