/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.reels.cleanup.UFI_ID
import app.morphe.patches.instagram.reels.cleanup.loadsLiteral
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Accent color"

private const val COLOR_FILTER = "Landroid/graphics/ColorFilter;"

/** The extension's answer to the color a reel's button column reads for its counts (#187). */
internal const val REEL_BUTTON_COLOR = "$ACCENT_THEME->reelButtons(I)I"

/** The extension's answer to the color filter a button of the column is built with, none over the video (#187). */
internal const val REEL_BUTTON_FILTER = "$ACCENT_THEME->reelButtons($COLOR_FILTER)$COLOR_FILTER"

/**
 * What a reel's button column (the one class whose render loads [UFI_ID]) colors its buttons with:
 * [color], its one instance (scope)int, which resolves the theme's text color on media (or its text
 * and icon color in the column's look under the video) and answers that color, and [buttons], its
 * methods building a button (like, comment, repost, share, save and the rest) with a color filter,
 * which they make from [color]'s answer in that other look and leave out over the video (`LX/0FBY;->A00`
 * and its A0I to A0N builders and render on 386215122).
 */
internal class ReelButtonColors(val color: MutableMethod, val buttons: List<MutableMethod>)

/**
 * Finds [ReelButtonColors]. The color read is the one the column's one (scope)ColorFilter method asks.
 * Answers null with a warning when there isn't one such column, one such filter calling one such color
 * read, or a button built with a filter, so the buttons beside a reel keep Instagram's colors. Refuses
 * when the extension lacks either hook.
 */
internal fun BytecodePatchContext.findReelButtonColors(): ReelButtonColors? {
    fun skip(why: String): ReelButtonColors? {
        patchLog.warning("$PATCH: $why, so the buttons beside a reel keep Instagram's colors")
        return null
    }
    val columns = classesLoading(UFI_ID).filter { classDef -> classDef.methods.any { it.rendersColumn() } }
    val column = columns.singleOrNull() ?: return skip("${columns.size} classes render a reel's button column, not one")
    val filters = column.methods.filter { it.isColumnRead(COLOR_FILTER) }
    val filter = filters.singleOrNull() ?: return skip("${filters.size} color filters in ${column.type}, not one")
    val scope = filter.parameterTypes.single().toString()
    val asked = filter.code().mapNotNull { it.called() }
        .filter { it.definingClass == column.type && it.returnType == "I" && it.parameterTypes.map(Any::toString) == listOf(scope) }
        .distinctBy { it.name }
    val color = asked.singleOrNull()?.let { called -> column.methods.singleOrNull { it.name == called.name && it.isColumnRead("I") } }
        ?: return skip("${column.type}'s color filter reads ${asked.size} colors of its own, not one")
    if (color.code().none { it.opcode == Opcode.RETURN }) return skip("${color.where()} returns no color")
    val builders = column.methods.filter { method -> method.code().any { it.filterArgument() != null } }
    if (builders.isEmpty()) return skip("${column.type} builds no button with a color filter")
    for (hook in listOf(REEL_BUTTON_COLOR, REEL_BUTTON_FILTER)) {
        val present = classDefByOrNull(ACCENT_THEME)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } == true
        if (!present) throw PatchException("$PATCH: the extension has no public static $hook")
    }
    val owner = mutableClassDefBy(column.type)
    return ReelButtonColors(
        owner.methods.single { it.name == color.name && it.isColumnRead("I") },
        builders.map { builder -> owner.methods.single { it.name == builder.name && it.sameShape(builder) } },
    )
}

/**
 * Hands the color a reel's button column reads, at its return's own label, and the color filter each
 * of its buttons is built with, right before the constructor takes it and at that call's own label, to
 * the extension, which answers the picked color with Reel buttons in the accent color on (#187). Over
 * the video the column builds its buttons with no filter and never asks its filter method, so the
 * constructor's argument is the one place each button's filter passes. A filter register the call also
 * passes as another argument, an address under a try block, or a register past v255 is left with a
 * warning. The range form takes any register. Leaves the column as it is when [findReelButtonColors]
 * finds nothing.
 */
internal fun BytecodePatchContext.tintReelButtons() {
    val found = findReelButtonColors() ?: return
    val color = found.color
    val returns = color.code().withIndex().filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    for ((index, register) in returns.asReversed()) {
        color.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $REEL_BUTTON_COLOR
                move-result v$register
            """,
        )
    }
    for (builder in found.buttons) {
        val code = builder.code()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val guarded = builder.implementation?.tryBlocks.orEmpty().map { it.startCodeAddress until it.startCodeAddress + it.codeUnitCount }
        val sites = code.indices.mapNotNull { index -> code[index].filterArgument()?.let { index to it } }
        for ((index, register) in sites.asReversed()) {
            val why = when {
                register == SHARED -> "passes its color filter's register twice"
                guarded.any { addresses[index] in it } -> "builds a button under a try block"
                register > 255 -> "keeps a button's color filter in v$register"
                else -> null
            }
            if (why != null) {
                patchLog.warning("$PATCH: ${builder.where()} $why, so that button keeps Instagram's color")
                continue
            }
            builder.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $REEL_BUTTON_FILTER
                    move-result-object v$register
                """,
            )
        }
    }
}

/** What [filterArgument] answers for a constructor call passing its color filter's register twice. */
private const val SHARED = -1

/**
 * For a call to a constructor taking one color filter, the register that argument is passed in, or
 * [SHARED] when the call passes that register as another argument too; null for any other instruction.
 */
private fun Instruction.filterArgument(): Int? {
    if (opcode != Opcode.INVOKE_DIRECT && opcode != Opcode.INVOKE_DIRECT_RANGE) return null
    val called = called()?.takeIf { it.name == "<init>" } ?: return null
    val parameters = called.parameterTypes.map(Any::toString)
    if (parameters.count { it == COLOR_FILTER } != 1) return null
    var slot = 1
    for (parameter in parameters.subList(0, parameters.indexOf(COLOR_FILTER))) slot += if (parameter == "J" || parameter == "D") 2 else 1
    return when (this) {
        is RegisterRangeInstruction -> startRegister + slot
        is FiveRegisterInstruction -> {
            val registers = listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
            registers[slot].takeIf { register -> registers.count { it == register } == 1 } ?: SHARED
        }
        else -> null
    }
}

/** An instance method of one object parameter answering [returns], with a body. */
private fun Method.isColumnRead(returns: String): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && implementation != null && returnType == returns &&
        parameterTypes.size == 1 && parameterTypes.single().toString().startsWith("L")

/** The column's render: an instance method of one parameter answering an object, loading [UFI_ID]. */
private fun Method.rendersColumn(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.size == 1 && returnType.startsWith("L") &&
        loadsLiteral(UFI_ID)

private fun Method.sameShape(other: Method): Boolean =
    returnType == other.returnType && parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"
