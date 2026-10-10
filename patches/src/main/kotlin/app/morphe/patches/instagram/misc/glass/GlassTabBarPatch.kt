/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.glass

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Draws Instagram's tab bar as a floating, rounded glass pill. See GlassTabBar in the extension.
 *
 * It changes no Instagram code. The extension finds the bar by the id Instagram's own code looks it
 * up by, once an activity resumes, and the application-start hook the settings patch already writes
 * is what starts the watching; this patch only switches the status method on, so the settings screen
 * offers the switches and the extension acts. In the default selection with its switch off.
 */
@Suppress("unused")
val glassTabBarPatch = bytecodePatch(
    name = "Glass tab bar",
    description = "Draws the tab bar as a floating, rounded pill of frosted glass that blurs what's behind it " +
        "(Android 12 and newer), with a highlight on the tab you're on. Content can optionally run down behind it. " +
        "Starts off. Turn it on in HushGram settings > Tab bar, then restart Instagram.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("glassTabBar")
        enableStatus("glassTabBar")
    }
}
