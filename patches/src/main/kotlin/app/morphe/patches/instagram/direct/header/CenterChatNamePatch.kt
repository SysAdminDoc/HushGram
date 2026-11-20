/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.header

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * A chat's name and username in the middle of its top bar (#779). See CenterChatName in the extension.
 *
 * The extension finds the chat's header by the ids Instagram's own header code looks it up by, once an
 * activity resumes, and the application-start hook the settings patch already writes is what starts the
 * watching. This patch switches the status method on, so the settings screen offers the switch and the
 * extension acts. Included in the default selection with its switch off.
 */
@Suppress("unused")
val centerChatNamePatch = bytecodePatch(
    name = "Center names in chats",
    description = "Puts the name and username at the top of a chat in the middle of the bar, or as near the middle " +
        "as the call buttons leave room for. Starts off. Turn it on in HushGram settings > Messages.",
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("centerChatName")
        enableStatus("centerChatName")
    }
}
