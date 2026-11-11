/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

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
 * <p>Three more choices under it (#37): Video resolution ({@link #resolution}) and Video frame rate
 * ({@link #frameRate}), covered the same way, and Photo quality ({@link #photoQuality}), the JPEG
 * quality of a photo being prepared, which has no share type where it's chosen and so covers every
 * photo while the switch is on.
 *
 * <p>Photo size ({@link #photoTier}) works the same way on a photo's size. Instagram's photo size step
 * sends a story photo at 1440x2560 unless the server turns on one of its larger sizes, 2048 to 8192
 * on the longest side, which keep a photo that big at its own size up to that one. The choice turns on
 * Instagram's 3072, 4096 and 8192 sizes up to the one chosen. The server still serves story photos at
 * 1080x1920, so a bigger photo improves the source it scales from.
 *
 * <p>2160p and 120 fps go past what Instagram's own steps know. Its width step tops out at 1440, so
 * {@link #resolution} asks it for 1440 and {@link #renderWidth}, right after the step's width cap,
 * raises the width to the choice, no wider than the clip. Either is asked only where the phone's first
 * H.264 encoder takes the size or rate, and a width it can't take steps down to 1440, then 1080.
 *
 * <p>The width step only sizes Instagram's check before upload, though. The video that goes up is
 * encoded from the server's upload settings, 1080 on the short side for a story at the 30 fps default
 * (traced on 454, 2026-11-11). {@link #uploadSize} and {@link #frameRate} answer there too, right
 * before that encode is built, so the choices reach what's sent.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or anything
 * thrown, Instagram asks its own setting as before.
 */
public final class StoryUploadQuality {
    /** What's counted for each story upload answered yes. */
    static final String RAISED = "stories prepared at full quality";

    /** What's counted for each upload given a higher video bitrate. */
    static final String RAISED_BITRATE = "uploads given a higher video bitrate";

    /** What's counted for each upload given a wider video. */
    static final String RAISED_RESOLUTION = "uploads given a higher video resolution";

    /** What's counted for each upload made wider than Instagram's width step goes. */
    static final String RAISED_PAST_STEP = "uploads made wider than Instagram's 1440";

    /** What's counted for each upload whose chosen width the phone's encoder couldn't take. */
    static final String STEPPED_DOWN = "uploads stepped down to a width the encoder takes";

    /** What's counted for each upload given a higher frame rate. */
    static final String RAISED_FRAME_RATE = "uploads given a higher frame rate";

    /** What's counted for each upload encoded bigger than the server's upload settings ask. */
    static final String RAISED_UPLOAD_SIZE = "uploads encoded bigger than the server asks";

    /** What's counted for each photo given a higher JPEG quality. */
    static final String RAISED_PHOTO = "photos given a higher quality";

    /** What's counted for each photo let past Instagram's own longest side. */
    static final String RAISED_PHOTO_SIZE = "photos allowed a longer side";

    /** The share type Instagram gives a story posted to your story, as its enum names it. */
    static final String STORY = "STORY";

    /** What the phone's video encoder takes: a width by a height, and with a rate above 0, that many frames a second too. */
    interface Encoder {
        boolean takes(int width, int height, int fps);
    }

    /** The width {@link #resolution} raised the width step it's in to, for {@link #renderWidth} later in the same step. */
    private static final ThreadLocal<Integer> WANTED = new ThreadLocal<>();

    /** The phone's first H.264 encoder, read once. */
    private static volatile Encoder encoder;

    /** Whether {@link #encoder} has been looked for, so a phone with none isn't searched again. */
    private static volatile boolean encoderRead;

    /** The width {@link #renderWidth} last answered, which a frame rate past 60 is checked at. */
    private static volatile int lastWidth = UploadResolution.P1080.width;

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

    /**
     * Injected first in Instagram's width step for an upload, with its share type and the width it
     * was asked for (0, 1080 or 1440). Returns the Video resolution choice when it's wider, for an
     * upload the switch covers, and Instagram's own otherwise. A choice past the 1440 the step knows
     * asks it for 1440, and {@link #renderWidth} takes it the rest of the way. Never throws, and never
     * waits for the settings.
     */
    public static int resolution(Object shareType, int instagram) {
        WANTED.remove();
        try {
            HookStatus.invoked(FamilyNames.STORY_UPLOAD_QUALITY);
            if (!covered(shareType)) return instagram;
            UploadResolution choice = Settings.UPLOAD_VIDEO_RESOLUTION.get();
            if (choice == null || choice.width <= instagram) return instagram;
            HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, RAISED_RESOLUTION);
            WANTED.set(choice.width);
            return Math.min(choice.width, UploadResolution.STEP_TOP);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "story upload resolution", failure);
            return instagram;
        }
    }

    /**
     * Injected in Instagram's width step right after its width cap, with the width the cap answered
     * and the clip's own width it worked from. For an upload {@link #resolution} raised, answers the
     * chosen width, no wider than the clip and never narrower than the cap's, when the phone's H.264
     * encoder takes it at 9:16, else the widest of 1440 and 1080 below it the encoder takes. Any other
     * upload, a phone whose encoder can't be read, or one that takes none of them, keeps the cap's
     * width. Never throws.
     */
    public static int renderWidth(int width, int source) {
        Integer wanted = WANTED.get();
        WANTED.remove();
        try {
            if (wanted == null || width <= 0) return width;
            Encoder phone = encoder();
            if (phone == null) return width;
            int top = Math.max(width, Math.min(wanted, source)) & ~1;
            int[] widths = { top, UploadResolution.STEP_TOP, UploadResolution.P1080.width };
            for (int candidate : widths) {
                if (candidate > top || !phone.takes(candidate, portraitHeight(candidate), 0)) continue;
                if (candidate > width) HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, RAISED_PAST_STEP);
                if (candidate < width) HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, STEPPED_DOWN);
                lastWidth = candidate;
                return candidate;
            }
            return width;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "story upload width", failure);
            return width;
        }
    }

    /**
     * Injected right before Instagram builds the encode from the server's upload settings, with the
     * upload's share type (null when it has none), the size the server asked for and whether that
     * size is the short side (non-zero) or the long one. For an upload the switch covers, answers the
     * Video resolution choice when it's bigger, as that side of a 9:16 frame, where the phone's H.264
     * encoder takes it, else the widest of 1440 and 1080 above the server's it does. Any other upload,
     * or a phone whose encoder can't be read, keeps the server's size. Never throws, and never waits
     * for the settings.
     */
    public static int uploadSize(Object shareType, int server, int shortSide) {
        try {
            HookStatus.invoked(FamilyNames.STORY_UPLOAD_QUALITY);
            if (server <= 0 || !covered(shareType)) return server;
            int serverWidth = shortSide != 0 ? server : server * 9 / 16;
            lastWidth = serverWidth;
            UploadResolution choice = Settings.UPLOAD_VIDEO_RESOLUTION.get();
            if (choice == null || choice.width <= serverWidth) return server;
            Encoder phone = encoder();
            if (phone == null) return server;
            int[] widths = { choice.width, UploadResolution.STEP_TOP, UploadResolution.P1080.width };
            for (int width : widths) {
                if (width > choice.width || width <= serverWidth || !phone.takes(width, portraitHeight(width), 0)) continue;
                if (width < choice.width) HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, STEPPED_DOWN);
                HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, RAISED_UPLOAD_SIZE);
                lastWidth = width;
                return shortSide != 0 ? width : portraitHeight(width);
            }
            return server;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "story upload size", failure);
            return server;
        }
    }

    /**
     * Injected where Instagram's video encoder settings are final, and right before it builds the
     * encode from the server's upload settings, with the upload's share type and the frame rate it
     * worked out (0 when it didn't). Returns the Video frame rate choice when it's
     * higher, for an upload the switch covers, and Instagram's own otherwise. A choice past 60 is
     * asked only when the phone's H.264 encoder takes it at the last width answered, and 60 is asked
     * in its place otherwise. Never throws, and never waits for the settings.
     */
    public static int frameRate(Object shareType, int instagram) {
        try {
            HookStatus.invoked(FamilyNames.STORY_UPLOAD_QUALITY);
            if (!covered(shareType)) return instagram;
            UploadFrameRate choice = Settings.UPLOAD_FRAME_RATE.get();
            if (choice == null || choice.fps <= instagram) return instagram;
            int fps = choice.fps;
            if (fps > UploadFrameRate.FPS_60.fps) {
                Encoder phone = encoder();
                int width = lastWidth;
                if (phone == null || !phone.takes(width, portraitHeight(width), fps)) fps = UploadFrameRate.FPS_60.fps;
                if (fps <= instagram) return instagram;
            }
            HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, RAISED_FRAME_RATE);
            return fps;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "story upload frame rate", failure);
            return instagram;
        }
    }

    /**
     * Injected first where Instagram sets up the JPEG for a photo it's preparing, with the quality it
     * chose. Returns the Photo quality choice when it's higher and the switch is on, and Instagram's
     * own otherwise. Never throws, and never waits for the settings.
     */
    public static int photoQuality(int instagram) {
        try {
            HookStatus.invoked(FamilyNames.STORY_UPLOAD_QUALITY);
            if (instagram <= 0 || !Utils.settingsReady() || !Settings.UPLOAD_STORIES_AT_FULL_QUALITY.get()) return instagram;
            UploadPhotoQuality choice = Settings.UPLOAD_PHOTO_QUALITY.get();
            if (choice == null || choice.percent <= instagram) return instagram;
            HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, RAISED_PHOTO);
            return choice.percent;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "photo upload quality", failure);
            return instagram;
        }
    }

    /**
     * Injected first in Instagram's photo size step, once for each of its 3072, 4096 and 8192 sizes,
     * with that size and whether Instagram turned it on (non-zero is yes). Answers yes for every size
     * up to the Photo size choice while the switch is on, so the step lets a photo that big keep its
     * own longest side up to that size, and Instagram's own answer otherwise. Never throws, and never
     * waits for the settings.
     */
    public static int photoTier(int edge, int instagram) {
        try {
            HookStatus.invoked(FamilyNames.STORY_UPLOAD_QUALITY);
            if (instagram != 0 || !Utils.settingsReady() || !Settings.UPLOAD_STORIES_AT_FULL_QUALITY.get()) return instagram;
            UploadPhotoSize choice = Settings.UPLOAD_PHOTO_SIZE.get();
            if (choice == null || choice.edge < edge) return instagram;
            if (choice.edge == edge) HookStatus.counted(FamilyNames.STORY_UPLOAD_QUALITY, RAISED_PHOTO_SIZE);
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "photo upload size", failure);
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

    /** The height a story this wide has at 9:16, kept even the way encoders want it. */
    static int portraitHeight(int width) {
        return Math.round(width * 16f / 9f) & ~1;
    }

    /** The phone's first H.264 encoder, the one Instagram's encoder setup gets when it asks for H.264 by type, or null when it has none. */
    private static Encoder encoder() {
        if (encoderRead) return encoder;
        Encoder found = null;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder() || !supportsAvc(info)) continue;
                MediaCodecInfo.VideoCapabilities video =
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
                if (video != null) {
                    found = (width, height, fps) -> fps > 0
                            ? video.areSizeAndRateSupported(width, height, fps)
                            : video.isSizeSupported(width, height);
                }
                break;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_QUALITY, "video encoder lookup", failure);
        }
        encoder = found;
        encoderRead = true;
        return found;
    }

    private static boolean supportsAvc(MediaCodecInfo info) {
        for (String type : info.getSupportedTypes()) {
            if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    /** Stands in for the phone's encoder; null reads as a phone that has none. */
    static void encoderForTests(Encoder stand) {
        encoder = stand;
        encoderRead = true;
    }

    /** Puts the encoder lookup, the last width and this thread's wanted width back. */
    static void resetForTests() {
        encoder = null;
        encoderRead = false;
        lastWidth = UploadResolution.P1080.width;
        WANTED.remove();
    }
}
