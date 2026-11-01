/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.lang.reflect.Array;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the notes row" patch.
 *
 * <p>Your messages are a list of sections (the search bar, the notes row, the inbox header, your
 * chats), and each time Instagram works the list out again it first names the sections it may
 * show. The patch hands that list of names to {@link #sections} before Instagram reads it, and
 * while the switch is on the notes row, the one Instagram 449 names {@link #NOTES}, is left out.
 * Its Map bubble sits inside that row, so it goes too. Every other section is built as before, so
 * search, requests and your chats stay, and so do notes elsewhere, like the bubble over a profile
 * picture.
 *
 * <p>A second hook answers the row's own question of whether it has a Map bubble to add, for
 * {@link #mapShown}, so the bubble can go while the notes stay.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer goes through untouched.
 */
public final class NotesRow {
    /** The constant name Instagram 449 gives the notes row's section. */
    static final String NOTES = "TRAY";

    /** The diagnostic counter route: how often the inbox named the row, and how often it was left out. */
    static final String ROUTE = "Notes row";

    static final String ROW = "notes row";

    static final String MAP = "Map bubble";

    private static volatile boolean logged;
    private static volatile boolean loggedMap;

    private NotesRow() {
    }

    /**
     * Injected where your messages name the sections they may show. Answers a copy of [sections]
     * without the notes row while the switch is on, and [sections] itself otherwise, or when
     * anything goes wrong. Never throws, and never changes the array it's given.
     */
    public static Object[] sections(Object[] sections) {
        return sections(sections, NotesRow::switchedOn);
    }

    static Object[] sections(Object[] sections, BooleanSupplier on) {
        if (sections == null) return null;
        try {
            HookStatus.invoked(FamilyNames.NOTES_ROW);
            int at = indexOf(sections, NOTES);
            if (at < 0) return sections;
            FeedFilterCounters.sawKind(ROUTE, ROW);
            if (!on.getAsBoolean()) return sections;
            Object[] kept = without(sections, at);
            FeedFilterCounters.removed(ROUTE, 1, ROW);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Notes row: left the notes row out of your messages");
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTES_ROW, ROW, failure);
            return sections;
        }
    }

    /**
     * Injected where the notes row reads whether it has a Map bubble to add. Answers 0 while the
     * switch is on, and [shown] otherwise, or when anything goes wrong. Never throws.
     */
    public static int mapShown(int shown) {
        return mapShown(shown, NotesRow::mapSwitchedOn);
    }

    static int mapShown(int shown, BooleanSupplier hide) {
        if (shown == 0) return 0;
        try {
            HookStatus.invoked(FamilyNames.NOTES_ROW);
            FeedFilterCounters.sawKind(ROUTE, MAP);
            if (!hide.getAsBoolean()) return shown;
            FeedFilterCounters.removed(ROUTE, 1, MAP);
            if (!loggedMap) {
                loggedMap = true;
                Logger.printDebug(() -> "Notes row: left the Map bubble out of the notes row");
            }
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTES_ROW, MAP, failure);
            return shown;
        }
    }

    /** Where the section Instagram calls [name] is in [sections], or -1 when it didn't name it this time. */
    static int indexOf(Object[] sections, String name) {
        for (int at = 0; at < sections.length; at++) {
            Object section = sections[at];
            if (section instanceof Enum && name.equals(((Enum<?>) section).name())) return at;
        }
        return -1;
    }

    /** A copy of [sections], of the same array type, without the section at [at]. */
    static Object[] without(Object[] sections, int at) {
        Object[] kept = (Object[]) Array.newInstance(sections.getClass().getComponentType(), sections.length - 1);
        System.arraycopy(sections, 0, kept, 0, at);
        System.arraycopy(sections, at + 1, kept, at, sections.length - at - 1);
        return kept;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_NOTES_ROW.get();
    }

    private static boolean mapSwitchedOn() {
        return Utils.settingsReady() && Settings.HIDE_NOTES_MAP.get();
    }
}
