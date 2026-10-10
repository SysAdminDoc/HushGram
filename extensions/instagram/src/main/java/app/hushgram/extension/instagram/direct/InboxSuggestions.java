/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide suggested accounts in DMs" patch.
 *
 * <p>Under your chats Instagram can show a section of accounts to follow. Your messages load it
 * with a list of units, each a group of accounts with a name, and the inbox builds that section
 * from the first unit: Accounts to follow when it's named {@link #ACCOUNTS_TO_FOLLOW}, or another
 * group, like the people who follow you. Follow requests come from the second unit, read
 * elsewhere. The patch hands the list to {@link #units} as the inbox starts building the section,
 * and while the switch is on a list led by Accounts to follow is answered with an empty one, so
 * the section isn't built. A list led by any other unit goes through, and follow requests stay.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram gets its own list.
 */
public final class InboxSuggestions {
    /** The name Instagram's unit of accounts to follow carries. */
    static final String ACCOUNTS_TO_FOLLOW = "suggested_accounts_to_follow";

    /** The diagnostic counter route: how often the inbox had the section, and how often it was left out. */
    static final String ROUTE = "Accounts to follow in messages";

    static final String SECTION = "accounts to follow";

    private static volatile boolean logged;

    private InboxSuggestions() {
    }

    /**
     * Injected first thing where your messages build their section of accounts. Answers an empty
     * list when [units] is led by Accounts to follow and the switch is on, and [units] itself
     * otherwise, or when anything goes wrong. Never throws, and never changes the list it's given.
     */
    public static List<?> units(List<?> units) {
        return units(units, InboxSuggestions::switchedOn);
    }

    static List<?> units(List<?> units, BooleanSupplier on) {
        if (units == null || units.isEmpty()) return units;
        try {
            HookStatus.invoked(FamilyNames.INBOX_SUGGESTIONS);
            if (!ACCOUNTS_TO_FOLLOW.equals(nameOf(units.get(0)))) return units;
            FeedFilterCounters.sawKind(ROUTE, SECTION);
            if (!on.getAsBoolean()) return units;
            FeedFilterCounters.removed(ROUTE, 1, SECTION);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Inbox suggestions: left Accounts to follow out of your messages");
            }
            return Collections.emptyList();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.INBOX_SUGGESTIONS, SECTION, failure);
            return units;
        }
    }

    /**
     * A unit's name, through the getName() Instagram's unit models keep their name for. Null for
     * an item without one, or one whose name isn't text.
     */
    static String nameOf(Object unit) throws ReflectiveOperationException {
        if (unit == null) return null;
        Method getName;
        try {
            getName = unit.getClass().getMethod("getName");
        } catch (NoSuchMethodException none) {
            return null;
        }
        // The model's class needn't be public, so its public method can't be called without this.
        getName.setAccessible(true);
        Object name = getName.invoke(unit);
        return name instanceof String ? (String) name : null;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_INBOX_SUGGESTIONS.get();
    }
}
