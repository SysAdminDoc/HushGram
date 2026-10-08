/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Resume long videos: a video longer than {@link #MIN_DURATION_MS} that was left partway picks up
 * where it was left, once, the next time a player starts it.
 *
 * <p>Instagram 449 plays feed videos and reels through IgVideoPlayerImpl, and the patch tells this
 * class about five moments of it:
 *
 * <ul>
 *   <li>{@link #started}, first thing in its playback-started callback, which runs each time the
 *       video starts playing. The first start of a video in a player looks up the point saved for
 *       it and, when there is one, seeks there once Instagram's start is done.</li>
 *   <li>{@link #stopped}, first thing in its pause and its stop, which save where the video is, or
 *       forget its point near the end. Its release stops it first. A pause Instagram makes on its
 *       way through a seek, a pinch or a replay saves nothing, since the video plays on.</li>
 *   <li>{@link #rebound}, first thing where it's given a video, which saves the one it had and
 *       starts the player over.</li>
 *   <li>{@link #ended}, first thing where the video plays to its end or loops, which forgets its
 *       point.</li>
 *   <li>{@link #seeking}, first thing in its seek, so a seek someone else makes before the resume
 *       (your drag of the scrubber, or Instagram carrying a position over) wins.</li>
 * </ul>
 *
 * <p>The patch also tells it when Instagram lets an account's session go ({@link #sessionEnded}),
 * at an account switch and when an account signs out or is removed from the phone. That account's
 * resumes still waiting to seek are dropped, and a signed-out account's points go with it.
 *
 * <p>A point is saved only for a video of at least {@link #MIN_DURATION_MS} that was stopped at
 * least {@link #MIN_SAVED_MS} in and more than {@link #END_MARGIN_MS} before its end. A stop
 * closer to the end forgets the point. Instagram shares nearly every video as a reel, so a reel
 * that long resumes too. Live videos and ads are left alone, and so is a start already past
 * {@link #START_WINDOW_MS}, which is where Instagram put it itself. The points are kept in
 * {@link ResumePoints}.
 *
 * <p>Off, paused, before the settings are ready, or when anything here throws, the player plays as
 * Instagram starts it: nothing is saved, nothing is looked up and nothing seeks. The report counts
 * what happened by kind, and Debug logging writes positions and durations, never which video.
 */
public final class ResumePlayback {
    /** A video shorter than this is never saved or resumed: two minutes. */
    static final int MIN_DURATION_MS = 120_000;

    /** A stop earlier than this says nothing about where the video was left. */
    static final int MIN_SAVED_MS = 15_000;

    /** A stop this close to the end, or past it, means the video was finished, and forgets it. */
    static final int END_MARGIN_MS = 10_000;

    /** A start already past this was positioned by Instagram, so it isn't moved. */
    static final int START_WINDOW_MS = 3_000;

    /**
     * How long a player's first start keeps its place through a new bind. A bind right after the
     * start it belongs to isn't a new video.
     */
    static final long BIND_GRACE_MS = 2_000;

    /** The report's line. */
    static final String ROUTE = "Resume long videos";

    // What the report counts, by kind. None of them says which video.
    static final String RESUMED = "resumed";
    static final String SAVED = "point saved";
    static final String CLEARED = "point cleared at the end";
    static final String NOTHING_SAVED = "no point yet";
    static final String SHORT = "under two minutes";
    static final String LIVE = "live";
    static final String AD = "ad";
    static final String SEEKED_FIRST = "seek before the resume";
    static final String PAST_START = "started past the start";
    static final String MOVED_ON = "moved on before the resume";
    static final String SESSION_ENDED = "an account's session ended";
    static final String ACCOUNT_FORGOTTEN = "a signed-out account's points forgotten";

    /** Instagram's ProductType constants this leaves alone, by the names Instagram keeps. */
    static final String LIVE_PRODUCT = "LIVE";
    static final String AD_PRODUCT = "AD";

    /** What {@link #videoSource} answers until the patch fills it in. */
    static final Object NOT_PATCHED = new Object();

    /** What {@link #position} and {@link #duration} answer until the patch fills them in. */
    static final int NOT_PATCHED_TIME = Integer.MIN_VALUE;

    private static final String SOURCE = "ResumePlayback";
    private static final String FAMILY = FamilyNames.RESUME_LONG_VIDEOS;

    /** What this class reads from a player and does to it. {@link #PATCHED} is the patch's; tests stand in. */
    interface Player {
        /** The player's position in milliseconds, or {@link #NOT_PATCHED_TIME}. */
        int position(Object player);

        /** The length of the player's video in milliseconds, 0 when unknown, or {@link #NOT_PATCHED_TIME}. */
        int duration(Object player);

        /** What the rule needs of the player's video, null when it has none, or {@link Facts#NOT_PATCHED}. */
        @Nullable
        Facts facts(Object player);

        /** Seeks the player to [positionMs]. False when the patch didn't fill it in. */
        boolean seek(Object player, int positionMs);
    }

    static final Player PATCHED = new Player() {
        @Override
        public int position(Object player) {
            return ResumePlayback.position(player);
        }

        @Override
        public int duration(Object player) {
            return ResumePlayback.duration(player);
        }

        @Override
        public Facts facts(Object player) {
            Object source = videoSource(player);
            if (source == NOT_PATCHED) return Facts.NOT_PATCHED;
            if (source == null) return null;
            return factsOf(ownedKey(owner(playerSession(player), sessions), videoId(source)), productType(source),
                sponsored(source));
        }

        @Override
        public boolean seek(Object player, int positionMs) {
            return seekPlayer(player, positionMs, false, false);
        }
    };

    /** Runs the resume once Instagram's start is done. {@link #ON_MAIN_LOOPER} is the app's; tests stand in. */
    interface Later {
        /** False when it can't run [task]. */
        boolean post(Runnable task);
    }

    /** On the main thread, which Instagram's player runs on. */
    static final Later ON_MAIN_LOOPER = task -> new Handler(Looper.getMainLooper()).post(task);

    static volatile Player access = PATCHED;
    static volatile Later later = ON_MAIN_LOOPER;
    @Nullable
    static volatile ResumePoints pointsForTests;

    private static final Players PLAYERS = new Players();
    private static final Object POINTS_LOCK = new Object();
    private static final Object AGED = new Object();
    /** Null until submitted; the request owns completion, including across test process resets. */
    private static final AtomicReference<Object> aging = new AtomicReference<>();
    @Nullable
    private static ResumePoints points;
    /** The only Undo copy is in this process. Every access is under POINTS_LOCK. */
    @Nullable private static Map<String, ResumePoints.Point> clearedPoints;
    @Nullable private static ResumePoints clearedStore;
    private static long undoUntil;
    private static long undoId;
    public static final long UNDO_WINDOW_MS = 10_000;

    private ResumePlayback() {
    }

    /** Called on a worker by Settings, independently of the playback switch or Pause. */
    public static void clearHistory() {
        synchronized (POINTS_LOCK) {
            clearedPoints = null;
            clearedStore = null;
            ResumePoints store = points();
            if (store == null) throw new IllegalStateException("Resume store unavailable");
            Map<String, ResumePoints.Point> snapshot = store.clear(System.currentTimeMillis());
            PLAYERS.clear();
            clearedPoints = snapshot;
            clearedStore = store;
            int timeout = (int) UNDO_WINDOW_MS;
            Context context = Utils.getContext();
            if (Build.VERSION.SDK_INT >= 29 && context != null) {
                AccessibilityManager accessibility = context.getSystemService(AccessibilityManager.class);
                if (accessibility != null) timeout = accessibility.getRecommendedTimeoutMillis(timeout,
                        AccessibilityManager.FLAG_CONTENT_TEXT | AccessibilityManager.FLAG_CONTENT_CONTROLS);
            }
            undoId++;
            undoUntil = SystemClock.elapsedRealtime() + timeout;
            Utils.runOnMainThreadDelayed(ResumePlayback::canUndoHistory, timeout);
        }
    }

    /** Expiry releases the snapshot even when nobody taps Undo. */
    public static boolean canUndoHistory() {
        synchronized (POINTS_LOCK) {
            if (clearedPoints != null && SystemClock.elapsedRealtime() >= undoUntil) {
                clearedPoints = null;
                clearedStore = null;
            }
            return clearedPoints != null;
        }
    }

    /** Consumes Undo before writing. An old queued seek stays cancelled even after Undo. */
    public static boolean undoHistory() {
        synchronized (POINTS_LOCK) {
            return undoHistory(undoHistoryToken());
        }
    }

    public static long undoHistoryToken() {
        synchronized (POINTS_LOCK) {
            return canUndoHistory() ? undoId : 0;
        }
    }

    public static long undoHistoryDeadline(long token) {
        synchronized (POINTS_LOCK) {
            return token != 0 && token == undoHistoryToken() ? undoUntil : 0;
        }
    }

    public static boolean undoHistory(long token) {
        synchronized (POINTS_LOCK) {
            if (undoHistoryDeadline(token) == 0) return false;
            Map<String, ResumePoints.Point> snapshot = clearedPoints;
            ResumePoints store = clearedStore;
            clearedPoints = null;
            clearedStore = null;
            store.restore(snapshot, System.currentTimeMillis());
            return true;
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: IgVideoPlayerImpl's position. Only a player may be passed. */
    public static int position(Object player) {
        return NOT_PATCHED_TIME;
    }

    /** Filled in by the patch: IgVideoPlayerImpl's length of its video. Only a player may be passed. */
    public static int duration(Object player) {
        return NOT_PATCHED_TIME;
    }

    /** Filled in by the patch: the IgVideoSource of the player's video, or null. Only a player may be passed. */
    @Nullable
    public static Object videoSource(Object player) {
        return NOT_PATCHED;
    }

    /** Filled in by the patch: the UserSession [player] was made for, or null. Only a player may be passed. */
    @Nullable
    public static Object playerSession(Object player) {
        return null;
    }

    /**
     * The user ID of the account [session] belongs to, or null when there's no session or its
     * account has signed out. At a sign-out Instagram ends the session without waiting for its
     * players, so they stop, pause and let go of their views after {@link #forgetAccount} has
     * deleted the account's points, and a save then would put one back. The ID goes no further than
     * {@link #ownedKey}, which hashes it.
     */
    @Nullable
    static String owner(@Nullable Object session, Session sessions) {
        if (session == null || sessions.loggedOut(session)) return null;
        return sessions.userId(session);
    }

    /** Filled in by the patch: the media id an IgVideoSource plays. */
    @Nullable
    public static String videoId(Object source) {
        return null;
    }

    /** Filled in by the patch: an IgVideoSource's ProductType. */
    @Nullable
    public static Object productType(Object source) {
        return null;
    }

    /** Filled in by the patch: whether an IgVideoSource is sponsored. */
    public static boolean sponsored(Object source) {
        return false;
    }

    /**
     * Filled in by the patch: IgVideoPlayerImpl's seek to [positionMs], handed the seek's two flags
     * as they are, since the stub has no register of its own to set them in. The resume passes false
     * for both, as Instagram's own plain seek does. True once it's called it.
     */
    public static boolean seekPlayer(Object player, int positionMs, boolean pauseFirst, boolean flag) {
        return false;
    }

    /**
     * Filled in by the patch: the user ID of [session], a UserSession, or null. Only a session may
     * be passed. The ID goes no further than {@link ResumePoints#owner}, which hashes it.
     */
    @Nullable
    public static String sessionUserId(Object session) {
        return null;
    }

    /**
     * Filled in by the patch: whether [session], a UserSession, ends because its account signed out
     * or was removed from the phone, rather than because another account was switched to.
     */
    public static boolean sessionLoggedOut(Object session) {
        return false;
    }

    /** What this class reads from a UserSession. {@link #SESSION_STUBS} is the patch's; tests stand in. */
    interface Session {
        @Nullable
        String userId(Object session);

        boolean loggedOut(Object session);
    }

    static final Session SESSION_STUBS = new Session() {
        @Override
        public String userId(Object session) {
            return sessionUserId(session);
        }

        @Override
        public boolean loggedOut(Object session) {
            return sessionLoggedOut(session);
        }
    };

    static volatile Session sessions = SESSION_STUBS;

    // ------------------------------------------------------------------ hooks

    /**
     * The hook, first thing in UserSession.completeEndSession(), where Instagram lets an account's
     * session go: at an account switch, once the old account's screens have let go of it, and at
     * once when the account signs out or is removed from the phone. Runs with the switch off and
     * while paused, as Clear remembered positions does, since all it does is forget. It runs inside
     * Instagram's session manager lock, at a sign-out on the main thread, so it never waits on the
     * points: see {@link #forgetAccount}. Never throws.
     */
    public static void sessionEnded(Object session) {
        try {
            HookStatus.invoked(FAMILY);
            if (session == null) return;
            HookStatus.bound(FAMILY, "session end");
            forgetAccount(sessions.userId(session), sessions.loggedOut(session), System.currentTimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "session end", failure);
        }
    }

    /** The hook, first thing in IgVideoPlayerImpl's playback-started callback. */
    public static void started(Object player) {
        try {
            HookStatus.invoked(FAMILY);
            ageOnce();
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player start");
            start(player, SystemClock.uptimeMillis(), System.currentTimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player start", failure);
        }
    }

    /**
     * The hook, first thing in IgVideoPlayerImpl's pause and its stop, with Instagram's reason for
     * it. A seek of a playing video pauses it first with a reason of {@link TapToPlay#MOMENTARY}
     * and plays on from the new place, so that pause saves nothing: the point would be where you
     * scrubbed away from.
     */
    public static void stopped(Object player, @Nullable String reason) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player pause");
            if (PLAYERS.seekingNow(player) || (reason != null && TapToPlay.MOMENTARY.contains(reason))) return;
            remember(player, System.currentTimeMillis(), reason == null ? "a pause" : "a pause for " + reason);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player pause", failure);
        }
    }

    /**
     * The hook, first thing where IgVideoPlayerImpl is given a video. The video it had is saved,
     * and the next start counts as a first one, unless the player's first start came just now.
     */
    public static void rebound(Object player) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player bind");
            remember(player, System.currentTimeMillis(), "a new video");
            PLAYERS.rebound(player, SystemClock.uptimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player bind", failure);
        }
    }

    /** The hook, first thing where IgVideoPlayerImpl's video plays to its end or loops: its point goes. */
    public static void ended(Object player) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "video end");
            Facts facts = access.facts(player);
            if (facts == null || facts == Facts.NOT_PATCHED) return;
            ResumePoints store = points();
            if (store != null && store.remove(facts.videoId, System.currentTimeMillis())) {
                count(CLEARED);
                log(() -> "Resume long videos: forgot a point at the end of a video");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "video end", failure);
        }
    }

    /**
     * The hook, first thing in IgVideoPlayerImpl's seek. A seek before the resume that goes past the
     * start means the player was put where someone wanted it, and the resume is dropped.
     */
    public static void seeking(Object player, int positionMs) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player seek");
            if (positionMs > START_WINDOW_MS) PLAYERS.positioned(player);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player seek", failure);
        }
    }

    /**
     * An account's session ended: every resume its players have waiting is dropped, so none seeks
     * once another account is on screen, and when it [loggedOut] its points go too, from the file
     * and from the copy Undo would bring back. A session whose user ID isn't known can't be told
     * from another, so every waiting resume is dropped and no point is touched.
     *
     * Only the players are let go here, on their own lock. The points wait for a worker: a resume
     * holds POINTS_LOCK across Instagram's seek, the first read of the file can block, and this
     * runs where Instagram holds its session manager. A save that slips in first is gone with the
     * rest, and the account's players save nothing after (see {@link #owner}).
     */
    static void forgetAccount(@Nullable String userId, boolean loggedOut, long now) {
        count(SESSION_ENDED);
        if (userId == null || userId.isEmpty()) {
            PLAYERS.clear();
            log(() -> "Resume long videos: a session whose account isn't known ended, so no resume waits");
            return;
        }
        String prefix = ResumePoints.owner(userId) + '/';
        int waiting = PLAYERS.forget(prefix);
        if (!loggedOut) {
            log(() -> "Resume long videos: an account's session ended, " + waiting + " player(s) let go");
            return;
        }
        Runnable forget = () -> forgetPoints(prefix, now);
        if (Utils.runOnBackgroundThread(forget)) return;
        // The queue is full. The points still go, on a thread of their own.
        Thread worker = new Thread(forget, "HushGram resume sign-out");
        worker.setDaemon(true);
        worker.start();
    }

    /** A signed-out account's points, from the file and from the copy Undo would bring back. On a worker. */
    private static void forgetPoints(String prefix, long now) {
        try {
            int removed;
            synchronized (POINTS_LOCK) {
                ResumePoints store = points();
                removed = store == null ? 0 : store.removeOwner(prefix, now);
                if (clearedPoints != null) clearedPoints.keySet().removeIf(key -> key.startsWith(prefix));
            }
            int forgotten = removed;
            count(ACCOUNT_FORGOTTEN);
            log(() -> "Resume long videos: an account signed out, " + forgotten + " point(s) forgotten");
        } catch (Throwable failure) {
            Logger.printException(() -> "Resume long videos: could not forget a signed-out account's points", failure);
        }
    }

    // ------------------------------------------------------------------ the rule

    private static boolean on() {
        return Utils.settingsReady() && Settings.RESUME_LONG_VIDEOS.get();
    }

    /** A start of [player]: its first start of a video looks the video up and resumes it. */
    static void start(Object player, long uptime, long now) {
        Facts facts = readFacts(player);
        if (facts == null) return;
        State state = PLAYERS.firstStart(player, facts.videoId, uptime);
        if (state == null) return;
        FeedFilterCounters.sawList(ROUTE, 1);

        int length = readDuration(player);
        String skip = skipReason(facts, length);
        if (skip != null) {
            count(skip);
            log(() -> "Resume long videos: left a start alone, " + skip + " (" + clock(length) + ")");
            return;
        }
        ResumePoints store = points();
        if (store == null) return;
        ResumePoints.Point point = store.get(facts.videoId, now);
        if (point == null) {
            count(NOTHING_SAVED);
            return;
        }
        if (PLAYERS.wasPositioned(state)) {
            skipped(SEEKED_FIRST);
            return;
        }
        int at = readPosition(player);
        if (at == NOT_PATCHED_TIME) return;
        if (at > START_WINDOW_MS) {
            skipped(PAST_START);
            return;
        }
        int target = point.positionMs;
        if (target >= length - END_MARGIN_MS) {
            // Saved before the video was known to be this short. It'd end the video at once.
            if (store.remove(facts.videoId, now)) count(CLEARED);
            return;
        }
        if (!later.post(() -> resume(player, state, facts.videoId, target, length))) {
            log(() -> "Resume long videos: no looper to resume on");
        }
    }

    /** The resume itself, once Instagram's start is done: the seek, unless something moved first. */
    static void resume(Object player, State state, String videoId, int target, int length) {
        // Clear and the final seek are serialized, so a cleared queued restore cannot race through.
        synchronized (POINTS_LOCK) {
            try {
                if (!on()) return;
                Facts facts = readFacts(player);
                if (facts == null || !videoId.equals(facts.videoId) || !PLAYERS.isCurrent(player, state)) {
                    skipped(MOVED_ON);
                    return;
                }
                if (!PLAYERS.beginSeek(state)) {
                    skipped(SEEKED_FIRST);
                    return;
                }
                boolean sought;
                try {
                    sought = access.seek(player, target);
                } finally {
                    PLAYERS.endSeek(state);
                }
                if (!sought) {
                    HookStatus.missingMember(FAMILY, "method", "IgVideoPlayerImpl", "the seek");
                    return;
                }
                HookStatus.bound(FAMILY, "resume seek");
                count(RESUMED);
                log(() -> "Resume long videos: resumed at " + clock(target) + " of " + clock(length));
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "resume", failure);
            }
        }
    }

    /** Saves where [player]'s video is, or forgets its point when it's at the end. [why] goes in the log. */
    static void remember(Object player, long now, String why) {
        synchronized (POINTS_LOCK) {
            Facts facts = readFacts(player);
            if (facts == null) return;
            int length = readDuration(player);
            if (skipReason(facts, length) != null) return;
            int at = readPosition(player);
            if (at == NOT_PATCHED_TIME || at < 0) return;
            ResumePoints store = points();
            if (store == null) return;
            if (at >= length - END_MARGIN_MS) {
                if (store.remove(facts.videoId, now)) {
                    count(CLEARED);
                    log(() -> "Resume long videos: forgot a point at the end of " + clock(length));
                }
            } else if (at >= MIN_SAVED_MS) {
                store.put(facts.videoId, at, now);
                count(SAVED);
                log(() -> "Resume long videos: saved " + clock(at) + " of " + clock(length) + " at " + why);
            } else {
                log(() -> "Resume long videos: stopped at " + clock(at) + " of " + clock(length) + ", too early to save");
            }
        }
    }

    /** Why a video [length] long is left alone, or null when it's one this resumes. */
    @Nullable
    static String skipReason(Facts facts, int length) {
        if (facts.live) return LIVE;
        if (facts.ad) return AD;
        if (length < MIN_DURATION_MS) return SHORT;
        return null;
    }

    @Nullable
    private static Facts readFacts(Object player) {
        Facts facts = access.facts(player);
        if (facts == Facts.NOT_PATCHED) {
            HookStatus.missingMember(FAMILY, "method", "IgVideoPlayerImpl", "the video reader");
            return null;
        }
        return facts;
    }

    private static int readPosition(Object player) {
        int at = access.position(player);
        if (at == NOT_PATCHED_TIME) {
            HookStatus.missingMember(FAMILY, "method", "IgVideoPlayerImpl", "the position reader");
        } else {
            HookStatus.bound(FAMILY, "player position");
        }
        return at;
    }

    private static int readDuration(Object player) {
        int length;
        try {
            length = access.duration(player);
        } catch (IllegalStateException released) {
            // Instagram's length reader throws once the player has let go of its video.
            return 0;
        }
        if (length == NOT_PATCHED_TIME) {
            HookStatus.missingMember(FAMILY, "method", "IgVideoPlayerImpl", "the length reader");
            return 0;
        }
        return length;
    }

    // ------------------------------------------------------------------ the video

    /**
     * The saved points' key for [videoId] played by the account with [userId], or null when either
     * is missing. A video whose account isn't known is neither saved nor resumed. Each player carries
     * its own session, and a delayed resume checks the key again before it seeks, so one account's
     * point can't reach another account's player.
     */
    @Nullable
    static String ownedKey(@Nullable String userId, @Nullable String videoId) {
        if (userId == null || userId.isEmpty() || videoId == null || videoId.isEmpty()) return null;
        return ResumePoints.key(userId, videoId);
    }

    /**
     * What the rule reads of a video with [videoId], Instagram's ProductType constant [productType]
     * and its sponsored flag, or null when it has no ID. The type is read by the constant's name.
     */
    @Nullable
    static Facts factsOf(@Nullable String videoId, @Nullable Object productType, boolean sponsored) {
        if (videoId == null || videoId.isEmpty()) return null;
        String product = productType instanceof Enum ? ((Enum<?>) productType).name() : null;
        return new Facts(videoId, LIVE_PRODUCT.equals(product), AD_PRODUCT.equals(product) || sponsored);
    }

    /** What the rule reads of a player's video. */
    static final class Facts {
        /** What {@link Player#facts} answers until the patch fills its bridges in. */
        static final Facts NOT_PATCHED = new Facts("", false, false);

        /** The video's key in the saved points: its account's part and its media ID ({@link #ownedKey}). */
        final String videoId;
        final boolean live;
        final boolean ad;

        Facts(String videoId, boolean live, boolean ad) {
            this.videoId = videoId;
            this.live = live;
            this.ad = ad;
        }
    }

    // ------------------------------------------------------------------ the saved points

    /**
     * The first time Instagram starts a video in a process, switch on or off: drops the saved
     * points past their age on a worker. A point keeps a video's ID, and with the switch off
     * nothing reads the points, so they'd stay for good otherwise.
     */
    private static void ageOnce() {
        if (!Utils.settingsReady() || aging.get() != null) return;
        Object request = new Object();
        if (!aging.compareAndSet(null, request)) return;
        if (!Utils.runOnBackgroundThread(() -> {
            try {
                ResumePoints store;
                synchronized (POINTS_LOCK) {
                    if (aging.get() != request) return;
                    store = points();
                }
                if (store != null) {
                    store.dropExpired(System.currentTimeMillis());
                    aging.compareAndSet(request, AGED);
                }
            } catch (Throwable failure) {
                Logger.printException(() -> "Resume long videos: could not age the saved points", failure);
            } finally {
                aging.compareAndSet(request, null);
            }
        })) aging.compareAndSet(request, null);
    }

    /** The saved points, read the first time they're needed. Null outside the main process. */
    @Nullable
    private static ResumePoints points() {
        ResumePoints forTests = pointsForTests;
        if (forTests != null) return forTests;
        synchronized (POINTS_LOCK) {
            if (points == null) {
                Context context = Utils.getContext();
                if (context == null || !Utils.isMainProcess()) return null;
                // Points from before they had an account could be anyone's, so they go.
                context.deleteSharedPreferences(ResumePoints.UNOWNED_FILE);
                points = new ResumePoints(context.getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE));
            }
            return points;
        }
    }

    // ------------------------------------------------------------------ the report

    private static void count(String kind) {
        FeedFilterCounters.sawKind(ROUTE, kind);
    }

    private static void skipped(String why) {
        count(why);
        log(() -> "Resume long videos: didn't resume, " + why);
    }

    private static void log(Logger.LogMessage message) {
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, message);
    }

    /** A position as minutes and seconds, the way a player shows it. */
    static String clock(int ms) {
        int seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ------------------------------------------------------------------ the players

    /** One player's first start of a video, and what happened to it since. */
    static final class State {
        final String videoId;
        final long startedAt;
        /** Someone else put the player where they wanted it before the resume. */
        boolean positioned;
        /** The resume's own seek is running. */
        boolean seeking;

        State(String videoId, long startedAt) {
            this.videoId = videoId;
            this.startedAt = startedAt;
        }
    }

    /**
     * Each player's {@link State}, by identity and weakly: a player Instagram drops is forgotten
     * with it. A player with no state has had no first start since it was bound, and a seek on it
     * before then is kept as a positioned marker.
     */
    static final class Players {
        private final Map<Key, State> states = new HashMap<>();
        private final Set<Key> soughtBeforeStart = new HashSet<>();
        private final ReferenceQueue<Object> gone = new ReferenceQueue<>();

        /**
         * The state of [player]'s first start of [videoId], or null when this isn't one: the player
         * already started this video since it was bound.
         */
        @Nullable
        synchronized State firstStart(Object player, String videoId, long uptime) {
            purge();
            Key key = new Key(player, null);
            State current = states.get(key);
            if (current != null && current.videoId.equals(videoId)) return null;
            State state = new State(videoId, uptime);
            state.positioned = soughtBeforeStart.remove(key);
            states.remove(key);
            states.put(new Key(player, gone), state);
            return state;
        }

        synchronized boolean wasPositioned(State state) {
            return state.positioned;
        }

        synchronized boolean isCurrent(Object player, State state) {
            purge();
            return states.get(new Key(player, null)) == state;
        }

        /** Marks the resume's seek as running. False when someone else sought first. */
        synchronized boolean beginSeek(State state) {
            if (state.positioned) return false;
            state.seeking = true;
            return true;
        }

        synchronized void endSeek(State state) {
            state.seeking = false;
        }

        synchronized boolean seekingNow(Object player) {
            purge();
            State state = states.get(new Key(player, null));
            return state != null && state.seeking;
        }

        /** Someone other than the resume sought [player]. */
        synchronized void positioned(Object player) {
            purge();
            Key key = new Key(player, null);
            State state = states.get(key);
            if (state == null) {
                soughtBeforeStart.add(new Key(player, gone));
            } else if (!state.seeking) {
                state.positioned = true;
            }
        }

        /** [player] was given a video: its next start is a first one, unless its first start came just now. */
        synchronized void rebound(Object player, long uptime) {
            purge();
            Key key = new Key(player, null);
            State state = states.get(key);
            if (state != null && uptime - state.startedAt < BIND_GRACE_MS) return;
            states.remove(key);
            soughtBeforeStart.remove(key);
        }

        synchronized int size() {
            purge();
            return states.size();
        }

        /**
         * Lets go of every player whose video's key starts with [prefix], one account's, so a resume
         * one of them has waiting finds it isn't current. Answers how many.
         */
        synchronized int forget(String prefix) {
            purge();
            int dropped = 0;
            for (Iterator<State> known = states.values().iterator(); known.hasNext(); ) {
                if (!known.next().videoId.startsWith(prefix)) continue;
                known.remove();
                dropped++;
            }
            return dropped;
        }

        synchronized void clear() {
            states.clear();
            soughtBeforeStart.clear();
            purge();
        }

        private void purge() {
            Reference<?> cleared;
            while ((cleared = gone.poll()) != null) {
                states.remove(cleared);
                soughtBeforeStart.remove(cleared);
            }
        }

        private static final class Key extends WeakReference<Object> {
            private final int hash;

            Key(Object player, @Nullable ReferenceQueue<Object> queue) {
                super(player, queue);
                hash = System.identityHashCode(player);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                if (other == this) return true;
                if (!(other instanceof Key)) return false;
                Object mine = get();
                return mine != null && mine == ((Key) other).get();
            }
        }
    }

    // ------------------------------------------------------------------ for tests

    /** Forgets every player and puts the app's player, looper and points back. */
    static void forget() {
        PLAYERS.clear();
        synchronized (POINTS_LOCK) {
            aging.set(null);
            points = null;
            clearedPoints = null;
            clearedStore = null;
            undoUntil = 0;
        }
        access = PATCHED;
        later = ON_MAIN_LOOPER;
        sessions = SESSION_STUBS;
        pointsForTests = null;
    }

    static int playersKnown() {
        return PLAYERS.size();
    }
}
