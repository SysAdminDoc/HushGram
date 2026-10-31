/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

/**
 * How far Ambient light's glow reaches out from a post or a reel (#416). It holds no Android type and
 * reads no setting; the name a person reads is the settings screen's.
 */
public enum AmbientSpread {
    NARROW(32),
    MEDIUM(64),
    WIDE(112);

    /** How far the glow reaches past the picture's edge, in dp. */
    public final int dp;

    AmbientSpread(int dp) {
        this.dp = dp;
    }
}
