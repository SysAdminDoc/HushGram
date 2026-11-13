/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Let the screen sleep after a reel" patch.
 *
 * <p>Measured on the device, Instagram asks for {@code FLAG_KEEP_SCREEN_ON} exactly once when the
 * reel viewer opens and never asks for it to go — not even at a reel's end — and it does not take
 * its own screen wake locks on this build. That flag is the whole keep-awake. So the patch cannot
 * ride Instagram's requests: it turns them down and holds the screen itself for as long as the
 * player keeps reporting progress.
 *
 * <p>The tick is the player checking its own playback progress, about every two seconds while a reel
 * runs and not at all once it stops. A six second lease rides on it, which covers the measured gap
 * between checks, so a playing reel keeps the screen on and a reel that has finished, a viewer left
 * paused or one closed lets the phone's screen timeout take over.
 *
 * <p>With the switch off nothing is denied and nothing is held. Locks without screen bits — the
 * player's {@code PARTIAL} lock for sound — are never touched.
 */
public final class ReelScreenAwake {
    private static final String TAG = "HushGramReelScreen";

    /** {@code PowerManager.SCREEN_DIM_WAKE_LOCK} and {@code SCREEN_BRIGHT_WAKE_LOCK} both carry this bit. */
    private static final int SCREEN_BITS = 0x2;

    /** How long after the last progress tick the screen is still treated as playing. */
    private static final long LEASE_MS = 30000L;

    /** How long the display is left dimmed after playback stops, before the sleep takes it. */
    private static final long DIM_MS = 10000L;

    private static final String LOCK_TAG = "HushGram:ReelPlayback";
    private static final String DIM_TAG = "HushGram:ReelDim";

    /** Every screen lock this patch has been shown, so one Instagram holds is let go. */
    private static final Set<PowerManager.WakeLock> seen =
            Collections.newSetFromMap(new WeakHashMap<PowerManager.WakeLock, Boolean>());

    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** The patch's own screen lock, held while a reel plays. */
    private static PowerManager.WakeLock own;

    /**
     * A dim screen lock, held for a few seconds after playback stops. Without it the sleep deadline
     * has already passed while the bright lock was held, so the display would go straight to sleep
     * and lock instead of dimming first.
     */
    private static PowerManager.WakeLock dim;

    private static long lastTickAt;
    private static boolean leaseOpen;
    private static int ticks;

    private static final Runnable EXPIRE = new Runnable() {
        @Override
        public void run() {
            if (stillPlaying()) {
                // Instagram is in front and its player is still putting out sound, so a reel is
                // running even though its progress ticks have gone quiet. Re-check soon, so the
                // screen is let go promptly once the sound stops.
                handler.postDelayed(this, 4000L);
                return;
            }
            leaseOpen = false;
            Log.i(TAG, "Playback stopped; dimming before the screen sleeps. ticks=" + ticks);
            letGoOfHeldLocks();
            startDimming();
        }
    };

    /**
     * Whether a reel is still running: either its progress ticks kept coming, or Instagram's player
     * is still playing sound with Instagram in front. The second test is what covers the quiet gaps
     * between ticks while a video plays.
     */
    private static boolean stillPlaying() {
        if (!on()) return false;
        if (SystemClock.elapsedRealtime() - lastTickAt < LEASE_MS) return true;
        Context context = Utils.getContext();
        if (context == null) return false;
        Context app = context.getApplicationContext();
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        boolean music = audio != null && audio.isMusicActive();
        ActivityManager activity = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        int importance = -1;
        if (activity != null) {
            ActivityManager.RunningAppProcessInfo self = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(self);
            importance = self.importance;
        }
        boolean foreground = importance >= 0 &&
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
        Log.i(TAG, "idle check: musicActive=" + music + " importance=" + importance);
        return music && foreground;
    }
    /** The reel viewer being handed a playback state. */
    public static void progressed() {
        tick("state push");
    }

    /** The player checking its own playback progress, about every two seconds while a reel runs. */
    public static void checked() {
        tick("progress check");
    }

    /** The player changing between playing and stopped. */
    public static void playbackState(int isPlaying) {
        tick(isPlaying != 0 ? "play state true" : "play state false");
    }

