/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The bubble a note sits in, under Pure black dark mode. Instagram fills it with its own notes
 * color, a dark gray at night (#543): the row over the inbox is Compose, and the older bubble view
 * takes its color through a setter.
 */
public final class NoteBubble {
    /** The brightest channel a dark theme's fill has; the night gray is 0x36 at most. */
    private static final int DARK_CHANNEL = 0x60;

    /** How far a gray's channels sit apart at most; the night gray's are 0x0b apart. */
    private static final int GRAY_SPREAD = 0x10;

    private static final int BLACK = 0xff000000;

    private static volatile boolean logged;

    private NoteBubble() {
    }

    /**
     * Injected where the bubble takes its fill. Answers black for an opaque dark gray and {@code color}
     * itself for anything else, so the day theme's white and a note's own dark color stay. Never throws.
     */
    public static int color(int color) {
        try {
            HookStatus.invoked(FamilyNames.PURE_BLACK);
            if (color >>> 24 != 0xff) return color;
            int red = (color >> 16) & 0xff;
            int green = (color >> 8) & 0xff;
            int blue = color & 0xff;
            int brightest = Math.max(red, Math.max(green, blue));
            int dimmest = Math.min(red, Math.min(green, blue));
            return brightest > DARK_CHANNEL || brightest - dimmest > GRAY_SPREAD ? color : BLACK;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "note bubble", failure);
            return color;
        }
    }

    /**
     * Injected where the notes row over the inbox, which is Compose, has picked a note's fill. A
     * Compose color keeps its ARGB in the high half and its color space in the low half: an sRGB
     * one goes through {@link #color(int)}, and one outside sRGB stays as it is. Never throws.
     */
    public static long card(long color) {
        try {
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Pure black: a note's fill in the inbox is " + Long.toHexString(color));
            }
            if ((color & 0xffffffffL) != 0) return color;
            int argb = (int) (color >>> 32);
            int filled = color(argb);
            return filled == argb ? color : (long) filled << 32;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "note card", failure);
            return color;
        }
    }
}
