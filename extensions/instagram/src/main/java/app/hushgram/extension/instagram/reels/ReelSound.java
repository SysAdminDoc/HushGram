/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StorySound;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Start reels with sound" patch (#342).
 *
 * <p>Instagram keeps one audio state for the signed-in account, the one the Reels speaker icon and
 * the volume keys flip, and the feed and the Reels viewer both play by it. Home keeps it muted while
 * you scroll, so a reel tapped open from a feed post starts muted too.
 *
 * <p>The patch asks {@link #wanted()} as a Reels viewer is created, and on a yes asks
 * {@link #startWithSound(Object, Object)} with the viewer and where it was opened from, a
 * {@code ClipsViewerSource} whose constant names Instagram's build keeps. Only a viewer opened from
 * Home ({@link #FROM_HOME}) gets a yes, which makes the patch set the account's state to on the way
 * the speaker icon does. The Reels tab, profiles, chats and Explore keep Instagram's own choice. The
 * phone's ringer and volume still count, as they do for stories.
 *
 * <p>With Start reels muted on, every viewer gets a yes from any source, and {@link #opened(Object, int)}
 * answers 0, so the patch sets the state to off instead. Nothing is put back; the speaker icon and the
 * volume keys unmute as usual. It wins over Start reels with sound.
 *
 * <p>Instagram also changes the state by itself. As the app comes to the front for the first time
 * since it started, or after ten minutes away, it clears the state back to its defaults, which play
 * with sound; and as a reel opens with the app just opened, it turns the sound on. Both came right
 * after a reel opened from a link or a notification was set muted, so that reel played with sound
 * (#342). While Start reels muted is on, {@link #letsReset(Boolean, Boolean)} keeps an off state
 * through the clear and {@link #letsSoundOn(int)} holds those two turn-ons back. The speaker icon and
 * the volume keys take other calls and still unmute.
 *
 * <p>When the patch turned the sound on from off, the viewer's pause puts it back to off, so Home is
 * muted again on the way back, and its resume turns it on again for the reel. A reel you mute
 * yourself stays muted.
 */
public final class ReelSound {
    /** What {@link HookStatus} counts: a reel opened from Home with the sound turned on. */
    static final String STARTED = "reels started with sound";

    /** What {@link HookStatus} counts: a reel opened with the sound turned off by Start reels muted. */
    static final String STARTED_MUTED = "reels started muted";

    /** What {@link HookStatus} counts: the sound put back to off as a reel opened from Home was left. */
    static final String RESTORED = "reel sound put back to off";

    /** What {@link HookStatus} counts: Instagram's clear of a muted state as the app came back, kept muted. */
    static final String KEPT_MUTED = "reel mute kept as the app came back";

    /** What {@link HookStatus} counts: Instagram turning the sound on by itself, held back. */
    static final String HELD_OFF = "reel sound turn-on held back";

    /** The hook's name in a failure report. */
    static final String VIEWER = "reels viewer";

    /** The name of the hooks on Instagram's own changes to the state in a failure report. */
    static final String APP_OPEN = "sound as the app opens";

    /** The start of every source name for a viewer opened from a post on Home, as Instagram 452 names them. */
    static final String FROM_HOME = "FEED_TIMELINE";

    /**
     * Every viewer that turned the account's sound on from off, held weakly, mapped to whether its
     * pause has put the sound back to off. A viewer is a Fragment, whose equals is identity.
     */
    private static final Map<Object, Boolean> turnedOn = new WeakHashMap<>();

    /** Every viewer told to start muted, until {@link #opened(Object, int)} answers for it. */
    private static final Map<Object, Boolean> startedMuted = new WeakHashMap<>();

    private ReelSound() {
    }

    /**
     * Asked first as a Reels viewer is created, before anything is read from it. True while the
     * switch is on, HushGram isn't paused and the settings are ready. Never throws.
     */
    public static int wanted() {
        try {
            HookStatus.invoked(FamilyNames.REEL_SOUND);
            return Utils.settingsReady() && (Settings.START_REELS_WITH_SOUND.get() || Settings.START_REELS_MUTED.get()) ? 1 : 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /**
     * Asked after a yes from {@link #wanted()}, with the viewer and its source. True means set
     * Instagram's audio state to on: the viewer was opened from Home, the phone's ringer is on and
     * its media volume is up. False for every other source, a source that isn't an enum, and
     * anything that throws. Never throws.
     */
    public static int startWithSound(Object viewer, Object source) {
        try {
            if (viewer == null) return 0;
            if (Utils.settingsReady() && Settings.START_REELS_MUTED.get()) {
                synchronized (ReelSound.class) {
                    startedMuted.put(viewer, Boolean.TRUE);
                }
                HookStatus.counted(FamilyNames.REEL_SOUND, STARTED_MUTED);
                return 1;
            }
            if (!(source instanceof Enum)) return 0;
            String name = ((Enum<?>) source).name();
            Logger.printDebug(() -> "Reels viewer opened from " + name);
            if (!name.startsWith(FROM_HOME)) return 0;
            if (!Utils.settingsReady() || !Settings.START_REELS_WITH_SOUND.get() || !StorySound.phoneAllowsSound()) return 0;
            HookStatus.counted(FamilyNames.REEL_SOUND, STARTED);
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /**
     * Told, after a yes from {@link #startWithSound(Object, Object)} and before the sound is set
     * on, whether the account's sound was on already. Only an off is worth putting back. Answers the
     * state to set: 1 for on, 0 for a viewer that {@link #startWithSound(Object, Object)} started
     * muted, which is never put back. Never throws.
     */
    public static int opened(Object viewer, int wasOn) {
        try {
            if (viewer == null) return 1;
            synchronized (ReelSound.class) {
                if (startedMuted.remove(viewer) != null) {
                    turnedOn.remove(viewer);
                    return 0;
                }
                if (wasOn == 0) turnedOn.put(viewer, Boolean.FALSE);
                else turnedOn.remove(viewer);
            }
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SOUND, VIEWER, failure);
            return 1;
        }
    }

    /**
     * Asked as a Reels viewer pauses. True when this viewer turned the sound on from off and it
     * hasn't been put back yet, so the patch reads the sound and asks {@link #paused(Object, int)}.
     * Never throws.
     */
    public static int pausing(Object viewer) {
        try {
            if (viewer == null) return 0;
            synchronized (ReelSound.class) {
                return Boolean.FALSE.equals(turnedOn.get(viewer)) ? 1 : 0;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /**
     * Told whether the sound is still on as the viewer pauses. True means set it to off, which is
     * what it was before the viewer opened. A sound already off was muted on the reel, so it's left
     * as it is and the viewer is forgotten. Never throws.
     */
    public static int paused(Object viewer, int isOn) {
        try {
            if (viewer == null) return 0;
            synchronized (ReelSound.class) {
                if (!turnedOn.containsKey(viewer)) return 0;
                if (isOn == 0) {
                    turnedOn.remove(viewer);
                    return 0;
                }
                turnedOn.put(viewer, Boolean.TRUE);
            }
            HookStatus.counted(FamilyNames.REEL_SOUND, RESTORED);
            Logger.printDebug(() -> "Reels viewer left: sound back to off");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /**
     * Asked as a Reels viewer resumes. True when its pause put the sound back to off and the reel
     * should play with sound again: the switch still on, HushGram not paused, the phone's ringer on
     * and its volume up. Otherwise the viewer is forgotten and the sound stays off. Never throws.
     */
    public static int resuming(Object viewer) {
        try {
            if (viewer == null) return 0;
            synchronized (ReelSound.class) {
                if (!Boolean.TRUE.equals(turnedOn.get(viewer))) return 0;
                turnedOn.remove(viewer);
            }
            if (!Utils.settingsReady() || !Settings.START_REELS_WITH_SOUND.get() || !StorySound.phoneAllowsSound()) return 0;
            synchronized (ReelSound.class) {
                turnedOn.put(viewer, Boolean.FALSE);
            }
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /**
     * Asked as Instagram, coming to the front, is about to change the account's sound from
     * [current] to [next], which clears it to Instagram's defaults. 0 skips the change: the sound is
     * off, the change would turn it anything but off, and Start reels muted is on. 1 lets it happen,
     * as it does for anything that throws. Never throws.
     */
    public static int letsReset(Boolean current, Boolean next) {
        try {
            if (!Boolean.FALSE.equals(current) || Boolean.FALSE.equals(next) || !keepsMuted()) return 1;
            HookStatus.counted(FamilyNames.REEL_SOUND, KEPT_MUTED);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SOUND, APP_OPEN, failure);
            return 1;
        }
    }

    /**
     * Asked as Instagram is about to set the account's sound by itself, with the value, non-zero
     * for on. 0 skips an on while Start reels muted is on. 1 lets it happen, as it does for an off
     * and for anything that throws. Never throws.
     */
    public static int letsSoundOn(int on) {
        try {
            if (on == 0 || !keepsMuted()) return 1;
            HookStatus.counted(FamilyNames.REEL_SOUND, HELD_OFF);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SOUND, APP_OPEN, failure);
            return 1;
        }
    }

    /** Whether Start reels muted is on. Off while paused or before the settings are read. */
    private static boolean keepsMuted() {
        return Utils.settingsReady() && Settings.START_REELS_MUTED.get();
    }

    /** Forgets every viewer. For tests. */
    static void resetForTests() {
        synchronized (ReelSound.class) {
            turnedOn.clear();
            startedMuted.clear();
        }
    }
}
