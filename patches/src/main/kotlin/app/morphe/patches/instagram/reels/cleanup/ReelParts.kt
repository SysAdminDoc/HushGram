/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE

private const val DECLUTTER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;"
internal const val HIDE_FOLLOW_BUTTON = "$DECLUTTER->hideFollowButton()Z"
internal const val HIDE_CHIPS = "$DECLUTTER->hideChips()Z"
internal const val HIDE_SOCIAL_FOOTER = "$DECLUTTER->hideSocialFooter()Z"
internal const val HIDE_COMMENT_BAR = "$DECLUTTER->hideCommentBar(Ljava/lang/Object;)Z"

/**
 * A part of the Reels viewer the patch hides: the method whose marker ends in [marker], and the
 * extension hook asked first thing in it. A render answers nothing when the hook says to hide, and
 * the check answers no.
 */
internal data class ReelPart(val marker: String, val hook: String, val check: Boolean = false)

/** The Follow button beside a reel's author. The suggested accounts' cards draw a Legacy one, which stays. */
internal val FOLLOW_PARTS = listOf(ReelPart("ClipsFollowButtonComponent_render", HIDE_FOLLOW_BUTTON))

/**
 * The pills that prompt you to make something or promote something. On Instagram 449 the first
 * four and the glasses pills sit above the author, built by the component that also draws a live
 * badge and a state-controlled media label, which have renders of their own and stay. The Meta AI
 * pill sits in the reel's media info.
 */
internal val CHIP_PARTS = listOf(
    "ClipsBaselAttributionPillComponent_render",
    "ClipsBaselCreativeToolAttributionPillComponent_render",
    "ClipsBaselPlatformizedCreativeToolAttributionComponent_render",
    "StoriesTemplatePillComponent_render",
    "ClipsMetaAiPillComponent_render",
    "MetaAffiliateCTAComponent_render",
    "ClipsWearablesAttributionBlackToGradientPillComponent_render",
    "ClipsWearablesAttributionBluePillComponent_render",
    "ClipsWearablesAttributionRimLightInnerPillComponent_render",
    "ClipsWearablesAttributionSemiTransparentPillComponent_render",
    "ClipsWearablesAttributionShopAIGlassesSemiTransparentPillComponent_render",
    "ClipsWearablesAttributionShopRBMGlassesBlackPillComponent_render",
    "ClipsWearablesAttributionShopRBMGlassesSemiTransparentPillComponent_render",
).map { ReelPart(it, HIDE_CHIPS) }

/**
 * Friends' activity and the comment preview. The bubbles of friends' likes, comments and follows
 * go above the author only when this check answers yes, and the media info draws the other two.
 */
internal val SOCIAL_PARTS = listOf(
    ReelPart("ClipsInfoOverlayUseCase_shouldShowFloatingBubblesAboveUsername", HIDE_SOCIAL_FOOTER, check = true),
    ReelPart("ClipsInlineCommentSocialContextComponent_render", HIDE_SOCIAL_FOOTER),
    ReelPart("ClipsFriendlyViewerComponent_render", HIDE_SOCIAL_FOOTER),
)

internal val REEL_PARTS = FOLLOW_PARTS + CHIP_PARTS + SOCIAL_PARTS

internal const val HIDE_SOCIAL_CONTEXT = "$DECLUTTER->hideSocialContext(Ljava/lang/Object;)Z"

/**
 * The use case that works out a reel's floating bubbles (friends' likes, comments and notes). When
 * there are none to show it answers a state of its own, and the reel then builds no bubbles at all,
 * wherever they'd have gone.
 */
internal const val FLOATING_BUBBLES = "FloatingBubblesUseCase_getUiState"

/**
 * Instagram's check for leaving out a reel's social context line, the faces with Liked by or
 * Followed by beside them. The line under the author and the one at a reel's end are only shown
 * once this answers no. It's handed the line, whose type is an enum naming what it says.
 */
internal const val SOCIAL_CONTEXT_CHECK = "MediaSocialContextViewUtil_shouldHideSocialContextForClips"

/** Types the social context's enum has to name, so the check is known to be handed that line. */
internal val SOCIAL_CONTEXT_TYPES = listOf("FOLLOWED_BY", "LIKED_BY")

/**
 * The controller of the Add comment bar under a reel opened outside the Reels tab, such as from a
 * profile's reposts: its show, the hide Instagram calls itself, its onViewCreated, which inflates
 * the bar and keeps it, and its unVanish, which puts the bar back after the viewer hid it for a
 * while without going through the show (on 450 a lambda outside the controller holds it).
 */
internal const val COMMENT_BAR_SHOW = "ClipsViewerCommentBarController_showCommentBar"
internal const val COMMENT_BAR_HIDE = "ClipsViewerCommentBarController_hideCommentBar"
internal const val COMMENT_BAR_CREATED = "ClipsViewerCommentBarController_onViewCreated"
internal const val COMMENT_BAR_UNVANISH = "ClipsViewerCommentBarController_unVanishCommentBar"

/** Where the viewer was opened from, an enum that keeps its name, which the controller holds. */
internal const val CLIPS_VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"

internal val CLEANUP_MARKERS = REEL_PARTS.map { it.marker } + FLOATING_BUBBLES + SOCIAL_CONTEXT_CHECK +
    COMMENT_BAR_SHOW + COMMENT_BAR_HIDE + COMMENT_BAR_CREATED + COMMENT_BAR_UNVANISH
