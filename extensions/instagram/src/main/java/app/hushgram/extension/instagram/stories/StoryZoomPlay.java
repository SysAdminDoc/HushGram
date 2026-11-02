/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.os.SystemClock;
import android.view.ViewConfiguration;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Keep stories playing while zooming" patch.
 *
 * <p>When two fingers start a pinch on a story, the story viewer pauses it for the zoom and plays
 * it again when the fingers lift. The patch asks {@link #keepPlaying()} first thing in the
 * viewer's pause for a pinch. When it says yes, that pause is skipped and the viewer's resume runs
 * instead: the first finger down has already paused the story ("tapped"), and that pause would
 * otherwise hold until the fingers lift. The pinch's moving fingers count as a drag to that resume,
 * which won't play a story being dragged, so {@link #dragging(int)} answers "not dragging" for that
 * one call.
 *
 * <p>A pinch that starts on a story the user is already holding still leaves it paused, which is
 * what holding and then adding a finger to zoom asks for. The patch reports every pause here
 * ({@link #paused(String)}) and every resume ({@link #resumed()}). On 453 a story held still keeps
 * the pause its first finger made ({@link #TAP}) until the finger lifts, so a pinch starting longer
 * than the system's touch and hold delay after that pause counts as held; a plain pinch starts
 * about 40 ms after its first finger lands, and that finger's own pause replaces any older one. A
 * pause for {@link #HOLD} counts as held too, until the next resume. Nothing else does: leaving the
 * viewer pauses it too ("fragment_paused"), and the next story starts playing without a resume, so
 * counting that pause kept every later pinch paused.
 */
public final class StoryZoomPlay {
    /** The reason Instagram gives the viewer when a pinch pauses a story. */
    static final String PINCH = "paused_for_pinch_to_zoom";

    /** The reason Instagram gives the viewer when a long press pauses a story. */
    static final String HOLD = "long_pressed";

    /** The reason Instagram gives the viewer when a finger lands on a story. */
    static final String TAP = "tapped";

    /** What the diagnostic report counts for a pinch with the switch on. */
    static final String KEPT = "pinches kept playing";
    static final String LEFT_HELD = "pinches left paused, story held";

    private static volatile boolean held;

    /** When the last {@link #TAP} pause came, in uptime ms, or -1 after a resume. */
    private static volatile long tappedAt = -1;

    /** True while the resume a kept pinch asks for runs. */
    private static volatile boolean pinchResume;

    private StoryZoomPlay() {
    }

    /** Injected at the top of the viewer's pause, with its reason. Never throws. */
    public static void paused(String reason) {
        try {
            Logger.printDebug(() -> "Story paused: " + reason);
            if (HOLD.equals(reason)) held = true;
            else if (TAP.equals(reason)) tappedAt = SystemClock.uptimeMillis();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_ZOOM_PLAY, "story paused", failure);
        }
    }

    /** Injected at the top of the viewer's resume. Never throws. */
    public static void resumed() {
        try {
            if (held) Logger.printDebug(() -> "Story resumed after a hold");
            held = false;
            tappedAt = -1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_ZOOM_PLAY, "story resumed", failure);
        }
    }

    /**
     * True skips the pause a pinch asks for and plays the story again. False while the switch is
     * off, HushGram is paused, the settings aren't ready, the story is held still, or when anything
     * goes wrong. Never throws.
     */
    public static boolean keepPlaying() {
        try {
            HookStatus.invoked(FamilyNames.STORY_ZOOM_PLAY);
            if (!Utils.settingsReady() || !Settings.KEEP_STORIES_PLAYING_ON_ZOOM.get()) return false;
            long tapped = tappedAt;
            boolean heldStill = tapped >= 0
                    && SystemClock.uptimeMillis() - tapped >= ViewConfiguration.getLongPressTimeout();
            boolean keep = !held && !heldStill;
            pinchResume = keep;
            HookStatus.counted(FamilyNames.STORY_ZOOM_PLAY, keep ? KEPT : LEFT_HELD);
            Logger.printDebug(() -> keep ? "Pinch on a story: kept playing" : "Pinch on a story: held, left paused");
            return keep;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_ZOOM_PLAY, "pinch pause", failure);
            return false;
        }
    }

    /** Injected right after the resume a kept pinch asks for returns. */
    public static void pinchResumed() {
        pinchResume = false;
    }

    /**
     * Injected where the viewer's resume reads whether the story is being dragged, with that answer
     * (non-zero for yes). Answers 0 during the resume a kept pinch asks for and passes every other
     * answer through. Never throws.
     */
    public static int dragging(int dragging) {
        try {
            if (!pinchResume || dragging == 0) return dragging;
            Logger.printDebug(() -> "Pinch on a story: resumed past the drag check");
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_ZOOM_PLAY, "drag check", failure);
            return dragging;
        }
    }

    /** For tests. */
    static void reset() {
        held = false;
        tappedAt = -1;
        pinchResume = false;
    }
}
