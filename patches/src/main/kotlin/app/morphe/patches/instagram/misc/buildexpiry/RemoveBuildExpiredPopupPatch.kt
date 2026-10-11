/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.buildexpiry

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val SUPPRESS = "$EXTENSION_PACKAGE/misc/BuildExpiry;->suppress()Z"
internal const val PATCH_NAME = "Remove build expired popup"
internal const val LOCKOUT = "build expired lockout"

/**
 * Asks the extension ahead of the lockout's first instruction and leaves when it says the popup is
 * suppressed. The label keeps the original first instruction as the way on, so a jump to it still lands.
 */
internal fun MutableMethod.suppressLockout() {
    requireLocals(PATCH_NAME, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SUPPRESS
            move-result v0
            if-eqz v0, :lockout
            return-void
        """,
        ExternalLabel("lockout", getInstruction(0)),
    )
}

@Suppress("unused")
val removeBuildExpiredPopupPatch = bytecodePatch(
    name = "Remove build expired popup",
    description = "Stops Instagram from locking you out with a screen that says this version is too old. A " +
        "patched build can't update itself, so it would stop working after a few weeks. On by default. Turn it " +
        "off in HushGram settings > Updates.",
    default = true,
) {
    category("Updates")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        uniqueMethod(PATCH_NAME, LOCKOUT, BuildExpiredLockoutFingerprint).suppressLockout()

        enableStatus("buildExpiredPopup")
    }
}
