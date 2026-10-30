/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertArrayEquals;
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

import app.hushgram.extension.instagram.settings.SettingsLock.State;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Lock these settings (#301): the wait opens the page after its hours and not before, changing the
 * time of day doesn't move it, a restart falls back on the time of day, and the open time ends.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class SettingsLockTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private final Phone phone = new Phone();

    /** Clocks a test moves by hand. */
    static final class Phone implements SettingsLock.Clocks {
        long wall = 1_800_000_000_000L;
        long elapsed = 5 * HOUR;
        int starts = 12;

        @Override public long wall() {
            return wall;
        }

        @Override public long elapsed() {
            return elapsed;
        }

        @Override public int starts() {
            return starts;
        }

        void pass(long ms) {
            wall += ms;
            elapsed += ms;
        }

        /** The phone restarts: the clock since the start begins again and the count goes up. */
        void restart(long downFor) {
            wall += downFor;
            elapsed = 30_000L;
            starts++;
        }
    }

    @Before public void prepare() {
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsLock.clocks = phone;
    }

    @After public void restore() {
        SettingsLock.clocks = SettingsLock.PHONE;
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test public void itStartsOffAndLocksWithAWait() {
        assertEquals(State.OFF, SettingsLock.state());
        assertFalse(SettingsLock.locked());
        assertFalse("nothing to ask for while off", SettingsLock.ask());
        assertFalse("no wait, no lock", SettingsLock.lock(0));
        assertTrue(SettingsLock.lock(6));
        assertEquals(State.LOCKED, SettingsLock.state());
        assertTrue(SettingsLock.locked());
        assertEquals(6, SettingsLock.hours());
        assertEquals(0, SettingsLock.left());
    }

    @Test public void askToUnlockOpensAfterItsHoursAndNotBeforeThenLocksAgain() {
        SettingsLock.lock(1);
        phone.pass(3 * HOUR);
        assertEquals("time before the ask doesn't count", State.LOCKED, SettingsLock.state());
        assertTrue(SettingsLock.ask());
        assertEquals(State.WAITING, SettingsLock.state());
        assertTrue(SettingsLock.locked());
        assertEquals(HOUR, SettingsLock.left());
        phone.pass(HOUR - 1);
        assertEquals(State.WAITING, SettingsLock.state());
        assertEquals(1, SettingsLock.left());
        phone.pass(1);
        assertEquals(State.OPEN, SettingsLock.state());
        assertFalse(SettingsLock.locked());
        assertEquals(SettingsLock.OPEN_MS, SettingsLock.left());
        phone.pass(SettingsLock.OPEN_MS - 1);
        assertEquals(State.OPEN, SettingsLock.state());
        phone.pass(1);
        assertEquals(State.LOCKED, SettingsLock.state());
        assertTrue(SettingsLock.locked());
        SettingsLock.forgetSpentAsk();
        assertEquals("", Settings.SETTINGS_LOCK_ASKED.savedValue());
        assertTrue("a new ask starts a new wait", SettingsLock.ask());
        assertEquals(HOUR, SettingsLock.left());
    }

    @Test public void changingTheTimeOfDayDoesntMoveTheWait() {
        SettingsLock.lock(6);
        SettingsLock.ask();
        phone.wall += 7 * HOUR;
        assertEquals("the clock went forward", State.WAITING, SettingsLock.state());
        assertEquals(6 * HOUR, SettingsLock.left());
        phone.wall -= 3 * 24 * HOUR;
        assertEquals("the clock went back", State.WAITING, SettingsLock.state());
        assertEquals(6 * HOUR, SettingsLock.left());
        phone.elapsed += 6 * HOUR;
        assertEquals(State.OPEN, SettingsLock.state());
    }

    @Test public void aRestartCountsTheTimeOfDaySinceTheAsk() {
        SettingsLock.lock(1);
        SettingsLock.ask();
        phone.pass(10 * MINUTE);
        phone.restart(20 * MINUTE);
        assertEquals(State.WAITING, SettingsLock.state());
        assertEquals(30 * MINUTE, SettingsLock.left());
        phone.pass(30 * MINUTE);
        assertEquals(State.OPEN, SettingsLock.state());
    }

    @Test public void aRestartIsSeenEvenWhenTheNewClockSinceStartIsFurtherAlong() {
        SettingsLock.lock(1);
        SettingsLock.ask();
        phone.restart(5 * MINUTE);
        // The phone has now run longer since this start than it had when the ask was made.
        phone.elapsed = 8 * HOUR;
        assertEquals(55 * MINUTE, SettingsLock.left());
    }

    @Test public void withoutAStartCountTheClockSinceStartStillCatchesARestart() {
        phone.starts = -1;
        SettingsLock.lock(1);
        SettingsLock.ask();
        phone.pass(15 * MINUTE);
        assertEquals(45 * MINUTE, SettingsLock.left());
        phone.restart(15 * MINUTE);
        phone.starts = -1;
        assertEquals(30 * MINUTE, SettingsLock.left());
        // After a restart the time of day going back never counts as negative time.
        phone.wall -= 10 * HOUR;
        assertEquals(HOUR, SettingsLock.left());
    }

    @Test public void aSecondAskDoesntStartTheWaitOver() {
        SettingsLock.lock(1);
        SettingsLock.ask();
        phone.pass(30 * MINUTE);
        assertFalse(SettingsLock.ask());
        assertEquals(30 * MINUTE, SettingsLock.left());
    }

    @Test public void stoppingAWaitKeepsTheLockAndTheNextAskStartsItOver() {
        assertTrue(SettingsLock.lock(48));
        assertFalse("nothing to stop before an ask", SettingsLock.stopWait());
        SettingsLock.ask();
        phone.pass(47 * HOUR);
        assertTrue(SettingsLock.stopWait());
        assertEquals(State.LOCKED, SettingsLock.state());
        assertEquals("", Settings.SETTINGS_LOCK_ASKED.savedValue());
        assertEquals(48, SettingsLock.hours());
        phone.pass(2 * HOUR);
        assertEquals("the stopped wait never opens it", State.LOCKED, SettingsLock.state());
        assertTrue(SettingsLock.ask());
        assertEquals(48 * HOUR, SettingsLock.left());
        phone.pass(48 * HOUR);
        assertEquals(State.OPEN, SettingsLock.state());
        assertFalse("open: nothing to stop", SettingsLock.stopWait());
        assertEquals(State.OPEN, SettingsLock.state());
    }

    @Test public void theWaitsGoUpToThreeDays() {
        assertArrayEquals(new int[] {1, 6, 24, 48, 72}, SettingsLock.WAITS);
        assertTrue(SettingsLock.lock(72));
        SettingsLock.ask();
        assertEquals(72 * HOUR, SettingsLock.left());
    }

    @Test public void onlyAnOpenPageChangesTheLock() {
        SettingsLock.lock(1);
        assertFalse("locked: a shorter wait would be a way around it", SettingsLock.lock(1));
        assertFalse(SettingsLock.off());
        SettingsLock.ask();
        assertFalse("waiting", SettingsLock.off());
        assertFalse(SettingsLock.lock(24));
        phone.pass(HOUR);
        assertEquals(State.OPEN, SettingsLock.state());
        assertTrue(SettingsLock.lock(24));
        assertEquals(State.LOCKED, SettingsLock.state());
        assertEquals(24, SettingsLock.hours());
        assertEquals("the new lock starts with no ask", "", Settings.SETTINGS_LOCK_ASKED.savedValue());
        SettingsLock.ask();
        phone.pass(24 * HOUR);
        assertTrue(SettingsLock.off());
        assertEquals(State.OFF, SettingsLock.state());
        assertEquals("", Settings.SETTINGS_LOCK_ASKED.savedValue());
    }

    @Test public void anUnreadableAskIsLockedAndAskingStartsAFreshWait() {
        SettingsLock.lock(1);
        Settings.SETTINGS_LOCK_ASKED.save("yesterday");
        assertEquals(State.LOCKED, SettingsLock.state());
        Settings.SETTINGS_LOCK_ASKED.save("1,x,3");
        assertEquals(State.LOCKED, SettingsLock.state());
        assertTrue(SettingsLock.ask());
        assertEquals(State.WAITING, SettingsLock.state());
    }

    @Test public void pauseKeepsTheLockAndABackupLeavesItOut() {
        SettingsLock.lock(24);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(Settings.SETTINGS_LOCK_HOURS.isKeptWhenPaused());
        assertTrue(Settings.SETTINGS_LOCK_ASKED.isKeptWhenPaused());
        assertEquals(State.LOCKED, SettingsLock.state());
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_LOCK_HOURS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_LOCK_ASKED.key));
    }
}
