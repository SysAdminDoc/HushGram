/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Accent color"

/**
 * Instagram's color/igds_prism_secondary_button_background_filled, the fill its button styles give the
 * default secondary button, Edit profile's and Share profile's: 0x7f060357 in 455 and 0x7f060389 in
 * 456 (aapt2, the same in every build of a version; one method loads it in each). 454 had it at 0x7f060355,
 * which 455 gives a borderless button's label, so read it again each update.
 */
internal val SECONDARY_FILL_IDS = listOf(0x7f060357L, 0x7f060389L)

/** The extension's answer to the default secondary button's fill (#187). */
internal const val SECONDARY_BUTTON_FILL = "$ACCENT_THEME->secondaryButtonFill(Landroid/content/Context;I)I"

private const val CONTEXT = "Landroid/content/Context;"

/**
 * The method Instagram's button styles answer a secondary button's fill with, taking a context and the
 * button's style and answering a color resource's number, and the registers each of its two returns of
 * the default style's fill hands back: the filled color's own number, and the fill another method of
 * the class answers on a dark screen.
 */
internal class SecondaryFill(val method: Method, val context: Int, val returns: List<Int>)

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/**
 * The method loading one of [SECONDARY_FILL_IDS] straight into a return, with the index of that return
 * and of the return of its call to another method of its class taking the same context and style (#187).
 * Answers null with a warning when there's no one such method, its context register is written to or
 * sits past v15, and refuses without the extension's hook.
 */
internal fun BytecodePatchContext.findSecondaryFill(): SecondaryFill? {
    val found = SECONDARY_FILL_IDS.flatMap { id ->
        classesLoading(id).flatMap { it.methods }.mapNotNull { method -> method.fillReturns(id)?.let { method to it } }
    }
    val (method, returns) = found.singleOrNull() ?: run {
        patchLog.warning("$PATCH: found ${found.size} methods answering a secondary button's fill, so Edit profile and Share profile keep Instagram's gray")
        return null
    }
    if (classDefByOrNull(ACCENT_THEME)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SECONDARY_BUTTON_FILL &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $SECONDARY_BUTTON_FILL")
    }
    val context = method.localRegisterCount() + if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    val code = method.implementation!!.instructions.toList()
    val registers = returns.map { (code[it] as OneRegisterInstruction).registerA }
    when {
        code.any { it.writes(context) } -> patchLog.warning("$PATCH: ${method.definingClass}->${method.name} writes its context, so secondary buttons keep Instagram's gray")
        (registers + context).any { it > 15 } -> patchLog.warning("$PATCH: ${method.definingClass}->${method.name} holds its fill past v15, so secondary buttons keep Instagram's gray")
        else -> return SecondaryFill(method, context, returns)
    }
    return null
}

/**
 * Hands each fill [findSecondaryFill] found to AccentTheme.secondaryButtonFill with the context right
 * before it's returned, and returns the answer, so the default secondary button takes the shade the
 * theme gives the other secondary buttons once the backgrounds are in the accent color or Classic
 * colors are on (#187). Whatever jumps to one of those returns goes through the hook too, and returns
 * a fill as well. Leaves the buttons as they are when there's no such method.
 */
internal fun BytecodePatchContext.fillSecondaryButtons() {
    val fill = findSecondaryFill() ?: return
    val method = mutableClassDefBy(fill.method.definingClass).methods.single {
        it.name == fill.method.name && it.parameterTypes.map(Any::toString) == fill.method.parameterTypes.map(Any::toString) &&
            it.returnType == fill.method.returnType
    }
    for (at in fill.returns.sortedDescending()) {
        val register = (method.implementation!!.instructions.elementAt(at) as OneRegisterInstruction).registerA
        method.addInstructionsAtControlFlowLabel(
            at,
            """
                invoke-static { v${fill.context}, v$register }, $SECONDARY_BUTTON_FILL
                move-result v$register
            """,
        )
    }
}

/**
 * The returns of this method's fill, when it takes a context and a style, answers an int, loads [id]
 * straight into a return and returns what a static method of its own class answers for the same two:
 * the filled color's return first. Null otherwise.
 */
private fun Method.fillReturns(id: Long): List<Int>? {
    val parameters = parameterTypes.map(Any::toString)
    if (returnType != "I" || parameters.size != 2 || parameters[0] != CONTEXT || !parameters[1].startsWith("L")) return null
    val code = implementation?.instructions?.toList() ?: return null
    val filled = code.indices.singleOrNull { at ->
        val load = code[at]
        load is NarrowLiteralInstruction && load is OneRegisterInstruction && (load.narrowLiteral.toLong() and 0xffffffffL) == id &&
            code.getOrNull(at + 1).returns(load.registerA)
    } ?: return null
    val dark = code.indices.singleOrNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_STATIC && call != null && call.definingClass == definingClass && call.returnType == "I" &&
            call.parameterTypes.map(Any::toString) == parameters &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT &&
            code.getOrNull(at + 2).returns((code[at + 1] as OneRegisterInstruction).registerA)
    }
    return listOfNotNull(filled + 1, dark?.plus(2))
}

private fun Instruction?.returns(register: Int): Boolean =
    this != null && opcode == Opcode.RETURN && (this as OneRegisterInstruction).registerA == register

/** Whether this writes [register], or a wide value over it. */
private fun Instruction.writes(register: Int): Boolean {
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return (opcode.setsRegister() && written == register) || (opcode.setsWideRegister() && written + 1 == register)
}
