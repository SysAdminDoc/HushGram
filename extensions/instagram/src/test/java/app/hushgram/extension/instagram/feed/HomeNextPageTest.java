/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Home asks for For you's next page right away after a page Hide suggested posts left short (#52),
 * a few times in a row at most, and never while a page loads, after the end, for an empty Home or
 * for Following and Favorites.
 */
@RunWith(RobolectricTestRunner.class)
public class HomeNextPageTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Home's adapter, its load more policy and the feed it reads its flag from, as the stand-ins see them. */
    private final Object adapter = new Object();
    private final Object policy = new Object();
    private final Object feed = new Object();

    /** What was posted to the main thread and not run yet. */
    private final List<Runnable> posted = new ArrayList<>();

    /** The parameters of each request for the next page, in order. */
    private final List<Object> asks = new ArrayList<>();

    private final long[] now = {1_000_000_000L};
    private String source;
    private int loading;
    private int more;
    private int empty;
    private HookStatus.Snapshot saved;
    private FeedFilterCounters.Snapshot counters;

    @Before
    public void standIn() {
        saved = HookStatus.snapshotAndClear();
        counters = FeedFilterCounters.snapshotAndClear();
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_POSTS.save(true);
        clear();
        source = null;
        loading = 0;
        more = 1;
        empty = 0;
        HomeNextPage.clock = () -> now[0];
        HomeNextPage.poster = posted::add;
        HomeNextPage.policies = adapter -> adapter == this.adapter ? policy : null;
        HomeNextPage.sources = feed -> source;
        HomeNextPage.loading = policy -> loading;
        HomeNextPage.more = policy -> more;
        HomeNextPage.asker = (policy, params) -> {
            assertSame("Home's own policy", this.policy, policy);
            asks.add(params);
        };
        FeedSuggestions.emptiness = feed -> empty;
    }

    @After
    public void restore() {
        clear();
        HomeNextPage.clock = System::nanoTime;
        HomeNextPage.poster = Utils::runOnMainThread;
        HomeNextPage.policies = HomeNextPage::policyOf;
        HomeNextPage.sources = HomeNextPage::sourceOf;
        HomeNextPage.loading = HomeNextPage::loadingNow;
        HomeNextPage.more = HomeNextPage::moreLeft;
        HomeNextPage.asker = HomeNextPage::askNextPage;
        // Closes a page, a flag read or a build a failed test left open on this thread.
        FeedSuggestions.homePageParsed();
        FeedSuggestions.feedEnded(0);
        HomeNextPage.feedRead(null);
        clear();
        FeedSuggestions.emptiness = FeedSuggestions::feedEmpty;
        FeedSuggestions.homeReadsForTests = null;
        FeedSuggestions.homePageLost = false;
        FeedSuggestions.homePageEndCounted = false;
        FeedSuggestions.verdictFeed = null;
        FeedSuggestions.tookOut = false;
        FeedSuggestions.pageLostEverything = false;
        FeedSuggestions.pageLostEverythingAt = 0;
        FeedSuggestions.olderEndCounted = false;
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
        FeedFilterCounters.restore(counters);
        HookStatus.restore(saved);
    }

    private static void clear() {
        HomeNextPage.pages = 0;
        HomeNextPage.shortPage = 0;
        HomeNextPage.shortPageAt = 0;
        HomeNextPage.decided = 0;
        HomeNextPage.chain = 0;
        HomeNextPage.waitingForOurs = false;
        HomeNextPage.capCounted = false;
        HomeNextPage.posted = false;
        HomeNextPage.latest = null;
    }

    /** One build of Home's list: the builder starts, reads its flag off the feed, and the posted request runs. */
    private void build() {
        HomeNextPage.buildStarts(adapter);
        HomeNextPage.feedRead(feed);
        runPosted();
    }

    private void runPosted() {
        List<Runnable> due = new ArrayList<>(posted);
        posted.clear();
        for (Runnable request : due) request.run();
    }

    /** A page of Home's response like the reporter's: one followed post kept, four suggestions taken out. */
    private static void shortPage() {
        HomeNextPage.pageParsed(5, 4);
    }

    /** What the report counted for [name] on Hide suggested posts' line. */
    private static int counted(String name) {
        Matcher count = Pattern.compile(Pattern.quote(name) + " (\\d+)").matcher(String.join("\n", HookStatus.report()));
        return count.find() ? Integer.parseInt(count.group(1)) : 0;
    }

    @Test
    public void aShortPageGetsTheNextPageAskedForOnceTheBuildIsDone() {
        shortPage();
        HomeNextPage.buildStarts(adapter);
        assertEquals("posted to run after the build", 1, posted.size());
        assertTrue("not asked inside the build", asks.isEmpty());
        HomeNextPage.feedRead(feed);
        runPosted();

        assertEquals(1, asks.size());
        assertTrue("extra parameters are a map", asks.get(0) instanceof Map);
        assertTrue("and add nothing", ((Map<?, ?>) asks.get(0)).isEmpty());
        assertEquals(1, counted(HomeNextPage.ASKED));

        build();
        assertEquals("one request a page", 1, asks.size());
        assertTrue("nothing waits, so nothing more is posted", posted.isEmpty());
    }

    @Test
    public void severalBuildsBeforeTheRequestRunsPostItOnceForTheLatest() {
        shortPage();
        Object other = new Object();
        HomeNextPage.buildStarts(other);
        HomeNextPage.feedRead(feed);
        HomeNextPage.buildStarts(adapter);
        HomeNextPage.feedRead(feed);
        assertEquals("posted once", 1, posted.size());
        runPosted();
        assertEquals("asked of the latest build's policy", 1, asks.size());
    }

    @Test
    public void itKeepsAskingWhilePagesComeBackShortAndStopsAtTheCap() {
        for (int page = 1; page <= HomeNextPage.CHAIN_CAP; page++) {
            shortPage();
            build();
            assertEquals("page " + page, page, asks.size());
        }
        shortPage();
        build();
        assertEquals("no more past the cap", HomeNextPage.CHAIN_CAP, asks.size());
        assertEquals(1, counted(HomeNextPage.CAPPED));
        assertEquals(HomeNextPage.CHAIN_CAP, counted(HomeNextPage.ASKED));
    }

    @Test
    public void aPageThatKeepsEnoughStartsTheCountOver() {
        for (int page = 1; page <= HomeNextPage.CHAIN_CAP; page++) {
            shortPage();
            build();
        }
        HomeNextPage.pageParsed(9, 4);
        build();
        assertEquals("a full page asks nothing", HomeNextPage.CHAIN_CAP, asks.size());
        assertEquals(0, HomeNextPage.chain);

        shortPage();
        build();
        assertEquals("a short page asks again", HomeNextPage.CHAIN_CAP + 1, asks.size());
        assertFalse("and its cap isn't counted yet", HomeNextPage.capCounted);
    }

    /** A short page Instagram asked for itself, the scroll's, starts a chain of its own after one hit the cap. */
    @Test
    public void aPageInstagramAskedForStartsANewChain() {
        for (int page = 0; page <= HomeNextPage.CHAIN_CAP; page++) {
            shortPage();
            build();
        }
        assertEquals(HomeNextPage.CHAIN_CAP, asks.size());

        shortPage();
        build();
        assertEquals(HomeNextPage.CHAIN_CAP + 1, asks.size());
        assertEquals(1, HomeNextPage.chain);
    }

    @Test
    public void itNeverAsksWhileAPageLoads() {
        shortPage();
        loading = 1;
        build();
        assertTrue(asks.isEmpty());

        // Still waiting: the build after the page is in asks.
        loading = 0;
        build();
        assertEquals(1, asks.size());
    }

    @Test
    public void itNeverAsksAfterTheEndOrWhenTheQuestionsCantBeAsked() {
        for (int[] answers : new int[][] {{0, 0}, {0, -1}, {-1, 1}}) {
            clear();
            loading = answers[0];
            more = answers[1];
            shortPage();
            build();
            assertTrue("loading " + answers[0] + ", more " + answers[1], asks.isEmpty());
            loading = 0;
            more = 1;
            build();
            assertTrue("decided: a later build doesn't ask either", asks.isEmpty());
        }
    }

    @Test
    public void anEmptyHomeIsLeftToItsEndCard() {
        for (int answer : new int[] {1, -1}) {
            clear();
            empty = answer;
            shortPage();
            build();
            assertTrue("emptiness " + answer, asks.isEmpty());
        }
    }

    @Test
    public void aPageThatKeptNothingOnAFeedWithPostsAsks() {
        HomeNextPage.pageParsed(4, 4);
        build();
        assertEquals(1, asks.size());
    }

    @Test
    public void followingAndFavoritesPageAsTheyDid() {
        for (String other : new String[] {"homecoming_following", "homecoming_favorites"}) {
            clear();
            source = other;
            shortPage();
            build();
            assertTrue(other, asks.isEmpty());
        }
        // A short page For you's list builds after another feed's build still asks.
        source = "homecoming_following";
        build();
        source = HomeNextPage.FOR_YOU;
        build();
        assertEquals(1, asks.size());
        assertTrue(HomeNextPage.forYou(null));
        assertTrue(HomeNextPage.forYou(""));
        assertFalse(HomeNextPage.forYou("homecoming_following"));
    }

    @Test
    public void anOffSwitchPauseOrUnreadySettingsLeaveInstagramsPaging() {
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        shortPage();
        build();
        assertTrue("off", asks.isEmpty());

        Settings.HIDE_SUGGESTED_POSTS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        clear();
        shortPage();
        build();
        assertTrue("paused", asks.isEmpty());
        PauseForTests.resume();

        clear();
        shortPage();
        SettingsContextRule.withoutContext(this::build);
        assertTrue("settings not ready", asks.isEmpty());
    }

    @Test
    public void aPageTheSwitchesTookNothingFromIsInstagramsOwn() {
        HomeNextPage.pageParsed(2, 0);
        HomeNextPage.buildStarts(adapter);
        assertTrue("nothing posted", posted.isEmpty());
        HomeNextPage.pageParsed(8, 3);
        HomeNextPage.buildStarts(adapter);
        assertTrue("a page keeping five isn't short", posted.isEmpty());
    }

    @Test
    public void anOldShortPageIsLeftAlone() {
        shortPage();
        now[0] += HomeNextPage.WINDOW_NANOS + 1;
        build();
        assertTrue(asks.isEmpty());
    }

    @Test
    public void aBuildThatNeverReadItsFeedLeavesThePageForTheNextOne() {
        shortPage();
        HomeNextPage.buildStarts(adapter);
        runPosted();
        assertTrue(asks.isEmpty());
        build();
        assertEquals(1, asks.size());
    }

    @Test
    public void aMissingPolicyOrAThrowingStubFailsOpen() {
        HomeNextPage.policies = adapter -> null;
        shortPage();
        build();
        assertTrue("no policy", asks.isEmpty());

        HomeNextPage.policies = adapter -> policy;
        HomeNextPage.asker = (policy, params) -> {
            throw new IllegalStateException("moved");
        };
        clear();
        shortPage();
        build();
        assertFalse("nothing waits on a request that threw", HomeNextPage.waitingForOurs);
        assertEquals(0, counted(HomeNextPage.ASKED));
        build();
        assertTrue("decided, so it isn't retried", posted.isEmpty());
    }

    @Test
    public void theStubsLeaveInstagramAloneAsBuilt() {
        assertNull(HomeNextPage.policyOf(adapter));
        assertNull(HomeNextPage.sourceOf(feed));
        assertEquals(-1, HomeNextPage.loadingNow(policy));
        assertEquals(-1, HomeNextPage.moreLeft(policy));
        HomeNextPage.askNextPage(policy, null);
    }

    /**
     * The reporter's page through FeedSuggestions' own hooks: Home's parser reads one followed post
     * and four suggested ones, the switch takes the four out, and the build that follows asks.
     */
    @Test
    public void homesParserAndFlagReadReachIt() {
        FeedSuggestions.homeReadsForTests = true;
        homePage(FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.EXPLORE_STORY, FeedSuggestionsTest.Kind.EXPLORE_STORY,
                FeedSuggestionsTest.Kind.EXPLORE_STORY, FeedSuggestionsTest.Kind.EXPLORE_STORY);
        assertEquals("the page is waiting", 1, HomeNextPage.shortPage);

        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("Home holds a post, so it doesn't end", 0, FeedSuggestions.feedEnded(0));
        runPosted();
        assertEquals(1, asks.size());
        assertEquals(1, counted(HomeNextPage.ASKED));

        // The page that answers keeps five, so the build after it asks nothing.
        homePage(FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.MEDIA,
                FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.EXPLORE_STORY);
        assertEquals(0, HomeNextPage.shortPage);
        HomeNextPage.buildStarts(adapter);
        assertTrue(posted.isEmpty());
    }

    /** An empty page that answers a request made here ends that chain: the next short page, the scroll's, starts its own. */
    @Test
    public void anEmptyAnswerEndsTheChainItAnswered() {
        FeedSuggestions.homeReadsForTests = true;
        shortPage();
        build();
        assertEquals(1, asks.size());
        assertTrue(HomeNextPage.waitingForOurs);

        homePage();
        assertFalse("the empty page answered it", HomeNextPage.waitingForOurs);

        shortPage();
        build();
        assertEquals(2, asks.size());
        assertEquals("a chain of its own", 1, HomeNextPage.chain);
    }

    /** A build whose adapter is gone by the time the request runs asks nothing and decides the page. */
    @Test
    public void aBuildWhoseAdapterIsGoneAsksNothing() {
        shortPage();
        HomeNextPage.Build build = new HomeNextPage.Build(new Object());
        build.feed = feed;
        build.adapter.clear();
        HomeNextPage.askIfShort(build);
        assertTrue(asks.isEmpty());
        assertEquals(HomeNextPage.shortPage, HomeNextPage.decided);
    }

    /** A page of Home's response holding items of [kinds], read through the filter and homeItem as Home's parser does. */
    private static void homePage(FeedSuggestionsTest.Kind... kinds) {
        FeedSuggestions.homePageStarts();
        for (FeedSuggestionsTest.Kind kind : kinds) {
            FeedSuggestions.homeItem(FeedSuggestions.filter(new FeedSuggestionsTest.Item(kind)), ignored -> 0);
        }
        FeedSuggestions.homePageParsed();
    }
}
