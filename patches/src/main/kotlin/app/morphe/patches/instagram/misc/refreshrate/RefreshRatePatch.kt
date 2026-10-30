/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.refreshrate

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Asks Android to run Instagram's windows at the display's fastest mode (#401, #396). See RefreshRate
 * in the extension.
 *
 * Writes no hook of its own: the application-start hook the settings patch already writes starts a
 * watcher that sets the window's preferred display mode as each activity resumes. This patch switches
 * the status method on, so the settings screen offers the switch and the extension acts.
 */
@Suppress("unused")
val refreshRatePatch = bytecodePatch(
    name = "Use the highest refresh rate",
    description = "Asks Android to run Instagram at your screen's fastest refresh rate, at the same resolution, " +
        "so scrolling looks smoother on a 90 or 120 Hz phone. Some phones keep their own limit for apps. " +
        "Starts off. Turn it on in HushGram settings > Layout.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("refreshRate")
        enableStatus("refreshRate")
    }
}
