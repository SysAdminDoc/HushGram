/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.util

import app.morphe.patches.instagram.ads.hideAdsPatch
import app.morphe.patches.instagram.coexist.installNextToInstagramPatch
import app.morphe.patches.instagram.direct.calls.askBeforeCallPatch
import app.morphe.patches.instagram.direct.messagebox.messageBoxPatch
import app.morphe.patches.instagram.feed.confirm.askBeforeLikePatch
import app.morphe.patches.instagram.feed.confirm.askBeforeRefreshPatch
import app.morphe.patches.instagram.direct.instants.hideInstantsPatch
import app.morphe.patches.instagram.direct.presence.showLastActivePatch
import app.morphe.patches.instagram.direct.lock.lockMessagesPatch
import app.morphe.patches.instagram.stories.live.viewLiveAnonymouslyPatch
import app.morphe.patches.instagram.direct.history.chatHistoryPatch
import app.morphe.patches.instagram.direct.keep.keepInChatPatch
import app.morphe.patches.instagram.direct.reels.swipeChatReelsPatch
import app.morphe.patches.instagram.direct.screenshots.allowScreenshotsPatch
import app.morphe.patches.instagram.direct.screenshots.dontReportScreenshotsPatch
import app.morphe.patches.instagram.direct.notes.hideNotesRowPatch
import app.morphe.patches.instagram.direct.search.hideInboxSearchPatch
import app.morphe.patches.instagram.direct.presence.hideActiveStatusPatch
import app.morphe.patches.instagram.direct.seen.readWithoutSeenReceiptPatch
import app.morphe.patches.instagram.direct.seen.viewDmMediaAnonymouslyPatch
import app.morphe.patches.instagram.direct.suggested.hideInboxSuggestionsPatch
import app.morphe.patches.instagram.direct.typing.hideTypingPatch
import app.morphe.patches.instagram.download.reel.downloadReelPatch
import app.morphe.patches.instagram.download.story.downloadStoryPatch
import app.morphe.patches.instagram.download.video.downloadVideoPatch
import app.morphe.patches.instagram.explore.exploreMenuPatch
import app.morphe.patches.instagram.explore.hideExploreGridPatch
import app.morphe.patches.instagram.explore.recent.dontSaveRecentSearchesPatch
import app.morphe.patches.instagram.explore.recent.showAllRecentSearchesPatch
import app.morphe.patches.instagram.feed.back.backLeavesHomePatch
import app.morphe.patches.instagram.feed.place.keepHomePlacePatch
import app.morphe.patches.instagram.feed.commentkeyboard.commentKeyboardPatch
import app.morphe.patches.instagram.feed.captions.hideCaptionsPatch
import app.morphe.patches.instagram.feed.comments.hideCommentsPatch
import app.morphe.patches.instagram.feed.emojirow.hideCommentEmojiRowPatch
import app.morphe.patches.instagram.feed.feedfollow.hideFeedFollowPatch
import app.morphe.patches.instagram.feed.following.startOnFollowingPatch
import app.morphe.patches.instagram.feed.home.hideHomeFeedPatch
import app.morphe.patches.instagram.feed.likes.showHiddenLikeCountsPatch
import app.morphe.patches.instagram.feed.photos.fullResolutionPhotosPatch
import app.morphe.patches.instagram.feed.reels.hideFeedReelsPatch
import app.morphe.patches.instagram.feed.savetray.skipCollectionsTrayPatch
import app.morphe.patches.instagram.feed.suggested.hideSuggestedPostsPatch
import app.morphe.patches.instagram.feed.swipecreate.stopSwipeToCreatePatch
import app.morphe.patches.instagram.feed.topbar.keepHomeTopBarPatch
import app.morphe.patches.instagram.feed.tabswipe.stopTabSwipingPatch
import app.morphe.patches.instagram.feed.time.showPostTimePatch
import app.morphe.patches.instagram.media.hdr.turnOffHdrBoostsPatch
import app.morphe.patches.instagram.media.loop.stopVideoLoopingPatch
import app.morphe.patches.instagram.misc.cache.clearMediaCachePatch
import app.morphe.patches.instagram.misc.location.spoofLocationPatch
import app.morphe.patches.instagram.misc.proxy.appProxyPatch
import app.morphe.patches.instagram.misc.consent.skipAdDataReminderPatch
import app.morphe.patches.instagram.misc.setupscreens.skipSetupPopupsPatch
import app.morphe.patches.instagram.misc.notifications.groupNotificationsPatch
import app.morphe.patches.instagram.media.quality.dataSaverPatch
import app.morphe.patches.instagram.media.quality.defaultPlaybackQualityPatch
import app.morphe.patches.instagram.media.resume.resumeLongVideosPatch
import app.morphe.patches.instagram.media.taptoplay.tapToPlayPatch
import app.morphe.patches.instagram.metaai.hideMetaAiPatch
import app.morphe.patches.instagram.misc.adid.removeAdIdPatch
import app.morphe.patches.instagram.misc.bottomspace.removeBottomSpacePatch
import app.morphe.patches.instagram.misc.buildexpiry.removeBuildExpiredPopupPatch
import app.morphe.patches.instagram.misc.comment.copyCommentPatch
import app.morphe.patches.instagram.misc.comment.saveCommentPhotoPatch
import app.morphe.patches.instagram.download.profile.saveProfilePicturePatch
import app.morphe.patches.instagram.download.voice.downloadVoiceMessagesPatch
import app.morphe.patches.instagram.misc.emoji.emojiStylePatch
import app.morphe.patches.instagram.misc.externalbrowser.openLinksExternallyPatch
import app.morphe.patches.instagram.misc.font.fontPatch
import app.morphe.patches.instagram.misc.icon.pickAppIconPatch
import app.morphe.patches.instagram.misc.resignedtrust.restoreTrustPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.misc.settingsrows.hideSettingsRowsPatch
import app.morphe.patches.instagram.misc.sharelinks.sanitizeSharingLinksPatch
import app.morphe.patches.instagram.misc.theme.accentColorPatch
import app.morphe.patches.instagram.misc.theme.launchScreenPatch
import app.morphe.patches.instagram.misc.theme.pureBlackPatch
import app.morphe.patches.instagram.misc.translatedstart.translatedStartPatch
import app.morphe.patches.instagram.misc.versioncode.changeVersionCodePatch
import app.morphe.patches.instagram.profile.friendship.friendshipStatusPatch
import app.morphe.patches.instagram.profile.highlights.hideHighlightsPatch
import app.morphe.patches.instagram.profile.notes.hideProfileNotesPatch
import app.morphe.patches.instagram.profile.tabs.hideProfileTabsPatch
import app.morphe.patches.instagram.profile.postslist.profilePostsListPatch
import app.morphe.patches.instagram.profile.suggested.hideProfileSuggestionsPatch
import app.morphe.patches.instagram.profile.threads.hideThreadsButtonPatch
import app.morphe.patches.instagram.reels.ailabel.hideAiLabeledReelsPatch
import app.morphe.patches.instagram.reels.autoscroll.keepReelsAutoScrollPatch
import app.morphe.patches.instagram.reels.blur.blurReelBarsPatch
import app.morphe.patches.instagram.reels.captionwords.hideCaptionWordReelsPatch
import app.morphe.patches.instagram.reels.cleanup.cleanUpReelsPatch
import app.morphe.patches.instagram.reels.doubletap.turnOffDoubleTapLikePatch
import app.morphe.patches.instagram.reels.origin.findReelOriginPatch
import app.morphe.patches.instagram.reels.partnership.hidePaidPartnershipReelsPatch
import app.morphe.patches.instagram.reels.scrolling.stopReelsScrollingPatch
import app.morphe.patches.instagram.reels.swipeprofile.swipeToProfilePatch
import app.morphe.patches.instagram.reels.seekbar.reelSeekBarPatch
import app.morphe.patches.instagram.reels.speed.keepReelSpeedPatch
import app.morphe.patches.instagram.reels.suggested.hideReelsSuggestionsPatch
import app.morphe.patches.instagram.reels.tab.hideReelsTabPatch
import app.morphe.patches.instagram.reels.tablayout.tabBarLayoutPatch
import app.morphe.patches.instagram.reels.tapvolume.reelTapAndVolumePatch
import app.morphe.patches.instagram.reels.sound.startReelsWithSoundPatch
import app.morphe.patches.instagram.reels.time.showReelTimePatch
import app.morphe.patches.instagram.reels.topbar.keepReelsTopBarPatch
import app.morphe.patches.instagram.direct.viewonce.markViewOncePatch
import app.morphe.patches.instagram.reels.watchhistory.dontSendReelWatchHistoryPatch
import app.morphe.patches.instagram.reels.zoom.pinchZoomReelsPatch
import app.morphe.patches.instagram.share.hideRepostButtonPatch
import app.morphe.patches.instagram.share.hideShareButtonPatch
import app.morphe.patches.instagram.feed.likes.changeLikeAnimationPatch
import app.morphe.patches.instagram.share.hideShareSheetGroupPatch
import app.morphe.patches.instagram.stories.autoadvance.stopStoryAutoAdvancePatch
import app.morphe.patches.instagram.stories.follow.hideStoryFollowPatch
import app.morphe.patches.instagram.stories.loop.loopStoryPatch
import app.morphe.patches.instagram.stories.opening.smoothStoryOpenPatch
import app.morphe.patches.instagram.stories.mentions.storyMentionsPatch
import app.morphe.patches.instagram.stories.progressbar.storyProgressBarPatch
import app.morphe.patches.instagram.stories.ring.storyRingSizePatch
import app.morphe.patches.instagram.stories.seekbar.storySeekBarPatch
import app.morphe.patches.instagram.stories.seen.viewStoriesAnonymouslyPatch
import app.morphe.patches.instagram.stories.time.showStoryTimePatch
import app.morphe.patches.instagram.feed.sound.startFeedVideosWithSoundPatch
import app.morphe.patches.instagram.feed.tap.tapFeedVideoToPausePatch
import app.morphe.patches.instagram.feed.seekbar.feedSeekBarPatch
import app.morphe.patches.instagram.feed.rounded.roundedPostCornersPatch
import app.morphe.patches.instagram.feed.ambient.ambientLightPatch
import app.morphe.patches.instagram.feed.carousel.carouselArrowsPatch
import app.morphe.patches.instagram.feed.zoom.longPressZoomPatch
import app.morphe.patches.instagram.stories.sound.startStoriesWithSoundPatch
import app.morphe.patches.instagram.stories.fonts.storyFontsPatch
import app.morphe.patches.instagram.stories.swipereply.stopSwipeReplyPatch
import app.morphe.patches.instagram.stories.quality.uploadStoriesAtFullQualityPatch
import app.morphe.patches.instagram.stories.tray.hideSuggestedStoriesPatch
import app.morphe.patches.instagram.misc.glass.glassTabBarPatch
import app.morphe.patches.instagram.reels.fullscreen.fullScreenBarsPatch
import app.morphe.patches.instagram.misc.heartbeat.stopHeartbeatPatch
import app.morphe.patches.instagram.misc.refreshrate.refreshRatePatch
import app.morphe.patches.instagram.misc.profilesaved.savedOnProfilePatch
import app.morphe.patcher.patch.Patch
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reviewed selection policy for every named patch, separate from each saved runtime choice.
 *
 * Simple mode picks every patch whose switch keeps Instagram as it is until the reader turns it on,
 * so [neutral] holds every switch-gated patch whose switch starts off. [optIn] is what changes the
 * build itself or isn't ready yet, each with its reason in DefaultSelectionPolicyTest.
 */
