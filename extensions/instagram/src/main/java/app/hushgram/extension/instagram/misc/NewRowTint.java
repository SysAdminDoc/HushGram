/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A new notification's row under Pure black dark mode. Instagram tints it with a color of its
 * Compose theme, which reads as gray over black (#374).
 */
public final class NewRowTint {
    /** Prism's dark tint, white at 15 percent. */
    private static final int PRISM_DARK = 0x26ffffff;

    /** The older dark theme's tint, an opaque gray. */
    private static final int CLASSIC_DARK = 0xff363636;

    private static final long BLACK = 0xff000000L << 32;

    private NewRowTint() {
    }

    /**
     * Injected after each Notifications read of the theme's new row tint. Answers black for a dark
     * theme's tint and {@code color} itself for anything else, so light mode's blue stays. A Compose
     * color keeps its ARGB in the high half and its color space in the low half. Never throws.
     */
    public static long color(long color) {
        try {
            HookStatus.invoked(FamilyNames.PURE_BLACK);
            int argb = (int) (color >>> 32);
            if ((color & 0xffffffffL) != 0 || (argb != PRISM_DARK && argb != CLASSIC_DARK)) return color;
            return BLACK;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "new row tint", failure);
            return color;
        }
    }
}
