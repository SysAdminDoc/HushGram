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
 * What the Control taps and volume on Reels patch asks. Its volume half is {@link #keepMuted}.
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

    /** What {@link HookStatus} counts for the volume keys: a volume up whose unmute was skipped. */
    static final String VOLUME_KEPT_MUTED = "volume ups kept muted";

    /** The hook's names in a failure report. */
    static final String TAP = "reel tap";
    static final String VOLUME = "reel volume key";

    /** {@link android.media.AudioManager#ADJUST_RAISE}, the direction a press of volume up hands the Reels runnable. */
    private static final int ADJUST_RAISE = 1;

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

    /**
     * Asked by the Reels controller's volume runnable after it has adjusted the phone's stream
     * volume, so the key is never swallowed, and before it unmutes the reel. {@code direction} is the
     * value it just gave AudioManager: 1 for volume up. {@code audioOn} is Instagram's own Reels sound
     * state, read the way its audio button reads it: 1 when sound is on, 0 when it's off, -1 when the
     * patch couldn't ask. True means skip the rest, which is Instagram's unmute, and only a volume up
     * on muted sound gets that: with sound already on the unmute runs, so a fade-in still finishes.
     * Never throws.
     */
    public static boolean keepMuted(int direction, int audioOn) {
        try {
            HookStatus.invoked(FamilyNames.REEL_TAP_AND_VOLUME);
            if (!Utils.settingsReady() || !Settings.KEEP_REELS_MUTED.get() || direction != ADJUST_RAISE) return false;
            if (audioOn != 0) return false;
            HookStatus.counted(FamilyNames.REEL_TAP_AND_VOLUME, VOLUME_KEPT_MUTED);
            Logger.printDebug(() -> "Reel volume key: unmute skipped");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_TAP_AND_VOLUME, VOLUME, failure);
            return false;
        }
    }
}
