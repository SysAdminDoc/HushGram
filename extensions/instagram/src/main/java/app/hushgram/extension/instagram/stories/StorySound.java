/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.media.AudioManager;

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
 */
public final class StorySound {
    /** What {@link HookStatus} counts: a story viewer opened with the sound turned on. */
    static final String STARTED = "stories started with sound";

    /** The hook's name in a failure report. */
    static final String VIEWER = "story viewer";

    private StorySound() {
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
    static boolean phoneAllowsSound() {
        Context context = Utils.getContext();
        if (context == null) return false;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        return audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0;
    }
}
