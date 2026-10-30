/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.util.ControlFlow
import app.morphe.util.isConditionalBranchInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val INBOX_ROW = "$EXTENSION_PACKAGE/metaai/MetaAi;->inboxRow(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val INBOX_ROW_RENDER_MARKER = "instagram.features.direct.inbox.feature.hatchinboxrow.ui.HatchInboxRow (HatchInboxRow.kt:48)"
internal const val INBOX_ROW_ITEM_MARKER = "instagram.features.direct.inbox.feature.hatchinboxrow.ui.HatchInboxRowItem.<init>.<anonymous> (HatchInboxRowItem.kt:12)"
internal const val INBOX_SECTION_MARKER = "No section generator found for section type "

internal class InboxRowSite(val type: String, val name: String, val parameters: List<String>,
                            val read: Int, val register: Int, val model: String)

/**
 * The retained Compose source markers identify the Hatch model without naming a renamed class.
 * Its section builder reads that model into a local, checks null, and drops an ineligible model
 * through the same null path. Later, a second null branch skips only the sorted Hatch insertion.
 * Discover and validate all of this before changing any method, registration or model state.
 */
internal fun BytecodePatchContext.findOptionalInboxRow(): InboxRowSite {
    val renderers = classesHolding(INBOX_ROW_RENDER_MARKER).asSequence().flatMap { it.methods.asSequence() }.filter { method ->
        val parameters = method.parameterTypes.map(CharSequence::toString)
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && parameters.size == 6 &&
            parameters.take(4).all { it.startsWith("L") } && parameters.takeLast(2) == listOf("I", "I") &&
            method.rowCode().any { it.rowString() == INBOX_ROW_RENDER_MARKER }
    }.toList()
    val renderer = renderers.singleOrNull() ?: refuseInbox("${renderers.size} Hatch row renderers, not one")
    val model = renderer.parameterTypes[3].toString()
    val callbacks = classesHolding(INBOX_ROW_ITEM_MARKER).asSequence().flatMap { it.methods.asSequence() }.filter { method ->
        val code = method.rowCode()
        val call = code.singleOrNull { it.rowMethod()?.toString() == renderer.toString() }
        val modelRegister = call?.rowArguments()?.getOrNull(3)
        code.any { it.rowString() == INBOX_ROW_ITEM_MARKER } && modelRegister != null &&
            code.any { it.opcode == Opcode.CHECK_CAST && it.rowReference() == model &&
                (it as OneRegisterInstruction).registerA == modelRegister }
    }.toList()
    if (callbacks.size != 1) refuseInbox("${callbacks.size} Hatch row item callbacks, not one")
    val candidates = classesHolding(INBOX_SECTION_MARKER).filter { owner -> owner.methods.any { method ->
        method.rowCode().any { it.rowString() == INBOX_SECTION_MARKER }
    } }.flatMap { owner ->
        owner.methods.mapNotNull { method -> (optionalInboxRead(method, model) ?: assignedInboxRead(method, model))?.let { read ->
            InboxRowSite(owner.type, method.name, method.parameterTypes.map(CharSequence::toString), read,
                (method.rowCode()[read] as OneRegisterInstruction).registerA, model)
        } }
    }
    return candidates.singleOrNull() ?: refuseInbox("${candidates.size} optional Hatch inbox row reads, not one")
}

/** Only the local optional row is filtered. The native list builder and its maps stay in charge. */
internal fun BytecodePatchContext.holdOptionalInboxRow(site: InboxRowSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(site.read + 1, """
        invoke-static/range { v${site.register} .. v${site.register} }, $INBOX_ROW
        move-result-object v${site.register}
        check-cast v${site.register}, ${site.model}
    """)
}

/**
 * 453's shape: the model is read into a local, a null or ineligible one skips to a common point, and
 * an eligible one is copied into the row local that starts out null and goes on to the sorted insertion.
 */
private fun assignedInboxRead(method: Method, model: String): Int? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z" ||
        method.parameterTypes.size != 4 || method.parameterTypes.any { !it.startsWith("L") }) return null
    val code = method.rowCode()
    val read = code.indices.singleOrNull { code[it].opcode == Opcode.IGET_OBJECT && code[it].rowField()?.type == model } ?: return null
    if (code.indices.any { index -> code.rowTarget(index)?.let { it in read + 1..read + 4 } == true }) return null
    val register = (code[read] as OneRegisterInstruction).registerA
    val skip = code.rowTarget(read + 1) ?: return null
    val eligibility = code.getOrNull(read + 2) as? TwoRegisterInstruction ?: return null
    if (code[read + 1].opcode != Opcode.IF_EQZ || (code[read + 1] as OneRegisterInstruction).registerA != register ||
        code[read + 2].opcode != Opcode.IGET_BOOLEAN || eligibility.registerB != register ||
        code[read + 2].rowField()?.let { it.definingClass != model || it.type != "Z" } != false ||
        code.getOrNull(read + 3)?.opcode != Opcode.IF_EQZ || (code[read + 3] as OneRegisterInstruction).registerA != eligibility.registerA ||
        code.rowTarget(read + 3) != skip) return null
    val copy = (read + 4 until code.size).firstOrNull { index ->
        code[index].opcode in listOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
            (code[index] as TwoRegisterInstruction).registerB == register
    } ?: return null
    val row = (code[copy] as TwoRegisterInstruction).registerA
    if (row == register || code.getOrNull(copy + 1)?.opcode !in listOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) ||
        code.rowTarget(copy + 1) != skip) return null
    // Between the eligibility test and the copy, every check either drops the row or keeps it, and the
    // model's local is never written over.
    for (index in read + 4 until copy) {
        val target = code.rowTarget(index)
        if (code[index].isConditionalBranchInstruction && target != skip && target != copy) return null
        if (code[index].opcode.setsRegister() && (code[index] as? OneRegisterInstruction)?.registerA in setOf(register, row)) return null
    }
    // The row local arrives here null on every path, so a dropped row adds nothing.
    val sources = reachingWrites(method, code, row, read)
    if (sources.isEmpty() || sources.any { (code.getOrNull(it) as? NarrowLiteralInstruction)?.narrowLiteral != 0 || code[it].opcode.setsWideRegister() }) return null
    val add = code.indices.singleOrNull { index ->
        code[index].opcode == Opcode.INVOKE_VIRTUAL &&
            code[index].rowMethod()?.toString() == "Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z" &&
            code[index].rowArguments().lastOrNull() == row
    } ?: return null
    val insertionGuards = code.indices.filter { index ->
        index > copy + 1 && index < add && code[index].opcode == Opcode.IF_EQZ &&
            (code[index] as OneRegisterInstruction).registerA == row && code.rowTarget(index)?.let { it > add } == true
    }
    return read.takeIf { insertionGuards.size == 1 }
}

