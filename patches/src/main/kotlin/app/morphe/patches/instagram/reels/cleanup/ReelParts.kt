/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE

private const val DECLUTTER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;"
internal const val HIDE_FOLLOW_BUTTON = "$DECLUTTER->hideFollowButton()Z"
internal const val HIDE_CHIPS = "$DECLUTTER->hideChips()Z"
internal const val HIDE_SEARCH_PILL = "$DECLUTTER->hideSearchPill()Z"
internal const val HIDE_PRODUCT_PILLS = "$DECLUTTER->hideProductPills()Z"
internal const val HIDE_POLL_PILLS = "$DECLUTTER->hidePollPills()Z"
internal const val HIDE_SOCIAL_FOOTER = "$DECLUTTER->hideSocialFooter()Z"
internal const val HIDE_EVERY_COMMENT_BAR = "$DECLUTTER->hideEveryCommentBar()Z"
internal const val HIDE_SAVE_BUTTON = "$DECLUTTER->hideSaveButton()Z"
internal const val HIDE_SAVE_COUNT = "$DECLUTTER->hideSaveCount()Z"
internal const val HIDE_COMMENT_BAR = "$DECLUTTER->hideCommentBar(Ljava/lang/Object;)Z"
internal const val HIDE_DIRECT_REPLY_BAR = "$DECLUTTER->hideDirectReplyBar(Ljava/lang/Object;)Z"

/**
 * A part of the Reels viewer the patch hides: the method whose marker ends in [marker], and the
 * extension hook asked first thing in it. A render answers nothing when the hook says to hide, and
 * the check answers no.
 */
internal data class ReelPart(val marker: String, val hook: String, val check: Boolean = false)

/**
 * The Follow button beside a reel's author, and the Subscribe button the author-info component draws
 * in its place for a creator who sells subscriptions (#248). Both are renders of their own, and the
 * Subscribe one is built only by the author-info component, so no other screen loses its button. The
 * suggested accounts' cards draw a Legacy Follow button, which stays.
 */
internal val FOLLOW_PARTS = listOf(
    ReelPart("ClipsFollowButtonComponent_render", HIDE_FOLLOW_BUTTON),
    ReelPart("SubscriptionCtaButtonComponent_render", HIDE_FOLLOW_BUTTON),
)

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

/**
 * The Add a comment bar the Reels tab draws under a reel on 450, a component of its own (the
 * controller below handles the bar of reels opened from elsewhere). Its render answers nothing when
 * the switch for all reels is on, and the reel's comment button still opens the comments.
 */
internal val COMMENT_BAR_PARTS = listOf(ReelPart("ClipsViewerInViewerCommentBarComponent_render", HIDE_EVERY_COMMENT_BAR))

/**
 * The Save button in a reel's right-hand button column, and the save count under it. On Instagram
 * 450 the reel's button column (the clips UFI) builds each button in a method of its own that answers
 * nothing when the reel has none, and the column adds only what it's handed, so an empty answer
 * closes up with no gap. They are not renders: each takes the component scope and two colors (a
 * long each) and is an instance method. A feed post's Save button is another component.
 */
internal val SAVE_PARTS = listOf(
    ReelPart("ClipsUfiComponent_maybeCreateSaveButtonComponent", HIDE_SAVE_BUTTON),
    ReelPart("ClipsUfiComponent_maybeCreateSaveCountComponent", HIDE_SAVE_COUNT),
)

/**
 * The overlays near the username that aren't a creation pill (#332): the suggested search terms
 * ("Deep dive"), the product tag and shopping buttons, and the poll or vote prompt. Each is a render
 * of the same shape as the pills above, so an empty answer leaves the row without it.
 */
internal val SEARCH_PARTS = listOf(ReelPart("ClipsContentDeepDiveComponent_render", HIDE_SEARCH_PILL))
internal val SHOP_PARTS = listOf("ProductTagCTAComponent_render", "ShoppingCtaComponent_render").map { ReelPart(it, HIDE_PRODUCT_PILLS) }
internal val POLL_PARTS = listOf(ReelPart("PollCTAComponent_render", HIDE_POLL_PILLS))

internal val REEL_PARTS = FOLLOW_PARTS + CHIP_PARTS + SEARCH_PARTS + SHOP_PARTS + POLL_PARTS + SOCIAL_PARTS + COMMENT_BAR_PARTS

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

/**
 * The controller of the reply bar under a reel opened with something to reply to, such as from a
 * chat (#177): the Reply to field with its quick reactions and plus button. Its show sets the bar
 * visible or gone by the controller's own hidden flag, its onViewCreated inflates the bar and calls
 * the hide when that flag is already set, and the hide is Instagram's own. On 450 the show and the
 * hide are merged into the controller's superclass, shared with the Spotlight reply bar, and reach
 * this controller's code through an instance check, so only the onViewCreated names its class.
 */
internal const val DIRECT_REPLY_BAR_SHOW = "ClipsViewerDirectReplyBarController_showReplyBar"
internal const val DIRECT_REPLY_BAR_HIDE = "ClipsViewerDirectReplyBarController_hideReplyBar"
internal const val DIRECT_REPLY_BAR_CREATED = "ClipsViewerDirectReplyBarController_onViewCreated"

/**
 * The source a chat hands the viewer of a reel shared in it, by the name the enum keeps. The
 * extension's hook answers yes for this one alone, so a build whose enum doesn't name it has no
 * chat for the switch to work on.
 */
internal const val CHAT_SOURCE = "DIRECT"

/** Where the viewer was opened from, an enum that keeps its name, which the controller holds. */
internal const val CLIPS_VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"

internal val CLEANUP_MARKERS = REEL_PARTS.map { it.marker } + SAVE_PARTS.map { it.marker } + FLOATING_BUBBLES + SOCIAL_CONTEXT_CHECK +
    COMMENT_BAR_SHOW + COMMENT_BAR_HIDE + COMMENT_BAR_CREATED + COMMENT_BAR_UNVANISH +
    DIRECT_REPLY_BAR_SHOW + DIRECT_REPLY_BAR_HIDE + DIRECT_REPLY_BAR_CREATED
