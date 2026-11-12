/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Story ring size" patch.
 *
 * <p>Instagram works out the size of each item in the stories row from the screen's width: about
 * 3.75 items a screen, kept between 66dp and 100dp. The ring, the picture inside it and the space
 * around it all follow from that one size. The patch passes the size through {@link #size} wherever
 * Instagram settles it, so everything in the row grows or shrinks together.
 *
 * <p>The ring itself is one view that builds its gradient in one place, from the colors and stops
 * it was given. The patch passes those through {@link #colors} first, which swaps the colors of
 * Instagram's own story gradient for the chosen ones. Nothing is kept: Instagram still holds its
 * own colors, and asks again each time a ring is measured or bound to another story.
 */
public final class StoryRing {
    /**
     * The four stops of Instagram's own story gradient, the yellow to lavender one around a story
     * that hasn't been watched. No other ring has them: Close Friends, subscribers and a Live's
     * pink are one flat color from 0 to 1, and the rainbow has seven. The patch fails unless the
     * app still builds its default ring with these.
     */
    static final float FIRST_STOP = 0.1435f;
    static final float SECOND_STOP = 0.3779f;
    static final float THIRD_STOP = 0.6071f;
    static final float FOURTH_STOP = 0.831f;
    /** How far a stop may be from its number and still be it, since Instagram keeps them as doubles. */
    private static final float STOP_SLACK = 0.001f;

    private static final float[] INSTAGRAM_STOPS = {FIRST_STOP, SECOND_STOP, THIRD_STOP, FOURTH_STOP};

    private static volatile boolean logged;
    private static volatile boolean colorLogged;

    private StoryRing() {
    }

    /**
     * Injected right after Instagram settles a stories row item's size, in pixels. Answers [size]
     * times the chosen share while the switch is on, and [size] itself otherwise, or when anything
     * goes wrong. Never throws, and never waits for the settings: before they're ready the row keeps
     * Instagram's size.
     */
    public static float size(float size) {
        try {
            HookStatus.invoked(FamilyNames.STORY_RING);
            if (!(size > 0f) || !Utils.settingsReady() || !Settings.STORY_RING.get()) return size;
            StoryRingSize chosen = Settings.STORY_RING_SCALE.get();
            if (chosen == null || chosen == StoryRingSize.INSTAGRAM) return size;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Story ring size: drawing the stories row at " + chosen.percent() + "%");
            }
            return size * chosen.scale;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_RING, "ring size", failure);
            return size;
        }
    }

    /**
     * Injected first where a ring builds its gradient, with the colors and the stops it was given.
     * Answers the chosen color's shades, as many as Instagram gave, when these are Instagram's own
     * story gradient and the switch is on. Answers [colors] itself for every other ring (Close
     * Friends, subscribers, a Live's pink, the rainbow, a spinner that is loading), for Instagram's
     * own choice, while paused or before the settings are ready, and when anything goes wrong. It
     * never changes the array it was given, which Instagram keeps, and never throws.
     *
     * <p>A watched story's ring isn't built here at all, so it stays grey. A Live that Instagram
     * rings with its story gradient rather than its pink takes the chosen color too, since it is
     * the same gradient, and keeps its LIVE badge.
     */
    public static int[] colors(int[] colors, float[] stops) {
        try {
            HookStatus.invoked(FamilyNames.STORY_RING);
            if (!instagramsOwn(colors, stops) || !Utils.settingsReady() || !Settings.STORY_RING.get()) return colors;
            StoryRingColor chosen = Settings.STORY_RING_COLOR.get();
            if (chosen == null) return colors;
            int[] shades = chosen.colors(colors.length);
            if (shades == null) return colors;
            if (!colorLogged) {
                colorLogged = true;
                Logger.printDebug(() -> "Story ring size: drawing unwatched story rings in " + chosen.name());
            }
            return shades;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_RING, "ring color", failure);
            return colors;
        }
    }

    /**
     * Whether these are the colors and stops of Instagram's own story gradient: its four stops,
     * and more than one color between them. A ring of one color is a state, never a story's.
     */
    static boolean instagramsOwn(int[] colors, float[] stops) {
        if (colors == null || stops == null) return false;
        if (colors.length != INSTAGRAM_STOPS.length || stops.length != INSTAGRAM_STOPS.length) return false;
        for (int at = 0; at < INSTAGRAM_STOPS.length; at++) {
            if (!(Math.abs(stops[at] - INSTAGRAM_STOPS[at]) <= STOP_SLACK)) return false;
        }
        for (int at = 1; at < colors.length; at++) {
            if (colors[at] != colors[0]) return true;
        }
        return false;
    }
}
