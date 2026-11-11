/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * Where Seek bar on stories puts its bar (#636): just above the row along the story's bottom, as it
 * always did, or in the space between that row and the bottom of the screen. It holds no Android type
 * and reads no setting; the name a person reads is the settings screen's.
 */
public enum StorySeekBarPosition {
    ABOVE,
    BELOW
}
