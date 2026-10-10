/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/**
 * How see-through the glass tab bar's pill is. Each step scales the strength of the tint laid over the
 * blur (or over the bar's colour when there is no blur), so {@link #STANDARD}, the default, is exactly the
 * look the bar has always had and the others are a step clearer or a step more frosted.
 */
public enum GlassOpacity {
    CLEAR(0.5f),
    LIGHT(0.75f),
    STANDARD(1f),
    FROSTED(1.35f);

    /** What the tint's alpha is multiplied by. */
    public final float scale;

    GlassOpacity(float scale) {
        this.scale = scale;
    }
}
