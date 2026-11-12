/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import java.util.LinkedHashSet;
import java.util.Set;

import app.hushgram.extension.shared.L10n;

/**
 * Words people type for what the settings call something else (#642). "navigation bar" found
 * nothing, since the page says Tab bar. A typed word also finds the settings' own word, in
 * English and in the language HushGram shows, and still finds itself where a row uses it.
 */
final class SearchWords {
    private SearchWords() {}

    /** [english] is how the settings say it, [typed] the words people use for it, already lowercase. */
    private static final class Same {
        final String english;
        final String[] typed;

        Same(String english, String... typed) {
            this.english = english;
            this.typed = typed;
        }
    }

    private static final Same[] SAME = {
            new Same("Tab bar", "navigation", "nav", "navbar", "bottom", "toolbar"),
            new Same("Messages", "dm", "dms", "direct", "inbox", "chat"),
            new Same("Color", "colour"),
            new Same("Layout", "theme", "appearance", "style"),
            new Same("Reels", "clips", "shorts"),
            new Same("ads", "sponsored", "advert", "advertisement", "advertising"),
            new Same("Notifications", "alert", "push"),
            new Same("Feed", "home", "timeline"),
            new Same("Explore", "discover"),
            new Same("Pause and diagnostics", "debug", "log", "logs"),
            new Same("Downloads", "save", "saver", "download"),
            new Same("Battery", "power"),
    };

    /**
     * For each search term, every text that answers it: the term, and for a word in the table the
     * settings' word for it. A typed word counts when the term is that word, or from four letters on
     * the start of it, so "navig" already finds the Tab bar. A plural with no word of its own goes
     * by its singular ("alerts", "colours"), while short ones like "ads" stay as typed.
     */
    static String[][] needs(String[] terms) {
        String[][] needs = new String[terms.length][];
        for (int i = 0; i < terms.length; i++) {
            String term = terms[i];
            Set<String> any = new LinkedHashSet<>();
            any.add(term);
            if (!addSame(term, any)) {
                String singular = singular(term);
                if (singular != null) addSame(singular, any);
            }
            needs[i] = any.toArray(new String[0]);
        }
        return needs;
    }

    /** Whether [text] answers every term, each through any of its words. */
    static boolean holdsAll(String text, String[][] needs) {
        for (String[] any : needs) {
            boolean found = false;
            for (String word : any) {
                if (text.contains(word)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean addSame(String term, Set<String> any) {
        boolean added = false;
        for (Same same : SAME) {
            if (!applies(term, same.typed)) continue;
            add(any, HushgramPreferenceFragment.searchText(same.english));
            add(any, HushgramPreferenceFragment.searchText(L10n.t(same.english)));
            added = true;
        }
        return added;
    }

    private static boolean applies(String term, String[] typed) {
        for (String word : typed) {
            if (word.equals(term) || term.length() >= 4 && word.startsWith(term)) return true;
        }
        return false;
    }

    private static void add(Set<String> any, String word) {
        String trimmed = word.trim();
        if (!trimmed.isEmpty()) any.add(trimmed);
    }

    private static String singular(String term) {
        if (term.length() > 4 && term.endsWith("ies")) return term.substring(0, term.length() - 3) + "y";
        if (term.length() > 4 && term.endsWith("s")) return term.substring(0, term.length() - 1);
        return null;
    }
}
