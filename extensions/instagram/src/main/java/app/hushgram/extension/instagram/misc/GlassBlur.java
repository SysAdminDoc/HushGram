/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import androidx.annotation.Nullable;

/**
 * The three blur steps the glass tab bar had before its blur became a slider (#289, #391). Kept so a
 * step saved by an older build, or carried in its settings file, comes back as the radius it drew.
 */
public enum GlassBlur {
    LIGHT(9),
    STANDARD(18),
    STRONG(30);

    /** The blur radius in dp this step drew. */
    public final int dp;

    GlassBlur(int dp) {
        this.dp = dp;
    }

    /** The radius in dp a step saved under [name] drew, or null for a name that was never a step. */
    @Nullable
    public static Integer dpOf(@Nullable String name) {
        for (GlassBlur step : values()) {
            if (step.name().equals(name)) return step.dp;
        }
        return null;
    }

    /**
     * The blur radius in screen pixels for [dp] at [density], whole pixels as the bar's other sizes are.
     * The backdrop also reaches this far past the pill, so the blur has something to blur at the edge.
     */
    public static int radiusPx(int dp, float density) {
        return Math.round(Math.max(0, dp) * density);
    }

    /** The radius the blur effect gets, with the backdrop worked out at [scale] of its size. At least 1. */
    public static float effectRadius(int dp, float density, float scale) {
        return Math.max(1f, radiusPx(dp, density) * scale);
    }
}
