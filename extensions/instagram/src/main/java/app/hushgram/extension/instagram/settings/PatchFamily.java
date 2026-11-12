/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.misc.ScreenReport;
import app.hushgram.extension.instagram.misc.VersionCode;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * Every patch in this source, and what Pause does to it.
 *
 * <p>Pause and safe mode work through the switches: while either is on, every feature switch
 * answers off and the hook behind it takes Instagram's own path. The hook's code is still there,
 * only its answer changes, and Debug logging keeps its saved value. A patch with no switch works
 * entirely when you patch, so it stays in until you patch again, and it says what stays. A patch that is only a
 * choice, such as what a tap on a reel does, has no switch and answers Instagram's own while paused.
 *
 * <p>The settings screen and the diagnostic report read this list, so they can't disagree about
 * it. A family is found in this build by the name of its {@link SettingsStatus} method, the same
 * name the patch uses to switch that method on.
 */
public enum PatchFamily {
    HIDE_ADS(FamilyNames.HIDE_ADS, "hideAds", null, Settings.HIDE_ADS),
    HIDE_PLUS_UPSELL(FamilyNames.HIDE_PLUS_UPSELL, "hidePlusUpsell", null, Settings.HIDE_PLUS_UPSELL),
    SANITIZE_SHARING_LINKS(FamilyNames.SANITIZE_SHARING_LINKS, "sanitizeSharingLinks", null,
            Settings.SANITIZE_SHARING_LINKS),
    EXTERNAL_BROWSER(FamilyNames.EXTERNAL_BROWSER, "externalBrowser", null, Settings.OPEN_LINKS_EXTERNALLY),
    BUILD_EXPIRED_POPUP(FamilyNames.BUILD_EXPIRED_POPUP, "buildExpiredPopup", null,
            Settings.REMOVE_BUILD_EXPIRED_POPUP),
    RESTORE_TRUST(FamilyNames.RESTORE_TRUST, "restoreTrust", "the re-signed build fix"),
    REMOVE_AD_ID(FamilyNames.REMOVE_AD_ID, "removeAdId", "the removed advertising ID permissions"),
    REEL_WATCH_HISTORY(FamilyNames.REEL_WATCH_HISTORY, "reelWatchHistory", null,
            Settings.DONT_SEND_REEL_WATCH_HISTORY),
    STORY_AUTO_ADVANCE(FamilyNames.STORY_AUTO_ADVANCE, "storyAutoAdvance", null,
            Settings.BLOCK_STORY_AUTO_ADVANCE),
    STORY_TIME(FamilyNames.STORY_TIME, "storyTime", null, Settings.SHOW_STORY_TIME),
    STORY_MENTIONS(FamilyNames.STORY_MENTIONS, "storyMentions", null,
            Settings.SHOW_STORY_MENTIONS, Settings.RED_STORY_MENTIONS),
    STORY_FOLLOW(FamilyNames.STORY_FOLLOW, "storyFollow", null, Settings.HIDE_STORY_FOLLOW),
    POST_TIME(FamilyNames.POST_TIME, "postTime", null, Settings.SHOW_POST_TIME),
    HIDDEN_LIKE_COUNTS(FamilyNames.HIDDEN_LIKE_COUNTS, "hiddenLikeCounts", null, Settings.SHOW_HIDDEN_LIKE_COUNTS),
    BACK_LEAVES_HOME(FamilyNames.BACK_LEAVES_HOME, "backLeavesHome", null, Settings.BACK_LEAVES_HOME),
    HOME_PLACE(FamilyNames.HOME_PLACE, "homePlace", null, Settings.KEEP_HOME_PLACE),
    STORY_LOOP(FamilyNames.STORY_LOOP, "storyLoop", null, Settings.LOOP_STORIES),
    SMOOTH_STORY_OPEN(FamilyNames.SMOOTH_STORY_OPEN, "smoothStoryOpen", null, Settings.SMOOTH_STORY_OPEN),
    STORY_ZOOM_PLAY(FamilyNames.STORY_ZOOM_PLAY, "storyZoomPlay", null, Settings.KEEP_STORIES_PLAYING_ON_ZOOM),
    STORY_PRIORITY(FamilyNames.STORY_PRIORITY, "storyPriority", null, Settings.STORY_PRIORITY),
    STORY_SOUND(FamilyNames.STORY_SOUND, "storySound", null, Settings.START_STORIES_WITH_SOUND),
    STORY_SWIPE_REPLY(FamilyNames.STORY_SWIPE_REPLY, "storySwipeReply", null, Settings.STOP_STORY_SWIPE_REPLY),
    STORY_REPLY_BOX(FamilyNames.STORY_REPLY_BOX, "storyReplyBox", null, Settings.HIDE_STORY_REPLY_BOX,
            Settings.HIDE_STORY_LIKE_BUTTON, Settings.HIDE_STORY_COMMENT_BUTTON, Settings.HIDE_STORY_SHARE_BUTTON),
    STORY_BUTTONS_ROOM(FamilyNames.STORY_BUTTONS_ROOM, "storyButtonsRoom", null, Settings.KEEP_STORY_BUTTONS_BELOW),
    STORY_FONTS(FamilyNames.STORY_FONTS, "storyFonts", null, Settings.STORY_FONTS, Settings.STORY_EMOJI),
    STORY_UPLOAD_QUALITY(FamilyNames.STORY_UPLOAD_QUALITY, "storyUploadQuality", null, Settings.UPLOAD_STORIES_AT_FULL_QUALITY),
    FEED_SOUND(FamilyNames.FEED_SOUND, "feedSound", null, Settings.START_FEED_VIDEOS_WITH_SOUND),
    FEED_TAP_PAUSE(FamilyNames.FEED_TAP_PAUSE, "feedTapPause", null, Settings.TAP_FEED_VIDEO_TO_PAUSE),
    FEED_HOLD_FAST(FamilyNames.FEED_HOLD_FAST, "feedHoldFast", null, Settings.HOLD_FEED_VIDEO_FAST),
    FEED_SEEK_BAR(FamilyNames.FEED_SEEK_BAR, "feedSeekBar", null, Settings.FEED_SEEK_BAR, Settings.FEED_SEEK_BUTTON,
            Settings.FEED_DOUBLE_TAP_SKIP),
    LONG_PRESS_ZOOM(FamilyNames.LONG_PRESS_ZOOM, "longPressZoom", null, Settings.LONG_PRESS_TO_ZOOM,
            Settings.LONG_PRESS_ZOOM_STAYS_OPEN, Settings.LONG_PRESS_FULL_SCREEN),
    ROUNDED_POST_CORNERS(FamilyNames.ROUNDED_POST_CORNERS, "roundedPostCorners", null, Settings.ROUNDED_POST_CORNERS,
            Settings.ROUNDED_REEL_CORNERS, Settings.ROUNDED_STORY_CORNERS, Settings.PEEK_CAROUSEL_PAGES),
    CAROUSEL_ARROWS(FamilyNames.CAROUSEL_ARROWS, "carouselArrows", null, Settings.CAROUSEL_ARROWS,
            Settings.CAROUSEL_DOTS_PILL, Settings.CAROUSEL_HIDE_NUMBER),
    AMBIENT_LIGHT(FamilyNames.AMBIENT_LIGHT, "ambientLight", null, Settings.AMBIENT_LIGHT, Settings.AMBIENT_LIGHT_STORIES),
    STORY_SEEN(FamilyNames.STORY_SEEN, "storySeen", null, Settings.VIEW_STORIES_ANONYMOUSLY,
            Settings.MARK_STORIES_SEEN, Settings.GRAY_OUT_WATCHED_STORIES),
    LIVE_SEEN(FamilyNames.LIVE_SEEN, "liveSeen", null, Settings.VIEW_LIVE_ANONYMOUSLY, Settings.MARK_LIVE_SEEN),
    DM_MEDIA_SEEN(FamilyNames.DM_MEDIA_SEEN, "visualSeen", null, Settings.VIEW_DM_MEDIA_ANONYMOUSLY),
    SPOOF_LOCATION(FamilyNames.SPOOF_LOCATION, "spoofLocation", null, Settings.SPOOF_LOCATION),
    APP_PROXY(FamilyNames.APP_PROXY, "appProxy", null, Settings.APP_PROXY),
    SETUP_POPUPS(FamilyNames.SETUP_POPUPS, "setupPopups", null, Settings.SKIP_SETUP_POPUPS),
    AD_DATA_REMINDER(FamilyNames.AD_DATA_REMINDER, "adDataReminder", null, Settings.SKIP_AD_DATA_REMINDER),
    PHOTO_LOCATIONS(FamilyNames.PHOTO_LOCATIONS, "photoLocations", null, Settings.KEEP_PHOTO_LOCATIONS),
    THREAD_SEEN(FamilyNames.THREAD_SEEN, "threadSeen", null, Settings.READ_WITHOUT_SEEN_RECEIPT,
            Settings.KEEP_CHATS_UNREAD, Settings.MARK_SEEN_ON_REPLY, Settings.MARK_READ_IN_CHAT, Settings.SEEN_PER_CHAT),
    TYPING(FamilyNames.TYPING, "typing", null, Settings.HIDE_TYPING),
    ACTIVE_STATUS(FamilyNames.ACTIVE_STATUS, "activeStatus", null, Settings.HIDE_ACTIVE_STATUS),
    LAST_ACTIVE(FamilyNames.LAST_ACTIVE, "lastActive", null, Settings.SHOW_LAST_ACTIVE),
    MESSAGES_LOCK(FamilyNames.MESSAGES_LOCK, "messagesLock", null, Settings.LOCK_MESSAGES, Settings.LOCK_APP,
            Settings.LOCK_SAVED, Settings.LOCK_EACH_CHAT, Settings.DOUBLE_TAP_HIDDEN_CHATS),
    SCREENSHOT_REPORTS(FamilyNames.SCREENSHOT_REPORTS, "screenshotReports", null, Settings.HIDE_SCREENSHOTS),
    BLEND_VISITS(FamilyNames.BLEND_VISITS, "blendVisits", null, Settings.HIDE_BLEND_VISITS),
    SCREENSHOT_BLOCK(FamilyNames.SCREENSHOT_BLOCK, "screenshotBlock", null, Settings.ALLOW_SCREENSHOTS),
    KEEP_IN_CHAT(FamilyNames.KEEP_IN_CHAT, "keepInChat", null, Settings.KEEP_IN_CHAT),
    VIEW_ONCE_MARK(FamilyNames.VIEW_ONCE_MARK, "viewOnceMark", null, Settings.MARK_VIEW_ONCE),
    CHAT_REELS(FamilyNames.CHAT_REELS, "chatReels", null, Settings.SWIPE_CHAT_REELS, Settings.STOP_AFTER_CHAT_REELS,
            Settings.ONLY_RECEIVED_CHAT_REELS, Settings.MESSAGES_AFTER_CHAT_REELS),
    CHAT_MEDIA_WALK(FamilyNames.CHAT_MEDIA_WALK, "chatMediaWalk", null, Settings.SWIPE_CHAT_MEDIA),
    CHAT_HISTORY(FamilyNames.CHAT_HISTORY, "chatHistory", null, Settings.JUMP_IN_CHAT, Settings.EXPORT_CHAT),
    PREVIEW_CHATS(FamilyNames.PREVIEW_CHATS, "previewChats", null, Settings.PREVIEW_CHATS),
    ASK_BEFORE_CALL(FamilyNames.ASK_BEFORE_CALL, "askBeforeCall", null, Settings.ASK_BEFORE_CALL,
            Settings.HIDE_CALL_BUTTONS),
    CALL_BLOCKER(FamilyNames.CALL_BLOCKER, "callBlocker", null),
    KINDNESS_REMINDER(FamilyNames.KINDNESS_REMINDER, "kindnessReminder", null, Settings.HIDE_KINDNESS_REMINDER),
    MESSAGE_BOX(FamilyNames.MESSAGE_BOX, "messageBox", null),
    PLUS_MENU(FamilyNames.PLUS_MENU, "plusMenu", null, Settings.HIDE_PLUS_SAVED, Settings.HIDE_PLUS_AI_IMAGES,
            Settings.HIDE_PLUS_DRAW, Settings.MOVE_TO_PLUS_MENU),
    GLASS_CHAT(FamilyNames.GLASS_CHAT, "glassChat", null, Settings.GLASS_CHAT, Settings.GLASS_STORY_REPLY_BOX),
    ASK_BEFORE_LIKE(FamilyNames.ASK_BEFORE_LIKE, "askBeforeLike", null, Settings.ASK_BEFORE_LIKE),
    ASK_BEFORE_REFRESH(FamilyNames.ASK_BEFORE_REFRESH, "askBeforeRefresh", null, Settings.ASK_BEFORE_REFRESH),
    ASK_BEFORE_FOLLOW(FamilyNames.ASK_BEFORE_FOLLOW, "askBeforeFollow", null, Settings.ASK_BEFORE_FOLLOW),
    SAVE_TRAY(FamilyNames.SAVE_TRAY, "saveTray", null, Settings.SKIP_COLLECTIONS_TRAY),
    STORIES_TRAY(FamilyNames.STORIES_TRAY, "storiesTray", null, Settings.HIDE_SUGGESTED_STORIES,
            Settings.HIDE_STORY_REWINDS, Settings.HIDE_STORY_RECAPS, Settings.STOP_LOADING_STORIES,
            Settings.HIDE_STORIES_TRAY, Settings.KEEP_TRAY_IN_FEED, Settings.HIDE_MUSIC_CARD, Settings.HIDE_STORY_SUGGESTIONS,
            Settings.HIDE_ADD_YOURS_CARDS, Settings.FILTER_TRAY_BY_BADGE),
    STORY_RING(FamilyNames.STORY_RING, "storyRingSize", null, Settings.STORY_RING),
    STORY_PROGRESS_BAR(FamilyNames.STORY_PROGRESS_BAR, "storyProgressBar", null, Settings.THICKER_STORY_BAR),
    STORY_SEEK_BAR(FamilyNames.STORY_SEEK_BAR, "storySeekBar", null, Settings.STORY_SEEK_BAR),
    STORY_SPEED(FamilyNames.STORY_SPEED, "storySpeed", null, Settings.STORY_SPEED_BUTTON, Settings.STORY_SPEED_IN_MENU),
    FEED_REELS(FamilyNames.FEED_REELS, "feedReels", null, Settings.HIDE_FEED_REELS),
    FEED_SUGGESTIONS(FamilyNames.FEED_SUGGESTIONS, "feedSuggestions", null, Settings.HIDE_SUGGESTED_ACCOUNTS,
            Settings.HIDE_SUGGESTED_POSTS, Settings.HIDE_THREADS_POSTS, Settings.HIDE_FEED_SURVEYS,
            Settings.HIDE_FEED_STORY_ROWS, Settings.HIDE_FEED_SHOPPING, Settings.HIDE_FEED_INTEREST, Settings.HIDE_CAUGHT_UP_CARD, Settings.HIDE_CHANNEL_JOIN_BAR, Settings.HIDE_FEED_VIDEOS,
            Settings.HIDE_FEED_PHOTOS, Settings.HIDE_FEED_CAROUSELS, Settings.HIDE_FEED_LIKED, Settings.HIDE_FEED_PROFESSIONAL,
            Settings.HIDDEN_IN_FAVORITES),
    HOME_FEED(FamilyNames.HOME_FEED, "homeFeed", null, Settings.HIDE_HOME_FEED),
    FOLLOWING_FEED(FamilyNames.FOLLOWING_FEED, "followingFeed", null, Settings.START_ON_FOLLOWING,
            Settings.ONLY_FOLLOWING, Settings.LOGO_ON_FOLLOWING, Settings.FOLLOWING_FALLBACK, Settings.FOLLOWING_MIX, Settings.FAVORITES_TAB_BAR),
    SWIPE_TO_CREATE(FamilyNames.SWIPE_TO_CREATE, "swipeToCreate", null, Settings.STOP_SWIPE_TO_CREATE),
    TAB_SWIPE(FamilyNames.TAB_SWIPE, "tabSwipe", null, Settings.STOP_TAB_SWIPING),
    TAB_BAR_LAYOUT(FamilyNames.TAB_BAR_LAYOUT, "tabBarLayout", null),
    FULL_RESOLUTION(FamilyNames.FULL_RESOLUTION, "fullResolution", null, Settings.FULL_RESOLUTION_PHOTOS,
            Settings.ASK_FOR_LARGER_PHOTOS, Settings.FULL_SIZE_COVERS),
    META_AI(FamilyNames.META_AI, "metaAi", null, Settings.HIDE_META_AI_SEARCH, Settings.HIDE_META_AI_POSTS,
            Settings.HIDE_ABOUT_THIS_REEL, Settings.HIDE_ASK_META_AI, Settings.HIDE_META_AI_SHARE_TARGET,
            Settings.HIDE_META_AI_PROFILE_BANNER, Settings.HIDE_META_AI_PROFILE_BUTTON, Settings.HIDE_META_AI_BLEND),
    EXPLORE_GRID(FamilyNames.EXPLORE_GRID, "exploreGrid", null, Settings.HIDE_EXPLORE_GRID,
            Settings.HIDE_SEARCH_SUGGESTIONS),
    RECENT_SEARCHES(FamilyNames.RECENT_SEARCHES, "recentSearches", null, Settings.DONT_SAVE_RECENT_SEARCHES),
    ALL_RECENT_SEARCHES(FamilyNames.ALL_RECENT_SEARCHES, "allRecentSearches", null, Settings.SHOW_ALL_RECENT_SEARCHES),
    EXPLORE_MENU(FamilyNames.EXPLORE_MENU, "exploreMenu", null,
            Settings.EXPLORE_WITHOUT_PERSONALIZATION, Settings.HIDE_EXPLORE_MENU),
    NOTES_ROW(FamilyNames.NOTES_ROW, "notesRow", null, Settings.HIDE_NOTES_ROW, Settings.HIDE_NOTES_MAP),
    SPLIT_NOTES_ROW(FamilyNames.SPLIT_NOTES_ROW, "splitNotesRow", null, Settings.SPLIT_NOTES_ROW),
    INBOX_SEARCH(FamilyNames.INBOX_SEARCH, "inboxSearch", null, Settings.HIDE_INBOX_SEARCH),
    INBOX_SUGGESTIONS(FamilyNames.INBOX_SUGGESTIONS, "inboxSuggestions", null, Settings.HIDE_INBOX_SUGGESTIONS),
    INSTANTS(FamilyNames.INSTANTS, "instants", null, Settings.HIDE_INSTANTS),
    SHARE_SHEET(FamilyNames.SHARE_SHEET, "shareSheet", null, Settings.HIDE_SHARE_SHEET_GROUP, Settings.HIDE_SHARE_SHEET_SEARCH),
    REPOST_BUTTON(FamilyNames.REPOST_BUTTON, "repostButton", null, Settings.HIDE_REPOST_BUTTON),
    HIDE_SHARE_BUTTON(FamilyNames.HIDE_SHARE_BUTTON, "hideShareButton", null, Settings.HIDE_SHARE_BUTTON),
    BOTTOM_SPACE(FamilyNames.BOTTOM_SPACE, "bottomSpace", null, Settings.REMOVE_BOTTOM_SPACE),
    PHONE_LAYOUT(FamilyNames.PHONE_LAYOUT, "phoneLayout", null, Settings.PHONE_LAYOUT),
    GLASS_TAB_BAR(FamilyNames.GLASS_TAB_BAR, "glassTabBar", null, Settings.GLASS_TAB_BAR,
            Settings.GLASS_TAB_BAR_BLUR, Settings.GLASS_TAB_BAR_FLOAT, Settings.GLASS_TAB_BAR_HAPTICS,
            Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL, Settings.GLASS_TAB_BAR_SLIDE, Settings.GLASS_TAB_BAR_LIQUID,
            Settings.GLASS_TAB_BAR_KEEP_SIZE, Settings.GLASS_TAB_BAR_SHRINK_ON_SCROLL, Settings.GLASS_TAB_BAR_REFRACT,
            Settings.GLASS_TAB_BAR_REELS_BEHIND, Settings.GLASS_TAB_BAR_TAP_SOUND),
    FULL_SCREEN_BARS(FamilyNames.FULL_SCREEN_BARS, "fullScreenBars", null, Settings.FULL_SCREEN_REELS,
            Settings.FILL_REEL_SCREEN, Settings.FIT_REEL_SCREEN, Settings.FULL_SCREEN_HOME, Settings.FULL_SCREEN_MESSAGES,
            Settings.FILL_STORY_SCREEN),
    SAVED_ON_PROFILE(FamilyNames.SAVED_ON_PROFILE, "savedOnProfile", null, Settings.SAVED_ON_PROFILE,
            Settings.SAVED_FAST_SCROLL),
    STOP_HEARTBEAT(FamilyNames.STOP_HEARTBEAT, "stopHeartbeat", null, Settings.STOP_HEARTBEAT,
            Settings.STOP_UPLOAD_ALARM, Settings.STOP_BACKGROUND_FETCH),
    REFRESH_RATE(FamilyNames.REFRESH_RATE, "refreshRate", null, Settings.HIGHEST_REFRESH_RATE),
    EMOJI_STYLE(FamilyNames.EMOJI_STYLE, "emojiStyle", null, Settings.NOTO_EMOJI),
    ACCENT_COLOR(FamilyNames.ACCENT_COLOR, "accentColor", null, Settings.ACCENT_COLOR, Settings.ACCENT_BADGES,
            Settings.ACCENT_ICONS, Settings.ACCENT_RINGS, Settings.ACCENT_RINGS_SOLID, Settings.ACCENT_BACKGROUNDS),
    LAUNCH_SCREEN(FamilyNames.LAUNCH_SCREEN, "launchScreen", null, Settings.LAUNCH_SCREEN, Settings.LAUNCH_SCREEN_ACCENT),
    HEADER_TEXT(FamilyNames.HEADER_TEXT, "headerText", null),
    APP_ICON(FamilyNames.APP_ICON, "appIcon", "the home screen icon you picked"),
    SETTINGS_ROWS(FamilyNames.SETTINGS_ROWS, "settingsRows", null),
    FONT(FamilyNames.FONT, "font", null),
    NOTIFICATION_GROUPS(FamilyNames.NOTIFICATION_GROUPS, "notificationGroups", null, Settings.GROUP_NOTIFICATIONS),
    HDR_BOOST(FamilyNames.HDR_BOOST, "hdrBoost", null, Settings.TURN_OFF_HDR_BOOSTS),
    STOP_LOOPING(FamilyNames.STOP_LOOPING, "stopLooping", null, Settings.STOP_VIDEO_LOOPING),
    BACKGROUND_PLAY(FamilyNames.BACKGROUND_PLAY, "backgroundPlay", null, Settings.KEEP_PLAYING_IN_BACKGROUND,
            Settings.REELS_PICTURE_IN_PICTURE, Settings.STORIES_PICTURE_IN_PICTURE),
    MEDIA_CACHE(FamilyNames.MEDIA_CACHE, "mediaCache", null, Settings.CLEAR_MEDIA_CACHE),
    FRIENDSHIP_STATUS(FamilyNames.FRIENDSHIP_STATUS, "friendshipStatus", null, Settings.SHOW_FRIENDSHIP_STATUS,
            Settings.MARK_FOLLOWING_LIST, Settings.FOLLOW_MARK_ON_PICTURE, Settings.FRIENDSHIP_STATUS_CHIP,
            Settings.FOLLOWING_NOT_BACK_FIRST,
            Settings.TRACK_UNFOLLOWERS, Settings.NOTIFY_UNFOLLOWS),
    PROFILE_SUGGESTIONS(FamilyNames.PROFILE_SUGGESTIONS, "profileSuggestions", null, Settings.HIDE_PROFILE_SUGGESTIONS,
            Settings.HIDE_FOLLOW_LIST_SUGGESTIONS, Settings.HIDE_PROFILE_COMPLETION, Settings.HIDE_FOLLOW_BACK_SUGGESTIONS,
            Settings.HIDE_NOTIFICATION_SUGGESTIONS, Settings.HIDE_NOTIFICATION_CHANNEL_INVITES, Settings.HIDE_NOTIFICATION_FEATURED,
            Settings.HIDE_FEATURED_DOT),
    PROFILE_HIGHLIGHTS(FamilyNames.PROFILE_HIGHLIGHTS, "profileHighlights", null, Settings.HIDE_HIGHLIGHTS),
    PROFILE_TABS(FamilyNames.PROFILE_TABS, "profileTabs", null),
    THREADS_BUTTON(FamilyNames.THREADS_BUTTON, "threadsButton", null, Settings.HIDE_THREADS_BUTTON,
            Settings.HIDE_CREATE_BUTTON, Settings.HIDE_SHARE_PROFILE_BUTTON, Settings.HIDE_SUBSCRIBE_PROFILE_BUTTON,
            Settings.HIDE_PROFILE_OVERFLOW_BUTTON),
    PROFILE_NOTES(FamilyNames.PROFILE_NOTES, "profileNotes", null, Settings.HIDE_PROFILE_NOTES),
    PROFILE_BANNERS(FamilyNames.PROFILE_BANNERS, "profileBanners", null, Settings.HIDE_PROFILE_THREADS_BADGE,
            Settings.HIDE_PROFILE_FACEBOOK_LINKS, Settings.HIDE_PROFILE_WHATSAPP_LINK, Settings.HIDE_PROFILE_SONG,
            Settings.HIDE_PROFILE_INTERESTS, Settings.HIDE_PROFILE_CHANNEL_BANNER, Settings.HIDE_OTHER_PROFILE_BANNERS),
    PROFILE_POSTS_LIST(FamilyNames.PROFILE_POSTS_LIST, "profilePostsList", null, Settings.PROFILE_POSTS_LIST),
    PROFILE_GRID_JUMP(FamilyNames.PROFILE_GRID_JUMP, "profileGridJump", null, Settings.PROFILE_GRID_JUMP),
    COMMENT_COPY(FamilyNames.COMMENT_COPY, "commentCopy", null, Settings.COPY_COMMENTS, Settings.COPY_COMMENT_AUTHORS,
            Settings.COPY_COMMENTS_WITHOUT_MENTIONS, Settings.SELECT_TEXT),
    COMMENT_PHOTO(FamilyNames.COMMENT_PHOTO, "commentPhoto", null, Settings.SAVE_COMMENT_PHOTOS,
            Settings.REPLY_WITH_COMMENT_GIFS, Settings.SHOW_COMMENT_GIF_NAMES),
    PROFILE_PICTURE(FamilyNames.PROFILE_PICTURE, "profilePicture", null, Settings.SAVE_PROFILE_PICTURES,
            Settings.VIEW_PROFILE_PICTURES, Settings.COPY_PROFILE_TEXT, Settings.PROFILE_EXTRAS_BUTTON,
            Settings.ABOUT_ACCOUNT_BUTTON, Settings.PROFILE_HISTORY, Settings.SAVED_PROFILES),
    VOICE_MESSAGE(FamilyNames.VOICE_MESSAGE, "voiceMessage", null, Settings.DOWNLOAD_VOICE_MESSAGES),
    HIDE_COMMENTS(FamilyNames.HIDE_COMMENTS, "hideComments", null, Settings.HIDE_COMMENTS, Settings.HIDE_COMMENT_REPLY),
    COMMENT_EMOJI_ROW(FamilyNames.COMMENT_EMOJI_ROW, "commentEmojiRow", null, Settings.HIDE_COMMENT_EMOJI_ROW),
    COMMENT_KEYBOARD(FamilyNames.COMMENT_KEYBOARD, "commentKeyboard", null, Settings.COMMENT_KEYBOARD_DOWN),
    COMMENT_GIFS(FamilyNames.COMMENT_GIFS, "commentGifs", null, Settings.HIDE_COMMENT_GIFS),
    COMMENT_SEARCH(FamilyNames.COMMENT_SEARCH, "commentSearch", null, Settings.COMMENT_SEARCH),
    FEED_FOLLOW(FamilyNames.FEED_FOLLOW, "feedFollow", null, Settings.HIDE_FEED_FOLLOW, Settings.HIDE_FEED_SUBSCRIBE),
    EDITS_LABEL(FamilyNames.EDITS_LABEL, "editsLabel", null, Settings.HIDE_EDITS_LABEL),
    FEED_SAVE(FamilyNames.FEED_SAVE, "feedSave", null, Settings.SAVE_IN_POST_MENU, Settings.HIDE_FEED_SAVE_BUTTON),
    WATCH_AGAIN(FamilyNames.WATCH_AGAIN, "watchAgain", null, Settings.WATCH_AGAIN_IN_FEED, Settings.HIDE_WATCH_MORE_REELS),
    ACTIVITY_BUBBLES(FamilyNames.ACTIVITY_BUBBLES, "activityBubbles", null, Settings.HIDE_ACTIVITY_BUBBLES),
    HIDE_CAPTIONS(FamilyNames.HIDE_CAPTIONS, "hideCaptions", null, Settings.HIDE_CAPTIONS),
    FEED_AUDIO_ICON(FamilyNames.FEED_AUDIO_ICON, "feedAudioIcon", null, Settings.HIDE_FEED_AUDIO_ICON),
    HOME_TOP_BAR(FamilyNames.HOME_TOP_BAR, "homeTopBar", null, Settings.KEEP_HOME_TOP_BAR,
            Settings.SEE_THROUGH_HOME_TOP_BAR),
    REEL_DECLUTTER(FamilyNames.REEL_DECLUTTER, "reelDeclutter", null, Settings.HIDE_REEL_FOLLOW_BUTTON,
            Settings.HIDE_REEL_CHIPS, Settings.HIDE_REEL_SEARCH_PILL, Settings.HIDE_REEL_PRODUCT_PILLS,
            Settings.HIDE_REEL_POLL_PILLS, Settings.HIDE_REEL_SOCIAL_FOOTER, Settings.HIDE_REEL_COMMENT_BAR,
            Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE, Settings.KEEP_REEL_COMMENT_BAR_SPACE, Settings.HIDE_REEL_REPLY_BAR,
            Settings.HIDE_REELS_TOP_BAR,
            Settings.HIDE_REELS_CREATE_BUTTON, Settings.HIDE_SAVED_REELS_CREATE_BUTTON,
            Settings.HIDE_REELS_YOUR_ALGORITHM, Settings.HIDE_REELS_FRIENDS_TAB,
            Settings.HIDE_REEL_SAVE_BUTTON, Settings.KEEP_REEL_CLEAR_MODE, Settings.QUIET_REEL_CLEAR_MODE,
            Settings.CLEAR_MODE_SEEK_BAR, Settings.SHOW_REELS_FRIENDS_TAB, Settings.PICK_FRIENDS_TAB),
    KEEP_REELS_TOP_BAR(FamilyNames.KEEP_REELS_TOP_BAR, "keepReelsTopBar", null, Settings.KEEP_REELS_TOP_BAR),
    REEL_DOWNLOAD(FamilyNames.REEL_DOWNLOAD, "reelDownload", null, Settings.DOWNLOAD_REELS),
    DOUBLE_TAP_LIKE(FamilyNames.DOUBLE_TAP_LIKE, "doubleTapLike", null, Settings.TURN_OFF_DOUBLE_TAP_LIKE,
            Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS,
            Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES),
    LIKE_ANIMATION(FamilyNames.LIKE_ANIMATION, "likeAnimation", null, Settings.CHANGE_LIKE_ANIMATION,
            Settings.GROW_LIKE_HEART),
    REELS_TAB(FamilyNames.REELS_TAB, "reelsTab", null, Settings.HIDE_REELS_TAB, Settings.HIDE_SEARCH_TAB,
            Settings.HIDE_CREATE_TAB, Settings.HIDE_PROFILE_TAB, Settings.HIDE_HOME_CREATE_BUTTON,
            Settings.HIDE_HOME_NOTIFICATIONS_BUTTON, Settings.HEART_COUNT, Settings.SEARCH_BUTTON_ON_HOME, Settings.GHOST_BUTTON_ON_HOME,
            Settings.CENTER_HOME_LOGO, Settings.CENTER_PROFILE_NAME, Settings.CENTER_SCREEN_TITLES,
            Settings.TITLES_AT_START, Settings.SHOW_REELS_TAB,
            Settings.SHOW_MESSAGES_TAB, Settings.MESSAGES_BUTTON_ON_HOME, Settings.SETTINGS_BUTTON_ON_HOME, Settings.MENU_BUTTON_ON_HOME, Settings.HISTORY_BUTTON_ON_HOME, Settings.HIDE_HOME_MESSAGES_BUTTON,
            Settings.SHOW_ACTIVITY_TAB, Settings.HIDE_HOME_TAB, Settings.HIDE_MESSAGES_TAB),
    REELS_SUGGESTIONS(FamilyNames.REELS_SUGGESTIONS, "reelsSuggestions", null, Settings.HIDE_REELS_SUGGESTIONS),
    PAID_PARTNERSHIP_REELS(FamilyNames.PAID_PARTNERSHIP_REELS, "paidPartnershipReels", null,
            Settings.HIDE_PAID_PARTNERSHIP_REELS),
    LIVE_REELS(FamilyNames.LIVE_REELS, "liveReels", null, Settings.HIDE_LIVE_REELS),
    AI_LABELED_REELS(FamilyNames.AI_LABELED_REELS, "aiLabeledReels", null, Settings.HIDE_AI_LABELED_REELS),
    CAPTION_WORD_REELS(FamilyNames.CAPTION_WORD_REELS, "captionWordReels", null, Settings.HIDE_CAPTION_WORD_REELS,
            Settings.HIDE_OTHER_LANGUAGE_REELS),
    LOW_VIEW_REELS(FamilyNames.LOW_VIEW_REELS, "lowViewReels", null, Settings.HIDE_LOW_VIEW_REELS),
    KEEP_REEL_SPEED(FamilyNames.KEEP_REEL_SPEED, "keepReelSpeed", null, Settings.KEEP_REEL_SPEED),
    REEL_LINKS(FamilyNames.REEL_LINKS, "reelLinks", null, Settings.OPEN_REEL_LINKS_FIRST),
    REEL_POST_TIME(FamilyNames.REEL_POST_TIME, "reelPostTime", null, Settings.SHOW_REEL_POST_TIME),
    REEL_SEEK_BAR(FamilyNames.REEL_SEEK_BAR, "reelSeekBar", null, Settings.REEL_SEEK_BAR, Settings.REEL_SEEK_THUMB, Settings.BIG_REEL_SEEK_BAR,
            Settings.DM_REEL_SEEK_BAR, Settings.PHOTO_REEL_SEEK_BAR),
    REEL_BLUR_BARS(FamilyNames.REEL_BLUR_BARS, "reelBlurBars", null, Settings.BLUR_REEL_BARS),
    REEL_TAP_AND_VOLUME(FamilyNames.REEL_TAP_AND_VOLUME, "reelTapAndVolume", null, Settings.KEEP_REELS_MUTED),
    REEL_SOUND(FamilyNames.REEL_SOUND, "reelSound", null, Settings.START_REELS_WITH_SOUND,
            Settings.START_REELS_MUTED),
    REEL_ZOOM(FamilyNames.REEL_ZOOM, "reelZoom", null, Settings.PINCH_ZOOM_REELS),
    REEL_DOUBLE_TAP_SKIP(FamilyNames.REEL_DOUBLE_TAP_SKIP, "reelDoubleTapSkip", null, Settings.DOUBLE_TAP_SKIP_REELS),
    HIDE_REEL_2X_POPUP(FamilyNames.HIDE_REEL_2X_POPUP, "reelFastLabel", null, Settings.HIDE_REEL_2X_POPUP),
    REEL_AUTO_SCROLL(FamilyNames.REEL_AUTO_SCROLL, "reelAutoScroll", null, Settings.KEEP_REEL_AUTO_SCROLL,
            Settings.REEL_AUTO_ADVANCE),
    REEL_SCROLLING(FamilyNames.REEL_SCROLLING, "reelScrolling", null, Settings.STOP_REELS_SCROLLING,
            Settings.REEL_SCROLL_FROM_PROFILE, Settings.REEL_SCROLL_FROM_SEARCH, Settings.REEL_SCROLL_FROM_AUDIO,
            Settings.REEL_CAP),
    SWIPE_TO_PROFILE(FamilyNames.SWIPE_TO_PROFILE, "swipeToProfile", null, Settings.REEL_SWIPE_TO_PROFILE),
    REEL_PROFILE_TAB_BAR(FamilyNames.REEL_PROFILE_TAB_BAR, "reelProfileTabBar", null, Settings.REEL_PROFILE_TAB_BAR),
    REEL_ORIGIN(FamilyNames.REEL_ORIGIN, "reelOrigin", null, Settings.FIND_REEL_ORIGIN,
            Settings.FIND_REEL_ORIGIN_ON_TAP),
    STORY_DOWNLOAD(FamilyNames.STORY_DOWNLOAD, "storyDownload", null, Settings.DOWNLOAD_STORIES,
            Settings.DOWNLOAD_BUTTON_ON_STORIES, Settings.TEXT_BUTTON_ON_STORIES),
    VIDEO_DOWNLOAD(FamilyNames.VIDEO_DOWNLOAD, "videoDownload", null, Settings.DOWNLOAD_VIDEOS, Settings.DOWNLOAD_PHOTOS,
            Settings.POST_DETAILS, Settings.DOWNLOAD_SKIP_SAVED, Settings.VIEWING_HISTORY),
    TAP_TO_PLAY(FamilyNames.TAP_TO_PLAY, "tapToPlay", null, Settings.TAP_TO_PLAY),
    RESUME_LONG_VIDEOS(FamilyNames.RESUME_LONG_VIDEOS, "resumeLongVideos", null, Settings.RESUME_LONG_VIDEOS),
    PLAYBACK_QUALITY(FamilyNames.PLAYBACK_QUALITY, "defaultPlaybackQuality", null,
            Settings.DEFAULT_PLAYBACK_QUALITY),
    DATA_SAVER(FamilyNames.DATA_SAVER, "dataSaver", null, Settings.DATA_SAVER),
    TRANSLATED_START(FamilyNames.TRANSLATED_START, "translatedStart", "the start-up fix for x86 devices"),
    PURE_BLACK(FamilyNames.PURE_BLACK, "pureBlack", "the pure black dark mode"),
    VERSION_CODE(FamilyNames.VERSION_CODE, "versionCode", "the raised version code");

