/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

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
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The count-only switches on reels (#719): each takes the number under its button and leaves the
 * button, and the button switches still take their count too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelCountSwitchesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static BooleanSetting[] switches() {
        return new BooleanSetting[]{Settings.HIDE_REEL_LIKE_BUTTON, Settings.HIDE_REEL_COMMENT_BUTTON,
                Settings.HIDE_REEL_SAVE_BUTTON, Settings.HIDE_REEL_LIKE_COUNT, Settings.HIDE_REEL_COMMENT_COUNT,
                Settings.HIDE_REEL_REPOST_COUNT, Settings.HIDE_REEL_SHARE_COUNT, Settings.HIDE_REEL_SAVE_COUNT};
    }

    @Before
    public void prepare() {
        reset();
    }

    @After
    public void restore() {
        reset();
    }

    private static void reset() {
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static boolean counted(String count, int times) {
        return HookStatus.report().toString().contains(count + " " + times);
    }

    @Test
    public void everyCountSwitchStartsOff() {
        for (BooleanSetting setting : switches()) assertFalse(setting.key, setting.defaultValue);
        Object count = new Object();
        assertFalse(ReelDeclutter.hideLikeCount());
        assertFalse(ReelDeclutter.hideCommentCount());
        assertFalse(ReelDeclutter.hideRepostCount());
        assertFalse(ReelDeclutter.hideSaveCount());
        assertSame(count, ReelDeclutter.shareCount(count));
    }

    @Test
    public void eachCountSwitchTakesItsNumberAndKeepsTheButton() {
        Settings.HIDE_REEL_LIKE_COUNT.save(true);
        assertTrue(ReelDeclutter.hideLikeCount());
        assertFalse("the heart stays", ReelDeclutter.hideLikeButton());
        assertFalse(ReelDeclutter.hideCommentCount());

        Settings.HIDE_REEL_COMMENT_COUNT.save(true);
        assertTrue(ReelDeclutter.hideCommentCount());
        assertFalse("the Comment button stays", ReelDeclutter.hideCommentButton());

        Settings.HIDE_REEL_SAVE_COUNT.save(true);
        assertTrue(ReelDeclutter.hideSaveCount());
        assertFalse("the Save button stays", ReelDeclutter.hideSaveButton());

        assertFalse(ReelDeclutter.hideRepostCount());
        Settings.HIDE_REEL_REPOST_COUNT.save(true);
        assertTrue(ReelDeclutter.hideRepostCount());

        Object count = new Object();
        assertSame(count, ReelDeclutter.shareCount(count));
        Settings.HIDE_REEL_SHARE_COUNT.save(true);
        assertNull(ReelDeclutter.shareCount(count));
        assertNull("nothing stays nothing", ReelDeclutter.shareCount(null));

        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_LIKE_COUNT_HIDDEN, 1));
        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_REPOST_COUNT_HIDDEN, 1));
        assertTrue(HookStatus.report().toString(), counted(ReelDeclutter.COUNT_SHARE_COUNT_HIDDEN, 1));
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains(ReelDeclutter.COUNT_LIKE_HIDDEN + " "));
    }

    @Test
    public void theButtonSwitchesStillTakeTheirCount() {
        Settings.HIDE_REEL_LIKE_BUTTON.save(true);
        Settings.HIDE_REEL_COMMENT_BUTTON.save(true);
        Settings.HIDE_REEL_SAVE_BUTTON.save(true);
        assertTrue(ReelDeclutter.hideLikeCount());
        assertTrue(ReelDeclutter.hideCommentCount());
        assertTrue(ReelDeclutter.hideSaveCount());
        assertFalse("repost has no button switch here", ReelDeclutter.hideRepostCount());
    }

    @Test
    public void pausedAndUnreadyKeepEveryCount() {
        for (BooleanSetting setting : switches()) setting.save(true);
        Object count = new Object();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ReelDeclutter.hideLikeCount());
        assertFalse("paused", ReelDeclutter.hideRepostCount());
        assertSame("paused", count, ReelDeclutter.shareCount(count));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("unready", ReelDeclutter.hideRepostCount());
            assertSame("unready", count, ReelDeclutter.shareCount(count));
        });
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains(ReelDeclutter.COUNT_SHARE_COUNT_HIDDEN));
        assertNull("and on again once resumed and ready", ReelDeclutter.shareCount(count));
    }
}
