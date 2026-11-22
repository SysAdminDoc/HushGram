/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.glass

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesReadingType
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Glass in chats"

internal const val GLASS_TOP_BAR = "Lapp/hushgram/extension/instagram/feed/GlassTopBar;"

/** The extension's hook on the bottom padding Instagram's helper sets on a view (#528). */
internal const val SET_BOTTOM_PADDING = "$GLASS_TOP_BAR->bottomPadding(Landroid/view/View;I)I"

/** The extension's hook on a chat's message list's bottom padding as Instagram's chat code reads it back (#528). */
internal const val READ_BOTTOM_PADDING = "$GLASS_TOP_BAR->ownBottomPadding(Landroid/view/View;I)I"

/** The view a chat's messages are listed in. */
internal const val MESSAGE_LIST = "Lcom/instagram/ui/recyclerview/CustomFadingEdgeRecyclerView;"

private val SETTER_PARAMETERS = listOf("Landroid/view/View;", "I")

/** The parameters of the static call a chat makes after padding its list, which scrolls the newest message back into view. */
private val KEEPER_PARAMETERS = listOf("Landroidx/recyclerview/widget/RecyclerView;", "I")

private const val SET_PADDING = "Landroid/view/View;->setPadding(IIII)V"
private const val GET_PADDING_BOTTOM = "Landroid/view/View;->getPaddingBottom()I"
private val KEPT_SIDES = setOf(
    "Landroid/view/View;->getPaddingLeft()I",
    "Landroid/view/View;->getPaddingTop()I",
    "Landroid/view/View;->getPaddingRight()I",
)

/** Instagram's helper setting a view's bottom padding: the index of its setPadding call, and its view's and padding's registers. */
internal class PaddingSetter(val method: Method, val call: Int, val view: Int, val bottom: Int)

/** Where a chat reads its message list's bottom padding: the index of the move-result, the list's register and the result's. */
internal class PaddingRead(val method: Method, val result: Int, val list: Int, val value: Int)

internal class ChatPaddingSites(val setter: PaddingSetter, val read: PaddingRead)

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/**
 * Instagram pads a chat's message list at the bottom each time the message bar changes height, through a static
 * helper taking the view and the padding (07tl.A0j on 456's 386214534), called by the chat's padding code
 * (05LM.A0d, which reads the list's padding first) and by its animation (0Jmu.GGM). Both read the list from a
 * field and then call a static (RecyclerView, int) that scrolls the newest message back into view. Answers the
 * one helper they call, when it keeps the left, top and right padding and sets its parameter as the bottom,
 * and the one place they read the list's bottom padding. None, with a warning, when either isn't exactly one.
 * Refuses without the extension's hooks.
 */
internal fun BytecodePatchContext.findChatPadding(): ChatPaddingSites? {
    val callers = classesReadingType(MESSAGE_LIST).flatMap { it.methods }.filter { setterCalls(it).isNotEmpty() }
    val setters = callers.flatMap(::setterCalls).distinctBy { it.toString() }.mapNotNull { called ->
        classDefByOrNull(called.definingClass)?.methods?.firstOrNull {
            it.name == called.name && it.parameterTypes.map(Any::toString) == SETTER_PARAMETERS && it.returnType == "V"
        }?.let(::setter)
    }
    val reads = callers.mapNotNull(::read)
    val setter = setters.singleOrNull()
    val read = reads.singleOrNull()
    if (setter == null || read == null) {
        patchLog.warning(
            "$PATCH: found ${setters.size} helpers padding a chat's messages and ${reads.size} reads of that padding, " +
                "so the messages can still jump as the message bar changes height",
        )
        return null
    }
    for (hook in listOf(SET_BOTTOM_PADDING, READ_BOTTOM_PADDING)) {
        if (classDefByOrNull(GLASS_TOP_BAR)?.methods?.any {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
            } != true
        ) {
            refuse("the extension has no public static $hook")
        }
    }
    return ChatPaddingSites(setter, read)
}

/**
 * The bottom padding Instagram sets on a chat's message list goes through GlassTopBar.bottomPadding, which adds
 * the room the glass gives the messages under the message bar, and the chat's read of it goes through
 * GlassTopBar.ownBottomPadding, which takes that room off again (#528, #757). Instagram set the list's padding
 * back without it each time the message bar changed height, and scrolled the list by the difference to keep the
 * newest message in view, so the messages jumped by the room and back, and could stay behind the bar. Leaves
 * Instagram's padding as it is when there's no such helper.
 */