class DefaultCatalogTest {
    private val neutral = listOf<Patch<*>>(
        accentColorPatch,
        allowScreenshotsPatch,
        askBeforeCallPatch,
        askBeforeLikePatch,
        askBeforeRefreshPatch,
        backLeavesHomePatch,
        keepHomePlacePatch,
        blurReelBarsPatch,
        changeLikeAnimationPatch,
        cleanUpReelsPatch,
        clearMediaCachePatch,
        commentKeyboardPatch,
        reelTapAndVolumePatch,
        copyCommentPatch,
        dataSaverPatch,
        dontReportScreenshotsPatch,
        dontSaveRecentSearchesPatch,
        showAllRecentSearchesPatch,
        dontSendReelWatchHistoryPatch,
        downloadVideoPatch,
        downloadVoiceMessagesPatch,
        emojiStylePatch,
        findReelOriginPatch,
        fontPatch,
        fullResolutionPhotosPatch,
        glassTabBarPatch,
        groupNotificationsPatch,
        hideActiveStatusPatch,
        hideCommentsPatch,
        hideCommentEmojiRowPatch,
        hideFeedFollowPatch,
        hideCaptionsPatch,
        keepHomeTopBarPatch,
        keepReelsTopBarPatch,
        markViewOncePatch,
        hideExploreGridPatch,
        exploreMenuPatch,
        hideFeedReelsPatch,
        hideHighlightsPatch,
        hideHomeFeedPatch,
        hideInboxSearchPatch,
        hideInboxSuggestionsPatch,
        hideInstantsPatch,
        hideNotesRowPatch,
        hideProfileNotesPatch,
        hideProfileTabsPatch,
        messageBoxPatch,
        hideProfileSuggestionsPatch,
        hideReelsSuggestionsPatch,
        hidePaidPartnershipReelsPatch,
        hideAiLabeledReelsPatch,
        hideCaptionWordReelsPatch,
        hideReelsTabPatch,
        hideRepostButtonPatch,
        hideShareButtonPatch,
        hideShareSheetGroupPatch,
        hideStoryFollowPatch,
        hideThreadsButtonPatch,
        hideTypingPatch,
        keepInChatPatch,
        swipeChatReelsPatch,
        chatHistoryPatch,
        keepReelsAutoScrollPatch,
        launchScreenPatch,
        lockMessagesPatch,
        loopStoryPatch,
        pickAppIconPatch,
        profilePostsListPatch,
        readWithoutSeenReceiptPatch,
        reelSeekBarPatch,
        removeBottomSpacePatch,
        saveCommentPhotoPatch,
        savedOnProfilePatch,
        saveProfilePicturePatch,
        showHiddenLikeCountsPatch,
        showLastActivePatch,
        showPostTimePatch,
        showReelTimePatch,
        showStoryTimePatch,
        skipCollectionsTrayPatch,
        smoothStoryOpenPatch,
        spoofLocationPatch,
        appProxyPatch,
        skipSetupPopupsPatch,
        skipAdDataReminderPatch,
        startOnFollowingPatch,
        stopHeartbeatPatch,
        stopReelsScrollingPatch,
        stopStoryAutoAdvancePatch,
        stopSwipeToCreatePatch,
        stopTabSwipingPatch,
        storyMentionsPatch,
        storyRingSizePatch,
        storyProgressBarPatch,
        storySeekBarPatch,
        tabBarLayoutPatch,
        tapToPlayPatch,
        turnOffDoubleTapLikePatch,
        turnOffHdrBoostsPatch,
        stopVideoLoopingPatch,
        viewLiveAnonymouslyPatch,
        viewStoriesAnonymouslyPatch,
        startFeedVideosWithSoundPatch,
        tapFeedVideoToPausePatch,
        feedSeekBarPatch,
        startStoriesWithSoundPatch,
        startReelsWithSoundPatch,
        pinchZoomReelsPatch,
        refreshRatePatch,
        stopSwipeReplyPatch,
        uploadStoriesAtFullQualityPatch,
        storyFontsPatch,
        longPressZoomPatch,
        roundedPostCornersPatch,
        carouselArrowsPatch,
        ambientLightPatch,
        fullScreenBarsPatch,
        swipeToProfilePatch,
        hideSettingsRowsPatch,
    )

