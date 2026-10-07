/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.util

import app.morphe.patches.instagram.ads.hideAdsPatch
import app.morphe.patches.instagram.direct.notes.hideNotesRowPatch
import app.morphe.patches.instagram.direct.seen.viewDmMediaAnonymouslyPatch
import app.morphe.patches.instagram.download.reel.downloadReelPatch
import app.morphe.patches.instagram.download.story.downloadStoryPatch
import app.morphe.patches.instagram.download.video.downloadVideoPatch
import app.morphe.patches.instagram.explore.hideExploreGridPatch
import app.morphe.patches.instagram.feed.following.startOnFollowingPatch
import app.morphe.patches.instagram.feed.reels.hideFeedReelsPatch
import app.morphe.patches.instagram.feed.suggested.hideSuggestedPostsPatch
import app.morphe.patches.instagram.feed.swipecreate.stopSwipeToCreatePatch
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
import app.morphe.patches.instagram.misc.developeroptions.openDeveloperOptionsPatch
import app.morphe.patches.instagram.direct.ephemeral.makeEphemeralPermanentPatch
import app.morphe.patches.instagram.media.images.improveImageViewingPatch
import app.morphe.patches.instagram.misc.glass.glassTabBarPatch
import app.morphe.patches.instagram.privacy.disableScreenshotDetectionPatch
import app.morphe.patches.instagram.privacy.disableTypingStatusPatch
import app.morphe.patches.instagram.privacy.viewChatsAnonymouslyPatch
import app.morphe.patches.instagram.privacy.viewLiveAnonymouslyPatch
import app.morphe.patches.instagram.misc.externalbrowser.openLinksExternallyPatch
import app.morphe.patches.instagram.misc.resignedtrust.restoreTrustPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.misc.sharelinks.sanitizeSharingLinksPatch
import app.morphe.patches.instagram.misc.theme.pureBlackPatch
import app.morphe.patches.instagram.misc.translatedstart.translatedStartPatch
import app.morphe.patches.instagram.profile.friendship.friendshipStatusPatch
import app.morphe.patches.instagram.profile.highlights.hideHighlightsPatch
import app.morphe.patches.instagram.profile.suggested.hideProfileSuggestionsPatch
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
import app.morphe.patches.instagram.share.hideShareSheetGroupPatch
import app.morphe.patches.instagram.stories.autoadvance.stopStoryAutoAdvancePatch
import app.morphe.patches.instagram.stories.loop.loopStoryPatch
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
        copyCommentPatch,
        hideHighlightsPatch,
        hideNotesRowPatch,
        saveCommentPhotoPatch,
        stopReelsScrollingPatch,
        stopSwipeToCreatePatch,
        storyRingSizePatch,
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
        cleanUpReelsPatch,
        disableScreenshotDetectionPatch,
        disableTypingStatusPatch,
        glassTabBarPatch,
        improveImageViewingPatch,
        makeEphemeralPermanentPatch,
        dontSendReelWatchHistoryPatch,
        downloadVideoPatch,
        hideExploreGridPatch,
        hideFeedReelsPatch,
        hideProfileSuggestionsPatch,
        hideReelsSuggestionsPatch,
        hideReelsTabPatch,
        hideRepostButtonPatch,
        hideShareSheetGroupPatch,
        keepReelsAutoScrollPatch,
        loopStoryPatch,
        openDeveloperOptionsPatch,
        pureBlackPatch,
        reelSeekBarPatch,
        removeBottomSpacePatch,
        showStoryTimePatch,
        startOnFollowingPatch,
        stopStoryAutoAdvancePatch,
        tapToPlayPatch,
        turnOffDoubleTapLikePatch,
        viewChatsAnonymouslyPatch,
        viewDmMediaAnonymouslyPatch,
        viewLiveAnonymouslyPatch,
        viewStoriesAnonymouslyPatch,
    )

    @Test fun existingDefaultsRetainTheirSelections() {
        assertEquals(18, prior.size)
        prior.forEach { assertEquals(it.name, true, it.use) }
    }

    @Test fun initiallyNeutralControlsAreAvailableInSimpleMode() {
        assertEquals(7, neutral.size)
        neutral.forEach { assertEquals(it.name, true, it.use) }
    }

    @Test fun immediateBehaviorChangesAndUnacceptedDmReceiptsStayOptIn() {
        assertEquals(30, optIn.size)
        optIn.forEach { assertEquals(it.name, false, it.use) }
    }

    @Test fun generatedCatalogMatchesAllReviewedDeclarations() {
        val all = prior + neutral + optIn
        val declarations = all.associate { it.name!! to it.use }
        assertEquals("every named patch needs one reviewed decision", 55, all.size)
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
