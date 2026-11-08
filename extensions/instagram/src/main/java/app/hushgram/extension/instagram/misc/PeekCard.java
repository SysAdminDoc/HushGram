/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The Instagram Plus card over the menu you get by holding a chat in the inbox, under Pure black
 * dark mode. It's Compose and fills itself with the context menu's own color, a dark gray at night
 * that the view menu under it no longer shares (#543).
 */
public final class PeekCard {
    /** The brightest channel a dark theme's fill has. */
    private static final int DARK_CHANNEL = 0x60;

    /** How far a gray's channels sit apart at most. */
    private static final int GRAY_SPREAD = 0x10;

    private static final long BLACK = 0xff000000L << 32;

    private static volatile boolean logged;

    private PeekCard() {
    }

    /**
     * Injected after the card reads its fill. Answers opaque black for a dark gray, see-through or
     * not, since the card would show the blur behind it, and {@code color} itself for anything else,
     * so the day theme's light card stays. A Compose color keeps its ARGB in the high half and its
     * color space in the low half; one outside sRGB stays as it is. Never throws.
     */
    public static long color(long color) {
        try {
            HookStatus.invoked(FamilyNames.PURE_BLACK);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Pure black: the Instagram Plus card's fill is " + Long.toHexString(color));
            }
            if ((color & 0xffffffffL) != 0) return color;
            int argb = (int) (color >>> 32);
            if (argb >>> 24 == 0) return color;
            int red = (argb >> 16) & 0xff;
            int green = (argb >> 8) & 0xff;
            int blue = argb & 0xff;
            int brightest = Math.max(red, Math.max(green, blue));
            int dimmest = Math.min(red, Math.min(green, blue));
            return brightest > DARK_CHANNEL || brightest - dimmest > GRAY_SPREAD ? color : BLACK;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "Instagram Plus card", failure);
            return color;
        }
    }
}
