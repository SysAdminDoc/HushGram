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

/** When Hide Reply on comments leaves Reply out of a comment's line (#619). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentReplyTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENT_REPLY.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_COMMENT_REPLY.resetToDefault();
        Settings.HIDE_COMMENTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnEachCommentLeavesReplyOut() {
        assertTrue(CommentReply.hidden());
        assertTrue(CommentReply.hidden());
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 2, 0 found, 0 missing. Counted: "
                + CommentReply.COUNT_HIDDEN + " 2"), HookStatus.report());
    }

    /** Reply has its own switch: Hide comments on its own leaves Reply where it is. */
    @Test
    public void hideCommentsAloneKeepsReply() {
        Settings.HIDE_COMMENT_REPLY.resetToDefault();
        assertFalse(Settings.HIDE_COMMENT_REPLY.get());
        Settings.HIDE_COMMENTS.save(true);
        assertFalse(CommentReply.hidden());
    }

    /** Off, paused, unready or throwing, Reply is drawn as Instagram has it. */
    @Test
    public void offPausedUnreadyAndThrowingKeepReply() {
        Settings.HIDE_COMMENT_REPLY.resetToDefault();
        assertFalse(CommentReply.hidden());
        Settings.HIDE_COMMENT_REPLY.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(CommentReply.hidden());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(CommentReply.hidden()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(CommentReply.hidden()));
        assertTrue(CommentReply.hidden());

        assertFalse(CommentReply.hidden(THROWS));
        String missing = HookStatus.missing(FamilyNames.HIDE_COMMENTS).toString();
        assertTrue(missing, missing.contains("'" + CommentReply.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
