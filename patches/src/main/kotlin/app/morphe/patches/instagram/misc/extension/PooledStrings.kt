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
 * strings for it by a constant number loaded right before the call.
 */
internal fun BytecodePatchContext.loadsString(method: Method, value: String): Boolean {
    if (value in method.strings()) return true
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.indices.any { at ->
        val call = code[at]
        val pool = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        val number = code.getOrNull(at - 1) as? NarrowLiteralInstruction ?: return@any false
        call.opcode == Opcode.INVOKE_STATIC && pool.parameterTypes.map(Any::toString) == listOf("I") &&
            pool.returnType == "Ljava/lang/String;" &&
            (number as OneRegisterInstruction).registerA == (call as Instruction35c).registerC &&
            pooledString(pool, number.narrowLiteral) == value
    }
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
