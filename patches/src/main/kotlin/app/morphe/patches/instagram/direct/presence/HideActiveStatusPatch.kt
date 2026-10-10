/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.direct.ghost.ghostModeEntryPatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val ACTIVE_STATUS_PATCH = "Hide your active status"

/**
 * One-sided: Instagram stops telling its presence service you're active, and the presence of the
 * people you chat with still comes back. In the default selection with its switch off.
 */
@Suppress("unused")
val hideActiveStatusPatch = bytecodePatch(
    name = "Hide your active status",
    description = "Stops people from seeing Active now for you while you use Instagram. You still see when they're " +
        "active. Ghost mode, at the top of Ads and privacy, turns it on with the others. Starts off. Turn it on in " +
        "HushGram settings > Messages.",
) {
    category("Ghost mode")
    dependsOn(settingsPatch, instagramExtensionPatch, ghostModeEntryPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("activeStatus")
        sendIdleForActive()
        enableStatus("activeStatus")
    }
}

/**
 * Hands the extension the status first thing in the presence write request's constructor, and
 * puts what it answers back in the status parameter, cast to the status enum, before the
 * constructor stores it. The extension answers the status it was given or another constant of the
 * same enum, so the cast holds and the register keeps its type for the store.
 */
internal fun BytecodePatchContext.sendIdleForActive() {
    val constructor = findPresenceWrite()
    val status = constructor.parameterRegister(STATUS_PARAMETER)
    constructor.addInstructions(
        0,
        """
            invoke-static/range { $status .. $status }, $SEND_STATUS
            move-result-object $status
            check-cast $status, $PRESENCE_STATUS
        """,
    )
}
