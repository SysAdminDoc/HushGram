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

    /** Something that isn't a type, or a type's name as a string, is never taken for one. */
    @Test
    public void anythingButTheTypeStays() {
        assertFalse(ReelDeclutter.hideSocialContext(null));
        assertFalse(ReelDeclutter.hideSocialContext("FOLLOWED_BY"));
        assertFalse(ReelDeclutter.hideSocialContext(new Object()));
    }
}
