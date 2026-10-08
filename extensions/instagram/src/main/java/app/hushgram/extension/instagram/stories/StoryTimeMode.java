/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * How {@link StoryTime} writes a story's time: the date and time it was posted, which is all the
 * switch did before there was a choice, how long the story has left before it expires, or only the
 * time of day it was posted.
 */
public enum StoryTimeMode {
    DATE_AND_TIME,
    TIME_LEFT,
    TIME_POSTED
}
