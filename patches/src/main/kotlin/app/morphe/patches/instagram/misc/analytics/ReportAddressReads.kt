/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesTouching
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** How far past a toString() the field it's stored in may be written. */
private const val STORE_WINDOW = 8

/**
 * Lacrima, Instagram's error reporter, builds its report address with [builder] while the app
 * starts, before HushGram's settings are ready, and keeps it as text in a field: it calls the
 * builder, turns the Uri into a String and stores that. Each send reads the field again, by which
 * time the settings are ready. This passes every read of each such field, outside the extension,
 * through [filter], a static `(String)String` call, right after the read, so the switch decides at
 * send time. Answers how many reads it found.
 */
internal fun BytecodePatchContext.filterReportAddressReads(builder: Method, filter: String): Int {
    val fields = mutableSetOf<String>()
    val building = classesCalling(builder.definingClass, builder.name).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in building || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.indices.filter { code[it].calls(builder) }.forEach { call -> code.storedField(call)?.let(fields::add) }
        }
    }
    if (fields.isEmpty()) return 0

    val readers = mutableListOf<String>()
    val reading = fields.flatMapTo(HashSet()) { field ->
        classesTouching(field.substringBefore("->"), field.substringAfter("->").substringBefore(":")).map { it.type }
    }
    classDefForEach { classDef ->
        if (classDef.type !in reading || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        if (classDef.methods.any { method -> method.reads(fields).isNotEmpty() }) readers += classDef.type
    }
    var count = 0
    readers.forEach { type ->
        mutableClassDefBy(type).methods.forEach { method ->
            method.reads(fields).asReversed().forEach { (index, register) ->
                method.addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $filter
                        move-result-object v$register
                    """,
                )
                count++
            }
        }
    }
    return count
}

private fun FieldReference.key() = "$definingClass->$name:$type"

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

private fun Instruction.calls(method: Method): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return opcode in STATIC_CALLS && reference.definingClass == method.definingClass &&
        reference.name == method.name && reference.returnType == method.returnType &&
        reference.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
}

/** The first register an invoke passes, or null for one with no arguments. */
private fun Instruction.firstArgument(): Int? = when (this) {
    is FiveRegisterInstruction -> if (registerCount > 0) registerC else null
    is RegisterRangeInstruction -> if (registerCount > 0) startRegister else null
    else -> null
}

/**
 * The String field the Uri the builder returns at [call] ends up in: its result moved out, its
 * toString() moved out, and that register put into a field within a few instructions. Null when
 * the code at [call] does anything else.
 *
 * Only a straight run of code counts (audit A20): the register can't be written again before the
 * store, no branch, switch, return or throw may come first, and no jump may land anywhere between
 * the call and the store, since a path arriving there would store some other value.
 */
private fun List<Instruction>.storedField(call: Int): String? {
    val uri = getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { (it as OneRegisterInstruction).registerA } ?: return null
    val text = getOrNull(call + 2)?.takeIf { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode in VIRTUAL_CALLS && reference?.name == "toString" &&
            reference.parameterTypes.isEmpty() && reference.returnType == "Ljava/lang/String;" && instruction.firstArgument() == uri
    }?.let { getOrNull(call + 3) }?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { (it as OneRegisterInstruction).registerA } ?: return null
    val landing = jumpTargets()
    if ((call + 1..call + 3).any { it in landing }) return null
    for (index in call + 4 until minOf(size, call + 4 + STORE_WINDOW)) {
        if (index in landing) return null
        val instruction = this[index]
        if (instruction.opcode == Opcode.IPUT_OBJECT || instruction.opcode == Opcode.SPUT_OBJECT) {
            if ((instruction as OneRegisterInstruction).registerA != text) continue
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            return field.takeIf { it.type == "Ljava/lang/String;" }?.key()
        }
        if (instruction is OffsetInstruction || !instruction.opcode.canContinue() || instruction.writes(text)) return null
    }
    return null
}

/** The indices an if, goto or switch in this code can jump to. */
private fun List<Instruction>.jumpTargets(): Set<Int> {
    val address = IntArray(size + 1)
    forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val byAddress = HashMap<Int, Int>().apply { address.forEachIndexed { index, at -> putIfAbsent(at, index) } }
    val targets = HashSet<Int>()
    forEachIndexed { index, instruction ->
        val offset = (instruction as? OffsetInstruction)?.codeOffset ?: return@forEachIndexed
        val target = byAddress[address[index] + offset] ?: return@forEachIndexed
        when (instruction.opcode) {
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> (this[target] as? SwitchPayload)?.switchElements?.forEach { element ->
                byAddress[address[index] + element.offset]?.let(targets::add)
            }
            Opcode.FILL_ARRAY_DATA -> Unit
            else -> targets += target
        }
    }
    return targets
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

/** The index and target register of each read of one of [fields] in this method. */
private fun Method.reads(fields: Set<String>): List<Pair<Int, Int>> =
    implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        (instruction.opcode == Opcode.IGET_OBJECT || instruction.opcode == Opcode.SGET_OBJECT) &&
            ((instruction as ReferenceInstruction).reference as FieldReference).key() in fields
    }?.map { (index, instruction) -> index to (instruction as OneRegisterInstruction).registerA }.orEmpty()
