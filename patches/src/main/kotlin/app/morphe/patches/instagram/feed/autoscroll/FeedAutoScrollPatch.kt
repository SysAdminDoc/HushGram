/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.autoscroll

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val FEED_AUTO_SCROLL_STATUS = "feedAutoScroll"

/**
 * Moves Home on to the next post by itself (#660). See FeedAutoScroll in the extension.
 *
 * Home's list hands itself to the extension as it's made, and the extension looks at it on a short
 * timer from then on. The feed video player tells the extension when a video starts over or ends,
 * and every touch on an Instagram screen is shown to it first, so a finger on the screen holds the
 * move. Nothing Instagram does is changed by the hooks; they only read. The patch switches the status
 * method on, and the settings screen then offers the switch. In the default selection with its
 * switch off.
 */
@Suppress("unused")
val feedAutoScrollPatch = bytecodePatch(
    name = "Auto scroll in Home",
    description = "Moves Home on to the next post by itself. A video moves on when it ends, a photo or carousel after " +
        "the time you pick. Touching the screen holds it. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(FEED_AUTO_SCROLL_STATUS)
        findFeedAutoScroll().write(this)
        enableStatus(FEED_AUTO_SCROLL_STATUS)
    }
}
