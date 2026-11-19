/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Let the screen sleep when a reel stops" patch (#777).
 *
 * <p>The Reels viewer keeps the screen on for as long as it's open: as it resumes it adds
 * FLAG_KEEP_SCREEN_ON to its activity's window, and it only clears it again as it pauses. A reel
 * you paused, or one stuck loading, keeps the phone awake until you leave. The patch tells
 * {@link #keepAwake} each time the viewer is about to add or clear that flag, and
 * {@link #started} each time a video player starts. While the viewer wants the screen on and the
 * switch is on, a check every {@link #CHECK_MS} asks each player that started whether it plays.
 * When none does, the flag comes off, so the phone's own screen timeout runs from your last touch.
 * It goes back on the moment a player plays again, and when the switch goes off. The viewer
 * clearing the flag itself ends the checks.
 *
 * <p>Nothing changes before a player has been seen, so a reel on some player this doesn't know
 * keeps the screen on as before. Nothing is requested or sent.
 */
public final class ReelScreenSleep {
    /** The step counted each time the flag comes off. */
    static final String STEP = "let the screen sleep";

    /** The step counted each time the flag goes back on for a playing video. */
    static final String WOKE = "kept the screen on again";

    static final int KEEP_SCREEN_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

    /** How often the check runs while the viewer wants the screen on, in milliseconds. */
    static final long CHECK_MS = 2_000L;

    /** How soon after a player starts the check runs, once its start has gone through. */
    static final long STARTED_MS = 500L;

    private static final Object LOCK = new Object();

    /** Every player that started lately. Weak, so a player Instagram lets go of goes too. */
    private static final Map<Object, Boolean> players = new WeakHashMap<>();

    /** The window the viewer wants kept on, or null while it doesn't. */
    @Nullable
    private static volatile WeakReference<Window> awake;

    /** Whether this took the flag off {@link #awake}, so it's ours to give back. */
    private static boolean letGo;

    @Nullable
    private static Handler main;

    private static final Runnable CHECK = ReelScreenSleep::check;

    /** Stands in for {@link #playing} in tests, where no patch fills it. */
    @Nullable
    static Predicate<Object> playingForTests;

    /** Stands in for the switch in tests. */
    @Nullable
    static BooleanSupplier switchForTests;

    private ReelScreenSleep() {
    }

    // ---- Stubs the patch fills ----

    /** Whether [player], an IgVideoPlayerImpl, is playing. The patch writes the body; false unpatched. */
    public static boolean playing(Object player) {
        return false;
    }

    // ---- Hooks ----

    /**
     * In the Reels viewer's keep-awake helper, right after it gets its activity's window and before
     * it adds or clears FLAG_KEEP_SCREEN_ON on it. [keep] is non-zero when it's about to add it.
     * Never throws.
     */
    public static void keepAwake(@Nullable Window window, int keep) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SCREEN_SLEEP);
            if (window == null) return;
            Handler handler = handler();
            handler.removeCallbacks(CHECK);
            synchronized (LOCK) {
                // Instagram is about to set the flag itself either way, so nothing is ours to give back.
                letGo = false;
                awake = keep != 0 ? new WeakReference<>(window) : null;
            }
            if (keep != 0) handler.postDelayed(CHECK, CHECK_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCREEN_SLEEP, "keep awake", failure);
        }
    }

    /** At the top of IgVideoPlayerImpl's playInternal, each time [player] starts playing. Never throws. */
    public static void started(@Nullable Object player) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SCREEN_SLEEP);
            if (player == null) return;
            synchronized (LOCK) {
                players.put(player, Boolean.TRUE);
            }
            if (awake == null) return;
            Handler handler = handler();
            handler.removeCallbacks(CHECK);
            handler.postDelayed(CHECK, STARTED_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCREEN_SLEEP, "player start", failure);
        }
    }

    // ---- The check ----

    /** Whether the switch is on: the settings are read and it's on, which a pause answers off. */
    private static boolean on() {
        BooleanSupplier forTests = switchForTests;
        if (forTests != null) return forTests.getAsBoolean();
        return Utils.settingsReady() && Settings.REEL_SCREEN_SLEEP.get();
    }

    private static Handler handler() {
        synchronized (LOCK) {
            if (main == null) main = new Handler(Looper.getMainLooper());
            return main;
        }
    }

    /** Runs on the main thread while the viewer wants the screen on. */
    static void check() {
        try {
            WeakReference<Window> ref = awake;
            Window window = ref != null ? ref.get() : null;
            if (window == null || !window.getDecorView().isAttachedToWindow()) {
                forget();
                return;
            }
            if (!on()) {
                // Off: the flag goes back if this took it, and nothing more is checked until the
                // viewer resumes again.
                if (giveBack()) window.addFlags(KEEP_SCREEN_ON);
                return;
            }
            List<Object> started = startedPlayers();
            boolean seen = !started.isEmpty();
            boolean any = false;
            for (Object player : started) {
                if (isPlaying(player)) {
                    any = true;
                    break;
                }
            }
            if (any) {
                if (giveBack()) {
                    window.addFlags(KEEP_SCREEN_ON);
                    HookStatus.counted(FamilyNames.REEL_SCREEN_SLEEP, WOKE);
                    Logger.printInfo(() -> "Reel screen sleep: a video plays, keeping the screen on");
                }
            } else if (seen && take()) {
                window.clearFlags(KEEP_SCREEN_ON);
                HookStatus.counted(FamilyNames.REEL_SCREEN_SLEEP, STEP);
                Logger.printInfo(() -> "Reel screen sleep: nothing plays, the screen can sleep");
            }
            if (awake == ref) handler().postDelayed(CHECK, CHECK_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCREEN_SLEEP, STEP, failure);
        }
    }

    private static boolean isPlaying(Object player) {
        Predicate<Object> forTests = playingForTests;
        return forTests != null ? forTests.test(player) : playing(player);
    }

    private static List<Object> startedPlayers() {
        synchronized (LOCK) {
            return new ArrayList<>(players.keySet());
        }
    }

    /** Marks the flag as taken; false when this already had it. */
    private static boolean take() {
        synchronized (LOCK) {
            if (letGo) return false;
            letGo = true;
            return true;
        }
    }

    /** Marks the flag as given back; false when this didn't have it. */
    private static boolean giveBack() {
        synchronized (LOCK) {
            if (!letGo) return false;
            letGo = false;
            return true;
        }
    }

    private static void forget() {
        synchronized (LOCK) {
            awake = null;
            letGo = false;
        }
    }

    /** Back to how a fresh process starts, for tests. */
    static void resetForTests() {
        synchronized (LOCK) {
            if (main != null) main.removeCallbacks(CHECK);
            main = null;
            players.clear();
            awake = null;
            letGo = false;
        }
        playingForTests = null;
        switchForTests = null;
    }
}
