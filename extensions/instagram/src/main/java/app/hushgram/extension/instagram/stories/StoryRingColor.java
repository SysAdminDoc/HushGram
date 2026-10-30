/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * The color the ring around a story that hasn't been watched is drawn in. Instagram's own keeps
 * its gradient as it is.
 *
 * <p>Each color is two close shades the ring runs between. Instagram draws the same ring on a
 * white page, on a black one and over a Reel, so every shade stands out by at least
 * {@link #LEAST_CONTRAST} to one against both white and black. That is also why there is no white
 * and no black here: one of them would vanish on half the pages. There is no green and no pink
 * either, since those are the rings of Close Friends and of a Live, which this choice leaves as
 * they are.
 *
 * <p>It holds no Android type and reads no setting, and the name a person reads is the settings
 * screen's, in the phone's language.
 */
public enum StoryRingColor {
    INSTAGRAM(0, 0),
    BLUE(0xFF2F8CFF, 0xFF3D5AFE),
    TEAL(0xFF009E9E, 0xFF00897B),
    PURPLE(0xFF9B5CFF, 0xFFB14BE0),
    RED(0xFFFF3B30, 0xFFE0010D),
    ORANGE(0xFFF26B0F, 0xFFE0461F),
    GOLD(0xFFC58500, 0xFFB86E00);

    /** The least a shade stands out against white and against black, as a contrast ratio to one. */
    static final double LEAST_CONTRAST = 3.0;

    /** The shade the ring starts from, opaque. Zero for {@link #INSTAGRAM}, which has none. */
    public final int from;
    /** The shade the ring ends at, opaque. Zero for {@link #INSTAGRAM}, which has none. */
    public final int to;

    StoryRingColor(int from, int to) {
        this.from = from;
        this.to = to;
    }

    /**
     * The ring's colors for a gradient of {@code count} stops: the two shades with an even blend
     * between them, in a new array each time since the gradient's builder owns what it is given.
     * Null for {@link #INSTAGRAM} and for a count below two, which leave the ring alone.
     */
    public int[] colors(int count) {
        if (this == INSTAGRAM || count < 2) return null;
        int[] colors = new int[count];
        for (int at = 0; at < count; at++) {
            colors[at] = blend(from, to, at / (float) (count - 1));
        }
        return colors;
    }

    private static int blend(int from, int to, float share) {
        return 0xFF000000
                | channel(from >> 16, to >> 16, share) << 16
                | channel(from >> 8, to >> 8, share) << 8
                | channel(from, to, share);
    }

    private static int channel(int from, int to, float share) {
        int start = from & 0xFF;
        return Math.round(start + ((to & 0xFF) - start) * share) & 0xFF;
    }
}
