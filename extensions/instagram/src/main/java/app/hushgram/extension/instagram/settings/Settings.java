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

import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.download.FileNameTemplate;
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.EnumSetting;
import app.hushgram.extension.shared.settings.StringSetting;

/**
 * The switches behind the hooks that ask before they act.
 *
 * <p>A switch's default is the second argument of its {@link BooleanSetting}. Picking a patch in
 * Morphe Manager is the choice to use it, and the switch is the way to turn it off again without
 * patching a second time. While HushGram is paused, safe mode included
 * ({@link app.hushgram.extension.shared.settings.HushgramPause}), a switch answers off and the hook
 * behind it takes Instagram's own path.
 */
@SuppressWarnings("unused")
public class Settings extends BaseSettings {
    /** Navigation listeners are installed at native tab binding, so the choice applies after restart. */
    public static final EnumSetting<NavigationTarget> NAVIGATION_SETTINGS_TARGET =
            new EnumSetting<>("hushgram_navigation_settings_target", NavigationTarget.OFF, true);

    /** Sponsored posts, reels and stories: the ad injector is told no ad went in. */
    public static final BooleanSetting HIDE_ADS =
            new BooleanSetting("hushgram_hide_ads", TRUE);

    /** igsh, igshid, utm_source and the other tracking keys come off links that leave Instagram. */
    public static final BooleanSetting SANITIZE_SHARING_LINKS =
            new BooleanSetting("hushgram_sanitize_sharing_links", TRUE);

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

    /** A long press on the Home tab opens Instagram's developer options. Its patch is opt-in. */
    public static final BooleanSetting OPEN_DEVELOPER_OPTIONS =
            new BooleanSetting("hushgram_open_developer_options", TRUE);

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
     * watched may come back.
     */
    public static final BooleanSetting DONT_SEND_REEL_WATCH_HISTORY =
            new BooleanSetting("hushgram_dont_send_reel_watch_history", TRUE);

    /**
     * A story whose photo timer ran out or whose video ended stays on screen until you tap or
     * swipe, instead of the viewer moving on by itself.
     */
    public static final BooleanSetting BLOCK_STORY_AUTO_ADVANCE =
            new BooleanSetting("hushgram_block_story_auto_advance", TRUE);

    /**
     * A story's header shows the date and time it was posted, in the phone's language and 12 or
     * 24-hour setting, instead of how long ago
     * ({@link app.hushgram.extension.instagram.stories.StoryTime}). Read as each header is drawn,
     * so a change shows from the next story. The patch is off in the default selection, so a build
     * that has it asked for it, and the switch starts on.
     */
    public static final BooleanSetting SHOW_STORY_TIME =
            new BooleanSetting("hushgram_show_story_time", TRUE);

    /**
     * A story plays again from the start when it ends, instead of the viewer moving on
     * ({@link app.hushgram.extension.instagram.stories.StoryLoop}). While it's on it wins over
     * {@link #BLOCK_STORY_AUTO_ADVANCE}. The patch is off in the default selection, so a build that
     * has it asked for it, and the switch starts on.
     */
    public static final BooleanSetting LOOP_STORIES =
            new BooleanSetting("hushgram_loop_stories", TRUE);

    /**
     * The stories you watch, which Instagram posts to media/seen/ to put you on their viewer lists.
     * Held back, you stay off them. Replies and reactions still show you.
     */
    public static final BooleanSetting VIEW_STORIES_ANONYMOUSLY =
            new BooleanSetting("hushgram_view_stories_anonymously", TRUE);

    /**
     * The text of messages other people send is kept on this phone, and the ones they later delete
     * are listed in HushGram's settings ({@link app.hushgram.extension.instagram.direct.SavedMessages}).
     * Starts on once the patch is picked.
     */
    public static final BooleanSetting SAVE_DELETED_MESSAGES =
            new BooleanSetting("hushgram_save_deleted_messages", TRUE);

    /** A separate opt-in for the direct visual-media receipt. Ordinary chat receipts stay native. */
    public static final BooleanSetting VIEW_DM_MEDIA_ANONYMOUSLY =
            new BooleanSetting("hushgram_view_dm_media_anonymously", FALSE);

    /**
     * The Mark as seen button in the story viewer's header
     * ({@link app.hushgram.extension.instagram.stories.StorySeenButton}). Off to start. A story you
     * tap it on is sent as seen while the rest stay held back. Read each time a story is shown and
     * each time a batch of views goes to be sent.
     */
    public static final BooleanSetting MARK_STORIES_SEEN =
            new BooleanSetting("hushgram_mark_stories_seen", FALSE);

