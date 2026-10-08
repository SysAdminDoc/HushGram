/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Keep in chat tells Instagram a photo or video message's view mode is, and when it leaves it alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class KeepInChatTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_IN_CHAT.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_IN_CHAT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnViewOnceAndReplayableStayInChat() {
        assertEquals("permanent", KeepInChat.viewMode("once"));
        assertEquals("permanent", KeepInChat.viewMode("replayable"));
        assertTrue(HookStatus.missing(FamilyNames.KEEP_IN_CHAT).toString(), HookStatus.missing(FamilyNames.KEEP_IN_CHAT).isEmpty());

        assertEquals("permanent", KeepInChat.viewMode("permanent"));
        assertEquals("a mode Instagram adds later", "later", KeepInChat.viewMode("later"));
        assertNull(KeepInChat.viewMode(null));
    }

    @Test
    public void offPausedUnreadyAndThrowingLeaveItToInstagram() {
        Settings.KEEP_IN_CHAT.resetToDefault();
        assertFalse(Settings.KEEP_IN_CHAT.get());
        assertEquals("once", KeepInChat.viewMode("once"));
        Settings.KEEP_IN_CHAT.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("replayable", KeepInChat.viewMode("replayable"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals("once", KeepInChat.viewMode("once")));
        assertEquals("permanent", KeepInChat.viewMode("once"));

        assertEquals("once", KeepInChat.viewMode("once", THROWS));
        String missing = HookStatus.missing(FamilyNames.KEEP_IN_CHAT).toString();
        assertTrue(missing, missing.contains("'" + KeepInChat.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
