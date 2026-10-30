/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * The size the rings in the stories row at the top of Home are drawn at, as a share of the size
 * Instagram picks for the screen. Instagram's own keeps every ring as it is.
 *
 * <p>It holds no Android type and reads no setting, and the name a person reads is the settings
 * screen's, in the phone's language. The shares run from 50% to 150% in tens (#223). The earlier
 * 70%, 85%, 115% and 130% stay under their first names, since a saved choice and a backup hold the
 * name, so a size picked before still draws the same.
 */
public enum StoryRingSize {
    PERCENT_50(0.5f),
    PERCENT_60(0.6f),
    SMALLEST(0.7f),
    PERCENT_80(0.8f),
    SMALLER(0.85f),
    PERCENT_90(0.9f),
    INSTAGRAM(1f),
    PERCENT_110(1.1f),
    LARGER(1.15f),
    PERCENT_120(1.2f),
    LARGEST(1.3f),
    PERCENT_140(1.4f),
    PERCENT_150(1.5f);

    /** What a ring's size is multiplied by. */
    public final float scale;

    StoryRingSize(float scale) {
        this.scale = scale;
    }

    /** The scale as a whole percentage, such as 85 for {@link #SMALLER}. */
    public int percent() {
        return Math.round(scale * 100);
    }
}
