/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.postslist

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val POSTS_LIST_PATCH = "Profile posts as a list"

/**
 * Opens a profile's posts in Instagram's own scrolling list of full posts, the one a tapped grid post
 * opens, with the grid one Back away. 450 has no list layout for the grid itself, so this is the
 * cheaper of the two and the one that gives full posts. In the default selection with its switch off.
 */
@Suppress("unused")
val profilePostsListPatch = bytecodePatch(
    name = "Profile posts as a list",
    description = "Opening someone's profile takes you on to their posts as a scrolling list of full posts. Back " +
        "shows the grid. Your own profile keeps its grid. Starts off. Turn it on in HushGram settings > Profiles.",
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("profilePostsList")
        tellOnResume()
        enableStatus("profilePostsList")
    }
}

/**
 * Hands the extension the tab first thing in the posts tab's onResume. One call that reads only the
 * tab and returns nothing, so no register or branch of Instagram's changes. The extension decides
 * whether this tab's first post is opened, and does it after onResume has returned.
 */
internal fun BytecodePatchContext.tellOnResume() {
    findPostsTabResume().addInstructions(0, "invoke-static/range { p0 .. p0 }, $RESUMED")
}
