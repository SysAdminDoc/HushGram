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

import app.hushgram.extension.instagram.misc.VersionCode;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * Every patch in this source, and what Pause does to it.
 *
 * <p>Pause and safe mode work through the switches: while either is on, every feature switch
 * answers off and the hook behind it takes Instagram's own path. The hook's code is still there,
 * only its answer changes, and Debug logging keeps its saved value. A patch with no switch works
 * entirely when you patch, so it stays in until you patch again, and it says what stays.
 *
 * <p>The settings screen and the diagnostic report read this list, so they can't disagree about
 * it. A family is found in this build by the name of its {@link SettingsStatus} method, the same
 * name the patch uses to switch that method on.
 */
public enum PatchFamily {
    HIDE_ADS(FamilyNames.HIDE_ADS, "hideAds", null, Settings.HIDE_ADS),
    SANITIZE_SHARING_LINKS(FamilyNames.SANITIZE_SHARING_LINKS, "sanitizeSharingLinks", null,
            Settings.SANITIZE_SHARING_LINKS),
    EXTERNAL_BROWSER(FamilyNames.EXTERNAL_BROWSER, "externalBrowser", null, Settings.OPEN_LINKS_EXTERNALLY),
    DISABLE_ANALYTICS(FamilyNames.DISABLE_ANALYTICS, "disableAnalytics", null, Settings.DISABLE_ANALYTICS),
    BUILD_EXPIRED_POPUP(FamilyNames.BUILD_EXPIRED_POPUP, "buildExpiredPopup", null,
            Settings.REMOVE_BUILD_EXPIRED_POPUP),
    RESTORE_TRUST(FamilyNames.RESTORE_TRUST, "restoreTrust", "the re-signed build fix"),
    REMOVE_AD_ID(FamilyNames.REMOVE_AD_ID, "removeAdId", "the removed advertising ID permissions"),
    REEL_WATCH_HISTORY(FamilyNames.REEL_WATCH_HISTORY, "reelWatchHistory", null,
            Settings.DONT_SEND_REEL_WATCH_HISTORY),
    STORY_AUTO_ADVANCE(FamilyNames.STORY_AUTO_ADVANCE, "storyAutoAdvance", null,
            Settings.BLOCK_STORY_AUTO_ADVANCE),
    STORY_TIME(FamilyNames.STORY_TIME, "storyTime", null, Settings.SHOW_STORY_TIME),
    STORY_MENTIONS(FamilyNames.STORY_MENTIONS, "storyMentions", null, Settings.SHOW_STORY_MENTIONS),
    POST_TIME(FamilyNames.POST_TIME, "postTime", null, Settings.SHOW_POST_TIME),
    STORY_LOOP(FamilyNames.STORY_LOOP, "storyLoop", null, Settings.LOOP_STORIES),
    STORY_SEEN(FamilyNames.STORY_SEEN, "storySeen", null, Settings.VIEW_STORIES_ANONYMOUSLY,
            Settings.MARK_STORIES_SEEN),
    LIVE_SEEN(FamilyNames.LIVE_SEEN, "liveSeen", null, Settings.VIEW_LIVE_ANONYMOUSLY),
    DM_MEDIA_SEEN(FamilyNames.DM_MEDIA_SEEN, "visualSeen", null, Settings.VIEW_DM_MEDIA_ANONYMOUSLY),
    DELETED_MESSAGES(FamilyNames.DELETED_MESSAGES, "deletedMessages", null, Settings.SAVE_DELETED_MESSAGES),
    SPOOF_LOCATION(FamilyNames.SPOOF_LOCATION, "spoofLocation", null, Settings.SPOOF_LOCATION),
    THREAD_SEEN(FamilyNames.THREAD_SEEN, "threadSeen", null, Settings.READ_WITHOUT_SEEN_RECEIPT),
    TYPING(FamilyNames.TYPING, "typing", null, Settings.HIDE_TYPING),
    MESSAGES_LOCK(FamilyNames.MESSAGES_LOCK, "messagesLock", null, Settings.LOCK_MESSAGES, Settings.LOCK_APP),
    SCREENSHOT_REPORTS(FamilyNames.SCREENSHOT_REPORTS, "screenshotReports", null, Settings.HIDE_SCREENSHOTS),
    SCREENSHOT_BLOCK(FamilyNames.SCREENSHOT_BLOCK, "screenshotBlock", null, Settings.ALLOW_SCREENSHOTS),
    KEEP_IN_CHAT(FamilyNames.KEEP_IN_CHAT, "keepInChat", null, Settings.KEEP_IN_CHAT),
    ASK_BEFORE_CALL(FamilyNames.ASK_BEFORE_CALL, "askBeforeCall", null, Settings.ASK_BEFORE_CALL),
    ASK_BEFORE_LIKE(FamilyNames.ASK_BEFORE_LIKE, "askBeforeLike", null, Settings.ASK_BEFORE_LIKE),
    ASK_BEFORE_REFRESH(FamilyNames.ASK_BEFORE_REFRESH, "askBeforeRefresh", null, Settings.ASK_BEFORE_REFRESH),
    STORIES_TRAY(FamilyNames.STORIES_TRAY, "storiesTray", null, Settings.HIDE_SUGGESTED_STORIES,
            Settings.HIDE_STORY_REWINDS, Settings.HIDE_STORY_RECAPS, Settings.STOP_LOADING_STORIES,
            Settings.HIDE_STORIES_TRAY),
    STORY_RING(FamilyNames.STORY_RING, "storyRingSize", null, Settings.STORY_RING),
    FEED_REELS(FamilyNames.FEED_REELS, "feedReels", null, Settings.HIDE_FEED_REELS),
    FEED_SUGGESTIONS(FamilyNames.FEED_SUGGESTIONS, "feedSuggestions", null, Settings.HIDE_SUGGESTED_ACCOUNTS,
            Settings.HIDE_SUGGESTED_POSTS, Settings.HIDE_THREADS_POSTS, Settings.HIDE_FEED_SURVEYS,
            Settings.HIDE_FEED_SHOPPING, Settings.HIDE_FEED_VIDEOS, Settings.HIDE_FEED_PHOTOS,
            Settings.HIDE_FEED_CAROUSELS),
    HOME_FEED(FamilyNames.HOME_FEED, "homeFeed", null, Settings.HIDE_HOME_FEED),
    FOLLOWING_FEED(FamilyNames.FOLLOWING_FEED, "followingFeed", null, Settings.START_ON_FOLLOWING,
            Settings.ONLY_FOLLOWING),
    SWIPE_TO_CREATE(FamilyNames.SWIPE_TO_CREATE, "swipeToCreate", null, Settings.STOP_SWIPE_TO_CREATE),
    TAB_SWIPE(FamilyNames.TAB_SWIPE, "tabSwipe", null, Settings.STOP_TAB_SWIPING),
    FULL_RESOLUTION(FamilyNames.FULL_RESOLUTION, "fullResolution", null, Settings.FULL_RESOLUTION_PHOTOS,
            Settings.ASK_FOR_LARGER_PHOTOS),
    META_AI(FamilyNames.META_AI, "metaAi", null, Settings.HIDE_META_AI_SEARCH, Settings.HIDE_META_AI_POSTS,
            Settings.HIDE_ABOUT_THIS_REEL, Settings.HIDE_ASK_META_AI),
    EXPLORE_GRID(FamilyNames.EXPLORE_GRID, "exploreGrid", null, Settings.HIDE_EXPLORE_GRID),
    RECENT_SEARCHES(FamilyNames.RECENT_SEARCHES, "recentSearches", null, Settings.DONT_SAVE_RECENT_SEARCHES),
    NOTES_ROW(FamilyNames.NOTES_ROW, "notesRow", null, Settings.HIDE_NOTES_ROW),
    INSTANTS(FamilyNames.INSTANTS, "instants", null, Settings.HIDE_INSTANTS),
    SHARE_SHEET(FamilyNames.SHARE_SHEET, "shareSheet", null, Settings.HIDE_SHARE_SHEET_GROUP),
    REPOST_BUTTON(FamilyNames.REPOST_BUTTON, "repostButton", null, Settings.HIDE_REPOST_BUTTON),
    HIDE_SHARE_BUTTON(FamilyNames.HIDE_SHARE_BUTTON, "hideShareButton", null, Settings.HIDE_SHARE_BUTTON),
    BOTTOM_SPACE(FamilyNames.BOTTOM_SPACE, "bottomSpace", null, Settings.REMOVE_BOTTOM_SPACE),
    GLASS_TAB_BAR(FamilyNames.GLASS_TAB_BAR, "glassTabBar", null, Settings.GLASS_TAB_BAR,
            Settings.GLASS_TAB_BAR_BLUR, Settings.GLASS_TAB_BAR_FLOAT, Settings.GLASS_TAB_BAR_HAPTICS),
    SAVED_ON_PROFILE(FamilyNames.SAVED_ON_PROFILE, "savedOnProfile", null, Settings.SAVED_ON_PROFILE),
    EMOJI_STYLE(FamilyNames.EMOJI_STYLE, "emojiStyle", null, Settings.NOTO_EMOJI),
    NOTIFICATION_GROUPS(FamilyNames.NOTIFICATION_GROUPS, "notificationGroups", null, Settings.GROUP_NOTIFICATIONS),
    HDR_BOOST(FamilyNames.HDR_BOOST, "hdrBoost", null, Settings.TURN_OFF_HDR_BOOSTS),
    MEDIA_CACHE(FamilyNames.MEDIA_CACHE, "mediaCache", null, Settings.CLEAR_MEDIA_CACHE),
    FRIENDSHIP_STATUS(FamilyNames.FRIENDSHIP_STATUS, "friendshipStatus", null, Settings.SHOW_FRIENDSHIP_STATUS,
            Settings.MARK_FOLLOWING_LIST, Settings.FRIENDSHIP_STATUS_CHIP),
    PROFILE_SUGGESTIONS(FamilyNames.PROFILE_SUGGESTIONS, "profileSuggestions", null, Settings.HIDE_PROFILE_SUGGESTIONS),
    PROFILE_HIGHLIGHTS(FamilyNames.PROFILE_HIGHLIGHTS, "profileHighlights", null, Settings.HIDE_HIGHLIGHTS),
    THREADS_BUTTON(FamilyNames.THREADS_BUTTON, "threadsButton", null, Settings.HIDE_THREADS_BUTTON),
    COMMENT_COPY(FamilyNames.COMMENT_COPY, "commentCopy", null, Settings.COPY_COMMENTS),
    COMMENT_PHOTO(FamilyNames.COMMENT_PHOTO, "commentPhoto", null, Settings.SAVE_COMMENT_PHOTOS),
    PROFILE_PICTURE(FamilyNames.PROFILE_PICTURE, "profilePicture", null, Settings.SAVE_PROFILE_PICTURES,
            Settings.VIEW_PROFILE_PICTURES, Settings.COPY_PROFILE_TEXT),
    VOICE_MESSAGE(FamilyNames.VOICE_MESSAGE, "voiceMessage", null, Settings.DOWNLOAD_VOICE_MESSAGES),
    HIDE_COMMENTS(FamilyNames.HIDE_COMMENTS, "hideComments", null, Settings.HIDE_COMMENTS),
    REEL_DECLUTTER(FamilyNames.REEL_DECLUTTER, "reelDeclutter", null, Settings.HIDE_REEL_FOLLOW_BUTTON,
            Settings.HIDE_REEL_CHIPS, Settings.HIDE_REEL_SOCIAL_FOOTER, Settings.HIDE_REEL_COMMENT_BAR),
    REEL_DOWNLOAD(FamilyNames.REEL_DOWNLOAD, "reelDownload", null, Settings.DOWNLOAD_REELS),
    DOUBLE_TAP_LIKE(FamilyNames.DOUBLE_TAP_LIKE, "doubleTapLike", null, Settings.TURN_OFF_DOUBLE_TAP_LIKE,
            Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS,
            Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS),
    LIKE_ANIMATION(FamilyNames.LIKE_ANIMATION, "likeAnimation", null, Settings.CHANGE_LIKE_ANIMATION),
    REELS_TAB(FamilyNames.REELS_TAB, "reelsTab", null, Settings.HIDE_REELS_TAB),
    REELS_SUGGESTIONS(FamilyNames.REELS_SUGGESTIONS, "reelsSuggestions", null, Settings.HIDE_REELS_SUGGESTIONS),
    KEEP_REEL_SPEED(FamilyNames.KEEP_REEL_SPEED, "keepReelSpeed", null, Settings.KEEP_REEL_SPEED),
    REEL_SEEK_BAR(FamilyNames.REEL_SEEK_BAR, "reelSeekBar", null, Settings.REEL_SEEK_BAR, Settings.REEL_SEEK_THUMB),
    REEL_AUTO_SCROLL(FamilyNames.REEL_AUTO_SCROLL, "reelAutoScroll", null, Settings.KEEP_REEL_AUTO_SCROLL),
    REEL_SCROLLING(FamilyNames.REEL_SCROLLING, "reelScrolling", null, Settings.STOP_REELS_SCROLLING,
            Settings.REEL_CAP),
    STORY_DOWNLOAD(FamilyNames.STORY_DOWNLOAD, "storyDownload", null, Settings.DOWNLOAD_STORIES),
    VIDEO_DOWNLOAD(FamilyNames.VIDEO_DOWNLOAD, "videoDownload", null, Settings.DOWNLOAD_VIDEOS, Settings.DOWNLOAD_PHOTOS,
            Settings.POST_DETAILS),
    TAP_TO_PLAY(FamilyNames.TAP_TO_PLAY, "tapToPlay", null, Settings.TAP_TO_PLAY),
    RESUME_LONG_VIDEOS(FamilyNames.RESUME_LONG_VIDEOS, "resumeLongVideos", null, Settings.RESUME_LONG_VIDEOS),
    PLAYBACK_QUALITY(FamilyNames.PLAYBACK_QUALITY, "defaultPlaybackQuality", null,
            Settings.DEFAULT_PLAYBACK_QUALITY),
    DATA_SAVER(FamilyNames.DATA_SAVER, "dataSaver", null, Settings.DATA_SAVER),
    TRANSLATED_START(FamilyNames.TRANSLATED_START, "translatedStart", "the start-up fix for x86 devices"),
    DEVELOPER_OPTIONS(FamilyNames.DEVELOPER_OPTIONS, "developerOptions", null, Settings.OPEN_DEVELOPER_OPTIONS),
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

