/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.heartbeat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val STOP = "$EXTENSION_PACKAGE/misc/Heartbeat;->stop()Z"
private const val SET_UPLOAD_ALARM =
    "$EXTENSION_PACKAGE/misc/Heartbeat;->setUploadAlarm(Landroid/app/AlarmManager;IJLandroid/app/PendingIntent;)V"
private const val ALARM_SET = "Landroid/app/AlarmManager;->set(IJLandroid/app/PendingIntent;)V"

/**
 * Skips Instagram's heartbeat alarm and its analytics upload alarm, each behind its own switch. See
 * Heartbeat in the extension. In the default selection with both switches off.
 */
@Suppress("unused")
val stopHeartbeatPatch = bytecodePatch(
    name = "Stop background wake-ups",
    description = "Stops two alarms that wake your phone while Instagram is in the background. One goes off every " +
        "minute or two only to note that Instagram is still running, and the other wakes it five minutes later to " +
        "upload usage events. Messages and notifications still arrive. Starts off. Turn it on in HushGram " +
        "settings > Battery.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("stopHeartbeat")
        uniqueMethod("Stop background wake-ups", "heartbeat alarm", HeartbeatAlarmFingerprint).apply {
            requireLocals("Stop background wake-ups", 1)
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

        // The upload alarm's AlarmManager.set call goes to the extension instead, on the same registers.
        uniqueMethod("Stop background wake-ups", "analytics upload alarm", UploadAlarmFingerprint).apply {
            val calls = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                (instruction as? ReferenceInstruction)?.reference?.toString() == ALARM_SET
            }
            val (index, call) = calls.singleOrNull()
                ?: throw PatchException("Stop background wake-ups: expected one AlarmManager.set in the upload scheduler, found ${calls.size}")
            val set = call as? FiveRegisterInstruction
                ?: throw PatchException("Stop background wake-ups: the upload alarm's AlarmManager.set is in a form it can't move")
            val registers = listOf(set.registerC, set.registerD, set.registerE, set.registerF, set.registerG)
                .take(set.registerCount)
            replaceInstruction(index, registers.joinToString(prefix = "invoke-static { ", postfix = " }, $SET_UPLOAD_ALARM") { "v$it" })
        }

        enableStatus("stopHeartbeat")
    }
}
