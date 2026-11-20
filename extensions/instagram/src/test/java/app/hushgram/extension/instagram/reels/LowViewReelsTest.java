/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Which reels a page keeps by their numbers (views, likes, likes and comments for their views,
 * length and age), the order Newest first puts them in, Home's posts judged by the same numbers, and
 * that the page comes back as it came whenever the hook can't decide.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LowViewReelsTest {
    /** Now, for the age rule: a fixed time in seconds since 1970. */
    private static final long NOW = 1_800_000_000L;
    private static final long DAY = 86_400L;

    /** A reel's post: its counts (or none), length, post time and whether its author is followed. */
    static final class Post {
        final long views;
        final long likes;
        final boolean followed;
        long comments = LowViewReels.NO_COUNT;
        double seconds = LowViewReels.NO_SECONDS;
        long postedAt = LowViewReels.NO_COUNT;

        Post(long views, long likes, boolean followed) {
            this.views = views;
            this.likes = likes;
            this.followed = followed;
        }
    }

    /** A Reels item carrying a post, which can be null. */
    static final class Clip {
        final Post post;

        Clip(Post post) {
            this.post = post;
        }

        Clip comments(long count) {
            post.comments = count;
            return this;
        }

        Clip seconds(double length) {
            post.seconds = length;
            return this;
        }

        Clip posted(long at) {
            post.postedAt = at;
            return this;
        }

        @Override
        public String toString() {
            return post == null ? "empty clip" : "clip " + post.views + "/" + post.likes + " at " + post.postedAt;
        }
    }

    static class Reader implements LowViewReels.Reader {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Object media(Object item) {
            reads.incrementAndGet();
            return item instanceof Clip ? ((Clip) item).post : null;
        }

        @Override
        public long views(Object media) {
            return ((Post) media).views;
        }

        @Override
        public long likes(Object media) {
            return ((Post) media).likes;
        }

        @Override
        public boolean followed(Object media) {
            return ((Post) media).followed;
        }

        @Override
        public long comments(Object media) {
            return ((Post) media).comments;
        }

        @Override
        public double seconds(Object media) {
            return ((Post) media).seconds;
        }

        @Override
        public long postedAt(Object media) {
            return ((Post) media).postedAt;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Reader reader = new Reader();
    private static final String REELS_TAB = "clips_viewer_clips_tab";

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_LOW_VIEW_REELS.save(true);
        Settings.MIN_REEL_VIEWS.save(1000);
        Settings.MIN_REEL_LIKES.save(10);
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_LOW_VIEW_REELS.resetToDefault();
        Settings.MIN_REEL_VIEWS.resetToDefault();
        Settings.MIN_REEL_LIKES.resetToDefault();
        Settings.MAX_REEL_VIEWS.resetToDefault();
        Settings.MIN_REEL_LIKES_PER_1000.resetToDefault();
        Settings.MIN_REEL_COMMENTS_PER_1000.resetToDefault();
        Settings.MIN_REEL_SECONDS.resetToDefault();
        Settings.MAX_REEL_SECONDS.resetToDefault();
        Settings.MAX_REEL_AGE_DAYS.resetToDefault();
        Settings.REELS_NEWEST_FIRST.resetToDefault();
        Settings.HIDE_LOW_VIEW_HOME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private List<?> page(List<?> items, String module) {
        return LowViewReels.page(items, module, reader, LowViewReels::rulesWhenOn, () -> NOW);
    }

    /** A reel judged by its views alone: its likes are far over any minimum. */
    private static Clip clip(long views, boolean followed) {
        return clip(views, 1_000_000, followed);
    }

    private static Clip clip(long views, long likes, boolean followed) {
        return new Clip(new Post(views, likes, followed));
    }

    /** A reel inside the default numbers, to be judged by the one number a test sets. */
    private static Clip fine() {
        return clip(50_000, false);
    }

    private static String report() {
        return FeedFilterCounters.report().toString();
    }

    @Test
    public void belowTheLineGoesAndTheLineStays() {
        Clip low = clip(999, false);
        Clip line = clip(1000, false);
        Clip high = clip(50_000, false);
        List<?> kept = page(Arrays.asList(low, line, high), REELS_TAB);
        assertEquals(Arrays.asList(line, high), kept);
        assertTrue(HookStatus.missing(FamilyNames.LOW_VIEW_REELS).toString(),
                HookStatus.missing(FamilyNames.LOW_VIEW_REELS).isEmpty());
        assertTrue(report().contains(LowViewReels.BELOW));
    }

    @Test
    public void aReelWithNoCountAndAFollowedAuthorStay() {
        Clip none = clip(LowViewReels.NO_COUNT, false);
        Clip followed = clip(3, true);
        Clip low = clip(3, false);
        assertEquals(Arrays.asList(none, followed), page(Arrays.asList(none, followed, low), REELS_TAB));
        String report = report();
        assertTrue(report, report.contains(LowViewReels.NO_VIEWS));
        assertTrue(report, report.contains(LowViewReels.FOLLOWED));
    }

    @Test
    public void unknownItemsAndNullsStayInPlace() {
        Clip low = clip(1, false);
        Clip high = clip(5000, false);
        Object other = "not a reels item";
        Clip empty = new Clip(null);
        assertEquals(Arrays.asList(null, other, empty, high), page(Arrays.asList(null, other, low, empty, high), REELS_TAB));
        assertTrue(report().contains(LowViewReels.NOT_A_REEL));
    }

    @Test
    public void onlyTheReelsTabIsFiltered() {
        List<?> items = Arrays.asList(clip(1, false), clip(9999, false));
        assertSame(items, page(items, "clips_viewer_clips_profile"));
        assertSame(items, page(items, "clips_viewer_direct"));
        assertSame(items, page(items, null));
    }

    @Test
    public void aPageOfOnlyLowReelsIsKeptWhole() {
        List<?> items = Arrays.asList(clip(1, false), clip(2, false));
        assertSame(items, page(items, REELS_TAB));
        assertTrue(report().contains(LowViewReels.PAGE_KEPT));
    }

    @Test
    public void offPausedAndUnreadyKeepEveryReel() {
        List<?> items = Arrays.asList(clip(1, false), clip(9999, false));
        Settings.HIDE_LOW_VIEW_REELS.save(false);
        assertSame(items, page(items, REELS_TAB));
        Settings.HIDE_LOW_VIEW_REELS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(items, page(items, REELS_TAB));
        PauseForTests.resume();
        assertEquals(1, page(items, REELS_TAB).size());
    }

    @Test
    public void aNullPageAndAFailingReaderAreHarmless() {
        assertEquals(null, LowViewReels.page(null, REELS_TAB));
        List<?> items = Arrays.asList(clip(1, false), clip(9999, false));
        LowViewReels.Reader broken = new Reader() {
            @Override
            public long views(Object media) {
                throw new IllegalStateException("boom");
            }
        };
        assertSame(items, LowViewReels.page(items, REELS_TAB, broken,
                () -> new LowViewReels.Rules().minViews(1000).minLikes(10), () -> NOW));
    }

    @Test
    public void fewLikesGoWhetherOrNotThereIsAViewCount() {
        Clip noViewsFewLikes = clip(LowViewReels.NO_COUNT, 9, false);
        Clip noViewsLikesOnTheLine = clip(LowViewReels.NO_COUNT, 10, false);
        Clip manyViewsFewLikes = clip(50_000, 2, false);
        Clip both = clip(50_000, 400, false);
        List<?> kept = page(Arrays.asList(noViewsFewLikes, noViewsLikesOnTheLine, manyViewsFewLikes, both), REELS_TAB);
        assertEquals(Arrays.asList(noViewsLikesOnTheLine, both), kept);
        String report = report();
        assertTrue(report, report.contains(LowViewReels.FEW_LIKES));
        assertTrue(report, report.contains(LowViewReels.NO_VIEWS));
    }

    @Test
    public void noLikeCountAndAFollowedAuthorKeepAReel() {
        Clip noCounts = clip(LowViewReels.NO_COUNT, LowViewReels.NO_COUNT, false);
        Clip followed = clip(LowViewReels.NO_COUNT, 1, true);
        Clip fewLikes = clip(LowViewReels.NO_COUNT, 1, false);
        assertEquals(Arrays.asList(noCounts, followed), page(Arrays.asList(noCounts, followed, fewLikes), REELS_TAB));
        String report = report();
        assertTrue(report, report.contains(LowViewReels.NO_LIKES));
        assertTrue(report, report.contains(LowViewReels.FOLLOWED));
    }

    @Test
    public void zeroLikesLeavesLikesAlone() {
        Settings.MIN_REEL_LIKES.save(0);
        List<?> items = Arrays.asList(clip(LowViewReels.NO_COUNT, 1, false), clip(50_000, 0, false),
                clip(LowViewReels.NO_COUNT, LowViewReels.NO_COUNT, false));
        assertSame(items, page(items, REELS_TAB));
        String report = report();
        assertFalse(report, report.contains(LowViewReels.NO_LIKES));
        assertFalse(report, report.contains(LowViewReels.FEW_LIKES));
    }

    @Test
    public void viewsAndLikesBothCountTowardAWholePage() {
        Clip fewViews = clip(5, false);
        Clip fewLikes = clip(LowViewReels.NO_COUNT, 3, false);
        List<?> items = Arrays.asList(fewViews, fewLikes);
        assertSame(items, page(items, REELS_TAB));
        assertTrue(report().contains(LowViewReels.PAGE_KEPT));
    }

    @Test
    public void theLikesMinimumDefaultsToTenAndCanBeZero() {
        Settings.MIN_REEL_LIKES.resetToDefault();
        assertEquals(10, (int) Settings.MIN_REEL_LIKES.get());
        Settings.MIN_REEL_LIKES.save(-5);
        assertEquals(0, (int) Settings.MIN_REEL_LIKES.get());
        Settings.HIDE_LOW_VIEW_REELS.save(false);
        Settings.MIN_REEL_LIKES.save(50);
        assertNull("off, nothing is judged", LowViewReels.rulesWhenOn());
    }

    @Test
    public void theMinimumDefaultsToAThousandAndCanBeZero() {
        Settings.MIN_REEL_VIEWS.resetToDefault();
        assertEquals(1000, (int) Settings.MIN_REEL_VIEWS.get());
        Settings.MIN_REEL_VIEWS.save(-3);
        assertEquals(0, (int) Settings.MIN_REEL_VIEWS.get());
    }

    @Test
    public void theNewNumbersStartAtZeroAndStayInRange() {
        assertEquals(0, (int) Settings.MAX_REEL_VIEWS.get());
        assertEquals(0, (int) Settings.MIN_REEL_LIKES_PER_1000.get());
        assertEquals(0, (int) Settings.MIN_REEL_COMMENTS_PER_1000.get());
        assertEquals(0, (int) Settings.MIN_REEL_SECONDS.get());
        assertEquals(0, (int) Settings.MAX_REEL_SECONDS.get());
        assertEquals(0, (int) Settings.MAX_REEL_AGE_DAYS.get());
        assertFalse(Settings.REELS_NEWEST_FIRST.get());
        assertFalse(Settings.HIDE_LOW_VIEW_HOME.get());
        Settings.MIN_REEL_LIKES_PER_1000.save(5000);
        assertEquals(1000, (int) Settings.MIN_REEL_LIKES_PER_1000.get());
        Settings.MAX_REEL_SECONDS.save(1_000_000);
        assertEquals(86_400, (int) Settings.MAX_REEL_SECONDS.get());
        Settings.MAX_REEL_AGE_DAYS.save(99_999);
        assertEquals(3650, (int) Settings.MAX_REEL_AGE_DAYS.get());
    }

    @Test
    public void theRulesReadEverySetting() {
        Settings.MAX_REEL_VIEWS.save(2);
        Settings.MIN_REEL_LIKES_PER_1000.save(3);
        Settings.MIN_REEL_COMMENTS_PER_1000.save(4);
        Settings.MIN_REEL_SECONDS.save(5);
        Settings.MAX_REEL_SECONDS.save(6);
        Settings.MAX_REEL_AGE_DAYS.save(7);
        Settings.REELS_NEWEST_FIRST.save(true);
        LowViewReels.Rules rules = LowViewReels.rulesWhenOn();
        assertNotNull(rules);
        assertEquals(1000, rules.minViews);
        assertEquals(2, rules.maxViews);
        assertEquals(10, rules.minLikes);
        assertEquals(3, rules.likesPerThousand);
        assertEquals(4, rules.commentsPerThousand);
        assertEquals(5, rules.shortest);
        assertEquals(6, rules.longest);
        assertEquals(7, rules.maxAgeDays);
        assertTrue(rules.newestFirst);
    }

    @Test
    public void everyNumberAtZeroLeavesThePageAlone() {
        Settings.MIN_REEL_VIEWS.save(0);
        Settings.MIN_REEL_LIKES.save(0);
        List<?> items = Arrays.asList(clip(1, 0, false), clip(LowViewReels.NO_COUNT, LowViewReels.NO_COUNT, false));
        assertSame(items, page(items, REELS_TAB));
        assertEquals("no post is read", 0, reader.reads.get());
        assertFalse(report(), report().contains(LowViewReels.NO_VIEWS));
    }

    @Test
    public void overTheMaximumGoesAndZeroMeansNoMaximum() {
        Settings.MAX_REEL_VIEWS.save(10_000);
        Clip over = clip(10_001, false);
        Clip line = clip(10_000, false);
        Clip none = clip(LowViewReels.NO_COUNT, false);
        assertEquals(Arrays.asList(line, none), page(Arrays.asList(over, line, none), REELS_TAB));
        assertTrue(report(), report().contains(LowViewReels.ABOVE));

        Settings.MAX_REEL_VIEWS.save(0);
        List<?> items = Arrays.asList(clip(5_000_000, false), line);
        assertSame(items, page(items, REELS_TAB));
    }

    @Test
    public void viewsAtZeroStillLetsTheMaximumJudge() {
        Settings.MIN_REEL_VIEWS.save(0);
        Settings.MIN_REEL_LIKES.save(0);
        Settings.MAX_REEL_VIEWS.save(100);
        Clip few = clip(3, false);
        Clip many = clip(101, false);
        assertEquals(Arrays.asList(few), page(Arrays.asList(few, many), REELS_TAB));
    }

    @Test
    public void fewLikesForTheirViewsGo() {
        Settings.MIN_REEL_LIKES.save(0);
        Settings.MIN_REEL_LIKES_PER_1000.save(20);
        Clip under = clip(10_000, 199, false);
        Clip line = clip(10_000, 200, false);
        Clip noViews = clip(LowViewReels.NO_COUNT, 1, false);
        Clip noLikes = clip(10_000, LowViewReels.NO_COUNT, false);
        assertEquals(Arrays.asList(line, noViews, noLikes), page(Arrays.asList(under, line, noViews, noLikes), REELS_TAB));
        String report = report();
        assertTrue(report, report.contains(LowViewReels.FEW_LIKES_FOR_VIEWS));
        assertTrue(report, report.contains(LowViewReels.NO_VIEWS));
        assertTrue(report, report.contains(LowViewReels.NO_LIKES));
    }

    @Test
    public void fewCommentsForTheirViewsGo() {
        Settings.MIN_REEL_COMMENTS_PER_1000.save(2);
        Clip under = fine().comments(99);
        Clip line = fine().comments(100);
        Clip noComments = fine();
        Clip noViews = clip(LowViewReels.NO_COUNT, false).comments(0);
        assertEquals(Arrays.asList(line, noComments, noViews), page(Arrays.asList(under, line, noComments, noViews), REELS_TAB));
        String report = report();
        assertTrue(report, report.contains(LowViewReels.FEW_COMMENTS_FOR_VIEWS));
        assertTrue(report, report.contains(LowViewReels.NO_COMMENTS));
    }

    @Test
    public void tooShortAndTooLongGoAndTheLinesStay() {
        Settings.MIN_REEL_SECONDS.save(5);
        Settings.MAX_REEL_SECONDS.save(60);
        Clip short_ = fine().seconds(4.5);
        Clip shortest = fine().seconds(5);
        Clip longest = fine().seconds(60);
        Clip long_ = fine().seconds(60.5);
        Clip photo = fine();
        assertEquals(Arrays.asList(shortest, longest, photo),
                page(Arrays.asList(short_, shortest, longest, long_, photo), REELS_TAB));
        String report = report();
        assertTrue(report, report.contains(LowViewReels.TOO_SHORT));
        assertTrue(report, report.contains(LowViewReels.TOO_LONG));
        assertTrue(report, report.contains(LowViewReels.NO_LENGTH));
    }

    @Test
    public void olderThanTheMaximumAgeGoes() {
        Settings.MAX_REEL_AGE_DAYS.save(7);
        Clip line = fine().posted(NOW - 7 * DAY);
        Clip old = fine().posted(NOW - 7 * DAY - 1);
        Clip fresh = fine().posted(NOW - 60);
        Clip untimed = fine();
        assertEquals(Arrays.asList(line, fresh, untimed), page(Arrays.asList(line, old, fresh, untimed), REELS_TAB));
        String report = report();
        assertTrue(report, report.contains(LowViewReels.TOO_OLD));
        assertTrue(report, report.contains(LowViewReels.NO_TIME));
    }

    @Test
    public void aFollowedAuthorKeepsAReelWhateverItsNumbers() {
        Settings.MAX_REEL_AGE_DAYS.save(1);
        Settings.MAX_REEL_SECONDS.save(10);
        Clip followed = clip(1, 0, true).seconds(500).posted(NOW - 900 * DAY);
        Clip stranger = clip(1, 0, false).seconds(500).posted(NOW - 900 * DAY);
        Clip other = fine();
        assertEquals(Arrays.asList(followed, other), page(Arrays.asList(followed, stranger, other), REELS_TAB));
    }

    @Test
    public void eachReasonIsCountedOnItsOwn() {
        Settings.MAX_REEL_SECONDS.save(10);
        page(Arrays.asList(clip(5, false), clip(6, false), fine().seconds(30), fine()), REELS_TAB);
        String report = report();
        assertTrue(report, report.contains(LowViewReels.BELOW));
        assertTrue(report, report.contains(LowViewReels.TOO_LONG));
        String hooks = HookStatus.report().toString();
        assertTrue(hooks, hooks.contains(LowViewReels.REMOVED + " 3"));
    }

    @Test
    public void newestFirstPutsTimedReelsInTheirPlacesNewestOnTop() {
        Settings.REELS_NEWEST_FIRST.save(true);
        Clip a = fine().posted(NOW - 300);
        Clip b = fine().posted(NOW - 100);
        Object other = "not a reels item";
        Clip c = fine().posted(NOW - 200);
        Clip untimed = fine();
        List<?> sorted = page(Arrays.asList(a, b, other, c, untimed), REELS_TAB);
        assertEquals(Arrays.asList(b, c, other, a, untimed), sorted);
        assertTrue(report(), report().contains(LowViewReels.NEWEST_FIRST));
    }

    @Test
    public void newestFirstLeavesAnOrderedPageAndTiesAlone() {
        Settings.REELS_NEWEST_FIRST.save(true);
        Clip first = fine().posted(NOW - 10);
        Clip tie = fine().posted(NOW - 10);
        Clip last = fine().posted(NOW - 99);
        List<?> items = Arrays.asList(first, tie, last);
        assertSame(items, page(items, REELS_TAB));
        assertFalse(report(), report().contains(LowViewReels.NEWEST_FIRST));
    }

    @Test
    public void newestFirstWorksOnItsOwnAndAfterRemovals() {
        Settings.REELS_NEWEST_FIRST.save(true);
        Settings.MIN_REEL_VIEWS.save(0);
        Settings.MIN_REEL_LIKES.save(0);
        Clip older = clip(1, 0, false).posted(NOW - 50);
        Clip newer = clip(1, 0, false).posted(NOW - 5);
        assertEquals("no number, only the order", Arrays.asList(newer, older), page(Arrays.asList(older, newer), REELS_TAB));

        Settings.MIN_REEL_VIEWS.save(1000);
        Clip low = clip(3, false).posted(NOW);
        Clip a = fine().posted(NOW - 50);
        Clip b = fine().posted(NOW - 5);
        assertEquals(Arrays.asList(b, a), page(Arrays.asList(a, low, b), REELS_TAB));
    }

    @Test
    public void newestFirstStaysOffUntilTurnedOn() {
        Clip older = fine().posted(NOW - 50);
        Clip newer = fine().posted(NOW - 5);
        List<?> items = Arrays.asList(older, newer);
        assertSame(items, page(items, REELS_TAB));
    }

    @Test
    public void homeTakesTheSameNumbersOnlyWithAlsoOnHome() {
        Settings.MAX_REEL_AGE_DAYS.save(7);
        Post old = new Post(50_000, 1_000_000, false);
        old.postedAt = NOW - 30 * DAY;
        assertNull("Also on Home is off", home(old));
        assertNull(LowViewReels.homeRulesWhenOn());

        Settings.HIDE_LOW_VIEW_HOME.save(true);
        assertEquals(LowViewReels.TOO_OLD, home(old));
        assertEquals(LowViewReels.BELOW, home(new Post(5, 1_000_000, false)));
        assertNull("inside every number", home(new Post(50_000, 1_000_000, false)));
        assertNull("followed", home(new Post(5, 1, true)));
        assertTrue(FeedFilterCounters.report().toString().contains(LowViewReels.FOLLOWED));
        assertNull(home(null));
    }

    @Test
    public void homeIsLeftAloneWhenOnlyTheOrderIsAskedFor() {
        Settings.HIDE_LOW_VIEW_HOME.save(true);
        Settings.REELS_NEWEST_FIRST.save(true);
        Settings.MIN_REEL_VIEWS.save(0);
        Settings.MIN_REEL_LIKES.save(0);
        assertNull(LowViewReels.homeRulesWhenOn());
        assertFalse(LowViewReels.homeOn());
        Settings.MIN_REEL_VIEWS.save(10);
        assertTrue(LowViewReels.homeOn());
        Settings.HIDE_LOW_VIEW_REELS.save(false);
        assertFalse("the switch rules Also on Home", LowViewReels.homeOn());
    }

    @Test
    public void aFailingHomeReadKeepsThePost() {
        Settings.HIDE_LOW_VIEW_HOME.save(true);
        LowViewReels.Reader broken = new Reader() {
            @Override
            public long views(Object media) {
                throw new IllegalStateException("boom");
            }
        };
        assertNull(LowViewReels.homeReason(new Post(5, 1, false), broken, LowViewReels::homeRulesWhenOn, () -> NOW,
                LowViewReels.HOME_ROUTE));
        String missing = HookStatus.missing(FamilyNames.LOW_VIEW_REELS).toString();
        assertTrue(missing, missing.contains("home post"));
    }

    private String home(Post post) {
        return LowViewReels.homeReason(post, reader, LowViewReels::homeRulesWhenOn, () -> NOW, LowViewReels.HOME_ROUTE);
    }

    private List<?> added(List<?> items, String module, int held) {
        return LowViewReels.added(items, module, held, reader, LowViewReels::rulesWhenOn, () -> NOW);
    }

    /** Once the viewer holds reels, a short page where every reel goes is emptied instead of kept whole (#780). */
    @Test
    public void addedReelsGoEvenWhenNoneStay() {
        Settings.MIN_REEL_SECONDS.save(20);
        Settings.MAX_REEL_AGE_DAYS.save(30);
        Clip short_ = fine().seconds(9);
        Clip old = fine().seconds(45).posted(NOW - 40 * DAY);
        assertEquals(Arrays.asList(), added(Arrays.asList(short_, old), REELS_TAB, 6));

        Clip kept = fine().seconds(45).posted(NOW - DAY);
        Clip low = clip(5, false).seconds(45);
        assertEquals(Arrays.asList(kept), added(Arrays.asList(low, kept, short_), REELS_TAB, 9));
        String report = report();
        assertTrue(report, report.contains(LowViewReels.ADDED_ROUTE));
        assertTrue(report, report.contains(LowViewReels.TOO_SHORT));
        assertTrue(report, report.contains(LowViewReels.TOO_OLD));
        assertTrue(report, report.contains(LowViewReels.BELOW));
    }

    /** Before the viewer holds more than its placeholder, the page filter decides, so the tab never starts empty. */
    @Test
    public void theFirstAddAndOtherViewersAreLeftToThePage() {
        List<?> items = Arrays.asList(clip(1, false), clip(2, false));
        assertSame(items, added(items, REELS_TAB, 0));
        assertSame(items, added(items, REELS_TAB, 1));
        assertSame(items, added(items, "clips_viewer_clips_profile", 9));
        assertSame(items, added(items, null, 9));
        List<?> inside = Arrays.asList(fine(), fine());
        assertSame("nothing goes", inside, added(inside, REELS_TAB, 9));
        assertNull(added(null, REELS_TAB, 9));
    }

    @Test
    public void addedReelsFromFollowedAccountsStay() {
        Clip followed = clip(3, true);
        Clip stranger = clip(3, false);
        assertEquals(Arrays.asList(followed), added(Arrays.asList(followed, stranger), REELS_TAB, 4));
        assertTrue(report().contains(LowViewReels.FOLLOWED));
    }

    @Test
    public void addedReelsStayWhenOffOrPaused() {
        List<?> items = Arrays.asList(clip(1, false), clip(2, false));
        Settings.HIDE_LOW_VIEW_REELS.save(false);
        assertSame(items, added(items, REELS_TAB, 9));
        Settings.HIDE_LOW_VIEW_REELS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(items, added(items, REELS_TAB, 9));
        PauseForTests.resume();
        assertEquals(0, added(items, REELS_TAB, 9).size());
    }

    @Test
    public void aFailingReaderKeepsTheAddedReels() {
        List<?> items = Arrays.asList(clip(1, false), clip(9999, false));
        LowViewReels.Reader broken = new Reader() {
            @Override
            public long views(Object media) {
                throw new IllegalStateException("boom");
            }
        };
        assertSame(items, LowViewReels.added(items, REELS_TAB, 9, broken,
                () -> new LowViewReels.Rules().minViews(1000).minLikes(10), () -> NOW));
        String missing = HookStatus.missing(FamilyNames.LOW_VIEW_REELS).toString();
        assertTrue(missing, missing.contains("reels items added"));
    }
}
