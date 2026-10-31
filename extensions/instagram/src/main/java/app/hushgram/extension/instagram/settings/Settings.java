/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.hushgram.extension.shared.settings.Setting.parent;
import static app.hushgram.extension.shared.settings.Setting.parentsAny;

import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.download.FileNameTemplate;
import app.hushgram.extension.instagram.download.HoldFingers;
import app.hushgram.extension.instagram.download.HoldTime;
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.media.TapToPlayScope;
import app.hushgram.extension.instagram.profile.ProfileTabs;
import app.hushgram.extension.instagram.stories.StoryBarThickness;
import app.hushgram.extension.instagram.stories.StoryRingColor;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.instagram.stories.StoryTimeMode;
import app.hushgram.extension.instagram.stories.TrayBadge;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.EnumSetting;
import app.hushgram.extension.shared.settings.IntegerSetting;
import app.hushgram.extension.shared.settings.LongSetting;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.StringSetting;

import java.util.Collections;
import java.util.List;

/**
 * The switches behind the hooks that ask before they act.
 *
 * <p>A switch's default is the second argument of its {@link BooleanSetting}. Morphe Manager's simple
 * mode picks nearly every patch, so a switch that changes what Instagram does starts off and the
 * reader turns it on here. The few that start on were in the default selection before that, and
 * kept their switches. While HushGram is paused, safe mode included
 * ({@link app.hushgram.extension.shared.settings.HushgramPause}), a switch answers off and the hook
 * behind it takes Instagram's own path.
 */
@SuppressWarnings("unused")
public class Settings extends BaseSettings {
    /**
     * Navigation listeners are installed at native tab binding, and a change in settings puts the
     * choice on the tabs already built ({@link NavigationSettings#applyChoice}), so no restart (#82).
     */
    public static final EnumSetting<NavigationTarget> NAVIGATION_SETTINGS_TARGET =
            new EnumSetting<>("hushgram_navigation_settings_target", NavigationTarget.OFF, false);

    /**
     * The tab whose long press opens the Playback quality picker (#93). It rides the same tab hooks as
     * {@link #NAVIGATION_SETTINGS_TARGET}, so a change reaches the tabs already built, and when both
     * name one tab, opening HushGram wins. Off to start.
     */
    public static final EnumSetting<NavigationTarget> QUALITY_TAB_TARGET =
            new EnumSetting<>("hushgram_quality_tab_target", NavigationTarget.OFF, false);

    /**
     * The tab whose long press turns Ghost mode on or off (#255), the switches {@link GhostMode#picked}
     * names. It rides the same tab hooks as {@link #NAVIGATION_SETTINGS_TARGET}; when another choice
     * names the same tab, opening HushGram and then the quality picker come first. Off to start.
     */
    public static final EnumSetting<NavigationTarget> GHOST_TAB_TARGET =
            new EnumSetting<>("hushgram_ghost_tab_target", NavigationTarget.OFF, false);

    /**
     * Leaves the HushGram row out of Instagram's own settings menu while another way in works
     * ({@link SettingsEntry#menuRowLeftOut}): the launcher shortcut or the logo's long press with its
     * switch on, the chosen tab on a button Instagram built ({@link NavigationSettings#opensFromATab}),
     * or a profile menu button carrying the long press ({@link ProfileMenuPress#opensHushGram}). Off
     * to start. With every other way off, or paused, the row comes back, so there's always a way in.
     */
    public static final BooleanSetting HIDE_MENU_ROW =
            new BooleanSetting("hushgram_hide_menu_row", FALSE);

    /**
     * HushGram settings in the menu you get by holding Instagram's icon ({@link SettingsEntry}). On
     * to start. Off, the shortcut is taken out and neither a start nor Instagram's own shortcut calls
     * put it back; Instagram's own stay (HushFacebook#146).
     */
    public static final BooleanSetting SETTINGS_SHORTCUT =
            new BooleanSetting("hushgram_settings_shortcut", TRUE);

    /**
     * A long press on the Instagram logo at the top of Home opens HushGram
     * ({@link SettingsEntry#setLogoLongClickListener}). On to start. Off, the press is Instagram's
     * again. Read on every press, so no restart (HushFacebook#146).
     */
    public static final BooleanSetting SETTINGS_LOGO_PRESS =
            new BooleanSetting("hushgram_settings_logo_press", TRUE);

    static {
        // Both are ways back to the switch that resumes HushGram, so a pause neither takes them
        // away nor brings them back. Here, after both fields, since a static block runs in order.
        Setting.keepWhenPaused(SETTINGS_SHORTCUT, SETTINGS_LOGO_PRESS);
    }

    /**
     * A long press on the menu button, the three lines at the top right of your own profile, opens
     * HushGram (#84). Off to start. Read through Pause, and only a button Instagram gave no long
     * press of its own gets one ({@link ProfileMenuPress}), so off, paused or not ready yet, the
     * button is Instagram's.
     */
    public static final BooleanSetting PROFILE_MENU_LONG_PRESS =
            new BooleanSetting("hushgram_profile_menu_long_press", FALSE);

    /**
     * Categories expand inline on the main settings screen. Off shows every setting in one list.
     * Keep the existing stored key and default for upgrades and backups. Read the saved value
     * because Pause doesn't change how settings are displayed.
     */
    public static final BooleanSetting CATEGORY_PAGES =
            new BooleanSetting("hushgram_category_pages", TRUE);

    /**
     * Each switch's description behind an "i" at the end of its title, for a shorter page (#130).
     * Off to start. A settings-page choice, read saved even during Pause ({@link DescriptionInfo}).
     */
    public static final BooleanSetting DESCRIPTIONS_BEHIND_INFO =
            new BooleanSetting("hushgram_descriptions_behind_info", FALSE);

    /** A settings-page choice, read saved even during Pause. Existing backup names stay the default. */
    public static final BooleanSetting DATE_SETTINGS_EXPORT =
            new BooleanSetting("hushgram_date_settings_export", FALSE);

    /** Sponsored posts, reels and stories: the ad injector is told no ad went in. */
    public static final BooleanSetting HIDE_ADS =
            new BooleanSetting("hushgram_hide_ads", TRUE);

    /** igsh, igshid, utm_source and the other tracking keys come off links that leave Instagram. */
    public static final BooleanSetting SANITIZE_SHARING_LINKS =
            new BooleanSetting("hushgram_sanitize_sharing_links", TRUE);

    /**
     * The domain links to instagram.com go out on when you copy or share them
     * ({@link app.hushgram.extension.instagram.share.SharingDomain}). Blank to start, which keeps
     * instagram.com. Used only while {@link #SANITIZE_SHARING_LINKS} is on.
     */
    public static final StringSetting SHARING_DOMAIN =
            new StringSetting("hushgram_sharing_domain", "", parent(SANITIZE_SHARING_LINKS));

    /**
     * A web link tapped in Instagram opens in the phone's default browser, without Instagram's
     * click tracker. Instagram and other Meta pages, and ads, stay in the in-app browser.
     */
    public static final BooleanSetting OPEN_LINKS_EXTERNALLY =
            new BooleanSetting("hushgram_open_links_externally", TRUE);

    /**
     * The screen an old build shows to push an update, which a patched build can't install from
     * the Play Store. Instagram checks when its main screen opens.
     */
    public static final BooleanSetting REMOVE_BUILD_EXPIRED_POPUP =
            new BooleanSetting("hushgram_remove_build_expired_popup", TRUE);

    /**
     * The reels you watch, and how far into each you got, which Instagram posts to
     * clips/write_seen_state/ to rank your Reels. Nobody else sees it. Held back, reels you've
     * watched may come back. Off to start.
     */
    public static final BooleanSetting DONT_SEND_REEL_WATCH_HISTORY =
            new BooleanSetting("hushgram_dont_send_reel_watch_history", FALSE);

    /**
     * A story whose photo timer ran out or whose video ended stays on screen until you tap or
     * swipe, instead of the viewer moving on by itself. Off to start.
     */
    public static final BooleanSetting BLOCK_STORY_AUTO_ADVANCE =
            new BooleanSetting("hushgram_block_story_auto_advance", FALSE);

    /**
     * A story's header shows when it was posted the way {@link #STORY_TIME_MODE} says, in the
     * phone's language and 12 or 24-hour setting, instead of how long ago
     * ({@link app.hushgram.extension.instagram.stories.StoryTime}). Read as each header is drawn,
     * so a change shows from the next story. Off to start.
     */
    public static final BooleanSetting SHOW_STORY_TIME =
            new BooleanSetting("hushgram_show_story_time", FALSE);

    /**
     * How {@link #SHOW_STORY_TIME} writes the time: the date and time it was posted, the time left
     * before the story expires, or only the time of day it was posted. It starts as the date and
     * time, which is what the switch showed before the choice, so no one's header changes until
     * they pick. It isn't a switch: the switch above it is.
     */
    public static final EnumSetting<StoryTimeMode> STORY_TIME_MODE =
            new EnumSetting<>("hushgram_story_time_mode", StoryTimeMode.DATE_AND_TIME, parent(SHOW_STORY_TIME));

    /**
     * A story's header gets a pill under the name saying how many accounts the story mentions, and
     * a tap on it lists them ({@link app.hushgram.extension.instagram.stories.StoryMentions}). Read
     * at each story's bind. Off to start.
     */
    public static final BooleanSetting SHOW_STORY_MENTIONS =
            new BooleanSetting("hushgram_show_story_mentions", FALSE);

    /**
     * A story's header leaves out the Follow button Instagram shows for an account you don't follow
     * ({@link app.hushgram.extension.instagram.stories.StoryFollowButton}). Read before each frame
     * of a story header. Off to start.
     */
    public static final BooleanSetting HIDE_STORY_FOLLOW =
            new BooleanSetting("hushgram_hide_story_follow", FALSE);

    /**
     * A feed post's footer and each comment show the date and time they went up instead of how
     * long ago ({@link app.hushgram.extension.instagram.feed.PostTime}), the way a story's exact
     * time writes it. Read as each time is written, so a change shows on posts and comments loaded
     * after it. Off to start.
     */
    public static final BooleanSetting SHOW_POST_TIME = new BooleanSetting("hushgram_show_post_time", FALSE);

    /**
     * A post or reel whose poster hid its like count shows the count, when Instagram's server still
     * sends it ({@link app.hushgram.extension.instagram.feed.HiddenLikeCounts}). Read each time a like
     * row is drawn, so a change shows on posts loaded after it. Off to start.
     */
    public static final BooleanSetting SHOW_HIDDEN_LIKE_COUNTS =
            new BooleanSetting("hushgram_show_hidden_like_counts", FALSE);

    /**
     * Back on Home leaves as it is, instead of first scrolling the feed to the top and reloading it
     * ({@link app.hushgram.extension.instagram.feed.BackLeavesHome}). Read at each Back press on Home,
     * so a change applies at once. Off to start.
     */
    public static final BooleanSetting BACK_LEAVES_HOME =
            new BooleanSetting("hushgram_back_leaves_home", FALSE);

    /**
     * Coming back to Instagram leaves Home where it was, however long you were away, instead of
     * reloading the feed and scrolling to the top after 30 seconds
     * ({@link app.hushgram.extension.instagram.feed.HomePlace}). Off to start.
     */
    public static final BooleanSetting KEEP_HOME_PLACE =
            new BooleanSetting("hushgram_keep_home_position", FALSE);

    /**
     * A story plays again from the start when it ends, instead of the viewer moving on
     * ({@link app.hushgram.extension.instagram.stories.StoryLoop}). While it's on it wins over
     * {@link #BLOCK_STORY_AUTO_ADVANCE}. Off to start.
     */
    public static final BooleanSetting LOOP_STORIES =
            new BooleanSetting("hushgram_loop_stories", FALSE);

    /**
     * A story tapped in the stories row grows out of its ring on a slower spring with no bounce
     * ({@link app.hushgram.extension.instagram.stories.SmoothStoryOpen}). The story viewer builds
     * its spring once, so a change waits for a restart. Off to start.
     */
    public static final BooleanSetting SMOOTH_STORY_OPEN =
            new BooleanSetting("hushgram_smooth_story_open", FALSE, true);

    /**
     * Stories open with their sound on, through Instagram's own audio state, when the phone's ringer
     * is on and its volume is up ({@link app.hushgram.extension.instagram.stories.StorySound}). Off
     * to start.
     */
    public static final BooleanSetting START_STORIES_WITH_SOUND =
            new BooleanSetting("hushgram_start_stories_with_sound", FALSE);

    /**
     * A swipe up on someone's story leaves the reply box closed
     * ({@link app.hushgram.extension.instagram.stories.StorySwipeReply}). Read at each swipe, so a
     * change applies at once. Off to start.
     */
    public static final BooleanSetting STOP_STORY_SWIPE_REPLY =
            new BooleanSetting("hushgram_stop_story_swipe_reply", FALSE);

    /**
     * The font files picked in settings show up in the story text editor alongside Instagram's own fonts
     * ({@link app.hushgram.extension.instagram.stories.StoryFonts}). Read each time the editor lists
     * its fonts. Off to start, and turned on when a font is added.
     */
    public static final BooleanSetting STORY_FONTS =
            new BooleanSetting("hushgram_story_fonts", FALSE);

    /**
     * Emoji in story text draw from an emoji font file picked in settings
     * ({@link app.hushgram.extension.instagram.stories.StoryEmoji}). Read each time the story text
     * changes. Off to start, and turned on only once a picked file loads as a font.
     */
    public static final BooleanSetting STORY_EMOJI =
            new BooleanSetting("hushgram_story_emoji", FALSE);

    /**
     * A video story is prepared the way Instagram prepares it with its own "Upload at highest quality"
     * setting on, at 1080p and 9 Mbps ({@link app.hushgram.extension.instagram.stories.StoryUploadQuality}). The server still
     * keeps story videos at 720p.
     * Read as each upload is prepared, so a change applies to the next story posted. Off to start.
     */
    public static final BooleanSetting UPLOAD_STORIES_AT_FULL_QUALITY =
            new BooleanSetting("hushgram_upload_stories_at_full_quality", FALSE);

    /**
     * The first feed video of a Home session starts with its sound on, through the video's own sound
     * flag, when the phone's ringer is on and its volume is up
     * ({@link app.hushgram.extension.instagram.feed.FeedSound}). Off to start.
     */
    public static final BooleanSetting START_FEED_VIDEOS_WITH_SOUND =
            new BooleanSetting("hushgram_start_feed_videos_with_sound", FALSE);

    /**
     * A tap on the video playing in a feed pauses it in place of Instagram's own tap there, which
     * opens some videos in Reels. It also plays a paused video again when it still can, though the
     * feed can reclaim a paused video's player first
     * ({@link app.hushgram.extension.instagram.feed.FeedTapPause}). Off to start.
     */
    public static final BooleanSetting TAP_FEED_VIDEO_TO_PAUSE =
            new BooleanSetting("hushgram_tap_feed_video_to_pause", FALSE);

    /**
     * A seek bar along the bottom of the video playing in a feed, which moves the video to where a
     * drag lets go ({@link app.hushgram.extension.instagram.feed.FeedSeekBar}). Off to start.
     */
    public static final BooleanSetting FEED_SEEK_BAR =
            new BooleanSetting("hushgram_feed_seek_bar", FALSE);

    /**
     * A long press on a feed photo shows it zoomed in around the finger until the finger lifts, in
     * place of Instagram's own long press there ({@link app.hushgram.extension.instagram.feed.LongPressZoom}).
     * Off to start.
     */
    public static final BooleanSetting LONG_PRESS_TO_ZOOM =
            new BooleanSetting("hushgram_long_press_to_zoom", FALSE);

    /**
     * Under {@link #LONG_PRESS_TO_ZOOM}: the zoom stays up after the finger lifts, to drag and pinch,
     * until a tap or Back closes it (#149). Off to start, so a lift closes it.
     */
    public static final BooleanSetting LONG_PRESS_ZOOM_STAYS_OPEN =
            new BooleanSetting("hushgram_long_press_zoom_stays_open", FALSE, parent(LONG_PRESS_TO_ZOOM));

    /**
     * The picture, video and carousel pages of a post in Home and Following are cut at rounded corners
     * ({@link app.hushgram.extension.instagram.feed.RoundedCorners}). Stories, the profile grid, Explore
     * and the Reels tab stay as Instagram draws them. Off to start, so no frame is touched.
     */
    public static final BooleanSetting ROUNDED_POST_CORNERS =
            new BooleanSetting("hushgram_rounded_post_corners", FALSE);

    /**
     * A reel's video in the Reels tab and wherever a reel opens full screen is cut at rounded corners
     * ({@link app.hushgram.extension.instagram.feed.ReelCorners}, #362), with the seek bar, captions and
     * buttons left whole. Part of the Rounded post corners patch. Off to start.
     */
    public static final BooleanSetting ROUNDED_REEL_CORNERS =
            new BooleanSetting("hushgram_rounded_reel_corners", FALSE);

    /**
     * A story's photo or video, wherever stories open, is cut at rounded corners
     * ({@link app.hushgram.extension.instagram.feed.StoryCorners}, #363, #368), with the progress bar,
     * header and stickers left whole. Part of the Rounded post corners patch. Off to start.
     */
    public static final BooleanSetting ROUNDED_STORY_CORNERS =
            new BooleanSetting("hushgram_rounded_story_corners", FALSE);

    /**
     * Under {@link #ROUNDED_POST_CORNERS}, {@link #ROUNDED_REEL_CORNERS} and {@link #ROUNDED_STORY_CORNERS}:
     * how round the corners are. Small to start.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.feed.CornerSize> ROUNDED_CORNER_SIZE =
            new EnumSetting<>("hushgram_rounded_corner_size", app.hushgram.extension.instagram.feed.CornerSize.SMALL,
                    parentsAny(ROUNDED_POST_CORNERS, ROUNDED_REEL_CORNERS, ROUNDED_STORY_CORNERS));

    /**
     * A feed carousel's page sits inset with the next page's edge showing at the right
     * ({@link app.hushgram.extension.instagram.feed.CarouselPeek}, #361). Part of the Rounded post
     * corners patch. Off to start.
     */
    public static final BooleanSetting PEEK_CAROUSEL_PAGES =
            new BooleanSetting("hushgram_peek_carousel_pages", FALSE);

    /**
     * A back and a next arrow over each feed carousel ({@link app.hushgram.extension.instagram.feed.CarouselArrows},
     * #417). Off to start.
     */
    public static final BooleanSetting CAROUSEL_ARROWS =
            new BooleanSetting("hushgram_carousel_arrows", FALSE);

