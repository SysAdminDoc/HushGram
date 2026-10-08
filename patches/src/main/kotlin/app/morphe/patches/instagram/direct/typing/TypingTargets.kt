/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.typing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireFreeAt
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val TYPING_STATUS = "$EXTENSION_PACKAGE/direct/TypingStatus;"
internal const val HOLD_TYPING = "$TYPING_STATUS->hold(I)Z"

/** The trace name and the flag Instagram's typing status service logs each time it's asked. */
internal const val TYPING_SERVICE = "IGDirectTypingStatusService"
internal const val TYPING_ENABLED = "is_typing_indicator_enabled"

/** The realtime command that shows the other person you're typing. */
internal const val TYPING_COMMAND = "indicate_activity"
internal const val REALTIME_CLIENT = "Lcom/instagram/realtimeclient/RealtimeClientManager;"

/** The service's one method that takes the typing flag, holding both names it logs. */
internal object TypingStatusFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf(TYPING_SERVICE, TYPING_ENABLED),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

internal data class TypingTargets(val service: MutableMethod, val sender: Method)

private fun refuse(why: String): Nothing = throw PatchException("$TYPING_PATCH: $why")
private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Resolve the typing status service and prove what holding a non-zero flag skips, before any
 * edit: the flag is tested once, untouched since entry; the one call of the typing indicator's
 * sender sits on the flag's non-zero side and can't be reached from its zero side; and nothing
 * else in Instagram calls that sender.
 */
internal fun BytecodePatchContext.findTyping(): TypingTargets {
    val service = uniqueMethod(TYPING_PATCH, "typing status service", TypingStatusFingerprint)
    if (!AccessFlags.PUBLIC.isSet(service.accessFlags) || service.localRegisterCount() < 1) {
        refuse("typing status service needs public access and a local")
    }
    service.requireFreeAt(TYPING_PATCH, 0, listOf(0))
    if (0 in service.jumpTargets()) refuse("a jump or exception handler enters the typing status service at its first instruction")
    val code = service.code()
    val flag = service.parameterRegisterNumber(0)
    val branchAt = code.indices.filter {
        code[it].opcode in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ) && (code[it] as OneRegisterInstruction).registerA == flag
    }.one("typing flag check")
    service.requireParameterIntact(TYPING_PATCH, 0, listOf(branchAt))

    val sender = classesHolding(TYPING_COMMAND).flatMap { it.methods }.filter { method ->
        val body = method.code()
        body.any { it.string() == TYPING_COMMAND } &&
            body.any { it.call()?.let { call -> call.definingClass == REALTIME_CLIENT && call.name == "sendCommand" } == true }
    }.one("typing indicator sender")
    val senderKey = sender.key()
    val calls = classesCalling(sender.definingClass, sender.name).flatMap { it.methods }.flatMap { method ->
        val body = method.code()
        body.indices.filter { body[it].call()?.key() == senderKey }.map { method.key() to it }
    }
    val (caller, callAt) = calls.one("call of the typing indicator sender")
    if (caller != service.key()) refuse("the typing indicator is sent from outside the typing status service")

    val flow = ControlFlow.of(service)
    val jump = flow.normal[branchAt].filter { it != branchAt + 1 }.one("typing flag check's jump")
    val zero = if (code[branchAt].opcode == Opcode.IF_EQZ) jump else branchAt + 1
    val typing = if (code[branchAt].opcode == Opcode.IF_EQZ) branchAt + 1 else jump
    if (callAt in reachable(flow, zero)) refuse("the typing indicator can be sent when you stop typing")
    if (callAt !in reachable(flow, typing)) refuse("typing never reaches the typing indicator's sender")

    classDefByOrNull(TYPING_STATUS)?.methods?.filter {
        it.name == "hold" && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf("I") &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    }?.singleOrNull() ?: refuse("extension has no public static hold(I)Z")
    return TypingTargets(service, sender)
}

/** Every instruction a path from [start] can run, exception handlers included. */
private fun reachable(flow: ControlFlow, start: Int): Set<Int> {
    val seen = mutableSetOf<Int>()
    val pending = ArrayDeque(listOf(start))
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (!seen.add(at)) continue
        flow.normal[at].forEach { pending += it }
        flow.exceptional[at].forEach { pending += it }
    }
    return seen
}
