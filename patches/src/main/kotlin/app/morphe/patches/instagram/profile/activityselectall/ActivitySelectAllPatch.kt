/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.activityselectall

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val ACTIVITY_SELECT_ALL_STATUS = "activitySelectAll"

/**
 * Select all in Your activity (#762). See ActivitySelectAll in the extension.
 *
 * No Instagram code changes: the extension, started by the application-start hook the settings patch
 * already writes, adds Select all to the top bar while a Bloks grid of checkable tiles shows, and ticks
 * them the way a tap does. In the default selection with its switch off. Grouped under Settings, since
 * Interface is full and Your activity is a page of Instagram's own Settings and activity.
 */
@Suppress("unused")
val activitySelectAllPatch = bytecodePatch(
    name = "Select all in Your activity",
    description = "Adds Select all beside Select on the photo and video grids in Your activity, so you can tick " +
        "every post, reel or like at once. Starts off. Turn it on in HushGram settings > Profiles.",
) {
    category("Settings")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(ACTIVITY_SELECT_ALL_STATUS)
        enableStatus(ACTIVITY_SELECT_ALL_STATUS)
    }
}