    /**
     * The rows of suggested reels between posts in the home feed, and the other feed units that
     * open the Reels viewer. A reel someone you follow posts is a post and stays.
     */
    public static final BooleanSetting HIDE_FEED_REELS =
            new BooleanSetting("hushgram_hide_feed_reels", TRUE);

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

    /** Stories in the tray at the top of Home from accounts you don't follow, and accounts it suggests. */
    public static final BooleanSetting HIDE_SUGGESTED_STORIES =
            new BooleanSetting("hushgram_hide_suggested_stories", TRUE);

    /** The whole row of stories at the top of Home, Your story included. Off until you turn it on. */
    public static final BooleanSetting HIDE_STORIES_TRAY =
            new BooleanSetting("hushgram_hide_stories_tray", FALSE);

    /**
     * The rings in the stories row at the top of Home are drawn at the size in
     * {@link #STORY_RING_SCALE} ({@link app.hushgram.extension.instagram.stories.StoryRing}). The
     * patch is off in the default selection, so a build that has it asked for it, and the switch
     * starts on. Nothing Instagram stores is written, so off or paused, the rings are Instagram's
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
     * while you haven't picked a feed there. Instagram remembers a pick from then on.
     */
    public static final BooleanSetting START_ON_FOLLOWING =
            new BooleanSetting("hushgram_start_on_following", TRUE, true);

    /**
     * With {@link #START_ON_FOLLOWING} on, Home keeps to accounts you follow: For you leaves the
     * feed picker, and a remembered For you opens Following. Off to start, so For you stays a tap
     * away until you choose this.
     */
    public static final BooleanSetting ONLY_FOLLOWING =
            new BooleanSetting("hushgram_only_following", FALSE, true, parent(START_ON_FOLLOWING));

    /**
     * Meta AI in the search bars: the Search tab's ("Search with Meta AI") and the one at the top of
     * your messages ("Search or ask Meta AI"), and the "Ask a follow-up…" bar under search results.
     */
    public static final BooleanSetting HIDE_META_AI_SEARCH =
            new BooleanSetting("hushgram_hide_meta_ai_search", TRUE, true);

    /** Meta AI's units in the home feed: Vibes videos, Meta AI chats and Imagine pictures. */
    public static final BooleanSetting HIDE_META_AI_POSTS =
            new BooleanSetting("hushgram_hide_meta_ai_posts", TRUE);

    /** The grid of posts and reels under the Search tab's bar. Search and its results stay. */
    public static final BooleanSetting HIDE_EXPLORE_GRID =
            new BooleanSetting("hushgram_hide_explore_grid", TRUE);

    /**
     * The row of notes at the top of your messages, its Map bubble included
     * ({@link app.hushgram.extension.instagram.direct.NotesRow}). Read each time Instagram works out
     * your messages again, so a change shows the next time they update. Off to start.
     */
    public static final BooleanSetting HIDE_NOTES_ROW =
            new BooleanSetting("hushgram_hide_notes_row", FALSE);

    /**
     * The New group button beside the share sheet's search bar, whichever form Instagram gives it,
     * and the button that sends to the people you picked there as a group.
     */
    public static final BooleanSetting HIDE_SHARE_SHEET_GROUP =
            new BooleanSetting("hushgram_hide_share_sheet_group", TRUE);

    /**
     * The Repost button under posts and beside reels, with its count: every post and reel reads as
     * one that can't be reposted ({@link app.hushgram.extension.instagram.share.RepostButton}).
     * Nothing Instagram stores is written, so off or paused, Repost is back on the next post drawn.
     */
    public static final BooleanSetting HIDE_REPOST_BUTTON =
            new BooleanSetting("hushgram_hide_repost_button", TRUE);

    /**
     * The space Instagram leaves under its tab bar for a navigation bar the phone says isn't there:
     * when the phone reports no bottom inset, Instagram's guess from the system's navigation bar
     * height becomes 0 ({@link app.hushgram.extension.instagram.misc.BottomSpace}). Read each time
     * Instagram lays out its window's insets.
     */
    public static final BooleanSetting REMOVE_BOTTOM_SPACE =
            new BooleanSetting("hushgram_remove_bottom_space", TRUE, true);

    /**
     * Instagram's tab bar is drawn as a floating, rounded glass pill
     * ({@link app.hushgram.extension.instagram.misc.GlassTabBar}). The patch is off in the default
     * selection, so a build that has it asked for it, and the switch starts on. Instagram builds
     * the bar as it starts, so a change takes a restart.
     */
    public static final BooleanSetting GLASS_TAB_BAR =
            new BooleanSetting("hushgram_glass_tab_bar", TRUE, true);

    /**
     * Under {@link #GLASS_TAB_BAR}: the pill blurs what's behind it, on Android 12 and newer. Off, or
     * on an older Android, it's a frosted tint without the blur.
     */
    public static final BooleanSetting GLASS_TAB_BAR_BLUR =
            new BooleanSetting("hushgram_glass_tab_bar_blur", TRUE, true, parent(GLASS_TAB_BAR));