    /** The name Morphe Manager lists the patch under. */
    public final String patchName;

    /** The {@link SettingsStatus} method the patch switches on. */
    final String statusMethod;

    /**
     * What of this patch stays in while HushGram is paused, in English, or null when a pause turns
     * all of it off. The English is also a key of {@link L10n}: the screen shows it translated, and
     * the report keeps it in English.
     */
    @Nullable
    public final String staysWhilePaused;

    /** The switches Pause turns off for this patch. Empty when it has none. */
    public final List<BooleanSetting> switches;

    /** The families a test says this build carries, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Set<PatchFamily> inBuildForTests;

    PatchFamily(String patchName, String statusMethod, @Nullable String staysWhilePaused,
                BooleanSetting... switches) {
        this.patchName = patchName;
        this.statusMethod = statusMethod;
        this.staysWhilePaused = staysWhilePaused;
        this.switches = Collections.unmodifiableList(Arrays.asList(switches));
    }

    /** Whether this patch was selected for this build. */
    public boolean inBuild() {
        Set<PatchFamily> forced = inBuildForTests;
        if (forced != null) return forced.contains(this);
        try {
            return Boolean.TRUE.equals(SettingsStatus.class.getMethod(statusMethod).invoke(null));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Could not ask whether " + patchName + " is in this build", failure);
            return false;
        }
    }

