/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

/**
 * How long after you leave Instagram {@link MessagesLock} locks again. Right away is what the lock
 * did before there was a choice, so no one's lock changes until they pick.
 */
public enum LockDelay {
    RIGHT_AWAY(0),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(5 * 60_000L),
    FIFTEEN_MINUTES(15 * 60_000L),
    ONE_HOUR(60 * 60_000L);

    /** Time away from Instagram, in milliseconds, after which it locks. */
    public final long millis;

    LockDelay(long millis) {
        this.millis = millis;
    }
}
