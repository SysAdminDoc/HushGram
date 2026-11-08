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
    /** What {@link HookStatus} counts: a tap sent to mute, a tap left to pause the reel, and a tap that left it playing. */
    static final String TAP_MUTED = "taps that muted";
    static final String TAP_PAUSED = "taps that paused";
    static final String TAP_KEPT_PLAYING = "taps that kept it playing";

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
            // Keep playing is asked next, by keepPlaying, and the reel doesn't pause.
            if (choice == ReelTapChoice.KEEP_PLAYING) return false;
            if (choice == ReelTapChoice.PAUSE) HookStatus.counted(FamilyNames.REEL_TAP_AND_VOLUME, TAP_PAUSED);
            // The reel is about to pause: Burn-in guard's Light when paused (#457) brings the controls back.
            BurnInGuard.reelPaused();
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_TAP_AND_VOLUME, TAP, failure);
            return false;
        }
    }

    /**
     * Asked on the same pause path after {@link #muteInsteadOfPause} said no. True means leave the
     * playing reel as it is: the tap does nothing, so a stray touch doesn't stop it (#554). A paused
     * reel never gets here, so a tap still plays it. Never throws.
     */
    public static boolean keepPlaying() {
        try {
            if (!Utils.settingsReady() || Settings.REEL_TAP_CHOICE.get() != ReelTapChoice.KEEP_PLAYING) return false;
            HookStatus.counted(FamilyNames.REEL_TAP_AND_VOLUME, TAP_KEPT_PLAYING);
            Logger.printDebug(() -> "Reel tap: kept playing");
            return true;
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
