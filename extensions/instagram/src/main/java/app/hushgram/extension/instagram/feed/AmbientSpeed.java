/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

/**
 * How quickly Ambient light's glow follows what a video shows (#416). It holds no Android type and
 * reads no setting; the name a person reads is the settings screen's.
 */
public enum AmbientSpeed {
    SLOW(900, 1200),
    MEDIUM(350, 450),
    FAST(120, 160);

    /** How long between two looks at the picture, and how long the glow takes to fade to a new color, in milliseconds. */
    public final int sampleMillis, fadeMillis;

    AmbientSpeed(int sampleMillis, int fadeMillis) {
        this.sampleMillis = sampleMillis;
        this.fadeMillis = fadeMillis;
    }
}
