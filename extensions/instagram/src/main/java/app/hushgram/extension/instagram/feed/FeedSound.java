/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StorySound;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Start feed videos with sound" patch.
 *
 * <p>Instagram 450's feed video controller starts each video through one method that makes the
 * video's state object and gives it a sound flag, which Instagram works out from its own server
 * settings and which is false for nearly everyone. The video's speaker icon and the volume the
 * player is prepared with both follow that flag. The patch asks {@link #startWithSound(Object)}
 * with the controller each time the flag comes out false, and a yes makes it true, which is what a
 * tap on the speaker would have done.
 *
 * <p>The answer is yes for the first video each feed controller starts, once the switch is on and
 * the phone allows sound. Later videos start as Instagram starts them, so a video you mute with the
 * speaker doesn't get its sound turned back on by the next one. The phone's ringer and volume are
 * respected: with the ringer on silent or vibrate, or the media volume at zero, the answer is no
 * and the video stays muted. A no doesn't use up the controller's turn.
 */
public final class FeedSound {
    /** What {@link HookStatus} counts: a feed video started with the sound turned on. */
    static final String STARTED = "feed videos started with sound";

    /** The hook's name in a failure report. */
    static final String VIDEO = "feed video start";

    /**
     * Every controller that already had its turn, held weakly so a closed feed is not kept alive.
     * One controller class serves Home and other post lists, so going back to Home after a profile's
     * posts must still find Home's controller here.
     */
    private static final Set<Object> served = Collections.newSetFromMap(new WeakHashMap<>());

    private FeedSound() {
    }

    /**
     * Asked with the feed video controller when a video's sound flag came out false. True means make
     * it true. False while the switch is off, HushGram is paused, the settings aren't ready, the
     * phone's ringer isn't on, its media volume is zero, this controller already had its turn, or
     * anything in here throws. Never throws.
     */
    public static int startWithSound(Object controller) {
        try {
            HookStatus.invoked(FamilyNames.FEED_SOUND);
            if (controller == null || !Utils.settingsReady() || !Settings.START_FEED_VIDEOS_WITH_SOUND.get()) return 0;
            if (!StorySound.phoneAllowsSound()) return 0;
            synchronized (FeedSound.class) {
                if (!served.add(controller)) return 0;
            }
            HookStatus.counted(FamilyNames.FEED_SOUND, STARTED);
            Logger.printDebug(() -> "Feed video: starting with sound");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SOUND, VIDEO, failure);
            return 0;
        }
    }

    /** Forgets which controller had its turn. For tests. */
    static void resetForTests() {
        synchronized (FeedSound.class) {
            served.clear();
        }
    }
}
