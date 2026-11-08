/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** Which parts of the Reels viewer each of the three switches leaves out. */
@RunWith(RobolectricTestRunner.class)
public class ReelDeclutterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
        Settings.HIDE_REEL_CHIPS.save(true);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(true);
    }

    @After
    public void switchBack() {
        Settings.HIDE_REEL_FOLLOW_BUTTON.resetToDefault();
        Settings.HIDE_REEL_CHIPS.resetToDefault();
        Settings.HIDE_REEL_SOCIAL_FOOTER.resetToDefault();
    }

    @Test
    public void withTheSwitchesOnEveryPartIsHidden() {
        assertTrue(ReelDeclutter.hideFollowButton());
        assertTrue(ReelDeclutter.hideChips());
        assertTrue(ReelDeclutter.hideSocialFooter());
        assertTrue(ReelDeclutter.hideSocialContext(LineType.FOLLOWED_BY));
    }

    /** The search, shopping and poll switches (#332) each answer for their own render. */
    @Test
    public void theSearchShoppingAndPollSwitchesAreSeparate() {
        assertFalse(ReelDeclutter.hideSearchPill());
        assertFalse(ReelDeclutter.hideProductPills());
        assertFalse(ReelDeclutter.hidePollPills());
        Settings.HIDE_REEL_PRODUCT_PILLS.save(true);
        try {
            assertTrue(ReelDeclutter.hideProductPills());
            assertFalse(ReelDeclutter.hideSearchPill());
            assertFalse(ReelDeclutter.hidePollPills());
            assertTrue(ReelDeclutter.hideChips());
        } finally {
            Settings.HIDE_REEL_PRODUCT_PILLS.resetToDefault();
        }
        Settings.HIDE_REEL_SEARCH_PILL.save(true);
        Settings.HIDE_REEL_POLL_PILLS.save(true);
        try {
            assertTrue(ReelDeclutter.hideSearchPill());
            assertTrue(ReelDeclutter.hidePollPills());
            assertFalse(ReelDeclutter.hideProductPills());
        } finally {
            Settings.HIDE_REEL_SEARCH_PILL.resetToDefault();
            Settings.HIDE_REEL_POLL_PILLS.resetToDefault();
        }
    }

    /** Each switch answers for its own parts only. */
    @Test
    public void eachSwitchTurnsOffOnlyItsOwnParts() {
        Settings.HIDE_REEL_CHIPS.save(false);
        try {
            assertFalse(ReelDeclutter.hideChips());
            assertTrue(ReelDeclutter.hideFollowButton());
            assertTrue(ReelDeclutter.hideSocialFooter());
            assertTrue(ReelDeclutter.hideSocialContext(LineType.LIKED_BY));
        } finally {
            Settings.HIDE_REEL_CHIPS.save(true);
        }
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        try {
            assertFalse(ReelDeclutter.hideFollowButton());
            assertFalse(ReelDeclutter.hideSocialFooter());
            assertTrue(ReelDeclutter.hideChips());
        } finally {
            Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
            Settings.HIDE_REEL_SOCIAL_FOOTER.save(true);
        }
    }

    /** Named like the enum Instagram 449 hands its social context check, a few of its types. */
    private enum LineType { FOLLOWED_BY, LIKED_BY, VIEWED_BY_FRIENDS, FOLLOWER_COUNT, SELLER_RNR, EFFECT_USED_BY_PEOPLE }

    /** Lines about people you know go; a follower count, a rating or what strangers did stays. */
    @Test
    public void onlyFriendsActivityLinesAreLeftOut() {
        assertTrue(ReelDeclutter.hideSocialContext(LineType.FOLLOWED_BY));
        assertTrue(ReelDeclutter.hideSocialContext(LineType.LIKED_BY));
        assertTrue(ReelDeclutter.hideSocialContext(LineType.VIEWED_BY_FRIENDS));
        assertFalse(ReelDeclutter.hideSocialContext(LineType.FOLLOWER_COUNT));
        assertFalse(ReelDeclutter.hideSocialContext(LineType.SELLER_RNR));
        assertFalse(ReelDeclutter.hideSocialContext(LineType.EFFECT_USED_BY_PEOPLE));
    }

    @Test
    public void withItsSwitchOffEveryLineStays() {
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        try {
            assertFalse(ReelDeclutter.hideSocialContext(LineType.FOLLOWED_BY));
            assertFalse(ReelDeclutter.hideSocialContext(LineType.LIKED_BY));
        } finally {
            Settings.HIDE_REEL_SOCIAL_FOOTER.save(true);
        }
    }

    /** Named like ClipsViewerSource, where Instagram 450's reel viewer was opened from. */
    private enum Source { REPOSTS_GRID, SELF_REPOSTS_GRID, PROFILE_CLIPS, CLIPS_TAB }

    /**
     * The comment bar starts on screen. With its switch on it goes from reposted reels only, yours
     * or someone else's, and anything but the source enum leaves it.
     */
    @Test
    public void theCommentBarGoesOnlyFromRepostedReelsWithItsSwitchOn() {
        assertFalse("off to start", ReelDeclutter.hideCommentBar(Source.REPOSTS_GRID));
        Settings.HIDE_REEL_COMMENT_BAR.save(true);
        try {
            assertTrue(ReelDeclutter.hideCommentBar(Source.REPOSTS_GRID));
            assertTrue(ReelDeclutter.hideCommentBar(Source.SELF_REPOSTS_GRID));
            assertFalse(ReelDeclutter.hideCommentBar(Source.PROFILE_CLIPS));
            assertFalse(ReelDeclutter.hideCommentBar(Source.CLIPS_TAB));
            assertFalse(ReelDeclutter.hideCommentBar("REPOSTS_GRID"));
            assertFalse(ReelDeclutter.hideCommentBar(null));
            assertTrue("the other parts keep their own switches", ReelDeclutter.hideFollowButton());
            Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
            assertTrue(ReelDeclutter.hideCommentBar(Source.REPOSTS_GRID));
        } finally {
            Settings.HIDE_REEL_COMMENT_BAR.save(false);
            Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
        }
    }

    /** The switch for all reels takes the bar off every source and the in-viewer bar, and only with itself on. */
    @Test
    public void theCommentBarGoesFromEveryReelWithTheAllReelsSwitchOn() {
        assertFalse("off to start", ReelDeclutter.hideEveryCommentBar());
        assertFalse(ReelDeclutter.hideCommentBar(Source.CLIPS_TAB));
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        try {
            assertTrue(ReelDeclutter.hideEveryCommentBar());
            assertTrue(ReelDeclutter.hideCommentBar(Source.CLIPS_TAB));
            assertTrue(ReelDeclutter.hideCommentBar(Source.PROFILE_CLIPS));
        } finally {
            Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(false);
        }
        assertFalse(ReelDeclutter.hideCommentBar(Source.CLIPS_TAB));
    }

    /**
     * #523: a reel opened from the feed dropped down into the room of the bar the switch hid. With
     * Keep the comment bar's space on, a bar a switch hid goes back to invisible as Instagram's hide
     * returns. A shown bar, a missing one and a bar no switch hid stay as they are.
     */
    @Test
    public void keepingTheSpaceLeavesAHiddenBarInvisible() {
        assertFalse("its own switch needs a bar switch on", Settings.KEEP_REEL_COMMENT_BAR_SPACE.isAvailable());
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        try {
            assertTrue(Settings.KEEP_REEL_COMMENT_BAR_SPACE.isAvailable());
            View bar = bar(View.GONE);
            ReelDeclutter.keepCommentBarSpace(bar, Source.PROFILE_CLIPS);
            assertEquals("off to start, the room goes", View.GONE, bar.getVisibility());

            Settings.KEEP_REEL_COMMENT_BAR_SPACE.save(true);
            ReelDeclutter.keepCommentBarSpace(bar, Source.PROFILE_CLIPS);
            assertEquals(View.INVISIBLE, bar.getVisibility());
            View shown = bar(View.VISIBLE);
            ReelDeclutter.keepCommentBarSpace(shown, Source.PROFILE_CLIPS);
            assertEquals("a shown bar stays shown", View.VISIBLE, shown.getVisibility());
            ReelDeclutter.keepCommentBarSpace(null, Source.PROFILE_CLIPS);

            Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(false);
            Settings.HIDE_REEL_COMMENT_BAR.save(true);
            assertTrue(Settings.KEEP_REEL_COMMENT_BAR_SPACE.isAvailable());
            View profile = bar(View.GONE);
            ReelDeclutter.keepCommentBarSpace(profile, Source.PROFILE_CLIPS);
            assertEquals("Instagram's own hide of a bar no switch covers", View.GONE, profile.getVisibility());
            View repost = bar(View.GONE);
            ReelDeclutter.keepCommentBarSpace(repost, Source.REPOSTS_GRID);
            assertEquals(View.INVISIBLE, repost.getVisibility());

            Settings.KEEP_REEL_COMMENT_BAR_SPACE.save(false);
            View off = bar(View.GONE);
            ReelDeclutter.keepCommentBarSpace(off, Source.REPOSTS_GRID);
            assertEquals(View.GONE, off.getVisibility());
        } finally {
            Settings.KEEP_REEL_COMMENT_BAR_SPACE.resetToDefault();
            Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.resetToDefault();
            Settings.HIDE_REEL_COMMENT_BAR.resetToDefault();
        }
    }

    private static View bar(int visibility) {
        View bar = new View(RuntimeEnvironment.getApplication());
        bar.setVisibility(visibility);
        return bar;
    }

    /** Something that isn't a type, or a type's name as a string, is never taken for one. */
    @Test
    public void anythingButTheTypeStays() {
        assertFalse(ReelDeclutter.hideSocialContext(null));
        assertFalse(ReelDeclutter.hideSocialContext("FOLLOWED_BY"));
        assertFalse(ReelDeclutter.hideSocialContext(new Object()));
    }
}
