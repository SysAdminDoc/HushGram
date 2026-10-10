/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
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
 * <p>Hide videos, Hide photos, Hide carousels and Hide posts you've liked filter by the post an item
 * carries, whoever posted it, and Hidden accounts ({@link HiddenAccounts}) by who posted it, so they
 * sit on Home's own reads ({@link #homeItem}) rather than that helper, which Explore's chain of posts
 * and the shop and ad feeds read through too. Following is a feed of Home's, paged through the same
 * response parser, so it loses the same posts.
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

    /** Why a post you've liked was taken out, on {@link #TYPES_ROUTE}. */
    static final String LIKED = "liked";

    /** The report's count of posts Hide posts you've liked took out of Home. */
    static final String LIKED_REMOVED = "liked posts removed";

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
     * The page of Home's feed response being parsed on this thread, from {@link #homePageStarts} to
     * {@link #homePageParsed}: how many items it read, and how many of them {@link #filter} took out.
     * Null outside a page, so Home's store and every other feed's reads, on this thread or another,
     * never count toward one.
     */
    private static final ThreadLocal<int[]> PAGE = new ThreadLocal<>();

    /** Set when the latest page of Home's own feed response that held items lost one to {@link #filter}. Tests clear it. */
    static volatile boolean homePageLost;

    /** Set once the report has counted {@link #HOME_ENDED} for that page. Tests clear it. */
    static volatile boolean homePageEndCounted;

    /**
     * The home feed that first read the latest page's verdict, set by that read. Each account has a
     * feed of its own, so another account's Home, still waiting on its first page or offline, isn't
     * ended by a page that belonged to the account before it. Null until a feed reads it. Tests clear it.
     */
    static volatile WeakReference<Object> verdictFeed;

    /** The counted kind of a Home that was ended, empty, after its latest page lost items to the switches. */
    static final String HOME_ENDED = "home page ended with every post removed";

    /** The home feed whose flag the adapter is reading, from {@link #homeFeedRead} to {@link #feedEnded} on one thread. */
    private static final ThreadLocal<Object> READING = new ThreadLocal<>();

    /** Asks a home feed whether it's empty, as {@link #feedEmpty} answers. Tests stand in. */
    static volatile ToIntFunction<Object> emptiness = FeedSuggestions::feedEmpty;

    /** Whether Home's reads go through {@link #homeItem}, when a test says so instead of the build. */
    @Nullable
    static volatile Boolean homeReadsForTests;

    private FeedSuggestions() {
    }

    /**
     * Injected right before each read of the home feed adapter's "no next page" flag, with the feed
     * object the flag is read from, which {@link #feedEnded} asks whether it's empty. Never throws.
     */
    public static void homeFeedRead(Object feed) {
        try {
            READING.set(feed);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home feed read", failure);
        }
    }

    /**
     * Injected at each read of the home feed adapter's "no next page" flag. Answers 1 (no next
     * page) once the suggestion switches, or the post switches {@link #homeItem} takes posts out
     * for, have emptied Home ({@link #suggestionsEmptiedHome}), or once Hide the
     * home feed has emptied Home ({@link HomeFeed#emptied}), and [noMorePages] otherwise. Turning
     * every switch off restores Instagram's answer in this run.
     *
     * <p>Instagram reads that flag only beside its own checks that the feed is empty and no page is
     * loading. With both true and a next page left it draws its loading placeholder, and nothing asks
     * for that page while the feed is empty, so a Home emptied of suggestions kept the placeholder for
     * good. Saying there's no next page gets Instagram's own empty feed card instead. A feed with posts
     * left, or one waiting on a page, draws what it did.
     */
    public static int feedEnded(int noMorePages) {
        Object feed = READING.get();
        READING.remove();
        if (noMorePages != 0) return noMorePages;
        if (HomeFeed.emptied()) return 1;
        if (!tookOut && !typesTookOut) return noMorePages;
        try {
            if (!Utils.settingsReady()) return noMorePages;
            boolean suggestions = tookOut && suggestionSwitchOn();
            boolean posts = typesTookOut && postSwitchOn();
            return (suggestions || posts) && suggestionsEmptiedHome(feed) ? 1 : noMorePages;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "empty feed", failure);
            return noMorePages;
        }
    }

    /** Whether any switch {@link #homeItem} takes posts out for is on, a name on Hidden accounts included. */
    private static boolean postSwitchOn() {
        return Settings.HIDE_FEED_VIDEOS.get() || Settings.HIDE_FEED_PHOTOS.get()
                || Settings.HIDE_FEED_CAROUSELS.get() || Settings.HIDE_FEED_LIKED.get()
                || !HiddenAccounts.hidden().isEmpty();
    }

    /** Whether any switch {@link #filter} takes items out for is on. */
    private static boolean suggestionSwitchOn() {
        return Settings.HIDE_SUGGESTED_POSTS.get() || Settings.HIDE_SUGGESTED_ACCOUNTS.get()
                || Settings.HIDE_THREADS_POSTS.get() || Settings.HIDE_FEED_SURVEYS.get()
                || Settings.HIDE_FEED_SHOPPING.get();
    }

    /**
     * Whether the suggestion switches can have emptied Home, whose flag is read from [feed]. Where
     * Home's reads go through {@link #homeItem}, that's once the latest page of Home's own feed
     * response lost items to {@link #filter} ({@link #homePageParsed}) and the feed isn't known to
     * hold anything.
     *
     * <p>The helper {@link #filter} sits on also reads Explore's chain of posts and the shop and ad
     * feeds, and Home reads its store of the last run before its first page, so an item taken out
     * anywhere used to end a Home that was only waiting for that page, and Instagram drew its Welcome
     * to Instagram card there for a few seconds at startup (#28). None of those reads is a page of
     * Home's response, so none of them ends Home now.
     *
     * <p>The page's kept items don't hold the end off: an account that follows nobody gets a page of
     * suggestions with an item or two Instagram keeps and then draws elsewhere or not at all (#105).
     * Instagram itself only ends a feed it finds empty, and the feed is asked here too, so a Home
     * showing posts keeps Instagram's answer and the report counts {@link #HOME_ENDED} only for a
     * Home that really is empty. Without Home's reads in the build, it's once anything's been taken
     * out.
     */
    private static boolean suggestionsEmptiedHome(@Nullable Object feed) {
        Boolean forced = homeReadsForTests;
        boolean homeReads = forced != null ? forced : PatchFamily.feedTypesInBuild();
        if (!homeReads) return true;
        if (!homePageLost) return false;
        if (feed != null) {
            WeakReference<Object> bound = verdictFeed;
            if (bound == null) verdictFeed = new WeakReference<>(feed);
            else if (bound.get() != feed) return false;
        }
        int empty = feedIsEmpty(feed);
        if (empty == 0) return false;
        if (empty == 1 && !homePageEndCounted) {
            homePageEndCounted = true;
            FeedFilterCounters.sawKind(ROUTE, HOME_ENDED);
            Logger.printDebug(() -> "Feed suggestions: Home's latest page lost its items and Home is empty, so it ends");
        }
        return true;
    }

    /** 1 when [feed] says it's empty, 0 when it holds something, and -1 when it can't be asked. */
    private static int feedIsEmpty(@Nullable Object feed) {
        if (feed == null) return -1;
        try {
            int answer = emptiness.applyAsInt(feed);
            return answer == 0 || answer == 1 ? answer : -1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home feed empty check", failure);
            return -1;
        }
    }

    /**
     * Whether the home feed object the adapter reads its flag from is empty: 1 when it is, 0 when it
     * holds something, and -1 when it can't be told. The patch writes the body, which asks the feed
     * the question the adapter asks right after reading the flag.
     */
    public static int feedEmpty(Object feed) {
        return -1;
    }

    /**
     * Injected first thing in Home's feed response parser: a page of Home's own feed starts on this
     * thread, and the items {@link #homeItem} reads until {@link #homePageParsed} are its items.
     * Never throws.
     */
    public static void homePageStarts() {
        try {
            PAGE.set(new int[2]);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home page start", failure);
        }
    }

    /**
     * Injected right before each return of Home's feed response parser. A page that held items
     * replaces the verdict of the one before: whether it lost any to {@link #filter}. A page with no
     * items, the parser giving up on a response for one, leaves it. Never throws.
     */
    public static void homePageParsed() {
        try {
            int[] page = PAGE.get();
            PAGE.remove();
            if (page == null || page[0] == 0) return;
            homePageLost = page[1] > 0;
            homePageEndCounted = false;
            verdictFeed = null;
            if (homePageLost) {
                Logger.printDebug(() -> "Feed suggestions: a page of Home lost " + page[1] + " of its " + page[0] + " items");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home page parsed", failure);
        }
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
     * of one video, one photo or a carousel while that type's switch is on, a post you've liked
     * while Hide posts you've liked is on, or a post by an account on {@link HiddenAccounts}, and
     * [item] itself otherwise, or when anything goes wrong. An item with no post, a row of suggested
     * accounts for one, stays. Inside a page of Home's feed response it also counts the item toward
     * that page, and whether it was lost to {@link #filter} or to its own removals, for
     * {@link #homePageParsed}. Never throws.
     */
    public static Object homeItem(Object item) {
        return homeItem(item, FeedSuggestions::mediaType, FeedSuggestions::liked, FeedSuggestions::author);
    }

    static Object homeItem(Object item, ToIntFunction<Object> typeOf) {
        return homeItem(item, typeOf, FeedSuggestions::liked, FeedSuggestions::author);
    }

    static Object homeItem(Object item, ToIntFunction<Object> typeOf, ToIntFunction<Object> likedOf) {
        return homeItem(item, typeOf, likedOf, FeedSuggestions::author);
    }

    static Object homeItem(Object item, ToIntFunction<Object> typeOf, ToIntFunction<Object> likedOf,
            Function<Object, String> authorOf) {
        boolean lost = Boolean.TRUE.equals(JUST_TOOK_OUT.get());
        JUST_TOOK_OUT.remove();
        int[] page = PAGE.get();
        if (page != null && (item != null || lost)) {
            page[0]++;
            if (lost) page[1]++;
        }
        if (item == null) return null;
        Object kept = byType(item, typeOf) == null ? null
                : byLiked(item, likedOf) == null ? null : byAuthor(item, authorOf);
        if (kept == null && page != null) page[1]++;
        return kept;
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
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, kind + " posts removed");
            typesTookOut = true;
            Logger.printDebug(() -> "Feed suggestions: took a " + kind + " out of Home");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "post type", failure);
            return item;
        }
    }

    /**
     * [item], or null while Hide posts you've liked is on and its post says you've liked it. A post
     * that doesn't say, or an item with no post, stays.
     */
    private static Object byLiked(Object item, ToIntFunction<Object> likedOf) {
        try {
            if (!Utils.settingsReady() || !Settings.HIDE_FEED_LIKED.get()) return item;
            if (likedOf.applyAsInt(item) != 1) return item;
            FeedFilterCounters.removed(TYPES_ROUTE, 1, LIKED);
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, LIKED_REMOVED);
            typesTookOut = true;
            Logger.printDebug(() -> "Feed suggestions: took a post you've liked out of Home");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "liked post", failure);
            return item;
        }
    }

    /**
     * [item], or null when its post's author is on {@link HiddenAccounts} for the signed-in account.
     * The author isn't read while the list is empty. A post whose author can't be read stays.
     */
    private static Object byAuthor(Object item, Function<Object, String> authorOf) {
        try {
            if (HiddenAccounts.hidden().isEmpty()) return item;
            if (!HiddenAccounts.hides(authorOf.apply(item))) return item;
            FeedFilterCounters.removed(TYPES_ROUTE, 1, HiddenAccounts.HIDDEN);
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, HiddenAccounts.REMOVED);
            typesTookOut = true;
            Logger.printDebug(() -> "Feed suggestions: took a post from a hidden account out of Home");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, HiddenAccounts.HIDDEN, failure);
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

    /**
     * Whether you've liked the post a feed item carries: 1 when its has_liked says so, and 0 when it
     * says not, doesn't say or the item carries no post. The patch writes the body, which reads the
     * item's post field and the post's has_liked.
     */
    public static int liked(Object item) {
        return 0;
    }

    /**
     * The username of whoever posted the post a feed item carries, or null when it carries none or
     * the post doesn't say. The patch writes the body, which reads the item's post field, the post's
     * user and that user's username. Null as built.
     */
    public static String author(Object item) {
        return null;
    }

    private static BooleanSetting switchFor(String kind) {
        if (SUGGESTED_POST.equals(kind)) return Settings.HIDE_SUGGESTED_POSTS;
        if (THREADS_UNITS.contains(kind)) return Settings.HIDE_THREADS_POSTS;
        if (SURVEY_UNITS.contains(kind)) return Settings.HIDE_FEED_SURVEYS;
        if (SHOPPING_UNITS.contains(kind)) return Settings.HIDE_FEED_SHOPPING;
        return Settings.HIDE_SUGGESTED_ACCOUNTS;
    }
}