    /** Whether a test says this build marks your own Following list, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean followingListMarkForTests;

    /**
     * Whether this build marks your own Following list. Show if a profile follows you goes in without
     * it when Instagram's follow list has moved, so its second switch isn't offered then.
     */
    public static boolean followingListMarkInBuild() {
        Boolean forced = followingListMarkForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(FRIENDSHIP_STATUS);
        return FRIENDSHIP_STATUS.inBuild() && SettingsStatus.followingListMark();
    }

    /** Whether a test says this build keeps Recent unfollowers, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean unfollowersForTests;

    /**
     * Whether this build keeps Recent unfollowers. It goes in only with the Following list switches,
     * and without them or when Instagram's follow list pager has moved, it isn't offered.
     */
    public static boolean unfollowersInBuild() {
        Boolean forced = unfollowersForTests;
        if (forced != null) return forced;
        if (!followingListMarkInBuild()) return false;
        return inBuildForTests != null || SettingsStatus.unfollowers();
    }

    /** Whether a test says this build copies a commenter's username, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean commentAuthorForTests;

    /**
     * Whether this build copies a commenter's username. Copy comment goes in without it when the
     * comment menu's label or the comment's author have moved, so its second switch isn't offered then.
     */
    public static boolean commentAuthorInBuild() {
        Boolean forced = commentAuthorForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(COMMENT_COPY);
        return COMMENT_COPY.inBuild() && SettingsStatus.commentAuthor();
    }

