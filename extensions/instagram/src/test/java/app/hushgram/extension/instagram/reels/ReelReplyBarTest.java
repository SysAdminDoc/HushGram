/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

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
 * Hide the reply bar on reels from chats: the hook the patch puts after each read of the reply bar
 * controller's hidden flag answers yes only with the switch on and a viewer opened from a chat, and
 * counts each yes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelReplyBarTest {
    /** Stands in for Instagram's ClipsViewerSource, by names Instagram 450 keeps. */
    enum Source { DIRECT, DIRECT_SEARCH, DIRECT_ICEBREAKER, BLEND, PUSH_NOTIF, REPOSTS_GRID, CLIPS_TAB }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.HIDE_REEL_REPLY_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_REEL_REPLY_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static boolean counted(int times) {
        return HookStatus.report().toString().contains(ReelDeclutter.COUNT_REPLY_BAR_HIDDEN + " " + times);
    }

    private static boolean countedAnything() {
        return HookStatus.report().toString().contains(ReelDeclutter.COUNT_REPLY_BAR_HIDDEN);
    }

    @Test
    public void withTheSwitchOnOnlyAReelFromAChatLosesItsReplyBar() {
        assertFalse("starts off", Settings.HIDE_REEL_REPLY_BAR.defaultValue);
        Settings.HIDE_REEL_REPLY_BAR.save(true);

        assertTrue(ReelDeclutter.hideDirectReplyBar(Source.DIRECT));
        assertTrue(ReelDeclutter.hideDirectReplyBar(Source.DIRECT));
        for (Source other : Source.values()) {
            if (other != Source.DIRECT) assertFalse(other.name(), ReelDeclutter.hideDirectReplyBar(other));
        }

        assertTrue(HookStatus.report().toString(), counted(2));
        assertEquals(Collections.singleton("DIRECT"), ReelDeclutter.CHATS);
        assertEquals("chat reel reply bars kept hidden", ReelDeclutter.COUNT_REPLY_BAR_HIDDEN);
    }

    /** A source that isn't the enum the controller keeps is no chat, so the bar stays and nothing is counted. */
    @Test
    public void aSourceItCantReadKeepsTheBar() {
        Settings.HIDE_REEL_REPLY_BAR.save(true);

        assertFalse(ReelDeclutter.hideDirectReplyBar(null));
        assertFalse(ReelDeclutter.hideDirectReplyBar("DIRECT"));
        assertFalse(ReelDeclutter.hideDirectReplyBar(new Object()));

        assertFalse(HookStatus.report().toString(), countedAnything());
    }

    /** The switch answers for the reply bar alone, and the comment bar's switches don't hide it. */
    @Test
    public void onlyTheReplyBarSwitchTurnsItOnAndItTurnsNothingElseOn() {
        Settings.HIDE_REEL_REPLY_BAR.save(true);
        assertFalse(ReelDeclutter.hideFollowButton());
        assertFalse(ReelDeclutter.hideChips());
        assertFalse(ReelDeclutter.hideSocialFooter());
        assertFalse(ReelDeclutter.hideEveryCommentBar());
        assertFalse(ReelDeclutter.hideCommentBar(Source.DIRECT));
        assertFalse(ReelDeclutter.hideCommentBar(Source.REPOSTS_GRID));
        assertFalse(ReelDeclutter.hideSaveButton());

        Settings.HIDE_REEL_REPLY_BAR.save(false);
        Settings.HIDE_REEL_COMMENT_BAR.save(true);
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        try {
            assertTrue(ReelDeclutter.hideCommentBar(Source.DIRECT));
            assertFalse("the comment bar's switches don't hide the reply bar", ReelDeclutter.hideDirectReplyBar(Source.DIRECT));
        } finally {
            Settings.HIDE_REEL_COMMENT_BAR.resetToDefault();
            Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.resetToDefault();
        }
    }

    /** Off to start, paused and before the settings are read, the bar is Instagram's and nothing is counted. */
    @Test
    public void offPausedAndUnreadyKeepTheBarAndCountNothing() {
        assertFalse("off to start", ReelDeclutter.hideDirectReplyBar(Source.DIRECT));
        assertFalse("off counts nothing", countedAnything());

        Settings.HIDE_REEL_REPLY_BAR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ReelDeclutter.hideDirectReplyBar(Source.DIRECT));
        assertFalse("paused counts nothing", countedAnything());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("unready", ReelDeclutter.hideDirectReplyBar(Source.DIRECT)));
        assertFalse("unready counts nothing", countedAnything());

        assertTrue("and on again once resumed and ready", ReelDeclutter.hideDirectReplyBar(Source.DIRECT));
        assertTrue(counted(1));

        Settings.HIDE_REEL_REPLY_BAR.save(false);
        assertFalse("turned off, the next show is Instagram's own", ReelDeclutter.hideDirectReplyBar(Source.DIRECT));
        assertTrue("and nothing more is counted", counted(1));
    }
}
