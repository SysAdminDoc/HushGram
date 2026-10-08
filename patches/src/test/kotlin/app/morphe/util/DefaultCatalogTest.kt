/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.util

import app.morphe.patches.instagram.ads.hideAdsPatch
import app.morphe.patches.instagram.direct.calls.askBeforeCallPatch
import app.morphe.patches.instagram.feed.confirm.askBeforeLikePatch
import app.morphe.patches.instagram.feed.confirm.askBeforeRefreshPatch
import app.morphe.patches.instagram.direct.instants.hideInstantsPatch
import app.morphe.patches.instagram.direct.lock.lockMessagesPatch
import app.morphe.patches.instagram.stories.live.viewLiveAnonymouslyPatch
import app.morphe.patches.instagram.direct.keep.keepInChatPatch
import app.morphe.patches.instagram.direct.screenshots.allowScreenshotsPatch
import app.morphe.patches.instagram.direct.screenshots.dontReportScreenshotsPatch
import app.morphe.patches.instagram.direct.notes.hideNotesRowPatch
import app.morphe.patches.instagram.direct.seen.readWithoutSeenReceiptPatch
import app.morphe.patches.instagram.direct.seen.viewDmMediaAnonymouslyPatch
import app.morphe.patches.instagram.direct.typing.hideTypingPatch
import app.morphe.patches.instagram.download.reel.downloadReelPatch
import app.morphe.patches.instagram.download.story.downloadStoryPatch
import app.morphe.patches.instagram.download.video.downloadVideoPatch
import app.morphe.patches.instagram.explore.hideExploreGridPatch
import app.morphe.patches.instagram.explore.recent.dontSaveRecentSearchesPatch
import app.morphe.patches.instagram.feed.comments.hideCommentsPatch
import app.morphe.patches.instagram.feed.following.startOnFollowingPatch
import app.morphe.patches.instagram.feed.home.hideHomeFeedPatch
import app.morphe.patches.instagram.feed.photos.fullResolutionPhotosPatch
import app.morphe.patches.instagram.feed.reels.hideFeedReelsPatch
import app.morphe.patches.instagram.feed.suggested.hideSuggestedPostsPatch
import app.morphe.patches.instagram.feed.swipecreate.stopSwipeToCreatePatch
import app.morphe.patches.instagram.feed.tabswipe.stopTabSwipingPatch
import app.morphe.patches.instagram.feed.time.showPostTimePatch
import app.morphe.patches.instagram.media.hdr.turnOffHdrBoostsPatch
import app.morphe.patches.instagram.misc.cache.clearMediaCachePatch
import app.morphe.patches.instagram.misc.location.spoofLocationPatch
import app.morphe.patches.instagram.misc.notifications.groupNotificationsPatch
import app.morphe.patches.instagram.media.quality.dataSaverPatch
import app.morphe.patches.instagram.media.quality.defaultPlaybackQualityPatch
import app.morphe.patches.instagram.media.resume.resumeLongVideosPatch
import app.morphe.patches.instagram.media.taptoplay.tapToPlayPatch
import app.morphe.patches.instagram.metaai.hideMetaAiPatch
import app.morphe.patches.instagram.misc.adid.removeAdIdPatch
import app.morphe.patches.instagram.misc.analytics.disableAnalyticsPatch
import app.morphe.patches.instagram.misc.bottomspace.removeBottomSpacePatch
import app.morphe.patches.instagram.misc.buildexpiry.removeBuildExpiredPopupPatch
import app.morphe.patches.instagram.misc.comment.copyCommentPatch
import app.morphe.patches.instagram.misc.comment.saveCommentPhotoPatch
import app.morphe.patches.instagram.download.profile.saveProfilePicturePatch
import app.morphe.patches.instagram.download.voice.downloadVoiceMessagesPatch
import app.morphe.patches.instagram.misc.developeroptions.openDeveloperOptionsPatch
import app.morphe.patches.instagram.direct.saved.saveDeletedMessagesPatch
import app.morphe.patches.instagram.misc.emoji.emojiStylePatch
import app.morphe.patches.instagram.misc.glass.glassTabBarPatch
import app.morphe.patches.instagram.misc.profilesaved.savedOnProfilePatch
import app.morphe.patches.instagram.misc.externalbrowser.openLinksExternallyPatch
import app.morphe.patches.instagram.misc.resignedtrust.restoreTrustPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.misc.sharelinks.sanitizeSharingLinksPatch
import app.morphe.patches.instagram.misc.theme.pureBlackPatch
import app.morphe.patches.instagram.misc.translatedstart.translatedStartPatch
import app.morphe.patches.instagram.misc.versioncode.changeVersionCodePatch
import app.morphe.patches.instagram.profile.friendship.friendshipStatusPatch
import app.morphe.patches.instagram.profile.highlights.hideHighlightsPatch
import app.morphe.patches.instagram.profile.suggested.hideProfileSuggestionsPatch
import app.morphe.patches.instagram.profile.threads.hideThreadsButtonPatch
import app.morphe.patches.instagram.reels.autoscroll.keepReelsAutoScrollPatch
import app.morphe.patches.instagram.reels.cleanup.cleanUpReelsPatch
import app.morphe.patches.instagram.reels.doubletap.turnOffDoubleTapLikePatch
import app.morphe.patches.instagram.reels.scrolling.stopReelsScrollingPatch
import app.morphe.patches.instagram.reels.seekbar.reelSeekBarPatch
import app.morphe.patches.instagram.reels.speed.keepReelSpeedPatch
import app.morphe.patches.instagram.reels.suggested.hideReelsSuggestionsPatch
import app.morphe.patches.instagram.reels.tab.hideReelsTabPatch
import app.morphe.patches.instagram.reels.watchhistory.dontSendReelWatchHistoryPatch
import app.morphe.patches.instagram.share.hideRepostButtonPatch
import app.morphe.patches.instagram.share.hideShareButtonPatch
import app.morphe.patches.instagram.feed.likes.changeLikeAnimationPatch
import app.morphe.patches.instagram.share.hideShareSheetGroupPatch
import app.morphe.patches.instagram.stories.autoadvance.stopStoryAutoAdvancePatch
import app.morphe.patches.instagram.stories.loop.loopStoryPatch
import app.morphe.patches.instagram.stories.mentions.storyMentionsPatch
import app.morphe.patches.instagram.stories.ring.storyRingSizePatch
import app.morphe.patches.instagram.stories.seen.viewStoriesAnonymouslyPatch
import app.morphe.patches.instagram.stories.time.showStoryTimePatch
import app.morphe.patches.instagram.stories.tray.hideSuggestedStoriesPatch
import app.morphe.patcher.patch.Patch
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reviewed selection policy for every named patch, separate from each saved runtime choice. */
class DefaultCatalogTest {
    private val neutral = listOf<Patch<*>>(
        askBeforeCallPatch,
        askBeforeLikePatch,
        askBeforeRefreshPatch,
        changeLikeAnimationPatch,
        clearMediaCachePatch,
        copyCommentPatch,
        dontSaveRecentSearchesPatch,
        emojiStylePatch,
        fullResolutionPhotosPatch,
        dataSaverPatch,
        groupNotificationsPatch,
        hideCommentsPatch,
        hideHighlightsPatch,
        hideThreadsButtonPatch,
        hideShareButtonPatch,
        hideHomeFeedPatch,
        hideInstantsPatch,
        hideNotesRowPatch,
        lockMessagesPatch,
        viewLiveAnonymouslyPatch,
        keepInChatPatch,
        allowScreenshotsPatch,
        dontReportScreenshotsPatch,
        saveCommentPhotoPatch,
        saveProfilePicturePatch,
        downloadVoiceMessagesPatch,
        stopReelsScrollingPatch,
        stopSwipeToCreatePatch,
        stopTabSwipingPatch,
        storyRingSizePatch,
        turnOffHdrBoostsPatch,
    )

