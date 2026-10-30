/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide suggested accounts in Reels" patch.
 *
 * <p>Every item of a Reels page, a reel, an ad or one of the units Instagram puts between them,
 * comes out of one converter as a clips item, and every caller of that converter skips an item it
 * answers null for. The patch passes each item through {@link #filter} at the converter's returns,
 * and the units Instagram builds from a netego item, with that unit's type, through
 * {@link #netego} as well. An item answered null never reaches the viewer, so paging carries on as
 * if the unit hadn't been sent.
 *
 * <p>Suggested accounts come two ways: as items of their own kinds ({@link #KINDS}), and as netego
 * units, whose kind is NETEGO for every unit and whose type says what they hold
 * ({@link #NETEGO_TYPES}). Reels, ads, Your algorithm (TYA_IN_REELS), stories and every other unit
 * stay.
 *
 * <p>Every unit that stays and isn't a reel or an ad is counted by its kind, and every netego unit
 * that stays by its type, under {@link #KEPT_ROUTE}. A screen of suggestions that still shows (#167)
 * then has its name in the reporter's Diagnostics, with or without Debug logging.
 *
 * <p>The friends tab of Reels, when it has no reels to show, shows a page of people to follow
 * instead ("Instagram is better with friends", #167). Instagram makes that page into a clips item of
 * its own, outside the converter, so the patch passes it through {@link #friendsTab} where it's
 * made. An item answered null there is no page to Instagram, which then shows its own empty friends
 * tab.
 */
public final class ReelsSuggestions {
    /** The clips item kinds of a card of accounts or creators to follow, by the names Instagram 449 gives them. */
    static final Set<String> KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SUGGESTED_USERS", "CREATORS_YOU_MAY_FOLLOW", "NETEGO_SUGGESTED_USERS", "NETEGO_SUGGESTED_CREATORS")));

    /** The netego unit types of friends and creators to follow. */
    static final Set<String> NETEGO_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "friend_su_in_reels", "creators_in_reels")));

    /** The diagnostic counter route: the suggestions seen, and the ones taken out. */
    static final String ROUTE = "Reels suggestions";

    /** The diagnostic counter route for the units kept that aren't reels or ads, by kind or netego type. */
    static final String KEPT_ROUTE = "Reels units kept";

    /** What the friends tab's page of people to follow counts as, by the name Instagram logs it under. */
    static final String FRIENDS_TAB_PAGE = "suggested_users_friends_lane";

    /**
     * Reads an item's kind, whatever it is, from its enum field whose type names one of [markers];
     * {@link #kindOf} outside tests.
     */
    interface KindReader {
        String kindOf(Object item, Set<String> markers, String family) throws Exception;
    }

    private ReelsSuggestions() {
    }

    /**
     * Injected at each return of Instagram's Reels item converter. Answers null for a card of
     * accounts or creators to follow while the switch is on, and [item] itself otherwise, or when
     * anything goes wrong. Never throws.
     */
    public static Object filter(Object item) {
        return filter(item, ReelsSuggestions::kindOf, ReelsSuggestions::switchedOn);
    }

    static Object filter(Object item, KindReader reader, BooleanSupplier on) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.REELS_SUGGESTIONS);
            String kind = reader.kindOf(item, KINDS, FamilyNames.REELS_SUGGESTIONS);
            if (kind == null) return item;
            if (!KINDS.contains(kind)) {
                kept(kind);
                return item;
            }
            return takeOut(item, kind, on);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_SUGGESTIONS, "reels item", failure);
            return item;
        }
    }

    /**
     * Injected where the converter returns the item it made from a netego unit, with the unit's
     * type. Answers null for friends or creators to follow while the switch is on, and [item]
     * itself otherwise, or when anything goes wrong. Never throws.
     */
    public static Object netego(Object item, String type) {
        return netego(item, type, ReelsSuggestions::switchedOn);
    }

    static Object netego(Object item, String type, BooleanSupplier on) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.REELS_SUGGESTIONS);
            if (type == null || !NETEGO_TYPES.contains(type)) {
                FeedFilterCounters.sawKind(KEPT_ROUTE, "netego " + (type == null ? "untyped" : type));
                Logger.printDebug(() -> "Reels suggestions: kept a netego unit of type " + type);
                return item;
            }
            return takeOut(item, type, on);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_SUGGESTIONS, "netego unit", failure);
            return item;
        }
    }

    /**
     * Injected at each return of the method that makes the friends tab's page of people to follow
     * into a clips item. Answers null for it while the switch is on, so Instagram shows its own empty
     * friends tab in its place, and [item] itself otherwise, or when it isn't a card of people to
     * follow, or when anything goes wrong. Never throws.
     */
    public static Object friendsTab(Object item) {
        return friendsTab(item, ReelsSuggestions::kindOf, ReelsSuggestions::switchedOn);
    }

    static Object friendsTab(Object item, KindReader reader, BooleanSupplier on) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.REELS_SUGGESTIONS);
            String kind = reader.kindOf(item, KINDS, FamilyNames.REELS_SUGGESTIONS);
            if (kind == null) return item;
            if (!KINDS.contains(kind)) {
                FeedFilterCounters.sawKind(KEPT_ROUTE, FRIENDS_TAB_PAGE + " " + kind);
                return item;
            }
            return takeOut(item, FRIENDS_TAB_PAGE, on);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_SUGGESTIONS, "friends tab page", failure);
            return item;
        }
    }

    /**
     * A reel or an ad is what a Reels page is made of, and Hide ads counts ads; anything else is named.
     * A NETEGO item has already been through {@link #netego} at the same return, which names it by
     * its type, so it isn't counted twice (or, switched off, as kept when it was a suggestion).
     */
    private static void kept(String kind) {
        if (kind.equals("ORGANIC") || kind.equals("MULTI_ADS") || kind.equals("NETEGO") || kind.startsWith("AD")) return;
        FeedFilterCounters.sawKind(KEPT_ROUTE, kind);
    }

    /**
     * The item's kind from whichever of its enum fields has a type naming one of [markers]. Each
     * patch reading kinds checks at patch time that only one of the item's enum types names any.
     */
    private static String kindOf(Object item, Set<String> markers, String family) throws IllegalAccessException {
        for (String marker : markers) {
            String kind = FeedItemKinds.kindOf(item, marker, family);
            if (kind != null) return kind;
        }
        return null;
    }

    private static Object takeOut(Object item, String kind, BooleanSupplier on) {
        FeedFilterCounters.sawKind(ROUTE, kind);
        if (!on.getAsBoolean()) return item;
        FeedFilterCounters.removed(ROUTE, 1, kind);
        Logger.printDebug(() -> "Reels suggestions: took out a " + kind + " unit");
        return null;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_REELS_SUGGESTIONS.get();
    }
}
