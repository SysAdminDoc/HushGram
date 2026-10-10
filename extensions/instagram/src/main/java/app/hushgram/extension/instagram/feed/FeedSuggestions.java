/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * Helper for the "Hide suggested posts" patch.
 *
 * <p>The patch passes every item Instagram's home feed parse helper reads through {@link #filter},
 * beside Hide Reels in the feed's filter when both are in. Suggestions are items of their own kinds: a row
 * of accounts to follow is one of the {@link #ACCOUNT_UNITS}, and a single post or reel from an
 * account you don't follow, labeled "Suggested for you" or "Suggested Reel", is an
 * {@link #SUGGESTED_POST}, which carries its post inside it. A post from an account you follow is a
 * MEDIA item and stays. Threads' units ({@link #THREADS_UNITS}) bring in posts, communities and
 * accounts from Threads, a survey ({@link #SURVEY_UNITS}) asks you to rate what you saw, and the
 * {@link #SHOPPING_UNITS} offer products. Each comes back as null while its switch is on, and every
 * caller of that
 * helper skips a null item, the home feed's page loads and its cache of recommended posts alike.
 *
 * <p>Explore's grid doesn't go through that helper (S22, Instagram 449), so it keeps its posts.
 *
 * <p>Hide videos, Hide photos and Hide carousels filter by the post an item carries, whoever posted
 * it, so they sit on Home's own reads ({@link #homeItem}) rather than that helper, which Explore's
 * chain of posts and the shop and ad feeds read through too.
 */
public final class FeedSuggestions {
    /**
     * The feed item kinds of suggested accounts, shops, hashtags and lists, by the constant names
     * Instagram 449 gives them.
     */
    static final Set<String> ACCOUNT_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SUGGESTED_USERS", "SUGGESTED_TOP_ACCOUNTS", "SUGGESTED_PRODUCERS", "SUGGESTED_PRODUCERS_V2",
            "SUGGESTED_CLOSE_FRIENDS", "SUGGESTED_BUSINESSES", "SUGGESTED_SHOPS", "SUGGESTED_HASHTAGS",
            "SUGGESTED_SHAREABLE_LISTS", "FOLLOW_CHAIN_USERS", "TYA_SUGGESTIONS_IN_FEED_UNIT")));

    /** The kind of a single suggested post or reel ("explore_story" in the feed's JSON). */
    static final String SUGGESTED_POST = "EXPLORE_STORY";

    /**
     * Threads' units: its posts ("threads_in_feed_unit", and the one at the end of the feed), and
     * the ones its JSON names text_app_ (accounts to follow on Threads, communities, live chats and
     * game threads).
     */
    static final Set<String> THREADS_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "THREADS_IN_FEED_UNIT", "TIFU_IN_EXPLORE", "EOF_TIFU", "KICKSTART_FEED_UNIT",
            "COMMUNITIES_IN_FEED_UNIT", "SMSL_IN_FEED_UNIT", "LIVE_CHAT_IN_FEED_UNIT", "SPORT_GAME_IN_FEED_UNIT",
            "THREADS_IN_FEED_UNIT_MUSE", "VERTICALS_IN_FEED_UNIT")));

    /** The survey between posts ("in_feed_survey" in the feed's JSON). */
    static final Set<String> SURVEY_UNITS = Collections.singleton("FEED_SURVEY");

    /** Products to shop, product picks from a post, and live shopping. */
    static final Set<String> SHOPPING_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SHOPPING_RECOMMENDATION_UNIT", "PRODUCT_PIVOTS", "LIVE_SHOPPING_NETEGO")));

    /** Every kind this patch reads. */
    static final Set<String> KINDS;

    static {
        Set<String> kinds = new HashSet<>(ACCOUNT_UNITS);
        kinds.add(SUGGESTED_POST);
        kinds.addAll(THREADS_UNITS);
        kinds.addAll(SURVEY_UNITS);
        kinds.addAll(SHOPPING_UNITS);
        KINDS = Collections.unmodifiableSet(kinds);
    }

    /** The diagnostic counter route: the suggestions seen, and the ones taken out. */
    static final String ROUTE = "Feed suggestions";

    /** Instagram's media_type values for a post of one photo, one video (reels too) and a carousel. */
    static final int PHOTO = 1, VIDEO = 2, CAROUSEL = 8;

    /** The diagnostic counter route of {@link #homeItem}: the post types read, and the posts taken out. */
    static final String TYPES_ROUTE = "Home post types";

    /** The counted kind of a post whose type isn't one of the three, or an item with no post. */
    static final String OTHER_TYPE = "other type";

    /** Set once {@link #filter} has taken an item out of the home feed in this run. Tests clear it. */
    static volatile boolean tookOut;

    /** Set once {@link #homeItem} has taken a post out of Home in this run. Tests clear it. */
    static volatile boolean typesTookOut;

    /**
     * Set on the thread {@link #filter} has just taken an item out on, and cleared by its next call
     * there, so the Home read right after it can tell an item it lost to a suggestion switch from one
     * the helper had none for.
     */
    private static final ThreadLocal<Boolean> JUST_TOOK_OUT = new ThreadLocal<>();

    /**
     * How long Home's reads can go quiet and still be one read. A page of Home's feed, or its store of
     * the last run, is read in one go, so a longer gap starts a new read ({@link #readingHome}).
     */
    static final long READ_GAP_MS = 2_000;

    /** When {@link #homeItem} last ran, by {@link #clock}. Tests clear it. */
    static volatile long homeReadAt;

    /** The clock Home's reads are timed by. Tests stand in. */
    static volatile LongSupplier clock = SystemClock::elapsedRealtime;

    /** Set once Home's latest read has lost an item to {@link #filter}. Tests clear it. */
    static volatile boolean homeLost;

    /** Set once Home's latest read has kept an item that carries a post. Tests clear it. */
    static volatile boolean homeKept;

    /** Set once Home's latest read has kept an item that carries no post. Tests clear it. */
    static volatile boolean homeKeptNoPost;

    /** Set once the report has counted the end of Home's latest read ({@link #HOME_ENDED}). Tests clear it. */
    static volatile boolean homeEndCounted;

    /** The counted kind of a Home page that was ended because every post in it was removed. */
    static final String HOME_ENDED = "home page ended with every post removed";

    /** The counted kind of an item Home kept that carries no post (an end of feed or a header unit). */
    static final String KEPT_NO_POST = "kept item without a post";

    /** Whether Home's reads go through {@link #homeItem}, when a test says so instead of the build. */
    @Nullable
    static volatile Boolean homeReadsForTests;

    private FeedSuggestions() {
    }

    /**
     * Injected at each read of the home feed adapter's "no next page" flag. Answers 1 (no next
     * page) once {@link #filter} has taken items out and a suggestion switch is still on, once
     * {@link #homeItem} has taken posts out and a post type switch is still on, or once Hide the
     * home feed has emptied Home ({@link HomeFeed#emptied}), and [noMorePages] otherwise. Turning
     * every switch off restores Instagram's answer in this run.
     *
     * <p>Instagram reads that flag only beside its own checks that the feed is empty and no page is
     * loading. With both true and a next page left it draws its loading placeholder, and nothing asks
     * for that page while the feed is empty, so a Home emptied of suggestions kept the placeholder for
     * good. Saying there's no next page gets Instagram's own empty feed card instead. A feed with posts
     * left, or one waiting on a page, draws what it did.
     *
     * <p>The suggestion switches end Home only once they can have emptied it ({@link #suggestionsEmptiedHome}).
     */
    public static int feedEnded(int noMorePages) {
        if (noMorePages != 0) return noMorePages;
        if (HomeFeed.emptied()) return 1;
        if (!tookOut && !typesTookOut) return noMorePages;
        try {
            if (!Utils.settingsReady()) return noMorePages;
            if (tookOut && suggestionsEmptiedHome() && (Settings.HIDE_SUGGESTED_POSTS.get()
                    || Settings.HIDE_SUGGESTED_ACCOUNTS.get() || Settings.HIDE_THREADS_POSTS.get())) return 1;
            return typesTookOut && (Settings.HIDE_FEED_VIDEOS.get() || Settings.HIDE_FEED_PHOTOS.get()
                    || Settings.HIDE_FEED_CAROUSELS.get()) ? 1 : noMorePages;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "empty feed", failure);
            return noMorePages;
        }
    }

    /**
     * Whether the suggestion switches can have emptied Home. Where Home's reads go through
     * {@link #homeItem}, that's once those reads have lost an item to {@link #filter} and kept none.
     * The helper {@link #filter} sits on also reads Explore's chain of posts and the shop and ad
     * feeds, and Home reads its store of the last run before its first page, so an item taken out
     * anywhere used to end a Home that was only waiting for that page, and Instagram drew its
     * Welcome to Instagram card there for a few seconds at startup (#28). Only Home's latest read
     * counts ({@link #readingHome}). Without those reads in the build, it's once anything's been taken
     * out.
     */
    private static boolean suggestionsEmptiedHome() {
        Boolean forced = homeReadsForTests;
        boolean homeReads = forced != null ? forced : PatchFamily.feedTypesInBuild();
        if (!homeReads) return true;
        if (!homeLost || homeKept) return false;
        if (homeKeptNoPost && !homeEndCounted) {
            homeEndCounted = true;
            FeedFilterCounters.sawKind(ROUTE, HOME_ENDED);
        }
        return true;
    }

    /**
     * Injected where the home feed's load more row asks whether its feed's pages come from
     * Following. Only then does Instagram hide the row under an end of feed card with no posts
     * above it, once there's no next page. Past that card the pages are suggested posts, paged from
     * another source, so the rule stopped applying just when it was needed. Answers 1 once
     * {@link #filter} has taken items out and Hide suggested posts is on, and [following] otherwise.
     * The rule still needs the card in the feed and no post in it, so a feed with posts keeps its
     * row. Never throws.
     */
    public static int endCardRule(int following) {
        if (following != 0 || !suggestionsGone()) return following;
        Logger.printDebug(() -> "Feed suggestions: load more row checked as Following's");
        return 1;
    }

    /**
     * Injected where that rule asks whether there's a next page. Answers 0 (no next page) once
     * {@link #filter} has taken items out and Hide suggested posts is on, and [hasMore] otherwise.
     *
     * <p>Everything past the end card is a suggested post, which {@link #filter} takes out, so the
     * pages come in empty while a next page is still promised, and the row kept its spinner under
     * the card for good. With no next page the row goes. Never throws.
     */
    public static int moreAfterFollowing(int hasMore) {
        if (hasMore == 0 || !suggestionsGone()) return hasMore;
        Logger.printDebug(() -> "Feed suggestions: no next page past the end card");
        return 0;
    }

    /** Whether {@link #filter} has taken items out and Hide suggested posts is on. */
    private static boolean suggestionsGone() {
        if (!tookOut) return false;
        try {
            return Utils.settingsReady() && Settings.HIDE_SUGGESTED_POSTS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "end card", failure);
            return false;
        }
    }

    /**
     * Injected at the return of Instagram's feed item parse helper. Answers null for a unit of
     * suggested accounts, a suggested post or a Threads unit while its switch is on, and [item]
     * itself otherwise, or when anything goes wrong. Never throws.
     */
    public static Object filter(Object item) {
        JUST_TOOK_OUT.remove();
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            String kind = FeedItemKinds.kindIn(item, KINDS, FamilyNames.FEED_SUGGESTIONS);
            if (kind == null) return item;
            FeedFilterCounters.sawKind(ROUTE, kind);
            BooleanSetting setting = switchFor(kind);
            if (!Utils.settingsReady() || !setting.get()) return item;
            FeedFilterCounters.removed(ROUTE, 1, kind);
            tookOut = true;
            JUST_TOOK_OUT.set(Boolean.TRUE);
            Logger.printDebug(() -> "Feed suggestions: took out a " + kind + " item");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "feed item", failure);
            return item;
        }
    }

    /**
     * Injected right after Home keeps each item it reads, from its feed response and from its store
     * of the last run, beside Hide the home feed's filter when both are in. Answers null for a post
     * of one video, one photo or a carousel while that type's switch is on, and [item] itself
     * otherwise, or when anything goes wrong. An item with no post, a row of suggested accounts for
     * one, stays. Also notes whether Home lost the item to {@link #filter} or kept it, for
     * {@link #suggestionsEmptiedHome}. Never throws.
     */
    public static Object homeItem(Object item) {
        return homeItem(item, FeedSuggestions::mediaType);
    }

    static Object homeItem(Object item, ToIntFunction<Object> typeOf) {
        boolean lost = Boolean.TRUE.equals(JUST_TOOK_OUT.get());
        JUST_TOOK_OUT.remove();
        readingHome();
        if (item == null) {
            if (lost) homeLost = true;
            return null;
        }
        Object kept = byType(item, typeOf);
        if (kept != null) {
            if (carriesPost(kept, typeOf)) homeKept = true;
            else {
                homeKeptNoPost = true;
                FeedFilterCounters.sawKind(TYPES_ROUTE, KEPT_NO_POST);
            }
        }
        return kept;
    }

    /**
     * Starts a new read of Home once the last item came more than {@link #READ_GAP_MS} ago, forgetting
     * what the reads before it lost and kept. Instagram asks whether Home has ended only once Home is
     * empty, so posts an earlier read kept (another account's before a switch, or an earlier load's)
     * are no longer there. Remembered for the whole run, they held off the end of a Home whose next
     * read lost everything, and it kept its loading placeholder for good (#104, #105).
     */
    private static void readingHome() {
        long now = clock.getAsLong();
        if (now - homeReadAt > READ_GAP_MS) {
            homeLost = false;
            homeKept = false;
            homeKeptNoPost = false;
            homeEndCounted = false;
        }
        homeReadAt = now;
    }

    /**
     * Whether a kept item carries a post. A Home whose first page was all suggestions can still keep
     * an item or two with none (a unit with no post of its own), and counting those as kept posts held
     * off the end of Home for good: Instagram's loading placeholder stayed on an account that follows
     * nobody (#105). Instagram ends Home only when it has nothing to draw, so this only lets the end
     * through. Skipped while every suggestion switch is off, and an item whose post can't be read
     * counts as one, so a failure never ends a Home that has posts.
     */
    private static boolean carriesPost(Object item, ToIntFunction<Object> typeOf) {
        try {
            if (!Utils.settingsReady() || !(Settings.HIDE_SUGGESTED_POSTS.get()
                    || Settings.HIDE_SUGGESTED_ACCOUNTS.get() || Settings.HIDE_THREADS_POSTS.get())) return true;
            return typeOf.applyAsInt(item) != 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "post check", failure);
            return true;
        }
    }

    /** [item], or null while the switch for its post's type is on. */
    private static Object byType(Object item, ToIntFunction<Object> typeOf) {
        try {
            if (!Utils.settingsReady()) return item;
            boolean videos = Settings.HIDE_FEED_VIDEOS.get();
            boolean photos = Settings.HIDE_FEED_PHOTOS.get();
            boolean carousels = Settings.HIDE_FEED_CAROUSELS.get();
            if (!videos && !photos && !carousels) return item;
            int type = typeOf.applyAsInt(item);
            String kind = type == VIDEO ? "video" : type == PHOTO ? "photo" : type == CAROUSEL ? "carousel" : OTHER_TYPE;
            FeedFilterCounters.sawKind(TYPES_ROUTE, kind);
            boolean hide = type == VIDEO ? videos : type == PHOTO ? photos : type == CAROUSEL && carousels;
            if (!hide) return item;
            FeedFilterCounters.removed(TYPES_ROUTE, 1, kind);
            typesTookOut = true;
            Logger.printDebug(() -> "Feed suggestions: took a " + kind + " out of Home");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "post type", failure);
            return item;
        }
    }

    /**
     * The media_type of the post a feed item carries: 1 for one photo, 2 for one video, 8 for a
     * carousel, and 0 when it carries none or the post doesn't say. The patch writes the body, which
     * reads the item's post field and the post's media_type.
     */
    public static int mediaType(Object item) {
        return 0;
    }

    private static BooleanSetting switchFor(String kind) {
        if (SUGGESTED_POST.equals(kind)) return Settings.HIDE_SUGGESTED_POSTS;
        if (THREADS_UNITS.contains(kind)) return Settings.HIDE_THREADS_POSTS;
        if (SURVEY_UNITS.contains(kind)) return Settings.HIDE_FEED_SURVEYS;
        if (SHOPPING_UNITS.contains(kind)) return Settings.HIDE_FEED_SHOPPING;
        return Settings.HIDE_SUGGESTED_ACCOUNTS;
    }
}
