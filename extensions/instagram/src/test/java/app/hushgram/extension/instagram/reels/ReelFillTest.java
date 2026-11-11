/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

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
        ReelFill.SHARED_REELS.clear();
        ReelFill.SHARED_FRAMES.clear();
    }

    /** #618: a 1080x2340 phone, taller than 9:16, where a reel 1080 wide is 1920 tall. */
    private static final int PHONE_WIDTH = 1080;
    private static final int PHONE_HEIGHT = 2340;

    /** The story viewer's room with its whole-screen story frame in it, laid out on that phone. */
    private static FrameLayout storyRoom() {
        FrameLayout room = new FrameLayout(RuntimeEnvironment.getApplication());
        room.addView(new FrameLayout(room.getContext()), new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        layOut(room);
        return room;
    }

    private static void layOut(View room) {
        room.measure(View.MeasureSpec.makeMeasureSpec(PHONE_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PHONE_HEIGHT, View.MeasureSpec.EXACTLY));
        room.layout(0, 0, PHONE_WIDTH, PHONE_HEIGHT);
    }

    /** What Instagram's whole-screen story layout does to the frame before it asks. */
    private static void instagramFillsTheScreen(View frame) {
        frame.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    /** #618: the binder lays a story out as it binds only with Stories fill the screen on. */
    @Test
    public void theStoryBinderLaysOutTheWholeScreenOnTheStorySwitch() {
        Object holder = new Object();
        assertEquals("off to start", 0, ReelFill.storyBound(holder, 1));
        Settings.FILL_STORY_SCREEN.save(true);
        assertEquals(1, ReelFill.storyBound(holder, 0));
        assertEquals(1, ReelFill.storyBound(holder, 1));
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertEquals("doesn't need Full screen Reels", 1, ReelFill.storyBound(holder, 1));
        assertEquals(0, ReelFill.storyBound(null, 1));
    }

    /**
     * #618: a reel shared to a story had its sides cut on a tall phone. Its frame goes back to 9:16 as
     * wide as the screen and centered, and a later layout from a holder the binder never saw, like the
     * newer story viewer's, keeps it so. A story on that holder that shares no reel gets the whole screen.
     */
    @Test
    public void aReelSharedToAStoryKeepsItsWholeFrame() {
        Settings.FILL_STORY_SCREEN.save(true);
        FrameLayout room = storyRoom();
        View frame = room.getChildAt(0);
        Object holder = new Object();
        assertEquals(1, ReelFill.storyBound(holder, 1));
        instagramFillsTheScreen(frame);
        ReelFill.storyFrame(holder, frame);
        layOut(room);
        assertEquals(Gravity.CENTER, ((FrameLayout.LayoutParams) frame.getLayoutParams()).gravity);
        assertEquals(PHONE_WIDTH, frame.getWidth());
        assertEquals(1920, frame.getHeight());
        assertEquals("a band above and below", 210, frame.getTop());

        instagramFillsTheScreen(frame);
        ReelFill.storyFrame(new Object(), frame);
        layOut(room);
        assertEquals("the newer viewer's own layout", 1920, frame.getHeight());

        assertEquals(1, ReelFill.storyBound(holder, 0));
        instagramFillsTheScreen(frame);
        ReelFill.storyFrame(holder, frame);
        layOut(room);
        assertEquals("the next story shares no reel", PHONE_HEIGHT, frame.getHeight());
        ReelFill.storyFrame(new Object(), frame);
        layOut(room);
        assertEquals(PHONE_HEIGHT, frame.getHeight());
    }

    /** #618: ordinary stories, the switch off and anything that isn't a view keep Instagram's frame. */
    @Test
    public void otherStoriesKeepTheWholeScreen() {
        FrameLayout room = storyRoom();
        View frame = room.getChildAt(0);
        Object holder = new Object();
        assertEquals("off", 0, ReelFill.storyBound(holder, 1));
        ReelFill.storyFrame(holder, frame);
        layOut(room);
        assertEquals(PHONE_HEIGHT, frame.getHeight());

        Settings.FILL_STORY_SCREEN.save(true);
        Object ordinary = new Object();
        ReelFill.storyBound(ordinary, 0);
        ReelFill.storyFrame(ordinary, frame);
        ReelFill.storyFrame(null, frame);
        ReelFill.storyFrame(holder, "not a view");
        ReelFill.storyFrame(holder, null);
        layOut(room);
        assertEquals(PHONE_HEIGHT, frame.getHeight());
        assertEquals(PHONE_WIDTH, frame.getWidth());
    }

    /** #618: on its first bind the viewer isn't laid out yet, and the screen's size stands in for it. */
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    public void aFrameBoundBeforeLayoutGoesByTheScreen() {
        DisplayMetrics screen = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics();
        assertEquals(PHONE_WIDTH, screen.widthPixels);
        assertEquals(PHONE_HEIGHT, screen.heightPixels);
        Settings.FILL_STORY_SCREEN.save(true);
        FrameLayout room = new FrameLayout(RuntimeEnvironment.getApplication());
        View frame = new FrameLayout(room.getContext());
        room.addView(frame, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        Object holder = new Object();
        ReelFill.storyBound(holder, 1);
        ReelFill.storyFrame(holder, frame);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) frame.getLayoutParams();
        assertEquals(MATCH_PARENT, params.width);
        assertEquals(1920, params.height);
        assertEquals(Gravity.CENTER, params.gravity);
    }

    /** #618: a 9:16 frame takes a tall room's width and a wide room's height. */
    @Test
    public void theWholeFrameFitsTheRoom() {
        assertArrayEquals(new int[] {MATCH_PARENT, 1920}, ReelFill.wholeFrame(PHONE_WIDTH, PHONE_HEIGHT));
        assertArrayEquals("already 9:16", new int[] {MATCH_PARENT, 1920}, ReelFill.wholeFrame(1080, 1920));
        assertArrayEquals("a 1248x1972 cover screen", new int[] {1109, MATCH_PARENT}, ReelFill.wholeFrame(1248, 1972));
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
