/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

/**
 * What the Seek bar on reels patch fills in: the id of the reel item the seek bar container's
 * binder reads. Apart from {@link ReelSeekBar} so a stub the phone's verifier refused can't take
 * the class the binder and the player's progress call down with it. Only ReelSeekBar reaches this
 * class, inside a try.
 */
final class ReelItemReader {
    private ReelItemReader() { }

    /** Filled in by the patch: the id of [item], a reel item, or null while unfilled. */
    @Nullable
    static String id(Object item) {
        return null;
    }
}
