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
 * Helper for the "Start feed videos with sound" patch, and its Start feed videos muted switch.
 *
 * <p>Instagram 450's feed video controller starts each video through one method that makes the
 * video's state object and gives it a sound flag, which Instagram works out from its own server
 * settings and which is false for nearly everyone. The video's speaker icon and the volume the
 * player is prepared with both follow that flag. The patch asks {@link #startWithSound(Object)}
 * with the controller each time the flag comes out false, and a yes makes it true, which is what a
 * tap on the speaker would have done.
 *
 * <p>The answer is yes for the first video each feed controller starts, once the switch is on and
 * the phone allows sound. On that yes the patch also turns the account's audio state on, the one a
 * tap on the speaker sets, and the videos after the first start by that state (#504). A video you
 * mute with the speaker turns it off again, so the next one doesn't get its sound turned back on.
 * The phone's ringer and volume are respected: with the ringer on silent or vibrate, or the media
 * volume at zero, the answer is no and the video stays muted. With Even on silent on, only the media
 * volume counts (#762). A no doesn't use up the controller's turn.
 *
 * <p>The feed and Reels share the account's audio state, so a reel you unmute made the feed play with
 * sound too (#718). With Start feed videos muted on, the patch asks {@link #keepsMuted()} each time
 * the flag comes out true, and a yes makes it false: the video starts muted while the account's state,
 * and with it Reels, keeps its sound. It wins over Start feed videos with sound.
 *
 * <p>A photo post with a song plays it only while the account's audio state is on, so a song that
 * came before any video stayed silent (#762). The patch asks {@link #startSongWithSound(Object)} when
 * such a post comes into view with the state still unset, never over a state you turned off.
 */
public final class FeedSound {
    /** What {@link HookStatus} counts: a feed video started with the sound turned on. */
    static final String STARTED = "feed videos started with sound";

    /** What {@link HookStatus} counts: a feed video started muted by Start feed videos muted. */
    static final String KEPT_MUTED = "feed videos started muted";

    /** What {@link HookStatus} counts: a photo post's song started with the sound on (#762). */
    static final String SONG_STARTED = "feed songs started with sound";

    /** The hook's name in a failure report. */
    static final String VIDEO = "feed video start";

    /** The song hook's name in a failure report. */
    static final String SONG = "feed song start";

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
            if (Settings.START_FEED_VIDEOS_MUTED.get()) return 0;
            boolean allowed = Settings.START_FEED_VIDEOS_ON_SILENT.get() ? StorySound.mediaVolumeUp() : StorySound.phoneAllowsSound();
            if (!allowed) return 0;
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

    /**
     * Asked when a feed video's sound flag came out true, from the account's audio state. True means make
     * it false, so the video starts muted. True only while Start feed videos muted is on, HushGram isn't
     * paused and the settings are ready. Never throws.
     */
    public static int keepsMuted() {
        try {
            HookStatus.invoked(FamilyNames.FEED_SOUND);
            if (!Utils.settingsReady() || !Settings.START_FEED_VIDEOS_MUTED.get()) return 0;
            HookStatus.counted(FamilyNames.FEED_SOUND, KEPT_MUTED);
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SOUND, VIDEO, failure);
            return 0;
        }
    }

    /**
     * Asked with the feed's music controller when a photo post with a song comes into view while the
     * account's audio state is still unset: nobody turned it on or off yet, or Instagram reset it after
     * a while in the background. Instagram plays a photo post's song only once that state is on, so
     * with nothing chosen the song stays silent until a video turned it on first (#762). True means
     * play it, and Instagram's own play turns the state on, so the videos after it start with sound
     * too. A state you set off yourself never reaches here. False while the switch is off, Start feed
     * videos muted is on, HushGram is paused, the settings aren't ready, the phone's ringer or volume
     * holds sound back the way {@link #startWithSound} reads them, or anything throws. Never throws.
     */
    public static int startSongWithSound(Object controller) {
        try {
            HookStatus.invoked(FamilyNames.FEED_SOUND);
            if (controller == null || !Utils.settingsReady() || !Settings.START_FEED_VIDEOS_WITH_SOUND.get()) return 0;
            if (Settings.START_FEED_VIDEOS_MUTED.get()) return 0;
            boolean allowed = Settings.START_FEED_VIDEOS_ON_SILENT.get() ? StorySound.mediaVolumeUp() : StorySound.phoneAllowsSound();
            if (!allowed) return 0;
            HookStatus.counted(FamilyNames.FEED_SOUND, SONG_STARTED);
            Logger.printDebug(() -> "Feed song: starting with sound");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SOUND, SONG, failure);
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
