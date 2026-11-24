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

/** When Hide AI labels on comments leaves "AI-generated profile" off a comment's name line (#787). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentAiLabelTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENT_AI_LABELS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_COMMENT_AI_LABELS.resetToDefault();
        Settings.HIDE_COMMENT_LIKES.resetToDefault();
        Settings.HIDE_COMMENT_REPLY.resetToDefault();
        Settings.HIDE_COMMENTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnALabeledCommentLeavesTheLabelOut() {
        assertFalse(CommentAiLabel.shown(1));
        assertFalse(CommentAiLabel.shown(1));
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 2, 0 found, 0 missing. Counted: "
                + CommentAiLabel.COUNT_HIDDEN + " 2"), HookStatus.report());
    }

    /** A comment without a label stays without one, and isn't counted as a label taken off. */
    @Test
    public void aCommentWithoutALabelGetsNone() {
        assertFalse(CommentAiLabel.shown(0));
        Settings.HIDE_COMMENT_AI_LABELS.resetToDefault();
        assertFalse(CommentAiLabel.shown(0));
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** The label has its own switch: the other comment switches leave it where it is. */
    @Test
    public void theOtherCommentSwitchesKeepTheLabel() {
        Settings.HIDE_COMMENT_AI_LABELS.resetToDefault();
        assertFalse(Settings.HIDE_COMMENT_AI_LABELS.get());
        Settings.HIDE_COMMENTS.save(true);
        Settings.HIDE_COMMENT_REPLY.save(true);
        Settings.HIDE_COMMENT_LIKES.save(true);
        assertTrue(CommentAiLabel.shown(1));
        assertTrue("the heart still goes", CommentLikes.hidden());
    }

    /** Off, paused, unready or throwing, a labeled comment shows its label as Instagram has it. */
    @Test
    public void offPausedUnreadyAndThrowingKeepTheLabel() {
        Settings.HIDE_COMMENT_AI_LABELS.resetToDefault();
        assertTrue(CommentAiLabel.shown(1));
        Settings.HIDE_COMMENT_AI_LABELS.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(CommentAiLabel.shown(1));
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue(CommentAiLabel.shown(1)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertTrue(CommentAiLabel.shown(1)));
        assertFalse(CommentAiLabel.shown(1));

        assertTrue(CommentAiLabel.shown(1, THROWS));
        String missing = HookStatus.missing(FamilyNames.HIDE_COMMENTS).toString();
        assertTrue(missing, missing.contains("'" + CommentAiLabel.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
