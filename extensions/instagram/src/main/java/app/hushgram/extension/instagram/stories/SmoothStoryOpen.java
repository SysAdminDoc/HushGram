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
 * Helper for the "Smooth story opening" patch.
 *
 * <p>A story tapped in the stories row grows out of its ring on a spring, which the story viewer's
 * animator builds once with a stiffness and a damping ratio from Instagram's server config. At the
 * stiffness 453 ships, 1500, the zoom is over in well under a fifth of a second, and a damping
 * ratio under 1 lets it overshoot and settle back. The patch passes both through here right before
 * the animator hands them to the spring: while the switch is on, the stiffness is eased down to
 * {@link #STIFFNESS} and the damping raised to {@link #DAMPING}, so the story opens more slowly and
 * stops without a bounce. The same spring closes it again.
 */
public final class SmoothStoryOpen {
    /**
     * The stiffness the spring gets at most while the switch is on. A stiffer one from Instagram is
     * eased to this, a softer one is kept.
     */
    static final float STIFFNESS = 250f;
    /** The damping ratio the spring gets at least: critically damped, so it never overshoots. */
    static final float DAMPING = 1f;

    private static volatile boolean logged;

    private SmoothStoryOpen() {
    }

    /**
     * Injected right before the animator sets its spring's stiffness. Answers {@link #STIFFNESS}
     * while the switch is on and [stiffness] is stiffer, and [stiffness] itself otherwise, while
     * paused, before the settings are ready, or when anything goes wrong. Never throws.
     */
    public static float stiffness(float stiffness) {
        try {
            HookStatus.invoked(FamilyNames.SMOOTH_STORY_OPEN);
            if (!(stiffness > STIFFNESS) || !on()) return stiffness;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Smooth story opening: easing the story spring from " + stiffness + " to " + STIFFNESS);
            }
            return STIFFNESS;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SMOOTH_STORY_OPEN, "spring stiffness", failure);
            return stiffness;
        }
    }

    /**
     * Injected right before the animator sets its spring's damping ratio. Answers {@link #DAMPING}
     * while the switch is on and [damping] is lower, and [damping] itself otherwise (NaN included),
     * while paused, before the settings are ready, or when anything goes wrong. Never throws.
     */
    public static float damping(float damping) {
        try {
            HookStatus.invoked(FamilyNames.SMOOTH_STORY_OPEN);
            if (!(damping < DAMPING) || !on()) return damping;
            return DAMPING;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SMOOTH_STORY_OPEN, "spring damping", failure);
            return damping;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.SMOOTH_STORY_OPEN.get();
    }
}
