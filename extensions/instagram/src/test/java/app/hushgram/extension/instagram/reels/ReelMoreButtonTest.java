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

import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the More button on reels (#647): the hook the patch puts first in the clips UFI's two builders
 * of the More (three dots) button answers yes only with its own switch on, counts each button it
 * takes off, and leaves the other buttons of the column to their own switches.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelMoreButtonTest {
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
        Settings.HIDE_REEL_MORE_BUTTON.resetToDefault();
        Settings.HIDE_REEL_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_REEL_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_REEL_SAVE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static boolean countedMore() {
        return HookStatus.report().toString().contains(ReelDeclutter.COUNT_MORE_HIDDEN);
    }

    @Test
    public void theSwitchTakesTheMoreButtonAndNothingElse() {
        assertFalse("starts off", Settings.HIDE_REEL_MORE_BUTTON.defaultValue);
        Settings.HIDE_REEL_MORE_BUTTON.save(true);

        assertTrue(ReelDeclutter.hideMoreButton());
        assertTrue("a paged card's More too", ReelDeclutter.hideMoreButton());
        assertFalse("the heart stays", ReelDeclutter.hideLikeButton());
        assertFalse("the Comment button stays", ReelDeclutter.hideCommentButton());
        assertFalse("the Save button stays", ReelDeclutter.hideSaveButton());
        assertFalse("the repost count stays", ReelDeclutter.hideRepostCount());

        assertTrue(HookStatus.report().toString(),
                HookStatus.report().toString().contains(ReelDeclutter.COUNT_MORE_HIDDEN + " 2"));
    }

    /** The other buttons' switches leave the More button where it is. */
    @Test
    public void theOtherButtonsSwitchesKeepTheMoreButton() {
        Settings.HIDE_REEL_LIKE_BUTTON.save(true);
        Settings.HIDE_REEL_COMMENT_BUTTON.save(true);
        Settings.HIDE_REEL_SAVE_BUTTON.save(true);

        assertFalse(ReelDeclutter.hideMoreButton());
        assertFalse(countedMore());
    }

    /** Off to start, paused and before the settings are read, Instagram builds the button and nothing is counted. */
    @Test
    public void offPausedAndUnreadyKeepTheButtonAndCountNothing() {
        assertFalse("off to start", ReelDeclutter.hideMoreButton());
        assertFalse("off counts nothing", countedMore());

        Settings.HIDE_REEL_MORE_BUTTON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ReelDeclutter.hideMoreButton());
        assertFalse("paused counts nothing", countedMore());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("unready", ReelDeclutter.hideMoreButton()));
        assertFalse("unready counts nothing", countedMore());
    }

    /** The switch belongs to Clean up Reels, so the patch's status and its switch list name it. */
    @Test
    public void theSwitchIsCleanUpReels() {
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.HIDE_REEL_MORE_BUTTON));
    }
}
