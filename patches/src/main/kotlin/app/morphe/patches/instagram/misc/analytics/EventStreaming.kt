/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Two names only the logger's event settings constructor holds, which pick its class out. */
internal val EVENT_SETTINGS_STRINGS = listOf("non-streamable events", "stateful events")

/** What the logger's step for each event holds where it sends an event to Falco's stream or the batch. */
internal val STREAM_STEP_STRINGS = listOf("event.streaming.eligible", "stream_is_active")

/**
 * Keeps Instagram's usage events off Falco's event stream, sending [hook] the logger's switch for
 * it right after the step for each event reads it. The step reads the switch first of everything
 * on the event settings, and branches on it straight away: off, the event goes to the batch upload,
 * whose addresses the rest of this patch refuses, and the step never starts the stream, a
 * sessionless request stream on the gateway host the server picks
 * (test-gateway.instagram.com on 449). That host also carries the app's realtime GraphQL
 * subscriptions, so it can't be refused itself.
 *
 * 385611400 splits the step in two (#77): the part holding [STREAM_STEP_STRINGS] starts the stream,
 * and the switch and the branch to the batch move to the one method calling it, so where the method
 * holding the strings reads no switch, its one caller has to, and the branch on its first switch
 * has to be what decides the call to that part: every call reached from the branch's fall through,
 * and none from where it jumps without coming back through the branch.
 *
 * Answers null when the call went in, or why not.
 */
internal fun BytecodePatchContext.keepEventsOffTheStream(hook: String): String? {
    val settings = mutableListOf<String>()
    val steps = mutableListOf<Method>()
    classesHolding(*EVENT_SETTINGS_STRINGS.toTypedArray()).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.name == "<init>" && method.strings().containsAll(EVENT_SETTINGS_STRINGS)) settings += classDef.type
        }
    }
    classesHolding(*STREAM_STEP_STRINGS.toTypedArray()).forEach { classDef ->
        classDef.methods.filterTo(steps) { it.strings().containsAll(STREAM_STEP_STRINGS) }
    }
    val config = settings.distinct().singleOrNull()
        ?: return "expected one class whose constructor holds $EVENT_SETTINGS_STRINGS, found ${settings.distinct().size}"
    val found = steps.singleOrNull() ?: return "expected one method holding $STREAM_STEP_STRINGS, found ${steps.size}"
    val step = if (found.readsSwitchOf(config)) {
        found
    } else {
        val callers = callersOf(found)
        val caller = callers.singleOrNull()
            ?: return "${found.definingClass}->${found.name} reads no switch of $config, and ${callers.size} methods call it, expected one"
        if (!caller.readsSwitchOf(config)) {
            return "neither ${found.definingClass}->${found.name} nor ${caller.definingClass}->${caller.name}, " +
                "the one method calling it, reads a switch of $config"
        }
        unguardedCall(caller, found, config)?.let { return it }
        caller
    }
    val mutable = mutableClassDefBy(step.definingClass).methods.single {
        it.name == step.name && it.returnType == step.returnType &&
            it.parameterTypes.map(Any::toString) == step.parameterTypes.map(Any::toString)
    }
    val code = mutable.implementation!!.instructions.toList()
    val read = code.indices.first { index -> code[index].isSwitchOf(config) }
    val register = (code[read] as TwoRegisterInstruction).registerA
    val branch = code.getOrNull(read + 1)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != register) {
        return "${step.definingClass}->${step.name} doesn't branch on $config's switch right after reading it"
    }
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val landsOnBranch = code.indices.any { index ->
        val jump = code[index] as? OffsetInstruction ?: return@any false
        (jump.opcode.name.startsWith("if-") || jump.opcode.name.startsWith("goto")) &&
            address.indexOf(address[index] + jump.codeOffset) == read + 1
    }
    if (landsOnBranch) return "${step.definingClass}->${step.name} jumps to its branch on $config's switch without reading it"
    mutable.addInstructionsAtControlFlowLabel(
        read + 1,
        """
            invoke-static/range { v$register .. v$register }, $hook
            move-result v$register
        """,
    )
    return null
}

/** A read of one of [config]'s boolean switches. */
private fun Instruction.isSwitchOf(config: String) =
    opcode == Opcode.IGET_BOOLEAN && ((this as ReferenceInstruction).reference as FieldReference).definingClass == config

private fun Method.readsSwitchOf(config: String) = implementation?.instructions?.any { it.isSwitchOf(config) } == true

/**
 * Why the branch right after [caller]'s first read of a switch of [config], the one the hook
 * answers, doesn't decide whether [caller] calls [part], or null when it does: every call to [part]
 * is reached from the branch's fall through, and none from where the branch jumps, without coming
 * back through the branch. Null too when no if-eqz follows the read, which the step's own check
 * refuses.
 */
private fun unguardedCall(caller: Method, part: Method, config: String): String? {
    val code = caller.implementation!!.instructions.toList()
    val where = "${caller.definingClass}->${caller.name}"
    val branch = code.indices.first { code[it].isSwitchOf(config) } + 1
    if (code.getOrNull(branch)?.opcode != Opcode.IF_EQZ) return null
    val flow = ControlFlow.of(caller)
    val jumped = flow.normal[branch].firstOrNull { it != branch + 1 }
        ?: return "$where's branch on $config's switch lands right after itself"
    fun reached(from: Int): Set<Int> {
        val seen = HashSet<Int>()
        val pending = ArrayDeque(listOf(from))
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at == branch || !seen.add(at)) continue
            pending.addAll(flow.normal[at])
            pending.addAll(flow.exceptional[at])
        }
        return seen
    }
    val on = reached(branch + 1)
    val off = reached(jumped)
    val calls = code.indices.filter { code[it].calls(part) }
    if (calls.isEmpty() || calls.any { it !in on || it in off }) {
        return "$where calls ${part.definingClass}->${part.name} other than only when its first switch of $config is on"
    }
    return null
}

/** The methods outside the extension calling [method]. */
private fun BytecodePatchContext.callersOf(method: Method): List<Method> =
    classesCalling(method.definingClass, method.name).flatMap { it.methods }.filter { caller ->
        caller.implementation?.instructions?.any { it.calls(method) } == true
    }

private fun Instruction.calls(method: Method): Boolean {
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return called.definingClass == method.definingClass && called.name == method.name &&
        called.returnType == method.returnType && called.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
}

private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}?.toSet().orEmpty()
