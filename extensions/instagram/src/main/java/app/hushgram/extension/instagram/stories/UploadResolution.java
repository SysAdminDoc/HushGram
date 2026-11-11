/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * The video width Upload stories at full quality asks for ({@link StoryUploadQuality}, #37). A floor,
 * and never more than the clip has: Instagram's width step only goes as wide as the source. 1440 is
 * the top that step knows, so 2160 asks it for 1440 and is raised past its cap only where the phone's
 * H.264 encoder takes the size.
 */
public enum UploadResolution {
    INSTAGRAM(0),
    P1080(1080),
    P1440(1440),
    P2160(2160);

    /** The widest Instagram's width step knows by name. */
    static final int STEP_TOP = 1440;

    /** Pixels wide, 0 for Instagram's own. */
    public final int width;

    UploadResolution(int width) {
        this.width = width;
    }
}
