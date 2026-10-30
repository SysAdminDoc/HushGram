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
 * Save in the common comment menu, for a photo or GIF the comment itself carries.
 *
 * The selected comment keeps the raw comment it was converted from, and that keeps its own
 * media_comment_info, parsed from the server's JSON or read from its tree, with the photo's Media
 * inside. The extension asks a bridge for that Media and lists the sizes Instagram supplied, so the
 * save picks the largest without building an address. The parent post's media_info is never read.
 * A GIF comment's giphy_media_info lists Giphy's fixed_height rendition twice, Instagram's own copy
 * and Giphy's, each with a GIF, WebP and MP4 address; the bridges read those as they are, and the
 * extension keeps only the ones on Meta's servers. The row shares the renderer's one call with Copy
 * comment and has its own row type, so either patch works alone or with the other.
 *
 * Its second switch, Reply with a comment's GIF (#402), adds a GIF row to a comment carrying a GIF.
 * The comment screen's selection hands the extension the screen and the two IDs it resolved the
 * comment from, and the row runs the screen's own Reply on them, then its GIF send with the comment's
 * GIF, so the reply goes up animated like one picked in Instagram's GIF search. A build where those
 * can't be told still gets Save, without the switch.
 */
@Suppress("unused")
val saveCommentPhotoPatch = bytecodePatch(
    name = "Save comment photo",
    description = "Adds Save to the menu on a comment that has its own photo or GIF, saving the largest size " +
        "Instagram sent. A GIF is saved as a .gif in Pictures/Instagram, taken from Instagram's own copy, not " +
        "Giphy. If Instagram lists only an animated WebP or an MP4 for it, you get that file instead, and an MP4 " +
        "goes in Movies/Instagram like a video. It can also add a GIF reply to a comment with a GIF, sending " +
        "that same GIF back as your reply. Starts off. Turn it on in HushGram settings > Comments.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("commentPhoto")
        requireStatusMethod(GIF_REPLY_STATUS)
        val photo = findCommentPhoto()
        applyCommentPhoto(photo)
        enableStatus("commentPhoto")
        if (photo.gifReply != null) enableStatus(GIF_REPLY_STATUS)
    }
}
