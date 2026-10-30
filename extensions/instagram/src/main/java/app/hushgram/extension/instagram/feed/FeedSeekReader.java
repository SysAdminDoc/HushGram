/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * What the Seek bar on feed videos patch fills in: the cover of the video the feed's video player
 * has, which sits in the video's frame, and the seek its controller makes for Instagram's own feed
 * scrubber.
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
}
