/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

/**
 * The name each patch goes by in Morphe Manager, which is also the family its hooks report
 * under in Hook status.
 *
 * <p>Compile-time constants, so a hook that names its family inlines the text and loads no
 * class. That matters for the hooks that can run before HushGram has a context, such as the
 * signature read Instagram makes while its content providers start: touching {@link PatchFamily}
 * there would load the settings with no context to read them from.
 */
public final class FamilyNames {
    public static final String HIDE_ADS = "Hide ads";
    public static final String SANITIZE_SHARING_LINKS = "Sanitize sharing links";
    public static final String EXTERNAL_BROWSER = "Open links in external browser";
    public static final String DISABLE_ANALYTICS = "Disable analytics";
    public static final String BUILD_EXPIRED_POPUP = "Remove build expired popup";
    public static final String RESTORE_TRUST = "Restore trust on re-signed builds";
    public static final String REMOVE_AD_ID = "Remove the advertising ID";
    public static final String REEL_WATCH_HISTORY = "Don't send reel watch history";
    public static final String STORY_AUTO_ADVANCE = "Stop Story auto-advance";
    public static final String STORY_TIME = "Show a story's exact time";
    public static final String STORY_LOOP = "Loop a story";
    public static final String STORY_SEEN = "View stories anonymously";
    public static final String DM_MEDIA_SEEN = "View DM photos and videos anonymously";
    public static final String STORIES_TRAY = "Hide suggested stories";
    public static final String STORY_RING = "Story ring size";
    public static final String FEED_REELS = "Hide Reels in the feed";
    public static final String FEED_SUGGESTIONS = "Hide suggested posts";
    public static final String FOLLOWING_FEED = "Start Home on Following";
    public static final String SWIPE_TO_CREATE = "Stop swipe to create";
    public static final String META_AI = "Hide Meta AI";
    public static final String EXPLORE_GRID = "Hide the Explore grid";
    public static final String NOTES_ROW = "Hide the notes row";
    public static final String SHARE_SHEET = "Hide group buttons on the share sheet";
    public static final String REPOST_BUTTON = "Hide the Repost button";
    public static final String BOTTOM_SPACE = "Remove the empty space at the bottom";
    public static final String GLASS_TAB_BAR = "Glass tab bar";
    public static final String VIEW_CHATS = "View chats anonymously";
    public static final String TYPING_STATUS = "Disable typing status";
    public static final String SCREENSHOT_DETECTION = "Disable screenshot detection";
    public static final String VIEW_LIVE = "View live anonymously";
    public static final String IMAGE_VIEWING = "Improve image viewing";
    public static final String EPHEMERAL_MEDIA = "Make ephemeral media permanent";
    public static final String FRIENDSHIP_STATUS = "Show if a profile follows you";
    public static final String PROFILE_SUGGESTIONS = "Hide suggested people on profiles";
    public static final String PROFILE_HIGHLIGHTS = "Hide highlights";
    public static final String COMMENT_COPY = "Copy comment";
    public static final String COMMENT_PHOTO = "Save comment photo";
    public static final String REEL_DECLUTTER = "Clean up Reels";
    public static final String REEL_DOWNLOAD = "Download any reel";
    public static final String DOUBLE_TAP_LIKE = "Turn off double tap to like";
    public static final String REELS_TAB = "Hide the Reels tab";
    public static final String REELS_SUGGESTIONS = "Hide suggested accounts in Reels";
    public static final String KEEP_REEL_SPEED = "Keep the reel speed";
    public static final String REEL_SEEK_BAR = "Keep a seek bar on Reels";
    public static final String REEL_AUTO_SCROLL = "Keep Reels auto scroll on";
    public static final String REEL_SCROLLING = "Stop Reels scrolling";
    public static final String STORY_DOWNLOAD = "Download any story";
    public static final String VIDEO_DOWNLOAD = "Download any video";
    public static final String TAP_TO_PLAY = "Tap to play";
    public static final String RESUME_LONG_VIDEOS = "Resume long videos";
    public static final String PLAYBACK_QUALITY = "Default playback quality";
    public static final String TRANSLATED_START = "Start on x86 devices";
    public static final String DEVELOPER_OPTIONS = "Open developer options";
    public static final String PURE_BLACK = "Pure black dark mode";

    private FamilyNames() {
    }
}
