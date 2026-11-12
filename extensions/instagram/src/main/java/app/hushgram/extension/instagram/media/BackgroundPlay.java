/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Keep playing in the background" patch (#497).
 *
 * <p>Instagram pauses the video on screen when you leave it: going home, switching apps or turning
 * the screen off pauses its fragment, and the fragment pauses IgVideoPlayerImpl. With the switch on,
 * this class picks the video that was playing with sound as you left ({@link #leaving} and the
 * screen-off check in {@link #watch}) and answers yes to {@link #hold} for that player's pause and
 * stop, so it plays on. Once the screen it plays on has stopped, {@link BackgroundPlayService} runs
 * in the foreground with a media notification: Pause or Play, Stop, and a tap that brings Instagram
 * back.
 *
 * <p>Everything else stops it as before. Coming back to the same screen ends the session with the
 * video still playing. Another Instagram screen opening over it pauses it, as Instagram would have.
 * Instagram's own handling of a lost audio focus still pauses or mutes it (a mute pauses it here,
 * and the sound coming back within {@link #MUTE_RESUME_MS} plays it again). Releasing the player,
 * picture in picture, the screen closing, Instagram being swiped away, and Stop in the notification
 * all end it. A session left paused for {@link #PAUSED_LIMIT_MS} ends by itself.
 *
 * <p>The patch writes the bodies of {@link #playing}, {@link #pause}, {@link #play} and
 * {@link #engine}. Every hook runs on the main thread, and none of them ever throws into Instagram.
 */
public final class BackgroundPlay {
    /** How long after you leave the screen it plays on still has to be up before the session is dropped. */
    static final long WATCHDOG_MS = 1_500;

    /** How many players that started lately are looked at for the one playing with sound. */
    static final int RECENT_PLAYERS = 6;

    /** A session left paused this long ends, and its notification goes. */
    static final long PAUSED_LIMIT_MS = 15 * 60_000L;

    /** A video muted in the background plays again when the sound comes back within this long. */
    static final long MUTE_RESUME_MS = 60_000;

    /** The reason the pause stub hands Instagram's pause, the one its fragment gives. */
    static final String PAUSE_REASON = "fragment_paused";

    private static final long NONE = Long.MIN_VALUE;
    private static final String HOOK = "player pause";
    private static final String KEPT = "pauses kept playing";

    /** Where a session is: none, leaving (the pause is held), or in the background (the service runs). */
    enum Phase { IDLE, LEAVING, BACKGROUND }

    /** What the stubs do, for tests that have no Instagram player. */
    interface Control {
        boolean playing(Object player);

        void pause(Object player);

        void play(Object player);
    }

    @Nullable
    static volatile Control controlForTests;

    /** Whether the screen is on, when a test says so instead of the PowerManager. */
    @Nullable
    static volatile Boolean interactiveForTests;

    /** Whether something plays music, when a test says so instead of the AudioManager. */
    @Nullable
    static volatile Boolean musicActiveForTests;

    /** Set by tests so the service isn't started; counts the starts instead. */
    static volatile boolean serviceForTests;
    static volatile int serviceStartsForTests;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<WeakReference<Object>> recent = new ArrayDeque<>();
    private static final Map<Object, Float> volumes = new WeakHashMap<>();
    private static Phase phase = Phase.IDLE;
    @Nullable
    private static WeakReference<Object> held;
    @Nullable
    private static WeakReference<Activity> leavingScreen;
    @Nullable
    private static WeakReference<Activity> resumedScreen;
    private static long mutedAt = NONE;
    private static long pausedSince = NONE;
    /** True while this class pauses the held player itself, so that pause goes through. */
    private static boolean passing;
    private static boolean watching;

    private BackgroundPlay() {
    }

    // ---- Stubs the patch fills ----

    /** Whether [player], an IgVideoPlayerImpl, is playing. The patch writes the body; false unpatched. */
    public static boolean playing(Object player) {
        return false;
    }

    /** Pauses [player] with {@link #PAUSE_REASON}. The patch writes the body. */
    public static void pause(Object player) {
    }

    /** Plays [player] again. The patch writes the body. */
    public static void play(Object player) {
    }

    /** [player]'s IgGrootPlayer, which Tap to play knows it by. The patch writes the body; null unpatched. */
    @Nullable
    public static Object engine(Object player) {
        return null;
    }

    // ---- Hooks ----

    /**
     * Called once Instagram's application has started, when this patch is in the build. Watches
     * every activity, for the screen going off as one pauses, and for the one a video plays on
     * stopping, resuming or closing.
     */
    public static void watch(Context context) {
        try {
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            if (watching || !(application instanceof Application)) return;
            watching = true;
            ((Application) application).registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "activity watch", t);
        }
    }

    /** At the top of IgFragmentActivity's onUserLeaveHint: home, recents, or a screen of another app opening. */
    public static void leaving(Activity activity) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            arm(activity, "you left Instagram");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "leave hint", t);
        }
    }

    /** At the top of IgVideoPlayerImpl's playInternal, after Tap to play's guard, each time [player] starts playing. */
    public static void started(Object player) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (player == null) return;
            boolean ours;
            synchronized (LOCK) {
                remember(player);
                ours = phase != Phase.IDLE;
            }
            // Read once Instagram's start is done: a start another patch holds back plays nothing.
            if (ours) Utils.runOnMainThread(() -> afterStart(player));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player start", t);
        }
    }

    /** At the top of IgVideoPlayerImpl's setVolume, with the volume it's asked for. */
    public static void volume(Object player, float volume) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (player == null) return;
            boolean muted;
            boolean unmuted;
            long now = SystemClock.uptimeMillis();
            synchronized (LOCK) {
                volumes.put(player, volume);
                if (phase != Phase.BACKGROUND || player != heldLocked()) return;
                muted = volume <= 0f && mutedAt == NONE;
                unmuted = volume > 0f && mutedAt != NONE && now - mutedAt <= MUTE_RESUME_MS;
                if (muted) mutedAt = now;
                else if (volume > 0f) mutedAt = NONE;
            }
            // Never from inside the player's own setVolume: the pause or play goes in after it.
            if (muted) {
                log(() -> "Instagram muted the video in the background, so it's paused");
                Utils.runOnMainThread(() -> {
                    Object current = held();
                    if (current == player && isPlaying(player)) pauseThrough(player);
                    BackgroundPlayService.refresh();
                });
            } else if (unmuted) {
                log(() -> "the sound came back, so the video plays again");
                Utils.runOnMainThread(() -> {
                    if (held() == player) playThrough(player);
                    BackgroundPlayService.refresh();
                });
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player volume", t);
        }
    }

    /**
     * At the top of IgVideoPlayerImpl's pause and stop, after Resume long videos' hook, with Instagram's reason. True, and the
     * pause or stop returns at once, only for the player held as you left, while the switch is on.
     * A pause for the audio focus always goes through.
     */
    public static boolean hold(Object player, @Nullable String reason) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, HOOK);
            Activity screen;
            synchronized (LOCK) {
                if (passing || phase == Phase.IDLE || player == null || player != heldLocked()) return false;
                screen = leavingScreen != null ? leavingScreen.get() : null;
            }
            if (!on()) {
                end(false, "the switch is off");
                return false;
            }
            if (reason != null && reason.toLowerCase(Locale.ROOT).contains("focus")) {
                log(() -> "let a pause for the audio focus through (" + reason + ")");
                return false;
            }
            if (screen != null && inPictureInPicture(screen)) {
                end(false, "Instagram went to picture in picture");
                return false;
            }
            HookStatus.counted(FamilyNames.BACKGROUND_PLAY, KEPT);
            log(() -> "kept the video playing through Instagram's " + (reason == null ? "pause" : "\"" + reason + "\""));
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return false;
        }
    }

    /** At the top of IgVideoPlayerImpl's release. The held player going ends the session. */
    public static void released(Object player) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            boolean ours;
            synchronized (LOCK) {
                ours = phase != Phase.IDLE && player != null && player == heldLocked();
            }
            if (ours) end(false, "Instagram let the player go");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player release", t);
        }
    }

    // ---- The notification's buttons, called by the service on the main thread ----

    static void pauseFromNotification() {
        Object player = held();
        if (player == null) return;
        synchronized (LOCK) {
            mutedAt = NONE;
        }
        pauseThrough(player);
    }

    static void playFromNotification() {
        Object player = held();
        if (player == null) return;
        // Tap to play holds back a start nobody tapped, and the button is a tap.
        TapToPlay.tappedFromNotification(engineOf(player));
        playThrough(player);
    }

    static void stopFromNotification() {
        end(true, "Stop in the notification");
    }

    /** The task was swiped away from recents while the video played on. */
    static void taskRemoved() {
        end(true, "Instagram was swiped away");
    }

    /** The service went away while a session still needed it. */
    static void serviceGone() {
        if (active()) end(true, "the notification's service stopped");
    }

    /** The service's regular check: the player still there, and a pause not left too long. */
    static void tick() {
        Object player;
        synchronized (LOCK) {
            if (phase != Phase.BACKGROUND) return;
            player = heldLocked();
        }
        if (player == null) {
            end(false, "the player is gone");
            return;
        }
        boolean playing = isPlaying(player);
        long now = SystemClock.uptimeMillis();
        boolean tooLong;
        synchronized (LOCK) {
            if (playing) pausedSince = NONE;
            else if (pausedSince == NONE) pausedSince = now;
            tooLong = !playing && now - pausedSince >= PAUSED_LIMIT_MS;
        }
        if (tooLong) end(false, "it was paused for " + PAUSED_LIMIT_MS / 60_000 + " minutes");
    }

    /** True while the service has a session to show. */
    static boolean active() {
        synchronized (LOCK) {
            return phase == Phase.BACKGROUND;
        }
    }

    /** Whether the held video is playing; false with none. */
    static boolean heldPlaying() {
        Object player = held();
        return player != null && isPlaying(player);
    }

    static Phase phase() {
        synchronized (LOCK) {
            return phase;
        }
    }

    // ---- The session ----

    /**
     * Holds the video playing with sound as [screen] goes. Nothing while the switch is off, a
     * session is already under way, or the screen is finishing, turning, or in picture in picture.
     */
    static void arm(Activity screen, String why) {
        if (screen == null || !on()) return;
        if (screen.isFinishing() || screen.isChangingConfigurations() || inPictureInPicture(screen)) return;
        synchronized (LOCK) {
            if (phase != Phase.IDLE) return;
        }
        Object player = audiblePlayer(screen);
        if (player == null) {
            log(() -> why + ", with no video playing with sound");
            return;
        }
        synchronized (LOCK) {
            if (phase != Phase.IDLE) return;
            phase = Phase.LEAVING;
            held = new WeakReference<>(player);
            leavingScreen = new WeakReference<>(screen);
            mutedAt = NONE;
            pausedSince = NONE;
        }
        log(() -> why + ", so the video playing with sound is kept going");
        Utils.runOnMainThreadDelayed(() -> watchdog(screen), WATCHDOG_MS);
    }

    /** Drops a session whose screen never went: still in front, or gone to picture in picture. */
    static void watchdog(Activity screen) {
        boolean stayed;
        synchronized (LOCK) {
            stayed = phase == Phase.LEAVING && leavingScreen != null && leavingScreen.get() == screen
                    && resumedScreen != null && resumedScreen.get() == screen;
        }
        if (stayed) end(false, "Instagram stayed on screen");
        else if (phase() == Phase.LEAVING && inPictureInPicture(screen)) end(false, "Instagram went to picture in picture");
    }

    static void stopped(Activity screen) {
        Object player;
        synchronized (LOCK) {
            if (phase != Phase.LEAVING || leavingScreen == null || leavingScreen.get() != screen) return;
            player = heldLocked();
        }
        if (screen.isChangingConfigurations() || inPictureInPicture(screen)) {
            end(false, "the screen turned or went to picture in picture");
            return;
        }
        if (player == null || !isPlaying(player)) {
            end(false, "Instagram stopped the video some other way as it left");
            return;
        }
        Float volume;
        synchronized (LOCK) {
            volume = volumes.get(player);
        }
        if (volume != null && volume <= 0f) {
            end(true, "Instagram muted the video as it left");
            return;
        }
        synchronized (LOCK) {
            if (phase != Phase.LEAVING) return;
            phase = Phase.BACKGROUND;
        }
        try {
            if (serviceForTests) serviceStartsForTests++;
            else BackgroundPlayService.start(screen.getApplicationContext());
            HookStatus.counted(FamilyNames.BACKGROUND_PLAY, "background sessions");
            log(() -> "Instagram is in the background and the video plays on");
        } catch (Throwable t) {
            // Android 12 and later refuse a foreground service started from the background.
            Logger.printInfo(() -> "Android wouldn't show the background playback notification", t instanceof Exception ? (Exception) t : null);
            end(true, "Android refused the notification's service");
        }
    }

    static void resumed(Activity screen) {
        Object player;
        boolean same;
        synchronized (LOCK) {
            resumedScreen = new WeakReference<>(screen);
            if (phase == Phase.IDLE) return;
            player = heldLocked();
            same = leavingScreen != null && leavingScreen.get() == screen;
        }
        if (same) {
            end(false, "Instagram came back");
        } else {
            // Another Instagram screen opened over the video's: Instagram meant to pause it.
            end(player != null, "another Instagram screen opened");
        }
    }

    static void paused(Activity screen) {
        synchronized (LOCK) {
            if (resumedScreen != null && resumedScreen.get() == screen) resumedScreen = null;
        }
    }

    static void destroyed(Activity screen) {
        boolean ours;
        synchronized (LOCK) {
            ours = phase != Phase.IDLE && leavingScreen != null && leavingScreen.get() == screen;
        }
        if (ours) end(true, "the video's screen closed");
    }

    /** Ends the session, pausing the held video first when [pause], and takes the notification down. */
    static void end(boolean pause, String why) {
        Object player;
        boolean wasBackground;
        synchronized (LOCK) {
            if (phase == Phase.IDLE) return;
            wasBackground = phase == Phase.BACKGROUND;
            player = heldLocked();
            phase = Phase.IDLE;
            held = null;
            leavingScreen = null;
            mutedAt = NONE;
            pausedSince = NONE;
        }
        if (pause && player != null) pauseThrough(player);
        if (wasBackground && !serviceForTests) BackgroundPlayService.finish();
        log(() -> "background playback ended: " + why);
    }

    // ---- Helpers ----

    private static void afterStart(Object player) {
        Object current;
        Activity screen;
        synchronized (LOCK) {
            if (phase != Phase.BACKGROUND) return;
            current = heldLocked();
            screen = leavingScreen != null ? leavingScreen.get() : null;
        }
        if (player != current && isPlaying(player) && (current == null || !isPlaying(current))) {
            // The reel moved on by itself: the one playing now is the one to keep.
            synchronized (LOCK) {
                if (phase != Phase.BACKGROUND || screen == null) return;
                held = new WeakReference<>(player);
            }
            log(() -> "another video took over in the background");
        }
        synchronized (LOCK) {
            if (player == heldLocked()) pausedSince = NONE;
        }
        BackgroundPlayService.refresh();
    }

    /** The player that started most lately and plays with sound now, or null. */
    @Nullable
    private static Object audiblePlayer(Context context) {
        Object[] candidates;
        synchronized (LOCK) {
            candidates = new Object[recent.size()];
            int i = 0;
            for (WeakReference<Object> reference : recent) candidates[i++] = reference.get();
        }
        Boolean music = null;
        for (Object player : candidates) {
            if (player == null || !isPlaying(player)) continue;
            Float volume;
            synchronized (LOCK) {
                volume = volumes.get(player);
            }
            if (volume != null) {
                if (volume > 0f) return player;
                continue;
            }
            if (music == null) music = musicActive(context);
            if (music) return player;
        }
        return null;
    }

    /** Puts [player] first among the recent ones. Under {@link #LOCK}. */
    private static void remember(Object player) {
        for (Iterator<WeakReference<Object>> it = recent.iterator(); it.hasNext(); ) {
            Object seen = it.next().get();
            if (seen == null || seen == player) it.remove();
        }
        recent.addFirst(new WeakReference<>(player));
        while (recent.size() > RECENT_PLAYERS) recent.removeLast();
    }

    @Nullable
    private static Object held() {
        synchronized (LOCK) {
            return heldLocked();
        }
    }

    @Nullable
    private static Object heldLocked() {
        return held != null ? held.get() : null;
    }

    private static void pauseThrough(Object player) {
        synchronized (LOCK) {
            passing = true;
        }
        try {
            Control control = controlForTests;
            if (control != null) control.pause(player);
            else pause(player);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "pause", t);
        } finally {
            synchronized (LOCK) {
                passing = false;
            }
        }
    }

    private static void playThrough(Object player) {
        try {
            Control control = controlForTests;
            if (control != null) control.play(player);
            else play(player);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "play", t);
        }
    }

    private static boolean isPlaying(Object player) {
        try {
            Control control = controlForTests;
            return control != null ? control.playing(player) : playing(player);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "is playing", t);
            return false;
        }
    }

    @Nullable
    private static Object engineOf(Object player) {
        try {
            return controlForTests != null ? player : engine(player);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "engine", t);
            return null;
        }
    }

    private static boolean interactive(Context context) {
        Boolean forced = interactiveForTests;
        if (forced != null) return forced;
        PowerManager power = context.getSystemService(PowerManager.class);
        return power == null || power.isInteractive();
    }

    private static boolean musicActive(Context context) {
        Boolean forced = musicActiveForTests;
        if (forced != null) return forced;
        AudioManager audio = context.getSystemService(AudioManager.class);
        return audio != null && audio.isMusicActive();
    }

    private static boolean inPictureInPicture(Activity screen) {
        try {
            return screen.isInPictureInPictureMode();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_PLAYING_IN_BACKGROUND.get();
    }

    private static void log(Logger.LogMessage message) {
        Logger.printInfo(message);
    }

    /** Clears every session and record. For tests. */
    static void resetForTests() {
        synchronized (LOCK) {
            recent.clear();
            volumes.clear();
            phase = Phase.IDLE;
            held = null;
            leavingScreen = null;
            resumedScreen = null;
            mutedAt = NONE;
            pausedSince = NONE;
            passing = false;
        }
        // Each test has an application of its own to watch.
        watching = false;
        controlForTests = null;
        interactiveForTests = null;
        musicActiveForTests = null;
        serviceForTests = false;
        serviceStartsForTests = 0;
    }

    /** Pretends the held session has been paused since [since], for the paused limit's test. */
    static void pausedSinceForTests(long since) {
        synchronized (LOCK) {
            pausedSince = since;
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityPrePaused(Activity activity) {
            screenOff(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            // Android 9 has no pre-pause; its paused callback runs before the fragments pause.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) screenOff(activity);
            try {
                paused(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "activity pause", t);
            }
        }

        private void screenOff(Activity activity) {
            try {
                if (!interactive(activity)) arm(activity, "the screen went off");
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "screen off", t);
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {
            try {
                stopped(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "activity stop", t);
            }
        }

        @Override
        public void onActivityResumed(Activity activity) {
            try {
                resumed(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "activity resume", t);
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            try {
                destroyed(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "activity close", t);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }
    }
}
