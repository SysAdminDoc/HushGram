/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The string the static pool method [pool] answers for [number], read off its switch, or null. */
internal fun BytecodePatchContext.pooledString(pool: MethodReference, number: Int): String? {
    val method = classDefByOrNull(pool.definingClass)?.methods?.singleOrNull {
        it.name == pool.name && it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == pool.returnType
    } ?: return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    code.indices.filter { code[it].opcode == Opcode.PACKED_SWITCH || code[it].opcode == Opcode.SPARSE_SWITCH }.forEach { switch ->
        val payload = code.getOrNull(address.indexOf(address[switch] + (code[switch] as OffsetInstruction).codeOffset)) as? SwitchPayload
            ?: return@forEach
        val case = payload.switchElements.firstOrNull { it.key == number } ?: return@forEach
        val load = code.getOrNull(address.indexOf(address[switch] + case.offset)) ?: return@forEach
        if (load.opcode == Opcode.CONST_STRING || load.opcode == Opcode.CONST_STRING_JUMBO) {
            return ((load as ReferenceInstruction).reference as StringReference).string
        }
    }
    return null
}

/**
 * Whether [method] loads [value] itself or, as 450's Redex has it, asks a static pool of shared
 * strings for it by a constant number ([poolAsks]).
 */
internal fun BytecodePatchContext.loadsString(method: Method, value: String): Boolean {
    if (value in method.strings()) return true
    val code = method.implementation?.instructions?.toList() ?: return false
    return poolAsks(code).any { (at, number) -> pooledString((code[at] as ReferenceInstruction).reference as MethodReference, number) == value }
}

/**
 * Each static `(I)String` call in [code] handed a constant number, as the call's index and the
 * number: one loaded into the call's register right before it, or, from 457, one loaded right before
 * a goto landing on a call the cases of a merged switch share (`LX/078q;->A00` on 386314341 loads a
 * midcard type's number in each case and jumps to one pool call).
 */
internal fun poolAsks(code: List<Instruction>): List<Pair<Int, Int>> {
    val calls = code.indices.filter { at ->
        val pool = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_STATIC && pool != null && pool.returnType == "Ljava/lang/String;" &&
            pool.parameterTypes.map(Any::toString) == listOf("I")
    }
    if (calls.isEmpty()) return emptyList()
    fun number(load: Int, call: Int): Int? {
        val literal = code.getOrNull(load) as? NarrowLiteralInstruction ?: return null
        return literal.narrowLiteral.takeIf { (literal as OneRegisterInstruction).registerA == (code[call] as Instruction35c).registerC }
    }
    val asks = calls.mapNotNullTo(mutableListOf()) { at -> number(at - 1, at)?.let { at to it } }
    val gotos = code.indices.filter { code[it].opcode == Opcode.GOTO || code[it].opcode == Opcode.GOTO_16 || code[it].opcode == Opcode.GOTO_32 }
    if (gotos.isEmpty()) return asks
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val callAt = calls.associateBy { address[it] }
    for (jump in gotos) {
        val call = callAt[address[jump] + (code[jump] as OffsetInstruction).codeOffset] ?: continue
        number(jump - 1, call)?.let { asks += call to it }
    }
    return asks
}

/**
 * The string [code] loads at [at]: a const-string's, or, as 450's Redex has it, a static pool's
 * asked for it by a constant number loaded right before the call. Null for anything else. Which
 * strings Redex pools differs from build to build of one version (#77).
 */
internal fun BytecodePatchContext.stringLoadedAt(code: List<Instruction>, at: Int): String? {
    val load = code[at]
    if (load.opcode == Opcode.CONST_STRING || load.opcode == Opcode.CONST_STRING_JUMBO) {
        return ((load as ReferenceInstruction).reference as StringReference).string
    }
    val pool = (load as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val number = code.getOrNull(at - 1) as? NarrowLiteralInstruction ?: return null
    if (load.opcode != Opcode.INVOKE_STATIC || pool.parameterTypes.map(Any::toString) != listOf("I") ||
        pool.returnType != "Ljava/lang/String;" || (number as OneRegisterInstruction).registerA != (load as Instruction35c).registerC
    ) {
        return null
    }
    return pooledString(pool, number.narrowLiteral)
}

private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}?.toSet().orEmpty()