    /**
     * Under {@link #GLASS_TAB_BAR}: Home, Reels and the other tabs run down behind the bar, so the glass
     * has the real content to blur. Reels' own buttons and caption end up under the bar as well, which
     * is why it starts off.
     */
    public static final BooleanSetting GLASS_TAB_BAR_FLOAT =
            new BooleanSetting("hushgram_glass_tab_bar_float", FALSE, true, parent(GLASS_TAB_BAR));

    /**
     * Follows you or Doesn't follow you beside the name on someone's profile
     * ({@link app.hushgram.extension.instagram.profile.FriendshipStatus}). Read each time Instagram
     * binds a profile's name.
     */
    public static final BooleanSetting SHOW_FRIENDSHIP_STATUS =
            new BooleanSetting("hushgram_show_friendship_status", TRUE);

    /**
     * Doesn't follow you on the rows of your own Following list
     * ({@link app.hushgram.extension.instagram.profile.FollowingList}). Off to start. Read each time
     * Instagram binds a row of a follow list.
     */
    public static final BooleanSetting MARK_FOLLOWING_LIST =
            new BooleanSetting("hushgram_mark_following_list", FALSE);

    /**
     * Suggested for you and the Discover people button on profiles
     * ({@link app.hushgram.extension.instagram.profile.ProfileSuggestions}). Read each time Instagram
     * builds or binds a profile's header, so off or paused, the suggestions are back on the next one.
     */
    public static final BooleanSetting HIDE_PROFILE_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_profile_suggestions", TRUE);

    /**
     * The row of story highlights on profiles
     * ({@link app.hushgram.extension.instagram.profile.ProfileHighlights}). Read each time Instagram
     * lays out a profile's header, so a change shows on the next profile opened. Off to start.
     */
    public static final BooleanSetting HIDE_HIGHLIGHTS =
            new BooleanSetting("hushgram_hide_highlights", FALSE);

    /**
     * A sideways swipe on Home that would open the camera
     * ({@link app.hushgram.extension.instagram.feed.SwipeToCreate}). Read at each step of a swipe,
     * so a change shows on the next one. Off to start.
     */
    public static final BooleanSetting STOP_SWIPE_TO_CREATE =
            new BooleanSetting("hushgram_stop_swipe_to_create", FALSE);

    /**
     * The cards of accounts and creators to follow that Instagram puts between reels
     * ({@link app.hushgram.extension.instagram.reels.ReelsSuggestions}). Read as each page of
     * reels arrives, so a change shows from the next page.
     */
    public static final BooleanSetting HIDE_REELS_SUGGESTIONS =
            new BooleanSetting("hushgram_hide_reels_suggestions", TRUE);

    /** An explicit Copy action for original comment text. Off until enabled. */
    public static final BooleanSetting COPY_COMMENTS =
            new BooleanSetting("hushgram_copy_comments", FALSE);

    /** An explicit Save action for a photo the comment itself carries. Off until enabled. */
    public static final BooleanSetting SAVE_COMMENT_PHOTOS =
            new BooleanSetting("hushgram_save_comment_photos", FALSE);

    /** The Follow button beside a reel's author in the Reels viewer. */
    public static final BooleanSetting HIDE_REEL_FOLLOW_BUTTON =
            new BooleanSetting("hushgram_hide_reel_follow_button", TRUE);

    /**
     * The pills on a reel that prompt you to make something (Edits, a template, a creative tool)
     * or promote something (Meta AI, Ray-Ban Meta glasses, an affiliate link). A live badge, a
     * state-controlled media label and the other labels there stay.
     */
    public static final BooleanSetting HIDE_REEL_CHIPS =
            new BooleanSetting("hushgram_hide_reel_chips", TRUE);

    /**
     * What friends did with a reel, shown over it: the bubbles above the author of friends who
     * liked, commented or follow them, the comment Instagram previews and the row of friends who
     * saw it.
     */
    public static final BooleanSetting HIDE_REEL_SOCIAL_FOOTER =
            new BooleanSetting("hushgram_hide_reel_social_footer", TRUE);

    /**
     * Download in every reel's more menu, saving the reel through the save pipeline below instead
     * of Instagram's own save, which only some reels offer and which stamps a watermark on.
     */
    public static final BooleanSetting DOWNLOAD_REELS =
            new BooleanSetting("hushgram_download_reels", TRUE);

    /**
     * A double tap on a post in the feed or on a reel doesn't like it, where the two switches under
     * it say so. The patch is off in the default selection, so a build that has it asked for it, and
     * the switches start on.
     */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE =
            new BooleanSetting("hushgram_turn_off_double_tap_like", TRUE);