    /** Whether a test says this build replies with a comment's GIF, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean commentGifReplyForTests;

    /**
     * Whether this build replies with a comment's GIF. Save comment photo goes in without it when the
     * comment screen's Reply or its GIF send have moved, so its second switch isn't offered then.
     */
    public static boolean commentGifReplyInBuild() {
        Boolean forced = commentGifReplyForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(COMMENT_PHOTO);
        return COMMENT_PHOTO.inBuild() && SettingsStatus.commentGifReply();
    }

    /** Whether a test says this build shows a comment GIF's name, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean commentGifNameForTests;

    /**
     * Whether this build shows a comment GIF's name. It takes the GIF's title from Save comment photo
     * and its row and label from Copy username, so its switch isn't offered without both.
     */
    public static boolean commentGifNameInBuild() {
        Boolean forced = commentGifNameForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(COMMENT_PHOTO) && families.contains(COMMENT_COPY);
        return COMMENT_PHOTO.inBuild() && SettingsStatus.commentGifName() && commentAuthorInBuild();
    }

    /** Whether a test says this build takes Reply off comments, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean commentReplyForTests;

    /**
     * Whether this build takes Reply off each comment in a post's or reel's comments. Hide comments
     * goes in without it when the line under a comment has moved, so that switch isn't offered then.
     */
    public static boolean commentReplyInBuild() {
        Boolean forced = commentReplyForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(HIDE_COMMENTS);
        return HIDE_COMMENTS.inBuild() && SettingsStatus.commentReply();
    }

