/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.glass

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.media.surface.videoSurfacePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Frosted glass in a chat's header (#449) and message bar, and on the reply box of other people's
 * stories (#391). See GlassChat in the extension.
 *
 * The extension finds the header by the ids Instagram's own header code looks it up by, once an
 * activity resumes, and the application-start hook the settings patch already writes is what starts the
 * watching. This patch switches the status method on, so the settings screen offers the switches and the
 * extension acts. Video stays on a TextureView while either switch is on, through [videoSurfacePatch], so
 * a video under the glass comes through the blur. Included in the default selection with its switches off.
 */
@Suppress("unused")
val glassChatPatch = bytecodePatch(
    name = "Glass in chats",
    description = "Gives a chat's top bar and message bar frosted glass that blurs the messages under them " +
        "(Android 12 and newer) and puts the back, call and video buttons on round glass circles. A second " +
        "switch gives the reply box on other people's stories the same glass. Starts off. Turn them on in " +
        "HushGram settings > Messages and Stories.",
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch, videoSurfacePatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("glassChat")
        enableStatus("glassChat")
    }
}
