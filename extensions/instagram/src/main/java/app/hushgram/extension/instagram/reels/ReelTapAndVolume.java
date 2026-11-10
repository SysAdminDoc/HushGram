/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.download.ReelMuteButton;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

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
 * <p>The speaker icon beside a reel (#608) goes through the same toggle. The patch hands this class
 * the controller each time it starts a reel ({@link #resuming}), so a tap on the icon flips the
 * sound of the reel on screen ({@link #toggle}) and the icon reads it the way the toggle does
 * ({@link #soundOn}).
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

    /** What {@link HookStatus} counts for the speaker icon (#608): a tap that turned the sound off or on. */
    static final String ICON_TOGGLED = "speaker icon taps";

    /** {@link android.media.AudioManager#ADJUST_RAISE}, the direction a press of volume up hands the Reels runnable. */
    private static final int ADJUST_RAISE = 1;

    /** The Reels controller that started a reel last, weakly: the one on screen, for the speaker icon. */
    @Nullable
    private static volatile WeakReference<Object> onScreen;

    private ReelTapAndVolume() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the controller's audio toggle, called the way its audio button calls it. Only a controller may be passed. */
    public static void toggleAudio(Object controller) {
    }

    /**
     * Filled in by the patch: whether the controller's reel plays with sound, read the way its audio
     * toggle reads it, 1 on, 0 off, -1 when it can't be told. Only a controller may be passed.
     */
    public static int audioOn(Object controller) {
        return -1;
    }

    // ------------------------------------------------------------------ the speaker icon (#608)

    /** The hook, first thing in the Reels controller's maybeResumePlayer: the controller about to start a reel. Never throws. */
    public static void resuming(@Nullable Object controller) {
        try {
            if (controller == null) return;
            WeakReference<Object> held = onScreen;
            if (held == null || held.get() != controller) onScreen = new WeakReference<>(controller);
            // A new reel may start muted or with sound, so the icon reads it again.
            ReelMuteButton.refresh();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_TAP_AND_VOLUME, "reel start", failure);
        }
    }

    /**
     * The speaker icon's tap: turns the sound of the reel on screen off or on through its controller's
     * own toggle, so the reel keeps playing. False when HushGram is paused or no reel has started since
     * Instagram did. Never throws.
     */
    public static boolean toggle() {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return false;
            Object controller = controller();
            if (controller == null) return false;
            toggleAudio(controller);
            HookStatus.counted(FamilyNames.REEL_TAP_AND_VOLUME, ICON_TOGGLED);
            Logger.printDebug(() -> "Reel speaker icon: sound toggled");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_TAP_AND_VOLUME, "speaker icon", failure);
            return false;
        }
    }

    /** Whether the reel on screen plays with sound: 1 on, 0 off, -1 when it can't be told. Never throws. */
    public static int soundOn() {
        try {
            Object controller = controller();
            return controller == null ? -1 : audioOn(controller);
        } catch (Throwable failure) {
            return -1;
        }
    }

    @Nullable
    private static Object controller() {
        WeakReference<Object> held = onScreen;
        return held == null ? null : held.get();
    }

    /** Forgets the controller. For tests. */
    static void forget() {
        onScreen = null;
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
            // Instagram may unmute right after this, so the speaker icon reads the sound again then.
            ReelMuteButton.refresh();
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
