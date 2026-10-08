/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.BitSet

/*
 * Feed draws each post's action row, its buttons and their counts, from an immutable state whose
 * toString prints every flag after a label like ", isCommentsEnabled=". The flags are final, so
 * only the state's constructors set them. A patch finds a flag by its label and every constructor
 * write of it, then passes each write through a hook of its own, so a state built while its switch
 * is on keeps that part of the row off however often Feed draws the row from it. Hide the Repost
 * button and Hide comments both hook the same constructor this way.
 */

private const val STRING_BUILDER = "Ljava/lang/StringBuilder;"
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val VALUE_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/** How many copies of a printed value toString may make between loading and appending it. */
private const val COPY_REACH = 4

private fun refuse(patch: String, detail: String): Nothing = throw PatchException("$patch: $detail")

/**
 * One write of a flag in a constructor of Feed's action-row state: `iput-boolean` of [value] into
 * [holder]'s [field] at [at], and the register the hook's answer goes into. That's [value] itself
 * when nothing reads it after the write, and otherwise a spare one the write then stores.
 */
internal class FeedStateWrite(
    val parameters: List<String>,
    val at: Int,
    val value: Int,
    val holder: Int,
    val field: FieldReference,
    val answer: Int,
)

/**
 * The one class whose toString prints [label]: Feed's action-row state. [patch] names the patch in
 * a refusal, made when no class prints the label or more than one does.
 */
internal fun BytecodePatchContext.feedRowState(patch: String, label: String): ClassDef {
    val states = classesHolding(label).filter { classDef ->
        classDef.methods.any { it.isToString() && it.holdsString(label) }
    }
    return states.singleOrNull()
        ?: refuse(patch, "expected one Feed action-row state printing \"$label\", found ${states.size}")
}

/**
 * Right before each of [writes] in the state of [type], passes the value through [hook], a static
 * method taking the flag and answering it. A label on a write moves onto the call, so a branch to
 * the write runs the call too. Every write is checked before the first changes, and the change is
 * made only when the answer is called. [what] names the flags in a refusal.
 */
internal fun BytecodePatchContext.prepareFlagWrites(
    patch: String,
    type: String,
    what: String,
    writes: List<FeedStateWrite>,
    hook: String,
): () -> Unit {
    val state = mutableClassDefBy(type)
    val constructors = writes.groupBy { it.parameters }.map { (parameters, inOne) ->
        state.methods.single { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == parameters } to inOne
    }
    for ((constructor, inOne) in constructors) for (write in inOne) {
        val store = constructor.getInstruction(write.at)
        val field = (store as? ReferenceInstruction)?.reference as? FieldReference
        if (store.opcode != Opcode.IPUT_BOOLEAN || field == null || !field.sameAs(write.field) ||
            (store as TwoRegisterInstruction).registerA != write.value
        ) refuse(patch, "$type's constructor changed at instruction ${write.at} before its $what flags were hooked")
    }
    return {
        for ((constructor, inOne) in constructors) for (write in inOne.sortedByDescending { it.at }) {
            constructor.addInstructionsAtControlFlowLabel(
                write.at,
                """
                    invoke-static { v${write.value} }, $hook
                    move-result v${write.answer}
                """,
            )
            if (write.answer != write.value) {
                constructor.replaceInstruction(write.at + 2, "iput-boolean v${write.answer}, v${write.holder}, ${write.field}")
            }
        }
    }
}

internal fun Method.isToString() = name == "toString" && parameterTypes.isEmpty() &&
    returnType == "Ljava/lang/String;" && !AccessFlags.STATIC.isSet(accessFlags)

