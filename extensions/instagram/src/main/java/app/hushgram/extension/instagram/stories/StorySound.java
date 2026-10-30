/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.media.AudioManager;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Start stories with sound" patch.
 *
 * <p>Instagram 450 keeps one audio state for the signed-in account, the one its speaker icon and
 * the volume keys flip, and a story reads it as the viewer opens: sound on when the state says so,
 * and with no answer saved, sound on only while the phone's ringer is on and its volume is up. A
 * muted state left behind by a reel or a feed video therefore silences the next story.
 *
 * <p>The patch asks {@link #startWithSound(Object)} once, as the story viewer is created. A yes
 * makes the patch's code set Instagram's own state to on, the way the speaker icon does, so the
 * icon and later taps agree with what you hear. The phone's ringer and volume are still respected
 * here: with the ringer on silent or vibrate, or the media volume at zero, the answer is no and
 * the viewer keeps Instagram's own choice. A story you mute after it opens stays muted until you
 * open the viewer again.
 *
 * <p>That state is the account's, so the feed and Reels read it too. When the patch turned the
 * sound on from off, the viewer's pause puts it back to off before the feed comes back (#342), and
 * its resume turns it on again for the story. A story you muted yourself is left muted.
 */
public final class StorySound {
    /** What {@link HookStatus} counts: a story viewer opened with the sound turned on. */
    static final String STARTED = "stories started with sound";

    /** What {@link HookStatus} counts: the sound put back to off as a story viewer was left. */
    static final String RESTORED = "story sound put back to off";

    /** The hook's name in a failure report. */
    static final String VIEWER = "story viewer";

    /**
     * Every viewer that turned the account's sound on from off, held weakly, mapped to whether its
     * pause has put the sound back to off. A viewer is a Fragment, whose equals is identity.
     */
    private static final Map<Object, Boolean> turnedOn = new WeakHashMap<>();

    private StorySound() {
    }

    /**
     * Told, after a yes from {@link #startWithSound(Object)} and before the sound is set on,
     * whether the account's sound was on already. Only an off is worth putting back. Never throws.
     */
    public static void opened(Object viewer, int wasOn) {
        try {
            if (viewer == null) return;
            synchronized (StorySound.class) {
                if (wasOn == 0) turnedOn.put(viewer, Boolean.FALSE);
                else turnedOn.remove(viewer);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SOUND, VIEWER, failure);
        }
    }

    /**
     * Asked as a story viewer pauses. True when this viewer turned the sound on from off and it
     * hasn't been put back yet, so the patch reads the sound and asks {@link #paused(Object, int)}.
     * Never throws.
     */
    public static int pausing(Object viewer) {
        try {
            if (viewer == null) return 0;
            synchronized (StorySound.class) {
                return Boolean.FALSE.equals(turnedOn.get(viewer)) ? 1 : 0;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /**
     * Told whether the sound is still on as the viewer pauses. True means set it to off, which is
     * what it was before the viewer opened. A sound already off was muted in the story, so it's
     * left as it is and the viewer is forgotten. Never throws.
     */
    public static int paused(Object viewer, int isOn) {
        try {
            if (viewer == null) return 0;
            synchronized (StorySound.class) {
                if (!turnedOn.containsKey(viewer)) return 0;
                if (isOn == 0) {
                    turnedOn.remove(viewer);
                    return 0;
                }
                turnedOn.put(viewer, Boolean.TRUE);
            }
            HookStatus.counted(FamilyNames.STORY_SOUND, RESTORED);
            Logger.printDebug(() -> "Story viewer left: sound back to off");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /**
     * Asked as a story viewer resumes. True when its pause put the sound back to off and the story
     * should play with sound again: the switch still on, HushGram not paused, the phone's ringer on
     * and its volume up. Otherwise the viewer is forgotten and the sound stays off. Never throws.
     */
    public static int resuming(Object viewer) {
        try {
            if (viewer == null) return 0;
            synchronized (StorySound.class) {
                if (!Boolean.TRUE.equals(turnedOn.get(viewer))) return 0;
                turnedOn.remove(viewer);
            }
            if (!Utils.settingsReady() || !Settings.START_STORIES_WITH_SOUND.get() || !phoneAllowsSound()) return 0;
            synchronized (StorySound.class) {
                turnedOn.put(viewer, Boolean.FALSE);
            }
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /** Forgets every viewer. For tests. */
    static void resetForTests() {
        synchronized (StorySound.class) {
            turnedOn.clear();
        }
    }

    /**
     * Asked once as a story viewer is created, with the viewer. True means set Instagram's audio
     * state to on. False while the switch is off, HushGram is paused, the settings aren't ready,
     * the phone's ringer isn't on, its media volume is zero, or anything in here throws. Never throws.
     */
    public static int startWithSound(Object viewer) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SOUND);
            if (viewer == null || !Utils.settingsReady() || !Settings.START_STORIES_WITH_SOUND.get()) return 0;
            if (!phoneAllowsSound()) return 0;
            HookStatus.counted(FamilyNames.STORY_SOUND, STARTED);
            Logger.printDebug(() -> "Story viewer: starting with sound");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SOUND, VIEWER, failure);
            return 0;
        }
    }

    /** True when the ringer is on and the media volume is up, which is when Instagram plays a story with no choice saved. */
    public static boolean phoneAllowsSound() {
        Context context = Utils.getContext();
        if (context == null) return false;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        return audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0;
    }
}
