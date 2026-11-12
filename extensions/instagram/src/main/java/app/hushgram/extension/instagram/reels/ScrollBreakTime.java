/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

/**
 * How long Home's feed, Reels and Explore can be on screen before Scroll break covers them for a
 * few minutes ({@link ScrollBreak}, #297). Off to start.
 */
public enum ScrollBreakTime {
    OFF(0),
    TEN_MINUTES(10),
    TWENTY_MINUTES(20),
    THIRTY_MINUTES(30),
    SIXTY_MINUTES(60);

    public final int minutes;

    ScrollBreakTime(int minutes) {
        this.minutes = minutes;
    }

    public long millis() {
        return minutes * 60_000L;
    }
}
