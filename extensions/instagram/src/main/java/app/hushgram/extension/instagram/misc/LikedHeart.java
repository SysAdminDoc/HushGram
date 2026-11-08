/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * The like button's line heart, which Instagram draws under the filled heart (#519).
 *
 * <p>Instagram's like heart draws the line heart at full strength first, then lays the filled heart
 * over it at the liked progress. The line heart's soft edge reaches about half a pixel past the filled
 * one, so a liked heart showed a thin light rim, which stands out once the fill is in the accent color.
 * With Accent color on and a color picked, a fully liked heart is drawn without its line heart.
 */
public final class LikedHeart {
    static final String COUNT_DROPPED = "liked hearts drawn without their line heart";

    private LikedHeart() {
    }

    /**
     * Injected in place of the like heart's call drawing its line heart, [outline], on [canvas], with
     * the heart's liked progress, [liked], one once the post is liked. Draws it unless the heart is
     * fully liked and Accent color is on. Anything thrown draws it as Instagram does.
     */
    public static void outline(Drawable outline, Canvas canvas, float liked) {
        if (outline == null) return;
        if (liked >= 1f && dropsOutline()) {
            HookStatus.counted(FamilyNames.ACCENT_COLOR, COUNT_DROPPED);
            return;
        }
        outline.draw(canvas);
    }

    /** Whether a fully liked heart is drawn without its line heart: Accent color on, a color picked, not paused. */
    static boolean dropsOutline() {
        try {
            return !HushgramPause.isPaused() && AccentColor.picked() != null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "liked heart", failure);
            return false;
        }
    }
}
