/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Leaves the Suggested people off the New message screen and the new group picker (#762).
 *
 * <p>Both pickers keep their rows in one controller, which the patch marks as the picker makes it
 * ({@link #picker}). The controller fills its rows two ways: the people Instagram suggests before
 * you type ({@link #suggested}), and the people matching what you type ({@link #results}). While
 * the switch is on, a marked controller gets no suggestions and no rows for an empty search, and
 * a search with text in it lists its matches as before. The same controller elsewhere, such as the
 * share sheet, is never marked and keeps its rows.
 *
 * <p>Fails open: with the switch off, HushGram paused, the settings not read yet or anything
 * thrown, Instagram gets its own list. The list handed in is never changed.
 */
public final class NewMessageSuggestions {
    /** The diagnostic counter route and the kind it counts. */
    static final String ROUTE = "Suggested people in New message";
    static final String SECTION = "suggested people";

    /** The pickers' row controllers, held weakly so a closed picker lets its controller go. */
    private static final Map<Object, Boolean> PICKERS = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean logged;

    private NewMessageSuggestions() {
    }

    /** Called with the row controller the New message and new group pickers make. */
    public static void picker(Object rows) {
        try {
            if (rows != null) PICKERS.put(rows, Boolean.TRUE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NEW_MESSAGE, "picker", failure);
        }
    }

    /** The people [rows] would suggest before anything is typed. Answers the list the controller shows. */
    public static List<?> suggested(Object rows, List<?> people) {
        return suggested(rows, people, NewMessageSuggestions::switchedOn);
    }

    static List<?> suggested(Object rows, List<?> people, BooleanSupplier on) {
        if (people == null || people.isEmpty()) return people;
        try {
            if (!isPicker(rows)) return people;
            HookStatus.invoked(FamilyNames.NEW_MESSAGE);
            FeedFilterCounters.sawKind(ROUTE, SECTION);
            if (!on.getAsBoolean()) return people;
            FeedFilterCounters.removed(ROUTE, people.size(), SECTION);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "New message: left the suggested people out of the picker");
            }
            return Collections.emptyList();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NEW_MESSAGE, SECTION, failure);
            return people;
        }
    }

    /**
     * The people [rows] would list for [query]. An empty query lists suggestions again, and gets
     * the same answer as {@link #suggested}; anything typed lists its matches untouched.
     */
    public static List<?> results(Object rows, String query, List<?> people) {
        return results(rows, query, people, NewMessageSuggestions::switchedOn);
    }

    static List<?> results(Object rows, String query, List<?> people, BooleanSupplier on) {
        if (people == null || people.isEmpty()) return people;
        try {
            if (query != null && !query.isEmpty()) return people;
            if (!isPicker(rows) || !on.getAsBoolean()) return people;
            return Collections.emptyList();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NEW_MESSAGE, "search", failure);
            return people;
        }
    }

    private static boolean isPicker(Object rows) {
        return rows != null && PICKERS.containsKey(rows);
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.get();
    }
}
