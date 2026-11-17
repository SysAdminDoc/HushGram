/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
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
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the Messages badge (#734): with the switch on the unread count the tab bar's badge gets is 0, so
 * Instagram's own "nothing unread" branch hides the badge. Off, paused, or no count, the number goes
 * through as Instagram sent it.
 */
@RunWith(RobolectricTestRunner.class)
public class MessagesBadgeTest {
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
        Settings.HIDE_MESSAGES_BADGE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test
    public void theSwitchStartsOffTakesARestartAndTheCountGoesThrough() {
        assertEquals(Boolean.FALSE, Settings.HIDE_MESSAGES_BADGE.defaultValue);
        assertTrue("Instagram reads the count again only on a new session", Settings.HIDE_MESSAGES_BADGE.rebootApp);
        assertEquals(7, MessagesBadge.count(7));
        assertEquals(1, MessagesBadge.count(1));
        assertFalse(report().contains(MessagesBadge.COUNT_HIDDEN));
    }

    @Test
    public void onTheCountIsZeroAndOffItComesBack() {
        Settings.HIDE_MESSAGES_BADGE.save(true);
        assertEquals(0, MessagesBadge.count(12));
        assertEquals(0, MessagesBadge.count(1));
        assertTrue(report(), report().contains(MessagesBadge.COUNT_HIDDEN));

        Settings.HIDE_MESSAGES_BADGE.save(false);
        assertEquals(12, MessagesBadge.count(12));
    }

    @Test
    public void noUnreadCountIsLeftAsInstagramSentIt() {
        Settings.HIDE_MESSAGES_BADGE.save(true);
        assertEquals(0, MessagesBadge.count(0));
        assertEquals(-1, MessagesBadge.count(-1));
        assertFalse("nothing was hidden", report().contains(MessagesBadge.COUNT_HIDDEN));
    }

    @Test
    public void pausedTheBadgeStays() {
        Settings.HIDE_MESSAGES_BADGE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(5, MessagesBadge.count(5));
        assertFalse(report().contains(MessagesBadge.COUNT_HIDDEN));
    }
}
