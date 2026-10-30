/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.reels.CaptionWordReels;
import app.hushgram.extension.instagram.reels.FeedReels;
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

    /** The kind counted for a post Hide reels by caption words' Also on Home took out of Home. */
    static final String CAPTION_WORD = "caption word";

    /** The report's count of posts Also on Home took out of Home for a word in their caption. */
    static final String CAPTION_WORD_REMOVED = "caption word posts removed";

    /** The report's count of Suggested for you titles {@link #sectionTitle} left off a header with nothing under it. */
    static final String SECTION_TITLE_DROPPED = "suggested section titles left off with nothing under them";

    /** The diagnostic counter route of the kinds {@link #sectionTitle} found in the Suggested for you section. */
    static final String SECTION_ROUTE = "Suggested section";

    /** The report's counts of how each {@link #sectionTitle} call went. */
    static final String SECTION_CHECKED = "suggested section titles checked";
    static final String SECTION_KEPT_SHOWS = "suggested section titles kept, something shows";
    static final String SECTION_KEPT_NOTHING_TAKEN = "suggested section titles kept, empty with nothing taken out";
    static final String SECTION_KEPT_UNREAD = "suggested section titles kept, section unreadable";

    /** The report's counts of how each {@link #caughtUpTitle} call went, before {@link #sectionTitle}'s decision. */
    static final String CAUGHT_UP_CHECKED = "caught up card titles checked";
    static final String CAUGHT_UP_KEPT_NO_SECTION = "caught up card titles kept, no section under them";

    private static final String END_CARD_KIND = "END_OF_FEED_DEMARCATOR";

    /** The end of feed card's kind. Instagram moves one that opens a section above that section's header. */
    static final Set<String> END_CARD = Collections.singleton("END_OF_FEED_DEMARCATOR");

    /** Reads the items a section holds, as {@link #sectionItems} does. Tests stand in. */
    static volatile Function<Object, List<?>> sections = FeedSuggestions::sectionItems;

    /** Reads the section an end of feed card shows under its title, as {@link #caughtUpSection} does. Tests stand in. */
    static volatile Function<Object, Object> caughtUpSections = FeedSuggestions::caughtUpSection;

    /** Set once {@link #filter} has taken an item out of the home feed in this run. Tests clear it. */
    static volatile boolean tookOut;

    /** Set once {@link #homeItem} has taken a post out of Home in this run. Tests clear it. */
    static volatile boolean typesTookOut;

    /** Set once Hide ads has taken a sponsored post out of a page of Home's response in this run ({@link #adTakenOut}). Tests clear it. */
    static volatile boolean adsTookOut;

    /**
     * Set on the thread {@link #filter} has just taken an item out on, and cleared by its next call
     * there, so the Home read right after it can tell an item it lost to a suggestion switch from one
     * the helper had none for.
     */
    private static final ThreadLocal<Boolean> JUST_TOOK_OUT = new ThreadLocal<>();

    /**
     * The page of Home's feed response being parsed on this thread, from {@link #homePageStarts} to
     * {@link #homePageParsed}: how many items it read, how many were taken out, and whether a real
     * MEDIA post with a positive media_type stayed.
     * Null outside a page, so Home's store and every other feed's reads, on this thread or another,
     * never count toward one.
     */
    private static final ThreadLocal<int[]> PAGE = new ThreadLocal<>();

    /** Set when the latest page of Home's own feed response that held items lost one to {@link #filter}. Tests clear it. */
    static volatile boolean homePageLost;

    /** Whether that page kept a real MEDIA post, even if its adapter has not shown it yet. Tests clear it. */
    static volatile boolean homePageKeptMedia;

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

    /** The counted kind of an Older Posts page whose loading placeholder was dropped after every post on it was removed. */
    static final String OLDER_ENDED = "older posts page ended with every post removed";

    /** How long after a page of Home's response lost every item the Older Posts placeholder may still be dropped, in nanoseconds. */
    static final long OLDER_WINDOW_NANOS = 10_000_000_000L;

    /** Set when the latest page of Home's response that held items lost all of them. Tests clear it. */
    static volatile boolean pageLostEverything;

    /** When that page was parsed, by {@link #clock}. Tests clear it. */
    static volatile long pageLostEverythingAt;

    /** Set once the report has counted {@link #OLDER_ENDED} for that page. Tests clear it. */
    static volatile boolean olderEndCounted;

    /** Counts the pages of Home's response that held items, so a feed can tell a page parsed after it opened. */
    static volatile long pageSeq;

    /** The most contextual feeds {@link #olderPageLoading} remembers the page count of at once. */
    private static final int OLDER_FEEDS_MAX = 8;

    /** One contextual feed's load more policy, and how many pages had been parsed when it first asked. */
    private static final class OlderFeed {
        final WeakReference<Object> policy;
        final long seqAtOpen;

        OlderFeed(Object policy, long seqAtOpen) {
            this.policy = new WeakReference<>(policy);
            this.seqAtOpen = seqAtOpen;
        }
    }

    /** The contextual feeds that have asked, oldest first. Guarded by itself. */
    private static final ArrayList<OlderFeed> OLDER_FEEDS = new ArrayList<>();

    /** The load more policy the contextual builder on this thread is about to ask, from {@link #olderPolicyAsked}. */
    private static final ThreadLocal<Object> OLDER_POLICY = new ThreadLocal<>();

    /** Forgets every contextual feed. Tests call it. */
    static void forgetOlderFeeds() {
        synchronized (OLDER_FEEDS) {
            OLDER_FEEDS.clear();
        }
    }

    /** The time {@link #olderPageLoading} measures a page's age by. Tests stand in. */
    static volatile LongSupplier clock = System::nanoTime;

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
     * object the flag is read from, which {@link #feedEnded} asks whether it's empty. A build of
     * Home's list waiting on a short page learns its feed here too ({@link HomeNextPage#feedRead}).
     * Never throws.
     */
    public static void homeFeedRead(Object feed) {
        try {
            READING.set(feed);
            HomeNextPage.feedRead(feed);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home feed read", failure);
        }
    }

    /**
     * Injected at each read of the home feed adapter's "no next page" flag. Answers 1 (no next
     * page) once the suggestion switches, the post switches {@link #homeItem} takes posts out
     * for or Hide ads have emptied Home ({@link #suggestionsEmptiedHome}), or once Hide the
     * home feed has emptied Home ({@link HomeFeed#emptied}), and [noMorePages] otherwise. Turning
     * every switch off restores Instagram's answer in this run.
     *
     * <p>Instagram reads that flag only beside its own checks that the feed is empty and no page is
     * loading. With both true and a next page left it draws its loading placeholder, and nothing asks
     * for that page while the feed is empty, so a Home emptied of suggestions kept the placeholder for
     * good. Saying there's no next page gets Instagram's own empty feed card instead, and
     * {@link HomeNextPage} still asks for For you's next page (#28), so posts it brings replace the
     * card. While that page is on its way the card waits a few seconds, so it doesn't flash before
     * the posts (#126). A feed with posts left, or one waiting on a page, draws what it did.
     */
    public static int feedEnded(int noMorePages) {
        Object feed = READING.get();
        READING.remove();
        if (noMorePages != 0) return noMorePages;
        if (HomeFeed.emptied()) return 1;
        if (!tookOut && !typesTookOut && !adsTookOut) return noMorePages;
        try {
            if (!Utils.settingsReady()) return noMorePages;
            return switchesTookOut() && suggestionsEmptiedHome(feed) ? 1 : noMorePages;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "empty feed", failure);
            return noMorePages;
        }
    }

    /**
     * Whether a switch that took something out of Home in this run is still on: a suggestion switch
     * after {@link #filter} took an item out, a post switch after {@link #homeItem} took a post out,
     * or Hide ads after it took an ad out of a page. Callers check {@link Utils#settingsReady} first.
     */
    static boolean switchesTookOut() {
        return (tookOut && suggestionSwitchOn()) || (typesTookOut && postSwitchOn())
                || (adsTookOut && Settings.HIDE_ADS.get());
    }

    /** Whether any switch {@link #homeItem} takes posts out for is on, a name on Hidden accounts included. */
    private static boolean postSwitchOn() {
        return Settings.HIDE_FEED_VIDEOS.get() || Settings.HIDE_FEED_PHOTOS.get()
                || Settings.HIDE_FEED_CAROUSELS.get() || Settings.HIDE_FEED_LIKED.get()
                || !HiddenAccounts.hidden().isEmpty() || !CaptionWordReels.homeWordsWhenOn().isEmpty();
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
     * <p>A retained MEDIA post with a real media_type keeps Instagram's answer even while its
     * adapter is empty or unreadable. Other kept items don't hold the end off: an account that
     * follows nobody gets suggestions with metadata Instagram draws elsewhere or not at all (#105).
     * Instagram itself only ends a feed it finds empty, and the feed is asked here too, so a Home
     * showing posts keeps Instagram's answer and the report counts {@link #HOME_ENDED} only for a
     * Home that really is empty. An empty Home whose next page is on its way keeps Instagram's
     * loading row a few seconds longer ({@link HomeNextPage#holdsEnd}), since that page usually
     * brings the followed posts (#126). Without Home's reads in the build, it's once anything's been
     * taken out.
     */
    private static boolean suggestionsEmptiedHome(@Nullable Object feed) {
        Boolean forced = homeReadsForTests;
        boolean homeReads = forced != null ? forced : PatchFamily.feedTypesInBuild();
        if (!homeReads) return true;
        if (!homePageLost || homePageKeptMedia) return false;
        if (feed != null) {
            WeakReference<Object> bound = verdictFeed;
            if (bound == null) verdictFeed = new WeakReference<>(feed);
            else if (bound.get() != feed) return false;
        }
        int empty = feedIsEmpty(feed);
        if (empty == 0) return false;
        if (HomeNextPage.holdsEnd(feed)) return false;
        if (empty == 1 && !homePageEndCounted) {
            homePageEndCounted = true;
            FeedFilterCounters.sawKind(ROUTE, HOME_ENDED);
            Logger.printDebug(() -> "Feed suggestions: Home's latest page lost its items and Home is empty, so it ends");
        }
        return true;
    }

    /** 1 when [feed] says it's empty, 0 when it holds something, and -1 when it can't be asked. */
    static int feedIsEmpty(@Nullable Object feed) {
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
            PAGE.set(new int[3]);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home page start", failure);
        }
    }

    /**
     * Injected right before each return of Home's feed response parser. A page that held items
     * replaces the verdict of the one before: whether it lost any to {@link #filter}. It also tells
     * {@link HomeNextPage} how many items the page kept, so a page the switches left short gets the
     * next one asked for. A page with no items, the parser giving up on a response for one, leaves
     * the verdict, and only tells {@link HomeNextPage} a request of its own was answered. Never throws.
     */
    public static void homePageParsed() {
        try {
            int[] page = PAGE.get();
            PAGE.remove();
            if (page == null) return;
            if (page[0] == 0) {
                HomeNextPage.emptyPageParsed();
                return;
            }
            HomeNextPage.pageParsed(page[0], page[1]);
            homePageKeptMedia = page[2] != 0;
            homePageLost = page[1] > 0;
            homePageEndCounted = false;
            pageSeq++;
            pageLostEverything = page[1] >= page[0];
            pageLostEverythingAt = clock.getAsLong();
            olderEndCounted = false;
            verdictFeed = null;
            if (homePageLost) {
                Logger.printDebug(() -> "Feed suggestions: a page of Home lost " + page[1] + " of its " + page[0] + " items");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home page parsed", failure);
        }
    }

    /**
     * Called by Hide ads' feed filter each time it takes a sponsored post or an ad pivot out. Inside
     * a page of Home's response being parsed on this thread, the item counts toward that page as read
     * and taken out, as one {@link #filter} took out does, so a page Hide ads left with no posts ends
     * Home or the Older Posts page instead of keeping a loading row. Outside a page (Home's store,
     * Explore and the other feeds the helper reads) it does nothing. Never throws.
     */
    public static void adTakenOut() {
        try {
            int[] page = PAGE.get();
            if (page == null) return;
            page[0]++;
            page[1]++;
            adsTookOut = true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "ad taken out", failure);
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

    /**
     * Injected right before the contextual feed's model builder asks its load more policy whether the
     * feed is loading, with that policy: one object per contextual feed, which {@link #olderPageLoading}
     * keys what it knows of the feed by. Never throws.
     */
    public static void olderPolicyAsked(Object policy) {
        try {
            OLDER_POLICY.set(policy);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "older posts policy", failure);
        }
    }

    /**
     * Injected right after the contextual feed's model builder asks whether its feed is loading, in
     * the builder that draws a full-screen loading row while the feed is loading and empty. The
     * Older Posts page, opened from the "Older posts" link under Home's end card, is a contextual
     * feed. Its first page comes back through the same parser as Home's, and when the post switches
     * or Hide suggested posts take every post out of it, the builder runs once with the page empty
     * and the fetch still marked as loading, and nothing runs it again, so the row stayed for good.
     *
     * <p>Answers 0 (not loading) only for a feed whose own page has lost every item: the latest page
     * of Home's response lost all of them, with a switch for it on, within the last
     * {@link #OLDER_WINDOW_NANOS}, and that page was parsed after this feed first asked. A feed's
     * first ask is when its fetch starts, so Home's own emptied page, which usually comes just before
     * Older Posts opens, never drops the row of a fetch still running. A feed that can't be told
     * apart (no policy handed over) keeps Instagram's answer. The builder still asks whether the feed
     * is empty, so a feed with posts keeps what it draws. It asks for no page, so it can't loop. Never throws.
     */
    public static int olderPageLoading(int loading) {
        Object policy = OLDER_POLICY.get();
        OLDER_POLICY.remove();
        if (loading == 0 || policy == null) return loading;
        try {
            long seqAtOpen = seqAtOpen(policy);
            if (!pageLostEverything || pageSeq <= seqAtOpen) return loading;
            if (!Utils.settingsReady() || !switchesTookOut()) return loading;
            long age = clock.getAsLong() - pageLostEverythingAt;
            if (age < 0 || age > OLDER_WINDOW_NANOS) return loading;
            if (!olderEndCounted) {
                olderEndCounted = true;
                HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, OLDER_ENDED);
            }
            Logger.printDebug(() -> "Feed suggestions: a page lost every post and its feed is empty, so the loading row is dropped");
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "older posts", failure);
            return loading;
        }
    }

    /** The page count when [policy]'s feed first asked, noted now if it hasn't before. */
    private static long seqAtOpen(Object policy) {
        synchronized (OLDER_FEEDS) {
            for (int i = OLDER_FEEDS.size() - 1; i >= 0; i--) {
                Object known = OLDER_FEEDS.get(i).policy.get();
                if (known == null) OLDER_FEEDS.remove(i);
                else if (known == policy) return OLDER_FEEDS.get(i).seqAtOpen;
            }
            if (OLDER_FEEDS.size() >= OLDER_FEEDS_MAX) OLDER_FEEDS.remove(0);
            long now = pageSeq;
            OLDER_FEEDS.add(new OlderFeed(policy, now));
            return now;
        }
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
     * while Hide posts you've liked is on, a post by an account on {@link HiddenAccounts}, or a post
     * whose caption holds one of Hide reels by caption words' words while its Also on Home is on, and
     * [item] itself otherwise, or when anything goes wrong. An item with no post, a row of suggested
     * accounts for one, stays. Inside a page of Home's feed response it also counts the item toward
     * that page, and whether it was lost to {@link #filter} or to its own removals, for
     * {@link #homePageParsed}. Never throws.
     */
    public static Object homeItem(Object item) {
        return homeItem(item, FeedSuggestions::itemType, FeedSuggestions::itemLiked, FeedSuggestions::author,
                FeedSuggestions::captionOf);
    }

    static Object homeItem(Object item, ToIntFunction<Object> typeOf) {
        return homeItem(item, typeOf, FeedSuggestions::itemLiked, FeedSuggestions::author, FeedSuggestions::captionOf);
    }

    static Object homeItem(Object item, ToIntFunction<Object> typeOf, ToIntFunction<Object> likedOf) {
        return homeItem(item, typeOf, likedOf, FeedSuggestions::author, FeedSuggestions::captionOf);
    }

    static Object homeItem(Object item, ToIntFunction<Object> typeOf, ToIntFunction<Object> likedOf,
            Function<Object, String> authorOf) {
        return homeItem(item, typeOf, likedOf, authorOf, FeedSuggestions::captionOf);
    }

    static Object homeItem(Object item, ToIntFunction<Object> typeOf, ToIntFunction<Object> likedOf,
            Function<Object, String> authorOf, Function<Object, String> captionOf) {
        boolean lost = Boolean.TRUE.equals(JUST_TOOK_OUT.get());
        JUST_TOOK_OUT.remove();
        int[] page = PAGE.get();
        if (page != null && (item != null || lost)) {
            page[0]++;
            if (lost) page[1]++;
        }
        if (item == null) return null;
        Object kept = byType(item, typeOf) == null ? null
                : byLiked(item, likedOf) == null ? null
                : byAuthor(item, authorOf) == null ? null : byCaption(item, captionOf);
        if (kept == null && page != null) page[1]++;
        if (kept != null && page != null) {
            try {
                if ("MEDIA".equals(FeedItemKinds.kindOf(kept, "MEDIA", FamilyNames.FEED_SUGGESTIONS))
                        && typeOf.applyAsInt(kept) > 0) page[2] = 1;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "retained home media", failure);
            }
        }
        return kept;
    }

    /**
     * [item], or null while Also on Home is on and its post's caption holds one of Hide reels by
     * caption words' words (#192). The caption isn't read while there are no words. A post with no
     * caption, or one that can't be read, stays.
     */
    private static Object byCaption(Object item, Function<Object, String> captionOf) {
        try {
            List<String> words = CaptionWordReels.homeWordsWhenOn();
            if (words.isEmpty()) return item;
            if (!CaptionWordReels.holdsAny(captionOf.apply(item), words)) return item;
            FeedFilterCounters.removed(TYPES_ROUTE, 1, CAPTION_WORD);
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, CAPTION_WORD_REMOVED);
            typesTookOut = true;
            Logger.printDebug(() -> "Feed suggestions: took a post with a blocked caption word out of Home");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, CAPTION_WORD, failure);
            return item;
        }
    }

    /**
     * The caption text of the post {@link #homePost} finds in a feed item, or null when it carries
     * none or the post has no caption. Reads the caption through the caption bridges Hide reels by
     * caption words writes.
     */
    static String captionOf(Object item) {
        Object media = homePost(item);
        if (media == null) return null;
        Object caption = InstagramMedia.caption(media);
        return caption == null ? null : InstagramMedia.captionText(caption);
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
     * The post a feed item carries: its own post field (a post from an account you follow, or an
     * ad), else the post of the suggested post unit it holds ("explore_story", its "media_or_ad" and
     * then its "media"), which is where Instagram 451 puts every suggested post on Home. Null when it
     * carries none, or when anything goes wrong.
     */
    static Object homePost(Object item) {
        return homePost(item, FeedSuggestions::itemPost, FeedSuggestions::exploreStory,
                FeedSuggestions::storyPost, FeedSuggestions::storyMedia);
    }

    static Object homePost(Object item, Function<Object, Object> postOf, Function<Object, Object> storyOf,
            Function<Object, Object> storyPostOf, Function<Object, Object> storyMediaOf) {
        if (item == null) return null;
        try {
            Object post = postOf.apply(item);
            if (post != null) return post;
            Object story = storyOf.apply(item);
            if (story == null) return null;
            post = storyPostOf.apply(story);
            return post != null ? post : storyMediaOf.apply(story);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "post read", failure);
            return null;
        }
    }

    /** {@link #mediaType} of the post {@link #homePost} finds in [item], 0 without one. */
    static int itemType(Object item) {
        Object post = homePost(item);
        return post == null ? 0 : mediaType(post);
    }

    /** {@link #liked} of the post {@link #homePost} finds in [item], 0 without one. */
    static int itemLiked(Object item) {
        Object post = homePost(item);
        return post == null ? 0 : liked(post);
    }

    /**
     * The username of whoever posted the post {@link #homePost} finds in [item], or null when there's
     * none or the post doesn't say.
     */
    static String author(Object item) {
        Object post = homePost(item);
        return post == null ? null : HiddenAccounts.authorOfPost(post);
    }

    /**
     * Answers an Object: the post in a feed item's own post field, the one its factory from a post
     * writes, or null. The patch writes the body. Null as built.
     */
    public static Object itemPost(Object item) {
        return null;
    }

    /**
     * Answers an Object: the suggested post unit ("explore_story") a feed item holds, or null. The
     * patch writes the body. Null as built.
     */
    public static Object exploreStory(Object item) {
        return null;
    }

    /**
     * Answers an Object: a suggested post unit's "media_or_ad" post, or null. The patch writes the
     * body. Null as built.
     */
    public static Object storyPost(Object story) {
        return null;
    }

    /**
     * Answers an Object: a suggested post unit's "media" post, or null. The patch writes the body.
     * Null as built.
     */
    public static Object storyMedia(Object story) {
        return null;
    }

    /**
     * The media_type of a post: 1 for one photo, 2 for one video, 8 for a carousel, and 0 when it
     * doesn't say. The patch writes the body, which reads the post's media_type. 0 as built.
     */
    public static int mediaType(Object post) {
        return 0;
    }

    /**
     * Whether you've liked a post: 1 when its has_liked says so, and 0 when it says not or doesn't
     * say. The patch writes the body, which reads the post's has_liked. 0 as built.
     */
    public static int liked(Object post) {
        return 0;
    }

    /**
     * Injected in the binder of the header Instagram draws after Home's end of feed card, right before
     * it sets the header's title to its section's name ("Suggested for you"), with that name and the
     * section. Answers "" while Hide suggested posts is on and the section shows nothing, and
     * [title] otherwise, or when anything goes wrong. It shows nothing when it holds only end of feed
     * cards and units a switch hides (suggestions, Threads, Hide Reels in the feed), or when it's
     * empty after a switch took something out of Home in this run. A section that's empty for
     * another reason, one still loading or one Instagram fills after the bind, keeps its title.
     * Every call is counted with why the title stayed or went, and the kinds the section held go to
     * the {@link #SECTION_ROUTE} route.
     *
     * <p>Instagram reads the header and its section's items out of the end of feed card's own data, and
     * the feed item helper {@link #filter} sits on reads those items too, so with every suggestion
     * taken out the section came in empty and the title stood over nothing (#104). The header's link
     * on the right ("Older posts") is set before the title, from another section, and stays: it's how
     * you reach the posts you've already seen. Never throws.
     */
    public static String sectionTitle(String title, Object section) {
        if (title == null || section == null) return title;
        try {
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, SECTION_CHECKED);
            if (!Utils.settingsReady() || !Settings.HIDE_SUGGESTED_POSTS.get()) return title;
            return titleOver(title, section);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "suggested section title", failure);
            return title;
        }
    }

    /**
     * Injected in the binder of Home's end of feed card where it draws the card's group set link
     * beside the title, with the title and the card, and the binder sets the card's title and
     * subtitle again with the answer. On 451 the caught up card draws the Suggested for you header
     * itself: "You've seen the latest posts from accounts you follow" on top, then the title with the
     * Older posts link beside it, so {@link #sectionTitle}'s header binder never runs for it (#104,
     * seen on a phone 2026-10-17). The title is the card's own text, not the section's name.
     * Answers "" while Hide suggested posts is on and the section the card's group set has open
     * shows nothing, as {@link #sectionTitle} decides, and [title] otherwise: for a card without a
     * group set or with the switch off, and when anything goes wrong. 451's caught up card drops a
     * title it's handed empty (seen on a phone), and other end of feed holders may leave a blank row
     * instead. Its subtitle and the link stay. Never throws.
     */
    public static String caughtUpTitle(String title, Object card) {
        if (title == null || title.isEmpty() || card == null) return title;
        try {
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, CAUGHT_UP_CHECKED);
            if (!Utils.settingsReady() || !Settings.HIDE_SUGGESTED_POSTS.get()) return title;
            Object section = caughtUpSections.apply(card);
            if (section == null) {
                HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, CAUGHT_UP_KEPT_NO_SECTION);
                return title;
            }
            return titleOver(title, section);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "caught up card title", failure);
            return title;
        }
    }

    /** [title], or "" when [section] shows nothing under it, with the switch on. Counted as {@link #sectionTitle} says. */
    private static String titleOver(String title, Object section) throws IllegalAccessException {
        List<?> items = sections.apply(section);
        if (items == null) {
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, SECTION_KEPT_UNREAD);
            return title;
        }
        String kinds = kindsIn(items);
        if (items.isEmpty()) {
            if (!tookOut && !FeedReels.tookOut) {
                HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, SECTION_KEPT_NOTHING_TAKEN);
                Logger.printDebug(() -> "Feed suggestions: the suggested section is empty but nothing was taken out of Home, so it keeps its title");
                return title;
            }
        } else if (showsAny(items)) {
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, SECTION_KEPT_SHOWS);
            Logger.printDebug(() -> "Feed suggestions: the suggested section keeps its title, it holds " + kinds);
            return title;
        }
        HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, SECTION_TITLE_DROPPED);
        Logger.printDebug(() -> "Feed suggestions: the suggested section shows nothing (" + kinds + "), so its header keeps only its link");
        return "";
    }

    /**
     * The section an end of feed card shows under its title: the one its group set has open, found by
     * the group set's own id for it, or null when the card has no group set or no section by that id.
     * The patch writes the body. Null as built, so the card keeps its title.
     */
    public static Object caughtUpSection(Object card) {
        return null;
    }

    /** Whether any of [items] shows under the header: anything but an end of feed card or a unit a switch hides. */
    private static boolean showsAny(List<?> items) throws IllegalAccessException {
        for (Object item : items) {
            if (item == null) continue;
            String kind = FeedItemKinds.kindOf(item, END_CARD_KIND, FamilyNames.FEED_SUGGESTIONS);
            if (kind == null || !hiddenInSection(kind)) return true;
        }
        return false;
    }

    private static boolean hiddenInSection(String kind) {
        if (END_CARD.contains(kind) || FeedReels.hides(kind)) return true;
        return KINDS.contains(kind) && switchFor(kind).get();
    }

    /** The kinds [items] hold, counted on the Suggested section route and listed for the debug log. */
    private static String kindsIn(List<?> items) {
        if (items.isEmpty()) return "no items";
        StringBuilder out = new StringBuilder();
        for (Object item : items) {
            String kind = null;
            try {
                kind = item == null ? null : FeedItemKinds.kindOf(item, END_CARD_KIND, FamilyNames.FEED_SUGGESTIONS);
            } catch (IllegalAccessException ignored) {
                // The kind stays unknown, which counts as showing.
            }
            String seen = kind == null ? "unknown" : kind;
            FeedFilterCounters.sawKind(SECTION_ROUTE, seen);
            if (out.length() > 0) out.append(", ");
            out.append(seen);
        }
        return out.toString();
    }

    /**
     * The items a section of the group set holds, as read (the items {@link #filter} took out aren't
     * among them), or null when it can't be told. The patch writes the body, which reads the
     * section's one list field. Null as built, so the header keeps its title.
     */
    public static List<?> sectionItems(Object section) {
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