private fun optionalInboxRead(method: Method, model: String): Int? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z" ||
        method.parameterTypes.size != 4 || method.parameterTypes.any { !it.startsWith("L") }) return null
    val code = method.rowCode()
    val reads = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && code[it].rowField()?.type == model }
    val read = reads.singleOrNull() ?: return null
    if (code.indices.any { index -> code.rowTarget(index)?.let { it in read + 1..read + 4 } == true }) return null
    val register = (code[read] as OneRegisterInstruction).registerA
    val skip = code.rowTarget(read + 1) ?: return null
    val nullMove = code.getOrNull(skip) as? TwoRegisterInstruction ?: return null
    val eligibility = code.getOrNull(read + 2) as? TwoRegisterInstruction ?: return null
    if (code.getOrNull(read + 1)?.opcode != Opcode.IF_EQZ || (code[read + 1] as OneRegisterInstruction).registerA != register ||
        code.getOrNull(read + 2)?.opcode != Opcode.IGET_BOOLEAN || eligibility.registerB != register ||
        code[read + 2].rowField()?.let { it.definingClass != model || it.type != "Z" } != false ||
        code.getOrNull(read + 3)?.opcode != Opcode.IF_NEZ || (code[read + 3] as OneRegisterInstruction).registerA != eligibility.registerA ||
        code.rowTarget(read + 3) != skip + 1 || code.getOrNull(read + 4)?.opcode !in listOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) ||
        code.rowTarget(read + 4) != skip || code[skip].opcode !in listOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) ||
        nullMove.registerA != register) return null
    // Every write of the native null source that reaches this optional read is a literal 0. 452 sets the
    // register to 0 early and reuses it on branches that never get here, so text order says nothing.
    val sources = reachingWrites(method, code, nullMove.registerB, read)
    if (sources.isEmpty() || sources.any { (code.getOrNull(it) as? NarrowLiteralInstruction)?.narrowLiteral != 0 || code[it].opcode.setsWideRegister() }) return null
    val adds = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_VIRTUAL &&
            code[index].rowMethod()?.toString() == "Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z" &&
            code[index].rowArguments().lastOrNull() == register
    }
    val add = adds.singleOrNull() ?: return null
    val insertionGuards = code.indices.filter { index ->
        index > read + 4 && index < add && code[index].opcode == Opcode.IF_EQZ &&
            (code[index] as OneRegisterInstruction).registerA == register && code.rowTarget(index)?.let { it > add } == true
    }
    if (insertionGuards.size != 1) return null
    return read
}

private fun Method.rowCode(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.rowReference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
private fun Instruction.rowString(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.rowField(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.rowMethod(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.rowArguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun List<Instruction>.rowTarget(index: Int): Int? {
    val branch = getOrNull(index) as? OffsetInstruction ?: return null
    val address = take(index).sumOf { it.codeUnits } + branch.codeOffset
    var current = 0
    for (candidate in indices) {
        if (current == address) return candidate
        current += this[candidate].codeUnits
    }
    return null
}

private fun refuseInbox(detail: String): Nothing = throw PatchException("Hide Meta AI: $detail")

/**
 * The indexes of the instructions whose write of [register] reaches [at] on some path from the method's
 * start, over every branch and handler. An empty set is returned when the register can arrive unwritten.
 */
private fun reachingWrites(method: Method, code: List<Instruction>, register: Int, at: Int): Set<Int> {
    val flow = ControlFlow.of(method)
    val written = code.map { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register }
    val into = arrayOfNulls<MutableSet<Int>>(code.size)
    into[0] = mutableSetOf(-1)
    val pending = ArrayDeque(listOf(0))
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        val state = into[index]!!
        val out: Set<Int> = if (written[index]) setOf(index) else state
        for (next in flow.normal[index] + flow.exceptional[index]) {
            val target = into[next] ?: mutableSetOf<Int>().also { into[next] = it }
            if (target.addAll(out)) pending.addLast(next)
        }
    }
    val reaching = into[at] ?: return emptySet()
    return if (-1 in reaching) emptySet() else reaching
}
