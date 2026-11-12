/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.flags

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** How far after a flag's id its read may come: 449 puts at most a cast between them. */
private const val READ_WITHIN = 4

/** A read of a server flag: the flag, the method, and the index and register of its move-result. */
internal class FlagRead(
    val flag: Long,
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val moveResult: Int,
    val register: Int,
)

/**
 * Every place Instagram loads one of [flags] and reads it as a boolean: on 449 a server flag is
 * `const-wide <id>`, at most a cast, a call taking the id last and answering a boolean
 * (`MobileConfigUnsafeContext.BXd(J)Z` or a static wrapper), and its move-result. Fails, in
 * [patch]'s name, when a flag is never read or is loaded for any other use, since that's an update
 * the patch hasn't seen. The extension's own code is left out.
 */
internal fun BytecodePatchContext.findFlagReads(patch: String, flags: List<Long>): List<FlagRead> {
    val reads = mutableListOf<FlagRead>()
    val loading = flags.flatMapTo(HashSet()) { flag -> classesLoading(flag).map { it.type } }
    classDefForEach { classDef ->
        if (classDef.type !in loading) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.CONST_WIDE) return@forEachIndexed
                val flag = (instruction as WideLiteralInstruction).wideLiteral
                if (flag !in flags) return@forEachIndexed
                val where = "${classDef.type}->${method.name} loads ${flag.toString(16)}"
                val id = (instruction as OneRegisterInstruction).registerA
                val callAt = (index + 1..minOf(index + READ_WITHIN, code.lastIndex))
                    .firstOrNull { code[it].methodReference() != null }
                    ?: refuse(patch, "$where with no call after it")
                val call = code[callAt].methodReference()!!
                val passed = code[callAt].arguments()
                if (passed.takeLast(2) != listOf(id, id + 1) || call.parameterTypes.lastOrNull()?.toString() != "J") {
                    refuse(patch, "$where and then calls ${call.definingClass}->${call.name} without it last")
                }
                if (call.returnType != "Z") {
                    refuse(patch, "$where for ${call.definingClass}->${call.name}, which answers ${call.returnType}")
                }
                val result = code.getOrNull(callAt + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) refuse(patch, "$where and drops the answer")
                reads += FlagRead(
                    flag, classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
                    callAt + 1, (result as OneRegisterInstruction).registerA,
                )
            }
        }
    }
    val unread = flags.filter { flag -> reads.none { it.flag == flag } }
    if (unread.isNotEmpty()) refuse(patch, "nothing reads ${unread.joinToString { it.toString(16) }}")
    return reads
}

/** Passes each read's answer through [hook], an `(I)Z` method, right after its move-result. */
internal fun BytecodePatchContext.answerFlagReads(reads: List<FlagRead>, hook: String) {
    reads.groupBy { Triple(it.type, it.name, it.parameters) }.forEach { (_, inMethod) ->
        val first = inMethod.first()
        val method = mutableClassDefBy(first.type).methods.single {
            it.name == first.name && it.returnType == first.returnType &&
                it.parameterTypes.map(CharSequence::toString) == first.parameters
        }
        inMethod.sortedByDescending { it.moveResult }.forEach { read ->
            method.addInstructions(
                read.moveResult + 1,
                """
                    invoke-static/range { v${read.register} .. v${read.register} }, $hook
                    move-result v${read.register}
                """,
            )
        }
    }
}

/**
 * One load of a server flag and the read it feeds, found by following the load through at most a
 * goto and a cast to the call that reads it. [shared] says whether another path reaches the same
 * read: 449 picks between an ordinary reel's flag and an ad's in a branch and reads whichever it
 * picked in one place, so answering that read would answer both.
 */
internal class FlagLoad(
    val flag: Long,
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    /** "Z" for a yes or no, "J" for a number. */
    val answer: String,
    val loadAt: Int,
    /** The check-cast on the way to the call, or null. */
    val castAt: Int?,
    val callAt: Int,
    val resultAt: Int,
    val register: Int,
    val shared: Boolean,
)

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/**
 * Every place outside the extension that loads [flag], and the read it feeds: the load
 * (`const-wide`), then at most [READ_WITHIN] gotos and casts, a call taking the id last and
 * answering [answer] (`Z` or `J`), and its move-result. Fails, in [patch]'s name, when a load feeds
 * no such read, since that's an update the patch hasn't seen. An empty answer is the caller's to
 * refuse, with the count it expected.
 */
internal fun BytecodePatchContext.findFlagLoads(patch: String, flag: Long, answer: String): List<FlagLoad> {
    require(answer == "Z" || answer == "J") { "a flag is read as Z or J, not $answer" }
    val loads = mutableListOf<FlagLoad>()
    val loading = classesLoading(flag).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in loading) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val at = code.indices.filter {
                code[it].opcode == Opcode.CONST_WIDE && (code[it] as WideLiteralInstruction).wideLiteral == flag
            }
            if (at.isEmpty()) return@forEach
            val flow = ControlFlow.of(method)
            val predecessors = Array(code.size) { mutableSetOf<Int>() }
            code.indices.forEach { from ->
                (flow.normal[from] + flow.exceptional[from]).forEach { to -> predecessors[to] += from }
            }
            at.forEach { loadAt ->
                loads += followLoad(patch, flag, answer, classDef.type, method, code, flow, predecessors, loadAt)
            }
        }
    }
    return loads
}