    /** One sign of playback life: holds the screen for another lease. */
    private static void tick(String why) {
        try {
            if (!on()) return;
            if (!ownLockReady()) {
                // Without a context the patch cannot hold the screen itself, so Instagram keeps it.
                return;
            }
            ticks++;
            lastTickAt = SystemClock.elapsedRealtime();
            if (!leaseOpen) {
                leaseOpen = true;
                Log.i(TAG, "Playback running (" + why + "); holding the screen awake.");
            }
            handler.removeCallbacks(EXPIRE);
            handler.postDelayed(EXPIRE, LEASE_MS);
            if (dim != null && dim.isHeld()) dim.release();
            handler.removeCallbacks(DIM_OFF);
            if (own != null && !own.isHeld()) own.acquire();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SCREEN_SLEEP_AFTER_REEL, "keeping a reel's screen awake", failure);
        }
    }

    /**
     * Instagram asking for {@code FLAG_KEEP_SCREEN_ON} (true) or for it to go (false). With the
     * switch on the flag is never set: the patch holds the screen itself so that it can let go.
     */
    public static boolean allowAwake(int want) {
        try {
            HookStatus.invoked(FamilyNames.SCREEN_SLEEP_AFTER_REEL);
            if (!on() || !ownLockReady()) return want != 0;
            if (want != 0) {
                tick("window flag ask");
                return false;
            }
            letGoOfHeldLocks();
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SCREEN_SLEEP_AFTER_REEL, "deciding a reel's screen awake", failure);
            return want != 0;
        }
    }

    /**
     * Instagram about to take or hold one of its wake locks. With the switch on, screen locks are
     * denied and released here; locks without screen bits are left alone.
     *
     * @param wakeLock the player's lock, or null before it has one
     * @param level    the {@code PowerManager} flags it was made with, or negative when unknown
     */
    public static boolean keepScreenAwakeLock(Object wakeLock, int level) {
        try {
            HookStatus.invoked(FamilyNames.SCREEN_SLEEP_AFTER_REEL);
            if (!(wakeLock instanceof PowerManager.WakeLock)) return true;
            PowerManager.WakeLock lock = (PowerManager.WakeLock) wakeLock;
            synchronized (seen) {
                seen.add(lock);
            }
            if (!on()) return true;
            if (level >= 0 && (level & SCREEN_BITS) == 0) return true;
            tick("player lock ask");
            letGoOfHeldLocks();
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SCREEN_SLEEP_AFTER_REEL, "letting a reel's screen lock go", failure);
            return true;
        }
    }

    /**
     * Makes the patch's screen locks, or reports that it cannot. Without them the patch must not
     * deny Instagram, or nothing would hold the screen while a reel plays.
     */
    private static boolean ownLockReady() {
        if (own != null && dim != null) return true;
        Context context = Utils.getContext();
        if (context == null) return false;
        PowerManager power = (PowerManager) context.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        if (power == null) return false;
        if (own == null) {
            own = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, LOCK_TAG);
            own.setReferenceCounted(false);
        }
        if (dim == null) {
            dim = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, DIM_TAG);
            dim.setReferenceCounted(false);
        }
        return true;
    }

    /** Leaves the display dimmed for a moment after playback, so it dims before it sleeps. */
    private static void startDimming() {
        if (dim == null || !on()) return;
        if (own != null && own.isHeld()) own.release();
        if (!dim.isHeld()) dim.acquire();
        handler.removeCallbacks(DIM_OFF);
        handler.postDelayed(DIM_OFF, DIM_MS);
    }

    private static final Runnable DIM_OFF = new Runnable() {
        @Override
        public void run() {
            if (dim != null && dim.isHeld()) dim.release();
        }
    };

    /** Releases Instagram's screen locks and, unless a reel is playing, the patch's own lock too. */
    private static void letGoOfHeldLocks() {
        synchronized (seen) {
            for (PowerManager.WakeLock lock : seen) {
                if (lock == null || !lock.isHeld()) continue;
                lock.release();
            }
        }
        boolean playing = leaseOpen && SystemClock.elapsedRealtime() - lastTickAt < LEASE_MS;
        if (!playing && own != null && own.isHeld()) own.release();
    }

    /** Whether the switch is on and its settings are ready to be read. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.LET_SCREEN_SLEEP_AFTER_REEL.get();
    }
}
