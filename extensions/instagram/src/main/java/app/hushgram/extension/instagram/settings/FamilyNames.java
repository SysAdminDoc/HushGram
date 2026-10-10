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
    public static final String STORY_MENTIONS = "See who a story mentions";
    public static final String POST_TIME = "Show a post's exact time";
    public static final String STORY_LOOP = "Loop a story";
    public static final String STORY_SEEN = "View stories anonymously";
    public static final String DM_MEDIA_SEEN = "View DM photos and videos anonymously";
    public static final String SPOOF_LOCATION = "Spoof location";
    public static final String THREAD_SEEN = "Read messages without the seen receipt";
    public static final String TYPING = "Hide that you're typing";
    public static final String MESSAGES_LOCK = "Lock your messages";
    public static final String STORIES_TRAY = "Hide suggested stories";
    public static final String STORY_RING = "Story ring size";
    public static final String FEED_REELS = "Hide Reels in the feed";
    public static final String FEED_SUGGESTIONS = "Hide suggested posts";
    public static final String HOME_FEED = "Hide the home feed";
    public static final String FOLLOWING_FEED = "Start Home on Following";
    public static final String SWIPE_TO_CREATE = "Stop swipe to create";
    public static final String TAB_SWIPE = "Stop swiping between tabs";
    public static final String FULL_RESOLUTION = "Full resolution photos";
    public static final String META_AI = "Hide Meta AI";
    public static final String EXPLORE_GRID = "Hide the Explore grid";
    public static final String RECENT_SEARCHES = "Don't save recent searches";
    public static final String NOTES_ROW = "Hide the notes row";
    public static final String INBOX_SUGGESTIONS = "Hide suggested accounts in DMs";
    public static final String INSTANTS = "Hide Instants";
    public static final String SHARE_SHEET = "Hide group buttons on the share sheet";
    public static final String REPOST_BUTTON = "Hide the Repost button";
    public static final String HIDE_SHARE_BUTTON = "Hide the Share button";
    public static final String BOTTOM_SPACE = "Remove the empty space at the bottom";
    public static final String GLASS_TAB_BAR = "Glass tab bar";
    public static final String SAVED_ON_PROFILE = "Saved on your profile";
    public static final String STOP_HEARTBEAT = "Stop background wake-ups";
    public static final String EMOJI_STYLE = "Emoji style";
    public static final String NOTIFICATION_GROUPS = "Group Instagram's notifications";
    public static final String HDR_BOOST = "Turn off HDR brightness boosts";
    public static final String MEDIA_CACHE = "Clear the media cache";
    public static final String FRIENDSHIP_STATUS = "Show if a profile follows you";
    public static final String PROFILE_SUGGESTIONS = "Hide suggested people on profiles";
    public static final String PROFILE_HIGHLIGHTS = "Hide highlights";
    public static final String THREADS_BUTTON = "Hide the Threads button";
    public static final String COMMENT_COPY = "Copy comment";
    public static final String COMMENT_PHOTO = "Save comment photo";
    public static final String PROFILE_PICTURE = "Save profile picture";
    public static final String VOICE_MESSAGE = "Download voice messages";
    public static final String HIDE_COMMENTS = "Hide comments";
    public static final String REEL_DECLUTTER = "Clean up Reels";
    public static final String REEL_DOWNLOAD = "Download any reel";
    public static final String DOUBLE_TAP_LIKE = "Turn off double tap to like";
    public static final String LIKE_ANIMATION = "Change the like animation";
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
    public static final String DATA_SAVER = "Data saver";
    public static final String TRANSLATED_START = "Start on x86 devices";
    public static final String DEVELOPER_OPTIONS = "Open developer options";
    public static final String PURE_BLACK = "Pure black dark mode";
    public static final String VERSION_CODE = "Change version code";

    private FamilyNames() {
    }
    public static final String SCREENSHOT_REPORTS = "Don't report screenshots";
    public static final String SCREENSHOT_BLOCK = "Allow screenshots";
    public static final String KEEP_IN_CHAT = "Keep in chat";
    public static final String ASK_BEFORE_CALL = "Ask before a call";
    public static final String ASK_BEFORE_LIKE = "Ask before a like";
    public static final String ASK_BEFORE_REFRESH = "Ask before a refresh";
    public static final String LIVE_SEEN = "View live anonymously";
}
