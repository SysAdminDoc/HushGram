/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

/**
 * How long the Reels screen goes untouched before Burn-in guard dims a reel's controls
 * ({@link BurnInGuard}, #162). At once keeps them dimmed whenever no finger is on them.
 */
public enum BurnInWait {
    AT_ONCE(0L),
    TWO_SECONDS(2_000L),
    FIVE_SECONDS(5_000L),
    TEN_SECONDS(10_000L);

    public final long millis;

    BurnInWait(long millis) {
        this.millis = millis;
    }
}
