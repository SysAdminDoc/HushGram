/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the search bar in Messages" patch.
 *
 * <p>It works where {@link NotesRow} does: each time Instagram works your messages out again it
 * first names the sections they may show, and the patch hands that list to {@link #sections}
 * before Instagram reads it. While the switch is on, the search bar's section, the one Instagram 452
 * names {@link #SEARCH}, is left out. That section is the whole row, the Meta AI search bar
 * included, so nothing is built where it was and no gap is left. Notes, requests and your chats
 * are built as before.
 *
 * <p>When both patches are in, each takes its own section out of the list the other hands on.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own list goes through untouched.
 */
public final class InboxSearchBar {
    /** The constant name Instagram 452 gives the search bar's section. */
    static final String SEARCH = "SEARCH_BAR";

    /** The diagnostic counter route: how often the inbox named the search bar, and how often it was left out. */
    static final String ROUTE = "Messages search bar";

    static final String ROW = "search bar";

    private static volatile boolean logged;

    private InboxSearchBar() {
    }

    /**
     * Injected where your messages name the sections they may show. Answers a copy of [sections]
     * without the search bar while the switch is on, and [sections] itself otherwise, or when
     * anything goes wrong. Never throws, and never changes the array it's given.
     */
    public static Object[] sections(Object[] sections) {
        return sections(sections, InboxSearchBar::switchedOn);
    }

    static Object[] sections(Object[] sections, BooleanSupplier on) {
        if (sections == null) return null;
        try {
            HookStatus.invoked(FamilyNames.INBOX_SEARCH);
            int at = NotesRow.indexOf(sections, SEARCH);
            if (at < 0) return sections;
            FeedFilterCounters.sawKind(ROUTE, ROW);
            if (!on.getAsBoolean()) return sections;
            Object[] kept = NotesRow.without(sections, at);
            FeedFilterCounters.removed(ROUTE, 1, ROW);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Messages search bar: left the search bar out of your messages");
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.INBOX_SEARCH, ROW, failure);
            return sections;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_INBOX_SEARCH.get();
    }
}
