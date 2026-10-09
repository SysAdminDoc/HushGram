/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Save in the common comment menu, for a photo the comment itself carries.
 *
 * The selected comment keeps the raw comment it was converted from, and that keeps its own
 * media_comment_info, parsed from the server's JSON or read from its tree, with the photo's Media
 * inside. The extension asks a bridge for that Media and lists the sizes Instagram supplied, so the
 * save picks the largest without building an address. The parent post's media_info is never read.
 * The row shares the renderer's one call with Copy comment and has its own row type, so either
 * patch works alone or with the other.
 */
@Suppress("unused")
val saveCommentPhotoPatch = bytecodePatch(
    name = "Save comment photo",
    description = "Adds Save to the menu on a comment that has its own photo, saving the largest size Instagram " +
        "sent. Starts off. Turn it on in HushGram settings > Comments.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("commentPhoto")
        val photo = findCommentPhoto()
        applyCommentPhoto(photo)
        enableStatus("commentPhoto")
    }
}
