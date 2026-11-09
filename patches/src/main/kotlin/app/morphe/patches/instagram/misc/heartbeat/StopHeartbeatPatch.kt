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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
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
private const val STOP_BACKGROUND_FETCH = "$EXTENSION_PACKAGE/misc/Heartbeat;->stopBackgroundFetch(Ljava/lang/Class;)Z"
private const val SKIP_BACKGROUND_FETCH = "$EXTENSION_PACKAGE/misc/Heartbeat;->skipBackgroundFetch()Z"

/**
 * Skips Instagram's heartbeat alarm, its analytics upload alarm and its background prefetch job, each
 * behind its own switch. See Heartbeat in the extension. In the default selection with every switch off.
 */
@Suppress("unused")
val stopHeartbeatPatch = bytecodePatch(
    name = "Stop background wake-ups",
    description = "Stops three things that wake your phone while Instagram is in the background. One alarm goes off " +
        "every minute or two only to note that Instagram is still running, another wakes it five minutes later to " +
        "upload usage events, and a job about every half hour downloads your feed ahead of time. Messages and " +
        "notifications still arrive. Starts off. Turn it on in HushGram settings > Battery.",
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

        stopBackgroundFetch(
            findBgFetchScheduler(),
            uniqueMethod("Stop background wake-ups", "background prefetch job", BgFetchJobStartFingerprint),
        )

        enableStatus("stopHeartbeat")
    }
}

/**
 * The prefetch scheduler (static, the job service class its second input) returns before queueing anything
 * when the extension says so, and the extension cancels what it queued earlier for that class. A prefetch job
 * queued before the switch went on ends as soon as it starts, done, with nothing fetched.
 */
internal fun stopBackgroundFetch(scheduler: MutableMethod, start: MutableMethod) {
    scheduler.apply {
        requireLocals("Stop background wake-ups", 1)
        val service = implementation!!.registerCount - 2
        addInstructionsWithLabels(
            0,
            """
                invoke-static/range { v$service .. v$service }, $STOP_BACKGROUND_FETCH
                move-result v0
                if-eqz v0, :schedule
                return-void
            """,
            ExternalLabel("schedule", getInstruction(0)),
        )
    }
    start.apply {
        requireLocals("Stop background wake-ups", 1)
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $SKIP_BACKGROUND_FETCH
                move-result v0
                if-eqz v0, :fetch
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("fetch", getInstruction(0)),
        )
    }
}
