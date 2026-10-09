/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;

/** What the two double-tap hooks answer. */
@RunWith(RobolectricTestRunner.class)
public class DoubleTapLikeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(true);
    }

    @After
    public void tearDown() {
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.resetToDefault();
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.save(true);
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.save(true);
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS.resetToDefault();
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES.resetToDefault();
    }

    /** Messages keep their double-tap reaction until their switch is on, and then only while the main one is. */
    @Test
    public void messagesAreHeldBackOnlyWithTheirOwnSwitch() {
        assertFalse("off to start", Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES.get());
        assertFalse(DoubleTapLike.holdBackMessage());
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES.save(true);
        FeedFilterCounters.snapshotAndClear();
        assertTrue(DoubleTapLike.holdBackMessage());
        assertTrue(FeedFilterCounters.report().toString().contains(DoubleTapLike.HELD_BACK));
        assertFalse("comments keep their own switch", DoubleTapLike.holdBackComment());
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.save(false);
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.save(false);
        assertTrue("posts and reels don't matter", DoubleTapLike.holdBackMessage());
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(false);
        assertFalse(DoubleTapLike.holdBackMessage());
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse("not before settings are ready", DoubleTapLike.holdBackMessage()));
    }

    /** Comments keep double tap to like until their switch is on, and then only while the main one is. */
    @Test
    public void commentsAreHeldBackOnlyWithTheirOwnSwitch() {
        assertFalse("off to start", Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS.get());
        assertFalse(DoubleTapLike.holdBackComment());
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS.save(true);
        FeedFilterCounters.snapshotAndClear();
        assertTrue(DoubleTapLike.holdBackComment());
        assertTrue(FeedFilterCounters.report().toString().contains(DoubleTapLike.HELD_BACK));
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.save(false);
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.save(false);
        assertTrue("the other two don't matter", DoubleTapLike.holdBackComment());
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(false);
        assertFalse(DoubleTapLike.holdBackComment());
    }

    /** On, a post's double tap returns early and a reel's like action comes back empty, and both are counted. */
    @Test
    public void withTheSwitchOnBothDoubleTapsAreHeldBack() {
        FeedFilterCounters.snapshotAndClear();
        assertTrue(DoubleTapLike.holdBackPost());
        assertNull(DoubleTapLike.likeAction(new Object()));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(DoubleTapLike.ROUTE));
        assertTrue(report, report.contains(DoubleTapLike.HELD_BACK));
    }

    /** Off, the post's double tap goes on and the reel keeps its own like action. */
    @Test
    public void withTheSwitchOffADoubleTapLikes() {
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(false);
        Object action = new Object();
        assertFalse(DoubleTapLike.holdBackPost());
        assertSame(action, DoubleTapLike.likeAction(action));
    }

    /** Each of the two switches under it holds back its own double tap and leaves the other alone. */
    @Test
    public void postsAndReelsAreHeldBackApart() {
        Object action = new Object();
        for (boolean posts : new boolean[] {true, false}) {
            for (boolean reels : new boolean[] {true, false}) {
                Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.save(posts);
                Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.save(reels);
                String which = "posts " + posts + ", reels " + reels;
                assertEquals(which, posts, DoubleTapLike.holdBackPost());
                assertEquals(which, reels ? null : action, DoubleTapLike.likeAction(action));
            }
        }
    }

    /** With the switch off, neither of the two under it holds anything back. */
    @Test
    public void withTheSwitchOffNeitherOfTheTwoUnderItCounts() {
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(false);
        Object action = new Object();
        assertFalse(DoubleTapLike.holdBackPost());
        assertSame(action, DoubleTapLike.likeAction(action));
    }

    /** A viewer with no like action has nothing to hold back, so nothing is counted. */
    @Test
    public void noLikeActionStaysNone() {
        FeedFilterCounters.snapshotAndClear();
        assertNull(DoubleTapLike.likeAction(null));
        assertFalse(FeedFilterCounters.report().toString().contains(DoubleTapLike.ROUTE));
    }
}