    /** Whether a test says this build filters Home by a post's type, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean feedTypesForTests;

    /**
     * Whether this build filters Home by a post's type. Hide suggested posts goes in without it when
     * Home's reads or a post's type have moved, so its post type switches aren't offered then.
     */
    public static boolean feedTypesInBuild() {
        Boolean forced = feedTypesForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(FEED_SUGGESTIONS);
        return FEED_SUGGESTIONS.inBuild() && SettingsStatus.feedTypes();
    }

    /** Whether a test says this build reads, or writes, MetaConfig overrides, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean overrideExchangeForTests, overrideImportForTests;

    /**
     * Whether this build reads MetaConfig overrides, for Export and Validate. Open developer options
     * goes in without it when Instagram's override reader has moved.
     */
    public static boolean overrideExchangeInBuild() {
        Boolean forced = overrideExchangeForTests;
        if (forced != null) return forced;
        Set<PatchFamily> families = inBuildForTests;
        if (families != null) return families.contains(DEVELOPER_OPTIONS);
        return DEVELOPER_OPTIONS.inBuild() && SettingsStatus.overrideExchange();
    }

    /** Whether this build writes them too, for Import, Restore and Reset. The writer needs the reader. */
    public static boolean overrideImportInBuild() {
        if (!overrideExchangeInBuild()) return false;
        Boolean forced = overrideImportForTests;
        if (forced != null) return forced;
        return inBuildForTests != null || SettingsStatus.overrideImport();
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
                    lines.add("  Hide videos, Hide photos and Hide carousels: not in this build (Home's feed or a post's type didn't match)");
                }
                if (family == FRIENDSHIP_STATUS && !followingListMarkInBuild()) {
                    lines.add("  Mark who doesn't follow you back: not in this build (Instagram's follow list didn't match)");
                }
                if (family == DEVELOPER_OPTIONS && !overrideExchangeInBuild()) {
                    lines.add("  Export, Validate and Import overrides: not in this build (Instagram's override reader didn't match)");
                } else if (family == DEVELOPER_OPTIONS && !overrideImportInBuild()) {
                    lines.add("  Import overrides: not in this build (Instagram's override writer didn't match)");
                }
                if (family == VERSION_CODE) lines.add("  " + VersionCode.reportLine());
                if (family == DISABLE_ANALYTICS || family == SANITIZE_SHARING_LINKS || family == TRANSLATED_START) {
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

    /** The same fixed-label metadata the fixture tools read from this APK's DEX. */
    static String coverageLine(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 32768) {
            return "patch target coverage unavailable";
        }
        try {
            String[] parts = encoded.split("\\|", -1);
            if (parts.length != 5 || !parts[0].equals("1")) throw new IllegalArgumentException();
            int matched = Integer.parseInt(parts[1]);
            int expected = Integer.parseInt(parts[2]);
            List<String> targets = Arrays.asList(parts[3].split(",", -1));
            List<String> missing = parts[4].isEmpty() ? Collections.emptyList() : Arrays.asList(parts[4].split(",", -1));
            if (matched < 1 || expected < matched || expected > 256 || targets.size() != expected
                    || missing.size() != expected - matched || new HashSet<>(targets).size() != expected
                    || new HashSet<>(missing).size() != missing.size() || !targets.containsAll(missing)) {
                throw new IllegalArgumentException();
            }
            for (String label : targets) {
                if (!label.matches("[a-z][a-z0-9 -]{0,63}")) throw new IllegalArgumentException();
            }
            return "patch targets matched " + matched + "/" + expected
                    + (missing.isEmpty() ? " (complete)" : " (partial); missing: " + String.join(", ", missing))
                    + ". Patch-time matches do not prove live endpoint suppression.";
        } catch (IllegalArgumentException failure) {
            return "patch target coverage unavailable";
        }
    }