    /** Whether a test says this build filters Home by a post's type, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean feedTypesForTests;

    /**
     * Whether this build filters Home by a post's type, whether you've liked it and who posted it.
     * Hide suggested posts goes in without it when Home's reads or a post's fields have moved, so
     * those switches and Hidden accounts aren't offered then.
     */
    public static boolean feedTypesInBuild() {
        Boolean forced = feedTypesForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(FEED_SUGGESTIONS);
        return FEED_SUGGESTIONS.inBuild() && SettingsStatus.feedTypes();
    }

    /** Whether a test says this build tells which feed Home asks for, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean favoritesSourceForTests;

    /**
     * Whether this build tells a page of Favorites from one of For you or Following, which Keep
     * hidden accounts in Favorites needs. Hide suggested posts goes in without it when the
     * parameters of Home's requests have moved, so that switch isn't offered then.
     */
    public static boolean favoritesSourceInBuild() {
        Boolean forced = favoritesSourceForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(FEED_SUGGESTIONS);
        return feedTypesInBuild() && SettingsStatus.favoritesSource();
    }

    /** Whether a test says this build reads an account's type, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean professionalForTests;

    /**
     * Whether this build reads whether a post's author is a business or creator account, which Hide
     * posts from business and creator accounts needs. Hide suggested posts goes in without it when
     * an account's type has moved, so that switch isn't offered then.
     */
    public static boolean professionalInBuild() {
        Boolean forced = professionalForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(FEED_SUGGESTIONS);
        return feedTypesInBuild() && SettingsStatus.feedProfessional();
    }

