/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * The video bitrate Upload stories at full quality asks for ({@link StoryUploadQuality}, #37).
 * Instagram's own leaves whatever it worked out. Anything else is a floor, since a bitrate Instagram
 * already picked above it stays. 50 Mbps is the top, which phone encoders take at 1080p.
 */
public enum UploadBitrate {
    INSTAGRAM(0),
    MBPS_12(12),
    MBPS_16(16),
    MBPS_20(20),
    MBPS_25(25),
    MBPS_35(35),
    MBPS_50(50);

    /** Megabits a second, 0 for Instagram's own. */
    public final int megabits;

    UploadBitrate(int megabits) {
        this.megabits = megabits;
    }

    /** Bits a second. */
    public int bitsPerSecond() {
        return megabits * 1_000_000;
    }
}
