/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.progressbar

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Draws the progress bar along the top of a story thicker (#360). See StoryProgressBar in the extension.
 *
 * It changes no Instagram code. The story viewer's bar is a view of its own (`reel_viewer_progress_bar`),
 * a few pixels tall in a header with room around it, and the extension stretches that view about its
 * middle by its id after the viewer lays out. The application-start hook the settings patch already
 * writes is what starts the watching; this patch only switches the status method on, so the settings
 * screen offers the switch and the extension acts. In the default selection with its switch off.
 */
@Suppress("unused")
val storyProgressBarPatch = bytecodePatch(
    name = "Thicker story progress bar",
    description = "Draws the progress bar along the top of a story twice or three times as thick. Its segments " +
        "and timing stay as they were. Starts off. Turn it on in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyProgressBar")
        enableStatus("storyProgressBar")
    }
}