    /**
     * A feed carousel's page dots on a round pill over the bottom of its photo, in place of Instagram's own dots
     * under it ({@link app.hushgram.extension.instagram.feed.CarouselArrows}, #419). Off to start.
     */
    public static final BooleanSetting CAROUSEL_DOTS_PILL =
            new BooleanSetting("hushgram_carousel_dots_pill", FALSE);

    /**
     * A soft glow in a post's or a reel's own colors around it ({@link app.hushgram.extension.instagram.feed.AmbientLight},
     * #416). Only a stage's background is wrapped, so off or paused, every stage has its own back at once. Off to start.
     */
    public static final BooleanSetting AMBIENT_LIGHT =
            new BooleanSetting("hushgram_ambient_light", FALSE);

    /**
     * The same glow in the bands around a story that doesn't fill the screen (#420), part of the Ambient light patch.
     * Shares the spread and the speed. Off to start.
     */
    public static final BooleanSetting AMBIENT_LIGHT_STORIES =
            new BooleanSetting("hushgram_ambient_light_stories", FALSE);

    /** Under {@link #AMBIENT_LIGHT} or {@link #AMBIENT_LIGHT_STORIES}: how far the glow reaches. Medium to start. */
    public static final EnumSetting<app.hushgram.extension.instagram.feed.AmbientSpread> AMBIENT_LIGHT_SPREAD =
            new EnumSetting<>("hushgram_ambient_light_spread", app.hushgram.extension.instagram.feed.AmbientSpread.MEDIUM,
                    parentsAny(AMBIENT_LIGHT, AMBIENT_LIGHT_STORIES));

    /** Under {@link #AMBIENT_LIGHT} or {@link #AMBIENT_LIGHT_STORIES}: how quickly the glow follows a video. Medium to start. */
    public static final EnumSetting<app.hushgram.extension.instagram.feed.AmbientSpeed> AMBIENT_LIGHT_SPEED =
            new EnumSetting<>("hushgram_ambient_light_speed", app.hushgram.extension.instagram.feed.AmbientSpeed.MEDIUM,
                    parentsAny(AMBIENT_LIGHT, AMBIENT_LIGHT_STORIES));

    /**
     * The stories you watch, which Instagram posts to media/seen/ to put you on their viewer lists.
     * Held back, you stay off them. Replies and reactions still show you. Off to start.
     */
    public static final BooleanSetting VIEW_STORIES_ANONYMOUSLY =
            new BooleanSetting("hushgram_view_stories_anonymously", FALSE);

    /** A separate opt-in for the direct visual-media receipt. Ordinary chat receipts stay native. */
    public static final BooleanSetting VIEW_DM_MEDIA_ANONYMOUSLY =
            new BooleanSetting("hushgram_view_dm_media_anonymously", FALSE);

    /**
     * A fix from the phone answers {@link #SPOOF_LOCATION_PLACE} wherever Instagram reads its
     * latitude, longitude or distance ({@link app.hushgram.extension.instagram.misc.SpoofLocation}).
     * Read at each read. Off to start.
     */
    public static final BooleanSetting SPOOF_LOCATION =
            new BooleanSetting("hushgram_spoof_location", FALSE);

    /**
     * The place a fix answers, as "latitude, longitude" in degrees. Empty or unreadable answers 0, 0
     * while {@link #SPOOF_LOCATION} is on, never the phone's place. A value control, not a switch.
     */
    public static final StringSetting SPOOF_LOCATION_PLACE =
            new StringSetting("hushgram_spoof_location_place", "", parent(SPOOF_LOCATION));

    /**
     * Instagram's requests go through {@link #APP_PROXY_SERVER}, inside Instagram only
     * ({@link app.hushgram.extension.instagram.misc.AppProxy}). Its network stack reads the proxy
     * as it starts, so a change waits for a restart. Off to start.
     */
    public static final BooleanSetting APP_PROXY =
            new BooleanSetting("hushgram_app_proxy", FALSE, true);

    /**
     * The HTTP proxy server, as host:port. Empty sends Instagram out directly even while
     * {@link #APP_PROXY} is on. A value control, not a switch.
     */
    public static final StringSetting APP_PROXY_SERVER =
            new StringSetting("hushgram_app_proxy_server", "", true, parent(APP_PROXY));

    /**
     * The setup screens asking to sync contacts and turn on location services stay closed when the
     * server sends them at start (#295, #329, #421, {@link app.hushgram.extension.instagram.misc.SetupPopups}).
     * Read each time a screen opens. Off to start.
     */
    public static final BooleanSetting SKIP_SETUP_POPUPS =
            new BooleanSetting("hushgram_skip_setup_popups", FALSE);

    /**
     * Instagram's reminder to review whether it can process your data for ads stays closed when one
     * of its routes would bring it up by itself (#331, {@link app.hushgram.extension.instagram.misc.AdDataReminder}).
     * Read each time a route runs. Off to start.
     */
    public static final BooleanSetting SKIP_AD_DATA_REMINDER =
            new BooleanSetting("hushgram_skip_ad_data_reminder", FALSE);

    /**
     * The seen receipt a chat sends when you open it, the one that puts Seen under the other
     * person's message ({@link app.hushgram.extension.instagram.direct.ThreadSeen}). Read each time
     * Instagram goes to send one, so a change applies to the next receipt. Off to start.
     */
    public static final BooleanSetting READ_WITHOUT_SEEN_RECEIPT =
            new BooleanSetting("hushgram_read_without_seen_receipt", FALSE);

    /**
     * Under {@link #READ_WITHOUT_SEEN_RECEIPT}: a chat opened while its receipt is held stays unread
     * in your messages until Mark as read lets that receipt go (#184). Read each time Instagram
     * records a chat seen, so a change applies the next time a chat opens. On to start, so a held
     * chat doesn't read as seen on this phone either. Off, a chat opened while its receipt is held
     * stays read here until a newer message comes in, even after the inbox refreshes (#186).
     */
    public static final BooleanSetting KEEP_CHATS_UNREAD =
            new BooleanSetting("hushgram_keep_chats_unread", TRUE, parent(READ_WITHOUT_SEEN_RECEIPT));

    /**
     * Under {@link #READ_WITHOUT_SEEN_RECEIPT}: sending a message in a chat whose receipt was held
     * lets that receipt go, so the other person sees Seen when you answer (#365). Read each time a
     * message is sent, so a change applies to the next one. Off to start.
     */
    public static final BooleanSetting MARK_SEEN_ON_REPLY =
            new BooleanSetting("hushgram_mark_seen_on_reply", FALSE, parent(READ_WITHOUT_SEEN_RECEIPT));

    /**
     * The typing indicator a chat shows the other person while you write
     * ({@link app.hushgram.extension.instagram.direct.TypingStatus}). Read each time you start
     * typing, so a change applies the next time. Off to start.
     */
    public static final BooleanSetting HIDE_TYPING = new BooleanSetting("hushgram_hide_typing", FALSE);

    /**
     * The Active status Instagram's presence service is sent while the app is in front
     * ({@link app.hushgram.extension.instagram.direct.ActiveStatus}). Read each time Instagram
     * writes your presence, which it does as the app opens and as it comes back to the front, so a
     * change shows from the next one. Off to start.
     */
    public static final BooleanSetting HIDE_ACTIVE_STATUS = new BooleanSetting("hushgram_hide_active_status", FALSE);

    /**
     * The exact time someone was last active in place of Instagram's "Active 3h ago", under their
     * name in your messages and in a chat's header
     * ({@link app.hushgram.extension.instagram.direct.LastActive}). Read each time Instagram writes
     * that text. Off to start.
     */
    public static final BooleanSetting SHOW_LAST_ACTIVE = new BooleanSetting("hushgram_show_last_active", FALSE);

    /**
     * Your inbox and chats stay covered until the phone's lock says it's you, and message
     * notifications say only that a message came
     * ({@link app.hushgram.extension.instagram.direct.MessagesLock}). Off to start.
     */
    public static final BooleanSetting LOCK_MESSAGES = new BooleanSetting("hushgram_lock_messages", FALSE);

    /**
     * All of Instagram stays covered until the phone's lock says it's you, the messages too
     * ({@link app.hushgram.extension.instagram.direct.MessagesLock}). Off to start.
     */
    public static final BooleanSetting LOCK_APP = new BooleanSetting("hushgram_lock_app", FALSE);

    /**
     * How long after you leave Instagram the two locks above lock again. It starts at right away,
     * which is what they did before the choice. It isn't a switch: the two above are.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.direct.LockDelay> LOCK_AGAIN =
            new EnumSetting<>("hushgram_lock_again", app.hushgram.extension.instagram.direct.LockDelay.RIGHT_AWAY);

    /**
     * The chats that stay locked one at a time, each as the id of the account it was locked on, its
     * thread id and the name it had there ({@link app.hushgram.extension.instagram.direct.ChatLocks},
     * {@code ChatList}), a line for each. It isn't a switch, and a backup leaves it out, since the ids
     * are only good on this phone's accounts.
     */
    public static final StringSetting LOCKED_CHATS =
            new StringSetting("hushgram_locked_chats", "", false, false);

    /**
     * The chats hidden one at a time ({@link app.hushgram.extension.instagram.direct.HiddenChats}),
     * in the same lines as {@link #LOCKED_CHATS}. A chat on the list is the switch. It isn't kept
     * while HushGram is paused, so a paused Instagram shows every chat again, and a backup leaves it
     * out like the locked chats.
     */
    public static final StringSetting HIDDEN_CHATS =
            new StringSetting("hushgram_hidden_chats", "", false, false);

    /**
     * The chats pinned to the top of the Messages inbox on this phone
     * ({@link app.hushgram.extension.instagram.direct.PinnedChats}, #345), in the same lines as
     * {@link #HIDDEN_CHATS}. A chat on the list is the switch, nothing is kept while HushGram is
     * paused, and a backup leaves it out like the hidden chats.
     */
    public static final StringSetting PINNED_CHATS =
            new StringSetting("hushgram_pinned_chats", "", false, false);

    /**
     * Two quick taps on Messages' button, on the tab bar or at the top of Home, open the list of
     * hidden chats over Instagram, after the phone's lock while a messages lock is on
     * ({@link app.hushgram.extension.instagram.direct.HiddenChatsTap}, #358). Off to start.
     */
    public static final BooleanSetting DOUBLE_TAP_HIDDEN_CHATS =
            new BooleanSetting("hushgram_double_tap_hidden_chats", FALSE);

    /**
     * What unlocks the messages: the phone's lock, a HushGram PIN
     * ({@link app.hushgram.extension.instagram.direct.PinLock}), or on Android 11 and up the
     * fingerprint or face alone. It isn't a switch, and a backup leaves it out, since a restored
     * choice of PIN would have no PIN behind it.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.direct.UnlockWith> UNLOCK_WITH =
            new EnumSetting<>("hushgram_unlock_with", app.hushgram.extension.instagram.direct.UnlockWith.PHONE_LOCK,
                    false, false);

    /**
     * The HushGram PIN as a salted hash, never the digits
     * ({@link app.hushgram.extension.instagram.direct.PinLock}). Empty until a PIN is set. A backup
     * leaves it out: a PIN never leaves the phone.
     */
    public static final StringSetting LOCK_PIN = new StringSetting("hushgram_lock_pin", "", false, false);

    /** Wrong PINs in a row, kept so a restart doesn't reset the count. Left out of a backup. */
    public static final LongSetting LOCK_PIN_MISSES = new LongSetting("hushgram_lock_pin_misses", 0L, false, false);

    /** When the wait after too many wrong PINs ends, in wall clock milliseconds. Left out of a backup. */
    public static final LongSetting LOCK_PIN_WAIT_UNTIL = new LongSetting("hushgram_lock_pin_wait_until", 0L, false, false);

    static {
        // The locks keep answering what you chose while HushGram is paused or in safe mode, so
        // neither one, nor the marker file that pauses it from outside, gets around them.
        Setting.keepWhenPaused(LOCK_MESSAGES, LOCK_APP, LOCK_AGAIN, LOCKED_CHATS,
                UNLOCK_WITH, LOCK_PIN, LOCK_PIN_MISSES, LOCK_PIN_WAIT_UNTIL);
    }

    /**
     * Lock these settings (#301): the hours a change waits for, or 0 while the lock is off
     * ({@link SettingsLock}). A backup leaves it out, so a settings file can't take the lock off.
     */
    public static final IntegerSetting SETTINGS_LOCK_HOURS =
            new IntegerSetting("hushgram_settings_lock_hours", 0, false, false);

    /**
     * When Ask to unlock was tapped, as "time of day,time since the phone started,phone starts", or
     * empty while nobody asked ({@link SettingsLock}). One value, so a wait never starts half
     * written. Left out of a backup.
     */
    public static final StringSetting SETTINGS_LOCK_ASKED =
            new StringSetting("hushgram_settings_lock_asked", "", false, false);

    static {
        // Pause HushGram and safe mode keep the lock, or pausing would be the way around it.
        Setting.keepWhenPaused(SETTINGS_LOCK_HOURS, SETTINGS_LOCK_ASKED);
    }

    /**
     * Instagram doesn't notice your screenshots, so nobody's told you took one of a disappearing
     * photo or video ({@link app.hushgram.extension.instagram.direct.ScreenshotReports}). Off to start.
     */
    public static final BooleanSetting HIDE_SCREENSHOTS = new BooleanSetting("hushgram_hide_screenshots", FALSE);

    /**
     * Screenshots and screen recordings work wherever Instagram blocks them, like disappearing
     * photos and videos ({@link app.hushgram.extension.instagram.direct.ScreenshotBlock}). Off to start.
     */
    public static final BooleanSetting ALLOW_SCREENSHOTS = new BooleanSetting("hushgram_allow_screenshots", FALSE);

    /**
     * View once and replayable photos and videos stay in the chat like ones sent with Keep in chat
     * ({@link app.hushgram.extension.instagram.direct.KeepInChat}). Off to start.
     */
    public static final BooleanSetting KEEP_IN_CHAT = new BooleanSetting("hushgram_keep_in_chat", FALSE);

    /**
     * A view once photo or video in a chat gets a border and Instagram's own view once icon before
     * it's opened ({@link app.hushgram.extension.instagram.direct.ViewOnceMark}, #165). Only the
     * chat's drawing changes. Off to start.
     */
    public static final BooleanSetting MARK_VIEW_ONCE = new BooleanSetting("hushgram_mark_view_once", FALSE);

    /**
     * Swiping up on a reel opened from a chat goes to the next reel shared in that chat, by
     * Instagram's own chat reels test ({@link app.hushgram.extension.instagram.direct.ChatReels}, #350).
     * Off to start.
     */
    public static final BooleanSetting SWIPE_CHAT_REELS = new BooleanSetting("hushgram_swipe_chat_reels", FALSE);

    /**
     * Reels opened from a chat end after the chat's own shared reels, with no suggested reels after
     * them ({@link app.hushgram.extension.instagram.direct.ChatReels#stop()}, #390). Off to start.
     */
    public static final BooleanSetting STOP_AFTER_CHAT_REELS = new BooleanSetting("hushgram_stop_after_chat_reels", FALSE);

    /**
     * Long pressing a chat's name offers to jump to its first message or to a date, paging back
     * through the chat on its own ({@link app.hushgram.extension.instagram.direct.ChatPager}, #246).
     * Off to start.
     */
    public static final BooleanSetting JUMP_IN_CHAT = new BooleanSetting("hushgram_jump_in_chat", FALSE);

    /**
     * Long pressing a chat's name offers Export chat, which loads the whole chat and saves it as a
     * text file in Downloads, HushGram folder ({@link app.hushgram.extension.instagram.direct.ChatExport}, #247).
     * Off to start.
     */
    public static final BooleanSetting EXPORT_CHAT = new BooleanSetting("hushgram_export_chat", FALSE);

    /**
     * A call started from a chat waits for a question first
     * ({@link app.hushgram.extension.instagram.direct.CallConfirm}). Off to start.
     */
    public static final BooleanSetting ASK_BEFORE_CALL = new BooleanSetting("hushgram_ask_before_call", FALSE);

    /**
     * The buttons the message box of a chat leaves off, as the names of Instagram's button enum with
     * commas between ({@link app.hushgram.extension.instagram.direct.MessageBox}, #217): any of
     * GALLERY, STICKER and VOICE_MESSAGE. Nothing named is the start, so every button shows. The text
     * box and Send are never offered. Read each time Instagram shows or hides a button and when a
     * chat's message box is made, so a change shows the next time a chat opens.
     */
    public static final StringSetting HIDDEN_MESSAGE_BOX_BUTTONS =
            new StringSetting("hushgram_hidden_message_box_buttons", "");

    /**
     * The voice and video call buttons at the top of a chat are taken off the chat's header
     * ({@link app.hushgram.extension.instagram.direct.CallButtons}, #217). Off to start.
     */
    public static final BooleanSetting HIDE_CALL_BUTTONS = new BooleanSetting("hushgram_hide_call_buttons", FALSE);

    /**
     * The Like button under a post asks before it likes or unlikes the post
     * ({@link app.hushgram.extension.instagram.feed.LikeConfirm}). A double tap isn't asked about.
     * Off to start.
     */
    public static final BooleanSetting ASK_BEFORE_LIKE = new BooleanSetting("hushgram_ask_before_like", FALSE);

    /**
     * Pulling down to refresh a list asks before the list reloads
     * ({@link app.hushgram.extension.instagram.feed.RefreshConfirm}). Off to start.
     */
    public static final BooleanSetting ASK_BEFORE_REFRESH = new BooleanSetting("hushgram_ask_before_refresh", FALSE);

    /**
     * Saving a post or reel shows the Saved message instead of the tray that asks you to start a
     * collection, which Instagram shows some accounts on every save (piko #2210,
     * {@link app.hushgram.extension.instagram.feed.SaveTray}). Off to start.
     */
    public static final BooleanSetting SKIP_COLLECTIONS_TRAY =
            new BooleanSetting("hushgram_skip_collections_tray", FALSE);

    /**
     * The Mark as seen button in the story viewer's header
     * ({@link app.hushgram.extension.instagram.stories.StorySeenButton}). Off to start. A story you
     * tap it on is sent as seen while the rest stay held back. Read each time a story is shown and
     * each time a batch of views goes to be sent.
     */
    public static final BooleanSetting MARK_STORIES_SEEN =
            new BooleanSetting("hushgram_mark_stories_seen", FALSE);

