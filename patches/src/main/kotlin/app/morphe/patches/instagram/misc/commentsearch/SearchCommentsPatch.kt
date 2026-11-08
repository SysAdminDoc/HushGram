/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.commentsearch

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.misc.theme.findCommentsScreens
import app.morphe.patches.instagram.misc.theme.hookReturns
import app.morphe.patches.instagram.misc.theme.isViewCreated
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode

internal const val COMMENT_SEARCH_STATUS = "commentSearch"

private const val PATCH = "Search comments"

/** Handed each comments sheet screen and its view as the screen finishes making the view. */
internal const val COMMENT_SEARCH_SHOWN = "$EXTENSION_PACKAGE/comment/CommentSearch;->contentShown(Ljava/lang/Object;Ljava/lang/Object;)V"

/**
 * Search comments (#560). Instagram's comments sheet has no search, so on a post with thousands of
 * comments the only way to find one is to scroll. Each comments sheet screen's onViewCreated hands
 * the screen and its view to the extension before it returns, and the extension puts a search box
 * above the list: Enter or the arrows scroll the list to the next or previous comment holding the
 * words or name typed in, loading more as they go.
 *
 * The call goes before each return rather than first, since Pure black dark mode's call is the
 * first instruction of the same methods, and by then the view the screen made is complete. The
 * screen and the view are read from `this` and the first parameter, so the patch refuses a screen
 * whose code writes over either before a return.
 */
@Suppress("unused")
val searchCommentsPatch = bytecodePatch(
    name = "Search comments",
    description = "Adds a search box above a post's comments. Type a word or a name and the comments scroll to " +
        "the next one that has it, loading more as they go. The arrows step forward and back through the " +
        "matches. Replies you haven't opened aren't searched. Starts off. " +
        "Turn it on in HushGram settings > Comments.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod(COMMENT_SEARCH_STATUS)
        addCommentSearch(findCommentsScreens(PATCH))
        enableStatus(COMMENT_SEARCH_STATUS)
    }
}

/** Before each return-void of each of [screens]' onViewCreated, hands the screen and its view to [COMMENT_SEARCH_SHOWN]. */
internal fun BytecodePatchContext.addCommentSearch(screens: List<String>) {
    val methods = screens.map { type -> mutableClassDefBy(type).methods.single { it.isViewCreated() } }
    for (method in methods) {
        val returns = method.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        method.requireThisIntact(PATCH, returns)
        method.requireParameterIntact(PATCH, 0, returns)
    }
    // The range form: the big screens keep p0 above v15, out of a plain invoke's reach.
    for (method in methods) method.hookReturns("invoke-static/range { p0 .. p1 }, $COMMENT_SEARCH_SHOWN", PATCH)
}
