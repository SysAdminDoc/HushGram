/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

/**
 * The shapes a seek bar can take (#586, #567, #592). {@link #STANDARD} leaves a bar as it's always
 * been. The rest are drawn by {@link SeekShapeDrawable}, and all of them are one drawable and no
 * handle of Android's, so a shape works on a bar HushGram added and on one Instagram made.
 */
public enum SeekShape {
    STANDARD,
    /** A thin line with no handle, which grows while it's held. */
    LINE,
    /** A thicker bar with round ends and no handle, like One UI's media bar. */
    ROUNDED,
    /** A rounded bar split around a tall handle, like Material 3 Expressive's. */
    EXPRESSIVE,
    /** A wavy line for the part played and a tall handle, like Android's media controls. */
    WAVE;

    /** Whether it has a handle taller than its track, which needs room above and below the track. */
    boolean tallHandle() {
        return this == EXPRESSIVE || this == WAVE;
    }
}
