/* Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.FailingStore;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SettingsLockPersistenceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final SettingsLockTest.Phone phone = new SettingsLockTest.Phone();

    @Before public void prepare() {
        Settings.SETTINGS_LOCK_HOURS.save(0);
        Settings.SETTINGS_LOCK_ASKED.save("");
        SettingsLock.clocks = phone;
    }

    @After public void restore() {
        SettingsLock.clocks = SettingsLock.PHONE;
        Settings.SETTINGS_LOCK_HOURS.save(0);
        Settings.SETTINGS_LOCK_ASKED.save("");
    }

    @Test public void failedLockNeverClaimsThatProtectionWasSaved() {
        for (FailingStore.Fault fault : failures()) {
            try (FailingStore ignored = FailingStore.install(fault)) {
                assertFalse("reported a lock after " + fault, SettingsLock.lock(6));
            }
            assertEquals(SettingsLock.State.OFF, SettingsLock.state());
        }
        assertTrue(SettingsLock.lock(6));
        assertEquals(SettingsLock.State.LOCKED, SettingsLock.state());
    }

    @Test public void failedAskAndCancelWaitKeepTheirWholeState() {
        assertTrue(SettingsLock.lock(1));
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.LOST)) {
            assertFalse(SettingsLock.ask());
        }
        assertEquals(SettingsLock.State.LOCKED, SettingsLock.state());
        assertTrue(SettingsLock.ask());
        String asked = Settings.SETTINGS_LOCK_ASKED.savedValue();
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_FALSE)) {
            assertFalse(SettingsLock.stopWait());
        }
        assertEquals(asked, Settings.SETTINGS_LOCK_ASKED.savedValue());
        assertEquals(SettingsLock.State.WAITING, SettingsLock.state());
        assertTrue(SettingsLock.stopWait());
        assertEquals(SettingsLock.State.LOCKED, SettingsLock.state());
    }

    @Test public void aFailedNewWaitWhileOpenCannotClearItsExistingAsk() {
        open();
        String asked = Settings.SETTINGS_LOCK_ASKED.savedValue();
        for (FailingStore.Fault fault : failures()) {
            try (FailingStore ignored = FailingStore.install(fault)) {
                assertFalse(SettingsLock.lock(24));
            }
            assertEquals(Integer.valueOf(1), Settings.SETTINGS_LOCK_HOURS.savedValue());
            assertEquals(asked, Settings.SETTINGS_LOCK_ASKED.savedValue());
            assertEquals(SettingsLock.State.OPEN, SettingsLock.state());
        }
        assertTrue(SettingsLock.lock(24));
        assertEquals(SettingsLock.State.LOCKED, SettingsLock.state());
    }

    @Test public void aFailedUnlockStaysOpenAndCanBeRetried() {
        open();
        String asked = Settings.SETTINGS_LOCK_ASKED.savedValue();
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING)) {
            assertFalse(SettingsLock.off());
        }
        assertEquals(Integer.valueOf(1), Settings.SETTINGS_LOCK_HOURS.savedValue());
        assertEquals(asked, Settings.SETTINGS_LOCK_ASKED.savedValue());
        assertEquals(SettingsLock.State.OPEN, SettingsLock.state());
        assertTrue(SettingsLock.off());
        assertEquals(SettingsLock.State.OFF, SettingsLock.state());
    }

    private void open() {
        assertTrue(SettingsLock.lock(1));
        assertTrue(SettingsLock.ask());
        phone.pass(3_600_000L);
        assertEquals(SettingsLock.State.OPEN, SettingsLock.state());
    }

    private static FailingStore.Fault[] failures() {
        return new FailingStore.Fault[]{FailingStore.Fault.LOST, FailingStore.Fault.COMMIT_FALSE,
                FailingStore.Fault.COMMIT_THROWS, FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING,
                FailingStore.Fault.EDIT_THROWS, FailingStore.Fault.STAGE_THROWS};
    }
}
