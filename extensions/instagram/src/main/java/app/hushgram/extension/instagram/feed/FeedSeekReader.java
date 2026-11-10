/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * What the Seek bar on feed videos patch fills in: the cover of the video the feed's video player
 * has, which sits in the video's frame, and the pause, seek and play Instagram's own feed scrubber
 * makes. A stub works in its own parameters, which is why the pause and the play take spare ones.
 *
 * <p>Apart from {@link FeedSeekBar} for the reason {@link FeedPlayerReader} is: a stub the phone's
 * verifier refused would take the class the player's progress callback calls down with it. Only
 * FeedSeekBar reaches this class, inside its tries. Each stub is handed the player,
 * IgFeedVideoPlayer, and works in its own parameters.
 */
final class FeedSeekReader {
    private FeedSeekReader() { }

    /** Filled in by the patch: the cover of the video the player has now, or null while it has none. */
    @Nullable
    static View view(Object player) {
        return null;
    }

    /**
     * Filled in by the patch: moves the player's video to [positionMs] through its controller, with
     * [userSeek] the flag Instagram's scrubber passes it. True once it's called the controller.
     */
    static boolean seekTo(Object player, int positionMs, boolean userSeek) {
        return false;
    }

    /**
     * Filled in by the patch: pauses the player the way Instagram's feed scrubber does when a finger
     * lands on it, with its reason loaded into [spare]. Answers an Object: the player once paused,
     * null while unfilled.
     */
    @Nullable
    static Object pause(Object player, @Nullable Object spare) {
        return null;
    }

    /**
     * Filled in by the patch: plays the player on the way Instagram's feed scrubber does when a drag
     * lets go, with its reason and flags loaded into the spares. Answers an Object: the player once
     * played, null while unfilled.
     */
    @Nullable
    static Object play(Object player, @Nullable Object spare1, @Nullable Object spare2, @Nullable Object spare3) {
        return null;
    }

    /** Filled in by the patch: the post the player has now, or null while it has none. */
    @Nullable
    static Object media(Object player) {
        return null;
    }

    /** Filled in by the patch: [media]'s picture strip, by the names in Instagram's JSON, or null when it has none. */
    @Nullable
    static Map<?, ?> sheet(Object media) {
        return null;
    }

    /**
     * Filled in by the patch, when the build has it: the picture strip kept with [media]'s cover
     * pictures, by the names in Instagram's JSON, or null when it has none. Reels carry theirs there.
     */
    @Nullable
    static Map<?, ?> scrubberSheet(Object media) {
        return null;
    }
}
