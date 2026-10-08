/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram

import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import org.junit.Assert.assertEquals

/** Compare an original native method with its stock path after validated neutral hook blocks. */
internal class NeutralNativePath(method: Method) {
    private val before = ControlFlow.of(method)
    private val operands = before.instructions.map(::operands)
    private val registers = method.implementation!!.registerCount
    private val handlers = handlers(method) { it }

    /**
     * [added] are the indices of the hook's own instructions in [method]. [substituted] are indices
     * that each replace one stock instruction in its place: they keep its edges, and the caller checks
     * what they became.
     */
    fun assertPreserved(what: String, method: Method, added: Set<Int>, substituted: Set<Int> = emptySet()) {
        val after = ControlFlow.of(method)
        val kept = after.instructions.indices.filterNot(added::contains)
        assertEquals("$what: native register frame", registers, method.implementation!!.registerCount)
        assertEntries("$what: every stock opcode and operand",
            operands.mapIndexed { at, stock -> if (kept.getOrNull(at)?.let { it in substituted } == true) null else stock },
            kept.map { if (it in substituted) null else operands(after.instructions[it]) })
        val oldIndex = kept.withIndex().associate { it.value to it.index }
        fun project(target: Int): Int = if (target == after.instructions.size) kept.size else
            oldIndex.getValue(if (target in added) kept.first { it > target } else target)
        assertEntries("$what: every original branch and fallthrough", before.normal.map { it.sorted() },
            kept.map { at -> after.normal[at].map(::project).distinct().sorted() })
        // Two catch types can share a handler. Compare graph edges as sets, then retain every
        // type and range in the ownership comparison below.
        assertEntries("$what: every original exception handler", before.exceptional.map { it.distinct().sorted() },
            kept.map { at -> after.exceptional[at].map(::project).distinct().sorted() })
        assertEquals("$what: original catch types and protected ranges", handlers, handlers(method, ::project))
    }

    private fun assertEntries(what: String, expected: List<Any?>, actual: List<Any?>) {
        assertEquals("$what: count", expected.size, actual.size)
        expected.indices.forEach { at -> assertEquals("$what at native instruction $at", expected[at], actual[at]) }
    }

    private fun handlers(method: Method, project: (Int) -> Int): List<Any> {
        val code = method.implementation!!.instructions.toList()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        fun at(address: Int) = project(addresses.indexOf(address).also { require(it >= 0) })
        return method.implementation!!.tryBlocks.map { block ->
            listOf(at(block.startCodeAddress), at(block.startCodeAddress + block.codeUnitCount),
                block.exceptionHandlers.map { it.exceptionType to at(it.handlerCodeAddress) })
        }
    }

    private fun operands(instruction: Instruction): List<Any?> = listOf(
        // Dexlib widens a goto when added code moves its destination out of the short range.
        // Its exact projected destination is checked above, independently of its encoding.
        when (instruction.opcode) {
            Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> instruction.opcode
        },
        (instruction as? OneRegisterInstruction)?.registerA,
        (instruction as? TwoRegisterInstruction)?.registerB,
        (instruction as? ThreeRegisterInstruction)?.registerC,
        (instruction as? FiveRegisterInstruction)?.let {
            listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
        },
        (instruction as? RegisterRangeInstruction)?.let { it.startRegister to it.registerCount },
        (instruction as? WideLiteralInstruction)?.wideLiteral,
        (instruction as? ReferenceInstruction)?.reference?.toString(),
        (instruction as? DualReferenceInstruction)?.reference2?.toString(),
        (instruction as? ArrayPayload)?.let { it.elementWidth to it.arrayElements.toList() },
        (instruction as? SwitchPayload)?.switchElements?.map { it.key },
    )
}
