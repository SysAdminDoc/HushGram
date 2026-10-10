/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

@Suppress("unused")
val copyCommentPatch = bytecodePatch(
    name = "Copy comment",
    description = "Adds Copy and Copy username to the menu you get on a comment, so you can copy its text or the " +
        "commenter's username. Starts off. Turn it on in HushGram settings > Comments.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("commentCopy")
        requireStatusMethod(COMMENT_AUTHOR_STATUS)
        val menu = findCommentMenu()
        applyCommentMenu(menu)
        if (menu.author != null) enableStatus(COMMENT_AUTHOR_STATUS)
        enableStatus("commentCopy")
    }
}

/** The status of the second switch, Copy the commenter's username, which a build can lack while Copy goes in (#35). */
internal const val COMMENT_AUTHOR_STATUS = "commentAuthor"

/** Validate the extension too, so a missing bridge can't leave the native renderer half changed. */
internal fun BytecodePatchContext.validateCommentStubs() {
    validateCommentHook()
    stub(COMMENT_NATIVE, "originalText", listOf(OBJECT), STRING)
    validateActionRow(COPY_ROW, COMMENT_NATIVE)
}

/** Only called after discovery and every accessibility/register/stub check succeeded. */
internal fun BytecodePatchContext.applyCommentMenu(menu: CommentMenu) {
    val text = stub(COMMENT_NATIVE, "originalText", listOf(OBJECT), STRING)
    applyCommentHook(menu.surface)
    replace(text, 2, """
        instance-of v0, p0, ${menu.text.definingClass}
        if-eqz v0, :unsupported
        check-cast p0, ${menu.text.definingClass}
        iget-object p0, p0, ${menu.text}
        return-object p0
        :unsupported
        const/4 v0, 0x0
        return-object v0
    """)
    applyActionRow(menu.surface, COPY_ROW, COMMENT_NATIVE, menu.icon, menu.label)
    menu.author?.let { applyCopyAuthor(menu.surface, it, menu.icon, menu.label) }
}
