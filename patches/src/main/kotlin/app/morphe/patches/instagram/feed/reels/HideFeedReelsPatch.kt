/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.reels

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.filterParsedFeedItems
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Hide Reels in the feed"
internal const val FILTER = "$EXTENSION_PACKAGE/reels/FeedReels;->filter(Ljava/lang/Object;)Ljava/lang/Object;"

/** The feed item types FeedReels drops, which the item's type enum has to name. */
internal val REEL_UNITS = listOf("CLIPS_NETEGO", "IMMERSIVE_SEGUE_ITEM", "VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT")

@Suppress("unused")
val hideFeedReelsPatch = bytecodePatch(
    name = "Hide Reels in the feed",
    description = "Removes the rows of suggested reels between posts in your home feed, and the other units that " +
        "open the Reels viewer from there. A reel someone you follow posts stays. Off until you turn it on. Switch: " +
        "HushGram settings > Reels.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        filterParsedFeedItems()
        enableStatus("feedReels")
    }
}

/**
 * Passes each feed item Instagram's static parse helper answers through the extension, which
 * answers null for a row of suggested reels and the like.
 */
internal fun BytecodePatchContext.filterParsedFeedItems() = filterParsedFeedItems(PATCH, FILTER, REEL_UNITS)
