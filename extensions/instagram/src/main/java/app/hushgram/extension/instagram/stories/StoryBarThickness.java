/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * How much thicker the progress bar along the top of a story is drawn than Instagram draws it (#360).
 * It holds no Android type and reads no setting; the name a person reads is the settings screen's.
 */
public enum StoryBarThickness {
    TWICE(2f),
    THREE_TIMES(3f);

    /** What the bar's height is multiplied by. */
    public final float scale;

    StoryBarThickness(float scale) {
        this.scale = scale;
    }
}
