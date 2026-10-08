/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.heartbeat

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

private const val STOP = "$EXTENSION_PACKAGE/misc/Heartbeat;->stop()Z"

@Suppress("unused")
val stopHeartbeatPatch = bytecodePatch(
    name = "Stop the background heartbeat",
    description = "Instagram wakes your phone with an alarm every minute or two, screen off included, only to " +
        "note that it is still running. This stops that. Messages, notifications and everything you see " +
        "are unaffected.",
    default = false,
) {
    category("Battery")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        uniqueMethod("Stop the background heartbeat", "heartbeat alarm", HeartbeatAlarmFingerprint).apply {
            requireLocals("Stop the background heartbeat", 1)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $STOP
                    move-result v0
                    if-eqz v0, :heartbeat
                    return-void
                """,
                ExternalLabel("heartbeat", getInstruction(0)),
            )
        }

        enableStatus("stopHeartbeat")
    }
}
