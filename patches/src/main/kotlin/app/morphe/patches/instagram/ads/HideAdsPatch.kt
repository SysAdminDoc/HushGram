/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val HIDE = "$EXTENSION_PACKAGE/ads/Ads;->hide()Z"

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides sponsored posts, reels and stories, and leaves no gap where an ad would have been. On by " +
        "default. Turn it off in HushGram settings > Ads and privacy.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        // At the top, where no local is live yet, so v0 is free once the method has a local. The
        // switch is read on every ad, so turning it off or pausing takes Instagram's own path.
        uniqueMethod("Hide ads", "method that puts an ad into a feed", AdInjectorFingerprint).apply {
            requireLocals("Hide ads", 1)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $HIDE
                    move-result v0
                    if-eqz v0, :show
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("show", getInstruction(0)),
            )
        }

        enableStatus("hideAds")
    }
}
