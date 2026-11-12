/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

/**
 * What a single tap on a reel does ({@link ReelTapAndVolume}). Instagram's default leaves the tap
 * alone, pause is what Instagram 450's own tap does spelled out, and mute turns the sound off or on
 * and leaves the reel playing.
 */
public enum ReelTapChoice {
    DEFAULT,
    PAUSE,
    MUTE
}