internal fun FieldReference.sameAs(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

internal fun Method.holdsString(value: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true

/**
 * The flag this toString prints under [label]: the boolean field of [type] it loads from itself
 * and appends right after appending the label. Instagram 450 copies most flags into another
 * register once loaded and back right before appending, so copies are followed both ways.
 * Refuses, saying what it found instead.
 */
internal fun Method.printedFlag(patch: String, type: String, label: String): FieldReference {
    val code = implementation!!.instructions.toList()
    val loads = code.indices.filter { code[it].loadsString(label) }
    val load = loads.singleOrNull() ?: refuse(patch, "$type->toString loads \"$label\" ${loads.size} times")
    var at = load + 1
    if (code.getOrNull(at)?.appended("Ljava/lang/String;") != (code[load] as OneRegisterInstruction).registerA) {
        refuse(patch, "$type->toString doesn't append \"$label\" right after loading it")
    }
    at++
    if (code.getOrNull(at)?.opcode == Opcode.MOVE_RESULT_OBJECT) at++
    if (code.getOrNull(at)?.opcode in VALUE_MOVES && code.getOrNull(at + 1)?.appended("Z") != null) at++
    val value = code.getOrNull(at)?.appended("Z")
        ?: refuse(patch, "$type->toString prints something other than a boolean after \"$label\"")
    val flow = ControlFlow.of(this)
    val reading = flow.loadReaching(at, value)
        ?: refuse(patch, "$type->toString prints \"$label\" from a value it doesn't load in one place")
    val read = code[reading]
    val field = (read as? ReferenceInstruction)?.reference as? FieldReference
    if (read.opcode != Opcode.IGET_BOOLEAN || field == null || field.definingClass != type) {
        refuse(patch, "$type->toString prints \"$label\" from something other than a boolean field of its own")
    }
    if (!flow.holdsThis(reading, (read as TwoRegisterInstruction).registerB, localRegisterCount())) {
        refuse(patch, "$type->toString prints \"$label\" from an object other than itself")
    }
    return field
}

/**
 * Each write of [flag] in this class, which has to be a final boolean field of its own, so set only
 * by its constructors. Refuses when it isn't, when another method sets it all the same, or when a
 * write has no register for the hook's answer.
 */
internal fun ClassDef.flagWrites(patch: String, flag: FieldReference): List<FeedStateWrite> {
    val declared = fields.singleOrNull { it.name == flag.name && it.type == flag.type }
    if (flag.definingClass != type || flag.type != "Z" || declared == null ||
        AccessFlags.STATIC.isSet(declared.accessFlags) || !AccessFlags.FINAL.isSet(declared.accessFlags)
    ) refuse(patch, "$type's ${flag.name} isn't a final boolean field of its own")
    val writes = mutableListOf<FeedStateWrite>()
    for (method in methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        for ((at, instruction) in code.withIndex()) {
            if (instruction.opcode != Opcode.IPUT_BOOLEAN) continue
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            if (!field.sameAs(flag)) continue
            if (method.name != "<init>") refuse(patch, "$type->${method.name} sets ${flag.name}, which only its constructor should")
            val store = instruction as TwoRegisterInstruction
            // The value's register takes the answer when nothing reads it afterwards, handlers included.
            val answer = if (method.readsAfter(at, store.registerA).isEmpty()) store.registerA
                else method.freeLocalsAt(patch, at, 1).single()
            writes += FeedStateWrite(
                method.parameterTypes.map(CharSequence::toString), at, store.registerA, store.registerB, field, answer,
            )
        }
    }
    return writes
}

private fun Instruction.loadsString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

/** The register this hands to `StringBuilder.append([parameter])`, or null when it's no such call. */
private fun Instruction.appended(parameter: String): Int? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val called = (this as ReferenceInstruction).reference as MethodReference
    if (called.definingClass != STRING_BUILDER || called.name != "append" ||
        called.parameterTypes.map(CharSequence::toString) != listOf(parameter)
    ) return null
    return when (this) {
        is FiveRegisterInstruction -> registerD
        is RegisterRangeInstruction -> startRegister + 1
        else -> null
    }
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/**
 * The one instruction other than a copy whose write is what [register] holds when instruction [at]
 * runs, walking back through up to [COPY_REACH] copies. Null when more than one write can reach it,
 * or it's what the register held when the method was called.
 */
private fun ControlFlow.loadReaching(at: Int, register: Int, depth: Int = 0): Int? {
    val writer = writersReaching(at, register).singleOrNull()?.takeIf { it >= 0 } ?: return null
    val write = instructions[writer]
    if (write.opcode !in VALUE_MOVES) return writer
    return if (depth < COPY_REACH) loadReaching(writer, (write as TwoRegisterInstruction).registerB, depth + 1) else null
}

/**
 * Every instruction whose write to [register] can be what instruction [at] reads, -1 standing for
 * what the register held when the method was called. Walks back along every path, branches,
 * switches and handlers included, to the nearest write. A handler sees the register as it was
 * before the instruction that threw, so that instruction's own write is walked past.
 */
private fun ControlFlow.writersReaching(at: Int, register: Int): Set<Int> {
    val normalFrom = Array(instructions.size) { mutableListOf<Int>() }
    val thrownFrom = Array(instructions.size) { mutableListOf<Int>() }
    for (from in instructions.indices) {
        normal[from].forEach { normalFrom[it] += from }
        exceptional[from].forEach { thrownFrom[it] += from }
    }
    val writers = sortedSetOf<Int>()
    val seen = BitSet()
    val pending = ArrayDeque<Int>()
    // Each pending instruction is one whose register before it runs is wanted.
    fun before(index: Int) {
        if (!seen[index]) { seen.set(index); pending += index }
    }
    before(at)
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (index == 0) writers += -1
        normalFrom[index].forEach { from -> if (instructions[from].writes(register)) writers += from else before(from) }
        thrownFrom[index].forEach(::before)
    }
    return writers
}

/**
 * Whether [register] holds `this`, which the method keeps in [self], when instruction [at] runs:
 * [self] untouched since the method started, or a copy made of it.
 */
private fun ControlFlow.holdsThis(at: Int, register: Int, self: Int, depth: Int = 0): Boolean {
    val writers = writersReaching(at, register)
    if (writers == setOf(-1)) return register == self
    val copy = writers.singleOrNull()?.takeIf { it >= 0 } ?: return false
    val move = instructions[copy]
    return depth < 4 && move.opcode in OBJECT_MOVES &&
        holdsThis(copy, (move as TwoRegisterInstruction).registerB, self, depth + 1)
}
