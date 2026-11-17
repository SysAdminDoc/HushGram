/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.blocked

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * A search box above the blocked accounts list that finds an account by user name or name, loading
 * the rest of the list as the user types (#731). Included in the default selection with its switch
 * off, so it's the user's pick.
 */
@Suppress("unused")
val searchBlockedAccountsPatch = bytecodePatch(
    name = "Search blocked accounts",
    description = "Adds a search box above your blocked accounts that finds them by username or name. " +
        "While you type, the rest of your list loads page by page, and a line under the box says how many " +
        "matched out of how many loaded. Starts off. Turn it on in HushGram settings > Ads and privacy.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod(BLOCKED_SEARCH_STATUS)
        addBlockedSearch(findBlockedSearch())
        enableStatus(BLOCKED_SEARCH_STATUS)
    }
}
