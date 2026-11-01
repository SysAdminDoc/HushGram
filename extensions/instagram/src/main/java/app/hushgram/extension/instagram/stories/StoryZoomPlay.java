/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Keep stories playing while zooming" patch.
 *
 * <p>When two fingers start a pinch on a story, the story viewer pauses it for the zoom and plays
 * it again when the fingers lift. The patch asks {@link #keepPlaying()} first thing in the
 * viewer's pause for a pinch, and the pause is skipped when it says yes.
 *
 * <p>A story the user holds still pauses through the same pause method with another reason. The
 * patch reports every pause here ({@link #paused(String)}) and every resume ({@link #resumed()}),
 * so a pinch that starts on a story already held leaves it paused, which is what holding and then
 * adding a finger to zoom asks for. Anything that pauses the story counts as held, which only ever
 * leaves Instagram's own pause in place.
 */
public final class StoryZoomPlay {
    /** The reason Instagram gives the viewer when a pinch pauses a story. */
    static final String PINCH = "paused_for_pinch_to_zoom";

    private static volatile boolean held;

    private StoryZoomPlay() {
    }

    /** Injected at the top of the viewer's pause, with its reason. Never throws. */
    public static void paused(String reason) {
        try {
            if (!PINCH.equals(reason)) held = true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_ZOOM_PLAY, "story paused", failure);
        }
    }

    /** Injected at the top of the viewer's resume. Never throws. */
    public static void resumed() {
        try {
            held = false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_ZOOM_PLAY, "story resumed", failure);
        }
    }

    /**
     * True skips the pause a pinch asks for. False while the switch is off, HushGram is paused, the
     * settings aren't ready, the story is already held, or when anything goes wrong. Never throws.
     */
    public static boolean keepPlaying() {
        try {
            HookStatus.invoked(FamilyNames.STORY_ZOOM_PLAY);
            return Utils.settingsReady() && Settings.KEEP_STORIES_PLAYING_ON_ZOOM.get() && !held;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_ZOOM_PLAY, "pinch pause", failure);
            return false;
        }
    }

    /** For tests. */
    static void reset() {
        held = false;
    }
}
