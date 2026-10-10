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
import app.hushgram.extension.instagram.direct.NotesRow;
import app.hushgram.extension.instagram.profile.ProfileHighlights;
import app.hushgram.extension.instagram.feed.FullResolution;
import app.hushgram.extension.instagram.feed.HomeFeed;
import app.hushgram.extension.instagram.feed.SwipeToCreate;
import app.hushgram.extension.instagram.reels.ReelDeclutter;
import app.hushgram.extension.instagram.reels.ReelScrolling;
import app.hushgram.extension.instagram.stories.StoryRing;
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
    enum StockSource { REPOSTS_GRID }

    /** Stands in for the presence status enum. */
    enum StockPresence { OFFLINE, ACTIVE, IDLE }

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

    /** Stands in for the unit leading your messages' Accounts to follow section. */
    public static final class StockUnit {
        public String getName() { return "suggested_accounts_to_follow"; }
    }

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        initiallyOff = new BooleanSetting[]{Settings.ASK_BEFORE_CALL, Settings.HIDE_REEL_COMMENT_BAR, Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE, Settings.COPY_COMMENTS, Settings.COPY_COMMENT_AUTHORS, Settings.SAVE_COMMENT_PHOTOS, Settings.SAVE_PROFILE_PICTURES, Settings.VIEW_PROFILE_PICTURES, Settings.COPY_PROFILE_TEXT, Settings.HIDE_FEED_VIDEOS, Settings.HIDE_FEED_PHOTOS, Settings.HIDE_FEED_CAROUSELS, Settings.HIDE_FEED_LIKED, Settings.DOWNLOAD_VOICE_MESSAGES, Settings.HIDE_COMMENTS, Settings.HIDE_SHARE_BUTTON, Settings.CHANGE_LIKE_ANIMATION,
                Settings.ASK_BEFORE_LIKE, Settings.ASK_BEFORE_REFRESH,
                Settings.HIDE_HIGHLIGHTS, Settings.HIDE_THREADS_BUTTON, Settings.PROFILE_POSTS_LIST, Settings.HIDE_NOTES_ROW, Settings.HIDE_INBOX_SUGGESTIONS, Settings.HIDE_INSTANTS,
                Settings.STOP_SWIPE_TO_CREATE, Settings.STOP_REELS_SCROLLING, Settings.REEL_CAP, Settings.FULL_RESOLUTION_PHOTOS, Settings.ASK_FOR_LARGER_PHOTOS,
                Settings.HIDE_HOME_FEED, Settings.STOP_TAB_SWIPING, Settings.TURN_OFF_HDR_BOOSTS, Settings.DONT_SAVE_RECENT_SEARCHES, Settings.DATA_SAVER, Settings.CLEAR_MEDIA_CACHE, Settings.GROUP_NOTIFICATIONS,
                Settings.LOCK_MESSAGES, Settings.LOCK_APP,
                Settings.HIDE_SCREENSHOTS,
                Settings.ALLOW_SCREENSHOTS,
                Settings.KEEP_IN_CHAT,
                Settings.VIEW_LIVE_ANONYMOUSLY, Settings.NOTO_EMOJI,
                Settings.DONT_SEND_REEL_WATCH_HISTORY, Settings.BLOCK_STORY_AUTO_ADVANCE, Settings.SHOW_STORY_TIME,
                Settings.SHOW_STORY_MENTIONS, Settings.SHOW_POST_TIME, Settings.SHOW_HIDDEN_LIKE_COUNTS, Settings.LOOP_STORIES, Settings.VIEW_STORIES_ANONYMOUSLY,
                Settings.SPOOF_LOCATION, Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.HIDE_TYPING, Settings.HIDE_ACTIVE_STATUS,
                Settings.HIDE_FEED_REELS, Settings.START_ON_FOLLOWING, Settings.HIDE_EXPLORE_GRID,
                Settings.HIDE_SHARE_SHEET_GROUP, Settings.HIDE_REPOST_BUTTON, Settings.REMOVE_BOTTOM_SPACE,
                Settings.HIDE_PROFILE_SUGGESTIONS, Settings.HIDE_REELS_SUGGESTIONS, Settings.HIDE_REEL_FOLLOW_BUTTON,
                Settings.HIDE_REEL_CHIPS, Settings.HIDE_REEL_SOCIAL_FOOTER, Settings.TURN_OFF_DOUBLE_TAP_LIKE,
                Settings.HIDE_REELS_TAB, Settings.SHOW_REELS_TAB, Settings.HIDE_SEARCH_TAB, Settings.HIDE_CREATE_TAB, Settings.HIDE_PROFILE_TAB, Settings.HIDE_HOME_CREATE_BUTTON, Settings.HIDE_HOME_NOTIFICATIONS_BUTTON, Settings.REEL_SEEK_BAR, Settings.BLUR_REEL_BARS, Settings.KEEP_REEL_AUTO_SCROLL,
                Settings.DOWNLOAD_VIDEOS, Settings.TAP_TO_PLAY,
                Settings.GLASS_TAB_BAR, Settings.SAVED_ON_PROFILE, Settings.STOP_HEARTBEAT, Settings.STOP_UPLOAD_ALARM};
        restoreDefaults();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ASK_BEFORE_CALL, PatchFamily.REEL_DECLUTTER, PatchFamily.COMMENT_COPY, PatchFamily.COMMENT_PHOTO, PatchFamily.PROFILE_PICTURE, PatchFamily.VOICE_MESSAGE, PatchFamily.HIDE_COMMENTS, PatchFamily.HIDE_SHARE_BUTTON, PatchFamily.LIKE_ANIMATION,
                PatchFamily.ASK_BEFORE_LIKE, PatchFamily.ASK_BEFORE_REFRESH,
                PatchFamily.PROFILE_HIGHLIGHTS, PatchFamily.THREADS_BUTTON, PatchFamily.PROFILE_POSTS_LIST, PatchFamily.NOTES_ROW, PatchFamily.INBOX_SUGGESTIONS, PatchFamily.INSTANTS, PatchFamily.SWIPE_TO_CREATE,
                PatchFamily.REEL_SCROLLING, PatchFamily.STORY_RING, PatchFamily.FULL_RESOLUTION, PatchFamily.HOME_FEED, PatchFamily.FEED_SUGGESTIONS,
                PatchFamily.TAB_SWIPE, PatchFamily.HDR_BOOST, PatchFamily.RECENT_SEARCHES, PatchFamily.DATA_SAVER, PatchFamily.MEDIA_CACHE, PatchFamily.NOTIFICATION_GROUPS, PatchFamily.MESSAGES_LOCK, PatchFamily.SCREENSHOT_REPORTS, PatchFamily.SCREENSHOT_BLOCK, PatchFamily.KEEP_IN_CHAT, PatchFamily.LIVE_SEEN,
                PatchFamily.EMOJI_STYLE,
                PatchFamily.REEL_WATCH_HISTORY, PatchFamily.STORY_AUTO_ADVANCE, PatchFamily.STORY_TIME, PatchFamily.STORY_MENTIONS,
                PatchFamily.POST_TIME, PatchFamily.HIDDEN_LIKE_COUNTS, PatchFamily.STORY_LOOP, PatchFamily.STORY_SEEN, PatchFamily.SPOOF_LOCATION,
                PatchFamily.THREAD_SEEN, PatchFamily.TYPING, PatchFamily.ACTIVE_STATUS, PatchFamily.FEED_REELS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.EXPLORE_GRID, PatchFamily.SHARE_SHEET, PatchFamily.REPOST_BUTTON, PatchFamily.BOTTOM_SPACE,
                PatchFamily.PROFILE_SUGGESTIONS, PatchFamily.REELS_SUGGESTIONS, PatchFamily.DOUBLE_TAP_LIKE,
                PatchFamily.REELS_TAB, PatchFamily.REEL_SEEK_BAR, PatchFamily.REEL_BLUR_BARS, PatchFamily.REEL_AUTO_SCROLL,
                PatchFamily.VIDEO_DOWNLOAD, PatchFamily.TAP_TO_PLAY,
                PatchFamily.GLASS_TAB_BAR, PatchFamily.SAVED_ON_PROFILE, PatchFamily.STOP_HEARTBEAT);
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
        assertStockHooks();
    }

    /**
     * Open developer options stays out of the default selection, so picking it is the choice to use
     * it and its switch starts on. View DM photos and videos anonymously stays out too, with its
     * switch off.
     */
    @Test public void theOptInSwitchesKeepTheirDefaults() {
        assertEquals(Boolean.TRUE, Settings.OPEN_DEVELOPER_OPTIONS.defaultValue);
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
        assertEquals(351f, StoryRing.size(270f), 0.001f);
        Settings.STORY_RING.save(false);
        assertStockHooks();
        Settings.STORY_RING.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertStockHooks();
        assertEquals(StoryRingSize.LARGEST, Settings.STORY_RING_SCALE.savedValue());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(this::assertStockHooks);
        SettingsContextRule.beforeThePauseIsDecided(this::assertStockHooks);
    }

    @Test public void openingTheExpandedSettingsNeverReplacesEitherSavedChoice() throws Exception {
        for (boolean chosen : new boolean[]{false, true}) {
            for (BooleanSetting setting : initiallyOff) setting.save(chosen);
            Settings.STORY_RING.save(chosen);
            Settings.STORY_RING_SCALE.save(chosen ? StoryRingSize.SMALLER : StoryRingSize.LARGER);
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
        java.util.List<Object> units = java.util.Arrays.asList(new StockUnit(), new StockUnit());
        assertSame(units, InboxSuggestions.units(units));
        assertFalse(Instants.hide());
        assertTrue(app.hushgram.extension.instagram.feed.CommentsButton.feedState(1));
        assertTrue(app.hushgram.extension.instagram.share.ShareButton.feedState(1));
        assertFalse(app.hushgram.extension.instagram.direct.CallConfirm.hold(new Object(), null, null, null, 0));
        assertSame(StockPresence.ACTIVE, app.hushgram.extension.instagram.direct.ActiveStatus.status(StockPresence.ACTIVE));
        StockPostsTab postsTab = new StockPostsTab();
        app.hushgram.extension.instagram.profile.PostsList.resumed(postsTab);
        assertFalse(postsTab.getArguments().containsKey("hushgram_posts_list_opened"));
        assertFalse(app.hushgram.extension.instagram.share.ShareButton.hideInReels());
        Object heart = new Object();
        assertSame(heart, app.hushgram.extension.instagram.feed.LikeAnimation.pick(heart));
        assertFalse(app.hushgram.extension.instagram.feed.LikeAnimation.allow(0));
        assertTrue(app.hushgram.extension.instagram.feed.HiddenLikeCounts.hidden(1));
        assertFalse(app.hushgram.extension.instagram.feed.HiddenLikeCounts.hidden(0));
        assertFalse(app.hushgram.extension.instagram.feed.LikeConfirm.hold(new Object(), null, null, null, null, 0));
        Object refreshListener = new Object();
        assertSame(refreshListener, app.hushgram.extension.instagram.feed.RefreshConfirm.listener(
                new android.view.View(RuntimeEnvironment.getApplication()), refreshListener, null));
        assertEquals(lockOn, MessagesLock.holdBanner());
        assertFalse(app.hushgram.extension.instagram.stories.LiveSeen.hold());
        assertEquals("once", app.hushgram.extension.instagram.direct.KeepInChat.viewMode("once", new Object()));
        assertFalse(app.hushgram.extension.instagram.direct.ScreenshotBlock.lift());
        assertFalse(app.hushgram.extension.instagram.direct.ScreenshotReports.hold());
        assertEquals(0, SwipeToCreate.enabled());
        assertEquals(0, SwipeToCreate.hold(-1f, 0f, "swipe"));
        Object photo = new Object();
        assertSame(photo, FullResolution.photo(new Object(), photo));
        Object feedItem = new Object();
        assertSame(feedItem, HomeFeed.filter(feedItem));
        assertFalse(ReelDeclutter.hideCommentBar(StockSource.REPOSTS_GRID));
        assertEquals(270f, StoryRing.size(270f), 0f);
        assertEquals(0, app.hushgram.extension.instagram.misc.EmojiStyle.replaceStrategy(0));
        java.util.List<Object> profileButtons = java.util.Arrays.asList(new Object(), new Object());
        assertSame(profileButtons, app.hushgram.extension.instagram.profile.ThreadsButton.buttons(profileButtons));
        assertFalse(app.hushgram.extension.instagram.reels.ReelWatchHistory.holdBack());
        assertFalse(app.hushgram.extension.instagram.stories.StoryAdvance.hold());
        assertFalse(app.hushgram.extension.instagram.stories.StoryLoop.loop(0));
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
    }
}
