/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.os.SystemClock;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.Setting;

/**
 * Lock these settings (#301). Locked, the settings page changes nothing. Ask to unlock starts the
 * wait the lock was set with, 1 to 72 hours, and when it ends the page opens for ten minutes, then
 * locks again unless the lock was turned off in that time. A wait can be stopped before it ends,
 * which keeps the lock as it was before the ask.
 *
 * <p>The wait is counted on the clock that runs from the phone's start, which setting the time of
 * day doesn't move. Only when the phone restarted since Ask to unlock does it fall back on the time
 * of day, since that clock started over. Both values are read with savedValue(), so Pause HushGram
 * never drops the lock.
 */
final class SettingsLock {
    enum State {
        /** No lock: the page works as always. */
        OFF,
        /** Locked, and nobody asked to unlock. */
        LOCKED,
        /** Ask to unlock was tapped and the wait runs. */
        WAITING,
        /** The wait is over: the page is open until {@link #OPEN_MS} more have passed. */
        OPEN
    }

    /** How long the page stays open once the wait is over. */
    static final long OPEN_MS = 10 * 60_000L;
    /** The waits a lock can be set with, in hours. */
    static final int[] WAITS = {1, 6, 24, 48, 72};
    private static final long HOUR_MS = 3_600_000L;

    /** The phone's clocks. */
    interface Clocks {
        /** The time of day, in ms since 1970. */
        long wall();
        /** Ms since the phone started, which setting the time doesn't change. */
        long elapsed();
        /** How many times the phone has started, or -1 when it doesn't say. */
        int starts();
    }

    static final Clocks PHONE = new Clocks() {
        @Override public long wall() {
            return System.currentTimeMillis();
        }

        @Override public long elapsed() {
            return SystemClock.elapsedRealtime();
        }

        @Override public int starts() {
            try {
                Context context = Utils.getContext();
                return context == null ? -1 : android.provider.Settings.Global.getInt(
                        context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
            } catch (RuntimeException unreadable) {
                return -1;
            }
        }
    };

    /** The clocks the lock reads. Tests swap in their own. */
    static volatile Clocks clocks = PHONE;

    private SettingsLock() { }

    static State state() {
        int hours = Settings.SETTINGS_LOCK_HOURS.savedValue();
        if (hours <= 0) return State.OFF;
        long since = sinceAsked();
        if (since < 0) return State.LOCKED;
        long wait = hours * HOUR_MS;
        if (since < wait) return State.WAITING;
        return since < wait + OPEN_MS ? State.OPEN : State.LOCKED;
    }

    /** Whether the page should change nothing now. */
    static boolean locked() {
        State state = state();
        return state == State.LOCKED || state == State.WAITING;
    }

    /** The hours the lock waits, 0 while it's off. */
    static int hours() {
        return Math.max(0, Settings.SETTINGS_LOCK_HOURS.savedValue());
    }

    /** While waiting, the ms until the page opens. While open, the ms until it locks again. Otherwise 0. */
    static long left() {
        State state = state();
        if (state != State.WAITING && state != State.OPEN) return 0;
        long wait = hours() * HOUR_MS;
        long since = sinceAsked();
        return Math.max(0, (state == State.WAITING ? wait : wait + OPEN_MS) - since);
    }

    /**
     * How long ago Ask to unlock was tapped, or -1 when nobody asked or the value can't be read. On
     * the same start of the phone it's the clock since the start. After a restart that clock began
     * again, so it's the time of day, never below zero.
     */
    static long sinceAsked() {
        String[] asked = Settings.SETTINGS_LOCK_ASKED.savedValue().split(",");
        if (asked.length != 3) return -1;
        long wall;
        long elapsed;
        int starts;
        try {
            wall = Long.parseLong(asked[0]);
            elapsed = Long.parseLong(asked[1]);
            starts = Integer.parseInt(asked[2]);
        } catch (NumberFormatException unreadable) {
            return -1;
        }
        Clocks now = clocks;
        long elapsedNow = now.elapsed();
        int startsNow = starts < 0 ? -1 : now.starts();
        boolean sameStart = elapsedNow >= elapsed && (starts < 0 || startsNow < 0 || startsNow == starts);
        if (sameStart) return elapsedNow - elapsed;
        return Math.max(0, now.wall() - wall);
    }

    /** Locks with a wait of [hours]. Only while off or open, so a lock never shortens its own wait. */
    static boolean lock(int hours) {
        if (hours <= 0 || locked()) return false;
        return save(hours, "");
    }

    /** Starts the wait. Only while locked with nobody asking, so a second tap doesn't start it over. */
    static boolean ask() {
        if (state() != State.LOCKED) return false;
        Clocks now = clocks;
        return save(null, now.wall() + "," + now.elapsed() + "," + now.starts());
    }

    /**
     * Stops a running wait, so the settings stay locked and the next ask starts the whole wait
     * again. Only while waiting: it never opens anything or takes the lock off.
     */
    static boolean stopWait() {
        if (state() != State.WAITING) return false;
        return save(null, "");
    }

    /** Turns the lock off. Only while open. */
    static boolean off() {
        if (state() != State.OPEN) return false;
        return save(0, "");
    }

    /** The wait and its ask change together, with the store's result deciding success. */
    private static boolean save(Integer hours, String asked) {
        Map<Setting<?>, Object> updates = new LinkedHashMap<>();
        if (hours != null) updates.put(Settings.SETTINGS_LOCK_HOURS, hours);
        updates.put(Settings.SETTINGS_LOCK_ASKED, asked);
        try {
            Setting.saveAll(updates);
            return true;
        } catch (IOException failure) {
            Logger.printException(() -> "Could not save the settings lock", failure);
            return false;
        }
    }

    /**
     * Drops an ask whose open time ran out, so a later change of the time of day after a restart
     * can't bring the open time back.
     */
    static void forgetSpentAsk() {
        if (state() == State.LOCKED && !Settings.SETTINGS_LOCK_ASKED.savedValue().isEmpty()) {
            Settings.SETTINGS_LOCK_ASKED.save("");
        }
    }
}