    /** "on", "disabled by its switch" or "disabled while paused", then the saved switches. */
    private String reportLine(boolean paused) {
        StringBuilder line = new StringBuilder(patchName).append(": ");
        if (switches.isEmpty()) {
            return line.append("no switch, stays in while paused: ").append(staysWhilePaused).toString();
        }
        boolean anyOn = false;
        // A switch with switches under it acts only through them, and one under a switch that's off
        // does nothing, whatever it holds.
        Set<Object> parents = new HashSet<>();
        for (BooleanSetting setting : switches) parents.addAll(setting.getParentSettings());
        for (BooleanSetting setting : switches) {
            anyOn |= setting.savedValue() && setting.isAvailable() && !parents.contains(setting);
        }
        line.append(paused ? "disabled while paused (saved " : anyOn ? "on (" : "disabled by its switch (");
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) line.append(", ");
            BooleanSetting setting = switches.get(i);
            line.append(setting.key).append(setting.savedValue() ? "=on" : "=off");
        }
        line.append(')');
        if (staysWhilePaused != null) line.append("; stays in while paused: ").append(staysWhilePaused);
        return line.toString();
    }

    /**
     * Registers the [PATCHES] report section, and tells Hook status which families no pause
     * reaches, so a paused export marks only the ones a switch runs. Registering twice keeps one.
     */
    public static void registerDiagnostics() {
        LogBufferManager.registerReportSection(REPORT);
        for (PatchFamily family : values()) {
            if (family.switches.isEmpty()) HookStatus.runsWhilePaused(family.patchName);
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
