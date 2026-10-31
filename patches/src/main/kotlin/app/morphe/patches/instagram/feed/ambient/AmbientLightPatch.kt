/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.ambient

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Puts a soft glow in a post's own colors around its photo or video, and in the bands around a reel that
 * doesn't fill the screen (#416). See AmbientLight in the extension.
 *
 * It changes no Instagram code. The extension finds each feed post's media frame by its class name
 * (`MediaFrameLayout`, kept by the minifier) and each reel's video by the viewer's frame id
 * (`clips_viewer_video_layout`, the one Rounded corners on Reels uses), samples a tiny copy of the picture
 * a few times a second and draws the glow behind it as the background of the row or the viewer. The
 * application-start hook the settings patch already writes is what starts the watching; this patch only
 * switches the status method on, so the settings screen offers the switch and the extension acts. In the
 * default selection with its switch off.
 */
@Suppress("unused")
val ambientLightPatch = bytecodePatch(
    name = "Ambient light",
    description = "Puts a soft glow in a post's own colors around its photo or video in Home and Following, and in " +
        "the bands around a reel that doesn't fill the screen. The glow follows a video as it plays, and you pick " +
        "how far it spreads and how fast it changes. A second switch fills the bands above and below a story that " +
        "doesn't fill the screen. Both start off. Turn them on in HushGram settings > Feed and Stories.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("ambientLight")
        enableStatus("ambientLight")
    }
}
