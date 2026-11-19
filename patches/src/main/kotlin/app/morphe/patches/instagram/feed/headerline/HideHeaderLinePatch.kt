/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.headerline

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val HEADER_LINE_STATUS = "feedHeaderLine"
internal const val HEADER_LINE_KINDS = "$EXTENSION_PACKAGE/feed/HeaderLine;->kinds(Ljava/util/List;)Ljava/util/List;"

/** The message the feed post header's state builder loads when a post can't get a header. */
internal const val FEED_HEADER_BUILDER = "MediaHeaderInvalidUiState"

private const val LIST = "Ljava/util/List;"
private const val MEDIA = "Lcom/instagram/feed/media/Media;"

private fun refuse(detail: String): Nothing = throw PatchException("Hide the line under names: $detail")

/**
 * Where the header builder counts the line's label kinds: [at] is the if-lt that sends a list of
 * fewer than two kinds to the line that stays put, [list] the kinds list and [count] its size.
 */
internal class HeaderLineSite(val owner: String, val method: String, val at: Int, val list: Int, val count: Int)

/**
 * The feed post header's state builder (the one method taking a Media that loads its invalid state's
 * name) builds the list of label kinds for the line under the author, then asks its size and compares
 * it with 2: two or more build the sliding line, fewer the one that stays put or none. The first such
 * size check, right before the line's state is made from Media and that list, is the site.
 */
internal fun BytecodePatchContext.findHeaderLine(): HeaderLineSite {
    val builder = classesLoadingString(FEED_HEADER_BUILDER).flatMap { owner ->
        owner.methods.filter { loadsString(it, FEED_HEADER_BUILDER) && it.parameterTypes.any { type -> type.toString() == MEDIA } }
    }.singleOrNull() ?: refuse("expected one feed header builder")
    val code = builder.code()
    val gates = code.indices.filter { at -> isTwoGate(code, at) }
    val at = gates.firstOrNull() ?: refuse("no size check against 2 in ${builder.definingClass}->${builder.name}")
    val list = (code[at - 2] as Instruction35c).registerC
    val count = (code[at - 1] as OneRegisterInstruction).registerA
    if (list > 255 || count > 255) refuse("registers v$list and v$count need a wide form")
    val made = (at + 1 until minOf(code.size, at + 16)).firstOrNull { next ->
        val called = code[next].reference() as? MethodReference ?: return@firstOrNull false
        called.name == "<init>" && called.parameterTypes.map { it.toString() }.let { MEDIA in it && LIST in it }
    } ?: refuse("no state made from Media and the kinds list after the size check")
    if (!hands(code, at + 1, made, list)) refuse("the state made at $made doesn't take v$list")
    val branchedOnto = branchesOnto(code, at)
    if (branchedOnto.isNotEmpty()) refuse("$branchedOnto branch onto the size check")
    return HeaderLineSite(builder.definingClass, "${builder.name}(${builder.parameterTypes.joinToString("")})${builder.returnType}", at, list, count)
}

/** At [at]: invoke-interface {vL} List.size(), move-result vS, if-lt vS, vT where vT was last set to 2. */
private fun isTwoGate(code: List<Instruction>, at: Int): Boolean {
    if (at < 2 || code[at].opcode != Opcode.IF_LT) return false
    val size = code[at - 2]
    if (size.opcode != Opcode.INVOKE_INTERFACE) return false
    val called = size.reference() as? MethodReference ?: return false
    if (called.definingClass != LIST || called.name != "size" || (size as? Instruction35c)?.registerCount != 1) return false
    val result = code[at - 1]
    if (result.opcode != Opcode.MOVE_RESULT) return false
    val gate = code[at] as TwoRegisterInstruction
    if (gate.registerA != (result as OneRegisterInstruction).registerA) return false
    val limit = gate.registerB
    val set = (at - 3 downTo 0).firstOrNull { back -> writes(code[back], limit) } ?: return false
    return code[set].opcode in setOf(Opcode.CONST_4, Opcode.CONST_16) && (code[set] as NarrowLiteralInstruction).narrowLiteral == 2
}

private fun writes(instruction: Instruction, register: Int): Boolean =
    instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == register

/** The constructor call at [made] takes [list], directly or through a move-object into its range. */
private fun hands(code: List<Instruction>, from: Int, made: Int, list: Int): Boolean {
    val call = code[made]
    val passed = when (call) {
        is Instruction35c -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
        is Instruction3rc -> (call.startRegister until call.startRegister + call.registerCount).toList()
        else -> return false
    }
    if (list in passed) return true
    return (from until made).any { at ->
        val move = code[at]
        move.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
            (move as TwoRegisterInstruction).registerB == list && move.registerA in passed
    }
}

/** The indexes of the branches (gotos, ifs) that land on the instruction at [target]. */
internal fun branchesOnto(code: List<Instruction>, target: Int): List<Int> {
    var unit = 0
    val units = code.map { instruction -> unit.also { unit += instruction.codeUnits } }
    return code.indices.filter { from ->
        val branch = code[from] as? OffsetInstruction ?: return@filter false
        units[from] + branch.codeOffset == units[target]
    }
}

/** Hands the kinds list to the extension right before Instagram compares its size with 2, then counts it again. */
internal fun BytecodePatchContext.hideHeaderLine(site: HeaderLineSite) {
    val method = mutableClassDefBy(site.owner).methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == site.method
    } ?: refuse("${site.owner}->${site.method} is gone")
    method.addInstructions(site.at, """
        invoke-static/range { v${site.list} .. v${site.list} }, $HEADER_LINE_KINDS
        move-result-object v${site.list}
        invoke-interface/range { v${site.list} .. v${site.list} }, Ljava/util/List;->size()I
        move-result v${site.count}
    """.trimIndent())
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/**
 * Takes the song, the place and the other labels off the line under the author of
 * feed posts (#771). See HeaderLine in the extension. Sponsored and Paid partnership stay. In the
 * default selection with its switch off.
 */
@Suppress("unused")
val hideHeaderLinePatch = bytecodePatch(
    name = "Hide the line under names",
    description = "Takes the song, the place and the other labels off the line under the " +
        "name on posts in your feed. Sponsored and Paid partnership stay. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(HEADER_LINE_STATUS)
        hideHeaderLine(findHeaderLine())
        enableStatus(HEADER_LINE_STATUS)
    }
}