    /**
     * Stories you watch while views are held back still turn gray on this phone
     * ({@link app.hushgram.extension.instagram.stories.StorySeenRings}). Off to start, so a watched
     * story keeps its colored ring and its place (#92). On, Instagram writes the story's seen time
     * on the phone as it would, and the view itself stays held back (#113). Read each time a story
     * is watched.
     */
    public static final BooleanSetting GRAY_OUT_WATCHED_STORIES =
            new BooleanSetting("hushgram_gray_out_watched_stories", FALSE);

    /**
     * Lives you watch don't list you as a viewer
     * ({@link app.hushgram.extension.instagram.stories.LiveSeen}). Off to start. Read before each
     * heartbeat a live you watch would send.
     */
    public static final BooleanSetting VIEW_LIVE_ANONYMOUSLY = new BooleanSetting("hushgram_view_live_anonymously", FALSE);

    /**
     * The Mark as seen button in the live viewer's header
     * ({@link app.hushgram.extension.instagram.stories.LiveSeenButton}, #412). Off to start. A live
     * you tap it on lists you as a viewer while the rest stay hidden. Read each time a live opens.
     */
    public static final BooleanSetting MARK_LIVE_SEEN = new BooleanSetting("hushgram_mark_live_seen", FALSE);

    /**
     * The rows of suggested reels between posts in the home feed, and the other feed units that
     * open the Reels viewer. A reel someone you follow posts is a post and stays. Off to start.
     */
    public static final BooleanSetting HIDE_FEED_REELS =
            new BooleanSetting("hushgram_hide_feed_reels", FALSE);

    /**
     * The rows in the home feed of accounts, shops and hashtags Instagram suggests you follow, and
     * its other units of suggestions.
     */
    public static final BooleanSetting HIDE_SUGGESTED_ACCOUNTS =
            new BooleanSetting("hushgram_hide_suggested_accounts", TRUE);

    /**
     * The single posts and reels in the home feed from accounts you don't follow, which Instagram
     * labels "Suggested for you" or "Suggested Reel".
     */
    public static final BooleanSetting HIDE_SUGGESTED_POSTS =
            new BooleanSetting("hushgram_hide_suggested_posts", TRUE);

    /**
     * Threads' units in the home feed: its posts, and the accounts, communities, live chats and game
     * threads it suggests.
     */
    public static final BooleanSetting HIDE_THREADS_POSTS =
            new BooleanSetting("hushgram_hide_threads_posts", TRUE);

    /** The surveys in the home feed that ask you to rate what you saw. */
    public static final BooleanSetting HIDE_FEED_SURVEYS =
            new BooleanSetting("hushgram_hide_feed_surveys", TRUE);

    /**
     * The "Are you interested in this post?" bubble with Not interested and Interested buttons that
     * Instagram adds under a post in Home's feed and under a reel (#131). Left out, never answered.
     * Off to start.
     */
    public static final BooleanSetting HIDE_FEED_INTEREST =
            new BooleanSetting("hushgram_hide_feed_interest", FALSE);

    /** The rows of products and live shopping in the home feed. */
    public static final BooleanSetting HIDE_FEED_SHOPPING =
            new BooleanSetting("hushgram_hide_feed_shopping", TRUE);

    /**
     * The "You've seen the latest posts from accounts you follow" card Home puts after the followed
     * posts, folded away with its Older posts link (#307). A card at the top of Home, when there are
     * no new posts at all, stays. Off to start.
     */
    public static final BooleanSetting HIDE_CAUGHT_UP_CARD =
            new BooleanSetting("hushgram_hide_caught_up_card", FALSE);

    /** Posts in Home that are one video, reels among them. Off until enabled. */
    public static final BooleanSetting HIDE_FEED_VIDEOS =
            new BooleanSetting("hushgram_hide_feed_videos", FALSE);

    /** Posts in Home that are one photo. Off until enabled. */
    public static final BooleanSetting HIDE_FEED_PHOTOS =
            new BooleanSetting("hushgram_hide_feed_photos", FALSE);

    /** Posts in Home with more than one photo or video. Off until enabled. */
    public static final BooleanSetting HIDE_FEED_CAROUSELS =
            new BooleanSetting("hushgram_hide_feed_carousels", FALSE);

    /** Posts in Home you've already liked, by the post's has_liked. Off until enabled. */
    public static final BooleanSetting HIDE_FEED_LIKED =
            new BooleanSetting("hushgram_hide_feed_liked", FALSE);

    /**
     * The accounts whose posts Home leaves out ({@link app.hushgram.extension.instagram.feed.HiddenAccounts}),
     * a line for each: the id of the signed-in account it was hidden for, a tab, and the username.
     * Empty to start. A name on the list is the switch. It reads empty while HushGram is paused, and
     * a backup leaves it out, since the ids are only good on this phone's accounts.
     */
    public static final StringSetting HIDDEN_ACCOUNTS =
            new StringSetting("hushgram_hidden_accounts", "", false, false);

    /**
     * The id of the account Home was last set up for, so a restart that opens settings before Home
     * still shows that account's hidden accounts. Not a switch, and left out of a backup.
     */
    public static final StringSetting FEED_ACCOUNT =
            new StringSetting("hushgram_feed_account", "", false, false);

    /**
     * Every post in Home's feed, on purpose, leaving the stories row
     * ({@link app.hushgram.extension.instagram.feed.HomeFeed}). Read as each page arrives, so a
     * change shows on the next pull to refresh. Off to start.
     */
    public static final BooleanSetting HIDE_HOME_FEED =
            new BooleanSetting("hushgram_hide_home_feed", FALSE);

    /** Stories in the tray at the top of Home from accounts you don't follow, and accounts it suggests. */
    public static final BooleanSetting HIDE_SUGGESTED_STORIES =
            new BooleanSetting("hushgram_hide_suggested_stories", TRUE);

    /** Rewind cards in the stories tray at the top of Home, which bring back old highlights. Off to start. */
    public static final BooleanSetting HIDE_STORY_REWINDS =
            new BooleanSetting("hushgram_hide_story_rewinds", FALSE);

    /**
     * The memories, recaps, follow anniversaries and birthday cards Instagram makes for the stories
     * tray at the top of Home. Off to start.
     */
    public static final BooleanSetting HIDE_STORY_RECAPS =
            new BooleanSetting("hushgram_hide_story_recaps", FALSE);

    /**
     * Nothing in the stories tray at the top of Home loads: its items and the reels it fetches after
     * them are dropped as the tray's response is read. Off to start.
     */
    public static final BooleanSetting STOP_LOADING_STORIES =
            new BooleanSetting("hushgram_stop_loading_stories", FALSE);

    /** The whole row of stories at the top of Home, Your story included. Off until you turn it on. */
    public static final BooleanSetting HIDE_STORIES_TRAY =
            new BooleanSetting("hushgram_hide_stories_tray", FALSE);

    /**
     * The row of stories stays in the feed instead of floating over it when you come back to Home
     * mid-feed ({@link app.hushgram.extension.instagram.stories.StoriesTray#keepTrayOutOfOverlay}).
     * Off to start.
     */
    public static final BooleanSetting KEEP_TRAY_IN_FEED =
            new BooleanSetting("hushgram_keep_tray_in_feed", FALSE);

    /**
     * The Music for you card Instagram puts between stories in the viewer
     * ({@link app.hushgram.extension.instagram.stories.StoriesTray#musicCard}). Off to start.
     */
    public static final BooleanSetting HIDE_MUSIC_CARD =
            new BooleanSetting("hushgram_hide_music_card", FALSE);

    /**
     * The Suggested for you cards of accounts to follow between the stories you tap through: the
     * netegos {@link app.hushgram.extension.instagram.stories.StoriesTray#netego} leaves out and the
     * empty story state reel {@link app.hushgram.extension.instagram.stories.StoriesTray#filter}
     * drops. Off to start.
     */
    public static final BooleanSetting HIDE_STORY_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_story_suggestions", FALSE);

    /**
     * The Add Yours cards between the stories you tap through, Popular Add Yours and Join a
     * trending Add Yours among them: the midcards
     * {@link app.hushgram.extension.instagram.stories.StoriesTray#midcard} and the netegos
     * {@link app.hushgram.extension.instagram.stories.StoriesTray#netego} leave out. Off to start.
     */
    public static final BooleanSetting HIDE_ADD_YOURS_CARDS =
            new BooleanSetting("hushgram_hide_add_yours_cards", FALSE);

    /**
     * The row of stories at the top of Home keeps only the stories {@link #TRAY_BADGE} names, by
     * the owner's verified badge ({@link app.hushgram.extension.instagram.stories.StoriesTray#filter},
     * #340). Your own story stays. Off to start, which is every story.
     */
    public static final BooleanSetting FILTER_TRAY_BY_BADGE =
            new BooleanSetting("hushgram_filter_tray_by_badge", FALSE);

    /** Under {@link #FILTER_TRAY_BY_BADGE}: which stories stay. Accounts without the badge to start. */
    public static final EnumSetting<TrayBadge> TRAY_BADGE =
            new EnumSetting<>("hushgram_tray_badge", TrayBadge.UNVERIFIED, parent(FILTER_TRAY_BY_BADGE));

    /**
     * The rings in the stories row at the top of Home are drawn at the size in
     * {@link #STORY_RING_SCALE}, and the ring of a story that hasn't been watched in the color in
     * {@link #STORY_RING_COLOR} ({@link app.hushgram.extension.instagram.stories.StoryRing}). The
     * switch starts on, and both choices start as Instagram's own, so nothing changes until one is
     * picked. Nothing Instagram stores is written, so off or paused, the rings are Instagram's
     * again once Home is built anew.
     */
    public static final BooleanSetting STORY_RING =
            new BooleanSetting("hushgram_story_ring", TRUE, true);

    /**
     * The size the rings are drawn at while {@link #STORY_RING} is on, as a share of Instagram's.
     * It starts as Instagram's own, so picking the patch changes nothing until a size is chosen. It
     * isn't a switch: the switch above it is.
     */
    public static final EnumSetting<StoryRingSize> STORY_RING_SCALE =
            new EnumSetting<>("hushgram_story_ring_size", StoryRingSize.INSTAGRAM, true, parent(STORY_RING));

    /**
     * The color the ring of a story that hasn't been watched is drawn in while {@link #STORY_RING}
     * is on. It starts as Instagram's own gradient, and it is a choice of its own: the size above
     * doesn't change it, and it doesn't change the size.
     */
    public static final EnumSetting<StoryRingColor> STORY_RING_COLOR =
            new EnumSetting<>("hushgram_story_ring_color", StoryRingColor.INSTAGRAM, true, parent(STORY_RING));

    /**
     * The progress bar along the top of a story is drawn {@link #STORY_BAR_THICKNESS} times as thick
     * ({@link app.hushgram.extension.instagram.stories.StoryProgressBar}, #360). Nothing Instagram
     * stores is written, so off or paused, the bar is Instagram's again at once. Off to start.
     */
    public static final BooleanSetting THICKER_STORY_BAR =
            new BooleanSetting("hushgram_thicker_story_bar", FALSE);

    /** Under {@link #THICKER_STORY_BAR}: how much thicker the bar is. Twice to start. */
    public static final EnumSetting<StoryBarThickness> STORY_BAR_THICKNESS =
            new EnumSetting<>("hushgram_story_bar_thickness", StoryBarThickness.TWICE, parent(THICKER_STORY_BAR));

    /**
     * A seek bar over the bottom of a video story, which moves the video when dragged
     * ({@link app.hushgram.extension.instagram.stories.StorySeekBar}, #355). Nothing Instagram stores
     * is written, so off or paused, the bar hides at once. Off to start.
     */
    public static final BooleanSetting STORY_SEEK_BAR =
            new BooleanSetting("hushgram_story_seek_bar", FALSE);

    /**
     * Home opening on the Following feed, with Instagram's For you and Following picker at its top,
     * while you haven't picked a feed there. Instagram remembers a pick from then on. Off to start.
     */
    public static final BooleanSetting START_ON_FOLLOWING =
            new BooleanSetting("hushgram_start_on_following", FALSE, true);

    /**
     * With {@link #START_ON_FOLLOWING} on, Home keeps to accounts you follow: For you leaves the
     * feed picker, and a remembered For you opens Following. Off to start, so For you stays a tap
     * away until you choose this.
     */
    public static final BooleanSetting ONLY_FOLLOWING =
            new BooleanSetting("hushgram_only_following", FALSE, true, parent(START_ON_FOLLOWING));

    /**
     * Instagram's logo stays at the top of Home where the picked feed's name (Following, or Home once
     * you scroll down) would show, with or without {@link #START_ON_FOLLOWING}. Its arrow still opens
     * the feed picker. Off to start, so the name shows until you choose this.
     */
    public static final BooleanSetting LOGO_ON_FOLLOWING =
            new BooleanSetting("hushgram_logo_on_following", FALSE, true);

    /**
     * An account whose For you brings page after page of nothing but suggested posts opens Home on
     * Following, while no feed is picked for it (#308, {@link app.hushgram.extension.instagram.feed.ForYouFallback}).
     * Works without Start Home on Following. Off to start.
     */
    public static final BooleanSetting FOLLOWING_FALLBACK =
            new BooleanSetting("hushgram_following_fallback", FALSE, true);

    /**
     * The ids of the accounts {@link #FOLLOWING_FALLBACK} moved to Following, a line each. Not a
     * switch, and left out of a backup, since the ids are only good on this phone's accounts.
     */
    public static final StringSetting FOLLOWING_FALLBACK_ACCOUNTS =
            new StringSetting("hushgram_following_fallback_accounts", "", false, false);

    /**
     * Meta AI in the search bars: the Search tab's ("Search with Meta AI") and the one at the top of
     * your messages ("Search or ask Meta AI"), and the "Ask a follow-up…" bar under search results.
     */
    public static final BooleanSetting HIDE_META_AI_SEARCH =
            new BooleanSetting("hushgram_hide_meta_ai_search", TRUE, true);

    /** Meta AI's units in the home feed: Vibes videos, Meta AI chats and Imagine pictures. */
    public static final BooleanSetting HIDE_META_AI_POSTS =
            new BooleanSetting("hushgram_hide_meta_ai_posts", TRUE);

    /**
     * About this reel at the top of a reel's More menu, in Reels and in the feed: the generated
     * summary, its Sources and the Ask Meta AI box. The menu's other options stay. Off to start.
     */
    public static final BooleanSetting HIDE_ABOUT_THIS_REEL =
            new BooleanSetting("hushgram_hide_about_this_reel", FALSE);

    /** Only the Ask Meta AI box under About this reel's summary. Off to start. */
    public static final BooleanSetting HIDE_ASK_META_AI =
            new BooleanSetting("hushgram_hide_ask_meta_ai", FALSE);

    /**
     * Meta AI's target ("hatch", shown as Muse on some accounts) in the row at the bottom of the
     * share sheet. The row is built each time the sheet opens. Off to start.
     */
    public static final BooleanSetting HIDE_META_AI_SHARE_TARGET =
            new BooleanSetting("hushgram_hide_meta_ai_share_target", FALSE);

    /**
     * The Meet Muse card (Try Muse button) among a profile's banners, built from the profile's data
     * each time the profile or Edit featured opens, and the same card as a quick promotion at the
     * top of Home's feed or your profile. Off to start, like every part added to Hide
     * Meta AI after its first two, so an update never takes the card away unasked.
     */
    public static final BooleanSetting HIDE_META_AI_PROFILE_BANNER =
            new BooleanSetting("hushgram_hide_meta_ai_profile_banner", FALSE);

    /**
     * The Muse button (the blue m) in your own profile's top bar, left out each time the bar is
     * built. Your username, the menu and the bar's other buttons stay. Off to start, like every part
     * added to Hide Meta AI after its first two.
     */
    public static final BooleanSetting HIDE_META_AI_PROFILE_BUTTON =
            new BooleanSetting("hushgram_hide_meta_ai_profile_button", FALSE);

    /**
     * The Blend invite button on a reel, in the Reels viewer and the clips viewer. Everything else
     * on the reel stays. Off to start, since Blend isn't Meta AI and the other switches here don't
     * ask for it.
     */
    public static final BooleanSetting HIDE_META_AI_BLEND =
            new BooleanSetting("hushgram_hide_meta_ai_blend", FALSE);

    /** The grid of posts and reels under the Search tab's bar. Search and its results stay. Off to start. */
    public static final BooleanSetting HIDE_EXPLORE_GRID =
            new BooleanSetting("hushgram_hide_explore_grid", FALSE);

    /**
     * The Suggested for you accounts {@link app.hushgram.extension.instagram.explore.SearchSuggestions}
     * leaves out of what shows when you tap the search bar. Recent searches stay. Off to start.
     */
    public static final BooleanSetting HIDE_SEARCH_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_search_suggestions", FALSE);

    /**
     * What you open from search stays out of Recent, in the app's cache and on Instagram's side
     * ({@link app.hushgram.extension.instagram.explore.RecentSearches}). Read at each save. Off to start.
     */
    public static final BooleanSetting DONT_SAVE_RECENT_SEARCHES =
            new BooleanSetting("hushgram_dont_save_recent_searches", FALSE);

    /**
     * Search's empty screen lists every recent search instead of the first few, with See all still
     * there ({@link app.hushgram.extension.instagram.explore.RecentSearches#shown}). Read each time
     * the screen is built. Off to start.
     */
    public static final BooleanSetting SHOW_ALL_RECENT_SEARCHES =
            new BooleanSetting("hushgram_show_all_recent_searches", FALSE);

    /**
     * Where Instagram offers a grid that isn't personalized (the EU), Explore opens with it each
     * time and the ten-minute switch back is skipped
     * ({@link app.hushgram.extension.instagram.explore.ExploreMenu}). Read each time Explore
     * resumes. Off to start.
     */
    public static final BooleanSetting EXPLORE_WITHOUT_PERSONALIZATION =
            new BooleanSetting("hushgram_explore_without_personalization", FALSE);

    /**
     * The menu button on Explore's top bar that picks the personalized or the other grid, where
     * Instagram shows one (the EU). Read as the bar is built. Off to start.
     */
    public static final BooleanSetting HIDE_EXPLORE_MENU =
            new BooleanSetting("hushgram_hide_explore_menu", FALSE);

    /**
     * The row of notes at the top of your messages, its Map bubble included
     * ({@link app.hushgram.extension.instagram.direct.NotesRow}). Read each time Instagram works out
     * your messages again, so a change shows the next time they update. Off to start.
     */
    public static final BooleanSetting HIDE_NOTES_ROW =
            new BooleanSetting("hushgram_hide_notes_row", FALSE);

