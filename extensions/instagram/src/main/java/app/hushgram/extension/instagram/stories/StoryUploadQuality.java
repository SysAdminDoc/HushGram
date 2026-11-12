/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Upload stories at full quality" patch: a video story is prepared the way Instagram
 * prepares it when its own "Upload at highest quality" setting is on.
 *
 * <p>Before a video goes up, Instagram's transcode settings ask one question: whether this upload
 * gets the high quality treatment. Its answer comes from that setting on the phone, whose row in Data
 * usage the server shows only to some accounts, and which covers posts and reels too. A yes makes the video up to 1080 pixels wide
 * at 9 Mbps instead of 720 wide at a bitrate worked out from its size, the same as a post or a reel
 * gets with the setting on. The patch hands the upload's share type to {@link #fullQuality} at the start
 * of that question, and with the switch on a story gets a yes. Every other kind of upload gets
 * Instagram's own answer, and so does everything the server still decides after it. The server
 * re-encodes every story video to 720p after upload whatever the phone sends, so the yes improves
 * the source it works from, not the size viewers get.
 *
 * <p>Two choices under the switch (#37): Upload quality applies to, which can extend the yes to every
 * share type, so posts and reels get the same 1080p and 9 Mbps, and Video bitrate, which raises the
 * bitrate Instagram settles on after its checks ({@link #bitrate}) up to 50 Mbps. Instagram's servers
 * still re-encode what arrives, so a higher bitrate improves the source they work from.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or anything
 * thrown, Instagram asks its own setting as before.
 */
public final class StoryUploadQuality {
    /** What's counted for each story upload answered yes. */
    static final String RAISED = "stories prepared at full quality";

    /** What's counted for each upload given a higher video bitrate. */
    static final String RAISED_BITRATE = "uploads given a higher video bitrate";

    /** The share type Instagram gives a story posted to your story, as its enum names it. */
    static final String STORY = "STORY";

    private StoryUploadQuality() {
    }

    /**
     * Injected at the start of Instagram's high quality upload check, with the upload's share type.
     * True when the switch is on and the upload is a story, so the check answers yes; false lets
     * Instagram's own check run. Never throws, and never waits for the settings.
     */
    public static boolean fullQuality(Object shareType) {
        try {
            HookStatus.invoked(FamilyNames.STORY_UPLOAD_QUALITY);
            if (!covered(shareType)) return false;
            HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, RAISED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "story upload quality", failure);
            return false;
        }
    }

    /**
     * Injected where Instagram's video encoder settings are final, with the upload's share type and
     * the bitrate in bits a second it worked out. Returns the Video bitrate choice when it is higher,
     * for an upload the switch covers, and Instagram's own bitrate otherwise. Never throws, and never
     * waits for the settings.
     */
    public static int bitrate(Object shareType, int instagram) {
        try {
            HookStatus.invoked(FamilyNames.STORY_UPLOAD_QUALITY);
            if (instagram <= 0 || !covered(shareType)) return instagram;
            UploadBitrate choice = Settings.UPLOAD_VIDEO_BITRATE.get();
            if (choice == null || choice == UploadBitrate.INSTAGRAM) return instagram;
            int wanted = choice.bitsPerSecond();
            if (wanted <= instagram) return instagram;
            HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, RAISED_BITRATE);
            return wanted;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "story upload bitrate", failure);
            return instagram;
        }
    }

    /** Whether the switch is on and covers an upload of this share type: a story, or any with Stories, posts and reels. */
    private static boolean covered(Object shareType) {
        if (!(shareType instanceof Enum)) return false;
        if (!Utils.settingsReady() || !Settings.UPLOAD_STORIES_AT_FULL_QUALITY.get()) return false;
        if (STORY.equals(((Enum<?>) shareType).name())) return true;
        return Settings.UPLOAD_QUALITY_APPLIES_TO.get() == UploadScope.ALL;
    }
}