    /** Whether a test says this build mixes Following into For you, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean followingMixForTests;

    /**
     * Whether this build mixes posts from Following into For you. Start Home on Following goes in
     * without it when Home's paging has moved, so its Mix in Following switch isn't offered then.
     */
    public static boolean followingMixInBuild() {
        Boolean forced = followingMixForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(FOLLOWING_FEED);
        return FOLLOWING_FEED.inBuild() && SettingsStatus.followingMix();
    }

    /** Whether a test says this build has Favorites from the tab bar, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean favoritesFeedForTests;

    /**
     * Whether this build keeps the tab bar on Favorites and opens it from a tab (#643). Start Home on
     * Following goes in without it when Home's feed switch or the tab bar has moved, so its rows
     * aren't offered then.
     */
    public static boolean favoritesFeedInBuild() {
        Boolean forced = favoritesFeedForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(FOLLOWING_FEED);
        return FOLLOWING_FEED.inBuild() && SettingsStatus.favoritesFeed();
    }

    /** The families this build carries, in declaration order. */
    public static Set<PatchFamily> inThisBuild() {
        Set<PatchFamily> found = EnumSet.noneOf(PatchFamily.class);
        for (PatchFamily family : values()) {
            if (family.inBuild()) found.add(family);
        }
        return found;
    }

