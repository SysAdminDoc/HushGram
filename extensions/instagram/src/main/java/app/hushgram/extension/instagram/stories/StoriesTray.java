/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * Helper for the "Hide suggested stories" patch.
 *
 * <p>The row of stories at the top of Home is one row of Home's list, added by its story tray
 * binder group. The patch asks {@link #hideTray} as that group adds its row, and while Hide the
 * Stories tray is on the group adds none. Coming back to Home mid-feed, from a story opened in DMs
 * say, Instagram can also move the tray into an overlay above the feed without building the row
 * again (#88), so the patch asks {@link #keepTrayOutOfOverlay} there and the overlay stays empty
 * while the tray is hidden. Keep the Stories tray in the feed answers yes there too, and the row
 * stays in the feed instead of floating over it (#357).
 *
 * <p>The tray's items come from its own request, each marked with a reel type, and a story from an
 * account you don't follow is a suggested one. The patch passes each item the tray's parser reads
 * through {@link #filter}, which answers null for a suggested one while Hide suggested stories is
 * on, a rewind card while Hide story rewinds is on, and a card Instagram made from what's been
 * posted before while Hide memories and recaps is on. The parser skips it the way it skips an item
 * that didn't parse. With Stop loading stories and highlights on, every item goes, and {@link #remaining} empties
 * the list of reels the tray would fetch after them, so nothing in the row loads. With Filter the
 * Stories tray by verified accounts on, an account's story goes when its owner's verified badge
 * isn't the one chosen (#340), read through {@link #trayOwner}, {@link #userVerified} and
 * {@link #userId}, which the patch fills. Your own story, by Home's signed-in account, stays.
 *
 * <p>Instagram also puts cards of accounts to follow between the stories you tap through, two ways.
 * One is a reel of its own in the tray's answer, the empty story state, whose cards suggest
 * accounts, and {@link #filter} answers null for it while Hide Suggested for you between stories is
 * on. The other is a netego, a unit the viewer takes in beside the ads, whose type names it. The
 * patch hands that type to {@link #netego} first thing in the viewer's check of each ad or netego,
 * and a yes makes the check answer no, so the viewer leaves the unit out the way it leaves out one
 * that isn't valid.
 *
 * <p>The full-screen Suggested for you page between stories (#154) is neither. The viewer builds
 * it itself, as a midcard: a reel whose type Instagram turns into a name such as
 * story_interstitial_suggested_users_midcard. Each piece of code that adds a midcard builds its
 * reel first and adds it after, so the patch hands the new reel's type name to {@link #midcard}
 * right after the build, and a yes makes that code answer that it had no card to add.
 *
 * <p>The Add Yours cards between stories (#111), Popular Add Yours and Join a trending Add Yours
 * among them, are midcards too, and the trending prompts can also come as a netego. Both answer
 * yes for them while Hide Add Yours cards between stories is on.
 *
 * <p>The Music for you card isn't a tray item. Instagram puts it between the stories in the viewer,
 * when a server flag says the card is on, so the patch passes that flag's answer through
 * {@link #musicCard} and a yes becomes a no while Hide the Music for you card is on.
 */
public final class StoriesTray {
    /**
     * The reel types of suggested tray items, by the constant names Instagram 449 gives them: an
     * account to follow, its story, and a creator's story.
     */
    static final Set<String> SUGGESTED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SUGGESTED_USER", "SUGGESTED_USER_REEL", "SUGGESTED_CREATOR_REEL")));

    /** Rewind cards, which bring back old highlights. */
    static final Set<String> REWINDS = Collections.singleton("HIGHLIGHT_REWIND_REEL");

    /**
     * Cards Instagram makes from what's been posted before, by Instagram 450's names: memories, your
     * week, the year in review, follow anniversaries and birthdays.
     */
    static final Set<String> RECAPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MEMORY_REEL", "MY_WEEK_REEL", "END_OF_YEAR", "FOLLOW_VERSARIES", "BIRTHDAY_HIGHLIGHTS")));

    /** The empty story state's reel, whose cards suggest accounts to follow. */
    static final Set<String> SUGGESTION_CARDS = Collections.singleton("EMPTY_STORY_STATE_REEL");

    /**
     * The netego types of a Suggested for you card between stories, by the names Instagram 450
     * gives them: accounts, businesses and creators to follow.
     */
    static final Set<String> SUGGESTED_NETEGOS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "suggested_users_in_story", "suggested_businesses_in_story", "cymf_in_stories")));

    /** Every reel type a switch can take out. */
    static final Set<String> FILTERED;

    static {
        Set<String> all = new HashSet<>(SUGGESTED);
        all.addAll(REWINDS);
        all.addAll(RECAPS);
        all.addAll(SUGGESTION_CARDS);
        FILTERED = Collections.unmodifiableSet(all);
    }

    /** The diagnostic counter routes for the tray's suggested items, rewinds and recaps. */
    static final String ROUTE = "Suggested stories";
    static final String REWIND_ROUTE = "Story rewinds";
    static final String RECAP_ROUTE = "Memories and recaps";
    static final String STOP_ROUTE = "Stop loading stories and highlights";
    static final String SUGGESTION_ROUTE = "Suggested for you between stories";
    static final String BADGE_ROUTE = "Stories by verified badge";

    /** The diagnostic counter route for Music for you card checks answered no, one for each time Instagram asked. */
    static final String MUSIC_CARD_ROUTE = "Music for you card";
    static final String MUSIC_FLAG_ON = "Instagram said yes";
    static final String MUSIC_FLAG_OFF = "Instagram said no";

    /**
     * Every reel type the tray's parser reads, counted by name, so a report from an account that's
     * shown a card no switch covers yet (#111's Music for you) says which type it is.
     */
    static final String KINDS_ROUTE = "Stories tray reel types";

    /**
     * Every ad and netego the stories viewer checks, counted by netego type (an ad as {@link #AD}),
     * so a report from an account that still sees cards between stories (#154) shows whether they
     * reach the check at all, and under which type.
     */
    static final String UNITS_ROUTE = "Stories viewer units";
    static final String AD = "ad";

    /**
     * Every midcard the stories viewer builds, counted by its type's name, so a report from an
     * account that still sees a card between stories says which one it is.
     */
    static final String MIDCARD_ROUTE = "Stories viewer midcards";

    /** The midcard of the full-screen Suggested for you page between stories, by Instagram 451's name. */
    static final Set<String> SUGGESTED_MIDCARDS = Collections.singleton("story_interstitial_suggested_users_midcard");

    /**
     * The Add Yours midcards between stories, by Instagram 451's names: prompts to add yours to,
     * Add Yours templates, and the trending Add Yours prompts (#111).
     */
    static final Set<String> ADD_YOURS_MIDCARDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "story_interstitial_add_yours_midcard", "story_interstitial_ayu_midcard",
            "story_interstitial_trending_midcard")));

    /** The netego type of the trending Add Yours prompts between stories, by Instagram 451's name. */
    static final Set<String> ADD_YOURS_NETEGOS = Collections.singleton("trending_prompts_in_story");

    /** The diagnostic counter route for Add Yours cards between stories, midcards and netegos alike. */
    static final String ADD_YOURS_ROUTE = "Add Yours cards between stories";

    /** A constant only the reel type enum has among the tray item's enum fields. */
    private static final String REEL_TYPE_MARKER = "USER_REEL";

    private static volatile boolean loggedTray;

    private StoriesTray() {
    }

    /**
     * Injected first thing where Home's story tray adds its row. Answers true, and the tray stays out, while Hide the Stories tray is on, and
     * false otherwise, or when anything goes wrong. Never throws, and never waits for the settings:
     * before they're ready the tray stays.
     */
    public static boolean hideTray() {
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (!Utils.settingsReady() || !Settings.HIDE_STORIES_TRAY.get()) return false;
            if (!loggedTray) {
                loggedTray = true;
                Logger.printDebug(() -> "Stories tray: left the tray out of Home");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "tray row", failure);
            return false;
        }
    }

    /**
     * Injected first thing where Instagram floats the tray over the feed, as you come back to Home
     * mid-feed or scroll up quickly. Answers true, and the tray stays in its row at the top of the
     * feed, while Hide the Stories tray or Keep the Stories tray in the feed is on (#357: on some
     * accounts the floating tray stayed over the feed after a swipe between tabs and never scrolled
     * away). False otherwise, or when anything goes wrong. Never throws, and never waits for the
     * settings: before they're ready the tray floats as Instagram decides.
     */
    public static boolean keepTrayOutOfOverlay() {
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (!Utils.settingsReady()) return false;
            return Settings.HIDE_STORIES_TRAY.get() || Settings.KEEP_TRAY_IN_FEED.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "floating tray", failure);
            return false;
        }
    }

    /**
     * Injected where the tray's parser reads each item. Answers null for a suggested item, a rewind
     * or a recap while its switch is on, an account's story whose owner's verified badge isn't the
     * one kept, and [item] itself otherwise, or when anything goes wrong. Never throws.
     */
    public static Object filter(Object item) {
        return filter(item, StoriesTray::trayOwner, StoriesTray::userVerified, StoriesTray::userId);
    }

    /** {@link #filter(Object)}, reading a story's owner, its badge and its id through [owner], [verified] and [id]. */
    static Object filter(Object item, Function<Object, Object> owner, Function<Object, Boolean> verified,
            Function<Object, String> id) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            String type = FeedItemKinds.kindOf(item, REEL_TYPE_MARKER, FamilyNames.STORIES_TRAY);
            FeedFilterCounters.sawKind(KINDS_ROUTE, type);
            if (stopLoading()) {
                FeedFilterCounters.removed(STOP_ROUTE, 1, "any");
                return null;
            }
            String kind = FeedItemKinds.kindIn(item, FILTERED, FamilyNames.STORIES_TRAY);
            if (kind == null) return REEL_TYPE_MARKER.equals(type) ? byBadge(item, owner, verified, id) : item;
            String route = routeFor(kind);
            FeedFilterCounters.sawKind(route, kind);
            if (!Utils.settingsReady() || !switchFor(kind).get()) return item;
            FeedFilterCounters.removed(route, 1, kind);
            Logger.printDebug(() -> "Stories tray: took out a " + kind + " item");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "tray item", failure);
            return item;
        }
    }

    /**
     * A story [item] of an account's own, while Filter the Stories tray by verified accounts is on
     * (#340): null when its owner's verified badge isn't the one {@link Settings#TRAY_BADGE} keeps,
     * and [item] itself for your own story, an owner whose badge or id Instagram didn't send, or
     * with the switch off.
     */
    private static Object byBadge(Object item, Function<Object, Object> ownerOf, Function<Object, Boolean> verifiedOf,
            Function<Object, String> idOf) {
        if (!Utils.settingsReady() || !Settings.FILTER_TRAY_BY_BADGE.get()) return item;
        Object owner = ownerOf.apply(item);
        if (owner == null) return item;
        Boolean verified = verifiedOf.apply(owner);
        String id = idOf.apply(owner);
        if (verified == null || id == null || id.isEmpty() || id.equals(HiddenAccounts.signedInAccount())) return item;
        String badge = verified ? "verified" : "not verified";
        FeedFilterCounters.sawKind(BADGE_ROUTE, badge);
        if (verified == (Settings.TRAY_BADGE.get() == TrayBadge.VERIFIED)) return item;
        FeedFilterCounters.removed(BADGE_ROUTE, 1, badge);
        return null;
    }

    /** The account whose story a tray [item] is, a User. The patch writes the body. Null as built. */
    public static Object trayOwner(Object item) {
        return null;
    }

    /** Whether [user], a User, has Instagram's verified badge, or null when it doesn't say. The patch writes the body. Null as built. */
    public static Boolean userVerified(Object user) {
        return null;
    }

    /** [user]'s id, a User's, or null. The patch writes the body. Null as built. */
    public static String userId(Object user) {
        return null;
    }

    /**
     * Injected where the tray's parser reads the ids of the reels it fetches after the tray's items.
     * Answers an empty list while Stop loading stories and highlights is on, and [ids] itself otherwise, or when
     * anything goes wrong. Never throws.
     */
    public static ArrayList<?> remaining(ArrayList<?> ids) {
        if (ids == null || ids.isEmpty()) return ids;
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (!stopLoading()) return ids;
            FeedFilterCounters.removed(STOP_ROUTE, ids.size(), "reel left to fetch");
            return new ArrayList<>();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "reels left to fetch", failure);
            return ids;
        }
    }

    /**
     * Injected first thing in the stories viewer's check of an ad or a netego it's about to take
     * in, with the netego's type (null for an ad). Answers true, leave it out, for a Suggested for
     * you card while Hide Suggested for you between stories is on, a trending Add Yours card while
     * Hide Add Yours cards between stories is on, and false otherwise, or when anything goes wrong.
     * Never throws, and never waits for the settings.
     */
    public static boolean netego(String type) {
        try {
            FeedFilterCounters.sawKind(UNITS_ROUTE, type == null ? AD : type);
            if (type == null) return false;
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (SUGGESTED_NETEGOS.contains(type)) return leaveOut(SUGGESTION_ROUTE, Settings.HIDE_STORY_SUGGESTIONS, type);
            if (ADD_YOURS_NETEGOS.contains(type)) return leaveOut(ADD_YOURS_ROUTE, Settings.HIDE_ADD_YOURS_CARDS, type);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "story netego", failure);
            return false;
        }
    }

    /**
     * Injected right after the stories viewer builds a midcard to add between stories, with the
     * name of its type. Answers true, leave it out, for the Suggested for you page while Hide
     * Suggested for you between stories is on, an Add Yours card while Hide Add Yours cards between
     * stories is on, and false otherwise, or when anything goes wrong. Never throws, and never
     * waits for the settings.
     */
    public static boolean midcard(String kind) {
        if (kind == null) return false;
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            FeedFilterCounters.sawKind(MIDCARD_ROUTE, kind);
            if (SUGGESTED_MIDCARDS.contains(kind)) return leaveOut(SUGGESTION_ROUTE, Settings.HIDE_STORY_SUGGESTIONS, kind);
            if (ADD_YOURS_MIDCARDS.contains(kind)) return leaveOut(ADD_YOURS_ROUTE, Settings.HIDE_ADD_YOURS_CARDS, kind);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "story midcard", failure);
            return false;
        }
    }

    /** Counts a card [kind] a switch covers under [route], and answers whether [flag] leaves it out. */
    private static boolean leaveOut(String route, BooleanSetting flag, String kind) {
        FeedFilterCounters.sawKind(route, kind);
        if (!Utils.settingsReady() || !flag.get()) return false;
        FeedFilterCounters.removed(route, 1, kind);
        Logger.printDebug(() -> "Stories: left out a " + kind + " card");
        return true;
    }

    /**
     * Injected right after Instagram reads the server flag that lets its Music for you card into
     * the stories viewer ([enabled] nonzero for yes). Answers no while Hide the Music for you card
     * is on, and Instagram's own answer otherwise, or when the settings aren't ready or anything
     * goes wrong. Never throws, and never waits for the settings.
     */
    public static boolean musicCard(int enabled) {
        boolean instagram = enabled != 0;
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            // Every ask is counted by Instagram's answer, so a report from an account that still sees
            // the card (#111) shows whether the card came through this flag at all.
            FeedFilterCounters.sawKind(MUSIC_CARD_ROUTE, instagram ? MUSIC_FLAG_ON : MUSIC_FLAG_OFF);
            if (!instagram || !Utils.settingsReady() || !Settings.HIDE_MUSIC_CARD.get()) return instagram;
            FeedFilterCounters.removed(MUSIC_CARD_ROUTE, 1, "card left out");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "music card", failure);
            return instagram;
        }
    }

    private static boolean stopLoading() {
        return Utils.settingsReady() && Settings.STOP_LOADING_STORIES.get();
    }

    private static String routeFor(String kind) {
        if (SUGGESTED.contains(kind)) return ROUTE;
        if (REWINDS.contains(kind)) return REWIND_ROUTE;
        return SUGGESTION_CARDS.contains(kind) ? SUGGESTION_ROUTE : RECAP_ROUTE;
    }

    private static BooleanSetting switchFor(String kind) {
        if (SUGGESTED.contains(kind)) return Settings.HIDE_SUGGESTED_STORIES;
        if (REWINDS.contains(kind)) return Settings.HIDE_STORY_REWINDS;
        return SUGGESTION_CARDS.contains(kind) ? Settings.HIDE_STORY_SUGGESTIONS : Settings.HIDE_STORY_RECAPS;
    }
}