    /**
     * The search bar at the top of your messages, the Meta AI one included
     * ({@link app.hushgram.extension.instagram.direct.InboxSearchBar}). Read each time Instagram
     * works out your messages again, so a change shows the next time they update. Off to start.
     */
    public static final BooleanSetting HIDE_INBOX_SEARCH =
            new BooleanSetting("hushgram_hide_inbox_search", FALSE);

    /**
     * The Accounts to follow section under your chats
     * ({@link app.hushgram.extension.instagram.direct.InboxSuggestions}). Read each time your
     * messages build that section. Its row tells you to restart Instagram to see the change, so
     * the restart card comes up too. Off to start.
     */
    public static final BooleanSetting HIDE_INBOX_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_inbox_suggestions", FALSE, true);

    /**
     * Instants, Instagram's no-edit camera for friends, everywhere Instagram offers it
     * ({@link app.hushgram.extension.instagram.direct.Instants}). Instagram settles what it shows when
     * it starts, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_INSTANTS =
            new BooleanSetting("hushgram_hide_instants", FALSE, true);

    /**
     * The New group button beside the share sheet's search bar, whichever form Instagram gives it,
     * and the button that sends to the people you picked there as a group. Off to start.
     */
    public static final BooleanSetting HIDE_SHARE_SHEET_GROUP =
            new BooleanSetting("hushgram_hide_share_sheet_group", FALSE);

    /**
     * The buttons the share sheet leaves out of its row of round buttons under the people, by the
     * name the sheet gives each, comma separated ({@link app.hushgram.extension.instagram.share.ShareSheetActions},
     * #252). Empty hides none, so every button shows to start.
     */
    public static final StringSetting HIDDEN_SHARE_SHEET_BUTTONS =
            new StringSetting("hushgram_hidden_share_sheet_buttons", "");

    /**
     * The Repost button under posts and beside reels, with its count: every post and reel reads as
     * one that can't be reposted ({@link app.hushgram.extension.instagram.share.RepostButton}).
     * Nothing Instagram stores is written, so off or paused, Repost is back on the next post drawn.
     * Off to start.
     */
    public static final BooleanSetting HIDE_REPOST_BUTTON =
            new BooleanSetting("hushgram_hide_repost_button", FALSE);

    /**
     * Feed's action rows and the reels leave out the Share button and its count
     * ({@link app.hushgram.extension.instagram.share.ShareButton}). Read as each row's state is
     * built and as each reel is drawn, so a post or reel already on screen changes the next time
     * it's drawn. Off to start.
     */
    public static final BooleanSetting HIDE_SHARE_BUTTON =
            new BooleanSetting("hushgram_hide_share_button", FALSE);

    /**
     * The space Instagram leaves under its tab bar for a navigation bar the phone says isn't there:
     * when the phone reports no bottom inset, Instagram's guess from the system's navigation bar
     * height becomes 0 ({@link app.hushgram.extension.instagram.misc.BottomSpace}). Read each time
     * Instagram lays out its window's insets. Off to start.
     */
    public static final BooleanSetting REMOVE_BOTTOM_SPACE =
            new BooleanSetting("hushgram_remove_bottom_space", FALSE, true);

    /**
     * Instagram's tab bar is drawn as a floating, rounded glass pill
     * ({@link app.hushgram.extension.instagram.misc.GlassTabBar}). Instagram builds the bar as it
     * starts, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting GLASS_TAB_BAR =
            new BooleanSetting("hushgram_glass_tab_bar", FALSE, true);

    /**
     * The status bar and the navigation bar hide while Reels shows, in the Reels tab and in the full
     * screen viewer a reel opens from anywhere
     * ({@link app.hushgram.extension.instagram.reels.FullScreenBars}). A swipe from the edge brings them
     * back for a moment. Off to start.
     */
    public static final BooleanSetting FULL_SCREEN_REELS =
            new BooleanSetting("hushgram_full_screen_reels", FALSE);

    /**
     * Under {@link #FULL_SCREEN_REELS}: a reel whose shape is close to the screen's fills it, cut a
     * little at the sides, instead of fitting with black bars above and below (#288,
     * {@link app.hushgram.extension.instagram.reels.ReelFill}). Off to start.
     */
    public static final BooleanSetting FILL_REEL_SCREEN =
            new BooleanSetting("hushgram_fill_reel_screen", FALSE, parent(FULL_SCREEN_REELS));

    /**
     * Every reel fits inside the viewer, with bars where its shape differs from the screen's, so
     * nothing is cut at the sides (piko #2183,
     * {@link app.hushgram.extension.instagram.reels.ReelFill#fit}). Works without
     * {@link #FULL_SCREEN_REELS} and wins over {@link #FILL_REEL_SCREEN}. Off to start.
     */
    public static final BooleanSetting FIT_REEL_SCREEN =
            new BooleanSetting("hushgram_fit_reel_screen", FALSE);

    /** The same on Home, while its tab is selected and Reels isn't showing. Off to start. */
    public static final BooleanSetting FULL_SCREEN_HOME =
            new BooleanSetting("hushgram_full_screen_home", FALSE);

    /** The same on your messages and in a chat (#288). Off to start. */
    public static final BooleanSetting FULL_SCREEN_MESSAGES =
            new BooleanSetting("hushgram_full_screen_messages", FALSE);

