/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * The JPEG quality Upload stories at full quality asks for on a photo ({@link StoryUploadQuality},
 * #37). A floor, as Instagram sometimes picks 95 itself.
 */
public enum UploadPhotoQuality {
    INSTAGRAM(0),
    Q90(90),
    Q95(95),
    Q100(100);

    /** JPEG quality, 0 for Instagram's own. */
    public final int percent;

    UploadPhotoQuality(int percent) {
        this.percent = percent;
    }
}
