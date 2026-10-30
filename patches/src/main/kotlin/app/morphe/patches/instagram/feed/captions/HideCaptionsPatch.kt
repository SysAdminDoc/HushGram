/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.captions

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val HIDE_CAPTIONS_STATUS = "hideCaptions"

/**
 * Takes the caption off feed posts and reels (#395). See HideCaptions in the extension.
 *
 * Instagram 453 draws a feed post's caption as a declarative component with no view of its own, so
 * the patch asks the extension at the start of the caption's render and a yes draws nothing (see
 * [findFeedCaption]). A reel's caption is a view, which the extension sets gone before a frame
 * shows it, found by the id Instagram's own layout gives it, so the rows around it close up. The
 * application-start hook the settings patch already writes starts that watching. The patch switches
 * the status method on, and the settings screen then offers the switch. In the default selection
 * with its switch off.
 */
@Suppress("unused")
val hideCaptionsPatch = bytecodePatch(
    name = "Hide captions",
    description = "Takes the caption off posts in your feed and off reels. The post stays, and the comments sheet " +
        "still shows it. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(HIDE_CAPTIONS_STATUS)
        findFeedCaption().write(this)
        enableStatus(HIDE_CAPTIONS_STATUS)
    }
}