internal fun BytecodePatchContext.keepChatPadding() {
    val sites = findChatPadding() ?: return
    val setter = sites.setter
    mutable(setter.method).addInstructionsAtControlFlowLabel(
        setter.call,
        """
            invoke-static/range { v${setter.view} .. v${setter.bottom} }, $SET_BOTTOM_PADDING
            move-result v${setter.bottom}
        """,
    )
    val read = sites.read
    val call = if (read.list <= 15 && read.value <= 15) {
        "invoke-static { v${read.list}, v${read.value} }"
    } else {
        "invoke-static/range { v${read.list} .. v${read.value} }"
    }
    mutable(read.method).addInstructions(
        read.result + 1,
        """
            $call, $READ_BOTTOM_PADDING
            move-result v${read.value}
        """,
    )
}

private fun BytecodePatchContext.mutable(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
    it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
        it.returnType == method.returnType
}

/** The registers [code] reads a message list into from a field. */
private fun listRegisters(code: List<Instruction>): Set<Int> = code.filter {
    it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == MESSAGE_LIST
}.mapTo(HashSet()) { (it as OneRegisterInstruction).registerA }

/**
 * The static (View, int) calls [method] hands a message list it read from a field, when it also calls a static
 * (RecyclerView, int). Empty otherwise.
 */
private fun setterCalls(method: Method): List<MethodReference> {
    val code = method.code()
    val lists = listRegisters(code)
    if (lists.isEmpty() || code.none { it.callsStatic(KEEPER_PARAMETERS) }) return emptyList()
    return code.filter { it.callsStatic(SETTER_PARAMETERS) && (it as FiveRegisterInstruction).registerC in lists }
        .map { (it as ReferenceInstruction).reference as MethodReference }
}

private fun Instruction.callsStatic(parameters: List<String>): Boolean {
    if (opcode != Opcode.INVOKE_STATIC) return false
    val called = (this as ReferenceInstruction).reference as MethodReference
    return called.returnType == "V" && called.parameterTypes.map(Any::toString) == parameters
}

/**
 * [method] as a helper setting a view's bottom padding: static (View, int), reading the left, top and right
 * padding and calling setPadding once with its view and, untouched, its int as the bottom. Null otherwise.
 */
private fun setter(method: Method): PaddingSetter? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
        method.parameterTypes.map(Any::toString) != SETTER_PARAMETERS
    ) {
        return null
    }
    val registers = method.implementation?.registerCount ?: return null
    val view = registers - 2
    val bottom = registers - 1
    val code = method.code()
    if (!code.mapNotNullTo(HashSet()) { (it as? ReferenceInstruction)?.reference?.toString() }.containsAll(KEPT_SIDES)) return null
    val call = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == SET_PADDING }.singleOrNull()
        ?: return null
    val set = code[call] as? FiveRegisterInstruction ?: return null
    if (set.registerCount != 5 || set.registerC != view || set.registerG != bottom || (0 until call).any { code[it].writes(bottom) }) {
        return null
    }
    return PaddingSetter(method, call, view, bottom)
}

/**
 * [method]'s one read of a message list's bottom padding, with its move-result right after and nothing jumping
 * onto the instruction after that, into a register other than the list's. Registers above v15 only as a pair in
 * a row, for the range form. Null otherwise.
 */
private fun read(method: Method): PaddingRead? {
    val code = method.code()
    val lists = listRegisters(code)
    val at = code.indices.filter {
        code[it].opcode == Opcode.INVOKE_VIRTUAL && (code[it] as ReferenceInstruction).reference.toString() == GET_PADDING_BOTTOM &&
            (code[it] as FiveRegisterInstruction).registerC in lists
    }.singleOrNull() ?: return null
    val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction ?: return null
    val list = (code[at] as FiveRegisterInstruction).registerC
    val value = result.registerA
    if (value == list || at + 2 >= code.size || at + 2 in method.jumpTargets()) return null
    if ((list > 15 || value > 15) && value != list + 1) return null
    return PaddingRead(method, at + 1, list, value)
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

/** Whether this writes [register], or a wide value over it. */
private fun Instruction.writes(register: Int): Boolean {
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return (opcode.setsRegister() && written == register) || (opcode.setsWideRegister() && written + 1 == register)
}
