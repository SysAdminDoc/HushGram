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

internal const val EXPAND_CAPTIONS_STATUS = "expandCaptions"

/**
 * Shows more of a feed post's caption before "more" (#771). See CaptionLength in the extension.
 *
 * Instagram builds a caption's text in one place for every surface and cuts it with a helper that
 * takes the line count to stop at, one or two lines depending on the feed. The patch asks the
 * extension for that count right before the cut, with the surface the post shows on, so the feeds
 * get the count picked in settings, reels the whole caption on their own switch (#752), and stories
 * keep Instagram's (see [findCaptionTrim]). The Reels tab's own caption, which Instagram 456 draws
 * without that builder, asks the same reels switch where it settles its line count (#821, see
 * [findReelCaptionLines]).
 * The patch switches the status method on, and the settings screen then offers the switch. In the
 * default selection with its switch off.
 */
@Suppress("unused")
val expandCaptionsPatch = bytecodePatch(
    name = "Expand captions",
    description = "Shows the whole caption on posts in your feed, or as many lines as you pick, before \"more\", " +
        "and on a switch of its own the whole caption on reels. Stories stay as they are. Starts off. " +
        "Turn them on in HushGram settings > Feed and Reels.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(EXPAND_CAPTIONS_STATUS)
        val reels = findReelCaptionLines()
        findCaptionTrim().write(this)
        reels.forEach { it.write(this) }
        enableStatus(EXPAND_CAPTIONS_STATUS)
    }
}
