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
import android.view.Choreographer;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
 * the screen off pauses its fragment, and the fragment pauses IgVideoPlayerImpl, or the story
 * viewer's own player, which drives the same engine without it. With the switch on, this class
 * picks the video that was playing with sound as you left ({@link #leaving} and the screen-off
 * check in {@link #watch}) and answers yes to {@link #hold} for that player's pause and stop, so it
 * plays on. Once the screen it plays on has stopped, {@link BackgroundPlayService} runs in the
 * foreground with a media notification: Pause or Play, Stop, and a tap that brings Instagram back.
 *
 * <p>The Reels viewer a feed video opens in lets its player go as its screen stops. That release is
 * held too ({@link #releasing}) and goes through when the session ends: when you come back, before
 * the screen's fragments start and take a player again, so Instagram sees the same order it would
 * have without this. When another video takes over from the held one, its release goes through
 * then, so the old player gives its decoder back while the new one plays on.
 *
 * <p>A video story that ends in the background moves on to the next story, which takes over as the
 * held player: the slide between them is a spring that only moves on the screen's frames, and
 * {@link SpringSteps} gives it those while Instagram is hidden ({@link #springMoving}). The next
 * story's player then parks its start, since the hidden screen has it paused. Every story player
 * bound while hidden does, the ones got ready ahead included, so {@link #storyPrepared} queues them
 * and {@link #nextStory} plays one at a time, the first ready first, once Instagram moved off the
 * held one or it stalled; the service's check retries, since a story ready early gets no second
 * callback. The story it moves on from is paused, so a held one that was only loading doesn't play
 * beside it. A session paused on purpose plays none.
 *
 * <p>Everything else stops it as before. Coming back to the same screen ends the session with the
 * video still playing. Another Instagram screen opening over it pauses it, as Instagram would have.
 * Instagram's own handling of a lost audio focus still pauses or mutes it (a mute pauses it here,
 * and the sound coming back within {@link #MUTE_RESUME_MS} plays it again). Picture in picture, the
 * screen closing, Instagram being swiped away, and Stop in the notification all end it. A session
 * left paused for {@link #PAUSED_LIMIT_MS} ends by itself.
 *
 * <p>The patch writes the bodies of {@link #playing}, {@link #pause}, {@link #play},
 * {@link #release} and {@link #engine}. Every hook runs on the main thread, and none of them ever
 * throws into Instagram.
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

    /** How long a story played to take over has to start before another waiting one may. */
    static final long TAKEOVER_SETTLE_MS = 3_000;

    /**
     * A held story that hasn't played this long counts as one the viewer moved off, even with no
     * pause from Instagram, so a waiting story takes over at the service's check.
     */
    static final long STALLED_MS = 6_000;

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

        default void release(Object player, @Nullable String reason) {
        }
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
    /** True while this class pauses or releases the held player itself, so that call goes through. */
    private static boolean passing;
    /**
     * True once the notification's Pause or a lost audio focus paused the held video, until Play or
     * Instagram starts it again, so a story that gets ready meanwhile stays paused too.
     */
    private static boolean pausedOnPurpose;
    /**
     * Whether the service's last check saw the held video playing. Playing on two checks in a row
     * ends a Pause Instagram overrode, as when the audio focus came back, which its start may read
     * as playing only after its own check.
     */
    private static boolean playedOnLastTick;
    /**
     * The story players whose start is parked in this session and that haven't played since, the
     * first to get ready first. Every story player Instagram binds while hidden parks its start, the
     * ones it gets ready ahead included, so the first of these is the next story in the viewer's
     * order, and a story ready while the held one still plays waits here for its turn.
     */
    private static final ArrayList<WeakReference<Object>> waiting = new ArrayList<>();
    /**
     * The story player last played to take over from the held one, and when, so no other takes over
     * until it plays and the hold moves to it, or it hasn't started within {@link #TAKEOVER_SETTLE_MS}.
     */
    @Nullable
    private static WeakReference<Object> taker;
    private static long takenAt = NONE;
    /**
     * True once Instagram asked the held player to pause or stop in the background since it last
     * started: the viewer moved off its story. Until then a held story that isn't playing may only be
     * loading, so the waiting stories are left alone.
     */
    private static boolean movedOff;
    /**
     * The story players played to take over that gave way to another before they started. One that
     * starts late while another story plays is paused, so two never play at once.
     */
    private static final ArrayList<WeakReference<Object>> gaveWay = new ArrayList<>();
    /**
     * The story that was taking over when the held one played again. It's paused and waits first in
     * line, and a start it still had coming while the held one plays is paused back into line.
     */
    @Nullable
    private static WeakReference<Object> yielded;
    /**
     * True while the screen a session plays from stops: its fragments stop after the stop callback,
     * and the pause or stop they give the held player isn't the viewer moving off its story.
     */
    private static boolean screenStopping;
    /**
     * The releases Instagram asked for on the held player. They go through when the session ends, or
     * on their own player as another video takes over from it, so they only ever hold the held one's.
     */
    private static final ArrayList<Release> releases = new ArrayList<>();
    /**
     * The releases an ending session still lets through, each taken out as it goes. Instagram can
     * release a player from inside the pause the session passes on, or while another of these goes
     * through; that release goes through then and drops its entry here, so no player is released a
     * second time.
     */
    private static final ArrayList<Release> ending = new ArrayList<>();
    /**
     * Each player released while a session ends: the releases it lets through and any Instagram asks
     * for meanwhile, as one player's release lets another go along. Only kept while {@link #endingNow}.
     */
    private static final ArrayList<Object> releasedWhileEnding = new ArrayList<>();
    private static boolean endingNow;
    private static boolean watching;

    private BackgroundPlay() {
    }

    // ---- Stubs the patch fills ----

    /** Whether [player], an IgVideoPlayerImpl or a story player, is playing. The patch writes the body; false unpatched. */
    public static boolean playing(Object player) {
        return false;
    }

    /** Pauses [player] with {@link #PAUSE_REASON}. The patch writes the body. */
    public static void pause(Object player) {
    }

    /** Plays [player] again. The patch writes the body. */
    public static void play(Object player) {
    }

    /** Releases [player], an IgVideoPlayerImpl, with Instagram's own [reason]. The patch writes the body. */
    public static void release(Object player, @Nullable String reason) {
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

    /**
     * At the top of IgFragmentActivity's onUserLeaveHint: home, recents, or a screen of another app
     * opening. A story that goes to picture in picture ({@link StoriesPip}) keeps nothing else.
     */
    public static void leaving(Activity activity) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (StoriesPip.leaving(activity)) return;
            arm(activity, "you left Instagram");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "leave hint", t);
        }
    }

    /**
     * At the top of IgVideoPlayerImpl's playInternal, after Tap to play's guard, and of the story
     * player's start, each time [player] starts playing.
     */
    public static void started(Object player) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (player == null) return;
            boolean ours;
            WeakReference<Object> place;
            synchronized (LOCK) {
                remember(player);
                ours = phase != Phase.IDLE;
                // A story keeps its place in line until its start is read: the held one's check,
                // posted before it, still sees it waiting and plays it first. Only that place goes
                // then, so a start it parks again meanwhile still waits.
                place = ours ? placeOf(player) : null;
                if (!ours) unwait(player);
            }
            // Read once Instagram's start is done: a start another patch holds back plays nothing.
            if (ours) Utils.runOnMainThread(() -> afterStart(player, place, true));
            SettingsPause.started(player);
            ReelsPipButtons.started(player);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player start", t);
        }
    }

    /**
     * Right before the story player's prepared callback returns, once its video is ready, with
     * [parked], the start it left for its resume because the player is paused, or null when it
     * started. In a session Instagram's screen is hidden, so the story the viewer moved on to after
     * the held one ended is paused that way; it plays through the play stub, which resumes it. One
     * ready as Instagram leaves, before its screen stopped, waits its turn the same way.
     */
    public static void storyPrepared(Object player, @Nullable Object parked) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (player == null || parked == null) return;
            synchronized (LOCK) {
                if (phase == Phase.IDLE) return;
                unwait(player);
                waiting.add(new WeakReference<>(player));
            }
            // Posted, so the resume runs on the main thread once the callback is done.
            Utils.runOnMainThread(BackgroundPlay::nextStory);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "story prepared", t);
        }
    }

    /**
     * Plays the story whose turn it is among the {@link #waiting} ones, unless something paused the
     * session on purpose (the notification's Pause, a lost audio focus, or a mute): the held player's
     * own parked start, or, once Instagram moved off the held story ({@link #movedOff}) or it hasn't
     * played for {@link #STALLED_MS}, the first waiting player of its kind. Until then the held one
     * may only be loading. Only one takes over at a time; the rest wait for the hold to move to it,
     * and the next of them takes over once that story stops in turn. One that never started gives
     * way after {@link #TAKEOVER_SETTLE_MS} and is paused. True when it played one.
     */
    private static boolean nextStory() {
        return nextStory(false);
    }

    /** {@link #nextStory()}, with [tapped] true for the notification's Play, which Tap to play lets through. */
    private static boolean nextStory(boolean tapped) {
        Object current;
        Object claimed;
        boolean offStory;
        List<Object> ready = new ArrayList<>();
        long now = SystemClock.uptimeMillis();
        synchronized (LOCK) {
            if (phase != Phase.BACKGROUND || pausedOnPurpose || mutedAt != NONE) return false;
            current = heldLocked();
            offStory = movedOff || (pausedSince != NONE && now - pausedSince >= STALLED_MS);
            for (Iterator<WeakReference<Object>> it = waiting.iterator(); it.hasNext(); ) {
                Object player = it.next().get();
                if (player == null) it.remove();
                else ready.add(player);
            }
            claimed = taker != null ? taker.get() : null;
        }
        if (current == null || !on()) return false;
        if (claimed != null && claimed != current) {
            // It plays, so the hold moves to it if its own start didn't move it.
            if (isPlaying(claimed)) {
                afterStart(claimed);
                return false;
            }
            // One story is taking over already: none plays beside it, the held one's own start
            // included, until it's had its time to start.
            if (now - takenAt < TAKEOVER_SETTLE_MS) return false;
        }
        if (ready.isEmpty()) return false;
        for (Object player : ready) {
            // A waiting story whose start played is read once that start is done, and takes over
            // then: none plays beside it.
            if (player != current && isPlaying(player)) return false;
        }
        Object next = null;
        if (ready.contains(current)) {
            if (!isPlaying(current)) next = current;
        } else if (offStory && !isPlaying(current)) {
            for (Object player : ready) {
                if (player.getClass() == current.getClass() && !isPlaying(player)) {
                    next = player;
                    break;
                }
            }
        }
        if (next == null) return false;
        Object gaveUp;
        synchronized (LOCK) {
            if (phase != Phase.BACKGROUND || heldLocked() != current || pausedOnPurpose || mutedAt != NONE) return false;
            unwait(next);
            remove(gaveWay, next);
            if (yielded != null && yielded.get() == next) yielded = null;
            gaveUp = taker != null ? taker.get() : null;
            if (gaveUp == next || gaveUp == current) gaveUp = null;
            if (gaveUp != null) gaveWay.add(new WeakReference<>(gaveUp));
            taker = next != current ? new WeakReference<>(next) : null;
            takenAt = next != current ? now : NONE;
        }
        // The one that never started is paused, so a start it still has coming parks again.
        if (gaveUp != null) pauseOther(gaveUp);
        HookStatus.counted(FamilyNames.BACKGROUND_PLAY, "stories started in the background");
        log(() -> "the next story was ready in the background, so it plays");
        // Tap to play holds back a start nobody tapped, and Play is a tap.
        if (tapped) TapToPlay.tappedFromNotification(engineOf(next));
        playThrough(next);
        return true;
    }

    /**
     * Puts [player], the story taking over, first in line again, because the held one plays: it's
     * no longer taking over, and its own turn comes once the held one stops. Under {@link #LOCK}.
     */
    private static void yieldLocked(Object player) {
        unwait(player);
        waiting.add(0, new WeakReference<>(player));
        remove(gaveWay, player);
        if (taker != null && taker.get() == player) {
            taker = null;
            takenAt = NONE;
        }
        yielded = new WeakReference<>(player);
    }

    /**
     * Marks the session paused on purpose. The service's next check that sees the held video playing
     * still counts as a first one, since a pause can land a moment late. Under {@link #LOCK}.
     */
    private static void pauseOnPurposeLocked() {
        pausedOnPurpose = true;
        playedOnLastTick = false;
    }

    /** Forgets the waiting stories and the one taking over. Under {@link #LOCK}. */
    private static void clearStoriesLocked() {
        waiting.clear();
        gaveWay.clear();
        taker = null;
        takenAt = NONE;
        yielded = null;
        movedOff = false;
        screenStopping = false;
    }

    /**
     * Takes [player] and any player gone out of {@link #waiting}, and answers where in line [player]
     * was, or -1. Under {@link #LOCK}.
     */
    private static int unwait(Object player) {
        int line = -1;
        int index = 0;
        for (Iterator<WeakReference<Object>> it = waiting.iterator(); it.hasNext(); ) {
            Object seen = it.next().get();
            if (seen != null && seen != player) {
                index++;
                continue;
            }
            if (seen == player && line < 0) line = index;
            it.remove();
        }
        return line;
    }

    /** [player]'s place in {@link #waiting}, or null. Under {@link #LOCK}. */
    @Nullable
    private static WeakReference<Object> placeOf(Object player) {
        for (WeakReference<Object> reference : waiting) {
            if (reference.get() == player) return reference;
        }
        return null;
    }

    /**
     * Takes [place], one of the {@link #waiting} entries, out of line, and answers where it was
     * among the players still there, or -1 when it's gone already. Under {@link #LOCK}.
     */
    private static int takeOut(@Nullable WeakReference<Object> place) {
        if (place == null) return -1;
        int index = 0;
        for (Iterator<WeakReference<Object>> it = waiting.iterator(); it.hasNext(); ) {
            WeakReference<Object> reference = it.next();
            if (reference == place) {
                it.remove();
                return index;
            }
            if (reference.get() != null) index++;
        }
        return -1;
    }

    /**
     * Puts [player] back in {@link #waiting} at [line], or at its end when the line got shorter: a
     * start that played nothing, or was paused, doesn't take a story out of its turn. Under {@link #LOCK}.
     */
    private static void backInLine(Object player, int line) {
        unwait(player);
        waiting.add(Math.min(Math.max(0, line), waiting.size()), new WeakReference<>(player));
    }

    /** Takes [player] and any player gone out of [list]. Under {@link #LOCK}. */
    private static void remove(List<WeakReference<Object>> list, Object player) {
        for (Iterator<WeakReference<Object>> it = list.iterator(); it.hasNext(); ) {
            Object seen = it.next().get();
            if (seen == null || seen == player) it.remove();
        }
    }

    /** Whether [list] holds [player]. Under {@link #LOCK}. */
    private static boolean holds(List<WeakReference<Object>> list, Object player) {
        for (WeakReference<Object> reference : list) {
            if (reference.get() == player) return true;
        }
        return false;
    }

    /**
     * At the top of the spring system's activate, which runs each time one of Instagram's springs
     * sets off, with [frames], that system's frame callback. In a session it's stepped while
     * Instagram is hidden ({@link SpringSteps}), so the story viewer's slide to the next story ends
     * and that story plays. With no session it's only noted, so a slide still moving as you leave is
     * stepped too.
     */
    public static void springMoving(@Nullable Object frames) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (!(frames instanceof Choreographer.FrameCallback)) return;
            Choreographer.FrameCallback callback = (Choreographer.FrameCallback) frames;
            if (!keepsSprings()) {
                // Instagram's springs set off on the main thread; one that doesn't isn't noted.
                if (phase() == Phase.IDLE && on() && Utils.isCurrentlyOnMainThread()) SpringSteps.saw(callback);
                return;
            }
            Utils.runOnMainThreadNowOrLater(() -> {
                if (!keepsSprings()) return;
                SpringSteps.keep(callback);
                if (active()) HookStatus.counted(FamilyNames.BACKGROUND_PLAY, "springs stepped in the background");
            });
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "spring", t);
        }
    }

    /** At the top of IgVideoPlayerImpl's and the story player's setVolume, with the volume it's asked for. */
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
                if (muted) {
                    mutedAt = now;
                } else if (volume > 0f && mutedAt != NONE) {
                    // The sound came back too late to play it again: it stays paused, as after
                    // the notification's Pause, so no waiting story plays either. The stall clock
                    // starts over, so the story playing again isn't taken for one that stalled.
                    if (!unmuted) pauseOnPurposeLocked();
                    mutedAt = NONE;
                    pausedSince = NONE;
                }
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
     * At the top of IgVideoPlayerImpl's pause and stop, after Resume long videos' hook, and of the
     * story player's pause, with Instagram's reason. True, and the pause or stop returns at once,
     * only for the player held as you left, while the switch is on. A pause for the audio focus
     * always goes through. A pause that ends the session also returns at once when that end let
     * the player's held release through, since the player is gone by then.
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
            if (!on()) return endBeforePause(player, "the switch is off");
            if (reason != null && reason.toLowerCase(Locale.ROOT).contains("focus")) {
                synchronized (LOCK) {
                    pauseOnPurposeLocked();
                }
                log(() -> "let a pause for the audio focus through (" + reason + ")");
                return false;
            }
            if (screen != null && inPictureInPicture(screen)) {
                return endBeforePause(player, "Instagram went to picture in picture");
            }
            boolean moved;
            synchronized (LOCK) {
                // The pauses as you leave and as the screen stops aren't the viewer moving on; one
                // once the screen is hidden is. Its own parked start is then a story it moved off.
                moved = phase == Phase.BACKGROUND && player == heldLocked() && !screenStopping && !movedOff;
                if (moved) {
                    movedOff = true;
                    unwait(player);
                }
            }
            // The story the viewer moved on to may be waiting already, with no callback to come.
            if (moved) Utils.runOnMainThread(BackgroundPlay::nextStory);
            HookStatus.counted(FamilyNames.BACKGROUND_PLAY, KEPT);
            log(() -> "kept the video playing through Instagram's " + (reason == null ? "pause" : "\"" + reason + "\""));
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return false;
        }
    }

    /**
     * At the top of IgVideoPlayerImpl's release, with Instagram's reason. True, and the release
     * returns at once, only for the held player while the switch is on: the Reels viewer lets its
     * player go as its screen stops, which used to end the session there (#497). The release goes
     * through when the session ends, or on this player as soon as another video takes over from it.
     * A release asked again for a player that was held before goes through now.
     */
    public static boolean releasing(Object player, @Nullable String reason) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            Activity screen;
            synchronized (LOCK) {
                if (player == null) return false;
                // A release going through now, ours, one asked while ours runs or one asked while a
                // session ends, is the only one that player gets, so none waits for the session's end.
                if (phase == Phase.IDLE || passing || player != heldLocked()) {
                    forget(player);
                    if (endingNow) releasedWhileEnding.add(player);
                    return false;
                }
                screen = leavingScreen != null ? leavingScreen.get() : null;
            }
            if (!on()) return endLettingGo(player, "the switch is off");
            if (screen != null && inPictureInPicture(screen)) {
                return endLettingGo(player, "Instagram went to picture in picture");
            }
            synchronized (LOCK) {
                if (phase == Phase.IDLE || player != heldLocked()) return false;
                forget(player);
                releases.add(new Release(player, reason));
            }
            HookStatus.counted(FamilyNames.BACKGROUND_PLAY, KEPT);
            log(() -> "kept the video playing through Instagram's release" + (reason == null ? "" : " (\"" + reason + "\")"));
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "player release", t);
            return false;
        }
    }

    // ---- The notification's buttons, called by the service on the main thread ----

    /** Pauses the held video, and the story taking over from it if one is on its way. */
    static void pauseFromNotification() {
        Object player = held();
        if (player == null) return;
        Object claimed;
        synchronized (LOCK) {
            mutedAt = NONE;
            pauseOnPurposeLocked();
            claimed = taker != null ? taker.get() : null;
        }
        pauseThrough(player);
        if (claimed != null && claimed != player) pauseOther(claimed);
    }

    /**
     * Plays the story that was taking over when Pause came, the next waiting one when Instagram had
     * moved off the held story, or else the held video.
     */
    static void playFromNotification() {
        Object player = held();
        if (player == null) return;
        Object claimed;
        boolean moved;
        synchronized (LOCK) {
            // A story's lost audio focus mutes it rather than pausing it, and Play ends either.
            mutedAt = NONE;
            pausedOnPurpose = false;
            // It gets its time to start before it counts as stalled.
            pausedSince = NONE;
            claimed = taker != null ? taker.get() : null;
            if (claimed == player) claimed = null;
            if (claimed != null) takenAt = SystemClock.uptimeMillis();
            moved = movedOff;
        }
        if (claimed == null && moved && nextStory(true)) return;
        Object playing = claimed != null ? claimed : player;
        // Tap to play holds back a start nobody tapped, and the button is a tap.
        TapToPlay.tappedFromNotification(engineOf(playing));
        playThrough(playing);
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
            if (playing) {
                pausedSince = NONE;
                if (playedOnLastTick) pausedOnPurpose = false;
            } else if (pausedSince == NONE) {
                pausedSince = now;
            }
            playedOnLastTick = playing;
            tooLong = !playing && now - pausedSince >= PAUSED_LIMIT_MS;
        }
        if (tooLong) {
            end(false, "it was paused for " + PAUSED_LIMIT_MS / 60_000 + " minutes");
            return;
        }
        // A story ready while the held one played gets no second callback once its turn comes.
        if (!playing) nextStory();
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

    /** Whether a spring that sets off now gets stepped: a session is under way and the switch is on. */
    static boolean keepsSprings() {
        synchronized (LOCK) {
            if (phase == Phase.IDLE) return false;
        }
        return on();
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
            clearStoriesLocked();
            mutedAt = NONE;
            pausedSince = NONE;
        }
        log(() -> why + ", so the video playing with sound is kept going");
        // A slide set off just before still moves, and is stepped once Instagram is hidden.
        Utils.runOnMainThreadNowOrLater(SpringSteps::carryOn);
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
            screenStopping = true;
        }
        // The screen's fragments stop once this callback returns, in the same pass of the main thread.
        Utils.runOnMainThread(() -> {
            synchronized (LOCK) {
                screenStopping = false;
            }
        });
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

    /**
     * The video's screen starting again. With a release held, the session ends here, so the
     * release goes through before the screen's fragments start and take a player back.
     */
    static void restarted(Activity screen) {
        boolean ours;
        synchronized (LOCK) {
            ours = phase != Phase.IDLE && !releases.isEmpty() && leavingScreen != null && leavingScreen.get() == screen;
        }
        if (ours) end(false, "Instagram came back");
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

    /**
     * Ends the session, pausing the held video and any story taking over from it first when
     * [pause], lets each release Instagram asked for go through on its own player, and takes the
     * notification down.
     */
    static void end(boolean pause, String why) {
        finish(pause, why);
    }

    /** Ends the session as {@link #end} does, and returns each player it released meanwhile. */
    private static List<Object> finish(boolean pause, String why) {
        Object player;
        Object claimed;
        boolean wasBackground;
        synchronized (LOCK) {
            if (phase == Phase.IDLE) return new ArrayList<>();
            wasBackground = phase == Phase.BACKGROUND;
            player = heldLocked();
            claimed = taker != null ? taker.get() : null;
            phase = Phase.IDLE;
            held = null;
            leavingScreen = null;
            clearStoriesLocked();
            mutedAt = NONE;
            pausedSince = NONE;
            pausedOnPurpose = false;
            playedOnLastTick = false;
            ending.addAll(releases);
            releases.clear();
            releasedWhileEnding.clear();
            endingNow = true;
        }
        boolean letGo = false;
        List<Object> released;
        try {
            Utils.runOnMainThreadNowOrLater(SpringSteps::stop);
            if (pause && player != null) pauseThrough(player);
            if (pause && claimed != null && claimed != player) pauseOther(claimed);
            while (true) {
                Release next;
                synchronized (LOCK) {
                    if (ending.isEmpty()) break;
                    next = ending.remove(0);
                    releasedWhileEnding.add(next.player);
                }
                releaseThrough(next.player, next.reason);
                letGo = true;
            }
        } finally {
            synchronized (LOCK) {
                endingNow = false;
                released = new ArrayList<>(releasedWhileEnding);
                releasedWhileEnding.clear();
            }
        }
        if (wasBackground && !serviceForTests) BackgroundPlayService.finish();
        boolean releaseWent = letGo;
        log(() -> "background playback ended: " + why + (releaseWent ? ", and Instagram's release went through" : ""));
        return released;
    }

    /**
     * Ends the session as Instagram releases [player], dropping the release held for it earlier so
     * the end doesn't release it as well. True when the end released it all the same, as another
     * player's release let it go along: Instagram's own release then returns at once.
     */
    private static boolean endLettingGo(Object player, String why) {
        synchronized (LOCK) {
            forget(player);
        }
        return releasedIn(finish(false, why), player);
    }

    /**
     * Ends the session as Instagram pauses [player]. True when the end released it, through the
     * release held for it or along with another: the player is gone then, so its pause returns at once.
     */
    private static boolean endBeforePause(Object player, String why) {
        return releasedIn(finish(false, why), player);
    }

    private static boolean releasedIn(List<Object> released, Object player) {
        for (Object gone : released) {
            if (gone == player) return true;
        }
        return false;
    }

    /** Drops the release held for [player], if any, in the session and in one that is ending. Under LOCK. */
    private static void forget(Object player) {
        drop(releases, player);
        drop(ending, player);
    }

    private static void drop(List<Release> list, Object player) {
        for (Iterator<Release> it = list.iterator(); it.hasNext(); ) {
            if (it.next().player == player) it.remove();
        }
    }

    /** A release Instagram asked for during a session, kept until it ends. */
    private static final class Release {
        final Object player;
        @Nullable
        final String reason;

        Release(Object player, @Nullable String reason) {
            this.player = player;
            this.reason = reason;
        }
    }

    // ---- Helpers ----

    private static void afterStart(Object player) {
        afterStart(player, null, false);
    }

    /**
     * What a start of [player] means for the session, once Instagram's start is done. With
     * [fromStart], the start hook's own read, which takes [place], where [player] waited as it
     * started, out of line and keeps where that was.
     */
    private static void afterStart(Object player, @Nullable WeakReference<Object> place, boolean fromStart) {
        Object current;
        Object claimed;
        Activity screen;
        boolean onPurpose;
        boolean gaveUp;
        boolean wasYielded;
        int line;
        synchronized (LOCK) {
            line = fromStart ? takeOut(place) : -1;
            if (phase != Phase.BACKGROUND) return;
            current = heldLocked();
            claimed = taker != null ? taker.get() : null;
            screen = leavingScreen != null ? leavingScreen.get() : null;
            // A mute pauses the held video too, so nothing takes over from it meanwhile.
            onPurpose = pausedOnPurpose || mutedAt != NONE;
            gaveUp = holds(gaveWay, player);
            wasYielded = yielded != null && yielded.get() == player;
        }
        boolean plays = isPlaying(player);
        // A story taking over, or one sent back first in line, keeps the front of the line.
        int turn = player == claimed || wasYielded ? 0 : line;
        if (player != current && plays) {
            boolean currentPlays = current != null && isPlaying(current);
            boolean anotherPlays = currentPlays || (claimed != null && claimed != player && isPlaying(claimed));
            if (currentPlays && !onPurpose && (player == claimed || wasYielded)) {
                // The held story plays again, so the one taking over from it waits its turn.
                synchronized (LOCK) {
                    if (phase != Phase.BACKGROUND || heldLocked() != current) return;
                    yieldLocked(player);
                }
                log(() -> "a story started beside the held one playing again, so it waits its turn");
                pauseOther(player);
                return;
            }
            if (onPurpose || (gaveUp && anotherPlays)) {
                // A start that came after Pause or during a mute, or late from a story that gave
                // way to the one playing: one story at a time, and none while paused on purpose.
                // A story that was waiting keeps its turn.
                synchronized (LOCK) {
                    if (phase == Phase.BACKGROUND && turn >= 0 && player != heldLocked()) backInLine(player, turn);
                }
                log(() -> "a video started in the background after Pause or beside the one playing, so it's paused");
                pauseOther(player);
                return;
            }
            if (!currentPlays) takeOver(player, current, screen);
        } else if (player != current && player != claimed && turn >= 0) {
            // A start another patch held back plays nothing, so the story still waits its turn. The
            // one taking over has its own time to start, and gives way after it.
            synchronized (LOCK) {
                if (phase == Phase.BACKGROUND && player != heldLocked()) backInLine(player, turn);
            }
        }
        Object stale = null;
        synchronized (LOCK) {
            if (player == heldLocked()) {
                pausedSince = NONE;
                // A start another patch held back plays nothing, so a Pause still stands.
                if (plays) pausedOnPurpose = false;
                movedOff = false;
                Object pending = taker != null ? taker.get() : null;
                if (pending == player) {
                    taker = null;
                    takenAt = NONE;
                } else if (pending != null && plays) {
                    // It played again before the story taking over from it started: that one waits.
                    // A start that played nothing leaves the story taking over to go on.
                    yieldLocked(pending);
                    stale = pending;
                }
            }
        }
        if (stale != null) {
            log(() -> "the held story played again, so the one taking over waits its turn");
            pauseOther(stale);
        }
        BackgroundPlayService.refresh();
    }

    /**
     * Moves the hold from [current], which stopped, to [player], which plays: the reel or story moved
     * on by itself. The old one is paused, unless a release held for it lets it go now, so one that
     * was only loading doesn't play on beside it, and a story still taking over gives way and is
     * paused too. Neither plays again from the waiting stories.
     */
    private static void takeOver(Object player, @Nullable Object current, @Nullable Activity screen) {
        List<Release> replaced = new ArrayList<>();
        boolean pauseOld;
        Object gaveUp;
        synchronized (LOCK) {
            if (phase != Phase.BACKGROUND || screen == null || heldLocked() != current) return;
            held = new WeakReference<>(player);
            boolean oldReleased = false;
            for (Iterator<Release> it = releases.iterator(); it.hasNext(); ) {
                Release release = it.next();
                if (release.player != player) {
                    replaced.add(release);
                    if (release.player == current) oldReleased = true;
                    it.remove();
                }
            }
            pauseOld = current != null && !oldReleased;
            unwait(player);
            remove(gaveWay, player);
            if (current != null) {
                unwait(current);
                // A start the old one still had coming, as one only slow to load, is paused while another plays.
                remove(gaveWay, current);
                gaveWay.add(new WeakReference<>(current));
            }
            gaveUp = taker != null ? taker.get() : null;
            if (gaveUp == player || gaveUp == current) gaveUp = null;
            if (gaveUp != null) gaveWay.add(new WeakReference<>(gaveUp));
            taker = null;
            takenAt = NONE;
            yielded = null;
            // A mute was the old video's.
            mutedAt = NONE;
        }
        log(() -> "another video took over in the background");
        // Not passing: neither is the held player any more, and a pause or release one of them
        // brings on the new one stays held.
        if (pauseOld) pauseOther(current);
        if (gaveUp != null) pauseOther(gaveUp);
        // Those releases were held only to keep the old one playing, so they go now and its
        // decoder with them.
        for (Release release : replaced) releaseReplaced(release.player, release.reason);
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

    /**
     * Pauses [player], one that isn't the held player, through Instagram's own pause. Not passing,
     * so a pause or release it brings on the held one stays held.
     */
    private static void pauseOther(Object player) {
        try {
            Control control = controlForTests;
            if (control != null) control.pause(player);
            else pause(player);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "other pause", t);
        }
    }

    private static void releaseThrough(Object player, @Nullable String reason) {
        synchronized (LOCK) {
            passing = true;
        }
        try {
            Control control = controlForTests;
            if (control != null) control.release(player, reason);
            else release(player, reason);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "release", t);
        } finally {
            synchronized (LOCK) {
                passing = false;
            }
        }
    }

    /**
     * Lets a held release go on [player] once another video holds the session. It isn't the held
     * player any more, so its own release and stop go through without {@link #passing}.
     */
    private static void releaseReplaced(Object player, @Nullable String reason) {
        try {
            Control control = controlForTests;
            if (control != null) control.release(player, reason);
            else release(player, reason);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "replaced player release", t);
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

    /** The recently started players, newest first, for {@link SettingsPause}. */
    static List<Object> recentPlayers() {
        List<Object> players = new ArrayList<>();
        synchronized (LOCK) {
            for (WeakReference<Object> ref : recent) {
                Object player = ref.get();
                if (player != null) players.add(player);
            }
        }
        return players;
    }

    static boolean playingNow(Object player) {
        return isPlaying(player);
    }

    static void pauseUnderSettings(Object player) {
        pauseOther(player);
    }

    static void playAgain(Object player) {
        playThrough(player);
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
            clearStoriesLocked();
            mutedAt = NONE;
            pausedSince = NONE;
            passing = false;
            pausedOnPurpose = false;
            playedOnLastTick = false;
            releases.clear();
            ending.clear();
            releasedWhileEnding.clear();
            endingNow = false;
        }
        SpringSteps.resetForTests();
        SettingsPause.resetForTests();
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
                StoriesPip.screenPaused(activity);
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
            try {
                StoriesPip.screenStopped(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "story screen stop", t);
            }
        }

        @Override
        public void onActivityResumed(Activity activity) {
            try {
                resumed(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "activity resume", t);
            }
            try {
                StoriesPip.screenResumed(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "story screen resume", t);
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            try {
                destroyed(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "activity close", t);
            }
            try {
                StoriesPip.screenDestroyed(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "story screen close", t);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
            // Runs inside the activity's onStart, before it starts its fragments.
            try {
                restarted(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "activity start", t);
            }
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }
    }
}
