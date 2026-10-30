/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import androidx.annotation.Nullable;

/**
 * The four see-through steps the glass tab bar's pill had before its opacity became a slider (#391).
 * Each scaled the strength of the tint laid over the blur, and {@link #STANDARD} was the look the bar
 * has always had. Kept so a step saved by an older build, or carried in its settings file, comes back
 * as the percent it drew.
 */
public enum GlassOpacity {
    CLEAR(50),
    LIGHT(75),
    STANDARD(100),
    FROSTED(135);

    /** What the tint's alpha was multiplied by, in percent. */
    public final int percent;

    GlassOpacity(int percent) {
        this.percent = percent;
    }

    /** The percent a step saved under [name] drew, or null for a name that was never a step. */
    @Nullable
    public static Integer percentOf(@Nullable String name) {
        for (GlassOpacity step : values()) {
            if (step.name().equals(name)) return step.percent;
        }
        return null;
    }
}
