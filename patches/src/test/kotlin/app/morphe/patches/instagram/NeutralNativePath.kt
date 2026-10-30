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

/**
 * Compare an original native method with its stock path after validated neutral hook blocks.
 *
 * A nop right ahead of a payload only aligns it, and the assembler adds or drops one whenever code
 * ahead of the payload changes length by an odd number of code units, so those are left out on both
 * sides. Every other instruction is compared in place.
 */
internal class NeutralNativePath(method: Method) {
    private val before = ControlFlow.of(method)
    private val stock = before.instructions.indices.filterNot { aligns(before.instructions, it) }
    private val operands = stock.map { operands(before.instructions[it]) }
    private val registers = method.implementation!!.registerCount
    private val handlers = handlers(method) { place(it, stock) }

    /**
     * [added] are the indices of the hook's own instructions in [method]. [substituted] are indices
     * that each replace one stock instruction in its place: they keep its edges, and the caller checks
     * what they became.
     */
    fun assertPreserved(what: String, method: Method, added: Set<Int>, substituted: Set<Int> = emptySet()) {
        val after = ControlFlow.of(method)
        val kept = after.instructions.indices.filterNot { it in added || aligns(after.instructions, it) }
        assertEquals("$what: native register frame", registers, method.implementation!!.registerCount)
        assertEntries("$what: every stock opcode and operand",
            operands.mapIndexed { at, stock -> if (kept.getOrNull(at)?.let { it in substituted } == true) null else stock },
            kept.map { if (it in substituted) null else operands(after.instructions[it]) })
        fun project(target: Int): Int = place(target, kept)
        fun original(target: Int): Int = place(target, stock)
        assertEntries("$what: every original branch and fallthrough",
            stock.map { at -> before.normal[at].map(::original).distinct().sorted() },
            kept.map { at -> after.normal[at].map(::project).distinct().sorted() })
        // Two catch types can share a handler. Compare graph edges as sets, then retain every
        // type and range in the ownership comparison below.
        assertEntries("$what: every original exception handler",
            stock.map { at -> before.exceptional[at].map(::original).distinct().sorted() },
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

    private companion object {
        val PAYLOADS = setOf(Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD)

        /** Whether the instruction at [at] is a nop that only aligns the payload after it. */
        fun aligns(code: List<Instruction>, at: Int): Boolean = code[at].opcode == Opcode.NOP && code.getOrNull(at + 1)?.opcode in PAYLOADS

        /**
         * Where [target], an index into the whole method, lands among [kept], the sorted indices
         * compared: its own place, or the place of the next one kept after it (for a hook's own
         * instruction or an alignment nop), or the end.
         */
        fun place(target: Int, kept: List<Int>): Int = kept.binarySearch(target).let { if (it >= 0) it else -it - 1 }
    }
}
