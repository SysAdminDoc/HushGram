/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction30t
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Passes every object this method returns through [filter], a static `(T)T` call, so the caller
 * gets the filter's answer. The call goes in at the return's own label, so a branch that jumps
 * straight to a return passes through it too. Throws naming [patch] when the method returns no
 * object.
 *
 * A return inside a try range doesn't take the call in place. A synchronized block's catch-all
 * covers its monitor-exit and the return after it, so a call there could throw into a handler that
 * unlocks a second time, and ART then fails the method's lock verification and runs it slower
 * (ResourcesCompat's color state list loader on Instagram 453). Such a return jumps instead to its
 * own call and return, placed after an instruction that never falls through, outside every try
 * range. A method with no such place takes the call in place as before.
 */
internal fun MutableMethod.filterEveryReturn(patch: String, filter: String) {
    val implementation = implementation ?: throw PatchException("$patch: $definingClass->$name has no body")
    val returns = implementation.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) throw PatchException("$patch: $definingClass->$name returns no object")

    val call = { register: Int ->
        """
            invoke-static/range { v$register .. v$register }, $filter
            move-result-object v$register
        """
    }
    val covered = coveredIndexes()
    val (inside, outside) = returns.partition { (index, _) -> index in covered }
    // Locations follow their instruction as code goes in ahead of it, so they're taken first.
    val jumps = inside.map { (index, register) -> implementation.instructions[index].location to register }
    outside.asReversed().forEach { (index, register) ->
        addInstructionsAtControlFlowLabel(index, call(register))
    }
    jumps.forEach { (location, register) ->
        val tail = outsideEveryTry(coveredIndexes())
        if (tail == null) {
            addInstructionsAtControlFlowLabel(location.index, call(register))
            return@forEach
        }
        addInstructions(tail, call(register) + "return-object v$register")
        replaceInstruction(location.index, BuilderInstruction30t(Opcode.GOTO_32, implementation.newLabelForIndex(tail)))
    }
}

/** Instructions after which nothing falls through. */
private val NO_FALL_THROUGH = setOf(
    Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT,
    Opcode.THROW, Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32,
)

/** The indexes of the instructions some try range covers. */
private fun MutableMethod.coveredIndexes(): Set<Int> = implementation!!.tryBlocks.flatMapTo(HashSet()) { block ->
    block.start.location.index until block.end.location.index
}

/**
 * An index to put code no instruction falls into, outside every try range: right after a return,
 * throw or goto that no try range covers, before an instruction no try range covers either.
 */
private fun MutableMethod.outsideEveryTry(covered: Set<Int>): Int? {
    val code = implementation!!.instructions
    return (1 until code.size).firstOrNull { at ->
        code[at - 1].opcode in NO_FALL_THROUGH && at - 1 !in covered && at !in covered
    }
}

/**
 * Passes every boolean this method returns through [filter], a static `(I)Z` call, so the caller
 * gets the filter's answer. The value goes over as an int (no hook takes a boolean, see
 * BooleanHookParametersTest) and comes back as a boolean. As in [filterEveryReturn], the call goes
 * in at the return's own label, so a branch that jumps straight to a return passes through it too.
 * Throws naming [patch] when the method doesn't answer a boolean or has no return.
 */
internal fun MutableMethod.filterEveryBooleanReturn(patch: String, filter: String) {
    val implementation = implementation ?: throw PatchException("$patch: $definingClass->$name has no body")
    if (returnType != "Z") throw PatchException("$patch: $definingClass->$name answers $returnType, not a boolean")
    if (!filter.endsWith("(I)Z")) throw PatchException("$patch: $filter doesn't take an int and answer a boolean")
    val returns = implementation.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) throw PatchException("$patch: $definingClass->$name has no return")

    returns.asReversed().forEach { (index, register) ->
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $filter
                move-result v$register
            """,
        )
    }
}

/**
 * Passes every `const-string` of [value] in the app's own code through [filter], a static
 * `(String)String` call, right after the string is loaded. Only the path through the load reaches
 * the call: a branch to the next instruction still lands on it. Answers how many loads it found.
 * A load in a method [covered] says another hook already passes the value through [filter] is
 * counted and left alone, so it isn't passed through twice.
 */
internal fun BytecodePatchContext.filterEveryStringLoad(
    value: String,
    filter: String,
    covered: (Method) -> Boolean = { false },
): Int {
    val owners = mutableListOf<String>()
    classesHolding(value).forEach { classDef ->
        if (classDef.methods.any { method -> method.stringLoads(value).isNotEmpty() }) owners += classDef.type
    }
    var count = 0
    owners.forEach { type ->
        mutableClassDefBy(type).methods.forEach { method ->
            if (covered(method)) {
                count += method.stringLoads(value).size
                return@forEach
            }
            method.stringLoads(value).asReversed().forEach { (index, register) ->
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

/** The index and target register of each `const-string` of [value] in this method. */
private fun com.android.tools.smali.dexlib2.iface.Method.stringLoads(value: String): List<Pair<Int, Int>> =
    implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
            ((instruction as ReferenceInstruction).reference as StringReference).string == value
    }?.map { (index, instruction) -> index to (instruction as OneRegisterInstruction).registerA }.orEmpty()