private fun followLoad(
    patch: String,
    flag: Long,
    answer: String,
    type: String,
    method: Method,
    code: List<Instruction>,
    flow: ControlFlow,
    predecessors: Array<MutableSet<Int>>,
    loadAt: Int,
): FlagLoad {
    val where = "$type->${method.name} loads ${flag.toString(16)}"
    val id = (code[loadAt] as OneRegisterInstruction).registerA
    // The way from the load to the read, the load first.
    val path = mutableListOf(loadAt)
    var at = flow.normal[loadAt].singleOrNull() ?: refuse(patch, "$where and doesn't go on to one instruction")
    var castAt: Int? = null
    while (code[at].opcode in GOTOS || code[at].opcode == Opcode.CHECK_CAST) {
        if (path.size > READ_WITHIN) refuse(patch, "$where with no read of it in reach")
        if (code[at].opcode == Opcode.CHECK_CAST) {
            if (castAt != null) refuse(patch, "$where and casts twice before reading it")
            val cast = (code[at] as OneRegisterInstruction).registerA
            if (cast == id || cast == id + 1) refuse(patch, "$where and casts the id's register")
            castAt = at
        }
        path += at
        at = flow.normal[at].singleOrNull() ?: refuse(patch, "$where and branches before reading it")
    }
    val call = code[at].methodReference() ?: refuse(patch, "$where with no call after it")
    if (code[at].arguments().takeLast(2) != listOf(id, id + 1) || call.parameterTypes.lastOrNull()?.toString() != "J") {
        refuse(patch, "$where and then calls ${call.definingClass}->${call.name} without it last")
    }
    if (call.returnType != answer) {
        refuse(patch, "$where for ${call.definingClass}->${call.name}, which answers ${call.returnType}, not $answer")
    }
    val resultAt = at + 1
    val moveResult = if (answer == "J") Opcode.MOVE_RESULT_WIDE else Opcode.MOVE_RESULT
    if (code.getOrNull(resultAt)?.opcode != moveResult) refuse(patch, "$where and drops the answer")
    // The read is this load's own when each step after the load is reached only from the step
    // before it. Anything else reaching a step (another id's load falling or jumping onto the
    // cast, a branch onto the call) reads through it too.
    path += at
    path += resultAt
    val shared = path.zipWithNext().any { (from, to) -> predecessors[to] != setOf(from) }
    return FlagLoad(
        flag, type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType, answer,
        loadAt, castAt, at, resultAt, (code[resultAt] as OneRegisterInstruction).registerA, shared,
    )
}

/**
 * Passes [load]'s answer through [hook], an `(I)Z` method for a yes or no and a `(J)J` one for a
 * number, so only this flag's answer changes:
 * - A read of the load's own gets the hook right after its move-result.
 * - A shared read stays as it is for the other loads. This load gets a read of its own, a copy of
 *   the cast and the call put straight after the load, answered through the hook, which then jumps
 *   to the instruction after the shared read's move-result. Every branch that went to the shared
 *   read still goes there, since code put in front of an instruction leaves its label on it.
 *
 * Each pair is a load and its hook. Every write in a method is worked out from the method as it
 * was found, then made last first, so no write moves another's place.
 */
internal fun BytecodePatchContext.answerFlagLoads(answers: List<Pair<FlagLoad, String>>) {
    answers.groupBy { (load, _) -> Triple(load.type, load.name, load.parameters) }.forEach { (_, inMethod) ->
        val first = inMethod.first().first
        val method = mutableClassDefBy(first.type).methods.single {
            it.name == first.name && it.returnType == first.returnType &&
                it.parameterTypes.map(CharSequence::toString) == first.parameters
        }
        val code = method.implementation!!.instructions.toList()
        class Write(val at: Int, val smali: String, val after: ExternalLabel?)
        val writes = inMethod.map { (load, hook) ->
            val wide = load.answer == "J"
            val register = load.register
            val moveResult = if (wide) "move-result-wide" else "move-result"
            val answered = """
                invoke-static/range { v$register .. v${if (wide) register + 1 else register} }, $hook
                $moveResult v$register
            """
            if (!load.shared) {
                Write(load.resultAt + 1, answered, null)
            } else {
                val ownRead = listOfNotNull(load.castAt, load.callAt).joinToString("\n") { code[it].toSmali() }
                Write(
                    load.loadAt + 1,
                    """
                        $ownRead
                        $moveResult v$register
                        $answered
                        goto/32 :after_read
                    """,
                    // The instruction itself, not its index, so writes below it can't move it.
                    ExternalLabel("after_read", code[load.resultAt + 1]),
                )
            }
        }
        writes.sortedByDescending { it.at }.forEach { write ->
            if (write.after == null) method.addInstructions(write.at, write.smali)
            else method.addInstructionsWithLabels(write.at, write.smali, write.after)
        }
    }
}

/** A check-cast or an invoke written out as smali, registers and reference as they are. */
private fun Instruction.toSmali(): String {
    val reference = (this as ReferenceInstruction).reference.toString()
    return when (this) {
        is OneRegisterInstruction -> "${opcode.name} v$registerA, $reference"
        is RegisterRangeInstruction -> "${opcode.name} { v$startRegister .. v${startRegister + registerCount - 1} }, $reference"
        is FiveRegisterInstruction -> "${opcode.name} { ${arguments().joinToString { "v$it" }} }, $reference"
        else -> throw PatchException("can't copy ${opcode.name}")
    }
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke passes, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun refuse(patch: String, detail: String): Nothing = throw PatchException("$patch: $detail")
