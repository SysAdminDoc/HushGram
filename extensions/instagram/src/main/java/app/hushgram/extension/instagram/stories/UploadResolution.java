/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * The video width Upload stories at full quality asks for ({@link StoryUploadQuality}, #37). A floor,
 * and never more than the clip has: Instagram's width step only goes as wide as the source. 1440 is
 * the top that step knows.
 */
public enum UploadResolution {
    INSTAGRAM(0),
    P1080(1080),
    P1440(1440);

    /** Pixels wide, 0 for Instagram's own. */
    public final int width;

    UploadResolution(int width) {
        this.width = width;
    }
}
