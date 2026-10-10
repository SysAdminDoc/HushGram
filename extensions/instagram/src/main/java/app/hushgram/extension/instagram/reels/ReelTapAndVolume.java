/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * What the Control taps and volume on Reels patch asks.
 *
 * <p>Instagram 450's Reels viewer sends a single tap on a reel to its pause and mute navigator,
 * whose tap method either resumes a reel you paused or pauses the one that is playing. The patch
 * asks {@link #muteInsteadOfPause} at the start of that pause path, after the navigator has decided
 * the reel is playing. A yes makes the patch's code turn the sound off or on through the
 * controller's own toggle (the one the audio button calls) and return, so the reel keeps playing.
 * A reel you paused still resumes on a tap, a double tap still likes and a long press does what it
 * did.
 *
 * <p>Every answer is Instagram's own while the choice is Instagram's default, HushGram is paused,
 * the settings aren't ready, or anything in here throws.
 */
public final class ReelTapAndVolume {
    /** What {@link HookStatus} counts: a tap sent to mute, and a tap left to pause the reel. */
    static final String TAP_MUTED = "taps that muted";
    static final String TAP_PAUSED = "taps that paused";

    /** The hook's name in a failure report. */
    static final String TAP = "reel tap";

    private ReelTapAndVolume() {
    }

    /**
     * Asked at the start of the Reels tap's pause path, so only a tap on a playing reel gets here.
     * True means mute the reel instead of pausing it. Never throws.
     */
    public static boolean muteInsteadOfPause() {
        try {
            HookStatus.invoked(FamilyNames.REEL_TAP_AND_VOLUME);
            if (!Utils.settingsReady()) return false;
            ReelTapChoice choice = Settings.REEL_TAP_CHOICE.get();
            if (choice == ReelTapChoice.MUTE) {
                HookStatus.counted(FamilyNames.REEL_TAP_AND_VOLUME, TAP_MUTED);
                Logger.printDebug(() -> "Reel tap: muting instead of pausing");
                return true;
            }
            if (choice == ReelTapChoice.PAUSE) HookStatus.counted(FamilyNames.REEL_TAP_AND_VOLUME, TAP_PAUSED);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_TAP_AND_VOLUME, TAP, failure);
            return false;
        }
    }
}
