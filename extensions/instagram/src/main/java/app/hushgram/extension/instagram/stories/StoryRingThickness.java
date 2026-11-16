/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * How thick the story rings are drawn, as a share of the width Instagram gives them (#528).
 * Instagram's own keeps every ring as it is.
 *
 * <p>It holds no Android type and reads no setting, and the name a person reads is the settings
 * screen's, in the phone's language. A saved choice and a backup hold the name, so names stay.
 */
public enum StoryRingThickness {
    PERCENT_50(0.5f),
    PERCENT_75(0.75f),
    INSTAGRAM(1f),
    PERCENT_150(1.5f),
    PERCENT_200(2f);

    /** What a ring's width is multiplied by. */
    public final float scale;

    StoryRingThickness(float scale) {
        this.scale = scale;
    }

    /** The scale as a whole percentage, such as 150 for {@link #PERCENT_150}. */
    public int percent() {
        return Math.round(scale * 100);
    }
}
