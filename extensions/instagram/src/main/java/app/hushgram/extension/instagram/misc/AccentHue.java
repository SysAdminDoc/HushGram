/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/**
 * The colors Instagram's blue can turn into ({@link AccentColor}). {@link #BLUE} keeps Instagram's own.
 *
 * <p>Each color has three shades, all without alpha: the fill a button or a highlight takes, a darker
 * one for links on a light screen and a lighter one for links on a dark screen. White text on every
 * fill reads at least as well as it does on Instagram's own blue (a contrast of 3.9 or more against
 * its 3.2), and each link shade reads at better than 5 to 1 on its screen. The pressed shade, the dim one
 * and the pale one are worked out from the fill, as Instagram's are from its blue.
 *
 * <p>{@link #WALLPAPER} is Material You (#187, #264): its shades are Android 12's wallpaper tones,
 * read as Instagram runs ({@link MaterialYou}), so its own fields hold nothing.
 */
public enum AccentHue {
    BLUE(0, 0, 0),
    PURPLE(0x7638fa, 0x5806e0, 0xa07cfd),
    PINK(0xd6005a, 0xc1014f, 0xff6fa2),
    RED(0xe0010d, 0xc4000b, 0xff6b78),
    ORANGE(0xd95700, 0xb54800, 0xff9a40),
    GREEN(0x118841, 0x0d7a39, 0x3ee581),
    TEAL(0x00838f, 0x00727c, 0x4dd0d9),
    WALLPAPER(0, 0, 0);

    /** A button's or a highlight's color. */
    public final int fill;
    /** The fill held down, a little darker. */
    public final int pressed;
    /** The fill dimmed for a tinted background on a dark screen. */
    public final int dim;
    /** A link on a light screen. */
    public final int linkOnLight;
    /** A link on a dark screen. */
    public final int linkOnDark;
    /**
     * The fill most of the way to white, as Instagram's pale blue (#e0f1ff) is from its blue: a
     * selected pill on a light screen, and a link on a dark one in Instagram's older theme.
     */
    public final int pale;

    AccentHue(int fill, int linkOnLight, int linkOnDark) {
        this.fill = fill;
        this.pressed = scale(fill, 0.85f);
        this.dim = scale(fill, 0.45f);
        this.linkOnLight = linkOnLight;
        this.linkOnDark = linkOnDark;
        this.pale = towardWhite(fill, 0.878f);
    }

    /** [rgb] moved [share] of the way to white. */
    static int towardWhite(int rgb, float share) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        red += Math.round((0xff - red) * share);
        green += Math.round((0xff - green) * share);
        blue += Math.round((0xff - blue) * share);
        return (red << 16) | (green << 8) | blue;
    }

    /** [rgb] with each channel multiplied by [factor]. */
    static int scale(int rgb, float factor) {
        int red = Math.round(((rgb >> 16) & 0xff) * factor);
        int green = Math.round(((rgb >> 8) & 0xff) * factor);
        int blue = Math.round((rgb & 0xff) * factor);
        return (red << 16) | (green << 8) | blue;
    }
}
