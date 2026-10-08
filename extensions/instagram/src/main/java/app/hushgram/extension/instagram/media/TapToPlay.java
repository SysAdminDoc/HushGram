/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Tap to play: videos, reels and stories wait for a tap.
 *
 * <p>Instagram 449 plays through IgGrootPlayer. A feed video or a reel starts through
 * IgVideoPlayerImpl's playInternal, which then plays its IgGrootPlayer, and the story viewer and a
 * few other screens play an IgGrootPlayer themselves. The patch asks {@link #allowStart} first
 * thing in playInternal, handing it that IgGrootPlayer, and {@link #allowDirectStart} first thing
 * in IgGrootPlayer's play, and a no makes either return before it does anything. Each start
 * carries a reason, a string, but it can't tell a tap from an automatic start: playInternal only
 * ever gets "autoplay" or "resume", and a story you tap starts with "autoplay". So a start goes
 * ahead when one of these holds:
 *
 * <ul>
 *   <li>The player is armed: it made a start this gate let through, and it hasn't been paused or
 *       given a new video since. That keeps what a tap started going through Instagram's own
 *       restarts, such as playInternal's play of that same player, or the play after a seek's
 *       pause ({@link #MOMENTARY}).</li>
 *   <li>A tap ended no more than {@link #TAP_WINDOW_MS} ago ({@link TapClock}).</li>
 *   <li>A tap ended no more than {@link #LOAD_WINDOW_MS} ago and nothing has started on it yet:
 *       a story or a video the tap opened can take that long to load.</li>
 *   <li>Instagram's Reels auto scroller moved on to the next reel no more than
 *       {@link #LOAD_WINDOW_MS} ago, and this is the first start of a player that isn't armed since
 *       ({@link #autoScrolled}). Moving on counts as a tap on the reel it moves to, so that reel
 *       plays, and its own end moves on again, reel after reel, while auto scroll is on.</li>
 * </ul>
 *
 * <p>Every other start is held, and the player stays where it was, showing its first frame or its
 * cover. Instagram's own autoplay check answers no while the switch is on ({@link #autoplayAllowed}),
 * so the feed draws the play button it draws when data saver is on. That button stays drawn over
 * the video it started until the post is drawn again, so {@link #playButtonTapped} hides the one a
 * tap started a video from, until that start ends ({@link PlayButtons}). A tap on a reel resumes it
 * only when Instagram knows you paused it yourself, so a tap on a held reel would go down the pause
 * path and do nothing: {@link #resumeOnTap} sends a tap on a reel that's waiting down the resume
 * path instead. The story viewer resumes on the release of a press and hold only a story it paused
 * while it played, and {@link #resumeHeldStory} gives a story this patch held the same resume.
 *
 * <p>{@link Settings#TAP_TO_PLAY_SCOPE} can leave the Reels viewer out, or everything but it (#39).
 * playInternal's hook says whether its IgVideoPlayerImpl is the Reels viewer's: the patch checks
 * that the player's video logger is the one the Reels viewer makes. IgGrootPlayer's own play goes
 * by what playInternal last said of that IgGrootPlayer, and one it never said anything of, such as
 * a story's, is outside Reels. A start the choice leaves out goes ahead untouched, and the Reels
 * tap and auto scroller, or the story release, the feed's play button and the autoplay check, do
 * what Instagram decided wherever the choice leaves them out.
 *
 * <p>Off, paused, before the settings are ready, or when anything here throws, every start goes ahead
 * and the check answers what Instagram decided, as it would unpatched.
 */
public final class TapToPlay {
    /** How long after a tap ends a start still counts as that tap's. */
    static final long TAP_WINDOW_MS = 1000;

    /**
     * How long after a tap ends its first start still counts as the tap's. A story opened with a
     * tap started 1.7 seconds after it on the S22 while it loaded. Shorter than a photo story's five
     * seconds, so an advance Instagram makes on its own past a photo the tap opened still waits, and
     * a drag or a second finger ends the tap's claim before then ({@link TapClock}).
     */
    static final long LOAD_WINDOW_MS = 4000;

    /**
     * How long a start keeps its player armed through a new video. IgGrootPlayer can be prepared
     * again right after the play the gate let through, and that shouldn't disarm it. A non-tap
     * gesture expires this grace before another video can bind.
     */
    static final long BIND_GRACE_MS = 2000;

    /**
     * Pause reasons Instagram plays the same video again right after, so they don't undo the tap
     * that started it: a seek pauses and plays again with "seek_force_pause", a drag of the
     * scrubber with "seek", a drag of the long video viewer's scrubber with "Seek start" (it pauses
     * only a playing video, and plays it again at "Seek end"), a pinch to zoom with
     * "paused_for_pinch_to_zoom", and a reel that loops with "paused_for_replay".
     */
    static final Set<String> MOMENTARY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "seek", "seek_force_pause", "Seek start", "paused_for_pinch_to_zoom", "paused_for_replay")));

    /**
     * The IgVideoPlayerImpl states a tap on a reel starts from: prepared and never started, which is
     * where the gate leaves a held reel, and paused, which is where a pause for the comments sheet
     * leaves it. ClipsVideoPlayer's resume restarts a player only from these two, so a tap on a reel
     * in any other state does what Instagram decided.
     */
    static final Set<String> RESUMABLE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("PREPARED", "PAUSED")));

    /** What {@link ReelStateReader}'s stubs return until the patch fills them in. */
    static final Object NOT_PATCHED = new Object();

    /** How many decisions get a line of their own before the log sums them up. */
    static final int LOGGED_ONE_BY_ONE = 40;

    /** How many decisions each summary line covers after that. */
    static final int SUMMED_UP_BY = 50;

    private static final String SOURCE = "TapToPlay";

    private static final Players ARMED = new Players();
    /** The players whose last start this gate held, until one goes ahead or they get a new video. */
    private static final Players HELD = new Players();
    /** The IgGrootPlayers whose last playInternal was a Reels viewer player's, for their own plays. */
    private static final Players REELS = new Players();
    private static final Object TAP_LOCK = new Object();
    /** The end of the last tap a start went ahead on, so only its first start gets the load window. */
    private static long usedTap = TapClock.NO_TAP;
    /** No move of the auto scroller is waiting for its start. */
    static final long NO_SCROLL = Long.MIN_VALUE;
    /**
     * When Instagram's auto scroller last moved on, until a start uses that move or a swipe ends
     * it: {@link #NO_SCROLL} then. Guarded by {@link #TAP_LOCK}.
     */
    private static long autoScrolledAt = NO_SCROLL;
    private static final Object LOG_LOCK = new Object();
    private static int decisions;
    private static int allowedSinceSummary;
    private static int heldSinceSummary;
    private static int endedStarts;
    private static int autoScrolls;
    private static boolean checkLogged;
    private static boolean viewlessClickLogged;
    private static boolean reelsLeftOutLogged;
    private static boolean restLeftOutLogged;

    /** Makes the next decision throw, once. For tests. */
    @Nullable
    static volatile RuntimeException failNext;

    private TapToPlay() { }

    /**
     * The hook, first thing in IgVideoPlayerImpl's playInternal, with the IgGrootPlayer it's about
     * to play and [reels], non-zero when that IgVideoPlayerImpl is the Reels viewer's. False, and
     * playInternal returns at once, before it marks the video as playing.
     */
    public static boolean allowStart(@Nullable Object player, @Nullable String reason, int reels) {
        return allow(player, reason, reels != 0, "player start", "");
    }

    /** The hook, first thing in IgGrootPlayer's play. False, and the play returns at once. */
    public static boolean allowDirectStart(@Nullable Object player, @Nullable String reason) {
        return allow(player, reason, null, "direct start", " (direct)");
    }

    /**
     * The hook, first thing in IgGrootPlayer's pause. A paused player waits for a tap again, unless
     * the pause is one of the {@link #MOMENTARY} ones Instagram plays on from by itself.
     */
    public static void paused(@Nullable Object player, @Nullable String reason) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "player pause");
            if ((reason == null || !MOMENTARY.contains(reason)) && ARMED.remove(player)) {
                PlayButtons.ended(player);
                logEnded(() -> "a pause for " + (reason == null ? "no reason" : reason));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "player pause", failure);
        }
    }

    /** The hook, first thing where IgGrootPlayer is prepared with a video. A new video waits for a tap. */
    public static void rebound(@Nullable Object player) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "player prepare");
            HELD.remove(player);
            if (ARMED.removeUnlessAddedSince(player, SystemClock.uptimeMillis() - BIND_GRACE_MS)) {
                PlayButtons.ended(player);
                logEnded(() -> "a new video");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "player prepare", failure);
        }
    }

    /**
     * A swipe keeps the current video armed, but the next video must wait for its own start. It
     * also ends a move of the auto scroller that no start has used yet, so the reel you swipe to
     * waits for a tap, as it would with auto scroll off.
     */
    static void nonTapGesture() {
        ARMED.expireBindGrace();
        synchronized (TAP_LOCK) {
            autoScrolledAt = NO_SCROLL;
        }
    }

    /**
     * The hook in Instagram's Reels auto scroller, right before it moves the Reels pager on to the
     * next reel. The scroller moves on only while Instagram's auto scroll is on, when a reel ends or
     * nearly does, or when a photo's timer runs out. Moving on counts as a tap on the reel it moves
     * to: that reel's first start within {@link #LOAD_WINDOW_MS} goes ahead and arms its player, if
     * that player isn't armed already ({@link #decide}). A held reel never plays, so it never ends,
     * and the scroller never moves on from it, and a reel you pause doesn't end either, so the chain
     * stops there. Without this the reel auto scroll moved to sat on its first frame and auto scroll
     * stopped for good. Off, paused, before the settings are ready, or when anything here throws,
     * nothing is recorded, and the next reel waits for a tap.
     */
    public static void autoScrolled() {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            RuntimeException failure = failNext;
            if (failure != null) {
                failNext = null;
                throw failure;
            }
            if (!onFor(true)) return;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "auto scroll");
            scrolledAt(SystemClock.uptimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "auto scroll", failure);
        }
    }

    /** Records a move of the auto scroller at [now], for the start of the reel it moves to. */
    static void scrolledAt(long now) {
        synchronized (TAP_LOCK) {
            autoScrolledAt = now;
        }
        int count;
        synchronized (LOG_LOCK) {
            count = ++autoScrolls;
        }
        if (count <= LOGGED_ONE_BY_ONE) {
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> "Tap to play: auto scroll moved on, so the next reel's first start goes ahead");
        } else if ((count - LOGGED_ONE_BY_ONE) % SUMMED_UP_BY == 0) {
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Tap to play: " + SUMMED_UP_BY + " more auto scrolls");
        }
    }

    /**
     * The entry the patch calls, handed the autoplay check's answer as an int, non-zero for yes: a
     * boolean method may return a register the verifier types as int, and a boolean parameter
     * wouldn't take it.
     */
    public static boolean autoplayAllowed(int answer) {
        return autoplayAllowed(answer != 0);
    }

    /**
     * The hook at the return of Instagram's VideoAutoplayChecker, the check behind "Use less
     * mobile data". While the switch is on it answers no, so the feed draws its play button on a
     * video instead of starting it. Nothing is stored, so turning the switch off gives Instagram's
     * own answer again.
     */
    public static boolean autoplayAllowed(boolean answer) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            if (!answer || !onFor(false)) return answer;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "autoplay check");
            logCheckOnce();
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "autoplay check", failure);
            return answer;
        }
    }

    /**
     * The hook in the click of the feed's Litho play button, right after it hands its video to
     * Instagram, with the click event. While the switch is on, the button's view is hidden until
     * the start the tap asked for ends ({@link PlayButtons}); a click that carries no view leaves
     * the button as it is.
     */
    public static void playButtonTapped(@Nullable Object click) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            if (!onFor(false)) return;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "play button");
            View button = PlayButtons.viewOf(click);
            if (button != null) {
                long now = SystemClock.uptimeMillis();
                long sinceTap = TapClock.msSinceTap(now);
                PlayButtons.hide(button, now, sinceTap < 0 ? now : now - sinceTap);
                return;
            }
            synchronized (LOG_LOCK) {
                if (viewlessClickLogged) return;
                viewlessClickLogged = true;
            }
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> "Tap to play: the play button's click carries no view, so the button stays");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "play button", failure);
        }
    }

    /**
     * The entry the patch calls, handed the Reels tap's decision as an int, non-zero for resume,
     * so the hook doesn't depend on Instagram's code leaving that register typed as a boolean.
     */
    public static boolean resumeOnTap(int resume, @Nullable Object navigator) {
        return resumeOnTap(resume != 0, navigator);
    }

    /**
     * The hook in Instagram's Reels tap, at the branch where it has decided between resuming the
     * reel and pausing it, with that decision and the tap's navigator. Instagram resumes only a reel
     * you paused yourself, so a tap on a reel this patch held took the pause path, found nothing
     * playing and did nothing, however often you tapped. While the switch is on, a tap on a reel that's
     * prepared or paused ({@link #RESUMABLE}) resumes it, the way it resumes one you paused, and that
     * start comes inside the tap's window. A reel Instagram stopped for a tap on one of its stickers
     * needs none of this: Instagram's own tap away from the sticker starts it again
     * ("start_reason_sticker_tap_away"). A playing reel still pauses, and a reel with no
     * player or one still loading does what Instagram decided. Off, paused, before the settings are
     * ready, or when anything here throws, the tap does what Instagram decided.
     */
    public static boolean resumeOnTap(boolean resume, @Nullable Object navigator) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            RuntimeException failure = failNext;
            if (failure != null) {
                failNext = null;
                throw failure;
            }
            if (resume || navigator == null || !onFor(true)) return resume;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "Reels tap");
            Object state = reelStates.stateOf(navigator);
            if (state == NOT_PATCHED) {
                HookStatus.missingMember(FamilyNames.TAP_TO_PLAY, "method", "ClipsVideoPlayerController", "the reel's state");
                return resume;
            }
            String name = state instanceof Enum ? ((Enum<?>) state).name() : null;
            boolean start = name != null && RESUMABLE.contains(name);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Tap to play: a tap on a reel "
                    + (name == null ? "with no player" : name) + (start ? " starts it" : " goes to Instagram"));
            return start;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "Reels tap", failure);
            return resume;
        }
    }

    /**
     * The entry the patch calls, handed the story player's resume flag as an int, non-zero for yes,
     * so the hook doesn't depend on Instagram's code leaving that register typed as a boolean.
     */
    public static boolean resumeHeldStory(int resume, @Nullable Object storyPlayer) {
        return resumeHeldStory(resume != 0, storyPlayer);
    }

    /**
     * The hook in the story viewer's video player's resume, with its own answer to whether a resume
     * should start the story and the player. Instagram answers yes only for a story it paused while
     * it played, and a press on a story pauses it only then, so a story this patch held on its first
     * frame never started on the release of a press and hold: the only way to watch it was a tap back
     * and forward again. While the switch is on, the release of a press held at least a long press
     * starts a story whose last start the gate held, and that start comes inside the release's tap
     * window, so it goes ahead. A quick tap still only moves between stories, and every other resume,
     * a sheet or a dialog closing included, gets Instagram's answer. Off, paused, before the settings
     * are ready, or when anything here throws, the resume does what Instagram decided.
     */
    public static boolean resumeHeldStory(boolean resume, @Nullable Object storyPlayer) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            RuntimeException failure = failNext;
            if (failure != null) {
                failNext = null;
                throw failure;
            }
            if (resume || storyPlayer == null || !onFor(false)) return resume;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, "story release");
            long sinceTap = TapClock.msSinceTap(SystemClock.uptimeMillis());
            if (sinceTap < 0 || sinceTap > TAP_WINDOW_MS || TapClock.heldMs() < ViewConfiguration.getLongPressTimeout()) {
                return resume;
            }
            Object groot = storyPlayers.grootOf(storyPlayer);
            if (groot == NOT_PATCHED) {
                HookStatus.missingMember(FamilyNames.TAP_TO_PLAY, "field", "the story player", "its IgGrootPlayer");
                return resume;
            }
            boolean start = groot != null && HELD.contains(groot);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Tap to play: a hold's release on a "
                    + (start ? "held story starts it" : "story that wasn't held goes to Instagram"));
            return start;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, "story release", failure);
            return resume;
        }
    }

    /** The IgGrootPlayer of the story player a release landed on. Tests put their own in. */
    interface StoryPlayers {
        /** The story player's IgGrootPlayer, null when it has none, or {@link #NOT_PATCHED}. */
        @Nullable
        Object grootOf(Object storyPlayer);
    }

    /** Reaches the reader only through this, from inside {@link #resumeHeldStory}'s try, as {@link #reelStates}. */
    static StoryPlayers storyPlayers = StoryPlayerReader::grootOf;

    /** The state of the reel a tap landed on. Tests put their own in. */
    interface ReelStates {
        /** IgVideoPlayerImpl's state enum, null when the tap's reel has no player, or {@link #NOT_PATCHED}. */
        @Nullable
        Object stateOf(Object navigator);
    }

    /**
     * Reaches the reader only through this, from inside {@link #resumeOnTap}'s try, so a reader the
     * phone refuses to load costs the Reels tap and never the start gate beside it.
     */
    static ReelStates reelStates = ReelStateReader::reelState;

    private static void logCheckOnce() {
        synchronized (LOG_LOCK) {
            if (checkLogged) return;
            checkLogged = true;
        }
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                () -> "Tap to play: Instagram's autoplay check answers no");
    }

    /**
     * [reels] is what playInternal says of the player, which IgGrootPlayer's own play remembers
     * from it, or null for that play.
     */
    private static boolean allow(@Nullable Object player, @Nullable String reason, @Nullable Boolean reels, String hook, String path) {
        try {
            HookStatus.invoked(FamilyNames.TAP_TO_PLAY);
            RuntimeException failure = failNext;
            if (failure != null) {
                failNext = null;
                throw failure;
            }
            if (!on()) return true;
            HookStatus.bound(FamilyNames.TAP_TO_PLAY, hook);
            long now = SystemClock.uptimeMillis();
            boolean inReels;
            if (reels == null) {
                inReels = REELS.contains(player);
            } else {
                inReels = reels;
                if (inReels) REELS.add(player, now);
                else REELS.remove(player);
            }
            if (!scope().covers(inReels)) {
                logLeftOutOnce(inReels);
                return true;
            }
            return decide(player, reason, now, path);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAP_TO_PLAY, hook, failure);
            return true;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.TAP_TO_PLAY.get();
    }

    /** On, and the choice of where holds starts in the Reels viewer, [reels], or outside it. */
    private static boolean onFor(boolean reels) {
        return on() && scope().covers(reels);
    }

    private static TapToPlayScope scope() {
        TapToPlayScope scope = Settings.TAP_TO_PLAY_SCOPE.get();
        return scope == null ? TapToPlayScope.EVERYWHERE : scope;
    }

    private static void logLeftOutOnce(boolean reels) {
        synchronized (LOG_LOCK) {
            if (reels ? reelsLeftOutLogged : restLeftOutLogged) return;
            if (reels) reelsLeftOutLogged = true;
            else restLeftOutLogged = true;
        }
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Tap to play: starts "
                + (reels ? "in Reels" : "outside Reels") + " go ahead, the choice of where leaves them out");
    }

    /** The rule, with the clock passed in. Arms the player when it lets the start through. */
    static boolean decide(@Nullable Object player, @Nullable String reason, long now, String path) {
        boolean armed = ARMED.contains(player);
        long sinceTap = TapClock.msSinceTap(now);
        boolean covered = tapCovers(sinceTap, now, armed);
        boolean scrolled = player != null && !armed && autoScrollCovers(now);
        boolean allowed = armed || covered || scrolled;
        if (allowed && !armed) ARMED.add(player, now);
        if (allowed) HELD.remove(player);
        else HELD.add(player, now);
        if (covered) PlayButtons.started(player, now);
        logDecision(allowed, reason, sinceTap, armed, scrolled, path);
        return allowed;
    }

    /**
     * Whether the auto scroller's last move covers a start at [now], of a player that isn't armed:
     * the first such start within {@link #LOAD_WINDOW_MS} of the move, as a tap's load window covers
     * its first start. That start uses the move, whether or not a tap covers it too, so a second
     * player can't start on it. An armed player's start goes ahead by itself and never asks, so a
     * reel that loops before the pager settles leaves the move to the reel it moves to, and a start
     * with no player at all, which plays nothing, doesn't use it up either.
     */
    private static boolean autoScrollCovers(long now) {
        synchronized (TAP_LOCK) {
            long at = autoScrolledAt;
            if (at == NO_SCROLL || now < at || now - at > LOAD_WINDOW_MS) return false;
            autoScrolledAt = NO_SCROLL;
            return true;
        }
    }

    /**
     * Whether the tap that ended [sinceTap] ms before [now] covers a start: any start within
     * {@link #TAP_WINDOW_MS}, and its first start within {@link #LOAD_WINDOW_MS}. A start it
     * covers uses the tap. An [armed] player's start goes ahead anyway, so the tap covers it only
     * within the second: a tap that played the video again is used, and one it merely came after
     * still has its first start for the video it opened.
     */
    private static boolean tapCovers(long sinceTap, long now, boolean armed) {
        if (sinceTap < 0) return false;
        long tap = now - sinceTap;
        synchronized (TAP_LOCK) {
            boolean covered = sinceTap <= TAP_WINDOW_MS || (!armed && sinceTap <= LOAD_WINDOW_MS && usedTap != tap);
            if (covered) usedTap = tap;
            return covered;
        }
    }

    private static void logDecision(boolean allowed, @Nullable String reason, long sinceTap, boolean armed, boolean scrolled, String path) {
        String line;
        synchronized (LOG_LOCK) {
            decisions++;
            if (decisions <= LOGGED_ONE_BY_ONE) {
                line = "Tap to play: " + (allowed ? "allowed " : "held ") + (reason == null ? "no reason" : reason)
                        + " " + (sinceTap < 0 ? "no tap" : sinceTap + " ms") + " armed " + (armed ? "yes" : "no")
                        + (scrolled ? " after auto scroll" : "") + path;
            } else {
                if (allowed) allowedSinceSummary++;
                else heldSinceSummary++;
                if (allowedSinceSummary + heldSinceSummary < SUMMED_UP_BY) return;
                line = "Tap to play: " + SUMMED_UP_BY + " more starts, " + allowedSinceSummary + " allowed, "
                        + heldSinceSummary + " held";
                allowedSinceSummary = 0;
                heldSinceSummary = 0;
            }
        }
        final String logged = line;
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> logged);
    }

    /**
     * Logs what ended a start this gate let through, so a video that stops after a seek or a swipe
     * shows why in the diagnostic export. The first {@link #LOGGED_ONE_BY_ONE} get a line each, then
     * one line per {@link #SUMMED_UP_BY}.
     */
    private static void logEnded(Supplier<String> why) {
        int count;
        synchronized (LOG_LOCK) {
            count = ++endedStarts;
        }
        if (count <= LOGGED_ONE_BY_ONE) {
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Tap to play: " + why.get() + " ends a start");
        } else if ((count - LOGGED_ONE_BY_ONE) % SUMMED_UP_BY == 0) {
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Tap to play: " + SUMMED_UP_BY + " more starts ended");
        }
    }

    /**
     * Forgets every armed and held player, the hidden play button, the auto scroller's move and the
     * log's counts, and puts the patched readers back. For tests.
     */
    static void forget() {
        ARMED.clear();
        HELD.clear();
        REELS.clear();
        storyPlayers = StoryPlayerReader::grootOf;
        PlayButtons.forget();
        synchronized (TAP_LOCK) {
            usedTap = TapClock.NO_TAP;
            autoScrolledAt = NO_SCROLL;
        }
        reelStates = ReelStateReader::reelState;
        synchronized (LOG_LOCK) {
            decisions = 0;
            allowedSinceSummary = 0;
            heldSinceSummary = 0;
            endedStarts = 0;
            autoScrolls = 0;
            checkLogged = false;
            viewlessClickLogged = false;
            reelsLeftOutLogged = false;
            restLeftOutLogged = false;
        }
        failNext = null;
    }

    static boolean armed(Object player) {
        return ARMED.contains(player);
    }

    static int armedCount() {
        return ARMED.size();
    }

    /**
     * Players by identity and weakly, the ones a start was let through on ({@link #ARMED}) or held on
     * ({@link #HELD}): a player Instagram drops is forgotten with it, and one whose equals says it's
     * another player is still itself.
     */
    static final class Players {
        private final Map<Key, Long> armedAt = new HashMap<>();
        private final ReferenceQueue<Object> gone = new ReferenceQueue<>();

        synchronized void add(@Nullable Object player, long now) {
            purge();
            if (player != null) armedAt.put(new Key(player, gone), now);
        }

        synchronized boolean contains(@Nullable Object player) {
            purge();
            return player != null && armedAt.containsKey(new Key(player, null));
        }

        /** True when [player] was there. */
        synchronized boolean remove(@Nullable Object player) {
            purge();
            return player != null && armedAt.remove(new Key(player, null)) != null;
        }

        synchronized void expireBindGrace() {
            purge();
            armedAt.replaceAll((player, at) -> Long.MIN_VALUE);
        }

        /** Removes [player] unless it was added at or after [since]. True when it removes it. */
        synchronized boolean removeUnlessAddedSince(@Nullable Object player, long since) {
            purge();
            if (player == null) return false;
            Key key = new Key(player, null);
            Long at = armedAt.get(key);
            if (at == null || at >= since) return false;
            armedAt.remove(key);
            return true;
        }

        synchronized int size() {
            purge();
            return armedAt.size();
        }

        synchronized void clear() {
            armedAt.clear();
            purge();
        }

        private void purge() {
            Reference<?> cleared;
            while ((cleared = gone.poll()) != null) {
                armedAt.remove(cleared);
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
}
