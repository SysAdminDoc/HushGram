/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * The frame rate Upload stories at full quality asks for ({@link StoryUploadQuality}, #37). A floor.
 * Past 60 it's asked only where the phone's H.264 encoder takes that rate, and 60 otherwise.
 */
public enum UploadFrameRate {
    INSTAGRAM(0),
    FPS_60(60),
    FPS_120(120);

    /** Frames a second, 0 for Instagram's own. */
    public final int fps;

    UploadFrameRate(int fps) {
        this.fps = fps;
    }
}
