/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.notifications

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.sendToStandIn
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Group Instagram's notifications"
internal const val NOTIFICATION_GROUPS = "$EXTENSION_PACKAGE/misc/NotificationGroups;"
internal const val NOTIFICATION_MANAGER = "Landroid/app/NotificationManager;"

/**
 * The two ways to post a notification, by their parameters after the manager, and the
 * NotificationGroups method standing in for each: static, the manager first, the same parameters.
 */
internal val NOTIFY_SHAPES = listOf("(ILandroid/app/Notification;)V", "(Ljava/lang/String;ILandroid/app/Notification;)V")

/** The NotificationGroups method that stands in for a notify call of [shape]. */
internal fun notifyStandIn(shape: String): String = "$NOTIFICATION_GROUPS->notify($NOTIFICATION_MANAGER${shape.substringAfter('(')}"

/**
 * The two ways to take a notification down, by their parameters after the manager. Each goes to
 * NotificationGroups.cancel the same way, so a group's summary is counted again when one of its
 * notifications goes, and comes down with the last but one.
 */
internal val CANCEL_SHAPES = listOf("(I)V", "(Ljava/lang/String;I)V")

/** The NotificationGroups method that stands in for a cancel call of [shape]. */
internal fun cancelStandIn(shape: String): String = "$NOTIFICATION_GROUPS->cancel($NOTIFICATION_MANAGER${shape.substringAfter('(')}"

/** How many notify and cancel calls went to their stand-ins. */
internal data class Sent(val posts: Int, val cancels: Int)

@Suppress("unused")
val groupNotificationsPatch = bytecodePatch(
    name = "Group Instagram's notifications",
    description = "Puts Instagram's notifications in one group, or in a group per type with a second switch, " +
        "so they don't fill the notification shade. A group of two or more shows how many it holds. Tapping a " +
        "notification still opens what it did.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("notificationGroups")
        groupNotifications()
        enableStatus("notificationGroups")
    }
}

/** The notify call this instruction makes, by its shape after the manager, or null. */
internal fun Instruction.notifyCall(): String? = managerCall("notify", NOTIFY_SHAPES)

/** The cancel call this instruction makes, by its shape after the manager, or null. */
internal fun Instruction.cancelCall(): String? = managerCall("cancel", CANCEL_SHAPES)

private fun Instruction.managerCall(name: String, shapes: List<String>): String? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (call.definingClass != NOTIFICATION_MANAGER || call.name != name) return null
    val shape = call.parameterTypes.joinToString("", "(", ")") + call.returnType
    return shape.takeIf { it in shapes }
}

/**
 * Sends every notify and cancel call in Instagram's code to NotificationGroups, on the same
 * registers in the same order, the manager first, so nothing after a call moves. Answers how many
 * of each it sent. No notify call fails the patch, since then the switch would do nothing. No
 * cancel call only leaves a warning: notifications still group, and a summary is counted again at
 * the next post. The extension's own calls are the real ones the stand-ins make, so they stay, or
 * each stand-in would call itself.
 */
internal fun BytecodePatchContext.groupNotifications(): Sent {
    val posts = send("notify") { it.notifyCall()?.let(::notifyStandIn) }
    if (posts == 0) throw PatchException("$PATCH: Instagram posts no notification through NotificationManager.notify")
    val cancels = send("cancel") { it.cancelCall()?.let(::cancelStandIn) }
    if (cancels == 0) {
        patchLog.warning("$PATCH: Instagram takes no notification down through NotificationManager.cancel, so a summary is only counted again when something is posted")
    }
    return Sent(posts, cancels)
}

/** Sends each call [standIn] names a stand-in for, in the classes calling the manager's [name]. */
private fun BytecodePatchContext.send(name: String, standIn: (Instruction) -> String?): Int =
    classesCalling(NOTIFICATION_MANAGER, name).sumOf { found ->
        mutableClassDefBy(found.type).methods.sumOf { method ->
            val sites = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
                standIn(instruction)?.let { index to it }
            }.orEmpty()
            sites.asReversed().forEach { (index, target) -> method.sendToStandIn(index, target) }
            sites.size
        }
    }
