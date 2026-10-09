/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.buildexpiry

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

private const val SUPPRESS = "$EXTENSION_PACKAGE/misc/BuildExpiry;->suppress()Z"

@Suppress("unused")
val removeBuildExpiredPopupPatch = bytecodePatch(
    name = "Remove build expired popup",
    description = "Stops Instagram from locking you out with a screen that says this version is too old. A patched " +
        "build doesn't update on its own, so without this it would stop working after a few weeks. On to start. " +
        "Switch: HushGram settings > Updates.",
    default = true,
) {
    category("Updates")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        uniqueMethod("Remove build expired popup", "build expired lockout", BuildExpiredLockoutFingerprint).apply {
            requireLocals("Remove build expired popup", 1)
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

        enableStatus("buildExpiredPopup")
    }
}
