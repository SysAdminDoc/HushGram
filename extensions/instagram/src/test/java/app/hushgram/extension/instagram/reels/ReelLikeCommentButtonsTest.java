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
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the Like button and Hide the Comment button on reels (#668): the four hooks the patch puts
 * first in the clips UFI's builders answer yes only with their own switch on, and count each part
 * they take off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelLikeCommentButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void prepare() {
        reset();
    }

    @After
    public void restore() {
        reset();
    }

    private static void reset() {
        Settings.HIDE_REEL_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_REEL_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_REEL_SAVE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static boolean counted(String count, int times) {
        return HookStatus.report().toString().contains(count + " " + times);
    }

    private static boolean countedAnything() {
        String report = HookStatus.report().toString();
        return report.contains(ReelDeclutter.COUNT_LIKE_HIDDEN) || report.contains(ReelDeclutter.COUNT_LIKE_COUNT_HIDDEN)
                || report.contains(ReelDeclutter.COUNT_COMMENT_HIDDEN) || report.contains(ReelDeclutter.COUNT_COMMENT_COUNT_HIDDEN);
    }

    @Test
    public void theLikeSwitchTakesTheHeartAndItsCountAndNothingElse() {
        assertFalse("starts off", Settings.HIDE_REEL_LIKE_BUTTON.defaultValue);
        Settings.HIDE_REEL_LIKE_BUTTON.save(true);

        assertTrue(ReelDeclutter.hideLikeButton());
        assertTrue(ReelDeclutter.hideLikeButton());
        assertTrue(ReelDeclutter.hideLikeCount());
        assertFalse("the Comment button stays", ReelDeclutter.hideCommentButton());
        assertFalse(ReelDeclutter.hideCommentCount());
        assertFalse("the Save button stays", ReelDeclutter.hideSaveButton());

        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_LIKE_HIDDEN, 2));
        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_LIKE_COUNT_HIDDEN, 1));
        assertFalse(HookStatus.report().toString().contains(ReelDeclutter.COUNT_COMMENT_HIDDEN));
    }

    @Test
    public void theCommentSwitchTakesTheButtonAndItsCountAndNothingElse() {
        assertFalse("starts off", Settings.HIDE_REEL_COMMENT_BUTTON.defaultValue);
        Settings.HIDE_REEL_COMMENT_BUTTON.save(true);

        assertTrue(ReelDeclutter.hideCommentButton());
        assertTrue(ReelDeclutter.hideCommentCount());
        assertFalse("the heart stays", ReelDeclutter.hideLikeButton());
        assertFalse(ReelDeclutter.hideLikeCount());
        assertFalse("the comment bar keeps its own switch", ReelDeclutter.hideEveryCommentBar());

        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_COMMENT_HIDDEN, 1));
        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_COMMENT_COUNT_HIDDEN, 1));
    }

    /** Off to start, paused and before the settings are read, Instagram builds every button and nothing is counted. */
    @Test
    public void offPausedAndUnreadyKeepTheButtonsAndCountNothing() {
        assertFalse("off to start", ReelDeclutter.hideLikeButton());
        assertFalse("off to start", ReelDeclutter.hideCommentCount());
        assertFalse("off counts nothing", countedAnything());

        Settings.HIDE_REEL_LIKE_BUTTON.save(true);
        Settings.HIDE_REEL_COMMENT_BUTTON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ReelDeclutter.hideLikeButton());
        assertFalse("paused", ReelDeclutter.hideLikeCount());
        assertFalse("paused", ReelDeclutter.hideCommentButton());
        assertFalse("paused", ReelDeclutter.hideCommentCount());
        assertFalse("paused counts nothing", countedAnything());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("unready", ReelDeclutter.hideLikeButton());
            assertFalse("unready", ReelDeclutter.hideCommentButton());
        });
        assertFalse("unready counts nothing", countedAnything());

        assertTrue("and on again once resumed and ready", ReelDeclutter.hideCommentButton());
        assertTrue(counted(ReelDeclutter.COUNT_COMMENT_HIDDEN, 1));
    }
}
