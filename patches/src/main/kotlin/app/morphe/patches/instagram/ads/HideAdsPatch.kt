/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val HIDE_ADS = "$EXTENSION_PACKAGE/ads/Ads;->hide()Z"

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
        guardAdInjector(findAdInjector())
        enableStatus("hideAds")
    }
}

/**
 * Makes the ad-insert method answer that nothing went in, from its first instruction, while Hide
 * ads is on. At the top no local is live yet, so v0 is free once the method has a local. The switch
 * is read on every ad, so turning it off or pausing takes Instagram's own path.
 */
internal fun BytecodePatchContext.guardAdInjector(injector: AdInjector) {
    val method = mutableClassDefBy(injector.type).methods.single {
        it.name == injector.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == injector.parameters
    }
    method.requireLocals("Hide ads", 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_ADS
            move-result v0
            if-eqz v0, :show
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("show", method.getInstruction(0)),
    )
}
