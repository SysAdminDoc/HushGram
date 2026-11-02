/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
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

/** Which reels a page keeps by their view and like counts, and that the page comes back as it came whenever the hook can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LowViewReelsTest {
    /** A reel's post: a view count and a like count (or none) and whether its author is followed. */
    static final class Post {
        final long views;
        final long likes;
        final boolean followed;

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
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private List<?> page(List<?> items, String module) {
        return LowViewReels.page(items, module, reader, LowViewReels::minimumWhenOn, LowViewReels::minimumLikesWhenOn);
    }

    /** A reel judged by its views alone: its likes are far over any minimum. */
    private static Clip clip(long views, boolean followed) {
        return clip(views, 1_000_000, followed);
    }

    private static Clip clip(long views, long likes, boolean followed) {
        return new Clip(new Post(views, likes, followed));
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
        assertTrue(FeedFilterCounters.report().toString().contains(LowViewReels.BELOW));
    }

    @Test
    public void aReelWithNoCountAndAFollowedAuthorStay() {
        Clip none = clip(LowViewReels.NO_COUNT, false);
        Clip followed = clip(3, true);
        Clip low = clip(3, false);
        assertEquals(Arrays.asList(none, followed), page(Arrays.asList(none, followed, low), REELS_TAB));
        String report = FeedFilterCounters.report().toString();
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
        assertTrue(FeedFilterCounters.report().toString().contains(LowViewReels.NOT_A_REEL));
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
        assertTrue(FeedFilterCounters.report().toString().contains(LowViewReels.PAGE_KEPT));
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
        assertSame(items, LowViewReels.page(items, REELS_TAB, broken, () -> 1000, () -> 10));
    }

    @Test
    public void fewLikesGoWhetherOrNotThereIsAViewCount() {
        Clip noViewsFewLikes = clip(LowViewReels.NO_COUNT, 9, false);
        Clip noViewsLikesOnTheLine = clip(LowViewReels.NO_COUNT, 10, false);
        Clip manyViewsFewLikes = clip(50_000, 2, false);
        Clip both = clip(50_000, 400, false);
        List<?> kept = page(Arrays.asList(noViewsFewLikes, noViewsLikesOnTheLine, manyViewsFewLikes, both), REELS_TAB);
        assertEquals(Arrays.asList(noViewsLikesOnTheLine, both), kept);
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(LowViewReels.FEW_LIKES));
        assertTrue(report, report.contains(LowViewReels.NO_VIEWS));
    }

    @Test
    public void noLikeCountAndAFollowedAuthorKeepAReel() {
        Clip noCounts = clip(LowViewReels.NO_COUNT, LowViewReels.NO_COUNT, false);
        Clip followed = clip(LowViewReels.NO_COUNT, 1, true);
        Clip fewLikes = clip(LowViewReels.NO_COUNT, 1, false);
        assertEquals(Arrays.asList(noCounts, followed), page(Arrays.asList(noCounts, followed, fewLikes), REELS_TAB));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(LowViewReels.NO_LIKES));
        assertTrue(report, report.contains(LowViewReels.FOLLOWED));
    }

    @Test
    public void zeroLikesLeavesLikesAlone() {
        Settings.MIN_REEL_LIKES.save(0);
        List<?> items = Arrays.asList(clip(LowViewReels.NO_COUNT, 1, false), clip(50_000, 0, false),
                clip(LowViewReels.NO_COUNT, LowViewReels.NO_COUNT, false));
        assertSame(items, page(items, REELS_TAB));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, !report.contains(LowViewReels.NO_LIKES));
        assertTrue(report, !report.contains(LowViewReels.FEW_LIKES));
    }

    @Test
    public void viewsAndLikesBothCountTowardAWholePage() {
        Clip fewViews = clip(5, false);
        Clip fewLikes = clip(LowViewReels.NO_COUNT, 3, false);
        List<?> items = Arrays.asList(fewViews, fewLikes);
        assertSame(items, page(items, REELS_TAB));
        assertTrue(FeedFilterCounters.report().toString().contains(LowViewReels.PAGE_KEPT));
    }

    @Test
    public void theLikesMinimumDefaultsToTenAndCanBeZero() {
        Settings.MIN_REEL_LIKES.resetToDefault();
        assertEquals(10, (int) Settings.MIN_REEL_LIKES.get());
        Settings.MIN_REEL_LIKES.save(-5);
        assertEquals(0, (int) Settings.MIN_REEL_LIKES.get());
        Settings.HIDE_LOW_VIEW_REELS.save(false);
        Settings.MIN_REEL_LIKES.save(50);
        assertEquals(0, LowViewReels.minimumLikesWhenOn());
    }

    @Test
    public void theMinimumDefaultsToAThousandAndStaysInRange() {
        Settings.MIN_REEL_VIEWS.resetToDefault();
        assertEquals(1000, (int) Settings.MIN_REEL_VIEWS.get());
        Settings.MIN_REEL_VIEWS.save(0);
        assertEquals(1, (int) Settings.MIN_REEL_VIEWS.get());
    }
}
