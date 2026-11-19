/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

/**
 * Where Tap to play holds starts ({@link TapToPlay}): everywhere, or any mix of the Reels viewer,
 * the Explore tab and everything else (#39, #762). Reels shown in the feed play in the feed, so they
 * go with the feed, and a reel opened from Explore plays in the Reels viewer, so it goes with
 * Reels. Saved by name, so the three choices there were before Explore had its own keep their
 * meaning: Everywhere but Reels still holds videos in Explore.
 */
public enum TapToPlayScope {
    EVERYWHERE,
    OUTSIDE_REELS,
    ONLY_REELS,
    OUTSIDE_EXPLORE,
    ONLY_EXPLORE,
    REELS_AND_EXPLORE,
    OUTSIDE_REELS_AND_EXPLORE;

    /** Where a start happens: the Reels viewer, Explore's tab with no reel over it, or anywhere else. */
    public enum Surface { REELS, EXPLORE, ELSEWHERE }

    /** Whether this choice holds a start on [surface]. */
    public boolean covers(Surface surface) {
        switch (this) {
            case OUTSIDE_REELS:
                return surface != Surface.REELS;
            case ONLY_REELS:
                return surface == Surface.REELS;
            case OUTSIDE_EXPLORE:
                return surface != Surface.EXPLORE;
            case ONLY_EXPLORE:
                return surface == Surface.EXPLORE;
            case REELS_AND_EXPLORE:
                return surface != Surface.ELSEWHERE;
            case OUTSIDE_REELS_AND_EXPLORE:
                return surface == Surface.ELSEWHERE;
            default:
                return true;
        }
    }

    /** Whether this choice treats Explore unlike the rest outside Reels, so a start there has to ask where it is. */
    public boolean splitsExplore() {
        return covers(Surface.EXPLORE) != covers(Surface.ELSEWHERE);
    }
}
