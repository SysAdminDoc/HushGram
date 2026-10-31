/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide suggested people on profiles" patch.
 *
 * <p>Instagram draws the accounts it suggests on a profile three ways, and the server picks which:
 * a row of them inside the row of action buttons (Follow, Message), a row of its own under the
 * header, and a person-plus button beside the action buttons that opens them (Discover people on
 * your own profile). The patch hands each of them here as Instagram builds it:
 * <ul>
 *   <li>{@link #inlineRow} gets the flag the action row reads to show its suggestions, and a 0 sends
 *       it down Instagram's own path for no suggestions, which hides the row's container;</li>
 *   <li>{@link #keepStandaloneRow} is asked before the header adds its row of suggestions, and a 0
 *       leaves the row out of the header's list, so nothing is bound for it;</li>
 *   <li>{@link #chainingButton} gets the person-plus button once Instagram has set it up, and hides
 *       it, and the row's other buttons spread over the space it leaves ({@link ActionRow}).</li>
 * </ul>
 * Every other row of the header (bio, counts, links, highlights) and the action buttons themselves
 * are left alone.
 *
 * <p>The followers and following lists are built elsewhere, and only one thing there belongs to this
 * class: the Suggested for you section under the list. The list reads, as it opens, whether to fetch
 * accounts to suggest, and the patch hands that answer to {@link #followList}. A 0 means it never
 * asks, so the section is never fetched or drawn and every account in the list stays.
 *
 * <p>Someone else's follow lists open in a pager with mutual, followers and following tabs, and it
 * can add a Suggested tab of accounts to follow (#302). The pager reads, as it opens, whether to add
 * that tab, and the patch hands that answer to {@link #suggestedTab}: a 0 leaves the tab out, and a
 * list opened on it starts on its first tab. The mutual tab draws Suggested for you under the people
 * you both follow, and the patch hands its accounts to {@link #mutualSuggestions} each time it draws
 * them: an empty list draws no section, and the mutual followers above stay. The same switch as
 * {@link #followList} takes both.
 *
 * <p>Your own profile can also show Complete your profile's cards (Add bio, Find people to follow)
 * above its posts. Its posts ask one check whether to add them, and {@link #hideCompletionCards}
 * answers first: a yes makes that check say no, so the cards are never added.
 *
 * <p>Your notifications can end with People you don't follow back, accounts that follow you with a
 * Follow back button on each. They come two ways, and the notifications' list builder draws both
 * under the same header. The older reply lists them under their own key, and its parser hands them to
 * {@link #followBacks} before keeping them: a null means there are none, so the section isn't built.
 * The newer reply sends them as one of its account sections with the type FOLLOW_BACK, and the
 * builder asks {@link #hidesSuggestionSection} about each section that has accounts, before it adds
 * the header: a yes skips the section, header, rows and footer, as if it had none.
 *
 * <p>Your notifications also get rows that only suggest something, a thread from an account you
 * don't follow or someone you might know (#181). The row parser hands each row and its kind to
 * {@link #notificationParsed}, and the reply's list asks {@link #keepNotification} before adding it:
 * a null there is the list's own check for a row that didn't parse, so the row is never drawn. The
 * same switch takes the account sections that only suggest people, Suggested for you among them, by
 * {@link #hidesSuggestionSection} (#112).
 *
 * <p>The reply also has a Featured section at the top, rows the server picks with a header of their own
 * (#259). It keeps that section's rows in a list of their own and its header beside them, and hands
 * each to {@link #featuredRows} and {@link #featuredHeader} as it keeps them: an empty list and no
 * header leave nothing of the section to draw.
 *
 * <p>Each hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer stands.
 */
public final class ProfileSuggestions {
    /** The diagnostic counter route: what each hook was handed, and what it hid. */
    static final String ROUTE = "Profile suggestions";

    static final String INLINE_ROW = "inline row";
    static final String STANDALONE_ROW = "standalone row";
    static final String CHAINING_BUTTON = "discover people button";
    static final String FOLLOW_LIST = "follow list section";
    static final String SUGGESTED_TAB = "suggested tab on follow lists";
    static final String MUTUAL_SUGGESTIONS = "suggested section under mutual followers";
    static final String COMPLETION_CARDS = "complete your profile cards";
    static final String FOLLOW_BACKS = "people you don't follow back";

    /** The type the notifications' reply gives the People you don't follow back section. */
    static final String FOLLOW_BACK_TYPE = "FOLLOW_BACK";

    /** The type the notifications' reply gives a section of your follow requests, never a suggestion. */
    static final String FOLLOW_REQUEST_TYPE = "FOLLOW_REQUEST";

    /** The diagnostic count of suggestion sections the notifications' builder asked about, any type. */
    static final String SECTIONS = "notification suggestion sections";

    /** The diagnostic counter route of the notifications' account sections, by the server's name for each. */
    static final String SECTION_TYPES = "Notification sections";

    /** What's counted when a section other than People you don't follow back goes with Hide suggestions in notifications. */
    static final String SUGGESTED_SECTIONS = "suggestion sections in notifications";

    /** The diagnostic counter route of notification rows, by the server's name for each row's kind. */
    static final String NOTIFICATIONS = "Notification rows";
    static final String NO_TYPE = "no type";
    static final String SUGGESTION_ROWS = "suggestion rows in notifications";

    /** What's counted when Hide Featured in notifications takes the Featured section's rows off. */
    static final String FEATURED = "Featured in notifications";

    /**
     * The server's names for notification rows that only suggest something to you, read off the
     * notifications of an account that gets them on Instagram 451 (2026-10-14): "<name>, who you might
     * know, is on Instagram" and "Check out your new follow suggestions" are connect_lowlness, and
     * "<name> posted a thread you might be interested in" is the Threads one. The "Follow suggestions"
     * row over the list (#112, 2026-10-19) is find_friend_activity.
     */
    static final Set<String> SUGGESTION_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "connect_lowlness",
            "find_friend_activity",
            "ig_text_app_non_onboarded_unconnected_daily_digest")));

    /** The prefix of every Threads ("text app") row, and the words in the names of those from accounts you aren't connected to. */
    static final String THREADS_PREFIX = "ig_text_app_";
    static final List<String> THREADS_SUGGESTION_WORDS = Collections.unmodifiableList(Arrays.asList("unconnected", "suggest", "recommend"));

    /**
     * The last row this thread's parser found to be a suggestion. The reply's list adds each row right
     * after parsing it, on the same thread, so the row checked there is this one. Weak, since the parser
     * also serves replies that never ask.
     */
    private static final ThreadLocal<Marked> lastSuggestion = new ThreadLocal<>();

    /**
     * The words in a Threads row's name that make it something you did or were part of: a reply, a
     * like, a mention, a repost, a quote, a follow, a tag, a comment or a message. A Threads row
     * without one is Instagram nudging you toward Threads ("posted a thread you might like"), which
     * Hide Featured in notifications takes too (#259).
     */
    static final List<String> THREADS_ACTIVITY_WORDS = Collections.unmodifiableList(Arrays.asList(
            "reply", "replies", "like", "likes", "liked", "mention", "mentions", "repost", "reposts", "quote", "quotes",
            "follow", "follower", "followers", "tag", "tagged", "comment", "comments", "message", "reaction", "reactions"));

    /** A row the parser just read: what it is, and the switches that take it out of the list. */
    private static final class Marked {
        final WeakReference<Object> row;
        final boolean suggestion;
        final boolean featured;

        Marked(Object row, boolean suggestion, boolean featured) {
            this.row = new WeakReference<>(row);
            this.suggestion = suggestion;
            this.featured = featured;
        }
    }

    /** What's counted when the row's text buttons take the hidden button's space, and when they give it back. */
    static final String ROW_SPREAD = ActionRow.ROW_SPREAD;
    static final String ROW_PUT_BACK = ActionRow.ROW_PUT_BACK;

    private ProfileSuggestions() {
    }

    /**
     * Injected right after the profile's action row reads whether to show its row of suggested
     * accounts. Answers 0 while the switch is on, and [show] otherwise, or when anything goes wrong.
     * A 0 coming in stays 0. Never throws.
     */
    public static int inlineRow(int show) {
        return inlineRow(show, ProfileSuggestions::switchedOn);
    }

    static int inlineRow(int show, BooleanSupplier on) {
        if (show == 0) return 0;
        return hides(INLINE_ROW, on) ? 0 : show;
    }

    /**
     * Injected where the profile header is about to add its row of suggested accounts. Answers 0,
     * leave it out, while the switch is on, and 1 otherwise, or when anything goes wrong. Never
     * throws.
     */
    public static int keepStandaloneRow() {
        return keepStandaloneRow(ProfileSuggestions::switchedOn);
    }

    static int keepStandaloneRow(BooleanSupplier on) {
        return hides(STANDALONE_ROW, on) ? 0 : 1;
    }

    /**
     * Injected right after Instagram sets up the person-plus button beside a profile's action
     * buttons. Hides it while the switch is on. Otherwise a button this hid before gets its old
     * visibility back, and any other is left as it is. Never throws.
     */
    public static void chainingButton(View button) {
        chainingButton(button, ProfileSuggestions::switchedOn);
    }

    static void chainingButton(View button, BooleanSupplier on) {
        if (button == null) return;
        boolean hide = hides(CHAINING_BUTTON, on);
        try {
            ActionRow.hide(button, hide, FamilyNames.PROFILE_SUGGESTIONS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, CHAINING_BUTTON, failure);
        }
    }

    /**
     * Spreads the action row's other buttons over the space the hidden person-plus button leaves
     * (#87), or takes that back once the button shows again ({@link ActionRow#fitRow}). Answers
     * whether a weight changed. Never throws.
     */
    static boolean fitRow(View button) {
        return ActionRow.fitRow(button, FamilyNames.PROFILE_SUGGESTIONS);
    }

    /**
     * Injected right after the followers or following list reads whether to fetch the accounts it
     * suggests under the list. Answers 0 while Hide Suggested for you on follow lists is on, and
     * [fetch] otherwise, or when anything goes wrong. A 0 coming in stays 0. Never throws.
     */
    public static int followList(int fetch) {
        return followList(fetch, ProfileSuggestions::followListOn);
    }

    static int followList(int fetch, BooleanSupplier on) {
        if (fetch == 0) return 0;
        return hides(FOLLOW_LIST, on) ? 0 : fetch;
    }

    /**
     * Injected right after the pager over someone else's follow lists reads whether to add its
     * Suggested tab (#302). Answers 0 while Hide Suggested for you on follow lists is on, and [add]
     * otherwise, or when anything goes wrong. A 0 coming in stays 0. Never throws.
     */
    public static int suggestedTab(int add) {
        return suggestedTab(add, ProfileSuggestions::followListOn);
    }

    static int suggestedTab(int add, BooleanSupplier on) {
        if (add == 0) return 0;
        return hides(SUGGESTED_TAB, on) ? 0 : add;
    }

    /**
     * Injected where the mutual followers list reads the accounts it suggests under the people you
     * both follow, right before it checks whether there are any (#302). Answers an empty list while
     * Hide Suggested for you on follow lists is on, so the list draws no Suggested for you section,
     * and [accounts] as they came otherwise, or when anything goes wrong. The list keeps its own copy
     * of the accounts either way. Counts only when there were accounts to hide. Never throws.
     */
    public static List<?> mutualSuggestions(List<?> accounts) {
        return mutualSuggestions(accounts, ProfileSuggestions::followListOn);
    }

    static List<?> mutualSuggestions(List<?> accounts, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
        if (accounts == null || accounts.isEmpty()) return accounts;
        return decides(MUTUAL_SUGGESTIONS, on) ? Collections.emptyList() : accounts;
    }

    /**
     * Injected first in the check your own profile's posts make before adding the Complete your
     * profile cards (#159). True, which answers that check no, while Hide Complete your profile is
     * on, and false, Instagram's own check, otherwise or when anything goes wrong. Never throws.
     */
    public static boolean hideCompletionCards() {
        return hideCompletionCards(ProfileSuggestions::completionOn);
    }

    static boolean hideCompletionCards(BooleanSupplier on) {
        return hides(COMPLETION_CARDS, on);
    }

    /**
     * Injected where the notifications' parser keeps the accounts it read for People you don't follow
     * back (#161), the suggestion section under your notifications. Answers null while Hide People you
     * don't follow back is on, the same as a reply without those accounts, so the notifications build
     * no section for them at all. Otherwise, or when anything goes wrong, [accounts] as they came.
     * A follow notification is a story of its own and never passes here. Counts the call even with
     * no accounts, so the diagnostic report shows the hook ran. Never throws.
     */
    public static List<?> followBacks(List<?> accounts) {
        return followBacks(accounts, ProfileSuggestions::followBacksOn);
    }

    static List<?> followBacks(List<?> accounts, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
        if (accounts == null || accounts.isEmpty()) return accounts;
        return decides(FOLLOW_BACKS, on) ? null : accounts;
    }

    /**
     * Injected where the notifications' list builder has an account section from the server with
     * accounts in it and is about to add its header (#161, #112). True skips the whole section. People
     * you don't follow back (FOLLOW_BACK) goes with Hide People you don't follow back, and follow
     * requests (FOLLOW_REQUEST) always stay. Every other section only suggests people and goes with
     * Hide suggestions in notifications: Suggested for you, which the server types SUGGESTED_USERS or
     * leaves untyped, SCHOOLMATES, and any type Instagram adds later, since the builder heads those
     * Suggested for you too. False, Instagram goes on to draw it, with the switch off, paused or
     * unready, or when anything goes wrong. Counts each type for the diagnostic report. Follow
     * notifications are stories, not sections, and never pass here. Never throws.
     */
    public static boolean hidesSuggestionSection(String type) {
        return hidesSuggestionSection(type, ProfileSuggestions::followBacksOn, ProfileSuggestions::notificationSuggestionsOn);
    }

    static boolean hidesSuggestionSection(String type, BooleanSupplier followBacks, BooleanSupplier suggestions) {
        HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
        HookStatus.counted(FamilyNames.PROFILE_SUGGESTIONS, SECTIONS);
        try {
            FeedFilterCounters.sawKind(SECTION_TYPES, type == null ? NO_TYPE : type);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, SUGGESTED_SECTIONS, failure);
        }
        if (FOLLOW_BACK_TYPE.equals(type)) return decides(FOLLOW_BACKS, followBacks);
        if (FOLLOW_REQUEST_TYPE.equals(type)) return false;
        return decides(SUGGESTED_SECTIONS, suggestions);
    }

    /**
     * Injected right before the notifications' row parser hands back a row, with the row and the
     * server's name for its kind (#181). Counts the name for the diagnostic report, and remembers a
     * row that only suggests something, so {@link #keepNotification} can leave it out of the list.
     * Never throws.
     */
    public static void notificationParsed(Object row, String type) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
            FeedFilterCounters.sawKind(NOTIFICATIONS, type == null ? NO_TYPE : type);
            boolean suggestion = row != null && isSuggestion(type);
            boolean featured = row != null && isFeaturedNudge(type);
            lastSuggestion.set(suggestion || featured ? new Marked(row, suggestion, featured) : null);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, SUGGESTION_ROWS, failure);
        }
    }

    /**
     * Whether a row of this kind only suggests something: one of {@link #SUGGESTION_TYPES}, or a
     * Threads row whose name says it comes from an account you aren't connected to or is a suggestion.
     * Replies, likes and mentions on Threads say neither and stay.
     */
    static boolean isSuggestion(String type) {
        if (type == null) return false;
        if (SUGGESTION_TYPES.contains(type)) return true;
        if (!type.startsWith(THREADS_PREFIX)) return false;
        for (String word : THREADS_SUGGESTION_WORDS) {
            if (type.contains(word)) return true;
        }
        return false;
    }

    /**
     * Whether a row of this kind is Instagram nudging you toward Threads: a Threads row whose name has
     * none of {@link #THREADS_ACTIVITY_WORDS}. The server names each row, and "<name> posted a thread you
     * might like" arrives under a name no suggestion word catches, so Hide Featured in notifications
     * takes every Threads row that isn't something you took part in (#259).
     */
    static boolean isFeaturedNudge(String type) {
        if (type == null || !type.startsWith(THREADS_PREFIX)) return false;
        for (String word : type.substring(THREADS_PREFIX.length()).split("_")) {
            if (THREADS_ACTIVITY_WORDS.contains(word)) return false;
        }
        return true;
    }

    /**
     * Injected where the notifications' reply adds each row it parsed to its list, the null check
     * right after. Answers null, which that check skips, for a row {@link #notificationParsed} found
     * to be a suggestion while Hide suggestions in notifications is on, or a Threads nudge while Hide
     * Featured in notifications is on. Any other row, the switch off, paused or unready, or anything
     * going wrong, answers [row] as it came. Never throws.
     */
    public static Object keepNotification(Object row) {
        return keepNotification(row, ProfileSuggestions::notificationSuggestionsOn, ProfileSuggestions::featuredOn);
    }

    static Object keepNotification(Object row, BooleanSupplier on) {
        return keepNotification(row, on, () -> false);
    }

    static Object keepNotification(Object row, BooleanSupplier on, BooleanSupplier featuredOn) {
        if (row == null) return null;
        Marked marked;
        try {
            marked = lastSuggestion.get();
            if (marked == null || marked.row.get() != row) return row;
            lastSuggestion.set(null);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, SUGGESTION_ROWS, failure);
            return row;
        }
        if (marked.suggestion && decides(SUGGESTION_ROWS, on)) return null;
        if (marked.featured && decides(FEATURED, featuredOn)) return null;
        return row;
    }

    /**
     * Injected where the notifications' list builder is about to turn a row into a line, with the
     * name of the section the server put it in. True skips the row while Hide Featured in
     * notifications is on and the section is the Featured one, wherever the row came from (the reply,
     * the phone's saved copy, a page loaded as you scroll), and with it the section's header, which
     * the builder only adds beside a row. False otherwise, or when anything goes wrong. Never throws.
     */
    public static boolean skipsFeaturedRow(String section) {
        return skipsFeaturedRow(section, ProfileSuggestions::featuredOn);
    }

    static boolean skipsFeaturedRow(String section, BooleanSupplier on) {
        if (!FEATURED_SECTION.equals(section)) return false;
        return hides(FEATURED, on);
    }

    /** The name the server gives the Featured section of the notifications, on each row it holds. */
    static final String FEATURED_SECTION = "priority_stories";

    /**
     * Injected where the notifications' reply keeps the rows of its Featured section (#259). Answers an
     * empty list while Hide Featured in notifications is on, so the section has no rows to draw, and
     * [rows] as they came with the switch off, paused or unready, or anything going wrong. Never throws.
     */
    public static List<?> featuredRows(List<?> rows) {
        return featuredRows(rows, ProfileSuggestions::featuredOn);
    }

    static List<?> featuredRows(List<?> rows, BooleanSupplier on) {
        if (rows == null || rows.isEmpty()) return rows;
        return hides(FEATURED, on) ? new ArrayList<>() : rows;
    }

    /**
     * Injected where the notifications' reply keeps its Featured section's header. Answers null while
     * Hide Featured in notifications is on, the same as a reply that sends no header, and [header] as it
     * came otherwise. Never throws.
     */
    public static Object featuredHeader(Object header) {
        return featuredHeader(header, ProfileSuggestions::featuredOn);
    }

    static Object featuredHeader(Object header, BooleanSupplier on) {
        if (header == null) return null;
        try {
            return on.getAsBoolean() ? null : header;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, FEATURED, failure);
            return header;
        }
    }

    /** Whether to hide this part now, counting the call and the part either way. False when anything goes wrong. */
    private static boolean hides(String part, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
        return decides(part, on);
    }

    /** Whether to hide this part now, counting the part either way. False when anything goes wrong. */
    private static boolean decides(String part, BooleanSupplier on) {
        try {
            FeedFilterCounters.sawKind(ROUTE, part);
            if (!on.getAsBoolean()) return false;
            FeedFilterCounters.removed(ROUTE, 1, part);
            Logger.printDebug(() -> "Profile suggestions: hid the " + part);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, part, failure);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_PROFILE_SUGGESTIONS.get();
    }

    private static boolean followListOn() {
        return Utils.settingsReady() && Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.get();
    }

    private static boolean completionOn() {
        return Utils.settingsReady() && Settings.HIDE_PROFILE_COMPLETION.get();
    }

    private static boolean followBacksOn() {
        return Utils.settingsReady() && Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.get();
    }

    private static boolean notificationSuggestionsOn() {
        return Utils.settingsReady() && Settings.HIDE_NOTIFICATION_SUGGESTIONS.get();
    }

    /** What's counted when the heart's dot is taken off because only Featured is behind it. */
    static final String FEATURED_DOT = "Featured dot on the heart";

    /**
     * Injected where the account's badge counts are built from the server's numbers (#189). [dot] is
     * the count behind the heart's red dot, [activity] the sum of the likes, comments, comment likes,
     * follows and tags counts. A dot with none of those behind it is the server pointing at something
     * Featured, so it answers 0 for it while Keep Featured off the heart's dot is on. Any real activity,
     * the switch off, paused or unready, or anything going wrong answers [dot] as it came. Never throws.
     */
    public static int featuredDot(int dot, int activity) {
        return featuredDot(dot, activity, ProfileSuggestions::featuredDotOn);
    }

    static int featuredDot(int dot, int activity, BooleanSupplier on) {
        if (dot <= 0 || activity > 0) return dot;
        return hides(FEATURED_DOT, on) ? 0 : dot;
    }

    private static boolean featuredDotOn() {
        return Utils.settingsReady() && Settings.HIDE_FEATURED_DOT.get();
    }

    private static boolean featuredOn() {
        return Utils.settingsReady() && Settings.HIDE_NOTIFICATION_FEATURED.get();
    }
}
