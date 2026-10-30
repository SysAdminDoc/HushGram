/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/**
 * How long the Reels screen goes untouched before the glass tab bar slides away there (#170).
 * {@link #NEVER}, the default, leaves the bar on every reel, as it has always been. The wait and the
 * ways back are {@link GlassScrollHide}'s.
 */
public enum GlassReelsHide {
    NEVER(0),
    TWO_SECONDS(2_000L),
    THREE_SECONDS(3_000L),
    FIVE_SECONDS(5_000L),
    EIGHT_SECONDS(8_000L);

    /** The wait in milliseconds, or 0 for never. */
    public final long millis;

    GlassReelsHide(long millis) {
        this.millis = millis;
    }
}
