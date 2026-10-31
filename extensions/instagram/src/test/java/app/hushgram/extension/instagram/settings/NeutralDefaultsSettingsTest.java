/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.preference.SwitchPreference;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.direct.InboxSuggestions;
import app.hushgram.extension.instagram.direct.Instants;
import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.direct.InboxSearchBar;
import app.hushgram.extension.instagram.direct.NotesRow;
import app.hushgram.extension.instagram.profile.ProfileHighlights;
import app.hushgram.extension.instagram.feed.FullResolution;
import app.hushgram.extension.instagram.feed.HomeFeed;
import app.hushgram.extension.instagram.feed.SwipeToCreate;
import app.hushgram.extension.instagram.reels.ReelDeclutter;
import app.hushgram.extension.instagram.reels.ReelScrolling;
import app.hushgram.extension.instagram.stories.StoryRing;
import app.hushgram.extension.instagram.stories.StoryRingColor;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;

/**
 * Manager's default selection picks nearly every patch, so each switch it picks up starts off, its
 * hooks answer as stock Instagram, and opening the settings never replaces a saved choice.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class NeutralDefaultsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private BooleanSetting[] initiallyOff;

    /** Stands in for the inbox's section enum. */
    enum StockSection { SEARCH_BAR, TRAY }

    /** Stands in for where the reel viewer was opened from. */
    enum StockSource { REPOSTS_GRID, DIRECT }

    /** Stands in for the presence status enum. */
    enum StockPresence { OFFLINE, ACTIVE, IDLE }

    /** Stands in for the share type an upload is prepared for. */
    enum StockShareType { FEED, STORY }

    /** Stands in for someone else's posts tab, resumed with its grid. */
    public static final class StockPostsTab {
        private final android.os.Bundle arguments = new android.os.Bundle();

        StockPostsTab() {
            arguments.putString("ProfileMediaTabFragment.profile_tab_identifier", "profile_media_grid");
        }

        public android.os.Bundle getArguments() {
            return arguments;
        }

        public boolean isResumed() {
            return true;
        }
    }

    /** Stands in for a reel whose author's profile is opened, counting reads of its id. */
    public static final class StockReel {
        int reads;

        public String getId() {
            reads++;
            return "1_2";
        }
    }

    /** Stands in for the unit leading your messages' Accounts to follow section. */
    public static final class StockUnit {
        public String getName() { return "suggested_accounts_to_follow"; }
    }

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        initiallyOff = new BooleanSetting[]{Settings.ASK_BEFORE_CALL, Settings.HIDE_CALL_BUTTONS, Settings.HIDE_REEL_COMMENT_BAR, Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE, Settings.HIDE_REEL_REPLY_BAR, Settings.HIDE_REELS_TOP_BAR, Settings.HIDE_REEL_SAVE_BUTTON, Settings.KEEP_REEL_CLEAR_MODE, Settings.HIDE_FEED_INTEREST, Settings.HIDE_CAUGHT_UP_CARD, Settings.COPY_COMMENTS, Settings.COPY_COMMENT_AUTHORS, Settings.SAVE_COMMENT_PHOTOS, Settings.REPLY_WITH_COMMENT_GIFS,Settings.SAVE_PROFILE_PICTURES, Settings.VIEW_PROFILE_PICTURES, Settings.COPY_PROFILE_TEXT, Settings.PROFILE_EXTRAS_BUTTON, Settings.HIDE_FEED_VIDEOS, Settings.HIDE_FEED_PHOTOS, Settings.HIDE_FEED_CAROUSELS, Settings.HIDE_FEED_LIKED, Settings.DOWNLOAD_VOICE_MESSAGES, Settings.HIDE_COMMENTS, Settings.HIDE_SHARE_BUTTON, Settings.CHANGE_LIKE_ANIMATION,
                Settings.ASK_BEFORE_LIKE, Settings.ASK_BEFORE_REFRESH, Settings.SKIP_COLLECTIONS_TRAY,
                Settings.HIDE_HIGHLIGHTS, Settings.HIDE_THREADS_BUTTON, Settings.HIDE_CREATE_BUTTON, Settings.HIDE_SHARE_PROFILE_BUTTON, Settings.HIDE_PROFILE_NOTES, Settings.PROFILE_POSTS_LIST, Settings.HIDE_NOTES_ROW, Settings.HIDE_INBOX_SEARCH, Settings.HIDE_INBOX_SUGGESTIONS, Settings.HIDE_INSTANTS,
                Settings.STOP_SWIPE_TO_CREATE, Settings.STOP_REELS_SCROLLING, Settings.REEL_CAP, Settings.REEL_SWIPE_TO_PROFILE, Settings.FIND_REEL_ORIGIN, Settings.FIND_REEL_ORIGIN_ON_TAP, Settings.FULL_RESOLUTION_PHOTOS, Settings.ASK_FOR_LARGER_PHOTOS, Settings.FULL_SIZE_COVERS,
                Settings.HIDE_HOME_FEED, Settings.STOP_TAB_SWIPING, Settings.TURN_OFF_HDR_BOOSTS, Settings.DONT_SAVE_RECENT_SEARCHES, Settings.DATA_SAVER, Settings.CLEAR_MEDIA_CACHE, Settings.GROUP_NOTIFICATIONS,
                Settings.LOCK_MESSAGES, Settings.LOCK_APP,
                Settings.HIDE_SCREENSHOTS,
                Settings.ALLOW_SCREENSHOTS,
                Settings.KEEP_IN_CHAT,
                Settings.VIEW_LIVE_ANONYMOUSLY, Settings.NOTO_EMOJI, Settings.ACCENT_COLOR, Settings.ACCENT_BADGES, Settings.ACCENT_ICONS, Settings.LAUNCH_SCREEN,
                Settings.DONT_SEND_REEL_WATCH_HISTORY, Settings.BLOCK_STORY_AUTO_ADVANCE, Settings.START_STORIES_WITH_SOUND, Settings.STOP_STORY_SWIPE_REPLY, Settings.UPLOAD_STORIES_AT_FULL_QUALITY, Settings.STORY_FONTS, Settings.STORY_EMOJI, Settings.START_FEED_VIDEOS_WITH_SOUND, Settings.TAP_FEED_VIDEO_TO_PAUSE, Settings.LONG_PRESS_TO_ZOOM, Settings.ROUNDED_POST_CORNERS, Settings.FULL_SCREEN_REELS, Settings.FILL_REEL_SCREEN, Settings.FIT_REEL_SCREEN, Settings.FULL_SCREEN_HOME, Settings.FULL_SCREEN_MESSAGES, Settings.SHOW_STORY_TIME,
                Settings.SHOW_STORY_MENTIONS, Settings.HIDE_STORY_FOLLOW, Settings.SHOW_POST_TIME, Settings.SHOW_HIDDEN_LIKE_COUNTS, Settings.BACK_LEAVES_HOME, Settings.KEEP_HOME_PLACE, Settings.LOOP_STORIES, Settings.VIEW_STORIES_ANONYMOUSLY,
                Settings.SPOOF_LOCATION, Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.HIDE_TYPING, Settings.HIDE_ACTIVE_STATUS, Settings.SHOW_LAST_ACTIVE,
                Settings.HIDE_FEED_REELS, Settings.START_ON_FOLLOWING, Settings.HIDE_EXPLORE_GRID,
                Settings.HIDE_SHARE_SHEET_GROUP, Settings.HIDE_REPOST_BUTTON, Settings.REMOVE_BOTTOM_SPACE,
                Settings.HIDE_PROFILE_SUGGESTIONS, Settings.HIDE_REELS_SUGGESTIONS, Settings.HIDE_PAID_PARTNERSHIP_REELS, Settings.HIDE_AI_LABELED_REELS, Settings.HIDE_CAPTION_WORD_REELS, Settings.SHOW_REEL_POST_TIME, Settings.REEL_POST_TIME_SECONDS, Settings.REEL_POST_TIME_AGO, Settings.HIDE_REEL_FOLLOW_BUTTON,
                Settings.HIDE_REEL_CHIPS, Settings.HIDE_REEL_SEARCH_PILL, Settings.HIDE_REEL_PRODUCT_PILLS, Settings.HIDE_REEL_POLL_PILLS, Settings.HIDE_REEL_SOCIAL_FOOTER, Settings.TURN_OFF_DOUBLE_TAP_LIKE,
                Settings.HIDE_REELS_TAB, Settings.SHOW_REELS_TAB, Settings.SHOW_MESSAGES_TAB, Settings.HIDE_MESSAGES_TAB, Settings.SHOW_ACTIVITY_TAB, Settings.HIDE_HOME_TAB, Settings.HIDE_SEARCH_TAB, Settings.HIDE_CREATE_TAB, Settings.HIDE_PROFILE_TAB, Settings.HIDE_HOME_CREATE_BUTTON, Settings.HIDE_HOME_NOTIFICATIONS_BUTTON, Settings.SEARCH_BUTTON_ON_HOME, Settings.MESSAGES_BUTTON_ON_HOME, Settings.SETTINGS_BUTTON_ON_HOME, Settings.DOWNLOAD_BUTTON_ON_STORIES, Settings.HIDE_HOME_MESSAGES_BUTTON, Settings.GHOST_BUTTON_ON_HOME, Settings.CENTER_HOME_LOGO, Settings.CENTER_PROFILE_NAME, Settings.CENTER_SCREEN_TITLES, Settings.TITLES_AT_START, Settings.REEL_SEEK_BAR, Settings.BLUR_REEL_BARS, Settings.KEEP_REEL_AUTO_SCROLL, Settings.REEL_AUTO_ADVANCE,
                Settings.DOWNLOAD_VIDEOS, Settings.DOWNLOAD_SKIP_SAVED, Settings.VIEWING_HISTORY, Settings.HOLD_TO_DOWNLOAD, Settings.TAP_TO_PLAY, Settings.KEEP_REELS_MUTED,
                Settings.HIDE_CAPTIONS, Settings.KEEP_HOME_TOP_BAR, Settings.KEEP_REELS_TOP_BAR, Settings.GLASS_TAB_BAR, Settings.SAVED_ON_PROFILE, Settings.HIDE_COMMENT_EMOJI_ROW, Settings.COMMENT_KEYBOARD_DOWN, Settings.HIDE_FEED_FOLLOW, Settings.STOP_HEARTBEAT, Settings.STOP_UPLOAD_ALARM};
        restoreDefaults();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ASK_BEFORE_CALL, PatchFamily.REEL_DECLUTTER, PatchFamily.COMMENT_COPY, PatchFamily.COMMENT_PHOTO, PatchFamily.PROFILE_PICTURE, PatchFamily.VOICE_MESSAGE, PatchFamily.HIDE_COMMENTS, PatchFamily.HIDE_SHARE_BUTTON, PatchFamily.LIKE_ANIMATION,
                PatchFamily.ASK_BEFORE_LIKE, PatchFamily.ASK_BEFORE_REFRESH, PatchFamily.SAVE_TRAY,
                PatchFamily.PROFILE_HIGHLIGHTS, PatchFamily.THREADS_BUTTON, PatchFamily.PROFILE_NOTES, PatchFamily.PROFILE_POSTS_LIST, PatchFamily.NOTES_ROW, PatchFamily.INBOX_SEARCH, PatchFamily.INBOX_SUGGESTIONS, PatchFamily.INSTANTS, PatchFamily.SWIPE_TO_CREATE,
                PatchFamily.REEL_SCROLLING, PatchFamily.SWIPE_TO_PROFILE, PatchFamily.REEL_ORIGIN, PatchFamily.STORY_RING, PatchFamily.FULL_RESOLUTION, PatchFamily.HOME_FEED, PatchFamily.FEED_SUGGESTIONS,
                PatchFamily.TAB_SWIPE, PatchFamily.TAB_BAR_LAYOUT, PatchFamily.HDR_BOOST, PatchFamily.RECENT_SEARCHES, PatchFamily.DATA_SAVER, PatchFamily.MEDIA_CACHE, PatchFamily.NOTIFICATION_GROUPS, PatchFamily.MESSAGES_LOCK, PatchFamily.SCREENSHOT_REPORTS, PatchFamily.SCREENSHOT_BLOCK, PatchFamily.KEEP_IN_CHAT, PatchFamily.LIVE_SEEN,
                PatchFamily.EMOJI_STYLE, PatchFamily.ACCENT_COLOR, PatchFamily.LAUNCH_SCREEN, PatchFamily.FONT, PatchFamily.APP_ICON,
                PatchFamily.REEL_WATCH_HISTORY, PatchFamily.STORY_AUTO_ADVANCE, PatchFamily.STORY_SOUND, PatchFamily.STORY_SWIPE_REPLY, PatchFamily.STORY_UPLOAD_QUALITY, PatchFamily.STORY_FONTS, PatchFamily.FEED_SOUND, PatchFamily.FEED_TAP_PAUSE, PatchFamily.LONG_PRESS_ZOOM, PatchFamily.ROUNDED_POST_CORNERS, PatchFamily.FULL_SCREEN_BARS, PatchFamily.STORY_TIME, PatchFamily.STORY_MENTIONS, PatchFamily.STORY_FOLLOW, PatchFamily.STORY_DOWNLOAD,
                PatchFamily.POST_TIME, PatchFamily.HIDDEN_LIKE_COUNTS, PatchFamily.BACK_LEAVES_HOME, PatchFamily.HOME_PLACE, PatchFamily.STORY_LOOP, PatchFamily.STORY_SEEN, PatchFamily.SPOOF_LOCATION,
                PatchFamily.THREAD_SEEN, PatchFamily.TYPING, PatchFamily.ACTIVE_STATUS, PatchFamily.LAST_ACTIVE, PatchFamily.FEED_REELS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.EXPLORE_GRID, PatchFamily.SHARE_SHEET, PatchFamily.REPOST_BUTTON, PatchFamily.BOTTOM_SPACE,
                PatchFamily.PROFILE_SUGGESTIONS, PatchFamily.REELS_SUGGESTIONS, PatchFamily.PAID_PARTNERSHIP_REELS, PatchFamily.AI_LABELED_REELS, PatchFamily.CAPTION_WORD_REELS, PatchFamily.REEL_POST_TIME, PatchFamily.DOUBLE_TAP_LIKE,
                PatchFamily.REELS_TAB, PatchFamily.REEL_SEEK_BAR, PatchFamily.REEL_BLUR_BARS, PatchFamily.REEL_TAP_AND_VOLUME, PatchFamily.REEL_AUTO_SCROLL,
                PatchFamily.VIDEO_DOWNLOAD, PatchFamily.TAP_TO_PLAY,
                PatchFamily.HIDE_CAPTIONS, PatchFamily.HOME_TOP_BAR, PatchFamily.KEEP_REELS_TOP_BAR, PatchFamily.GLASS_TAB_BAR, PatchFamily.SAVED_ON_PROFILE, PatchFamily.COMMENT_EMOJI_ROW, PatchFamily.COMMENT_KEYBOARD, PatchFamily.FEED_FOLLOW, PatchFamily.STOP_HEARTBEAT);
    }

    @After public void restore() throws Exception {
        restoreDefaults();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        PatchFamily.inBuildForTests = null;
        Utils.awaitBackgroundTasksForTests();
    }

    private void restoreDefaults() {
        for (BooleanSetting setting : initiallyOff) setting.resetToDefault();
        Settings.STORY_RING.resetToDefault();
        Settings.STORY_RING_SCALE.resetToDefault();
        Settings.STORY_RING_COLOR.resetToDefault();
        Settings.FONT.resetToDefault();
        Settings.APP_ICON.resetToDefault();
        Settings.TAB_BAR_LAYOUT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test public void expandedCatalogKeepsItsDeclaredInitialRuntimeValues() {
        for (BooleanSetting setting : initiallyOff) {
            assertEquals(setting.key, Boolean.FALSE, setting.defaultValue);
            assertFalse(setting.key, setting.get());
        }
        assertEquals(Boolean.TRUE, Settings.STORY_RING.defaultValue);
        assertEquals(StoryRingSize.INSTAGRAM, Settings.STORY_RING_SCALE.defaultValue);
        assertEquals(StoryRingColor.INSTAGRAM, Settings.STORY_RING_COLOR.defaultValue);
        assertStockHooks();
    }

    /** View DM photos and videos anonymously stays out of the default selection, with its switch off. */
    @Test public void theOptInSwitchesKeepTheirDefaults() {
        assertEquals(Boolean.FALSE, Settings.VIEW_DM_MEDIA_ANONYMOUSLY.defaultValue);
        // Auto scroll's remembered choice starts off as well, so it adds nothing while the switch is off.
        assertEquals(Boolean.FALSE, Settings.REEL_AUTO_SCROLL_ON.defaultValue);
    }

    @Test public void ringKeepsExactNativeBitsUntilASizeIsChosen() {
        for (int bits : new int[]{0, 0x80000000, 0x43870000, 0xbf800000, 0x7f800000, 0x7fc01234}) {
            assertEquals("initial Instagram choice", bits,
                    Float.floatToRawIntBits(StoryRing.size(Float.intBitsToFloat(bits))));
        }
        Settings.STORY_RING_SCALE.save(StoryRingSize.LARGEST);
        Settings.STORY_RING_COLOR.save(StoryRingColor.BLUE);
        assertEquals(351f, StoryRing.size(270f), 0.001f);
        Settings.STORY_RING.save(false);
        assertStockHooks();
        Settings.STORY_RING.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertStockHooks();
        assertEquals(StoryRingSize.LARGEST, Settings.STORY_RING_SCALE.savedValue());
        assertEquals(StoryRingColor.BLUE, Settings.STORY_RING_COLOR.savedValue());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(this::assertStockHooks);
        SettingsContextRule.beforeThePauseIsDecided(this::assertStockHooks);
    }

    @Test public void openingTheExpandedSettingsNeverReplacesEitherSavedChoice() throws Exception {
        for (boolean chosen : new boolean[]{false, true}) {
            for (BooleanSetting setting : initiallyOff) setting.save(chosen);
            Settings.STORY_RING.save(chosen);
            Settings.STORY_RING_SCALE.save(chosen ? StoryRingSize.SMALLER : StoryRingSize.LARGER);
            Settings.STORY_RING_COLOR.save(chosen ? StoryRingColor.BLUE : StoryRingColor.GOLD);
            Settings.TAB_BAR_LAYOUT.save(chosen ? app.hushgram.extension.instagram.reels.TabBarLayoutChoice.CLASSIC
                    : app.hushgram.extension.instagram.reels.TabBarLayoutChoice.SWIPE);
            Map<String, Object> before = savedChoices();
            for (int opening = 0; opening < 2; opening++) {
                try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
                    var page = new HushgramPreferenceFragment();
                    controller.get().getFragmentManager().beginTransaction()
                            .add(android.R.id.content, page).commitNow();
                    Utils.awaitBackgroundTasksForTests();
                    for (BooleanSetting setting : initiallyOff) {
                        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(setting.key);
                        assertNotNull(setting.key, row);
                        assertEquals(setting.key, chosen, row.isChecked());
                    }
                    SwitchPreference ring = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STORY_RING.key);
                    assertEquals(chosen, ring.isChecked());
                }
                assertEquals("binding and closing must not reset saved preferences", before, savedChoices());
            }
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertStockHooks(chosen);
            assertEquals("Pause must not rewrite saved choices", before, savedChoices());
            PauseForTests.resume();
        }
    }

    private Map<String, Object> savedChoices() {
        Map<String, ?> all = RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.preferences.name, Context.MODE_PRIVATE).getAll();
        Map<String, Object> selected = new HashMap<>();
        for (BooleanSetting setting : initiallyOff) selected.put(setting.key, all.get(setting.key));
        selected.put(Settings.STORY_RING.key, all.get(Settings.STORY_RING.key));
        selected.put(Settings.STORY_RING_SCALE.key, all.get(Settings.STORY_RING_SCALE.key));
        selected.put(Settings.STORY_RING_COLOR.key, all.get(Settings.STORY_RING_COLOR.key));
        selected.put(Settings.TAB_BAR_LAYOUT.key, all.get(Settings.TAB_BAR_LAYOUT.key));
        return selected;
    }

    private void assertStockHooks() {
        assertStockHooks(false);
    }

    /**
     * Every hook answers as stock Instagram would. A lock that's on is the one exception: it keeps
     * locking while paused, so Pause can't be used to get around it.
     */
    private void assertStockHooks(boolean lockOn) {
        Object pager = new Object();
        assertEquals(1, ReelScrolling.pager(pager));
        for (int nativeValue : new int[]{0, 1, -7}) assertEquals(nativeValue, ReelScrolling.userInput(pager, nativeValue));
        assertEquals(1, ReelScrolling.pull());
        assertEquals(1, ProfileHighlights.keepTray());
        Object[] sections = {StockSection.SEARCH_BAR, StockSection.TRAY};
        assertSame(sections, NotesRow.sections(sections));
        assertSame(sections, InboxSearchBar.sections(sections));
        java.util.List<Object> units = java.util.Arrays.asList(new StockUnit(), new StockUnit());
        assertSame(units, InboxSuggestions.units(units));
        assertSame(units, app.hushgram.extension.instagram.reels.PaidPartnershipReels.page(units, "clips_viewer_clips_tab"));
        java.util.List<Object> titleParts = new java.util.ArrayList<>(units);
        app.hushgram.extension.instagram.reels.ReelPostTime.line(new Object(), titleParts);
        assertEquals("a reel's title gets no line", units, titleParts);
        assertFalse("a tap on a feed video stays Instagram's",
                app.hushgram.extension.instagram.feed.FeedTapPause.tapped(new Object(), new Object(), new Object()));
        Object pagerReel = new Object();
        assertSame("clear mode gets the reel the pager is on", pagerReel,
                app.hushgram.extension.instagram.reels.ReelClearMode.current(pagerReel));
        assertFalse(Instants.hide());
        assertFalse("a swipe on a reel keeps Instagram's answer", app.hushgram.extension.instagram.reels.SwipeToProfile.allow(0));
        assertTrue("the tab bar Instagram picked stays", app.hushgram.extension.instagram.reels.TabBarLayout.newTabBar(1));
        assertFalse("the classic bar Instagram picked stays", app.hushgram.extension.instagram.reels.TabBarLayout.newTabBar(0));
        assertTrue(app.hushgram.extension.instagram.feed.CommentsButton.feedState(1));
        assertTrue(app.hushgram.extension.instagram.share.ShareButton.feedState(1));
        assertFalse(app.hushgram.extension.instagram.direct.CallConfirm.hold(new Object(), null, null, null, 0));
        assertSame(StockPresence.ACTIVE, app.hushgram.extension.instagram.direct.ActiveStatus.status(StockPresence.ACTIVE));
        StockPostsTab postsTab = new StockPostsTab();
        app.hushgram.extension.instagram.profile.PostsList.resumed(postsTab);
        assertFalse(postsTab.getArguments().containsKey("hushgram_posts_list_opened"));
        StockReel reel = new StockReel();
        app.hushgram.extension.instagram.reels.ReelOrigin.opening(reel);
        assertEquals("a reel's profile isn't remembered", 0, reel.reads);
        assertFalse(app.hushgram.extension.instagram.share.ShareButton.hideInReels());
        Object heart = new Object();
        assertSame(heart, app.hushgram.extension.instagram.feed.LikeAnimation.pick(heart));
        assertFalse(app.hushgram.extension.instagram.feed.LikeAnimation.allow(0));
        assertTrue(app.hushgram.extension.instagram.feed.HiddenLikeCounts.hidden("poster", 1));
        assertFalse(app.hushgram.extension.instagram.feed.HiddenLikeCounts.hidden("poster", 0));
        assertFalse(app.hushgram.extension.instagram.feed.LikeConfirm.hold(new Object(), null, null, null, null, 0));
        Object refreshListener = new Object();
        assertSame(refreshListener, app.hushgram.extension.instagram.feed.RefreshConfirm.listener(
                new android.view.View(RuntimeEnvironment.getApplication()), refreshListener, null));
        assertEquals(lockOn, MessagesLock.holdBanner());
        assertFalse(app.hushgram.extension.instagram.stories.LiveSeen.hold("17841400000000000"));
        assertEquals("once", app.hushgram.extension.instagram.direct.KeepInChat.viewMode("once", new Object()));
        assertFalse(app.hushgram.extension.instagram.direct.ScreenshotBlock.lift());
        assertFalse(app.hushgram.extension.instagram.direct.ScreenshotReports.hold());
        assertEquals(0, SwipeToCreate.enabled());
        assertEquals(0, SwipeToCreate.hold(-1f, 0f, "swipe"));
        assertEquals(0, app.hushgram.extension.instagram.feed.BackLeavesHome.leave());
        Object photo = new Object();
        assertSame(photo, FullResolution.photo(new Object(), photo));
        Object feedItem = new Object();
        assertSame(feedItem, HomeFeed.filter(feedItem));
        assertFalse(ReelDeclutter.hideCommentBar(StockSource.REPOSTS_GRID));
        assertFalse(ReelDeclutter.hideDirectReplyBar(StockSource.DIRECT));
        assertEquals(270f, StoryRing.size(270f), 0f);
        int[] storyRing = {0xFFFFD600, 0xFFFF7A00, 0xFFFF0069, 0xFFD300C5};
        assertSame(storyRing, StoryRing.colors(storyRing, new float[]{0.1435f, 0.3779f, 0.6071f, 0.831f}));
        assertEquals(0, app.hushgram.extension.instagram.misc.EmojiStyle.replaceStrategy(0));
        assertEquals(0xff0095f6, app.hushgram.extension.instagram.misc.AccentColor.color(0xff0095f6));
        assertEquals(app.hushgram.extension.instagram.misc.FontChoice.INSTAGRAM, Settings.FONT.get());
        assertEquals(app.hushgram.extension.instagram.misc.AppIconChoice.INSTAGRAM, Settings.APP_ICON.get());
        assertSame(android.graphics.Typeface.DEFAULT_BOLD,
                app.hushgram.extension.instagram.misc.Fonts.prismTypeface(android.graphics.Typeface.DEFAULT_BOLD));
        java.util.List<Object> profileButtons = java.util.Arrays.asList(new Object(), new Object());
        assertSame(profileButtons, app.hushgram.extension.instagram.profile.ThreadsButton.buttons(profileButtons));
        assertFalse(app.hushgram.extension.instagram.reels.ReelWatchHistory.holdBack());
        assertFalse(app.hushgram.extension.instagram.stories.StoryAdvance.hold());
        assertFalse(app.hushgram.extension.instagram.stories.StoryLoop.loop(0));
        assertTrue(app.hushgram.extension.instagram.stories.StorySwipeReply.keepsSwipe());
        java.util.List<Object> storyFormats = new java.util.ArrayList<>(units);
        app.hushgram.extension.instagram.stories.StoryFonts.formats(storyFormats);
        assertEquals("the story text editor keeps Instagram's fonts", units, storyFormats);
        assertNull(app.hushgram.extension.instagram.stories.StoryFonts.named("IMPORT_FONT"));
        assertFalse(app.hushgram.extension.instagram.stories.StoryUploadQuality.fullQuality(StockShareType.STORY));
        Object batch = new Object();
        assertSame(batch, app.hushgram.extension.instagram.stories.StorySeen.toSend(null, batch));
        assertFalse(app.hushgram.extension.instagram.feed.FollowingFeed.flag(0));
        assertEquals("BLENDED_FOR_YOU", app.hushgram.extension.instagram.feed.FollowingFeed.saved("BLENDED_FOR_YOU"));
        assertFalse(app.hushgram.extension.instagram.explore.ExploreGrid.hide(java.util.Arrays.asList("section", "section")));
        assertFalse(app.hushgram.extension.instagram.share.ShareSheet.hideGroupButton());
        assertFalse(app.hushgram.extension.instagram.share.RepostButton.hide());
        assertEquals(135, app.hushgram.extension.instagram.misc.BottomSpace.navigationBarHeight(135));
        assertFalse(ReelDeclutter.hideChips());
        assertFalse(ReelDeclutter.hideFollowButton());
        assertFalse(app.hushgram.extension.instagram.reels.DoubleTapLike.holdBackPost());
        assertTrue(app.hushgram.extension.instagram.media.TapToPlay.autoplayAllowed(true));
        assertFalse("a save still opens Instagram's collections tray", app.hushgram.extension.instagram.feed.SaveTray.skip());
        assertFalse("a reel keeps Instagram's crop", app.hushgram.extension.instagram.reels.ReelFill.fit());
        assertFalse("a reel keeps Instagram's crop", app.hushgram.extension.instagram.reels.ReelFill.fill(10f));
    }
}
