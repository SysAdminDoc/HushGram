/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

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

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Which status Instagram's presence write request is built with. Runtime decisions only: the other
 * account not seeing Active now needs a check with two accounts on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ActiveStatusTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Meta's presence status enum, in 450's order, with constant names it keeps. */
    enum Status { OFFLINE, ACTIVE, IDLE, DISABLED }

    /** An enum with an Active and no Idle to put in its place. */
    enum NoIdle { OFFLINE, ACTIVE }

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_ACTIVE_STATUS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_ACTIVE_STATUS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnActiveIsSentAsIdle() {
        assertSame(Status.IDLE, ActiveStatus.status(Status.ACTIVE));
        assertSame("the second write finds Idle again", Status.IDLE, ActiveStatus.status(Status.ACTIVE));
        assertTrue(HookStatus.missing(FamilyNames.ACTIVE_STATUS).toString(), HookStatus.missing(FamilyNames.ACTIVE_STATUS).isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ActiveStatus.SENT_IDLE + " 2"));
    }

    /** Idle from the background and Offline or Disabled as the stream closes all go as Instagram wrote them. */
    @Test
    public void everyOtherStatusIsSentAsItIs() {
        assertSame(Status.OFFLINE, ActiveStatus.status(Status.OFFLINE));
        assertSame(Status.IDLE, ActiveStatus.status(Status.IDLE));
        assertSame(Status.DISABLED, ActiveStatus.status(Status.DISABLED));
        assertNull("the empty request Kotlin builds keeps its null", ActiveStatus.status(null));
        Object other = new Object();
        assertSame(other, ActiveStatus.status(other));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(ActiveStatus.SENT_IDLE));
    }

    @Test
    public void offToStartAndOffSendActive() {
        Settings.HIDE_ACTIVE_STATUS.resetToDefault();
        assertFalse(Settings.HIDE_ACTIVE_STATUS.defaultValue);
        assertSame(Status.ACTIVE, ActiveStatus.status(Status.ACTIVE));
        Settings.HIDE_ACTIVE_STATUS.save(false);
        assertSame(Status.ACTIVE, ActiveStatus.status(Status.ACTIVE));
    }

    @Test
    public void pausedAndUnreadySendActive() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(Status.ACTIVE, ActiveStatus.status(Status.ACTIVE));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(Status.ACTIVE, ActiveStatus.status(Status.ACTIVE)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame(Status.ACTIVE, ActiveStatus.status(Status.ACTIVE)));

        assertSame(Status.IDLE, ActiveStatus.status(Status.ACTIVE));
    }

    @Test
    public void aThrowingSwitchSendsActiveAndIsReported() {
        assertSame(Status.ACTIVE, ActiveStatus.status(Status.ACTIVE, THROWS));

        String missing = HookStatus.missing(FamilyNames.ACTIVE_STATUS).toString();
        assertTrue(missing, missing.contains("'" + ActiveStatus.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void anEnumWithoutIdleSendsActiveAndIsReported() {
        assertSame(Status.IDLE, ActiveStatus.status(Status.ACTIVE));
        assertSame(NoIdle.ACTIVE, ActiveStatus.status(NoIdle.ACTIVE));

        String missing = HookStatus.missing(FamilyNames.ACTIVE_STATUS).toString();
        assertTrue(missing, missing.contains(NoIdle.class.getName() + "#" + ActiveStatus.IDLE));
        assertSame("Idle of the other enum is still found", Status.IDLE, ActiveStatus.status(Status.ACTIVE));
    }

    /** The typing switch is a separate choice. */
    @Test
    public void theTypingSwitchIsAnIndependentChoice() {
        Settings.HIDE_ACTIVE_STATUS.save(false);
        Settings.HIDE_TYPING.save(true);
        try {
            assertSame(Status.ACTIVE, ActiveStatus.status(Status.ACTIVE));
            Settings.HIDE_ACTIVE_STATUS.save(true);
            Settings.HIDE_TYPING.save(false);
            assertSame(Status.IDLE, ActiveStatus.status(Status.ACTIVE));
            assertFalse(TypingStatus.hold(1));
        } finally {
            Settings.HIDE_TYPING.resetToDefault();
        }
    }
}
