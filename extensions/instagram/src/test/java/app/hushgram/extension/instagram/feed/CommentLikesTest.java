/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When Hide likes on comments leaves the heart and its count out of a comment's row (#762). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentLikesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENT_LIKES.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_COMMENT_LIKES.resetToDefault();
        Settings.HIDE_COMMENT_REPLY.resetToDefault();
        Settings.HIDE_COMMENTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnEachCommentLeavesTheHeartOut() {
        assertTrue(CommentLikes.hidden());
        assertTrue(CommentLikes.hidden());
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 2, 0 found, 0 missing. Counted: "
                + CommentLikes.COUNT_HIDDEN + " 2"), HookStatus.report());
    }

    /** The heart has its own switch: Hide comments or Hide Reply on their own leave it where it is. */
    @Test
    public void theOtherCommentSwitchesKeepTheHeart() {
        Settings.HIDE_COMMENT_LIKES.resetToDefault();
        assertFalse(Settings.HIDE_COMMENT_LIKES.get());
        Settings.HIDE_COMMENTS.save(true);
        Settings.HIDE_COMMENT_REPLY.save(true);
        assertFalse(CommentLikes.hidden());
        assertTrue("Reply still goes", CommentReply.hidden());
    }

    /** Off, paused, unready or throwing, the heart is drawn as Instagram has it. */
    @Test
    public void offPausedUnreadyAndThrowingKeepTheHeart() {
        Settings.HIDE_COMMENT_LIKES.resetToDefault();
        assertFalse(CommentLikes.hidden());
        Settings.HIDE_COMMENT_LIKES.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(CommentLikes.hidden());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(CommentLikes.hidden()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(CommentLikes.hidden()));
        assertTrue(CommentLikes.hidden());

        assertFalse(CommentLikes.hidden(THROWS));
        String missing = HookStatus.missing(FamilyNames.HIDE_COMMENTS).toString();
        assertTrue(missing, missing.contains("'" + CommentLikes.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
