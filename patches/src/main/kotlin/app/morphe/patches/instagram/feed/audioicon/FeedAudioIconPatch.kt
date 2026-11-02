/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.audioicon

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val FEED_AUDIO_ICON_STATUS = "feedAudioIcon"

/**
 * Takes the small speaker icon off feed videos (#464). See FeedAudioIcon in the extension.
 *
 * Instagram 453 builds one state object for each post it draws, and that state holds a flag its own
 * drawing code reads before it draws the icon or the icon's slide-in view. The patch hands the flag
 * to the extension as the constructor stores it (see [findFeedAudioIcon]). A tap on the video still
 * toggles sound, because nothing about the tap is touched. The patch switches the status method on,
 * and the settings screen then offers the switch. In the default selection with its switch off.
 */
@Suppress("unused")
val feedAudioIconPatch = bytecodePatch(
    name = "Hide the audio icon on posts",
    description = "Takes the speaker icon off videos in your feed. Tapping the video still turns the sound on or off. " +
        "Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(FEED_AUDIO_ICON_STATUS)
        findFeedAudioIcon().write(this)
        enableStatus(FEED_AUDIO_ICON_STATUS)
    }
}
