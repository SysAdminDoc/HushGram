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
import org.robolectric.annotation.Config;

import java.util.List;

import app.hushgram.extension.instagram.reels.FeedReels;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.settings.BooleanSetting;

/** Which home feed items Hide suggested posts takes out, and which it leaves. */
@RunWith(RobolectricTestRunner.class)
public class FeedSuggestionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Shaped like Instagram 449's feed item kinds, a few of them. */
    enum Kind {
        MEDIA, AD, CLIPS_NETEGO, END_OF_FEED_DEMARCATOR, STORIES_NETEGO, EXPLORE_STORY, SUGGESTED_USERS, SUGGESTED_TOP_ACCOUNTS,
        SUGGESTED_PRODUCERS, SUGGESTED_PRODUCERS_V2, SUGGESTED_CLOSE_FRIENDS, SUGGESTED_BUSINESSES, SUGGESTED_SHOPS,
        SUGGESTED_HASHTAGS, SUGGESTED_SHAREABLE_LISTS, FOLLOW_CHAIN_USERS, TYA_SUGGESTIONS_IN_FEED_UNIT,
        THREADS_IN_FEED_UNIT, TIFU_IN_EXPLORE, EOF_TIFU, KICKSTART_FEED_UNIT, COMMUNITIES_IN_FEED_UNIT, SMSL_IN_FEED_UNIT,
        LIVE_CHAT_IN_FEED_UNIT, SPORT_GAME_IN_FEED_UNIT, MEMU_IN_FEED_UNIT, THREADS_IN_FEED_UNIT_MUSE,
        VERTICALS_IN_FEED_UNIT, FEED_SURVEY, SHOPPING_RECOMMENDATION_UNIT, PRODUCT_PIVOTS, LIVE_SHOPPING_NETEGO
    }

    /** The item's other enum on 449: why the feed was fetched. */
    enum Fetch { COLD_START, PULL_TO_REFRESH }

    /** A feed item with its enum fields, the kind not first among them. */
    static final class Item {
        Fetch fetch = Fetch.COLD_START;
        Kind kind;
        String id = "3719";

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    /** Every test but the ones about Home's own reads runs the way a build without them does. */
    @Before
    public void withoutHomeReads() {
        FeedSuggestions.homeReadsForTests = false;
        FeedSuggestions.homePageLost = false;
        FeedSuggestions.homePageEndCounted = false;
        FeedSuggestions.verdictFeed = null;
        FeedSuggestions.tookOut = false;
    }

    @After
    public void resetHomeReads() {
        // Closes a page a failed test left open on this thread.
        FeedSuggestions.homePageParsed();
        FeedSuggestions.homeReadsForTests = null;
        FeedSuggestions.homePageLost = false;
        FeedSuggestions.homePageEndCounted = false;
        FeedSuggestions.verdictFeed = null;
        FeedSuggestions.emptiness = FeedSuggestions::feedEmpty;
        FeedSuggestions.tookOut = false;
        for (BooleanSetting setting : suggestionSwitches()) setting.resetToDefault();
    }

    /**
     * Every switch filter() takes items out for. A method, not a static field: Settings can only be
     * loaded once the test's context is in place.
     */
    private static BooleanSetting[] suggestionSwitches() {
        return new BooleanSetting[] {Settings.HIDE_SUGGESTED_POSTS, Settings.HIDE_SUGGESTED_ACCOUNTS,
                Settings.HIDE_THREADS_POSTS, Settings.HIDE_FEED_SURVEYS, Settings.HIDE_FEED_SHOPPING};
    }

    /** A home feed object, as the adapter reads its flag from, that says whether it's empty. */
    static final class Feed {
        boolean empty;

        Feed(boolean empty) {
            this.empty = empty;
        }
    }

    /** The adapter's read of its flag: the feed handed over first, then Instagram's answer through feedEnded. */
    private static int adapterReads(Feed feed, int noMorePages) {
        FeedSuggestions.homeFeedRead(feed);
        return FeedSuggestions.feedEnded(noMorePages);
    }

    /** One item Home's reads take: through the helper's filter, then homeItem. */
    private static Object homeRead(Item item) {
        return FeedSuggestions.homeItem(FeedSuggestions.filter(item), ignored -> 0);
    }

    /** A page of Home's own feed response holding [items], parsed on this thread. */
    private static void homePage(Item... items) {
        FeedSuggestions.homePageStarts();
        for (Item item : items) homeRead(item);
        FeedSuggestions.homePageParsed();
    }

    private void withHomeReads() {
        FeedSuggestions.homeReadsForTests = true;
        FeedSuggestions.emptiness = feed -> ((Feed) feed).empty ? 1 : 0;
    }

    @Test
    public void everySuggestedAccountsUnitIsTakenOut() {
        for (String name : FeedSuggestions.ACCOUNT_UNITS) {
            assertNull(name, FeedSuggestions.filter(new Item(Kind.valueOf(name))));
        }
    }

    @Test
    public void aSurveyIsTakenOutWhileItsSwitchIsOn() {
        assertNull(FeedSuggestions.filter(new Item(Kind.FEED_SURVEY)));
        Settings.HIDE_FEED_SURVEYS.save(false);
        try {
            Item survey = new Item(Kind.FEED_SURVEY);
            assertSame(survey, FeedSuggestions.filter(survey));
            assertNull(FeedSuggestions.filter(new Item(Kind.THREADS_IN_FEED_UNIT)));
        } finally {
            Settings.HIDE_FEED_SURVEYS.resetToDefault();
        }
    }

    @Test
    public void everyShoppingUnitIsTakenOutWhileItsSwitchIsOn() {
        for (String name : FeedSuggestions.SHOPPING_UNITS) {
            assertNull(name, FeedSuggestions.filter(new Item(Kind.valueOf(name))));
        }
        Settings.HIDE_FEED_SHOPPING.save(false);
        try {
            Item shop = new Item(Kind.SHOPPING_RECOMMENDATION_UNIT);
            assertSame(shop, FeedSuggestions.filter(shop));
            assertNull(FeedSuggestions.filter(new Item(Kind.FEED_SURVEY)));
        } finally {
            Settings.HIDE_FEED_SHOPPING.resetToDefault();
        }
    }

    @Test
    public void everyThreadsUnitIsTakenOut() {
        for (String name : FeedSuggestions.THREADS_UNITS) {
            assertNull(name, FeedSuggestions.filter(new Item(Kind.valueOf(name))));
        }
    }

    /**
     * Posts, ads, the reels row (Hide Reels in the feed's), the end of the feed, the stories row and
     * Meta AI's Imagine unit all stay.
     */
    @Test
    public void everythingElseStays() {
        for (Kind kind : new Kind[] {Kind.MEDIA, Kind.AD, Kind.CLIPS_NETEGO, Kind.END_OF_FEED_DEMARCATOR,
                Kind.STORIES_NETEGO, Kind.MEMU_IN_FEED_UNIT}) {
            Item item = new Item(kind);
            assertSame(kind.name(), item, FeedSuggestions.filter(item));
        }
        Item unset = new Item(null);
        assertSame(unset, FeedSuggestions.filter(unset));
        assertNull(FeedSuggestions.filter(null));
    }

    /** A single post or reel labeled "Suggested for you" or "Suggested Reel" is an explore story. */
    @Test
    public void aSuggestedPostIsTakenOut() {
        assertNull(FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY)));
    }

    /** Each switch holds back its own kind only. */
    @Test
    public void withASwitchOffItsKindStays() {
        Settings.HIDE_SUGGESTED_ACCOUNTS.save(false);
        try {
            Item row = new Item(Kind.SUGGESTED_USERS);
            assertSame(row, FeedSuggestions.filter(row));
            assertNull(FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY)));
        } finally {
            Settings.HIDE_SUGGESTED_ACCOUNTS.save(true);
        }
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        try {
            Item post = new Item(Kind.EXPLORE_STORY);
            assertSame(post, FeedSuggestions.filter(post));
            assertNull(FeedSuggestions.filter(new Item(Kind.SUGGESTED_USERS)));
            assertNull(FeedSuggestions.filter(new Item(Kind.THREADS_IN_FEED_UNIT)));
        } finally {
            Settings.HIDE_SUGGESTED_POSTS.save(true);
        }
        Settings.HIDE_THREADS_POSTS.save(false);
        try {
            Item threads = new Item(Kind.THREADS_IN_FEED_UNIT);
            assertSame(threads, FeedSuggestions.filter(threads));
            Item kickstart = new Item(Kind.KICKSTART_FEED_UNIT);
            assertSame(kickstart, FeedSuggestions.filter(kickstart));
            assertNull(FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY)));
            assertNull(FeedSuggestions.filter(new Item(Kind.SUGGESTED_USERS)));
        } finally {
            Settings.HIDE_THREADS_POSTS.save(true);
        }
    }

    /**
     * With both patches in, the parse helper's answer goes through both filters, in the order the
     * patches applied, and the two read their kinds off the same item class. Either order takes out
     * the same items.
     */
    @Test
    public void besideHideReelsInTheFeedEachTakesOutItsOwn() {
        Settings.HIDE_FEED_REELS.save(true);
        try {
            for (Kind kind : Kind.values()) {
                boolean dropped = kind == Kind.CLIPS_NETEGO || FeedSuggestions.KINDS.contains(kind.name());
                Item item = new Item(kind);
                Object reelsFirst = FeedSuggestions.filter(FeedReels.filter(item));
                Object suggestionsFirst = FeedReels.filter(FeedSuggestions.filter(item));
                if (dropped) {
                    assertNull(kind.name(), reelsFirst);
                    assertNull(kind.name(), suggestionsFirst);
                } else {
                    assertSame(kind.name(), item, reelsFirst);
                    assertSame(kind.name(), item, suggestionsFirst);
                }
            }
        } finally {
            Settings.HIDE_FEED_REELS.resetToDefault();
        }
    }

    /**
     * The feed's own "no next page" answer stands until an item's been taken out, a kept item or one
     * whose switch is off included. After that the emptied feed has no next page.
     */
    @Test
    public void theFeedEndsOnceSomethingsTakenOut() {
        FeedSuggestions.tookOut = false;
        assertEquals(0, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));
        FeedSuggestions.filter(new Item(Kind.MEDIA));
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        try {
            FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY));
        } finally {
            Settings.HIDE_SUGGESTED_POSTS.save(true);
        }
        assertEquals("nothing taken out yet", 0, FeedSuggestions.feedEnded(0));

        FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY));
        assertEquals(1, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));
    }

    /**
     * #28: where Home's reads go through homeItem, only a page of Home's own feed response that lost
     * items ends Home. An item taken out of another feed, a null the helper answered on its own, and
     * Home's store of the last run losing everything before its first page all leave Instagram's
     * answer, so a Home waiting on its first page keeps its loading placeholder and draws no Welcome
     * card at startup.
     */
    @Test
    public void onlyAPageOfHomesOwnEndsHome() {
        withHomeReads();
        Feed waiting = new Feed(true);
        assertNull(FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY)));
        assertEquals("taken out of Explore's chain or a shop feed", 0, adapterReads(waiting, 0));
        assertEquals(1, adapterReads(waiting, 1));

        assertNull(FeedSuggestions.homeItem(FeedSuggestions.filter(null), item -> 0));
        assertEquals("the helper's own null", 0, adapterReads(waiting, 0));

        for (int i = 0; i < 20; i++) assertNull(homeRead(new Item(Kind.EXPLORE_STORY)));
        assertNull(homeRead(new Item(Kind.SUGGESTED_USERS)));
        assertEquals("Home's store lost everything, and its first page hasn't come", 0, adapterReads(waiting, 0));

        homePage(new Item(Kind.SUGGESTED_USERS), new Item(Kind.EXPLORE_STORY));
        assertEquals("Home's own page lost everything", 1, adapterReads(waiting, 0));
        assertEquals(1, adapterReads(waiting, 1));

        Item post = new Item(Kind.MEDIA);
        homePage(post, new Item(Kind.EXPLORE_STORY));
        Feed showing = new Feed(false);
        assertEquals("Home shows the post it kept", 0, adapterReads(showing, 0));
        homePage(post);
        assertEquals("a page that lost nothing", 0, adapterReads(waiting, 0));
    }

    /**
     * #105, as the S22 showed it on an account that follows nobody: Home's store reads lose a run of
     * suggestions on one thread while Home's own page, on another, loses a suggested post and six
     * Explore stories and keeps one item Instagram draws elsewhere. The store's reads don't count
     * toward the page, the page ends the empty Home, and the report says so once however often the
     * adapter asks.
     */
    @Test
    public void aPageOfOnlySuggestionsEndsTheEmptyHomeAndTheReportSaysSo() throws Exception {
        withHomeReads();
        FeedFilterCounters.snapshotAndClear();
        Thread store = new Thread(() -> {
            for (int i = 0; i < 160; i++) homeRead(new Item(Kind.EXPLORE_STORY));
        });
        FeedSuggestions.homePageStarts();
        assertNull(homeRead(new Item(Kind.SUGGESTED_USERS)));
        store.start();
        store.join();
        for (int i = 0; i < 6; i++) assertNull(homeRead(new Item(Kind.EXPLORE_STORY)));
        Item drawnElsewhere = new Item(Kind.MEDIA);
        assertSame(drawnElsewhere, homeRead(drawnElsewhere));
        FeedSuggestions.homePageParsed();

        Feed home = new Feed(true);
        assertEquals("every post removed", 1, adapterReads(home, 0));
        assertEquals(1, adapterReads(home, 0));
        String report = String.join("\n", FeedFilterCounters.report());
        assertTrue(report, report.contains(FeedSuggestions.HOME_ENDED + " 1"));

        for (BooleanSetting setting : suggestionSwitches()) setting.save(false);
        assertEquals("every suggestion switch off", 0, adapterReads(home, 0));
    }

    /**
     * Each page is judged on its own (#104, #105). A post an earlier page kept, the first account's
     * before a switch, doesn't hold off the end of a Home whose next page lost everything, a later page
     * that keeps a post holds it off again, and a page with no items, a response the parser gave up
     * on, leaves the verdict where it was.
     */
    @Test
    public void eachPageIsJudgedOnItsOwn() {
        withHomeReads();
        FeedFilterCounters.snapshotAndClear();
        Feed home = new Feed(true);
        Item post = new Item(Kind.MEDIA);
        homePage(post);
        assertEquals("the first account's page kept its post", 0, adapterReads(home, 0));

        homePage(new Item(Kind.EXPLORE_STORY), new Item(Kind.SUGGESTED_USERS));
        assertEquals("the next account's page lost everything", 1, adapterReads(home, 0));

        homePage();
        assertEquals("an empty response changes nothing", 1, adapterReads(home, 0));

        homePage(post);
        assertEquals("a later page kept a post", 0, adapterReads(home, 0));

        homePage(new Item(Kind.EXPLORE_STORY));
        assertEquals(1, adapterReads(home, 0));
        String report = String.join("\n", FeedFilterCounters.report());
        assertTrue("counted once per emptied page: " + report, report.contains(FeedSuggestions.HOME_ENDED + " 2"));
    }

    /**
     * A feed that can't say whether it's empty, because the check failed or the build has none, still
     * ends after a page that lost items: Instagram checks the feed itself before drawing the empty card.
     * Only the report's count waits for a feed that says it's empty, and one with posts keeps
     * Instagram's answer.
     */
    @Test
    public void aFeedThatCantSayStillEndsUncounted() {
        withHomeReads();
        FeedFilterCounters.snapshotAndClear();
        homePage(new Item(Kind.EXPLORE_STORY));
        FeedSuggestions.emptiness = feed -> {
            throw new IllegalStateException("gone");
        };
        Feed home = new Feed(true);
        assertEquals("the check threw", 1, adapterReads(home, 0));
        FeedSuggestions.emptiness = FeedSuggestions::feedEmpty;
        assertEquals("the stub, unfilled", 1, adapterReads(home, 0));
        assertEquals("no feed handed over", 1, FeedSuggestions.feedEnded(0));
        String report = String.join("\n", FeedFilterCounters.report());
        assertFalse(report, report.contains(FeedSuggestions.HOME_ENDED));

        withHomeReads();
        assertEquals("a feed with posts", 0, adapterReads(new Feed(false), 0));
    }

    /**
     * A page's verdict belongs to the Home that read it first. Another account's Home, empty while
     * its first page is on the way or its request failed, keeps Instagram's answer until a page of
     * its own comes, rather than getting the end card from the account before it.
     */
    @Test
    public void anotherAccountsHomeWaitsForItsOwnPage() {
        withHomeReads();
        Feed first = new Feed(true);
        Feed next = new Feed(true);
        homePage(new Item(Kind.EXPLORE_STORY));
        assertEquals(1, adapterReads(first, 0));

        assertEquals("ended by the other account's page", 0, adapterReads(next, 0));
        assertEquals("the first account's Home stays ended", 1, adapterReads(first, 0));

        homePage(new Item(Kind.SUGGESTED_USERS));
        assertEquals("its own page lost everything", 1, adapterReads(next, 0));
        assertEquals(0, adapterReads(first, 0));
    }

    /** The feed handed over is the next flag read's alone: a read without one doesn't reuse it. */
    @Test
    public void theFeedHandedOverIsForTheNextReadOnly() {
        withHomeReads();
        homePage(new Item(Kind.EXPLORE_STORY));
        assertEquals(0, adapterReads(new Feed(false), 0));
        assertEquals("a read with no feed handed over can't tell", 1, FeedSuggestions.feedEnded(0));
    }

    @Test
    @Config(sdk = {28, 37})
    public void turningOffAllSuggestionSwitchesRestoresBothNativeEndAnswers() {
        BooleanSetting[] switches = suggestionSwitches();
        try {
            for (Kind removed : new Kind[] {Kind.EXPLORE_STORY, Kind.SUGGESTED_USERS,
                    Kind.THREADS_IN_FEED_UNIT, Kind.FEED_SURVEY, Kind.SHOPPING_RECOMMENDATION_UNIT}) {
                for (BooleanSetting setting : switches) setting.save(true);
                FeedSuggestions.tookOut = false;
                assertNull(removed.name(), FeedSuggestions.filter(new Item(removed)));
                assertEquals("filtered " + removed, 1, FeedSuggestions.feedEnded(0));

                for (BooleanSetting setting : switches) setting.save(false);
                assertEquals("all switches off after " + removed, 0, FeedSuggestions.feedEnded(0));
                assertEquals("native EOF after " + removed, 1, FeedSuggestions.feedEnded(1));

                for (BooleanSetting setting : switches) {
                    setting.save(true);
                    assertEquals(setting.key, 1, FeedSuggestions.feedEnded(0));
                    assertEquals(setting.key, 1, FeedSuggestions.feedEnded(1));
                    setting.save(false);
                }
            }
        } finally {
            for (BooleanSetting setting : switches) setting.resetToDefault();
            FeedSuggestions.tookOut = false;
        }
    }

    /**
     * Past Following's end card a promised next page stands until a suggested post's been taken
     * out, and then only while Hide suggested posts is on.
     */
    @Test
    public void theEndCardRuleAppliesOnceSuggestionsAreTakenOut() {
        FeedSuggestions.tookOut = false;
        assertEquals(1, FeedSuggestions.moreAfterFollowing(1));
        assertEquals(0, FeedSuggestions.moreAfterFollowing(0));
        assertEquals(0, FeedSuggestions.endCardRule(0));
        assertEquals(1, FeedSuggestions.endCardRule(1));

        FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY));
        assertEquals(0, FeedSuggestions.moreAfterFollowing(1));
        assertEquals(0, FeedSuggestions.moreAfterFollowing(0));
        assertEquals(1, FeedSuggestions.endCardRule(0));
        assertEquals(1, FeedSuggestions.endCardRule(1));
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        try {
            assertEquals("suggested posts are back", 1, FeedSuggestions.moreAfterFollowing(1));
            assertEquals("suggested posts are back", 0, FeedSuggestions.endCardRule(0));
        } finally {
            Settings.HIDE_SUGGESTED_POSTS.save(true);
        }
    }

    /** The diagnostic report counts the suggestions seen and the ones taken out, by kind. */
    @Test
    public void theReportCountsTheSuggestions() {
        FeedFilterCounters.snapshotAndClear();
        FeedSuggestions.filter(new Item(Kind.SUGGESTED_USERS));
        FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY));
        FeedSuggestions.filter(new Item(Kind.MEDIA));
        List<String> report = FeedFilterCounters.report();
        assertTrue(report.toString(), report.toString().contains(FeedSuggestions.ROUTE));
        assertTrue(report.toString(), report.toString().contains("SUGGESTED_USERS"));
        assertTrue(report.toString(), report.toString().contains("EXPLORE_STORY"));
    }
}
