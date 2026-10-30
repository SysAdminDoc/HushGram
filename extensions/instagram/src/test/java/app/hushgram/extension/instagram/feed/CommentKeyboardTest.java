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

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When the comment sheet keeps the keyboard down as it opens, and that it brings it up whenever it can't tell. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentKeyboardTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.COMMENT_KEYBOARD_DOWN.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.COMMENT_KEYBOARD_DOWN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start, the keyboard comes up as Instagram planned. */
    @Test
    public void offToStartBringsTheKeyboardUp() {
        assertEquals(Boolean.FALSE, Settings.COMMENT_KEYBOARD_DOWN.defaultValue);
        assertFalse(CommentKeyboard.keepsKeyboardDown());
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.COMMENT_KEYBOARD));
    }

    /** On, each sheet that opens keeps the keyboard down, and the count says so. */
    @Test
    public void onKeepsItDownEachTime() {
        Settings.COMMENT_KEYBOARD_DOWN.save(true);
        assertTrue(CommentKeyboard.keepsKeyboardDown());
        assertTrue(CommentKeyboard.keepsKeyboardDown());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(CommentKeyboard.COUNT_KEPT_DOWN));
    }

    /** Paused, or asked before the settings are read, the keyboard comes up. */
    @Test
    public void pausedAndUnreadyBringItUp() {
        Settings.COMMENT_KEYBOARD_DOWN.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(CommentKeyboard.keepsKeyboardDown());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(CommentKeyboard.keepsKeyboardDown()));

        assertTrue(CommentKeyboard.keepsKeyboardDown());
    }

    /** A switch that throws brings the keyboard up and says the hook threw. */
    @Test
    public void aThrowingSwitchBringsItUpAndIsReported() {
        assertFalse(CommentKeyboard.keepsKeyboardDown(() -> {
            throw new IllegalStateException("settings went away");
        }));
        String missing = HookStatus.missing(FamilyNames.COMMENT_KEYBOARD).toString();
        assertTrue(missing, missing.contains("'" + CommentKeyboard.HOOK + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