    /**
     * What of these families stays in while HushGram is paused, as a sentence in the phone's
     * language, or null when a pause turns every one of them off. Each item is followed by its
     * patch's name in brackets, the name Morphe Manager lists it under.
     */
    @Nullable
    static String staysWhilePausedSummary(Set<PatchFamily> inBuild) {
        List<String> parts = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (inBuild.contains(family) && family.staysWhilePaused != null) {
                parts.add(L10n.t(family.staysWhilePaused) + " (" + L10n.isolate(family.patchName) + ")");
            }
        }
        if (parts.isEmpty()) return null;
        return L10n.capitalize(L10n.quantity(parts.size(),
                "%1$s. It was set when you patched, so Pause can't turn it off. To rule it out, patch again "
                        + "and leave out that patch.",
                "%1$s. They were set when you patched, so Pause can't turn them off. To rule one out, patch "
                        + "again and leave out the patch in brackets after it.",
                L10n.join(parts)));
    }

    /**
     * One line per family in this build, saying whether a switch runs it, what the switch is set
     * to and what stays in while paused, then the families this build doesn't carry.
     */
    static List<String> reportLines(Set<PatchFamily> inBuild, boolean paused) {
        List<String> lines = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (inBuild.contains(family)) {
                lines.add(family.reportLine(paused));
                if (family == FEED_SUGGESTIONS && !feedTypesInBuild()) {
                    lines.add("  Hide videos, Hide photos, Hide carousels, Hide posts you've liked, Hide posts from business "
                            + "and creator accounts and Hidden accounts: not in this build (Home's feed or a post's details didn't match)");
                } else if (family == FEED_SUGGESTIONS) {
                    if (!favoritesSourceInBuild()) {
                        lines.add("  Keep hidden accounts in Favorites: not in this build (Home's requests didn't match)");
                    }
                    if (!professionalInBuild()) {
                        lines.add("  Hide posts from business and creator accounts: not in this build (an account's type didn't match)");
                    }
                }
                if (family == COMMENT_COPY && !commentAuthorInBuild()) {
                    lines.add("  Copy the commenter's username: not in this build (the comment menu's label or the comment's author didn't match)");
                }
                if (family == HIDE_COMMENTS && !commentReplyInBuild()) {
                    lines.add("  Hide Reply on comments: not in this build (the line under a comment didn't match)");
                }
                if (family == FOLLOWING_FEED && !followingMixInBuild()) {
                    lines.add("  Mix in Following: not in this build (Home's paging didn't match)");
                }
                if (family == FOLLOWING_FEED && !favoritesFeedInBuild()) {
                    lines.add("  Favorites from the tab bar: not in this build (Home's feed switch or the tab bar didn't match)");
                }
                if (family == COMMENT_PHOTO && !commentGifReplyInBuild()) {
                    lines.add("  Reply with a comment's GIF: not in this build (the comment screen's Reply or GIF send didn't match)");
                }
                if (family == COMMENT_PHOTO && !commentGifNameInBuild()) {
                    lines.add("  Show a comment GIF's name: not in this build (the GIF's title or Copy username's row didn't match)");
                }
                if (family == FRIENDSHIP_STATUS && !followingListMarkInBuild()) {
                    lines.add("  Mark who doesn't follow you back: not in this build (Instagram's follow list didn't match)");
                } else if (family == FRIENDSHIP_STATUS && !unfollowersInBuild()) {
                    lines.add("  Recent unfollowers: not in this build (Instagram's follow list pager didn't match)");
                }
                if (family == VERSION_CODE) lines.add("  " + VersionCode.reportLine());
                if (family == SANITIZE_SHARING_LINKS || family == TRANSLATED_START) {
                    try {
                        String encoded = (String) SettingsStatus.class.getMethod(family.statusMethod + "Coverage").invoke(null);
                        lines.add("  " + coverageLine(encoded));
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        lines.add("  patch target coverage unavailable");
                    }
                }
            }
            else absent.add(family.patchName);
        }
        if (!absent.isEmpty()) lines.add("not in this build: " + String.join(", ", absent));
        return lines;
    }

    /** Coverage metadata that read back whole: how many targets the patch found, and which it didn't. */
    static final class Coverage {
        final int matched;
        final int expected;
        final List<String> missing;

        private Coverage(int matched, int expected, List<String> missing) {
            this.matched = matched;
            this.expected = expected;
            this.missing = missing;
        }
    }

    /** The same fixed-label metadata the fixture tools read from this APK's DEX, or null if it's malformed. */
    static Coverage coverage(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 32768) return null;
        try {
            String[] parts = encoded.split("\\|", -1);
            if (parts.length != 5 || !parts[0].equals("1")) return null;
            int matched = Integer.parseInt(parts[1]);
            int expected = Integer.parseInt(parts[2]);
            List<String> targets = Arrays.asList(parts[3].split(",", -1));
            List<String> missing = parts[4].isEmpty() ? Collections.emptyList() : Arrays.asList(parts[4].split(",", -1));
            if (matched < 1 || expected < matched || expected > 256 || targets.size() != expected
                    || missing.size() != expected - matched || new HashSet<>(targets).size() != expected
                    || new HashSet<>(missing).size() != missing.size() || !targets.containsAll(missing)) {
                return null;
            }
            for (String label : targets) {
                if (!label.matches("[a-z][a-z0-9 -]{0,63}")) return null;
            }
            return new Coverage(matched, expected, missing);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    static String coverageLine(String encoded) {
        Coverage found = coverage(encoded);
        if (found == null) return "patch target coverage unavailable";
        return "patch targets matched " + found.matched + "/" + found.expected
                + (found.missing.isEmpty() ? " (complete)" : " (partial); missing: " + String.join(", ", found.missing))
                + ". Patch-time matches do not prove live endpoint suppression.";
    }

    /**
     * The sentence a privacy switch's row adds when this build has only part of what the patch works
     * on, so an on switch can't read as full protection (audit A03). Empty when it has all of it or
     * the metadata can't be read.
     */
    static String partialCoverageNote(String encoded) {
        Coverage found = coverage(encoded);
        if (found == null || found.missing.isEmpty()) return "";
        return L10n.f("On this Instagram build it covers %1$d of %2$d routes. The diagnostic report lists the rest.",
                found.matched, found.expected);
    }

    /** "on", "disabled by its switch" or "disabled while paused", then the saved switches. */
    private String reportLine(boolean paused) {
        StringBuilder line = new StringBuilder(patchName).append(": ");
        if (switches.isEmpty() && staysWhilePaused == null) {
            // A choice, not a switch: paused, it answers Instagram's own.
            line.append(paused ? "disabled while paused" : "a choice, not a switch");
            String chosen = choiceText();
            if (!chosen.isEmpty()) line.append(" (").append(chosen).append(')');
            return line.toString();
        }
        if (switches.isEmpty()) {
            return line.append("no switch, stays in while paused: ").append(staysWhilePaused).toString();
        }
        boolean anyOn = false;
        // One under a switch that's off does nothing, whatever it holds. Turn off double tap to like
        // acts only through the places under it, while every other switch with switches under it
        // acts on its own and those only change how.
        Set<Object> parents = new HashSet<>();
        if (this == DOUBLE_TAP_LIKE) {
            for (BooleanSetting setting : switches) parents.addAll(setting.getParentSettings());
        }
        for (BooleanSetting setting : switches) {
            anyOn |= setting.savedValue() && reachable(setting) && !parents.contains(setting);
        }
        // What a tap on a reel does is a choice beside the switch, and a saved Pause or Mute counts as on.
        anyOn |= this == REEL_TAP_AND_VOLUME
                && Settings.REEL_TAP_CHOICE.savedValue() != app.hushgram.extension.instagram.reels.ReelTapChoice.DEFAULT;
        // Share sheet buttons picked to hide act with the group switch off (#252).
        anyOn |= this == SHARE_SHEET && !Settings.HIDDEN_SHARE_SHEET_BUTTONS.savedValue().trim().isEmpty();
        // Muted notification types act with the group switch off.
        anyOn |= this == NOTIFICATION_GROUPS && !Settings.MUTED_NOTIFICATION_CHANNELS.savedValue().trim().isEmpty();
        line.append(paused ? "disabled while paused (saved " : anyOn ? "on (" : "disabled by its switch (");
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) line.append(", ");
            BooleanSetting setting = switches.get(i);
            line.append(setting.key).append(setting.savedValue() ? "=on" : "=off");
        }
        String chosen = choiceText();
        if (!chosen.isEmpty()) line.append(", ").append(chosen);
        line.append(')');
        if (staysWhilePaused != null) line.append("; stays in while paused: ").append(staysWhilePaused);
        return line.toString();
    }

    /**
     * Whether a switch is reachable all the way up. {@code parent()} walks the chain itself now, but
     * a hand-written availability (Tab bar corners) may name a parent without asking whether that
     * parent is reachable, so the report still climbs the listed parents.
     */
    private static boolean reachable(Setting<?> setting) {
        if (!setting.isAvailable()) return false;
        List<Setting<?>> above = setting.getParentSettings();
        if (above.isEmpty()) return true;
        for (Setting<?> parent : above) {
            boolean on = !(parent instanceof BooleanSetting) || ((BooleanSetting) parent).savedValue();
            if (on && reachable(parent)) return true;
        }
        return false;
    }

    /**
     * The choices a report prints beside the switches, so it shows what a switch that's on picked
     * (#85 needed the playback quality and the report left it out).
     */
    List<Setting<?>> choices() {
        switch (this) {
            case FONT:
                return Collections.singletonList(Settings.FONT);
            case EMOJI_STYLE:
                return Collections.singletonList(Settings.EMOJI_FONT);
            case MESSAGE_BOX:
                return Collections.singletonList(Settings.HIDDEN_MESSAGE_BOX_BUTTONS);
            case NOTIFICATION_GROUPS:
                return Collections.singletonList(Settings.MUTED_NOTIFICATION_CHANNELS);
            case PROFILE_TABS:
                return Arrays.asList(Settings.HIDDEN_OWN_PROFILE_TABS, Settings.HIDDEN_PROFILE_TABS);
            case PLAYBACK_QUALITY:
                return Collections.singletonList(Settings.PLAYBACK_QUALITY);
            case SHARE_SHEET:
                return Collections.singletonList(Settings.HIDDEN_SHARE_SHEET_BUTTONS);
            case SETTINGS_ROWS:
                return Collections.singletonList(Settings.HIDDEN_SETTINGS_ROWS);
            case REEL_TAP_AND_VOLUME:
                return Collections.singletonList(Settings.REEL_TAP_CHOICE);
            case TAB_BAR_LAYOUT:
                return Collections.singletonList(Settings.TAB_BAR_LAYOUT);
            default:
                return Collections.emptyList();
        }
    }

    private String choiceText() {
        StringBuilder text = new StringBuilder();
        for (Setting<?> choice : choices()) {
            if (text.length() > 0) text.append(", ");
            text.append(choice.key).append('=').append(choice.savedValue());
        }
        return text.toString();
    }

    /**
     * Registers the [PATCHES] and [SCREEN] report sections, and tells Hook status which families no
     * pause reaches, so a paused export marks only the ones a switch runs. Registering twice keeps one.
     */
    public static void registerDiagnostics() {
        LogBufferManager.registerReportSection(REPORT);
        LogBufferManager.registerReportSection(ScreenReport.SECTION);
        for (PatchFamily family : values()) {
            if (family.switches.isEmpty() && family.staysWhilePaused != null) HookStatus.runsWhilePaused(family.patchName);
        }
    }

    /** The [PATCHES] section of the diagnostic report. */
    static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override
        public String title() {
            return "PATCHES";
        }

        @Override
        public List<String> lines() {
            return reportLines(inThisBuild(), HushgramPause.isPaused());
        }
    };
}
