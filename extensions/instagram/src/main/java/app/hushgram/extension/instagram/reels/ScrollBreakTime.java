/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

/**
 * How long Home's feed, Reels and Explore can be on screen before Scroll break covers them for the
 * minutes its slider picks ({@link ScrollBreak}, #297). Off to start. Saved by name, so a time added
 * between two others leaves a saved choice as it was.
 */
public enum ScrollBreakTime {
    OFF(0),
    FIVE_MINUTES(5),
    TEN_MINUTES(10),
    FIFTEEN_MINUTES(15),
    TWENTY_MINUTES(20),
    THIRTY_MINUTES(30),
    FORTY_FIVE_MINUTES(45),
    SIXTY_MINUTES(60),
    NINETY_MINUTES(90),
    TWO_HOURS(120);

    public final int minutes;

    ScrollBreakTime(int minutes) {
        this.minutes = minutes;
    }

    public long millis() {
        return minutes * 60_000L;
    }
}
