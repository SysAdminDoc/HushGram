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

import android.app.Activity;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Home asks for For you's next page right away after a page Hide suggested posts or another Home
 * switch left short or empty (#52, #28), a few times in a row at most and a few more while Home's
 * list sits at its end, with a later check for a page no build follows, and never while a page loads,
 * after the end, for suggested posts while they're hidden (#105) or for Following and Favorites.
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

    /** The settle checks posted for later and not run yet. */
    private final List<Runnable> settles = new ArrayList<>();

    /** The checks on a request posted for later and not run yet. */
    private final List<Runnable> watches = new ArrayList<>();

    private final long[] now = {1_000_000_000L};
    private String source;
    private int loading;
    private int more;
    private int empty;
    private int end;
    private HookStatus.Snapshot saved;
    private FeedFilterCounters.Snapshot counters;

    @Before
    public void standIn() {
        saved = HookStatus.snapshotAndClear();
        counters = FeedFilterCounters.snapshotAndClear();
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_POSTS.save(true);
        // The stand-in pages lost their suggestions to Hide suggested posts.
        FeedSuggestions.tookOut = true;
        clear();
        source = null;
        loading = 0;
        more = 1;
        empty = 0;
        end = 0;
        HomeNextPage.ends = adapter -> adapter == this.adapter ? end : -1;
        HomeNextPage.later = settles::add;
        HomeNextPage.watcher = watches::add;
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
        HomeNextPage.ends = HomeNextPage::atEnd;
        HomeNextPage.later = HomeNextPage::settleLater;
        HomeNextPage.watcher = HomeNextPage::watchLater;
        FeedSuggestions.emptiness = FeedSuggestions::feedEmpty;
        FeedSuggestions.homeReadsForTests = null;
        FeedSuggestions.homePageLost = false;
        FeedSuggestions.homePageEndCounted = false;
        FeedSuggestions.verdictFeed = null;
        FeedSuggestions.tookOut = false;
        FeedSuggestions.typesTookOut = false;
        FeedSuggestions.adsTookOut = false;
        FeedSuggestions.pageLostEverything = false;
        FeedSuggestions.pageLostEverythingAt = 0;
        FeedSuggestions.olderEndCounted = false;
        PauseForTests.resume();
        for (BooleanSetting setting : suggestionSwitches()) setting.resetToDefault();
        Settings.HIDE_FEED_VIDEOS.resetToDefault();
        FeedFilterCounters.restore(counters);
        HookStatus.restore(saved);
    }

    /** Every switch FeedSuggestions.filter takes items out for. Settings load only once the test's context is in place. */
    private static BooleanSetting[] suggestionSwitches() {
        return new BooleanSetting[] {Settings.HIDE_SUGGESTED_POSTS, Settings.HIDE_SUGGESTED_ACCOUNTS,
                Settings.HIDE_THREADS_POSTS, Settings.HIDE_FEED_SURVEYS, Settings.HIDE_FEED_SHOPPING};
    }

    private static void clear() {
        HomeNextPage.pages = 0;
        HomeNextPage.shortPage = 0;
        HomeNextPage.shortPageAt = 0;
        HomeNextPage.decided = 0;
        HomeNextPage.chain = 0;
        HomeNextPage.requests = 0;
        HomeNextPage.answered = 0;
        HomeNextPage.requestAt = 0;
        HomeNextPage.waitingForOurs = false;
        HomeNextPage.capCounted = false;
        HomeNextPage.posted = false;
        HomeNextPage.latest = null;
        HomeNextPage.home = null;
        HomeNextPage.holdSince = 0;
        HomeNextPage.holdFeed = null;
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

    /** Runs the settle checks due now, as the main thread does {@link HomeNextPage#SETTLE_MS} after each was posted. */
    private void runSettles() {
        List<Runnable> due = new ArrayList<>(settles);
        settles.clear();
        for (Runnable check : due) check.run();
    }

    /** Runs the checks on requests due now, as the main thread does {@link HomeNextPage#ANSWER_MS} after each was posted. */
    private void runWatches() {
        List<Runnable> due = new ArrayList<>(watches);
        watches.clear();
        for (Runnable check : due) check.run();
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

    /** An empty Home, or one that can't say, still gets For you's next page asked for, within the cap (#28). */
    @Test
    public void anEmptyHomeAsksWithinTheCap() {
        for (int answer : new int[] {1, -1}) {
            clear();
            asks.clear();
            empty = answer;
            for (int page = 1; page <= HomeNextPage.CHAIN_CAP + 1; page++) {
                shortPage();
                build();
                assertEquals("emptiness " + answer + ", page " + page, Math.min(page, HomeNextPage.CHAIN_CAP), asks.size());
            }
        }
    }

    /**
     * #28: Hide suggested posts is off and Hide videos took every post of For you's page. Home is
     * empty, but For you has more, and that page is asked for. Before, nothing asked and the card
     * stayed. While it comes Home keeps the loading row (#126), and a next page that keeps posts
     * fills Home and keeps Instagram's answer.
     */
    @Test
    public void aHomeHideVideosEmptiedAsksForItsNextPage() {
        FeedSuggestions.homeReadsForTests = true;
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        FeedSuggestions.tookOut = false;
        Settings.HIDE_FEED_VIDEOS.save(true);
        empty = 1;
        FeedSuggestions.homePageStarts();
        for (int post = 0; post < 6; post++) {
            assertNull(FeedSuggestions.homeItem(new Object(), item -> FeedSuggestions.VIDEO));
        }
        FeedSuggestions.homePageParsed();

        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("the loading row stays while the next page comes", 0, FeedSuggestions.feedEnded(0));
        runPosted();
        assertEquals(1, asks.size());

        FeedSuggestions.homePageStarts();
        for (int post = 0; post < 6; post++) {
            assertTrue(FeedSuggestions.homeItem(new Object(), item -> FeedSuggestions.PHOTO) != null);
        }
        FeedSuggestions.homePageParsed();
        empty = 0;
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("posts replace the card", 0, FeedSuggestions.feedEnded(0));
        runPosted();
        assertEquals("a full page asks nothing", 1, asks.size());
    }

    /**
     * #105: an account that follows nobody gets suggested posts only. With Hide suggested posts on,
     * a page of them from feed_recs empties Home, Home keeps Instagram's empty feed card and nothing
     * asks for more of them.
     */
    @Test
    public void aHomeThatFollowsNobodyStaysOnItsEndCard() {
        FeedSuggestions.homeReadsForTests = true;
        empty = 1;
        source = HomeNextPage.SUGGESTED;
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY, FeedSuggestionsTest.Kind.EXPLORE_STORY,
                FeedSuggestionsTest.Kind.EXPLORE_STORY, FeedSuggestionsTest.Kind.SUGGESTED_USERS);

        for (int read = 0; read < 3; read++) {
            HomeNextPage.buildStarts(adapter);
            FeedSuggestions.homeFeedRead(feed);
            assertEquals("the empty feed card", 1, FeedSuggestions.feedEnded(0));
            runPosted();
        }
        assertTrue(asks.isEmpty());
        assertEquals("no hold for a list it doesn't ask for", 0, counted(HomeNextPage.HELD));
    }

    /**
     * #126: after a while away or an account switch, For you's first page was one suggested post and
     * the followed posts came a page later, so the Welcome card showed for a few seconds before them.
     * Home keeps the loading row while that page comes, and the posts it brings fill Home.
     */
    @Test
    public void anEmptyHomeKeepsTheLoadingRowWhileItsNextPageComes() {
        FeedSuggestions.homeReadsForTests = true;
        empty = 1;
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("no Welcome card", 0, FeedSuggestions.feedEnded(0));
        runPosted();
        assertEquals("the next page is asked for", 1, asks.size());
        assertEquals(1, counted(HomeNextPage.HELD));

        now[0] += HomeNextPage.HOLD_NANOS / 2;
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("a build before the answer still holds", 0, FeedSuggestions.feedEnded(0));
        runPosted();
        assertEquals(1, asks.size());

        homePage(FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.EXPLORE_STORY,
                FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.MEDIA);
        empty = 0;
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("the followed posts", 0, FeedSuggestions.feedEnded(0));
        assertEquals("a page that kept posts frees the hold", 0, HomeNextPage.holdSince);
        assertEquals("counted once", 1, counted(HomeNextPage.HELD));
        String report = String.join("\n", FeedFilterCounters.report());
        assertFalse(report, report.contains(FeedSuggestions.HOME_ENDED));
    }

    /**
     * A hold lasts HOLD_NANOS, and past it Home ends on the card. It stays spent while pages bring
     * nothing, so the card doesn't wait again, until a page keeps something or another feed is read.
     */
    @Test
    public void theHoldEndsAfterItsTimeAndStaysSpentUntilAPageKeepsSomething() {
        FeedSuggestions.homeReadsForTests = true;
        empty = 1;
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY, FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals(0, FeedSuggestions.feedEnded(0));
        runPosted();

        now[0] += HomeNextPage.HOLD_NANOS + 1;
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("past its time Home ends", 1, FeedSuggestions.feedEnded(0));
        runPosted();
        assertEquals(-1, HomeNextPage.holdSince);

        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("spent", 1, FeedSuggestions.feedEnded(0));
        runPosted();
        assertEquals(3, asks.size());

        // A page that keeps something lets the next empty page hold again.
        homePage(FeedSuggestionsTest.Kind.MEDIA, FeedSuggestionsTest.Kind.EXPLORE_STORY);
        assertEquals(0, HomeNextPage.holdSince);
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("held again", 0, FeedSuggestions.feedEnded(0));
        runPosted();

        // Spent once more, then another account's Home gets a hold of its own.
        now[0] += HomeNextPage.HOLD_NANOS + 1;
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals(1, FeedSuggestions.feedEnded(0));
        runPosted();
        Object other = new Object();
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(other);
        assertEquals("another feed", 0, FeedSuggestions.feedEnded(0));
        assertEquals(3, counted(HomeNextPage.HELD));
    }

    /**
     * No hold once no page is coming: no next page, a short page already decided or past the cap
     * with no request waiting, a list that isn't For you's, a feed other than the latest build's, or
     * a policy it can't read. A page loading or a request waiting for its answer holds even at the
     * cap, and a list at its end holds within {@link HomeNextPage#END_CAP}.
     */
    @Test
    public void anEmptyHomeEndsAtOnceWhenNoPageIsComing() {
        FeedSuggestions.homeReadsForTests = true;
        empty = 1;
        int capped = HomeNextPage.CHAIN_CAP;
        int[][] cases = {
                // more, loading, chain, waiting (a request of ours), at its end, decided, expected feedEnded
                {1, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 1},
                {-1, 0, 0, 0, 0, 0, 1},
                {1, -1, 0, 0, 0, 0, 1},
                {1, 0, 0, 0, 0, 1, 1},
                {1, 0, 0, 1, 0, 1, 0},
                {1, 0, capped, 0, 0, 0, 1},
                {1, 0, capped, 1, 0, 0, 0},
                {1, 1, capped, 0, 0, 0, 0},
                {1, 0, capped, 0, 1, 0, 0},
                {1, 0, HomeNextPage.END_CAP, 0, 1, 0, 1},
        };
        for (int[] c : cases) {
            String name = "more " + c[0] + ", loading " + c[1] + ", chain " + c[2] + ", waiting " + c[3]
                    + ", at its end " + c[4] + ", decided " + c[5];
            clear();
            homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
            more = c[0];
            loading = c[1];
            HomeNextPage.chain = c[2];
            HomeNextPage.waitingForOurs = c[3] == 1;
            end = c[4];
            if (c[5] == 1) HomeNextPage.decided = HomeNextPage.shortPage;
            HomeNextPage.buildStarts(adapter);
            FeedSuggestions.homeFeedRead(feed);
            assertEquals(name, c[6], FeedSuggestions.feedEnded(0));
            posted.clear();
        }
        more = 1;
        loading = 0;
        end = 0;

        clear();
        source = "homecoming_following";
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("Following's list", 1, FeedSuggestions.feedEnded(0));
        source = null;

        clear();
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        HomeNextPage.feedRead(new Object());
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("not the latest build's feed", 1, FeedSuggestions.feedEnded(0));

        clear();
        HomeNextPage.policies = adapter -> null;
        homePage(FeedSuggestionsTest.Kind.EXPLORE_STORY);
        HomeNextPage.buildStarts(adapter);
        FeedSuggestions.homeFeedRead(feed);
        assertEquals("no policy", 1, FeedSuggestions.feedEnded(0));
        assertFalse(HomeNextPage.holdsEnd(null));
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

    /**
     * #28: with Hide suggested posts off, Hide videos took four of a page's five posts and the
     * loading row under the one left never moved. The next page is asked for all the same, and once
     * Hide videos is off too, Home pages as Instagram has it.
     */
    @Test
    public void aPageHideVideosLeftShortAsksWithHideSuggestedPostsOff() {
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        Settings.HIDE_FEED_VIDEOS.save(true);
        FeedSuggestions.tookOut = false;
        FeedSuggestions.homePageStarts();
        for (int post = 0; post < 4; post++) {
            assertNull(FeedSuggestions.homeItem(new Object(), item -> FeedSuggestions.VIDEO));
        }
        Object photo = new Object();
        assertSame(photo, FeedSuggestions.homeItem(photo, item -> FeedSuggestions.PHOTO));
        FeedSuggestions.homePageParsed();
        assertEquals("the page is waiting", 1, HomeNextPage.shortPage);

        build();
        assertEquals(1, asks.size());

        Settings.HIDE_FEED_VIDEOS.save(false);
        shortPage();
        build();
        assertEquals("every switch that took something out is off", 1, asks.size());
    }

    /**
     * Suggested posts past the ones from accounts you follow page from a source of their own. With
     * Hide suggested posts on every post on them goes, so a short page of them asks for nothing
     * (#104's log). With it off they show, and a page Hide videos left short asks (#28).
     */
    @Test
    public void suggestedPostPagesAskOnlyWhileTheyShow() {
        source = HomeNextPage.SUGGESTED;
        shortPage();
        build();
        assertTrue("Hide suggested posts on", asks.isEmpty());

        Settings.HIDE_SUGGESTED_POSTS.save(false);
        Settings.HIDE_FEED_VIDEOS.save(true);
        FeedSuggestions.typesTookOut = true;
        build();
        assertEquals("Hide suggested posts off", 1, asks.size());
        assertTrue(HomeNextPage.asksFor(HomeNextPage.SUGGESTED));
        assertTrue(HomeNextPage.asksFor(HomeNextPage.FOR_YOU));
        assertFalse(HomeNextPage.asksFor("homecoming_following"));
    }

    /**
     * #28 as reported on 2026-10-17: Hide suggested posts off, Hide videos on, and For you's
     * suggested posts page from feed_recs. Hide videos alone takes a page of them down to two posts
     * under a loading row that never moved, or to none, leaving the Welcome card. Either way the
     * next page is asked for, and an emptied Home keeps the loading row while it comes (#126).
     */
    @Test
    public void hideVideosAloneAsksAfterASuggestedPostPageItShortenedOrEmptied() {
        FeedSuggestions.homeReadsForTests = true;
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        Settings.HIDE_FEED_VIDEOS.save(true);
        source = HomeNextPage.SUGGESTED;
        for (int kept : new int[] {2, 0}) {
            clear();
            asks.clear();
            FeedSuggestions.tookOut = false;
            FeedSuggestions.typesTookOut = false;
            empty = kept == 0 ? 1 : 0;
            FeedSuggestions.homePageStarts();
            for (int post = 0; post < 5; post++) {
                assertNull(FeedSuggestions.homeItem(new Object(), item -> FeedSuggestions.VIDEO));
            }
            for (int post = 0; post < kept; post++) {
                Object photo = new Object();
                assertSame(photo, FeedSuggestions.homeItem(photo, item -> FeedSuggestions.PHOTO));
            }
            FeedSuggestions.homePageParsed();
            assertTrue("Hide videos took them out", FeedSuggestions.typesTookOut);
            assertEquals("kept " + kept + ", the page is waiting", HomeNextPage.pages, HomeNextPage.shortPage);

            HomeNextPage.buildStarts(adapter);
            FeedSuggestions.homeFeedRead(feed);
            assertEquals("kept " + kept, 0, FeedSuggestions.feedEnded(0));
            runPosted();
            assertEquals("kept " + kept, 1, asks.size());
        }
    }

    @Test
    public void anOffSwitchPauseOrUnreadySettingsLeaveInstagramsPaging() {
        for (BooleanSetting setting : suggestionSwitches()) setting.save(false);
        shortPage();
        build();
        assertTrue("the switches that took the items out are off", asks.isEmpty());

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
        assertNull(HomeNextPage.listOf(adapter));
        assertEquals("a list that can't be read can't be at its end", -1, HomeNextPage.atEnd(adapter));
    }

    /** A list off screen isn't at its end, and anything but a view can't be told. */
    @Test
    public void onlyAListOnScreenCanBeAtItsEnd() {
        assertEquals(-1, HomeNextPage.endOf(null));
        assertEquals(-1, HomeNextPage.endOf("not a view"));
        assertEquals(0, HomeNextPage.endOf(new View(RuntimeEnvironment.getApplication())));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View shown = new View(controller.get());
            controller.get().setContentView(shown);
            assertTrue(shown.isShown());
            assertEquals("a list on screen with nothing below", 1, HomeNextPage.endOf(shown));
        }
    }

    /**
     * #28: Hide videos takes most of each suggested post page and Home's list sits at its end, so no
     * scroll would ever ask. Past {@link HomeNextPage#CHAIN_CAP} it keeps asking while the list
     * can't scroll further, up to {@link HomeNextPage#END_CAP} in a row.
     */
    @Test
    public void atTheEndOfHomesListItAsksPastTheCapUpToItsOwn() {
        end = 1;
        for (int page = 1; page <= HomeNextPage.END_CAP + 1; page++) {
            shortPage();
            build();
            assertEquals("page " + page, Math.min(page, HomeNextPage.END_CAP), asks.size());
        }
        assertEquals(1, counted(HomeNextPage.CAPPED));
        assertEquals(HomeNextPage.END_CAP, counted(HomeNextPage.ASKED));
    }

    /** Past the cap, a list that still scrolls, or one that can't say, waits for the scroll as before. */
    @Test
    public void pastTheCapAListThatScrollsOrCantSayWaitsForTheScroll() {
        for (int answer : new int[] {0, -1}) {
            clear();
            asks.clear();
            end = answer;
            for (int page = 1; page <= HomeNextPage.CHAIN_CAP + 1; page++) {
                shortPage();
                build();
            }
            assertEquals("end " + answer, HomeNextPage.CHAIN_CAP, asks.size());
        }

        // A list at its end until the asked-for pages fill it stops there.
        clear();
        asks.clear();
        end = 1;
        for (int page = 1; page <= HomeNextPage.CHAIN_CAP + 1; page++) {
            shortPage();
            build();
        }
        end = 0;
        shortPage();
        build();
        assertEquals(HomeNextPage.CHAIN_CAP + 1, asks.size());
    }

    /**
     * #28: a page the switches emptied changes nothing Home draws, so no build of Home's list may
     * follow it. The check after it asks with the latest build that read its feed, and stops once
     * the page is decided.
     */
    @Test
    public void aShortPageNoBuildFollowsIsDecidedByTheLaterCheck() {
        build();
        assertTrue("nothing short, nothing to check", settles.isEmpty());

        HomeNextPage.pageParsed(4, 4);
        assertEquals("checked on later", 1, settles.size());
        runSettles();
        assertEquals(1, asks.size());
        assertTrue("decided, so no more checks", settles.isEmpty());
    }

    /** The check waits while a page loads, like a build does, and lets the page go after the window. */
    @Test
    public void theLaterCheckWaitsWhileAPageLoadsAndStopsAfterTheWindow() {
        build();
        shortPage();
        loading = 1;
        runSettles();
        assertTrue(asks.isEmpty());
        assertEquals("checks again", 1, settles.size());
        loading = 0;
        runSettles();
        assertEquals(1, asks.size());
        assertTrue(settles.isEmpty());

        shortPage();
        loading = 1;
        runSettles();
        assertEquals(1, settles.size());
        now[0] += HomeNextPage.WINDOW_NANOS + 1;
        runSettles();
        assertTrue("past the window, no more checks", settles.isEmpty());
        assertEquals(1, asks.size());
    }

    /** With no build that read its feed yet the check waits, and a build that decides first leaves it nothing. */
    @Test
    public void theLaterCheckLeavesABuildsDecisionAlone() {
        shortPage();
        runSettles();
        assertTrue(asks.isEmpty());
        assertEquals("no build read its feed yet", 1, settles.size());
        build();
        assertEquals(1, asks.size());
        runSettles();
        assertTrue(settles.isEmpty());
        assertEquals("one request a page", 1, asks.size());

        // A request a build posted runs first: the check doesn't ask ahead of it.
        shortPage();
        HomeNextPage.buildStarts(adapter);
        HomeNextPage.feedRead(feed);
        runSettles();
        assertEquals(1, asks.size());
        runPosted();
        assertEquals(2, asks.size());
        runSettles();
        assertTrue(settles.isEmpty());
        assertEquals(2, asks.size());
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
        build.read(feed);
        build.adapter.clear();
        HomeNextPage.askIfShort(build);
        assertTrue(asks.isEmpty());
        assertEquals(HomeNextPage.shortPage, HomeNextPage.decided);
    }

    /** The later check drops a build whose list is gone (Home was rebuilt) and leaves the page to the new list's build. */
    @Test
    public void theLaterCheckDropsABuildWhoseListIsGone() {
        HomeNextPage.Build gone = new HomeNextPage.Build(new Object());
        gone.read(feed);
        gone.adapter.clear();
        HomeNextPage.home = gone;
        shortPage();
        runSettles();
        assertNull(HomeNextPage.home);
        assertTrue(asks.isEmpty());
        assertEquals("still waits", 1, settles.size());
        build();
        assertEquals("the new list's build asks", 1, asks.size());
    }

    /**
     * Following's and Favorites' lists keep adapters of Home's class, so their builds never become the
     * build the later check asks with, and with none of For you's the check stops after the window.
     */
    @Test
    public void onlyForYousBuildIsTheOneTheLaterCheckAsksWith() {
        Object following = new Object();
        HomeNextPage.sources = read -> read == following ? "homecoming_following" : source;
        build();
        HomeNextPage.Build forYou = HomeNextPage.home;
        assertTrue(forYou != null);
        HomeNextPage.buildStarts(adapter);
        HomeNextPage.feedRead(following);
        runPosted();
        assertSame("Following's build leaves For you's", forYou, HomeNextPage.home);

        clear();
        HomeNextPage.buildStarts(adapter);
        HomeNextPage.feedRead(following);
        runPosted();
        assertNull(HomeNextPage.home);
        shortPage();
        runSettles();
        assertTrue(asks.isEmpty());
        assertEquals("waits for For you's build", 1, settles.size());
        now[0] += HomeNextPage.WINDOW_NANOS + 1;
        runSettles();
        assertTrue("past the window, no more checks", settles.isEmpty());
        assertTrue(asks.isEmpty());

        assertTrue(HomeNextPage.homeFeed(null));
        assertTrue(HomeNextPage.homeFeed(HomeNextPage.SUGGESTED));
        assertFalse(HomeNextPage.homeFeed("homecoming_favorites"));
    }

    /**
     * #28 on the S22: the first request after a cold start read posts outside any page of Home's
     * response, Hide videos took them all, and no page answered it, so Home sat under one post. Once
     * Home's list sits at its end the request is made again, and a page that answers stops the watch.
     */
    @Test
    public void aRequestNoPageAnswersIsMadeAgainOnceHomesListSitsAtItsEnd() {
        shortPage();
        build();
        assertEquals(1, asks.size());
        assertEquals("the request is watched", 1, watches.size());

        runWatches();
        assertEquals("the list still scrolls, so the scroll asks", 1, asks.size());
        assertEquals("and the watch checks again", 1, watches.size());

        end = 1;
        runWatches();
        assertEquals("asked again at the end", 2, asks.size());
        assertEquals(2, HomeNextPage.chain);
        assertTrue(HomeNextPage.waitingForOurs);
        assertEquals(2, counted(HomeNextPage.ASKED));
        assertEquals("the new request is watched", 1, watches.size());

        HomeNextPage.pageParsed(9, 0);
        runWatches();
        assertEquals("a page answered it", 2, asks.size());
        assertTrue(watches.isEmpty());
    }

    /** An empty page doesn't answer a request: Home's list still sits at its end with nothing new in it. */
    @Test
    public void anEmptyPageLeavesTheRequestWatched() {
        FeedSuggestions.homeReadsForTests = true;
        end = 1;
        shortPage();
        build();
        homePage();
        assertFalse(HomeNextPage.waitingForOurs);
        runWatches();
        assertEquals(2, asks.size());
        assertTrue(HomeNextPage.waitingForOurs);
    }

    /** The watch stops at END_CAP, once Home has no next page or can't say, past its window, or with the switches off. */
    @Test
    public void theWatchStopsAtTheCapTheEndItsWindowOrAnOffSwitch() {
        end = 1;
        shortPage();
        build();
        for (int check = 0; check < HomeNextPage.END_CAP + 2; check++) runWatches();
        assertEquals("up to END_CAP in a row", HomeNextPage.END_CAP, asks.size());
        assertTrue(watches.isEmpty());

        for (int answer : new int[] {0, -1}) {
            clear();
            asks.clear();
            shortPage();
            build();
            more = answer;
            runWatches();
            assertEquals("next page " + answer, 1, asks.size());
            assertTrue(watches.isEmpty());
            more = 1;
        }

        // A page loading holds it, and it asks once loading ends with nothing answered.
        clear();
        asks.clear();
        shortPage();
        build();
        loading = 1;
        runWatches();
        assertEquals(1, asks.size());
        assertEquals(1, watches.size());
        loading = 0;
        runWatches();
        assertEquals(2, asks.size());

        clear();
        asks.clear();
        watches.clear();
        shortPage();
        build();
        now[0] += HomeNextPage.WATCH_NANOS + 1;
        runWatches();
        assertEquals("past the window", 1, asks.size());
        assertTrue(watches.isEmpty());

        clear();
        asks.clear();
        shortPage();
        build();
        FeedSuggestions.tookOut = false;
        runWatches();
        assertEquals("switches off", 1, asks.size());
        assertTrue(watches.isEmpty());

        // A request that isn't the latest is left to the latest one's watch.
        FeedSuggestions.tookOut = true;
        clear();
        asks.clear();
        shortPage();
        build();
        Runnable first = watches.remove(0);
        shortPage();
        build();
        first.run();
        assertEquals(2, asks.size());
        assertEquals(1, watches.size());
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