    private val prior = listOf<Patch<*>>(
        defaultPlaybackQualityPatch,
        disableAnalyticsPatch,
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
        glassTabBarPatch,
        savedOnProfilePatch,
        saveDeletedMessagesPatch,
        changeVersionCodePatch,
        cleanUpReelsPatch,
        dontSendReelWatchHistoryPatch,
        downloadVideoPatch,
        hideExploreGridPatch,
        hideFeedReelsPatch,
        hideProfileSuggestionsPatch,
        hideReelsSuggestionsPatch,
        hideReelsTabPatch,
        hideRepostButtonPatch,
        hideShareSheetGroupPatch,
        hideTypingPatch,
        keepReelsAutoScrollPatch,
        loopStoryPatch,
        openDeveloperOptionsPatch,
        pureBlackPatch,
        readWithoutSeenReceiptPatch,
        reelSeekBarPatch,
        removeBottomSpacePatch,
        showPostTimePatch,
        showStoryTimePatch,
        spoofLocationPatch,
        startOnFollowingPatch,
        stopStoryAutoAdvancePatch,
        storyMentionsPatch,
        tapToPlayPatch,
        turnOffDoubleTapLikePatch,
        viewDmMediaAnonymouslyPatch,
        viewStoriesAnonymouslyPatch,
    )

    @Test fun existingDefaultsRetainTheirSelections() {
        assertEquals(18, prior.size)
        prior.forEach { assertEquals(it.name, true, it.use) }
    }

    @Test fun initiallyNeutralControlsAreAvailableInSimpleMode() {
        assertEquals(31, neutral.size)
        neutral.forEach { assertEquals(it.name, true, it.use) }
    }

    @Test fun immediateBehaviorChangesAndUnacceptedDmReceiptsStayOptIn() {
        assertEquals(32, optIn.size)
        optIn.forEach { assertEquals(it.name, false, it.use) }
    }

    @Test fun generatedCatalogMatchesAllReviewedDeclarations() {
        val all = prior + neutral + optIn
        val declarations = all.associate { it.name!! to it.use }
        assertEquals("every named patch needs one reviewed decision", 81, all.size)
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
