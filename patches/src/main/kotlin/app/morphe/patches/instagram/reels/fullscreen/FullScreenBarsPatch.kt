/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fullscreen

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Hides the status bar and the navigation bar while Reels or Home shows. See FullScreenBars in the
 * extension.
 *
 * It changes no Instagram code. The extension finds the reels viewer and the tab bar's tabs by the
 * names Instagram looks them up by (clips_viewer_container, tab_bar, clips_tab, feed_tab, the same
 * ids on all seven 450 builds) once an activity resumes, and the application-start hook the settings
 * patch already writes starts the watching. This patch only switches the status method on, so the
 * settings screen offers the two switches and the extension acts. In the default selection with both
 * switches off.
 */
@Suppress("unused")
val fullScreenBarsPatch = bytecodePatch(
    name = "Full screen Reels and Home",
    description = "Hides the status bar and the navigation bar while Reels shows, and optionally while Home does. " +
        "Swipe in from the edge to bring them back for a moment. Back and the keyboard work as usual. " +
        "Starts off. Turn it on in HushGram settings > Reels (Full screen Reels) and Feed (Full screen Home).",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("fullScreenBars")
        enableStatus("fullScreenBars")
    }
}