    private val prior = listOf<Patch<*>>(
        defaultPlaybackQualityPatch,
        downloadReelPatch,
        downloadStoryPatch,
        friendshipStatusPatch,
        hideAdsPatch,
        hideMetaAiPatch,
        hideSuggestedPostsPatch,
        hideSuggestedStoriesPatch,
        keepReelSpeedPatch,
        openLinksExternallyPatch,
        removeAdIdPatch,
        removeBuildExpiredPopupPatch,
        restoreTrustPatch,
        resumeLongVideosPatch,
        sanitizeSharingLinksPatch,
        settingsPatch,
        translatedStartPatch,
    )

    private val optIn = listOf<Patch<*>>(
        changeVersionCodePatch,
        installNextToInstagramPatch,
        pureBlackPatch,
        viewDmMediaAnonymouslyPatch,
    )

    @Test fun existingDefaultsRetainTheirSelections() {
        assertEquals(17, prior.size)
        prior.forEach { assertEquals(it.name, true, it.default) }
    }

    @Test fun initiallyNeutralControlsAreAvailableInSimpleMode() {
        assertEquals(119, neutral.size)
        neutral.forEach { assertEquals(it.name, true, it.default) }
    }

    @Test fun patchTimeChangesAndUnreadyFeaturesStayOptIn() {
        assertEquals(4, optIn.size)
        optIn.forEach { assertEquals(it.name, false, it.default) }
    }

    @Test fun generatedCatalogMatchesAllReviewedDeclarations() {
        val all = prior + neutral + optIn
        val declarations = all.associate { it.name!! to it.default }
        assertEquals("every named patch needs one reviewed decision", 140, all.size)
        assertEquals("the review must not name a patch twice", all.size, declarations.size)
        val file = File("patches-list.json").takeIf(File::isFile) ?: File("../patches-list.json")
        val rows = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("patches")
        val generated = rows.associate { row ->
            row.asJsonObject.let { it["name"].asString to it["use"].asBoolean }
        }
        assertEquals("the catalog must not silently drop or duplicate a patch", declarations.size, rows.size())
        assertEquals("regenerate the catalog from the declarations", declarations, generated)
    }
}
