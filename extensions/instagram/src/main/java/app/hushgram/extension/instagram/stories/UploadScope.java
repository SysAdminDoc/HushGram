/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/** Which uploads Upload stories at full quality covers ({@link StoryUploadQuality}, #37). */
public enum UploadScope {
    /** Video stories only, the way the switch has always worked. */
    STORIES,
    /** Videos shared as posts, reels, stories and messages. */
    ALL
}
