/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * What the Seek bar on stories patch fills in: the story viewer's view, and its video player's
 * position, length, seek, pause and resume.
 *
 * <p>Apart from {@link StorySeekBar} for the reason StoryPlayerReader is: a stub the phone's verifier
 * refused would take the class the viewer's resume calls down with it. Only StorySeekBar reaches this
 * class, inside its tries. Each stub is handed the viewer, ReelViewerFragment, and nothing else.
 */
final class StorySeekReader {
    /** What the time stubs answer while the patch hasn't filled them. */
    static final int NOT_PATCHED = -2;

    private StorySeekReader() { }

    /** Filled in by the patch: the viewer's view, or null before it has one. */
    @Nullable
    static View view(Object viewer) {
        return null;
    }

    /** Filled in by the patch: where the story's video is, in milliseconds, or -1 without a player. */
    static int position(Object viewer) {
        return NOT_PATCHED;
    }

    /** Filled in by the patch: how long the story's video is, in milliseconds, or -1 for a photo. */
    static int duration(Object viewer) {
        return NOT_PATCHED;
    }

    /**
     * Filled in by the patch: moves the story's video to [positionMs], which the player keeps inside
     * the video. A live story's player throws. True once it's called the player.
     */
    static boolean seekTo(Object viewer, int positionMs) {
        return false;
    }

    /** Filled in by the patch: pauses the story as a press and hold does, video and timer. */
    static boolean pause(Object viewer, String reason) {
        return false;
    }

    /** Filled in by the patch: plays the story on, as letting go of a hold does. */
    static boolean resume(Object viewer) {
        return false;
    }
}
