/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

/**
 * The shape of the seek bar on feed videos alone (#567): {@link #SAME} follows the shape picked for
 * every seek bar, and the rest pick one of {@link SeekShape} for feed videos only, so a feed can have
 * the thin line while reels and stories keep the standard bar.
 */
public enum FeedSeekShape {
    SAME(null),
    STANDARD(SeekShape.STANDARD),
    LINE(SeekShape.LINE),
    ROUNDED(SeekShape.ROUNDED),
    EXPRESSIVE(SeekShape.EXPRESSIVE),
    WAVE(SeekShape.WAVE);

    @Nullable
    private final SeekShape shape;

    FeedSeekShape(@Nullable SeekShape shape) {
        this.shape = shape;
    }

    /** The shape feed videos take with this choice, or [everywhere] for {@link #SAME}. */
    public SeekShape or(SeekShape everywhere) {
        return shape != null ? shape : everywhere;
    }
}
