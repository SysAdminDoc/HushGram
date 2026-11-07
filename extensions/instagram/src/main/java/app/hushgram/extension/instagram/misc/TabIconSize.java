/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/**
 * How big the icons on the tab bar are drawn, as a share of the size Instagram gives them (#541).
 * Instagram's own keeps every icon as it is.
 *
 * <p>It holds no Android type and reads no setting. A saved choice and a backup hold the name, so
 * the names stay as they are.
 */
public enum TabIconSize {
    INSTAGRAM(1f),
    PERCENT_110(1.1f),
    PERCENT_120(1.2f),
    PERCENT_130(1.3f),
    PERCENT_140(1.4f),
    PERCENT_150(1.5f);

    /** What an icon's size is multiplied by. */
    public final float scale;

    TabIconSize(float scale) {
        this.scale = scale;
    }

    /** The scale as a whole percentage, such as 120 for {@link #PERCENT_120}. */
    public int percent() {
        return Math.round(scale * 100);
    }
}
