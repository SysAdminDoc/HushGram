/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.misc.GlassReelsHide;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When a reel is cropped to fill the screen instead of fitting with bars (#288). */
@RunWith(RobolectricTestRunner.class)
public class ReelFillTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A 9:16 reel on a 20:9 screen with both bars hidden: (1080/2400 - 0.5625) / 0.5625 is 20% off. */
    private static final float TALL_SCREEN = 20f;

    /** The same reel in a 1080x2201 viewer, with the comment bar gone and the status bar still up. */
    private static final float BAR_GONE = 12.8f;

    /**
     * #544: a 1080x2316 screen's Reels tab viewer with the tab bar's room, 2199 tall under the status
     * bar: (0.5625 - 1080/2199) / 0.5625 is 12.7% off.
     */
    private static final float TAB_BAR_GONE = 12.7f;

    /** Named like the enum Instagram hands the decision, a few of its sources. */
    private enum Source { REPOSTS_GRID, SELF_REPOSTS_GRID, PROFILE_CLIPS, CLIPS_TAB, CLIPS_TAB_HOMECOMING }

    /** A reel from the Reels tab, which the reposted reels switch doesn't cover. */
    private static boolean fill(float cropPercent) {
        return ReelFill.fill(Source.CLIPS_TAB, cropPercent);
    }

    @Before
    public void switchOn() {
        Settings.FULL_SCREEN_REELS.save(true);
        Settings.FILL_REEL_SCREEN.save(true);
    }

    @After
    public void switchBack() {
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FILL_REEL_SCREEN.resetToDefault();
        Settings.FIT_REEL_SCREEN.resetToDefault();
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.resetToDefault();
        Settings.HIDE_REEL_COMMENT_BAR.resetToDefault();
        Settings.KEEP_REEL_COMMENT_BAR_SPACE.resetToDefault();
        Settings.FILL_STORY_SCREEN.resetToDefault();
        Settings.GLASS_TAB_BAR_REELS_HIDE.resetToDefault();
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_FLOAT.resetToDefault();
        Settings.GLASS_TAB_BAR_REELS_BEHIND.resetToDefault();
    }

    /**
     * #544: a Reels auto-hide gives the Reels tab's viewer the tab bar's height, and the reel came up
     * low with a black band over it. That viewer fills, other viewers don't, and neither do reels past
     * the cap.
     */
    @Test
    public void theReelsTabFillsTheRoomAnAutoHiddenTabBarLeaves() {
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertFalse("the bar stays", fill(TAB_BAR_GONE));
        Settings.GLASS_TAB_BAR_REELS_HIDE.save(GlassReelsHide.TWO_SECONDS);
        assertTrue(fill(TAB_BAR_GONE));
        assertTrue(ReelFill.fill(Source.CLIPS_TAB_HOMECOMING, TAB_BAR_GONE));
        assertTrue("the bar still up before the wait", fill(7f));
        assertFalse("a 4:5 reel still fits", fill(43.75f));
        assertFalse(fill(ReelFill.MOST_CROP + 0.01f));
        assertFalse("a reel from a profile keeps Instagram's choice", ReelFill.fill(Source.PROFILE_CLIPS, TAB_BAR_GONE));
        assertFalse("not the source enum", ReelFill.fill("CLIPS_TAB", TAB_BAR_GONE));
        assertFalse(ReelFill.fill(null, TAB_BAR_GONE));
        Settings.GLASS_TAB_BAR_REELS_HIDE.save(GlassReelsHide.EIGHT_SECONDS);
        assertTrue(fill(TAB_BAR_GONE));
        Settings.FIT_REEL_SCREEN.save(true);
        assertTrue("Show the whole reel is still asked first", ReelFill.fit());
    }

    /** #544: reels run behind the floating glass bar have its room too, only with the glass bar on. */
    @Test
    public void reelsRunBehindTheGlassBarFillToo() {
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        Settings.GLASS_TAB_BAR_REELS_BEHIND.save(true);
        assertFalse("glass bar off", fill(TAB_BAR_GONE));
        Settings.GLASS_TAB_BAR.save(true);
        Settings.GLASS_TAB_BAR_FLOAT.save(true);
        assertTrue(fill(TAB_BAR_GONE));
        assertFalse(ReelFill.fill(Source.PROFILE_CLIPS, TAB_BAR_GONE));
        Settings.GLASS_TAB_BAR_FLOAT.save(false);
        assertFalse("a bar that doesn't float leaves the viewer above it", fill(TAB_BAR_GONE));
    }

    /** #436: stories answer on their own switch, whatever the reel switches say. */
    @Test
    public void storiesFillTheScreenOnTheirOwnSwitch() {
        assertFalse("off to start", ReelFill.story());
        Settings.FILL_STORY_SCREEN.save(true);
        assertTrue(ReelFill.story());
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertTrue("doesn't need Full screen Reels", ReelFill.story());
        Settings.FILL_STORY_SCREEN.save(false);
        assertFalse(ReelFill.story());
    }

    @Test
    public void aReelCloseToTheScreensShapeFillsIt() {
        assertTrue(fill(TALL_SCREEN));
        assertTrue(fill(0f));
        assertTrue(fill(ReelFill.MOST_CROP));
    }

    @Test
    public void squareAndWideReelsAndBadNumbersStayWithInstagram() {
        // A 4:5 video on that screen would lose 44% of its width, a square one more.
        assertFalse(fill(43.75f));
        assertFalse(fill(ReelFill.MOST_CROP + 0.01f));
        assertFalse(fill(-1f));
        assertFalse(fill(Float.NaN));
        assertFalse(fill(Float.POSITIVE_INFINITY));
    }

    @Test
    public void eitherSwitchOffLeavesItToInstagram() {
        Settings.FILL_REEL_SCREEN.save(false);
        assertFalse(fill(TALL_SCREEN));
        Settings.FILL_REEL_SCREEN.save(true);
        Settings.FULL_SCREEN_REELS.save(false);
        assertFalse(fill(TALL_SCREEN));
    }

    /**
     * #371: the comment bar's height goes to the viewer, and on a 1080x2400 screen a 9:16 reel then
     * came up with black bands above and below it, with neither Full screen switch on.
     */
    @Test
    public void hidingTheCommentBarOnAllReelsFillsTheRoomItLeaves() {
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertFalse("the bar's still there", fill(BAR_GONE));
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        assertTrue(fill(BAR_GONE));
        assertTrue(fill(ReelFill.MOST_CROP));
        assertFalse("a 4:5 reel still fits", fill(43.75f));
        assertFalse(fill(ReelFill.MOST_CROP + 0.01f));
        assertFalse(fill(Float.NaN));
    }

    /** Show the whole reel is asked first, so it keeps its bars with the comment bar gone. */
    @Test
    public void showTheWholeReelStillWinsWithTheCommentBarGone() {
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        Settings.FIT_REEL_SCREEN.save(true);
        assertTrue(ReelFill.fit());
    }

    /** The reposted reels switch leaves the same room, but only on the reels it takes the bar off. */
    @Test
    public void hidingTheBarOnRepostedReelsFillsThoseReels() {
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertFalse("the bar's still there", ReelFill.fill(Source.REPOSTS_GRID, BAR_GONE));
        Settings.HIDE_REEL_COMMENT_BAR.save(true);
        assertTrue(ReelFill.fill(Source.REPOSTS_GRID, BAR_GONE));
        assertTrue(ReelFill.fill(Source.SELF_REPOSTS_GRID, BAR_GONE));
        assertFalse("a 4:5 reel still fits", ReelFill.fill(Source.REPOSTS_GRID, 43.75f));
        assertFalse("this reel keeps its bar", ReelFill.fill(Source.PROFILE_CLIPS, BAR_GONE));
        assertFalse("this reel keeps its bar", ReelFill.fill(Source.CLIPS_TAB, BAR_GONE));
        assertFalse("not the source enum", ReelFill.fill("REPOSTS_GRID", BAR_GONE));
        assertFalse(ReelFill.fill(null, BAR_GONE));
    }

    /**
     * #523: Keep the comment bar's space leaves the bar's room empty, so the viewer doesn't grow and
     * those reels are left to Instagram. The Reels tab's bar keeps no room, so that viewer still fills,
     * and so does Fill the screen.
     */
    @Test
    public void keepingTheBarsSpaceLeavesThoseReelsToInstagram() {
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        Settings.KEEP_REEL_COMMENT_BAR_SPACE.save(true);
        assertFalse(ReelFill.fill(Source.PROFILE_CLIPS, BAR_GONE));
        assertFalse(ReelFill.fill(Source.REPOSTS_GRID, BAR_GONE));
        assertTrue("the Reels tab's bar keeps no room", ReelFill.fill(Source.CLIPS_TAB, BAR_GONE));
        assertTrue(ReelFill.fill(Source.CLIPS_TAB_HOMECOMING, BAR_GONE));
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(false);
        Settings.HIDE_REEL_COMMENT_BAR.save(true);
        assertFalse(ReelFill.fill(Source.REPOSTS_GRID, BAR_GONE));
        Settings.FULL_SCREEN_REELS.save(true);
        Settings.FILL_REEL_SCREEN.save(true);
        assertTrue("Fill the screen still fills", ReelFill.fill(Source.REPOSTS_GRID, TALL_SCREEN));
    }

    /** Full screen Reels and the switch for all reels don't care where a reel was opened from. */
    @Test
    public void theOtherSwitchesFillWhateverTheSource() {
        assertTrue(ReelFill.fill(null, TALL_SCREEN));
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        assertTrue(ReelFill.fill(null, BAR_GONE));
    }

    /** #491: a 9:16 reel on a 1248x1972 cover screen has room 12.5% wider than it. */
    private static final float COVER_SCREEN = 1248f / 1972f;

    /** The same 12.5% the other way, room narrower than 9:16. */
    private static final float NARROW_ROOM = ReelFill.REEL_SHAPE * 0.875f;

    /** #491: on a screen wider than 9:16 the reel gets the whole width, within the same cap either way. */
    @Test
    public void aWideScreenGivesTheReelItsWholeWidth() {
        assertTrue("16:10 cover screen", ReelFill.wide(COVER_SCREEN));
        assertTrue("narrower room, same distance", ReelFill.wide(NARROW_ROOM));
        assertTrue("already 9:16", ReelFill.wide(ReelFill.REEL_SHAPE));
        assertTrue("right at the cap, wide side", ReelFill.wide(ReelFill.REEL_SHAPE * 1.249f));
        assertTrue("right at the cap, narrow side", ReelFill.wide(ReelFill.REEL_SHAPE * 0.751f));
    }

    @Test
    public void roomPastTheCapKeepsInstagramsColumn() {
        assertFalse("a 16:10 tablet held sideways", ReelFill.wide(1.6f));
        assertFalse("a square screen", ReelFill.wide(1f));
        assertFalse("past the cap, wide side", ReelFill.wide(ReelFill.REEL_SHAPE * 1.26f));
        assertFalse("past the cap, narrow side", ReelFill.wide(ReelFill.REEL_SHAPE * 0.74f));
        assertFalse(ReelFill.wide(Float.NaN));
        assertFalse(ReelFill.wide(Float.POSITIVE_INFINITY));
    }

    @Test
    public void theWideSideNeedsBothSwitchesAndShowTheWholeReelWins() {
        Settings.FIT_REEL_SCREEN.save(true);
        assertFalse("Show the whole reel wins", ReelFill.wide(COVER_SCREEN));
        Settings.FIT_REEL_SCREEN.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertFalse(ReelFill.wide(COVER_SCREEN));
        Settings.FILL_REEL_SCREEN.save(true);
        Settings.FULL_SCREEN_REELS.save(false);
        assertFalse(ReelFill.wide(COVER_SCREEN));
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        assertFalse("hiding the comment bar alone doesn't change a wide screen's column", ReelFill.wide(COVER_SCREEN));
    }

    /** piko #2183: Show the whole reel answers on its own switch, with or without Full screen Reels. */
    @Test
    public void showTheWholeReelFitsOnItsOwnSwitch() {
        assertFalse("off to start", ReelFill.fit());
        Settings.FIT_REEL_SCREEN.save(true);
        assertTrue(ReelFill.fit());
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertTrue("doesn't need Full screen Reels", ReelFill.fit());
        Settings.FIT_REEL_SCREEN.save(false);
        assertFalse(ReelFill.fit());
    }
}
