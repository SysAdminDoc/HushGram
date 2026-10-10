/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

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

    /** Every test but the one about Home's own reads runs the way a build without them does. */
    @Before
    public void withoutHomeReads() {
        FeedSuggestions.homeReadsForTests = false;
        FeedSuggestions.homeLost = false;
        FeedSuggestions.homeKept = false;
        FeedSuggestions.homeReadAt = 0;
    }

    @After
    public void resetHomeReads() {
        FeedSuggestions.homeReadsForTests = null;
        FeedSuggestions.homeLost = false;
        FeedSuggestions.homeKept = false;
        FeedSuggestions.homeReadAt = 0;
        FeedSuggestions.clock = SystemClock::elapsedRealtime;
        FeedSuggestions.tookOut = false;
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
     * Where Home's reads go through homeItem, only Home's own losses end it (#28). An item taken out
     * of another feed, a null the helper answered on its own, or a loss beside a kept post leaves
     * Instagram's answer, so a Home waiting on its first page keeps its loading placeholder.
     */
    @Test
    public void onlyHomesOwnReadsEndHome() {
        FeedSuggestions.homeReadsForTests = true;
        FeedSuggestions.tookOut = false;
        assertNull(FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY)));
        assertEquals("taken out of another feed", 0, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));

        assertNull(FeedSuggestions.homeItem(FeedSuggestions.filter(null), item -> 0));
        assertEquals("the helper's own null", 0, FeedSuggestions.feedEnded(0));

        assertNull(FeedSuggestions.homeItem(FeedSuggestions.filter(new Item(Kind.SUGGESTED_USERS)), item -> 0));
        assertEquals("Home lost one and kept none", 1, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));

        Item post = new Item(Kind.MEDIA);
        assertSame(post, FeedSuggestions.homeItem(FeedSuggestions.filter(post), item -> FeedSuggestions.PHOTO));
        assertEquals("Home kept a post", 0, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));
    }

    /**
     * Only Home's latest read decides. A post an earlier read kept, the first account's before a
     * switch, doesn't hold off the end of a Home whose next read lost everything (#104, #105), and a
     * later read that keeps a post holds it off again.
     */
    @Test
    public void aLaterReadIsJudgedOnItsOwn() {
        long[] now = {50_000};
        FeedSuggestions.clock = () -> now[0];
        FeedSuggestions.homeReadsForTests = true;
        Item post = new Item(Kind.MEDIA);
        assertSame(post, FeedSuggestions.homeItem(FeedSuggestions.filter(post), item -> FeedSuggestions.PHOTO));
        assertNull(FeedSuggestions.homeItem(FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY)), item -> 0));
        assertEquals("the first account's Home kept a post", 0, FeedSuggestions.feedEnded(0));

        now[0] += FeedSuggestions.READ_GAP_MS + 1;
        assertNull(FeedSuggestions.homeItem(FeedSuggestions.filter(new Item(Kind.EXPLORE_STORY)), item -> 0));
        now[0] += 10;
        assertNull(FeedSuggestions.homeItem(FeedSuggestions.filter(new Item(Kind.SUGGESTED_USERS)), item -> 0));
        assertEquals("the next account's Home lost everything", 1, FeedSuggestions.feedEnded(0));

        now[0] += FeedSuggestions.READ_GAP_MS + 1;
        assertSame(post, FeedSuggestions.homeItem(FeedSuggestions.filter(post), item -> FeedSuggestions.PHOTO));
        assertEquals("a later read kept a post", 0, FeedSuggestions.feedEnded(0));
    }

    @Test
    @Config(sdk = {28, 37})
    public void turningOffAllSuggestionSwitchesRestoresBothNativeEndAnswers() {
        BooleanSetting[] switches = {Settings.HIDE_SUGGESTED_POSTS,
                Settings.HIDE_SUGGESTED_ACCOUNTS, Settings.HIDE_THREADS_POSTS};
        try {
            for (Kind removed : new Kind[] {Kind.EXPLORE_STORY, Kind.SUGGESTED_USERS,
                    Kind.THREADS_IN_FEED_UNIT}) {
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