    /** Under {@link #TURN_OFF_DOUBLE_TAP_LIKE}: a double tap on a post in the feed doesn't like it. */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS =
            new BooleanSetting("hushgram_turn_off_double_tap_like_on_posts", TRUE, parent(TURN_OFF_DOUBLE_TAP_LIKE));

    /** Under {@link #TURN_OFF_DOUBLE_TAP_LIKE}: a double tap on a reel doesn't like it. */
    public static final BooleanSetting TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS =
            new BooleanSetting("hushgram_turn_off_double_tap_like_on_reels", TRUE, parent(TURN_OFF_DOUBLE_TAP_LIKE));

    /**
     * Reels is off the tab bar, and a start or a switch meant for it lands on Home. Instagram builds
     * its tab list as it starts, so a change takes a restart. The patch is off in the default
     * selection, so a build that has it asked for it, and the switch starts on.
     */
    public static final BooleanSetting HIDE_REELS_TAB =
            new BooleanSetting("hushgram_hide_reels_tab", TRUE, true);

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
     * is shown, so a change shows from the next reels; the time follows the switch at once. The
     * patch is off in the default selection, so a build that has it asked for it, and the switch
     * starts on.
     */
    public static final BooleanSetting REEL_SEEK_BAR =
            new BooleanSetting("hushgram_reel_seek_bar", TRUE);

    /** A visible thumb on the native ordinary-Reel scrubber, independent of keeping its bar shown. */
    public static final BooleanSetting REEL_SEEK_THUMB =
            new BooleanSetting("hushgram_reel_seek_thumb", FALSE);

    /**
     * Instagram's auto scroll in Reels stays the way you last set it after a restart and after
     * you leave Reels ({@link app.hushgram.extension.instagram.reels.ReelAutoScroll}). Read each
     * time Instagram asks whether auto scroll is on, so a change shows from the next reel. The
     * patch is off in the default selection, so a build that has it asked for it, and the switch
     * starts on.
     */
    public static final BooleanSetting KEEP_REEL_AUTO_SCROLL =
            new BooleanSetting("hushgram_keep_reel_auto_scroll", TRUE);

    /**
     * A finger can't move the Reels viewer on to the next reel, and a pull down doesn't load new
     * ones ({@link app.hushgram.extension.instagram.reels.ReelScrolling}). A viewer turns its pager
     * off as it opens, so a change takes a restart. Off to start.
     */
    public static final BooleanSetting STOP_REELS_SCROLLING =
            new BooleanSetting("hushgram_stop_reels_scrolling", FALSE, true);

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
     * server lets it show, and a tap on it saves the same way.
     */
    public static final BooleanSetting DOWNLOAD_VIDEOS =
            new BooleanSetting("hushgram_download_videos", TRUE);

    /**
     * The same Download for a photo: someone else's photo post, and a carousel on a photo page,
     * saved at the largest size Instagram lists. Off to start.
     */
    public static final BooleanSetting DOWNLOAD_PHOTOS =
            new BooleanSetting("hushgram_download_photos", FALSE);

    /**
     * Videos, reels and stories start only after a tap: a player's start goes ahead when a tap has
     * just ended, and Instagram's own autoplay check answers no
     * ({@link app.hushgram.extension.instagram.media.TapToPlay}). Nothing Instagram stores is
     * written, so off or paused, Instagram plays as it did.
     */
    public static final BooleanSetting TAP_TO_PLAY =
            new BooleanSetting("hushgram_tap_to_play", TRUE);

    /**
     * A video or reel over two minutes left partway picks up there the next time a player starts
     * it ({@link app.hushgram.extension.instagram.media.ResumePlayback}). Starts off: it keeps the
     * IDs of the videos you left partway, for 30 days, in the app's own storage.
     */
    public static final BooleanSetting RESUME_LONG_VIDEOS =
            new BooleanSetting("hushgram_resume_long_videos", FALSE);

    /**
     * Videos, reels and video stories start at the quality in {@link #PLAYBACK_QUALITY}, through
     * the custom-quality setter of Instagram's DASH format evaluator
     * ({@link app.hushgram.extension.instagram.media.QualityChoice}). The patch is off in the
     * default selection, so a build that has it asked for it, and the switch starts on. Nothing
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
     * The quality a video save asks for: the best the player streams, a ceiling, or the smallest
     * file. Every video save reads it when it starts, and one that finds nothing at or under a
     * ceiling takes the nearest above it. Photos always save whole. Like the folder, it isn't a
     * switch.
     */
    public static final EnumSetting<DownloadQuality> DOWNLOAD_QUALITY =
            new EnumSetting<>("hushgram_download_quality", DownloadQuality.BEST);

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
