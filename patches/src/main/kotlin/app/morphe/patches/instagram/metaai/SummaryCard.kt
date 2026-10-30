/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val SUMMARY_CARD = "$EXTENSION_PACKAGE/metaai/MetaAi;->summaryCard(Landroid/view/View;)Landroid/view/View;"

/**
 * Instagram 452's layout_high_confidence_module and meta_ai_hcm_search_plugin_layout_v3: the two
 * kinds of search results row Meta AI's summary card is built in. The same ids in all three 452
 * builds (aapt2), unchanged from 451 (450 had 0x7f0e0b28 and 0x7f0e100e), so read them again each update.
 */
internal val SUMMARY_LAYOUTS = listOf(0x7f0e0b31, 0x7f0e1020)

private const val INFLATER = "Landroid/view/LayoutInflater;"
private const val GROUP = "Landroid/view/ViewGroup;"
private const val ROW_VIEW = "Landroid/view/View;"
private const val HOLDER_INIT = "<init>(Landroid/view/View;)V"

/**
 * Where the search results page makes the rows of Meta AI's summary card (#152). Each kind's maker
 * takes the page's (LayoutInflater, ViewGroup) and answers the row's holder: it loads one of
 * [SUMMARY_LAYOUTS], inflates it with the next call, which takes the layout and answers a View, and
 * hands that view, unchanged, to a holder's constructor. The site is the inflate's move-result.
 *
 * The rows stay in the page's list, which opens a tapped reel by its row's place in the list the
 * server sent, so the extension only folds them away. Fails unless each layout has one maker that
 * loads it once, and nothing jumps past the hook.
 */
internal fun BytecodePatchContext.findSummaryRows(): List<HookSite> = SUMMARY_LAYOUTS.map { layout ->
    val name = "0x${layout.toString(16)}"
    val makers = classesLoading(layout.toLong()).flatMap { owner ->
        owner.methods.filter { it.makesRow() && it.code().any { instruction -> instruction.literal() == layout } }.map { owner to it }
    }
    val (owner, method) = makers.singleOrNull() ?: refuseSummary("${makers.size} search results row makers inflate layout $name, not one")
    val where = "${owner.type}->${method.name}"
    val code = method.code()
    val loads = code.indices.filter { code[it].literal() == layout }
    val load = loads.singleOrNull() ?: refuseSummary("$where loads layout $name ${loads.size} times, not once")
    val layoutRegister = (code[load] as OneRegisterInstruction).registerA
    val inflate = (load + 1..code.lastIndex).firstOrNull { code[it].methodReference() != null }
    val inflated = inflate?.let { code[it].methodReference() }
    if (inflate == null || inflated?.returnType != ROW_VIEW || layoutRegister !in code[inflate].arguments() ||
        (load + 1 until inflate).any { code[it].writes(layoutRegister) }
    ) {
        refuseSummary("$where doesn't inflate layout $name with the call after it")
    }
    val result = inflate + 1
    if (code.getOrNull(result)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuseSummary("$where drops the row it inflates")
    val row = (code[result] as OneRegisterInstruction).registerA
    val handedOn = (result + 1..code.lastIndex).firstOrNull { at ->
        code[at].opcode == Opcode.INVOKE_DIRECT && code[at].methodReference()?.let { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" } == HOLDER_INIT &&
            code[at].arguments().getOrNull(1) == row
    }
    if (handedOn == null || (result + 1 until handedOn).any { code[it].writes(row) }) {
        refuseSummary("$where doesn't hand the row it inflates to a holder")
    }
    val flow = ControlFlow.of(method)
    val jumpedTo = code.indices.any { from ->
        (flow.normal[from].filter { it != from + 1 } + flow.exceptional[from]).contains(result + 1)
    }
    if (jumpedTo) refuseSummary("$where jumps past the row it inflates")
    HookSite(owner.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType, result, row)
}

/** Passes each summary row's view through MetaAi.summaryCard, right after it's inflated. */
internal fun BytecodePatchContext.foldSummaryRows(rows: List<HookSite>) = rows.forEach { passThrough(it, SUMMARY_CARD) }

private fun Method.makesRow(): Boolean =
    parameterTypes.map(CharSequence::toString) == listOf(INFLATER, GROUP) && returnType.startsWith("L") && returnType != ROW_VIEW

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.literal(): Int? = (this as? NarrowLiteralInstruction)?.takeIf { opcode.setsRegister() }?.narrowLiteral
private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val set = (this as? OneRegisterInstruction)?.registerA ?: return false
    return set == register || (opcode.setsWideRegister() && set + 1 == register)
}
private fun refuseSummary(detail: String): Nothing = throw PatchException("Hide Meta AI: $detail")
