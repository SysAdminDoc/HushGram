/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertFalse;
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

/**
 * When your typing indicator is kept back, and when Instagram sends it. Runtime decisions only: the
 * other person not seeing the dots needs a check with two accounts on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class TypingStatusTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int TYPING = 1;
    private static final int STOPPED = 0;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_TYPING.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_TYPING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTypingIsKeptBack() {
        assertTrue(TypingStatus.hold(TYPING));
        assertTrue("any non-zero flag is typing", TypingStatus.hold(0x100));
        assertTrue(HookStatus.missing(FamilyNames.TYPING).toString(), HookStatus.missing(FamilyNames.TYPING).isEmpty());
    }

    /** A stop always reaches Instagram, which then forgets the chat it last reported. */
    @Test
    public void aStopAlwaysRunsInstagramsCode() {
        assertFalse(TypingStatus.hold(STOPPED));
        Settings.HIDE_TYPING.save(false);
        assertFalse(TypingStatus.hold(STOPPED));
        assertFalse(TypingStatus.hold(STOPPED, THROWS));
    }

    @Test
    public void offToStartAndOffSendTheIndicator() {
        Settings.HIDE_TYPING.resetToDefault();
        assertFalse(Settings.HIDE_TYPING.defaultValue);
        assertFalse(TypingStatus.hold(TYPING));
        Settings.HIDE_TYPING.save(false);
        assertFalse(TypingStatus.hold(TYPING));
    }

    @Test
    public void pausedAndUnreadySendTheIndicator() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(TypingStatus.hold(TYPING));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(TypingStatus.hold(TYPING)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(TypingStatus.hold(TYPING)));

        assertTrue(TypingStatus.hold(TYPING));
    }

    @Test
    public void aThrowingSwitchSendsTheIndicatorAndIsReported() {
        assertFalse(TypingStatus.hold(TYPING, THROWS));

        String missing = HookStatus.missing(FamilyNames.TYPING).toString();
        assertTrue(missing, missing.contains("'" + TypingStatus.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** The seen receipt switch is a separate choice. */
    @Test
    public void theSeenReceiptSwitchIsAnIndependentChoice() {
        Settings.HIDE_TYPING.save(false);
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        try {
            assertFalse(TypingStatus.hold(TYPING));
            Settings.HIDE_TYPING.save(true);
            Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
            assertTrue(TypingStatus.hold(TYPING));
            assertFalse(ThreadSeen.hold(null, null));
        } finally {
            Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        }
    }
}