    /**
     * Which bars the three switches above hide (#231): both to start, or only the status bar or only the
     * navigation bar ({@link app.hushgram.extension.instagram.reels.HiddenBars}). Read each time a
     * screen hides them.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.reels.HiddenBars> FULL_SCREEN_HIDES =
            new EnumSetting<>("hushgram_full_screen_hides",
                    app.hushgram.extension.instagram.reels.HiddenBars.BOTH,
                    parentsAny(FULL_SCREEN_REELS, FULL_SCREEN_HOME, FULL_SCREEN_MESSAGES));

    /**
     * Under {@link #GLASS_TAB_BAR}: the pill blurs what's behind it, on Android 12 and newer. Off, or
     * on an older Android, it's a frosted tint without the blur.
     */
    public static final BooleanSetting GLASS_TAB_BAR_BLUR =
            new BooleanSetting("hushgram_glass_tab_bar_blur", TRUE, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR_BLUR}: how far the pill blurs what's behind it, in dp, from a slider
     * (#289, #391). Starts on the blur the bar has always had. Read as each frame records the backdrop,
     * so a change shows on the bar without a restart.
     */
    public static final IntegerSetting GLASS_TAB_BAR_BLUR_RADIUS =
            new IntegerSetting("hushgram_glass_tab_bar_blur_radius",
                    app.hushgram.extension.instagram.misc.GlassTabBar.BLUR_DP, false, parent(GLASS_TAB_BAR_BLUR))
                    .withRange(2, 48);

    /**
     * Under {@link #GLASS_TAB_BAR}: Home and the other tabs run down behind the bar, so the glass has
     * the real content to blur and no strip of Instagram's own colour sits behind the pill. Reels keeps
     * its own controls above the pill. Off, the content stops above the bar.
     */
    public static final BooleanSetting GLASS_TAB_BAR_FLOAT =
            new BooleanSetting("hushgram_glass_tab_bar_float", TRUE, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR_FLOAT}: the Reels tab's video runs full screen behind the pill, like Home,
     * with the caption, buttons and seek bar raised clear of it (#372). Off, the reel stops above the pill's
     * slot. Read each frame, so a change shows without a restart.
     */
    public static final BooleanSetting GLASS_TAB_BAR_REELS_BEHIND =
            new BooleanSetting("hushgram_glass_tab_bar_reels_behind", FALSE, false, parent(GLASS_TAB_BAR_FLOAT));

    /**
     * Under {@link #GLASS_TAB_BAR}: a light tick each time the highlight slides across a tab, so the
     * buzz follows the bar as it moves, whether you tapped a tab or swiped to another screen.
     */
    public static final BooleanSetting GLASS_TAB_BAR_HAPTICS =
            new BooleanSetting("hushgram_glass_tab_bar_haptics", TRUE, true, parent(GLASS_TAB_BAR));

    /**
     * Scrolling a list down slides the tab bar off the bottom of the screen, and scrolling up, reaching the
     * top, changing tab or pressing Back brings it back. The glass pill
     * ({@link app.hushgram.extension.instagram.misc.GlassTabBar}) or, with {@link #GLASS_TAB_BAR} off,
     * Instagram's own bar, whose room the screens take while it's away (#343), so it isn't under the glass
     * switch. Off to start.
     */
    public static final BooleanSetting GLASS_TAB_BAR_HIDE_ON_SCROLL =
            new BooleanSetting("hushgram_glass_tab_bar_hide_on_scroll", FALSE, true);

    /**
     * A finger put on the tab bar and slid sideways carries a highlight, and lifting it opens the tab under
     * it ({@link app.hushgram.extension.instagram.misc.GlassTabSlide}, #136). On the glass pill or, with
     * {@link #GLASS_TAB_BAR} off, on Instagram's own bar (#201), so it isn't under the glass switch. Taps
     * and long presses on a tab stay Instagram's. Read as the bar is built, so a change asks for a restart.
     * Off to start.
     */
    public static final BooleanSetting GLASS_TAB_BAR_SLIDE =
            new BooleanSetting("hushgram_glass_tab_bar_slide", FALSE, true);

    /**
     * Under {@link #GLASS_TAB_BAR}: the liquid glass look (#65). The pill and its highlight are fully round,
     * Search sits in a glass circle of its own beside the pill, and with {@link #GLASS_TAB_BAR_HIDE_ON_SCROLL}
     * off, scrolling a list down shrinks the bar to the tab you're on and Search
     * ({@link app.hushgram.extension.instagram.misc.GlassTabBar}). Read as the bar is built, so a change asks
     * for a restart. Off to start.
     */
    public static final BooleanSetting GLASS_TAB_BAR_LIQUID =
            new BooleanSetting("hushgram_glass_tab_bar_liquid", FALSE, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR_LIQUID}: scrolling a list down leaves the liquid pill full size rather than
     * shrinking it to the tab you're on and Search (#256). Hide the tab bar as you scroll and the Reels wait
     * still work as set. Read as the bar is built, so a change asks for a restart. Off to start.
     */
    public static final BooleanSetting GLASS_TAB_BAR_KEEP_SIZE =
            new BooleanSetting("hushgram_glass_tab_bar_keep_size", FALSE, true, parent(GLASS_TAB_BAR_LIQUID));

    /**
     * Under {@link #GLASS_TAB_BAR_LIQUID}: the liquid pill's rim bends what's behind it the way the edge of real
     * glass does, so what passes under the edge shows mirrored and upside down (#369). Android 13 and newer,
     * where the bar can run a shader. Read as the bar is built, so a change asks for a restart. Off to start.
     */
    public static final BooleanSetting GLASS_TAB_BAR_REFRACT =
            new BooleanSetting("hushgram_glass_tab_bar_refract", FALSE, true, parent(GLASS_TAB_BAR_LIQUID));

    /**
     * Under {@link #GLASS_TAB_BAR_HAPTICS}: which tick plays ({@link app.hushgram.extension.instagram.misc.HapticStyle}).
     * Starts on the phone's own click. A choice because only a hand can say which feels right.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.HapticStyle> GLASS_TAB_BAR_HAPTIC_STYLE =
            new EnumSetting<>("hushgram_glass_tab_bar_haptic_style",
                    app.hushgram.extension.instagram.misc.HapticStyle.SYSTEM, parent(GLASS_TAB_BAR_HAPTICS));

    /**
     * Under {@link #GLASS_TAB_BAR}: how strong the pill's tint is, in percent of the look the bar has always
     * had, from a slider (#391). Lower is clearer, higher more frosted. Read as the pill draws, so a change
     * shows on the bar without a restart.
     */
    public static final IntegerSetting GLASS_TAB_BAR_OPACITY =
            new IntegerSetting("hushgram_glass_tab_bar_opacity_percent", 100, false, parent(GLASS_TAB_BAR))
                    .withRange(0, 150);

    /** Where the steps the blur and opacity had before their sliders were saved (#391). */
    static final String OLD_GLASS_BLUR_KEY = "hushgram_glass_tab_bar_blur_strength";
    static final String OLD_GLASS_OPACITY_KEY = "hushgram_glass_tab_bar_opacity";

    static {
        carryOverGlassSteps();
    }

    /**
     * A step an older build saved becomes the value it drew, once, unless the slider already moved, and
     * its key goes. A second process can't save, so it leaves the step for the main one.
     */
    static void carryOverGlassSteps() {
        String blur = Setting.preferences.getString(OLD_GLASS_BLUR_KEY, "");
        Integer blurDp = app.hushgram.extension.instagram.misc.GlassBlur.dpOf(blur);
        if (!blur.isEmpty() && (blurDp == null || carryOver(GLASS_TAB_BAR_BLUR_RADIUS, blurDp))) {
            Setting.preferences.removeKey(OLD_GLASS_BLUR_KEY);
        }
        String opacity = Setting.preferences.getString(OLD_GLASS_OPACITY_KEY, "");
        Integer percent = app.hushgram.extension.instagram.misc.GlassOpacity.percentOf(opacity);
        if (!opacity.isEmpty() && (percent == null || carryOver(GLASS_TAB_BAR_OPACITY, percent))) {
            Setting.preferences.removeKey(OLD_GLASS_OPACITY_KEY);
        }
    }

    private static boolean carryOver(IntegerSetting slider, int value) {
        if (!slider.savedValue().equals(slider.defaultValue)) return true;
        return slider.save(value);
    }

    /**
     * Under {@link #GLASS_TAB_BAR}: how tall the pill is ({@link app.hushgram.extension.instagram.misc.GlassHeight}).
     * Starts on the height the bar has always had. A change shows after a restart, so it asks for one.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.GlassHeight> GLASS_TAB_BAR_HEIGHT =
            new EnumSetting<>("hushgram_glass_tab_bar_height",
                    app.hushgram.extension.instagram.misc.GlassHeight.STANDARD, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR}: how round the pill is ({@link app.hushgram.extension.instagram.misc.GlassCorners},
     * #144). Starts on the corners the bar has always had. {@link #GLASS_TAB_BAR_LIQUID} is fully round whatever
     * is picked, so the choice rests while that's on. A change shows after a restart, so it asks for one.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.GlassCorners> GLASS_TAB_BAR_CORNERS =
            new EnumSetting<>("hushgram_glass_tab_bar_corners",
                    app.hushgram.extension.instagram.misc.GlassCorners.STANDARD, true, new Setting.Availability() {
                        @Override public boolean isAvailable() {
                            return GLASS_TAB_BAR.savedValue() && !GLASS_TAB_BAR_LIQUID.savedValue();
                        }

                        @Override public List<Setting<?>> getParentSettings() {
                            return Collections.singletonList(GLASS_TAB_BAR);
                        }
                    });

    /**
     * Under {@link #GLASS_TAB_BAR_FLOAT}: how much room a floating pill leaves under itself
     * ({@link app.hushgram.extension.instagram.misc.GlassLift}, #87). Starts where the pill has always sat.
     * A change shows after a restart, so it asks for one.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.GlassLift> GLASS_TAB_BAR_LIFT =
            new EnumSetting<>("hushgram_glass_tab_bar_lift",
                    app.hushgram.extension.instagram.misc.GlassLift.STANDARD, true, parent(GLASS_TAB_BAR_FLOAT));

    /**
     * Under {@link #GLASS_TAB_BAR}: on the Reels screen the pill slides away once the screen has gone this
     * long without a touch ({@link app.hushgram.extension.instagram.misc.GlassReelsHide}, #170). A tap where
     * the pill sits, a tab change or Back brings it back. A drag there is the reel's seek bar's (#221). It has nothing to do with
     * {@link #GLASS_TAB_BAR_HIDE_ON_SCROLL}, which leaves the bar alone on every reel. Starts on never. Read
     * as the bar is built, so a change asks for a restart.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.GlassReelsHide> GLASS_TAB_BAR_REELS_HIDE =
            new EnumSetting<>("hushgram_glass_tab_bar_reels_hide",
                    app.hushgram.extension.instagram.misc.GlassReelsHide.NEVER, true, parent(GLASS_TAB_BAR));

    /**
     * Your own profile's tabs get one more, a bookmark, that opens Saved
     * ({@link app.hushgram.extension.instagram.misc.ProfileSaved}). The tabs are built as the profile
     * opens, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting SAVED_ON_PROFILE =
            new BooleanSetting("hushgram_saved_on_profile", FALSE, true);

    /**
     * Instagram stops waking itself with an alarm every minute or two to note that it is still running
     * ({@link app.hushgram.extension.instagram.misc.Heartbeat}). Read each time the next alarm would be set.
     * Off to start.
     */
    public static final BooleanSetting STOP_HEARTBEAT =
            new BooleanSetting("hushgram_stop_heartbeat", FALSE);

    /**
     * Instagram stops setting the alarm that wakes the phone five minutes on to upload its usage events
     * ({@link app.hushgram.extension.instagram.misc.Heartbeat#setUploadAlarm}). Read each time the alarm would be
     * set. Off to start.
     */
    public static final BooleanSetting STOP_UPLOAD_ALARM =
            new BooleanSetting("hushgram_stop_upload_alarm", FALSE);

    /**
     * Instagram's windows ask Android for the display's fastest mode at the resolution it runs at
     * ({@link app.hushgram.extension.instagram.misc.RefreshRate}). Read as each activity resumes, so
     * no restart. Off to start.
     */
    public static final BooleanSetting HIGHEST_REFRESH_RATE =
            new BooleanSetting("hushgram_highest_refresh_rate", FALSE);

    /**
     * Every emoji draws in Google's style: EmojiCompat is asked to replace every emoji it knows
     * from the font it loads from Google Play services, not only the ones the phone lacks
     * ({@link app.hushgram.extension.instagram.misc.EmojiStyle}). Read for each piece of text, but
     * text already drawn keeps its style, so a change shows fully after a restart. Off to start.
     */
    public static final BooleanSetting NOTO_EMOJI =
            new BooleanSetting("hushgram_noto_emoji", FALSE, true);

    /**
     * Where Instagram's emoji draw from: the phone's, a free emoji pack HushGram downloads, or an
     * emoji font file picked in settings ({@link app.hushgram.extension.instagram.misc.EmojiFonts}).
     * Any but the phone's goes ahead of {@link #NOTO_EMOJI}. Read once per process, so a change shows
     * after a restart. Starts on the phone's.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.EmojiChoice> EMOJI_FONT =
            new EnumSetting<>("hushgram_emoji_font", app.hushgram.extension.instagram.misc.EmojiChoice.PHONE, true);

    /**
     * Instagram's blue turns into the color picked in {@link #ACCENT_HUE} wherever it's read from the
     * theme ({@link app.hushgram.extension.instagram.misc.AccentColor}). Colors already on screen keep
     * theirs, so a change shows after a restart. Off to start.
     */
    public static final BooleanSetting ACCENT_COLOR =
            new BooleanSetting("hushgram_accent_color", FALSE, true);

    /**
     * Under {@link #ACCENT_COLOR}: the color ({@link app.hushgram.extension.instagram.misc.AccentHue}).
     * Starts on purple, and Instagram blue keeps the stock color. Material You (#187) takes the
     * wallpaper's colors on Android 12 and newer. A change shows after a restart.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.AccentHue> ACCENT_HUE =
            new EnumSetting<>("hushgram_accent_hue",
                    app.hushgram.extension.instagram.misc.AccentHue.PURPLE, true, parent(ACCENT_COLOR));

    /**
     * Under {@link #ACCENT_COLOR}: Instagram's red badges, the dots and counts on its icons, take the
     * picked color too (#315, {@link app.hushgram.extension.instagram.misc.AccentTheme}). A change shows
     * after a restart. Off to start.
     */
    public static final BooleanSetting ACCENT_BADGES =
            new BooleanSetting("hushgram_accent_badges", FALSE, true, parent(ACCENT_COLOR));

    /**
     * Under {@link #ACCENT_COLOR}: Instagram's icons, the top bar's, the tab bar's and a post's
     * buttons among them, take the picked color too (#8, {@link app.hushgram.extension.instagram.misc.AccentTheme}).
     * Text keeps its color. A change shows after a restart. Off to start.
     */
    public static final BooleanSetting ACCENT_ICONS =
            new BooleanSetting("hushgram_accent_icons", FALSE, true, parent(ACCENT_COLOR));

    /**
     * HushGram's logo on the screen Android shows while Instagram starts, on Android 13 and newer
     * ({@link app.hushgram.extension.instagram.misc.LaunchScreen}). Android keeps the answer, so a
     * change shows from the next start. Off to start.
     */
    public static final BooleanSetting LAUNCH_SCREEN =
            new BooleanSetting("hushgram_launch_screen", FALSE, true);

    /**
     * The font Instagram's text draws in: Instagram's own, the phone's, or a font file picked in
     * settings ({@link app.hushgram.extension.instagram.misc.Fonts}). Instagram keeps its fonts once
     * loaded, so a change shows after a restart. Starts on Instagram's own.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.FontChoice> FONT =
            new EnumSetting<>("hushgram_font", app.hushgram.extension.instagram.misc.FontChoice.INSTAGRAM, true);

    /**
     * The icon on the home screen and in the app drawer: Instagram's own, HushGram's, or Instagram's
     * glyph on black, on plum or in black on white ({@link app.hushgram.extension.instagram.misc.AppIcon},
     * #284, #353). It
     * changes when Instagram next goes to the background. Starts on Instagram's own.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.AppIconChoice> APP_ICON =
            new EnumSetting<>("hushgram_app_icon", app.hushgram.extension.instagram.misc.AppIconChoice.INSTAGRAM);

    static {
        // Changing the icon can take it off the home screen, so a pause leaves the pick where it is.
        Setting.keepWhenPaused(APP_ICON);
    }

    /**
     * The rows Instagram's own Settings and activity screen leaves out, as the names of Instagram's
     * row ids with commas between ({@link app.hushgram.extension.instagram.misc.SettingsRows}, #235):
     * ARCHIVE, CLOSE_FRIENDS and the like. Nothing named is the start, so every row shows. Read as
     * the screen makes each row, so a change shows the next time it opens.
     */
    public static final StringSetting HIDDEN_SETTINGS_ROWS =
            new StringSetting("hushgram_hidden_settings_rows", "");

    /**
     * The rows Instagram's settings screen has shown on this phone, a line each: the row's id name, a
     * tab and its title as Instagram showed it. Fills in as that screen opens, so the list of rows to
     * hide offers what this phone has. Not a switch, and left out of a backup.
     */
    public static final StringSetting SEEN_SETTINGS_ROWS =
            new StringSetting("hushgram_seen_settings_rows", "", false, false);

    /**
     * Every notification Instagram posts joins one group with a count
     * ({@link app.hushgram.extension.instagram.misc.NotificationGroups}). Read at each post. Off to
     * start.
     */
    public static final BooleanSetting GROUP_NOTIFICATIONS =
            new BooleanSetting("hushgram_group_notifications", FALSE);

    /** With {@link #GROUP_NOTIFICATIONS} on, a group per notification channel in place of one. */
    public static final BooleanSetting GROUP_NOTIFICATIONS_BY_TYPE =
            new BooleanSetting("hushgram_group_notifications_by_type", FALSE, parent(GROUP_NOTIFICATIONS));

    /**
     * The notification channels whose posts never reach the shade, as Instagram's channel ids with
     * commas between ({@link app.hushgram.extension.instagram.misc.NotificationGroups#mutedChannels}).
     * Nothing named is the start, so every notification posts. Read at each post, with or without
     * {@link #GROUP_NOTIFICATIONS}.
     */
    public static final StringSetting MUTED_NOTIFICATION_CHANNELS =
            new StringSetting("hushgram_muted_notification_channels", "");

    /**
     * Each time Instagram goes to the background with more than 500 MB in its cache folders, they're
     * emptied ({@link app.hushgram.extension.instagram.misc.MediaCache}). Off to start.
     */
    public static final BooleanSetting CLEAR_MEDIA_CACHE =
            new BooleanSetting("hushgram_clear_media_cache", FALSE);

    /**
     * Follows you or Doesn't follow you beside the name on someone's profile
     * ({@link app.hushgram.extension.instagram.profile.FriendshipStatus}). Read each time Instagram
     * binds a profile's name.
     */
    public static final BooleanSetting SHOW_FRIENDSHIP_STATUS =
            new BooleanSetting("hushgram_show_friendship_status", TRUE);

    /**
     * Show if a profile follows you as a chip under the profile's counts, which also says Following
     * each other ({@link app.hushgram.extension.instagram.profile.FriendshipStatus}), instead of the
     * gray label by the name. Off to start. Read each time Instagram binds a profile's name.
     */
    public static final BooleanSetting FRIENDSHIP_STATUS_CHIP =
            new BooleanSetting("hushgram_friendship_status_chip", FALSE);

    /**
     * Where the chip goes and how big it is (#157): under the counts at its usual size, or small and
     * centered under the profile picture. Needs {@link #FRIENDSHIP_STATUS_CHIP}. Read each time
     * Instagram binds a profile's name. Under the counts to start.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.profile.FollowLabelPlace> FOLLOW_LABEL_PLACE =
            new EnumSetting<>("hushgram_follow_label_place",
                    app.hushgram.extension.instagram.profile.FollowLabelPlace.UNDER_COUNTS, parent(FRIENDSHIP_STATUS_CHIP));

    /**
     * Doesn't follow you on the rows of your own Following list
     * ({@link app.hushgram.extension.instagram.profile.FollowingList}). Off to start. Read each time
     * Instagram binds a row of a follow list.
     */
    public static final BooleanSetting MARK_FOLLOWING_LIST =
            new BooleanSetting("hushgram_mark_following_list", FALSE);

    /**
     * Your own Following list with the accounts that don't follow you back first
     * ({@link app.hushgram.extension.instagram.profile.FollowingList}). Off to start. Read each time
     * Instagram rebuilds the rows of a follow list.
     */
    public static final BooleanSetting FOLLOWING_NOT_BACK_FIRST =
            new BooleanSetting("hushgram_following_not_back_first", FALSE);

    /**
     * Recent unfollowers ({@link app.hushgram.extension.instagram.profile.Unfollowers}): your own
     * Followers list compared each time it has loaded to the end. Off to start. Read each time
     * Instagram rebuilds the rows of a follow list.
     */
    public static final BooleanSetting TRACK_UNFOLLOWERS =
            new BooleanSetting("hushgram_track_unfollowers", FALSE);

    /**
     * Suggested for you and the Discover people button on profiles
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions}). Read each time Instagram
     * builds or binds a profile's header, so off or paused, the suggestions are back on the next one.
     * Off to start.
     */
    public static final BooleanSetting HIDE_PROFILE_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_profile_suggestions", FALSE);

    /**
     * The Suggested for you section under the followers and following lists
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions#followList}), the Suggested
     * tab on other people's follow lists and the section under their mutual followers (#302). Read as
     * a list opens or draws, so off or paused, they're back on the next list. Off to start.
     */
    public static final BooleanSetting HIDE_FOLLOW_LIST_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_follow_list_suggestions", FALSE);

    /**
     * The Complete your profile cards on your own profile, like Add bio and Find people to follow
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions#hideCompletionCards}). Read
     * each time your profile's posts are laid out, so off or paused, the cards are back on the next
     * one. Off to start (#159).
     */
    public static final BooleanSetting HIDE_PROFILE_COMPLETION =
            new BooleanSetting("hushgram_hide_profile_completion", FALSE);

    /**
     * The People you don't follow back section under your notifications, accounts that follow you
     * with a Follow back button on each
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions#followBacks} and
     * {@link app.hushgram.extension.instagram.profile.ProfileSuggestions#hidesSuggestionSection}). Read each
     * time the notifications load, so off or paused, the section is back on the next load. Follow
     * notifications stay either way. Off to start (#161).
     */
    public static final BooleanSetting HIDE_FOLLOW_BACK_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_follow_back_suggestions", FALSE);

    /**
     * Notification rows that only suggest something, a thread from an account you don't follow or
     * someone you might know
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions#keepNotification}), and the
     * account sections that only suggest people, such as Suggested for you
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions#hidesSuggestionSection}, #112).
     * Read each time the notifications load, so off or paused, they're back on the next load. Likes,
     * comments, mentions, new followers and follow requests stay either way. Off to start (#181).
     */
    public static final BooleanSetting HIDE_NOTIFICATION_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_notification_suggestions", FALSE);

    /**
     * The Featured section at the top of your notifications, header and rows: posts and threads the
     * server picks to show you there
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions#featuredRows}, #259). Read
     * each time the notifications load, so off or paused, it's back on the next load. Off to start.
     */
    public static final BooleanSetting HIDE_NOTIFICATION_FEATURED =
            new BooleanSetting("hushgram_hide_notification_featured", FALSE);

    /**
     * The red dot on the heart when nothing but Featured is behind it: the badge's dot count is
     * answered 0 while no like, comment, follow or tag is counted
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions#featuredDot}, #189). Read
     * each time the badge counts are built, so off or paused, it's back with the next one. Off to start.
     */
    public static final BooleanSetting HIDE_FEATURED_DOT =
            new BooleanSetting("hushgram_hide_featured_dot", FALSE);

    /**
     * The row of story highlights on profiles
     * ({@link app.hushgram.extension.instagram.profile.ProfileHighlights}). Read each time Instagram
     * lays out a profile's header, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_HIGHLIGHTS =
            new BooleanSetting("hushgram_hide_highlights", FALSE);

    /**
     * The tabs Instagram leaves off other people's profiles, as Instagram's tab keys with commas between ({@link app.hushgram.extension.instagram.profile.ProfileTabs}):
     * any of profile_clips, profile_reposts and profile_tagged_media_photos_of_you. Nothing named is the start, so every tab shows. The grid is
     * never offered. Read each time Instagram builds a profile's tabs, so it shows on the next profile opened. Before #296 it covered your own
     * profile too, so {@link #HIDDEN_OWN_PROFILE_TABS} follows it until that's saved.
     */
    public static final StringSetting HIDDEN_PROFILE_TABS =
            new StringSetting("hushgram_hidden_profile_tabs", "");

    /**
     * The tabs Instagram leaves off your own profile (#296), the same keys as {@link #HIDDEN_PROFILE_TABS}. Starts as
     * {@link ProfileTabs#SAME_AS_OTHERS}, which means it was never saved and your profile follows the other-profiles value, so a pick
     * made before the split keeps covering both. Saving either row writes a real value here.
     */
    public static final StringSetting HIDDEN_OWN_PROFILE_TABS =
            new StringSetting("hushgram_hidden_own_profile_tabs", ProfileTabs.SAME_AS_OTHERS);

    /**
     * The Threads button on profiles' top bar
     * ({@link app.hushgram.extension.instagram.profile.ThreadsButton}). Read each time Instagram
     * builds a profile's top bar, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_THREADS_BUTTON =
            new BooleanSetting("hushgram_hide_threads_button", FALSE);

    /**
     * The Create button, the plus at the top of your own profile
     * ({@link app.hushgram.extension.instagram.profile.ThreadsButton}, #254). Read each time Instagram
     * builds a profile's top bar, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_CREATE_BUTTON =
            new BooleanSetting("hushgram_hide_create_button", FALSE);

    /**
     * The Share profile button in your profile's row of action buttons, with Edit profile taking its
     * space ({@link app.hushgram.extension.instagram.profile.ShareProfileButton}, #253). Read each time
     * Instagram builds the row, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_SHARE_PROFILE_BUTTON =
            new BooleanSetting("hushgram_hide_share_profile_button", FALSE);

    /**
     * The Subscribe button in a creator's row of action buttons, with Following and Message taking
     * its space ({@link app.hushgram.extension.instagram.profile.ShareProfileButton}, #248). Read each
     * time Instagram builds the row, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_SUBSCRIBE_PROFILE_BUTTON =
            new BooleanSetting("hushgram_hide_subscribe_profile_button", FALSE);

    /**
     * The arrow at the end of a profile's row of action buttons, which opens a menu of the buttons
     * that didn't fit ({@link app.hushgram.extension.instagram.profile.ShareProfileButton}, #248).
     * Read each time Instagram builds the row. Off to start.
     */
    public static final BooleanSetting HIDE_PROFILE_OVERFLOW_BUTTON =
            new BooleanSetting("hushgram_hide_profile_overflow_button", FALSE);

    /**
     * The Notes bubble on profile pictures, yours included
     * ({@link app.hushgram.extension.instagram.profile.ProfileNotes}). Read each time Instagram opens
     * a profile, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_PROFILE_NOTES =
            new BooleanSetting("hushgram_hide_profile_notes", FALSE);

    /**
     * Opening someone's profile goes on to their posts as a scrolling list, the one Instagram opens for a
     * tapped post, with the grid one Back away ({@link app.hushgram.extension.instagram.profile.PostsList}).
     * Read each time a posts tab comes up, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting PROFILE_POSTS_LIST =
            new BooleanSetting("hushgram_profile_posts_list", FALSE);

    /**
     * A sideways swipe on Home that would open the camera
     * ({@link app.hushgram.extension.instagram.feed.SwipeToCreate}). Read at each step of a swipe,
     * so a change shows on the next one. Off to start.
     */
    public static final BooleanSetting STOP_SWIPE_TO_CREATE =
            new BooleanSetting("hushgram_stop_swipe_to_create", FALSE);

    /**
     * A sideways swipe between the main tabs
     * ({@link app.hushgram.extension.instagram.feed.TabSwipe}). Read at each touch, so a change
     * shows on the next swipe. Off to start.
     */
    public static final BooleanSetting STOP_TAB_SWIPING =
            new BooleanSetting("hushgram_stop_tab_swiping", FALSE);

    /**
     * The cards of accounts and creators to follow that Instagram puts between reels
     * ({@link app.hushgram.extension.instagram.reels.ReelsSuggestions}). Read as each page of
     * reels arrives, so a change shows from the next page. Off to start.
     */
    public static final BooleanSetting HIDE_REELS_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_reels_suggestions", FALSE);

    /**
     * Reels whose post Instagram marks as a paid partnership, or as boosted
     * ({@link app.hushgram.extension.instagram.reels.PaidPartnershipReels}). Read as each page of
     * reels arrives, so a change shows from the next page. Off to start.
     */
    public static final BooleanSetting HIDE_PAID_PARTNERSHIP_REELS =
            new BooleanSetting("hushgram_hide_paid_partnership_reels", FALSE);

    /**
     * Reels whose post carries Instagram's own AI label
     * ({@link app.hushgram.extension.instagram.reels.AiLabeledReels}). Read as each page of reels
     * arrives, so a change shows from the next page. Off to start.
     */
    public static final BooleanSetting HIDE_AI_LABELED_REELS =
            new BooleanSetting("hushgram_hide_ai_labeled_reels", FALSE);

    /**
     * Reels whose caption holds one of {@link #REELS_BLOCKED_WORDS}
     * ({@link app.hushgram.extension.instagram.reels.CaptionWordReels}). Read as each page of reels
     * arrives, so a change shows from the next page. Off to start (#192).
     */
    public static final BooleanSetting HIDE_CAPTION_WORD_REELS =
            new BooleanSetting("hushgram_hide_caption_word_reels", FALSE);

    /**
     * The words and phrases {@link #HIDE_CAPTION_WORD_REELS} looks for in a reel's caption, split at
     * commas and new lines. A value control, not a switch. Empty to start.
     */
    public static final StringSetting REELS_BLOCKED_WORDS =
            new StringSetting("hushgram_reels_blocked_words", "", parent(HIDE_CAPTION_WORD_REELS));

    /**
     * {@link #REELS_BLOCKED_WORDS} on Home too: a post there whose caption holds one goes
     * ({@link app.hushgram.extension.instagram.feed.FeedSuggestions#homeItem}). Waits for
     * {@link #HIDE_CAPTION_WORD_REELS}, and is off to start (#192).
     */
    public static final BooleanSetting HIDE_CAPTION_WORD_HOME =
            new BooleanSetting("hushgram_hide_caption_word_home", FALSE, parent(HIDE_CAPTION_WORD_REELS));

    /** An explicit Copy action for original comment text. Off until enabled. */
    public static final BooleanSetting COPY_COMMENTS =
            new BooleanSetting("hushgram_copy_comments", FALSE);

    /**
     * Copy comment leaves out the @names a comment starts with, the way a reply begins (#270).
     * The comment in Instagram is untouched. Off until enabled.
     */
    public static final BooleanSetting COPY_COMMENTS_WITHOUT_MENTIONS =
            new BooleanSetting("hushgram_copy_comments_without_mentions", FALSE, parent(COPY_COMMENTS));

    /**
     * A selected comment's menu gets Copy username, for the account that wrote it
     * ({@link app.hushgram.extension.instagram.comment.CommentAuthor}). Off until enabled.
     */
    public static final BooleanSetting COPY_COMMENT_AUTHORS =
            new BooleanSetting("hushgram_copy_comment_authors", FALSE);

    /** An explicit Save action for a photo the comment itself carries. Off until enabled. */
    public static final BooleanSetting SAVE_COMMENT_PHOTOS =
            new BooleanSetting("hushgram_save_comment_photos", FALSE);

    /** A GIF reply for a comment carrying a GIF, sending that same GIF back (#402). Off until enabled. */
    public static final BooleanSetting REPLY_WITH_COMMENT_GIFS =
            new BooleanSetting("hushgram_reply_with_comment_gifs", FALSE);

    /**
     * The menu on someone's profile gets Save profile picture
     * ({@link app.hushgram.extension.instagram.download.ProfilePicture}). Off until enabled.
     */
    public static final BooleanSetting SAVE_PROFILE_PICTURES =
            new BooleanSetting("hushgram_save_profile_pictures", FALSE);

    /**
     * The menu on someone's profile gets View profile picture, which opens their picture full
     * screen ({@link app.hushgram.extension.instagram.download.ProfilePicture}). Off until enabled.
     */
    public static final BooleanSetting VIEW_PROFILE_PICTURES =
            new BooleanSetting("hushgram_view_profile_pictures", FALSE);

    /**
     * The menu on someone's profile gets Copy username and Copy bio
     * ({@link app.hushgram.extension.instagram.download.ProfilePicture}). Off until enabled.
     */
    public static final BooleanSetting COPY_PROFILE_TEXT =
            new BooleanSetting("hushgram_copy_profile_text", FALSE);

    /**
     * A button next to the three dots on a profile opens a menu of the profile actions switched on
     * above ({@link app.hushgram.extension.instagram.download.ProfileExtras}). The three-dot menu
     * keeps its rows. Off until enabled.
     */
    public static final BooleanSetting PROFILE_EXTRAS_BUTTON =
            new BooleanSetting("hushgram_profile_extras_button", FALSE);

    /**
     * A voice message's menu in a chat gets Save
     * ({@link app.hushgram.extension.instagram.download.VoiceMessage}). Off until enabled.
     */
    public static final BooleanSetting DOWNLOAD_VOICE_MESSAGES =
            new BooleanSetting("hushgram_download_voice_messages", FALSE);

    /**
     * Feed's action rows leave out the Comment button and the comment count
     * ({@link app.hushgram.extension.instagram.feed.CommentsButton}). Read as each row's state is
     * built, so a post already drawn changes the next time Feed draws it. Off to start.
     */
    public static final BooleanSetting HIDE_COMMENTS =
            new BooleanSetting("hushgram_hide_comments", FALSE);

    /**
     * The comment sheet leaves out the row of quick emoji above the comment box
     * ({@link app.hushgram.extension.instagram.feed.CommentEmojiRow}). Read before each frame of a
     * resumed screen, so it needs no restart. Off to start.
     */
    public static final BooleanSetting HIDE_COMMENT_EMOJI_ROW =
            new BooleanSetting("hushgram_hide_comment_emoji_row", FALSE);

    /**
     * The comment sheet opens without bringing the keyboard up on its own
     * ({@link app.hushgram.extension.instagram.feed.CommentKeyboard}). A tap in the box still brings
     * it up. Read each time a sheet opens, so it needs no restart. Off to start.
     */
    public static final BooleanSetting COMMENT_KEYBOARD_DOWN =
            new BooleanSetting("hushgram_comment_keyboard_down", FALSE);

    /**
     * The Follow and Subscribe buttons beside the author's name on a feed post
     * ({@link app.hushgram.extension.instagram.feed.FeedFollowButton}). Read before each frame of a
     * resumed screen, so it needs no restart. Off to start.
     */
    public static final BooleanSetting HIDE_FEED_FOLLOW =
            new BooleanSetting("hushgram_hide_feed_follow", FALSE);

    /**
     * The caption on feed posts and in the Reels viewer
     * ({@link app.hushgram.extension.instagram.feed.HideCaptions}). Read before each frame of a
     * resumed screen, so it needs no restart. Off to start.
     */
    public static final BooleanSetting HIDE_CAPTIONS =
            new BooleanSetting("hushgram_hide_captions", FALSE);

    /**
     * Home's header staying in place while the feed scrolls
     * ({@link app.hushgram.extension.instagram.feed.HomeTopBar}). Home hands its header to the
     * scroll controller as it opens, so the restart card comes up. Off to start.
     */
    public static final BooleanSetting KEEP_HOME_TOP_BAR =
            new BooleanSetting("hushgram_keep_home_top_bar", FALSE, true);

    /**
     * Home's header showing the feed blurred under it, like frosted glass (#418,
     * {@link app.hushgram.extension.instagram.feed.GlassTopBar}). It takes the header as Home hands
     * it over, so the restart card comes up. Off to start.
     */
    public static final BooleanSetting SEE_THROUGH_HOME_TOP_BAR =
            new BooleanSetting("hushgram_see_through_home_top_bar", FALSE, true);

    /** The Follow button beside a reel's author in the Reels viewer. Off to start. */
    public static final BooleanSetting HIDE_REEL_FOLLOW_BUTTON =
            new BooleanSetting("hushgram_hide_reel_follow_button", FALSE);

    /**
     * The pills on a reel that prompt you to make something (Edits, a template, a creative tool)
     * or promote something (Meta AI, Ray-Ban Meta glasses, an affiliate link). A live badge, a
     * state-controlled media label and the other labels there stay. Off to start.
     */
    public static final BooleanSetting HIDE_REEL_CHIPS =
            new BooleanSetting("hushgram_hide_reel_chips", FALSE);

    /** The suggested search terms near a reel's author. Off to start. */
    public static final BooleanSetting HIDE_REEL_SEARCH_PILL =
            new BooleanSetting("hushgram_hide_reel_search_pill", FALSE);

    /** The product tag and shopping buttons near a reel's author. Off to start. */
    public static final BooleanSetting HIDE_REEL_PRODUCT_PILLS =
            new BooleanSetting("hushgram_hide_reel_product_pills", FALSE);

    /** The poll and vote prompts near a reel's author. Off to start. */
    public static final BooleanSetting HIDE_REEL_POLL_PILLS =
            new BooleanSetting("hushgram_hide_reel_poll_pills", FALSE);

    /**
     * What friends did with a reel, shown over it: the bubbles above the author of friends who
     * liked, commented or follow them, the comment Instagram previews and the row of friends who
     * saw it. Off to start.
     */
    public static final BooleanSetting HIDE_REEL_SOCIAL_FOOTER =
            new BooleanSetting("hushgram_hide_reel_social_footer", FALSE);

    /**
     * The Add comment bar under a reel opened from a profile's reposts. The reel's comment button
     * still opens its comments. Off to start.
     */
    public static final BooleanSetting HIDE_REEL_COMMENT_BAR =
            new BooleanSetting("hushgram_hide_reel_comment_bar", FALSE);

    /**
     * The Add comment bar under every reel, in the Reels tab too (#119). The reel's comment button
     * still opens its comments. Off to start.
     */
    public static final BooleanSetting HIDE_REEL_COMMENT_BAR_EVERYWHERE =
            new BooleanSetting("hushgram_hide_reel_comment_bar_everywhere", FALSE);

    /**
     * The reply bar under a reel opened from a chat (#177): the Reply to field, the quick reactions
     * and the plus button. The chat's own message box is another view and stays. Off to start.
     */
    public static final BooleanSetting HIDE_REEL_REPLY_BAR =
            new BooleanSetting("hushgram_hide_reel_reply_bar", FALSE);

    /**
     * The top bar of the Reels tab, with the Reels title and the camera button (#14). A reel opened
     * from anywhere else keeps its bar
     * ({@link app.hushgram.extension.instagram.reels.ReelsTopBar}). Off to start.
     */
    public static final BooleanSetting HIDE_REELS_TOP_BAR =
            new BooleanSetting("hushgram_hide_reels_top_bar", FALSE);

    /**
     * The Create (+) button in the top bar of every reel viewer, the Reels tab's and the others,
     * which opens the camera (#177). The rest of the bar stays
     * ({@link app.hushgram.extension.instagram.reels.ReelsTopBar}). Off to start.
     */
    public static final BooleanSetting HIDE_REELS_CREATE_BUTTON =
            new BooleanSetting("hushgram_hide_reels_create_button", FALSE);

    /**
     * The Your algorithm button in the top bar of the Reels tab (#217). The rest of the bar stays
     * ({@link app.hushgram.extension.instagram.reels.ReelsTopBar}). Off to start.
     */
    public static final BooleanSetting HIDE_REELS_YOUR_ALGORITHM =
            new BooleanSetting("hushgram_hide_reels_your_algorithm", FALSE);

    /**
     * The Friends tab beside Reels at the top of the Reels tab, the lane of reels the people you
     * follow shared (#300). The Reels title stays
     * ({@link app.hushgram.extension.instagram.reels.ReelsTopBar}). Off to start.
     */
    public static final BooleanSetting HIDE_REELS_FRIENDS_TAB =
            new BooleanSetting("hushgram_hide_reels_friends_tab", FALSE);

    /**
     * The Save (bookmark) button in the right-hand column of a reel, and the save count under it
     * when Instagram draws one. Feed posts keep theirs. Off to start.
     */
    public static final BooleanSetting HIDE_REEL_SAVE_BUTTON =
            new BooleanSetting("hushgram_hide_reel_save_button", FALSE);

    /**
     * Instagram's clear mode, once you turn it on for a reel, stays on for the next reels you swipe
     * to, until you leave it yourself (#138,
     * {@link app.hushgram.extension.instagram.reels.ReelClearMode}). Read at each turn-on and each
     * swipe, so a change needs no restart. Off to start.
     */
    public static final BooleanSetting KEEP_REEL_CLEAR_MODE =
            new BooleanSetting("hushgram_keep_reel_clear_mode", FALSE);

    /**
     * With {@link #KEEP_REEL_CLEAR_MODE} on, Reels starts in clear mode with no tap, until you leave
     * it yourself (#138, {@link app.hushgram.extension.instagram.reels.ReelClearMode}). Read when the
     * Reels viewer is set up and at each try, so a change needs no restart. Off to start.
     */
    public static final BooleanSetting START_REEL_CLEAR_MODE =
            new BooleanSetting("hushgram_start_reel_clear_mode", FALSE, parent(KEEP_REEL_CLEAR_MODE));

    /**
     * The top bar of a reels viewer staying in place while you swipe through reels (#411,
     * {@link app.hushgram.extension.instagram.reels.ReelsTopBarScroll}). Read at each scroll, so a
     * change needs no restart. Off to start.
     */
    public static final BooleanSetting KEEP_REELS_TOP_BAR =
            new BooleanSetting("hushgram_keep_reels_top_bar", FALSE);

    /**
     * Burn-in guard on the Reels screen (#162): dims a reel's controls after a few seconds without a
     * touch, and can also move the screen a step every few minutes
     * ({@link app.hushgram.extension.instagram.reels.BurnInGuard}). Off to start.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.reels.BurnInMode> BURN_IN_GUARD =
            new EnumSetting<>("hushgram_burn_in_guard", app.hushgram.extension.instagram.reels.BurnInMode.OFF);

    /** How long Burn-in guard waits before it dims (#162). 5 seconds to start, as before the choice. */
    public static final EnumSetting<app.hushgram.extension.instagram.reels.BurnInWait> BURN_IN_WAIT =
            new EnumSetting<>("hushgram_burn_in_wait", app.hushgram.extension.instagram.reels.BurnInWait.FIVE_SECONDS);

    /**
     * Burn-in guard's Stay dim when I tap (#162): once dimmed, the controls stay dimmed on the Reels screen
     * whatever you tap or swipe, the next reel included, and come back when you leave Reels. Read at each
     * touch, so a change needs no restart. Off to start.
     */
    public static final BooleanSetting BURN_IN_STAY_DIM =
            new BooleanSetting("hushgram_burn_in_stay_dim", FALSE);

    /**
     * Download in every reel's more menu, saving the reel through the save pipeline below instead
     * of Instagram's own save, which only some reels offer and which stamps a watermark on.
     */
    public static final BooleanSetting DOWNLOAD_REELS =
            new BooleanSetting("hushgram_download_reels", TRUE);

    /**
     * A reel with a video gets Download cover under Download in its menu, saving the still picture
     * Instagram shows before the reel plays (#48). Starts off, so the menu stays as it was.
     */
    public static final BooleanSetting DOWNLOAD_REEL_COVER =
            new BooleanSetting("hushgram_download_reel_cover", FALSE, parent(DOWNLOAD_REELS));

    /**
     * A reel that streams its sound as a track of its own gets Download audio under Download in
     * its menu, saving that track as an audio file (#216). Starts off, so the menu stays as it was.
     */
    public static final BooleanSetting DOWNLOAD_REEL_AUDIO =
            new BooleanSetting("hushgram_download_reel_audio", FALSE, parent(DOWNLOAD_REELS));

    /**
     * A double tap on a post in the feed or on a reel doesn't like it, where the two switches under
     * it say so. Off to start. The posts and reels switches under it start on, so turning this one on
     * covers both until you pick.
     */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE =
            new BooleanSetting("hushgram_turn_off_double_tap_like", FALSE);

    /** Under {@link #TURN_OFF_DOUBLE_TAP_LIKE}: a double tap on a post in the feed doesn't like it. */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS =
            new BooleanSetting("hushgram_turn_off_double_tap_like_on_posts", TRUE, parent(TURN_OFF_DOUBLE_TAP_LIKE));

    /** Under {@link #TURN_OFF_DOUBLE_TAP_LIKE}: a double tap on a reel doesn't like it. */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS =
            new BooleanSetting("hushgram_turn_off_double_tap_like_on_reels", TRUE, parent(TURN_OFF_DOUBLE_TAP_LIKE));

    /** Under {@link #TURN_OFF_DOUBLE_TAP_LIKE}: a double tap on a comment doesn't like it. Off to start. */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS =
            new BooleanSetting("hushgram_turn_off_double_tap_like_on_comments", FALSE, parent(TURN_OFF_DOUBLE_TAP_LIKE));

    /**
     * Under {@link #TURN_OFF_DOUBLE_TAP_LIKE}: a double tap on a message in a chat doesn't react to
     * it. Off to start. A long press still offers the reactions.
     */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES =
            new BooleanSetting("hushgram_turn_off_double_tap_like_on_messages", FALSE, parent(TURN_OFF_DOUBLE_TAP_LIKE));

    /**
     * The heart that pops up when you double tap a post plays {@link #LIKE_ANIMATION}
     * ({@link app.hushgram.extension.instagram.feed.LikeAnimation}). Read as each post's heart is set
     * up, so one already on screen changes the next time it's set up. Off to start.
     */
    public static final BooleanSetting CHANGE_LIKE_ANIMATION =
            new BooleanSetting("hushgram_change_like_animation", FALSE);

    /**
     * The name of the animation {@link #CHANGE_LIKE_ANIMATION} plays, one of Instagram's own. Blank
     * to start, which keeps Instagram's heart until one is picked, as a name this Instagram doesn't
     * have does.
     */
    public static final StringSetting LIKE_ANIMATION =
            new StringSetting("hushgram_like_animation", "", parent(CHANGE_LIKE_ANIMATION));

    /**
     * Reels is off the tab bar, and a start or a switch meant for it lands on Home. Instagram builds
     * its tab list as it starts, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_REELS_TAB =
            new BooleanSetting("hushgram_hide_reels_tab", FALSE, true);

    /**
     * Reels is put on the tab bar when Instagram's tab list leaves it out (#120). Hide the Reels tab
     * wins when both are on. The list is built as Instagram starts, so a change takes a restart.
     * Off to start.
     */
    public static final BooleanSetting SHOW_REELS_TAB =
            new BooleanSetting("hushgram_show_reels_tab", FALSE, true);

    /**
     * Messages is put on the tab bar, before Profile, when Instagram's tab list leaves it out (#160).
     * Tab order can move it like any tab. The list is built as Instagram starts, so a change takes a
     * restart. Off to start.
     */
    public static final BooleanSetting SHOW_MESSAGES_TAB =
            new BooleanSetting("hushgram_show_messages_tab", FALSE, true);

    /**
     * Messages' button is off the tab bar, and Messages stays one of Instagram's tabs, so the Messages
     * button on Home's header, notifications and shared chats still open it (#298). Only the button
     * folds, as each screen comes back, so no restart. Off to start.
     */
    public static final BooleanSetting HIDE_MESSAGES_TAB =
            new BooleanSetting("hushgram_hide_messages_tab", FALSE);

    /**
     * Activity (Instagram's notifications screen, the one the heart on Home's header opens) is put on
     * the tab bar, before Profile, when Instagram's tab list leaves it out (#156). Tab order can move
     * it like any tab. The list is built as Instagram starts, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting SHOW_ACTIVITY_TAB =
            new BooleanSetting("hushgram_show_activity_tab", FALSE, true);

    /**
     * Home is off the tab bar, and the Start tab takes its place wherever Instagram would open Home:
     * the first tab, Back, links, notifications and a restored back stack (#90). Needs a Start tab
     * other than Home that is still on the bar, and keeps Home without one. Built into the same tab
     * list as {@link #HIDE_REELS_TAB}, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_HOME_TAB =
            new BooleanSetting("hushgram_hide_home_tab", FALSE, true);

    /**
     * Search is off the tab bar, and a switch meant for it lands on Home. Built into the same tab
     * list as {@link #HIDE_REELS_TAB}, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_SEARCH_TAB =
            new BooleanSetting("hushgram_hide_search_tab", FALSE, true);

    /**
     * Create is off the tab bar, and the bar closes up around it. A change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_CREATE_TAB =
            new BooleanSetting("hushgram_hide_create_tab", FALSE, true);

    /**
     * Profile is off the tab bar, and a switch meant for it lands on Home. Your profile still opens from
     * your picture elsewhere in the app. A change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_PROFILE_TAB =
            new BooleanSetting("hushgram_hide_profile_tab", FALSE, true);

    /**
     * The Create (plus) button is off Home's header. The header is built without it, so no gap is left.
     * Instagram's own Create tab and the swipe to create are not touched. A change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_HOME_CREATE_BUTTON =
            new BooleanSetting("hushgram_hide_home_create_button", FALSE, true);

    /**
     * The notifications heart is off Home's header. The header is built without it, so no gap is left.
     * Notifications still arrive and can be opened from their alerts. A change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_HOME_NOTIFICATIONS_BUTTON =
            new BooleanSetting("hushgram_hide_home_notifications_button", FALSE, true);

    /**
     * A ghost button on Home's header, beside the Messages button, that turns Ghost mode on or off. The
     * header builds it as it draws, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting GHOST_BUTTON_ON_HOME =
            new BooleanSetting("hushgram_ghost_button_on_home", FALSE, true);

    /**
     * The ghost switches Ghost mode turns, as their keys with a comma between (#251): its settings row,
     * the button on Home's header and the long presses all flip only these, and the others keep what you
     * set. Empty, or naming none of the switches in the build, means all of them, which is how it starts.
     * Read at each flip, so a change needs no restart.
     */
    public static final StringSetting GHOST_MODE_PICKS =
            new StringSetting("hushgram_ghost_mode_picks", "");

    /**
     * A Search button on Home's header that opens Instagram's own Search tab, or with Hide the Search tab
     * on where the tabs swipe, Instagram's search screen ({@link app.hushgram.extension.instagram.reels.SearchHeaderButton}).
     * The header builds it as it draws, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting SEARCH_BUTTON_ON_HOME =
            new BooleanSetting("hushgram_search_button_on_home", FALSE, true);

    /**
     * A Messages button on Home's header when the header has no Messages button of its own, because
     * Instagram put Messages on the tab bar (#156). It opens the Messages tab the way Instagram's own
     * header button does ({@link app.hushgram.extension.instagram.reels.MessagesHeaderButton}). The
     * header builds it as it draws, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting MESSAGES_BUTTON_ON_HOME =
            new BooleanSetting("hushgram_messages_button_on_home", FALSE, true);

    /**
     * A gear on Home's header that opens HushGram settings (#404,
     * {@link app.hushgram.extension.instagram.reels.SettingsHeaderButton}). The header builds it as it
     * draws, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting SETTINGS_BUTTON_ON_HOME =
            new BooleanSetting("hushgram_settings_button_on_home", FALSE, true);

    /**
     * Instagram's own Messages button is off Home's header, for accounts that still have it there and
     * put Messages on the tab bar with Show the Messages tab (#210). Each header draw hides it, and
     * no Messages button of HushGram's goes in ({@link app.hushgram.extension.instagram.reels.MessagesHeaderButton}).
     * A change takes a restart. Off to start.
     */
    public static final BooleanSetting HIDE_HOME_MESSAGES_BUTTON =
            new BooleanSetting("hushgram_hide_home_messages_button", FALSE, true);

    /**
     * Instagram's logo sits in the middle of Home's header, with the buttons on each side, as
     * Instagram's newer header has it ({@link app.hushgram.extension.instagram.reels.HomeLogo}). The
     * header centers it as it draws, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting CENTER_HOME_LOGO =
            new BooleanSetting("hushgram_center_home_logo", FALSE, true);

    /**
     * The username at the top of a profile sits in the middle of the bar, with the buttons where they
     * are ({@link app.hushgram.extension.instagram.profile.ProfileTitle}, #140). It's moved after each
     * layout, so a change shows without a restart. Off to start.
     */
    public static final BooleanSetting CENTER_PROFILE_NAME =
            new BooleanSetting("hushgram_center_profile_name", FALSE);

    /**
     * The title of every other screen with Instagram's shared top bar, like notifications, sits in the
     * middle of the bar as far as the buttons beside it allow
     * ({@link app.hushgram.extension.instagram.profile.ProfileTitle}, #161). Home and profiles keep
     * their own switches. It's moved after each layout, so a change shows without a restart. Off to
     * start.
     */
    public static final BooleanSetting CENTER_SCREEN_TITLES =
            new BooleanSetting("hushgram_center_screen_titles", FALSE);

    /**
     * Instagram's logo on Home, the name on profiles and the titles of other screens sit at the start
     * of their bars, for accounts whose newer layout centers them (#310). It wins over the three
     * centering switches above. Home's part is set as the header draws
     * ({@link app.hushgram.extension.instagram.reels.HomeLogo}), so a change takes a restart; the
     * names and titles move after each layout
     * ({@link app.hushgram.extension.instagram.profile.ProfileTitle}). Off to start.
     */
    public static final BooleanSetting TITLES_AT_START =
            new BooleanSetting("hushgram_titles_at_start", FALSE, true);

    /**
     * The order the tab bar shows its tabs in, as the tab enum's names, comma separated, first tab
     * first ({@link app.hushgram.extension.instagram.reels.TabOrder}). Empty keeps Instagram's order.
     * The bar is built as Instagram starts, so a change takes a restart.
     */
    public static final StringSetting TAB_ORDER = new StringSetting("hushgram_tab_order", "", true);

    /**
     * The tab Instagram opens on when it's started from its icon ({@link app.hushgram.extension.instagram.reels.TabStart}).
     * Home leaves Instagram's own start alone. A tab that's hidden or isn't on the bar opens Home.
     * Read once, as Instagram starts, so a change takes a restart.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.reels.StartTab> START_TAB =
            new EnumSetting<>("hushgram_start_tab", app.hushgram.extension.instagram.reels.StartTab.HOME, true);

    /**
     * Which tab bar Instagram draws ({@link app.hushgram.extension.instagram.reels.TabBarLayout}, #356):
     * the one Instagram picks for the account, the newer swipe bar or the classic one. The tab host
     * lays itself out once, as Instagram starts, so a change takes a restart. Starts on Instagram's.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.reels.TabBarLayoutChoice> TAB_BAR_LAYOUT =
            new EnumSetting<>("hushgram_tab_bar_layout", app.hushgram.extension.instagram.reels.TabBarLayoutChoice.INSTAGRAM, true);

    /**
     * The speed locked with Instagram's own 2x lock on a reel stays for the next reels, until the
     * lock is slid off, a hold at the edge is let go of, or Instagram restarts.
     */
    public static final BooleanSetting KEEP_REEL_SPEED =
            new BooleanSetting("hushgram_keep_reel_speed", TRUE);

    /**
     * Instagram's seek bar stays under every ordinary reel, short ones too, with the time played
     * and the reel's length above it
     * ({@link app.hushgram.extension.instagram.reels.ReelSeekBar}). The bar is decided as each reel
     * is shown, so a change shows from the next reels; the time follows the switch at once. Off to
     * start.
     */
    public static final BooleanSetting REEL_SEEK_BAR =
            new BooleanSetting("hushgram_reel_seek_bar", FALSE);

    /** A visible thumb on the native ordinary-Reel scrubber, independent of keeping its bar shown. */
    public static final BooleanSetting REEL_SEEK_THUMB =
            new BooleanSetting("hushgram_reel_seek_thumb", FALSE);

    /**
     * A thicker seek bar on every ordinary reel, with a round white thumb, the time label and a soft
     * dark fade behind it, drawn on Instagram's own bar
     * ({@link app.hushgram.extension.instagram.reels.ReelSeekBar}). Also keeps the bar on short reels.
     * Off to start.
     */
    public static final BooleanSetting BIG_REEL_SEEK_BAR =
            new BooleanSetting("hushgram_big_reel_seek_bar", FALSE);

    /**
     * The seek bar of a video opened in a chat on every ordinary reel (#10): Instagram's thin gray
     * line that turns white as it plays and its big round white thumb, with the time label and no
     * fade, drawn on Instagram's own bar ({@link app.hushgram.extension.instagram.reels.ReelSeekBar}).
     * Takes over from {@link #BIG_REEL_SEEK_BAR} when both are on. Also keeps the bar on short reels.
     * Off to start.
     */
    public static final BooleanSetting DM_REEL_SEEK_BAR =
            new BooleanSetting("hushgram_dm_reel_seek_bar", FALSE);

    /**
     * A reel shows the date and time it was posted on a line under its author's name
     * ({@link app.hushgram.extension.instagram.reels.ReelPostTime}), the way a post's exact time
     * writes it (#172). Read as each reel's title is drawn, so a change shows from the next reel.
     * Off to start.
     */
    public static final BooleanSetting SHOW_REEL_POST_TIME =
            new BooleanSetting("hushgram_show_reel_post_time", FALSE);

    /**
     * {@link #SHOW_REEL_POST_TIME} writes the seconds too, like Oct 2, 3:45:30 PM. Off to start, so
     * a reel's time has the precision a post's has until someone asks for more.
     */
    public static final BooleanSetting REEL_POST_TIME_SECONDS =
            new BooleanSetting("hushgram_reel_post_time_seconds", FALSE, parent(SHOW_REEL_POST_TIME));

    /**
     * {@link #SHOW_REEL_POST_TIME} adds how long ago the reel went up, in days, hours and minutes,
     * like Oct 2, 3:45 PM · 1d 2h 5m ago (#195). Worked out as the reel comes up. Off to start.
     */
    public static final BooleanSetting REEL_POST_TIME_AGO =
            new BooleanSetting("hushgram_reel_post_time_ago", FALSE, parent(SHOW_REEL_POST_TIME));

    /**
     * A reel that doesn't fill the screen shows a blurred copy of itself in the bars above and below
     * it, or beside it, instead of black ({@link app.hushgram.extension.instagram.reels.ReelBlurBars}),
     * and the strip under the tab bar below any reel shows the reel blurred too (#197).
     * Looked at when the Reels viewer opens and then as pages settle, so a change shows the next time
     * Reels is opened; turning it off puts the black back within a second. Off to start.
     */
    public static final BooleanSetting BLUR_REEL_BARS =
            new BooleanSetting("hushgram_blur_reel_bars", FALSE);

    /**
     * Instagram's auto scroll in Reels stays the way you last set it after a restart and after
     * you leave Reels ({@link app.hushgram.extension.instagram.reels.ReelAutoScroll}). Read each
     * time Instagram asks whether auto scroll is on, so a change shows from the next reel. Off to
     * start. The last choice is still noted while it's off, so turning it on keeps the one you made.
     */
    public static final BooleanSetting KEEP_REEL_AUTO_SCROLL =
            new BooleanSetting("hushgram_keep_reel_auto_scroll", FALSE);

    /**
     * A reel that ends moves on to the next one instead of playing again, through Instagram's own
     * Reels auto scroller ({@link app.hushgram.extension.instagram.reels.ReelAutoScroll}), on an
     * account Instagram doesn't offer auto scroll to as well (#175). The Reels viewer builds its
     * scroller as it opens, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting REEL_AUTO_ADVANCE =
            new BooleanSetting("hushgram_reel_auto_advance", FALSE, true);

    /**
     * A finger can't move the Reels viewer on to the next reel, and a pull down doesn't load new
     * ones ({@link app.hushgram.extension.instagram.reels.ReelScrolling}). A viewer turns its pager
     * off as it opens, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting STOP_REELS_SCROLLING =
            new BooleanSetting("hushgram_stop_reels_scrolling", FALSE, true);

    /**
     * A left swipe on a reel opens its creator's profile ({@link app.hushgram.extension.instagram.reels.SwipeToProfile}),
     * as older Instagram builds did. The swipe setup reads it once to build the profile panel, so a
     * change takes a restart. Off to start.
     */
    public static final BooleanSetting REEL_SWIPE_TO_PROFILE =
            new BooleanSetting("hushgram_reel_swipe_to_profile", FALSE, true);

    /**
     * After {@link #REEL_CAP_COUNT} reels in a session, swiping in Reels stops until Instagram has been
     * in the background for {@link #REEL_CAP_BREAK} minutes
     * ({@link app.hushgram.extension.instagram.reels.ReelScrolling}). Read at each new reel. Off to start.
     */
    public static final BooleanSetting REEL_CAP =
            new BooleanSetting("hushgram_reel_cap", FALSE);

    /**
     * Under {@link #REEL_CAP}: how many reels a session plays before swiping stops, from a slider (#405).
     * Starts on the 20 it has always been. Read as each reel is counted, so no restart.
     */
    public static final IntegerSetting REEL_CAP_COUNT =
            new IntegerSetting("hushgram_reel_cap_count", app.hushgram.extension.instagram.reels.ReelScrolling.CAP,
                    false, parent(REEL_CAP)).withRange(5, 100);

    /**
     * Under {@link #REEL_CAP}: how many minutes Instagram stays in the background before swiping comes
     * back, from a slider (#405). Starts on the 15 it has always been. Read as Instagram comes back.
     */
    public static final IntegerSetting REEL_CAP_BREAK =
            new IntegerSetting("hushgram_reel_cap_break",
                    app.hushgram.extension.instagram.reels.ReelScrolling.BREAK_MINUTES, false, parent(REEL_CAP))
                    .withRange(5, 120);

    /**
     * After this long on Home's feed, Reels and Explore, a break screen covers them for 5 minutes
     * ({@link app.hushgram.extension.instagram.reels.ScrollBreak}, #297). Read each second, so a
     * change shows at once. Off to start.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.reels.ScrollBreakTime> SCROLL_BREAK =
            new EnumSetting<>("hushgram_scroll_break", app.hushgram.extension.instagram.reels.ScrollBreakTime.OFF);

    /**
     * A profile opened from a reel scrolls its Reels tab to that reel, or its posts grid for a photo
     * reel, and outlines its tile for a moment ({@link app.hushgram.extension.instagram.reels.ReelOrigin}).
     * Read as the profile opens and each time its Reels tab or posts grid comes up, so a change shows on
     * the next profile opened. Off to start.
     */
    public static final BooleanSetting FIND_REEL_ORIGIN =
            new BooleanSetting("hushgram_find_reel_origin", FALSE);

    /**
     * With Find the reel you came from on, a reel past the first screen of the Reels tab or posts grid
     * is looked for only once its Find the reel button is tapped, instead of right away
     * ({@link app.hushgram.extension.instagram.reels.ReelOrigin}). Read each time the grid comes up. Off
     * to start.
     */
    public static final BooleanSetting FIND_REEL_ORIGIN_ON_TAP =
            new BooleanSetting("hushgram_find_reel_origin_on_tap", FALSE);

    /**
     * Whether auto scroll in Reels was last left on, as
     * {@link app.hushgram.extension.instagram.reels.ReelAutoScroll} last saw it. It isn't a switch:
     * a pause doesn't change it, and a settings backup leaves it out.
     */
    public static final BooleanSetting REEL_AUTO_SCROLL_ON =
            new BooleanSetting("hushgram_reel_auto_scroll_on", FALSE, false, false);

    /**
     * Download in the menu of anyone's story, photo or video, saving it through the save pipeline
     * below. Instagram's own menu offers a save only on your own stories.
     */
    public static final BooleanSetting DOWNLOAD_STORIES =
            new BooleanSetting("hushgram_download_stories", TRUE);

    /**
     * A Download button in the story viewer's header, beside the three dots, that saves the story in
     * front the way the menu's Download row does (#400). Off to start. It works with Download on
     * stories off too.
     */
    public static final BooleanSetting DOWNLOAD_BUTTON_ON_STORIES =
            new BooleanSetting("hushgram_download_button_on_stories", FALSE);

    /**
     * A Lens button in the story viewer's header, beside the three dots, that sends what the story
     * shows to Google Lens so its text can be copied (#303). Off to start.
     */
    public static final BooleanSetting TEXT_BUTTON_ON_STORIES =
            new BooleanSetting("hushgram_text_button_on_stories", FALSE);

    /**
     * Download in the menu of anyone else's feed post with a video, saving it through the save
     * pipeline below. Instagram's own row is there only on your own posts, where Instagram's
     * server lets it show, and a tap on it saves the same way. Off to start.
     */
    public static final BooleanSetting DOWNLOAD_VIDEOS =
            new BooleanSetting("hushgram_download_videos", FALSE);

    /**
     * The same Download for a photo: someone else's photo post, and a carousel on a photo page,
     * saved at the largest size Instagram lists. Off to start.
     */
    public static final BooleanSetting DOWNLOAD_PHOTOS =
            new BooleanSetting("hushgram_download_photos", FALSE);

    /**
     * A feed post with a video, and a carousel showing one, gets Download cover in its menu, saving
     * the still picture Instagram shows before the video plays, as a reel's Download cover does
     * (#94). Under Download feed videos, which it needs. Starts off, so the menu stays as it was.
     */
    public static final BooleanSetting DOWNLOAD_FEED_COVER =
            new BooleanSetting("hushgram_download_feed_cover", FALSE, parent(DOWNLOAD_VIDEOS));

    /**
     * A carousel's menu gets Choose pages after Save all, and the feed's download icon offers it
     * too: a list of the pages with a box each, saving only the ticked ones in order through Save
     * all's batch ({@link app.hushgram.extension.instagram.download.CarouselPages}). It waits for the
     * video or the photo switch, as Save all does. Starts off, so the menu stays as it was.
     */
    public static final BooleanSetting DOWNLOAD_CHOOSE_PAGES =
            new BooleanSetting("hushgram_download_choose_pages", FALSE, parentsAny(DOWNLOAD_VIDEOS, DOWNLOAD_PHOTOS));

    /**
     * A Download icon in the action row of a feed post, just left of Save, so a tap saves the post
     * without opening its menu (#97). It uses the same save as the menu's Download, and a carousel asks
     * for this page or all of them ({@link app.hushgram.extension.instagram.download.FeedDownloadButton}).
     * It waits for the video or the photo switch, since a tap saves only what they allow. Starts off,
     * so the row stays Instagram's own.
     */
    public static final BooleanSetting FEED_DOWNLOAD_BUTTON =
            new BooleanSetting("hushgram_feed_download_button", FALSE, parentsAny(DOWNLOAD_VIDEOS, DOWNLOAD_PHOTOS));

    /**
     * Under {@link #FEED_DOWNLOAD_BUTTON}: a tap on a carousel saves every page at once, as Save all
     * does, instead of asking for this page or all of them (#190). A post with one page saves as it
     * always did. Starts off, so the icon keeps asking.
     */
    public static final BooleanSetting FEED_DOWNLOAD_ALL_PAGES =
            new BooleanSetting("hushgram_feed_download_all_pages", FALSE, parent(FEED_DOWNLOAD_BUTTON));

    /**
     * A Download icon in the column beside a reel, under Save, so a tap saves the reel without
     * opening its menu (#178). It saves what Download in the reel's menu saves, through the same call
     * ({@link app.hushgram.extension.instagram.download.ReelDownloadButton}), so it waits for Download
     * on reels. Starts off, so the column stays Instagram's own.
     */
    public static final BooleanSetting REEL_DOWNLOAD_BUTTON =
            new BooleanSetting("hushgram_reel_download_button", FALSE, parent(DOWNLOAD_REELS));

    /**
     * A Not interested icon in the column beside a reel (#191), so one tap runs Instagram's own Not
     * interested for that reel without opening its menu. Starts off, so the column stays Instagram's own.
     */
    public static final BooleanSetting REEL_NOT_INTERESTED_BUTTON =
            new BooleanSetting("hushgram_reel_not_interested_button", FALSE);

    /**
     * A Block icon in the column beside a reel (#191). A tap opens Instagram's own block sheet for
     * the reel's owner, confirm included, so nothing is blocked by the tap alone. Starts off, so the
     * column stays Instagram's own.
     */
    public static final BooleanSetting REEL_BLOCK_BUTTON = new BooleanSetting("hushgram_reel_block_button", FALSE);

    /**
     * Hide reels by sound (#191): a sound icon in the column beside a reel, whose tap hides that
     * reel's sound, and the Reels tab leaving out the reels using a hidden sound
     * ({@link app.hushgram.extension.instagram.reels.HiddenSounds}). Starts off, so the column and
     * the tab stay Instagram's own.
     */
    public static final BooleanSetting HIDE_SOUND_REELS = new BooleanSetting("hushgram_hide_sound_reels", FALSE);

    /**
     * A Lens icon in the column beside a reel that sends what the reel shows to Google Lens, so its
     * text can be copied (#303). Starts off, so the column stays Instagram's own.
     */
    public static final BooleanSetting TEXT_BUTTON_ON_REELS = new BooleanSetting("hushgram_text_button_on_reels", FALSE);

    /**
     * The sounds Hide reels by sound hides, a line for each: the sound's id, a tab, its title, a tab
     * and who made it. Empty to start. It reads empty while HushGram is paused, and a backup leaves it
     * out, like Hidden accounts.
     */
    public static final StringSetting HIDDEN_SOUNDS = new StringSetting("hushgram_hidden_sounds", "", false, false);

    /**
     * Hold to download: two fingers (or three) held still on a feed post's picture or video, or on a reel,
     * save what's under them with the same save as Download in the menu
     * ({@link app.hushgram.extension.instagram.download.HoldDownload}). Instagram's one-finger long press
     * stays its own. It waits for the video or the photo switch, as the feed's Download button does, and
     * a reel also needs Download on reels. Starts off, so no touch is watched.
     */
    public static final BooleanSetting HOLD_TO_DOWNLOAD =
            new BooleanSetting("hushgram_hold_to_download", FALSE, parentsAny(DOWNLOAD_VIDEOS, DOWNLOAD_PHOTOS));

    /** Under {@link #HOLD_TO_DOWNLOAD}: how many fingers it waits for. Two to start. */
    public static final EnumSetting<HoldFingers> HOLD_FINGERS =
            new EnumSetting<>("hushgram_hold_fingers", HoldFingers.TWO, parent(HOLD_TO_DOWNLOAD));

    /** Under {@link #HOLD_TO_DOWNLOAD}: how long the fingers stay down before it saves. A second to start. */
    public static final EnumSetting<HoldTime> HOLD_TIME =
            new EnumSetting<>("hushgram_hold_time", HoldTime.ONE_SECOND, parent(HOLD_TO_DOWNLOAD));

    /**
     * Videos, reels and stories start only after a tap: a player's start goes ahead when a tap has
     * just ended, and Instagram's own autoplay check answers no
     * ({@link app.hushgram.extension.instagram.media.TapToPlay}). Nothing Instagram stores is
     * written, so off or paused, Instagram plays as it did. Off to start.
     */
    public static final BooleanSetting TAP_TO_PLAY =
            new BooleanSetting("hushgram_tap_to_play", FALSE);

    /**
     * Where {@link #TAP_TO_PLAY} holds starts: everywhere, everywhere but the Reels viewer, or only
     * there (#39). It starts as everywhere, which is what the switch did before the choice, so no
     * one's Tap to play changes until they pick. It isn't a switch: the switch above it is.
     */
    public static final EnumSetting<TapToPlayScope> TAP_TO_PLAY_SCOPE =
            new EnumSetting<>("hushgram_tap_to_play_scope", TapToPlayScope.EVERYWHERE, parent(TAP_TO_PLAY));

    /**
     * Under {@link #TAP_TO_PLAY}: a reel Instagram's auto scroller moves to waits for a tap too
     * (#175). Off to start, which keeps 784c0d17's chain for #21: the move counts as a tap on the
     * next reel, so auto scroll plays reel after reel. It takes effect at the next move.
     */
    public static final BooleanSetting TAP_TO_PLAY_AUTO_SCROLL_WAITS =
            new BooleanSetting("hushgram_tap_to_play_auto_scroll_waits", FALSE, parent(TAP_TO_PLAY));

    /**
     * What a single tap on a reel does ({@link app.hushgram.extension.instagram.reels.ReelTapAndVolume}):
     * Instagram's own tap, pause, or mute. It starts as Instagram's own, so nothing changes until a
     * choice is made. Paused, it answers Instagram's own like every setting. A reel you paused still
     * resumes on a tap, and a double tap and a long press are untouched.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.reels.ReelTapChoice> REEL_TAP_CHOICE =
            new EnumSetting<>("hushgram_reel_tap_choice", app.hushgram.extension.instagram.reels.ReelTapChoice.DEFAULT);

    /**
     * A press of a volume key doesn't turn a muted reel's sound back on
     * ({@link app.hushgram.extension.instagram.reels.ReelTapAndVolume#keepMuted}). The key isn't
     * swallowed: the phone's volume still goes up, and only Instagram's unmute of the reel is
     * skipped. Volume down and every other path are Instagram's own. Off to start.
     */
    public static final BooleanSetting KEEP_REELS_MUTED =
            new BooleanSetting("hushgram_keep_reels_muted", FALSE);

    /**
     * A reel opened from a post on Home starts with its sound on, through Instagram's own audio
     * state, when the phone's ringer is on and its volume is up, and Home is muted again on the way
     * back ({@link app.hushgram.extension.instagram.reels.ReelSound}). Off to start.
     */
    public static final BooleanSetting START_REELS_WITH_SOUND =
            new BooleanSetting("hushgram_start_reels_with_sound", FALSE);

    /**
     * Every reel starts muted, whichever screen opened it, through the same audio state. The volume
     * keys and the speaker icon unmute it. Wins over {@link #START_REELS_WITH_SOUND} when both are on.
     * Off to start (#342).
     */
    public static final BooleanSetting START_REELS_MUTED =
            new BooleanSetting("hushgram_start_reels_muted", FALSE);

    /**
     * Two fingers zoom into a playing reel, through Instagram's own Reels zoom and its two server
     * flags ({@link app.hushgram.extension.instagram.reels.ReelZoom}). Instagram reads them once a
     * session, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting PINCH_ZOOM_REELS =
            new BooleanSetting("hushgram_pinch_zoom_reels", FALSE, true);

    /**
     * A video or reel over two minutes left partway picks up there the next time a player starts
     * it ({@link app.hushgram.extension.instagram.media.ResumePlayback}). Starts off: it keeps the
     * IDs of the videos you left partway, for 30 days, in the app's own storage.
     */
    public static final BooleanSetting RESUME_LONG_VIDEOS =
            new BooleanSetting("hushgram_resume_long_videos", FALSE);

    /**
     * HDR photos and reels don't brighten the screen: every headroom Instagram asks Android for is
     * none, HDR color mode is the default one, the screen's HDR/SDR ratio reads as none, the screen
     * reads as one without HDR outside the camera's recording helper, and Instagram's extended range
     * video layer is plain sRGB ({@link app.hushgram.extension.instagram.media.HdrBoost}). Read at
     * each request. Off to start.
     */
    public static final BooleanSetting TURN_OFF_HDR_BOOSTS =
            new BooleanSetting("hushgram_turn_off_hdr_boosts", FALSE);

    /**
     * Feed videos and reels stop on their last frame instead of playing again
     * ({@link app.hushgram.extension.instagram.media.StopLooping}). A reel in the Reels viewer still
     * loops while auto scroll or Move on when a reel ends is moving Reels on. Read each time a video
     * starts, so a change shows from the next one. Off to start.
     */
    public static final BooleanSetting STOP_VIDEO_LOOPING =
            new BooleanSetting("hushgram_stop_video_looping", FALSE);

    /**
     * Videos, reels and video stories start at the quality in {@link #PLAYBACK_QUALITY}, through
     * the custom-quality setter of Instagram's DASH format evaluator
     * ({@link app.hushgram.extension.instagram.media.QualityChoice}). The switch starts on, and the
     * quality starts at Instagram's own choice, so nothing changes until one is picked. Nothing
     * Instagram stores is written, so off or paused, Instagram picks the quality as it did.
     */
    public static final BooleanSetting DEFAULT_PLAYBACK_QUALITY =
            new BooleanSetting("hushgram_default_playback_quality", TRUE);

    /**
     * The quality videos start at while {@link #DEFAULT_PLAYBACK_QUALITY} is on: Instagram's own
     * choice, the lowest, a ceiling, or the highest. It starts as Instagram's own, so picking the
     * patch changes nothing until a quality is chosen. It isn't a switch: the switch above it is.
     */
    public static final EnumSetting<PlaybackQuality> PLAYBACK_QUALITY =
            new EnumSetting<>("hushgram_playback_quality", PlaybackQuality.AUTO, parent(DEFAULT_PLAYBACK_QUALITY));

    /**
     * Photos in the feed, carousels and posts opened from a profile load at the largest size the
     * server sent instead of the one Instagram picks for the screen
     * ({@link app.hushgram.extension.instagram.feed.FullResolution}). Read as each photo is bound,
     * so a change shows from the next photo. Off to start: a larger photo can take more data and
     * memory.
     */
    public static final BooleanSetting FULL_RESOLUTION_PHOTOS =
            new BooleanSetting("hushgram_full_resolution_photos", FALSE);

    /**
     * On a phone under 1440 pixels wide, Instagram reports a screen 1440 pixels on its shorter side
     * and asks for photos shown across the screen at that width
     * ({@link app.hushgram.extension.instagram.feed.LargerPhotos}). The reported screen changes
     * after a restart, the asked width from the next photo, so the restart card comes up. Off to
     * start: it takes more data.
     */
    public static final BooleanSetting ASK_FOR_LARGER_PHOTOS =
            new BooleanSetting("hushgram_ask_for_larger_photos", FALSE, true);

    /**
     * Instagram reports a screen 4320 pixels on its shorter side, so the reel covers and grid
     * pictures the server sizes to a third of that width come at full size, and Download cover
     * saves a picked cover that way (#214, {@link app.hushgram.extension.instagram.feed.LargerPhotos}).
     * The reported screen changes after a restart, so the restart card comes up. Off to start:
     * every grid picture loads larger.
     */
    public static final BooleanSetting FULL_SIZE_COVERS =
            new BooleanSetting("hushgram_full_size_covers", FALSE, true);

    /**
     * Photos shown across the screen are asked for at a smaller width, and videos start at the
     * lowest quality, through Full resolution photos' and Default playback quality's hooks
     * ({@link app.hushgram.extension.instagram.media.DataSaver}). Wins over both while it's saving.
     * Off to start.
     */
    public static final BooleanSetting DATA_SAVER =
            new BooleanSetting("hushgram_data_saver", FALSE);

    /**
     * Data saver saves only while the phone is on mobile data. It picks where, not whether, so it
     * isn't a switch Pause turns off: Data saver is. Starts on, so Wi-Fi stays as it is.
     */
    public static final BooleanSetting DATA_SAVER_MOBILE_DATA_ONLY =
            new BooleanSetting("hushgram_data_saver_mobile_data_only", TRUE, parent(DATA_SAVER));

    // ---- Downloads -------------------------------------------------------------------------
    // What every save reads when it starts (app.hushgram.extension.instagram.download), ported
    // with the save pipeline from Hushfacebook 3a473639 with the same types and defaults, keyed
    // hushgram_ and with Instagram's folder name. None of them is a switch: the patch that saves
    // brings its own switch and its settings rows.

    /**
     * The folder every save goes to, under Movies for a video and Pictures for a photo. The
     * settings row and an import keep it clean, and {@link SaveFolder#sanitize} cleans it again
     * wherever it's read, so whatever wrote the store, a save lands in one folder under each.
     * It isn't a switch, and a paused Instagram makes no HushGram saves for it to steer.
     */
    public static final StringSetting SAVE_FOLDER =
            new StringSetting("hushgram_save_folder", SaveFolder.DEFAULT);

    /**
     * A save goes into a folder named for the account that posted, inside {@link #SAVE_FOLDER},
     * when the save knows who posted. Starts off, so saves land where they always have.
     */
    public static final BooleanSetting SAVE_FOLDER_PER_ACCOUNT =
            new BooleanSetting("hushgram_save_folder_per_account", FALSE);

    /**
     * The folder picked with Android's folder picker that every save goes to in place of Movies
     * and Pictures, as the picker's tree address, or empty for none (#333).
     * {@link app.hushgram.extension.instagram.download.SaveTree} keeps
     * the grant that goes with it, which is this phone's alone, so a settings export leaves it out.
     */
    public static final StringSetting SAVE_TREE = new StringSetting("hushgram_save_tree", "");

    /**
     * Every save, photo or video, is named for the account that posted it and when it was posted,
     * with a carousel page's number on the end, when the save knows both (#20). It takes the place
     * of the {@code IG_IMG_} name and of {@link #FILENAME_TEMPLATE}. Starts off, so saves keep the
     * names they always had.
     */
    public static final BooleanSetting SAVE_NAME_BY_POST =
            new BooleanSetting("hushgram_save_name_by_post", FALSE);

    /**
     * A saved photo carries where it came from in its EXIF: the account that posted it, when the
     * post went up and a link to the post. Written only into a JPEG, after the file is saved, and
     * only what the save knows, so a profile picture carries just the account. The picture's pixels
     * are untouched. Starts off, so a save adds nothing to the file.
     */
    public static final BooleanSetting SAVE_PHOTO_METADATA =
            new BooleanSetting("hushgram_save_photo_metadata", FALSE);

    /**
     * The quality a video save asks for: the best the player streams, a ceiling, or the smallest
     * file. Every video save reads it when it starts, and one that finds nothing at or under a
     * ceiling takes the nearest above it. Photos always save whole. Like the folder, it isn't a
     * switch.
     */
    public static final EnumSetting<DownloadQuality> DOWNLOAD_QUALITY =
            new EnumSetting<>("hushgram_download_quality", DownloadQuality.BEST);

    /**
     * Download on a reel, a feed post or a story hands the item's link to an app picked from the
     * share sheet, a downloader such as Seal, instead of saving it here. Starts off.
     */
    public static final BooleanSetting SEND_DOWNLOADS_TO_APP =
            new BooleanSetting("hushgram_send_downloads_to_app", FALSE);

    /**
     * A reel's menu and a feed video's menu get Open in another player, which hands the video's
     * address to a player picked from Android's chooser. Starts off.
     */
    public static final BooleanSetting OPEN_IN_PLAYER =
            new BooleanSetting("hushgram_open_in_player", FALSE);

    /**
     * A feed post's menu gets Details: when it went up, who posted it, its media ID and the size a
     * Download would save, with buttons that copy the file's direct link, the username and the caption.
     * Starts off.
     */
    public static final BooleanSetting POST_DETAILS =
            new BooleanSetting("hushgram_post_details", FALSE);

    /**
     * Video saves keep to what other apps open: H.264 video with AAC-LC or HE-AAC sound, within
     * {@link #DOWNLOAD_QUALITY}, or the app's single MP4 file when the manifest has no such pair.
     * The sharpest version Meta streams is often AV1 with xHE-AAC sound, which Gallery and VLC play
     * and WhatsApp turns down. Off by default, so a save keeps the sharpest.
     */
    public static final BooleanSetting DOWNLOAD_COMPATIBLE =
            new BooleanSetting("hushgram_download_compatible", FALSE);

    /**
     * Saves what {@link #DOWNLOAD_COMPATIBLE} saves, whether that's on or not, and a video whose full
     * size Instagram has only as VP9 or AV1 is converted to H.264 on the phone rather than saved at
     * the smaller H.264 size (#236). Slower, and a picture over three minutes or in HDR keeps the
     * smaller H.264. Starts off.
     */
    public static final BooleanSetting DOWNLOAD_CONVERT =
            new BooleanSetting("hushgram_download_convert", FALSE);

    /**
     * Passes AV1 tracks over, for a phone that plays AV1 badly (#379): a video Instagram has at
     * full size only as AV1 is saved as VP9 when it has that size as VP9, or at its largest H.264
     * size, and {@link #DOWNLOAD_CONVERT} converts only VP9. Starts off.
     */
    public static final BooleanSetting DOWNLOAD_NO_AV1 =
            new BooleanSetting("hushgram_download_no_av1", FALSE);

    /**
     * Passes HDR tracks over (#352), 10-bit ones included: a video Instagram has at full size only
     * in HDR is saved at its largest standard range size, so an app that plays HDR as SDR with a
     * yellow or green cast, Messenger among them, shows the save as the reel looked. A cover frame
     * is taken from a standard range picture too. Starts off.
     */
    public static final BooleanSetting DOWNLOAD_NO_HDR =
            new BooleanSetting("hushgram_download_no_hdr", FALSE);

    /**
     * A save remembers what reached the gallery, and the same post, carousel page, reel, story,
     * cover or recording isn't saved again while its file is still where the save put it (#150).
     * Save all saves only the pages not saved before. Asked again within 30 seconds of being
     * told, it saves anyway. The list stays in HushGram's private files, out of settings backups
     * and the diagnostic report. Starts off, so every tap saves the way it always has.
     */
    public static final BooleanSetting DOWNLOAD_SKIP_SAVED =
            new BooleanSetting("hushgram_download_skip_saved", FALSE);

    /**
     * A list, kept only on this phone, of the posts, reels, stories and profiles that came up on
     * screen, newest first, about 500 rows (#228). It reads what Instagram already loaded, sends
     * nothing and reads nothing about other accounts. Turning it off stops recording at once, and
     * Clear history wipes the list. The list stays out of settings backups and the diagnostic
     * report. Starts off.
     */
    public static final BooleanSetting VIEWING_HISTORY =
            new BooleanSetting("hushgram_viewing_history", FALSE);

    /**
     * The name a saved video gets: {date}, {video_id}, {owner} and {posted} fill in per save, the
     * last three only when the save knows them, and the default is IG_VID_ and the date and time.
     * Photos keep their IG_IMG_ names. Cleaned like the folder wherever it's read
     * ({@link FileNameTemplate#sanitize}), and like the folder, it isn't a switch.
     */
    public static final StringSetting FILENAME_TEMPLATE =
            new StringSetting("hushgram_filename_template", FileNameTemplate.DEFAULT);

    /**
     * The Before you sign in notice at the top of the settings screen was tapped away. It isn't a
     * switch: a pause doesn't bring the notice back, and a settings backup leaves it out.
     */
    public static final BooleanSetting SIGN_IN_NOTICE_HIDDEN =
            new BooleanSetting("hushgram_sign_in_notice_hidden", FALSE, false, false);

}
