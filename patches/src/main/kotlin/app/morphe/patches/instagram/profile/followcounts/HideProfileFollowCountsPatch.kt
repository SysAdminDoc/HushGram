/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.followcounts

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val PROFILE_FOLLOW_COUNTS_STATUS = "profileFollowCounts"

/**
 * Takes Followers and Following, or just their numbers, off the top of profiles (#771). See
 * ProfileFollowCounts in the extension.
 *
 * No Instagram code changes: the extension's view hider, started by the application-start hook the
 * settings patch already writes, keeps the profile header's follow stacks or their numbers gone by
 * the ids Instagram's layout gives them. In the default selection with both switches off.
 */
@Suppress("unused")
val hideProfileFollowCountsPatch = bytecodePatch(
    name = "Hide Followers and Following on profiles",
    description = "Takes the Followers and Following buttons, or just their numbers, off the top of profiles. " +
        "Starts off. Turn it on in HushGram settings > Profiles.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(PROFILE_FOLLOW_COUNTS_STATUS)
        enableStatus(PROFILE_FOLLOW_COUNTS_STATUS)
    }
}
