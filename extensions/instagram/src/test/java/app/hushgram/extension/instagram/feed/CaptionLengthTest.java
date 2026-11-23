/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Expand captions (#771): a feed post's caption is cut at the count picked in settings, the whole
 * caption to start. Reels get the whole caption on a switch of their own (#752), and stories and the
 * viewers keep Instagram's count.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class CaptionLengthTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Instagram's surface enum, whose constants keep these names. */
    private enum Surface {
        NOT_SET, EXPLORE_FEED, MAIN_FEED, HASHTAG_PAGE, SAVE_FEED, SINGLE_MEDIA_FEED, MEDIA_CONTEXTUAL_FEED,
        SHOPPING_MEDIA_VIEWER, CLIPS_VIEWER, CLIPS_REMIX_ATTRIBUTION_FEED, STORY_VIEWER, STORY_MENTION_RESHARE,
        COMMENTS_VIEW
    }

    private static final Surface[] REELS = {Surface.CLIPS_VIEWER, Surface.CLIPS_REMIX_ATTRIBUTION_FEED};

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.EXPAND_CAPTIONS.resetToDefault();
        Settings.CAPTION_LINES.resetToDefault();
        Settings.EXPAND_REEL_CAPTIONS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.EXPAND_CAPTIONS.resetToDefault();
        Settings.CAPTION_LINES.resetToDefault();
        Settings.EXPAND_REEL_CAPTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static int on(Object surface, int instagram, CaptionLines picked) {
        return CaptionLength.maxLines(surface, instagram, () -> true, () -> picked, () -> false);
    }

    private static int reels(Object surface, int instagram, boolean feeds, boolean reels) {
        return CaptionLength.maxLines(surface, instagram, () -> feeds, () -> CaptionLines.TWO, () -> reels);
    }

    @Test
    public void theSwitchStartsOffWithTheWholeCaptionPicked() {
        assertEquals(Boolean.FALSE, Settings.EXPAND_CAPTIONS.defaultValue);
        assertEquals(CaptionLines.WHOLE, Settings.CAPTION_LINES.defaultValue);
        assertEquals(2, CaptionLength.maxLines(Surface.MAIN_FEED, 2));
    }

    @Test
    public void everyFeedGetsThePick() {
        for (Surface feed : new Surface[]{Surface.MAIN_FEED, Surface.EXPLORE_FEED, Surface.HASHTAG_PAGE,
                Surface.SAVE_FEED, Surface.SINGLE_MEDIA_FEED, Surface.MEDIA_CONTEXTUAL_FEED}) {
            assertEquals(feed.name(), Integer.MAX_VALUE, on(feed, 2, CaptionLines.WHOLE));
            assertEquals(feed.name(), 4, on(feed, 2, CaptionLines.FOUR));
            assertEquals(feed.name(), 1, on(feed, 2, CaptionLines.ONE));
        }
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(CaptionLength.COUNT_CHANGED));
        assertTrue(HookStatus.missing(FamilyNames.EXPAND_CAPTIONS).toString(),
                HookStatus.missing(FamilyNames.EXPAND_CAPTIONS).isEmpty());
    }

    @Test
    public void noPickReadsAsTheWholeCaption() {
        assertEquals(Integer.MAX_VALUE, on(Surface.MAIN_FEED, 2, null));
    }

    @Test
    public void withTheReelSwitchOffReelsStoriesAndViewersKeepInstagramsCount() {
        for (Surface other : new Surface[]{Surface.CLIPS_VIEWER, Surface.CLIPS_REMIX_ATTRIBUTION_FEED,
                Surface.STORY_VIEWER, Surface.STORY_MENTION_RESHARE, Surface.SHOPPING_MEDIA_VIEWER,
                Surface.COMMENTS_VIEW, Surface.NOT_SET}) {
            assertEquals(other.name(), 1, on(other, 1, CaptionLines.WHOLE));
            assertEquals(other.name(), 2, on(other, 2, CaptionLines.TEN));
        }
    }

    @Test
    public void theReelSwitchShowsTheWholeCaptionOnReels() {
        for (Surface reel : REELS) {
            assertEquals(reel.name(), Integer.MAX_VALUE, reels(reel, 1, false, true));
            assertEquals(reel.name(), Integer.MAX_VALUE, reels(reel, 2, true, true));
        }
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(CaptionLength.REEL_COUNT_CHANGED));
        assertTrue(HookStatus.missing(FamilyNames.EXPAND_CAPTIONS).toString(),
                HookStatus.missing(FamilyNames.EXPAND_CAPTIONS).isEmpty());
    }

    @Test
    public void eachSwitchLeavesTheOtherSurfacesAlone() {
        for (Surface reel : REELS) {
            assertEquals("the feed switch alone leaves " + reel, 1, reels(reel, 1, true, false));
        }
        assertEquals("the reel switch alone leaves the feed", 2, reels(Surface.MAIN_FEED, 2, false, true));
        assertEquals("the reel switch alone leaves Explore", 1, reels(Surface.EXPLORE_FEED, 1, false, true));
        assertEquals("both on, the feed still gets its pick", 2, reels(Surface.MAIN_FEED, 1, true, true));
        for (Surface other : new Surface[]{Surface.STORY_VIEWER, Surface.STORY_MENTION_RESHARE,
                Surface.SHOPPING_MEDIA_VIEWER, Surface.COMMENTS_VIEW, Surface.NOT_SET}) {
            assertEquals(other.name(), 1, reels(other, 1, true, true));
        }
    }

    /**
     * #821: the Reels tab's own caption render (Instagram 456) asks with its count alone. The reels switch
     * opens it whole, the same as a reel surface through the feed builder.
     */
    @Test
    public void theReelsTabsOwnCaptionOpensWholeWithTheReelSwitch() {
        assertEquals(Integer.MAX_VALUE, CaptionLength.reelLines(2, () -> true));
        assertEquals(Integer.MAX_VALUE, CaptionLength.reelLines(1, () -> true));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(CaptionLength.REEL_COUNT_CHANGED));
        assertTrue(HookStatus.missing(FamilyNames.EXPAND_CAPTIONS).toString(),
                HookStatus.missing(FamilyNames.EXPAND_CAPTIONS).isEmpty());
    }

    @Test
    public void theReelsTabsOwnCaptionKeepsInstagramsCountOtherwise() {
        assertEquals("switch off", 2, CaptionLength.reelLines(2, () -> false));
        assertEquals("no cut", 0, CaptionLength.reelLines(0, () -> true));
        assertEquals("already open", Integer.MAX_VALUE, CaptionLength.reelLines(Integer.MAX_VALUE, () -> true));
        assertEquals("a broken switch", 2, CaptionLength.reelLines(2, () -> {
            throw new IllegalStateException("boom");
        }));
        // The public hook reads the saved switch, which starts off.
        assertEquals(2, CaptionLength.reelLines(2));
        Settings.EXPAND_REEL_CAPTIONS.save(true);
        assertEquals(Integer.MAX_VALUE, CaptionLength.reelLines(2));
    }

    @Test
    public void anUncutOrOpenedReelCaptionStaysThatWay() {
        assertEquals("no cut", 0, reels(Surface.CLIPS_VIEWER, 0, false, true));
        assertEquals("opened by Instagram", 11, reels(Surface.CLIPS_VIEWER, 11, false, true));
        assertEquals(Integer.MAX_VALUE, reels(Surface.CLIPS_VIEWER, Integer.MAX_VALUE, false, true));
    }

    @Test
    public void aThrowingReelSwitchKeepsInstagramsCountAndIsRecorded() {
        int lines = CaptionLength.maxLines(Surface.CLIPS_VIEWER, 1, () -> false, () -> CaptionLines.WHOLE, () -> {
            throw new IllegalStateException("setting");
        });

        assertEquals(1, lines);
        String missing = HookStatus.missing(FamilyNames.EXPAND_CAPTIONS).toString();
        assertTrue(missing, missing.contains(CaptionLength.HOOK));
    }

    @Test
    public void anUncutOrOpenedCaptionStaysThatWay() {
        assertEquals("no cut", 0, on(Surface.MAIN_FEED, 0, CaptionLines.THREE));
        assertEquals("no cut", -1, on(Surface.MAIN_FEED, -1, CaptionLines.THREE));
        assertEquals("opened by Instagram", Integer.MAX_VALUE, on(Surface.MAIN_FEED, Integer.MAX_VALUE, CaptionLines.THREE));
        assertEquals("opened by Instagram", 11, on(Surface.MAIN_FEED, 11, CaptionLines.ONE));
        assertEquals("the largest pick still applies", 3, on(Surface.MAIN_FEED, 10, CaptionLines.THREE));
    }

    @Test
    public void aSurfaceThatIsntAnEnumKeepsInstagramsCount() {
        assertEquals(2, on(null, 2, CaptionLines.WHOLE));
        assertEquals(2, on("MAIN_FEED", 2, CaptionLines.WHOLE));
    }

    @Test
    public void offKeepsInstagramsCount() {
        assertEquals(2, CaptionLength.maxLines(Surface.MAIN_FEED, 2, () -> false, () -> CaptionLines.WHOLE));
    }

    @Test
    public void aThrowKeepsInstagramsCountAndIsRecorded() {
        int lines = CaptionLength.maxLines(Surface.MAIN_FEED, 2, () -> true, () -> {
            throw new IllegalStateException("setting");
        });

        assertEquals(2, lines);
        String missing = HookStatus.missing(FamilyNames.EXPAND_CAPTIONS).toString();
        assertTrue(missing, missing.contains(CaptionLength.HOOK));
    }

    @Test
    public void theSettingsDecideThroughThePublicHook() {
        Settings.EXPAND_CAPTIONS.save(true);
        assertEquals(Integer.MAX_VALUE, CaptionLength.maxLines(Surface.MAIN_FEED, 2));

        Settings.CAPTION_LINES.save(CaptionLines.SIX);
        assertEquals(6, CaptionLength.maxLines(Surface.MAIN_FEED, 2));
        assertEquals("Reels stay while their switch is off", 1, CaptionLength.maxLines(Surface.CLIPS_VIEWER, 1));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 2, CaptionLength.maxLines(Surface.MAIN_FEED, 2));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("unready", 2, CaptionLength.maxLines(Surface.MAIN_FEED, 2)));
    }

    @Test
    public void theReelSwitchDecidesThroughThePublicHook() {
        assertEquals("starts off", Boolean.FALSE, Settings.EXPAND_REEL_CAPTIONS.defaultValue);
        assertEquals(1, CaptionLength.maxLines(Surface.CLIPS_VIEWER, 1));

        Settings.EXPAND_REEL_CAPTIONS.save(true);
        assertEquals(Integer.MAX_VALUE, CaptionLength.maxLines(Surface.CLIPS_VIEWER, 1));
        assertEquals(Integer.MAX_VALUE, CaptionLength.maxLines(Surface.CLIPS_REMIX_ATTRIBUTION_FEED, 2));
        assertEquals("the feed switch is still off", 2, CaptionLength.maxLines(Surface.MAIN_FEED, 2));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1, CaptionLength.maxLines(Surface.CLIPS_VIEWER, 1));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("unready", 1, CaptionLength.maxLines(Surface.CLIPS_VIEWER, 1)));
    }
}
