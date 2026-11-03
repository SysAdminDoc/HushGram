/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Keep the reel speed: the speed you lock with Instagram's own 2x lock (hold the edge of a reel, then
 * slide down, and Instagram says "Locked at 2x speed") stays for the next reels.
 *
 * <p>Instagram 449 keeps a lock on the reel it was made on. The Reels viewer's player controller sets
 * a reel's speed through its setPlaybackSpeed, a hold at the edge sets the hold speed there, and a
 * lock leaves the reel playing at it. The next reel's player starts at normal speed, and nothing
 * carries the lock over: the menu that could, Instagram's variable playback speed, isn't offered.
 *
 * <p>The patch tells this class every speed set through that setter ({@link #speedSet}), each lock
 * ({@link #lockedUp}), each end of a lock and why ({@link #lockUpEnded}), and each hold let go of
 * without a lock ({@link #holdEnded}). A lock keeps the speed the reel was set to last. The
 * controller's maybeResumePlayer hands over the reel ({@link #item}) and then its player
 * ({@link #resuming}) just before it plays it, and a reel that isn't an ad gets the kept speed then.
 * Instagram's own reset of the reel's speed, after a lock or a hold and on every scroll to the next
 * reel, is handed the kept speed in place of normal ({@link #resetSpeed}). That reset sets the reel
 * being scrolled to, an ad too, so an ad is set back to normal speed when it starts.
 *
 * <p>Sliding the lock off, or Instagram ending it from the speed menu, forgets the speed, and so does
 * a hold at the edge let go of without a lock: that hold ends at normal speed, and the next reels
 * start there too. Scrolling to the next reel or switching tabs ends Instagram's lock but keeps the
 * speed. Ads play at normal speed, as Instagram plays them. The speed lives in memory only, so it's gone when Instagram
 * restarts, and nothing is kept or set while the switch is off, HushGram is paused, the settings
 * aren't ready, or anything here fails.
 *
 * <p>The speed icon beside a reel (#480) sets the reel on screen through the player maybeResumePlayer
 * handed over last ({@link #choose}), with the switch off too, and with it on keeps the pick the way
 * a lock keeps a speed.
 */
public final class ReelSpeed {
    static final float NORMAL = 1f;

    /** Two speeds this close are the same one. */
    static final float SAME = 0.01f;

    /** Counted under the patch's name for each reel started at the kept speed. */
    static final String APPLIED = "reel started at the kept speed";

    /** Counted under the patch's name for each speed picked with the speed icon (#480). */
    static final String PICKED = "speed picked with the speed icon";

    /** The reasons Instagram ends a lock with when you end it yourself: sliding it off, or the speed menu. */
    static final String SLID_OFF = "swipe_down";
    static final String MENU = "cancel_lock_up";

    private static final String FAMILY = FamilyNames.KEEP_REEL_SPEED;

    /** What this class does to a player and reads from a reel. {@link #PATCHED} is the patch's; tests stand in. */
    interface Player {
        /** Sets the player's speed through Instagram's own setter. */
        void setSpeed(Object player, float speed);

        /** Whether the reel is an ad. */
        boolean ad(Object item);
    }

    static final Player PATCHED = new Player() {
        @Override
        public void setSpeed(Object player, float speed) {
            setPlayerSpeed(player, speed);
        }

        @Override
        public boolean ad(Object item) {
            return adItem(item);
        }
    };

    static volatile Player access = PATCHED;

    private static final Object LOCK = new Object();

    /** The speed the reel on screen was set to last, through the controller's setter. */
    private static float lastSpeed = NORMAL;

    /** The speed kept for the next reels, or {@link #NORMAL} when none is. */
    private static float kept = NORMAL;

    /** The reel maybeResumePlayer is about to play, handed over just before its player. */
    private static final ThreadLocal<Object> RESUMING = new ThreadLocal<>();

    /** The player maybeResumePlayer played last, weakly: the reel on screen, for the speed icon (#480). */
    @Nullable
    private static volatile WeakReference<Object> onScreen;

    /** The speed the reel on screen plays at, as far as this class saw it set, switch on or off. */
    private static volatile float playing = NORMAL;

    private ReelSpeed() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the player's own speed setter. Only a player may be passed. */
    public static void setPlayerSpeed(Object player, float speed) {
    }

    /** Filled in by the patch: the reel's ad flag, the one Instagram's fast play reads for its ads hint. */
    public static boolean adItem(Object item) {
        return false;
    }

    // ------------------------------------------------------------------ hooks

    /** The hook, first thing in the controller's setPlaybackSpeed, whoever calls it. */
    public static void speedSet(float speed) {
        try {
            HookStatus.invoked(FAMILY);
            playing = speed;
            if (!on()) {
                drop();
                return;
            }
            synchronized (LOCK) {
                lastSpeed = speed;
            }
            Logger.printDebug(() -> "Reel speed: the reel on screen set to " + speed + "x");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "speed set", failure);
        }
    }

    /** The hook, where Instagram logs that a lock began. Keeps the speed the reel plays at. */
    public static void lockedUp() {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                drop();
                return;
            }
            HookStatus.bound(FAMILY, "lock");
            float speed;
            synchronized (LOCK) {
                speed = lastSpeed;
                if (!same(speed, NORMAL)) kept = speed;
            }
            Logger.printDebug(() -> same(speed, NORMAL)
                    ? "Reel speed: locked, but the reel plays at normal speed, so nothing is kept"
                    : "Reel speed: locked at " + speed + "x, the next reels start at it");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "lock", failure);
        }
    }

    /**
     * The hook, where Instagram's reset logs that a lock ended, with the reason it logs. Sliding the
     * lock off and the speed menu forget the kept speed; scrolling on and switching tabs keep it.
     */
    public static void lockUpEnded(@Nullable String reason) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                drop();
                return;
            }
            HookStatus.bound(FAMILY, "lock end");
            boolean yours = SLID_OFF.equals(reason) || MENU.equals(reason);
            float speed;
            synchronized (LOCK) {
                speed = kept;
                if (yours) kept = NORMAL;
            }
            if (yours) {
                Logger.printDebug(() -> "Reel speed: the lock ended (" + reason + "), the next reels start at normal speed");
            } else if (!same(speed, NORMAL)) {
                Logger.printDebug(() -> "Reel speed: the lock ended with the reel (" + reason + "), keeping " + speed + "x");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "lock end", failure);
        }
    }

    /**
     * The hook, on the speed Instagram's reset hands the setter, normal speed. The kept speed when one
     * is kept, so a reset doesn't take the reel on screen back to normal; otherwise [speed] as it was.
     */
    public static float resetSpeed(float speed) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                drop();
                return speed;
            }
            float speedKept;
            synchronized (LOCK) {
                speedKept = kept;
            }
            if (same(speedKept, NORMAL)) return speed;
            Logger.printDebug(() -> "Reel speed: Instagram's reset to " + speed + "x stays at the kept " + speedKept + "x");
            return speedKept;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reset", failure);
            return speed;
        }
    }

    /** The hook, where Instagram logs a hold at the edge let go of without a lock. Forgets the kept speed. */
    public static void holdEnded() {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                drop();
                return;
            }
            HookStatus.bound(FAMILY, "hold end");
            float speed;
            synchronized (LOCK) {
                speed = kept;
                kept = NORMAL;
            }
            if (!same(speed, NORMAL)) {
                Logger.printDebug(() -> "Reel speed: a hold ended without a lock, the next reels start at normal speed");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "hold end", failure);
        }
    }

    /** The hook, in maybeResumePlayer just before {@link #resuming}: the reel it's about to play. */
    public static void item(@Nullable Object item) {
        try {
            HookStatus.invoked(FAMILY);
            RESUMING.set(item);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel start", failure);
        }
    }

    /**
     * The hook, in maybeResumePlayer just before it plays [player]: the kept speed goes on, unless the
     * reel {@link #item} handed over is an ad. An ad goes back to normal speed, since Instagram's reset
     * a moment before handed its player the kept speed.
     */
    public static void resuming(@Nullable Object player) {
        try {
            HookStatus.invoked(FAMILY);
            Object item = RESUMING.get();
            RESUMING.remove();
            if (player != null) {
                WeakReference<Object> held = onScreen;
                // Another player starts at normal speed until something sets it.
                if (held == null || held.get() != player) playing = NORMAL;
                onScreen = new WeakReference<>(player);
            }
            if (!on()) {
                drop();
                return;
            }
            float speed;
            synchronized (LOCK) {
                speed = kept;
            }
            if (player == null || same(speed, NORMAL)) return;
            HookStatus.bound(FAMILY, "reel start");
            if (item != null && access.ad(item)) {
                access.setSpeed(player, NORMAL);
                playing = NORMAL;
                Logger.printDebug(() -> "Reel speed: an ad started, set back to normal speed");
                return;
            }
            access.setSpeed(player, speed);
            playing = speed;
            HookStatus.counted(FAMILY, APPLIED);
            Logger.printDebug(() -> "Reel speed: a reel started, set to the kept " + speed + "x");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel start", failure);
        }
    }

    // ------------------------------------------------------------------ the speed icon (#480)

    /**
     * The speed icon's pick: [speed] goes on the reel on screen through the player's own setter. With
     * the switch on it's kept for the next reels the way a lock keeps one, and normal speed forgets
     * it. False when HushGram is paused or no reel has played since Instagram started. Never throws.
     */
    public static boolean choose(float speed) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return false;
            WeakReference<Object> held = onScreen;
            Object player = held == null ? null : held.get();
            if (player == null) return false;
            access.setSpeed(player, speed);
            playing = speed;
            boolean keep = on();
            if (keep) {
                synchronized (LOCK) {
                    lastSpeed = speed;
                    kept = same(speed, NORMAL) ? NORMAL : speed;
                }
            }
            HookStatus.counted(FAMILY, PICKED);
            Logger.printDebug(() -> "Reel speed: picked " + speed + "x" + (keep ? ", the next reels start at it" : ""));
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "speed pick", failure);
            return false;
        }
    }

    /** The speed the reel on screen plays at, as far as this class saw it set. */
    public static float playing() {
        return playing;
    }

    // ------------------------------------------------------------------ the rule

    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_REEL_SPEED.get();
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < SAME;
    }

    /** Off or paused: nothing is kept, so turning the switch back on doesn't bring an old speed back. */
    private static void drop() {
        synchronized (LOCK) {
            kept = NORMAL;
            lastSpeed = NORMAL;
        }
    }

    /** The speed kept for the next reels, or {@link #NORMAL} when none is. For tests. */
    static float kept() {
        synchronized (LOCK) {
            return kept;
        }
    }

    /** Forgets the kept speed and the last speed set. For tests. */
    static void forget() {
        drop();
        RESUMING.remove();
        onScreen = null;
        playing = NORMAL;
        access = PATCHED;
    }
}
