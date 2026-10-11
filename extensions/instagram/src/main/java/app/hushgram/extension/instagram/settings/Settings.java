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
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.media.TapToPlayScope;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.instagram.stories.StoryTimeMode;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.EnumSetting;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.StringSetting;

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
     * Leaves the HushGram row out of Instagram's own settings menu while a tab long press opens
     * HushGram (#84). Off to start. The row is left out only while the chosen tab is on a button
     * Instagram built ({@link NavigationSettings#opensFromATab}), so turning the long press off,
     * or Pause, brings the row back and there's always a way in.
     */
    public static final BooleanSetting HIDE_MENU_ROW =
            new BooleanSetting("hushgram_hide_menu_row", FALSE);

    /**
     * HushGram's settings list their categories, and a tap opens one as its own page. Search still
     * looks through every category. On to start (#80, #100). The button under the status card, or
     * this switch, makes the page one long list. A choice about the page itself, so it's read
     * saved, not through Pause.
     */
    public static final BooleanSetting CATEGORY_PAGES =
            new BooleanSetting("hushgram_category_pages", TRUE);

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
     * Instagram's event uploads, to its own logging endpoint and to Facebook's graph endpoint, go
     * to an address on the phone that refuses them. The uploader reads the address when it starts,
     * so a change shows after a restart.
     */
    public static final BooleanSetting DISABLE_ANALYTICS =
            new BooleanSetting("hushgram_disable_analytics", TRUE, true);

    /**
     * The screen an old build shows to push an update, which a patched build can't install from
     * the Play Store. Instagram checks when its main screen opens.
     */
    public static final BooleanSetting REMOVE_BUILD_EXPIRED_POPUP =
            new BooleanSetting("hushgram_remove_build_expired_popup", TRUE);

    /**
     * A long press on the Home tab opens Instagram's developer options. Its patch stays out of the
     * default selection, so a build that has it asked for it, and the switch starts on.
     */
    public static final BooleanSetting OPEN_DEVELOPER_OPTIONS =
            new BooleanSetting("hushgram_open_developer_options", TRUE);

    /**
     * MetaConfig's list shows the names HushGram ships in place of numbers, when no list is imported.
     * Off goes back to Instagram's own labels. Only the labels change, never Instagram's schema.
     */
    public static final BooleanSetting USE_FLAG_NAMES =
            new BooleanSetting("hushgram_use_flag_names", TRUE);

    /**
     * Shows Import, Restore and Discard for native overrides, and OverrideImport checks it again
     * before it reads the store. Off by default, and deliberately not a patch switch: Pause and a
     * settings import never turn it on, and it answers off while HushGram is paused.
     */
    public static final BooleanSetting ALLOW_OVERRIDE_IMPORT =
            new BooleanSetting("hushgram_allow_override_import", FALSE);

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
     * A story plays again from the start when it ends, instead of the viewer moving on
     * ({@link app.hushgram.extension.instagram.stories.StoryLoop}). While it's on it wins over
     * {@link #BLOCK_STORY_AUTO_ADVANCE}. Off to start.
     */
    public static final BooleanSetting LOOP_STORIES =
            new BooleanSetting("hushgram_loop_stories", FALSE);

    /**
     * Stories open with their sound on, through Instagram's own audio state, when the phone's ringer
     * is on and its volume is up ({@link app.hushgram.extension.instagram.stories.StorySound}). Off
     * to start.
     */
    public static final BooleanSetting START_STORIES_WITH_SOUND =
            new BooleanSetting("hushgram_start_stories_with_sound", FALSE);

    /**
     * The first feed video of a Home session starts with its sound on, through the video's own sound
     * flag, when the phone's ringer is on and its volume is up
     * ({@link app.hushgram.extension.instagram.feed.FeedSound}). Off to start.
     */
    public static final BooleanSetting START_FEED_VIDEOS_WITH_SOUND =
            new BooleanSetting("hushgram_start_feed_videos_with_sound", FALSE);

    /**
     * A long press on a feed photo shows it zoomed in around the finger until the finger lifts, in
     * place of Instagram's own long press there ({@link app.hushgram.extension.instagram.feed.LongPressZoom}).
     * Off to start.
     */
    public static final BooleanSetting LONG_PRESS_TO_ZOOM =
            new BooleanSetting("hushgram_long_press_to_zoom", FALSE);

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
     * The seen receipt a chat sends when you open it, the one that puts Seen under the other
     * person's message ({@link app.hushgram.extension.instagram.direct.ThreadSeen}). Read each time
     * Instagram goes to send one, so a change applies to the next receipt. Off to start.
     */
    public static final BooleanSetting READ_WITHOUT_SEEN_RECEIPT =
            new BooleanSetting("hushgram_read_without_seen_receipt", FALSE);

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
     * The chats that stay locked one at a time, each as its thread id and the name it had when you
     * locked it ({@link app.hushgram.extension.instagram.direct.ChatLocks}), a line for each. It
     * isn't a switch, and a backup leaves it out, since the ids are only good on this phone's account.
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

    static {
        // The locks keep answering what you chose while HushGram is paused or in safe mode, so
        // neither one, nor the marker file that pauses it from outside, gets around them.
        Setting.keepWhenPaused(LOCK_MESSAGES, LOCK_APP, LOCK_AGAIN, LOCKED_CHATS);
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
     * A call started from a chat waits for a question first
     * ({@link app.hushgram.extension.instagram.direct.CallConfirm}). Off to start.
     */
    public static final BooleanSetting ASK_BEFORE_CALL = new BooleanSetting("hushgram_ask_before_call", FALSE);

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

    /** The rows of products and live shopping in the home feed. */
    public static final BooleanSetting HIDE_FEED_SHOPPING =
            new BooleanSetting("hushgram_hide_feed_shopping", TRUE);

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
     * The rings in the stories row at the top of Home are drawn at the size in
     * {@link #STORY_RING_SCALE} ({@link app.hushgram.extension.instagram.stories.StoryRing}). The
     * switch starts on, and the size starts as Instagram's own, so nothing changes until a size is
     * picked. Nothing Instagram stores is written, so off or paused, the rings are Instagram's
     * size again once Home is built anew.
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
     * With {@link #START_ON_FOLLOWING} on, Instagram's logo stays at the top of Home where the picked
     * feed's name (Following) would show. Its arrow still opens the feed picker. Off to start, so the
     * name shows until you choose this.
     */
    public static final BooleanSetting LOGO_ON_FOLLOWING =
            new BooleanSetting("hushgram_logo_on_following", FALSE, true, parent(START_ON_FOLLOWING));

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
     * each time the profile or Edit featured opens. Off to start, like every part added to Hide
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
     * What you open from search stays out of Recent, in the app's cache and on Instagram's side
     * ({@link app.hushgram.extension.instagram.explore.RecentSearches}). Read at each save. Off to start.
     */
    public static final BooleanSetting DONT_SAVE_RECENT_SEARCHES =
            new BooleanSetting("hushgram_dont_save_recent_searches", FALSE);

    /**
     * The row of notes at the top of your messages, its Map bubble included
     * ({@link app.hushgram.extension.instagram.direct.NotesRow}). Read each time Instagram works out
     * your messages again, so a change shows the next time they update. Off to start.
     */
    public static final BooleanSetting HIDE_NOTES_ROW =
            new BooleanSetting("hushgram_hide_notes_row", FALSE);

    /**
     * The Accounts to follow section under your chats
     * ({@link app.hushgram.extension.instagram.direct.InboxSuggestions}). Read each time your
     * messages build that section. Off to start.
     */
    public static final BooleanSetting HIDE_INBOX_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_inbox_suggestions", FALSE);

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
     * Under {@link #GLASS_TAB_BAR}: the pill blurs what's behind it, on Android 12 and newer. Off, or
     * on an older Android, it's a frosted tint without the blur.
     */
    public static final BooleanSetting GLASS_TAB_BAR_BLUR =
            new BooleanSetting("hushgram_glass_tab_bar_blur", TRUE, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR}: Home and the other tabs run down behind the bar, so the glass has
     * the real content to blur and no strip of Instagram's own colour sits behind the pill. Reels keeps
     * its own controls above the pill. Off, the content stops above the bar.
     */
    public static final BooleanSetting GLASS_TAB_BAR_FLOAT =
            new BooleanSetting("hushgram_glass_tab_bar_float", TRUE, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR}: a light tick each time the highlight slides across a tab, so the
     * buzz follows the bar as it moves, whether you tapped a tab or swiped to another screen.
     */
    public static final BooleanSetting GLASS_TAB_BAR_HAPTICS =
            new BooleanSetting("hushgram_glass_tab_bar_haptics", TRUE, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR}: scrolling a list down slides the pill off the bottom of the screen, and
     * scrolling up, reaching the top, changing tab or pressing Back brings it back
     * ({@link app.hushgram.extension.instagram.misc.GlassTabBar}). Off to start.
     */
    public static final BooleanSetting GLASS_TAB_BAR_HIDE_ON_SCROLL =
            new BooleanSetting("hushgram_glass_tab_bar_hide_on_scroll", FALSE, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR_HAPTICS}: which tick plays ({@link app.hushgram.extension.instagram.misc.HapticStyle}).
     * Starts on the phone's own click. A choice because only a hand can say which feels right.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.HapticStyle> GLASS_TAB_BAR_HAPTIC_STYLE =
            new EnumSetting<>("hushgram_glass_tab_bar_haptic_style",
                    app.hushgram.extension.instagram.misc.HapticStyle.SYSTEM, parent(GLASS_TAB_BAR_HAPTICS));

    /**
     * Under {@link #GLASS_TAB_BAR}: how see-through the pill is ({@link app.hushgram.extension.instagram.misc.GlassOpacity}).
     * Starts on the look the bar has always had. A change shows after a restart.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.GlassOpacity> GLASS_TAB_BAR_OPACITY =
            new EnumSetting<>("hushgram_glass_tab_bar_opacity",
                    app.hushgram.extension.instagram.misc.GlassOpacity.STANDARD, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR}: how tall the pill is ({@link app.hushgram.extension.instagram.misc.GlassHeight}).
     * Starts on the height the bar has always had. A change shows after a restart.
     */
    public static final EnumSetting<app.hushgram.extension.instagram.misc.GlassHeight> GLASS_TAB_BAR_HEIGHT =
            new EnumSetting<>("hushgram_glass_tab_bar_height",
                    app.hushgram.extension.instagram.misc.GlassHeight.STANDARD, parent(GLASS_TAB_BAR));

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
     * Every emoji draws in Google's style: EmojiCompat is asked to replace every emoji it knows
     * from the font it loads from Google Play services, not only the ones the phone lacks
     * ({@link app.hushgram.extension.instagram.misc.EmojiStyle}). Read for each piece of text, but
     * text already drawn keeps its style, so a change shows fully after a restart. Off to start.
     */
    public static final BooleanSetting NOTO_EMOJI =
            new BooleanSetting("hushgram_noto_emoji", FALSE, true);

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
     * Suggested for you and the Discover people button on profiles
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions}). Read each time Instagram
     * builds or binds a profile's header, so off or paused, the suggestions are back on the next one.
     * Off to start.
     */
    public static final BooleanSetting HIDE_PROFILE_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_profile_suggestions", FALSE);

    /**
     * The row of story highlights on profiles
     * ({@link app.hushgram.extension.instagram.profile.ProfileHighlights}). Read each time Instagram
     * lays out a profile's header, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_HIGHLIGHTS =
            new BooleanSetting("hushgram_hide_highlights", FALSE);

    /**
     * The Threads button on profiles' top bar
     * ({@link app.hushgram.extension.instagram.profile.ThreadsButton}). Read each time Instagram
     * builds a profile's top bar, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_THREADS_BUTTON =
            new BooleanSetting("hushgram_hide_threads_button", FALSE);

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

    /** An explicit Copy action for original comment text. Off until enabled. */
    public static final BooleanSetting COPY_COMMENTS =
            new BooleanSetting("hushgram_copy_comments", FALSE);

    /**
     * A selected comment's menu gets Copy username, for the account that wrote it
     * ({@link app.hushgram.extension.instagram.comment.CommentAuthor}). Off until enabled.
     */
    public static final BooleanSetting COPY_COMMENT_AUTHORS =
            new BooleanSetting("hushgram_copy_comment_authors", FALSE);

    /** An explicit Save action for a photo the comment itself carries. Off until enabled. */
    public static final BooleanSetting SAVE_COMMENT_PHOTOS =
            new BooleanSetting("hushgram_save_comment_photos", FALSE);

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
     * A Search button on Home's header that opens Instagram's own Search tab, with Hide the Search tab
     * on too where the tabs don't swipe ({@link app.hushgram.extension.instagram.reels.SearchHeaderButton}).
     * The header builds it as it draws, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting SEARCH_BUTTON_ON_HOME =
            new BooleanSetting("hushgram_search_button_on_home", FALSE, true);

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
     * A reel that doesn't fill the screen shows a blurred copy of itself in the bars above and below
     * it, or beside it, instead of black ({@link app.hushgram.extension.instagram.reels.ReelBlurBars}).
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
     * A finger can't move the Reels viewer on to the next reel, and a pull down doesn't load new
     * ones ({@link app.hushgram.extension.instagram.reels.ReelScrolling}). A viewer turns its pager
     * off as it opens, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting STOP_REELS_SCROLLING =
            new BooleanSetting("hushgram_stop_reels_scrolling", FALSE, true);

    /**
     * After 20 reels in a session, swiping in Reels stops until Instagram has been in the
     * background for 15 minutes ({@link app.hushgram.extension.instagram.reels.ReelScrolling}). Read
     * at each new reel. Off to start.
     */
    public static final BooleanSetting REEL_CAP =
            new BooleanSetting("hushgram_reel_cap", FALSE);

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
     * A Download icon in the action row of a feed post, just left of Save, so a tap saves the post
     * without opening its menu (#97). It uses the same save as the menu's Download, and a carousel asks
     * for this page or all of them ({@link app.hushgram.extension.instagram.download.FeedDownloadButton}).
     * It waits for the video or the photo switch, since a tap saves only what they allow. Starts off,
     * so the row stays Instagram's own.
     */
    public static final BooleanSetting FEED_DOWNLOAD_BUTTON =
            new BooleanSetting("hushgram_feed_download_button", FALSE, parentsAny(DOWNLOAD_VIDEOS, DOWNLOAD_PHOTOS));

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
     * A video or reel over two minutes left partway picks up there the next time a player starts
     * it ({@link app.hushgram.extension.instagram.media.ResumePlayback}). Starts off: it keeps the
     * IDs of the videos you left partway, for 30 days, in the app's own storage.
     */
    public static final BooleanSetting RESUME_LONG_VIDEOS =
            new BooleanSetting("hushgram_resume_long_videos", FALSE);

    /**
     * HDR photos and reels don't brighten the screen: every headroom Instagram asks Android for is
     * none, and HDR color mode is the default one ({@link app.hushgram.extension.instagram.media.HdrBoost}).
     * Read at each request. Off to start.
     */
    public static final BooleanSetting TURN_OFF_HDR_BOOSTS =
            new BooleanSetting("hushgram_turn_off_hdr_boosts", FALSE);

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
     * after a restart, the asked width from the next photo. Off to start: it takes more data.
     */
    public static final BooleanSetting ASK_FOR_LARGER_PHOTOS =
            new BooleanSetting("hushgram_ask_for_larger_photos", FALSE);

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
     * Every save, photo or video, is named for the account that posted it and when it was posted,
     * with a carousel page's number on the end, when the save knows both (#20). It takes the place
     * of the {@code IG_IMG_} name and of {@link #FILENAME_TEMPLATE}. Starts off, so saves keep the
     * names they always had.
     */
    public static final BooleanSetting SAVE_NAME_BY_POST =
            new BooleanSetting("hushgram_save_name_by_post", FALSE);

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
