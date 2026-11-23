/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.headerline

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val HEADER_LINE_STATUS = "feedHeaderLine"
internal const val HEADER_LINE_KINDS = "$EXTENSION_PACKAGE/feed/HeaderLine;->kinds(Ljava/util/List;)Ljava/util/List;"

/** The message the feed post header's state builder loads when a post can't get a header. */
internal const val FEED_HEADER_BUILDER = "MediaHeaderInvalidUiState"

internal const val REEL_LINE = "$EXTENSION_PACKAGE/feed/HeaderLine;->reelLine(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

/** The analytics name of the Follow button, which the Reels viewer's author row builder loads. */
internal const val REEL_LINE_BUILDER = "clips_viewer_follow_button"

/** How the toString of the reel line's sliding, Sponsored and Paid partnership shapes opens on 456. */
internal const val CYCLIC_LINE = "Cyclic(cyclicSubtitleUiState="
internal const val SPONSORED_LINE = "Sponsored(sponsoredSubtitleUiState="
internal const val PAID_LINE = "PaidPartnership(paidPartnershipSubtitleUiState="

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
    val gates = code.indices.mapNotNull { at -> twoGateSize(code, at)?.let { at to it } }
    val (at, size) = gates.firstOrNull() ?: refuse("no size check against 2 in ${builder.definingClass}->${builder.name}")
    val list = (code[size] as Instruction35c).registerC
    val count = (code[size + 1] as OneRegisterInstruction).registerA
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

/**
 * At [at]: invoke-interface {vL} List.size(), move-result vS, if-lt vS, vT where vT was last set to 2,
 * and the index of the size call, or null. 456.1's armeabi-v7a build keeps the 2 in a high register
 * and moves it into vT between the move-result and the if-lt.
 */
private fun twoGateSize(code: List<Instruction>, at: Int): Int? {
    if (at < 2 || code[at].opcode != Opcode.IF_LT) return null
    val gate = code[at] as TwoRegisterInstruction
    var limit = gate.registerB
    var resultAt = at - 1
    val between = code[resultAt]
    if (between.opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16) && (between as TwoRegisterInstruction).registerA == limit) {
        limit = between.registerB
        resultAt--
    }
    if (resultAt < 1) return null
    val size = code[resultAt - 1]
    if (size.opcode != Opcode.INVOKE_INTERFACE) return null
    val called = size.reference() as? MethodReference ?: return null
    if (called.definingClass != LIST || called.name != "size" || (size as? Instruction35c)?.registerCount != 1) return null
    val result = code[resultAt]
    if (result.opcode != Opcode.MOVE_RESULT) return null
    if (gate.registerA != (result as OneRegisterInstruction).registerA || limit == gate.registerA) return null
    val set = (resultAt - 2 downTo 0).firstOrNull { back -> writes(code[back], limit) } ?: return null
    val two = code[set].opcode in setOf(Opcode.CONST_4, Opcode.CONST_16) && (code[set] as NarrowLiteralInstruction).narrowLiteral == 2
    return if (two) resultAt - 1 else null
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

/**
 * Where a reel's author row takes its line: [owner]'s constructor [constructor] gets the line in
 * v[line], with v[scratch] a local not yet set. [lineType] is the line's interface, [none] the static
 * field holding Instagram's empty line, and [kept] the Sponsored and Paid partnership line classes.
 */
internal class ReelLineSite(
    val owner: String,
    val constructor: String,
    val line: Int,
    val scratch: Int,
    val lineType: String,
    val none: String,
    val kept: List<String>,
)

/**
 * The Reels viewer's author row builder (the one method loading [REEL_LINE_BUILDER] that makes a
 * sliding line, the class whose toString opens with [CYCLIC_LINE]) works out the line under the
 * author: Instagram's empty line, one label, or a sliding line of turns. It then makes the row's
 * props with it, the one constructor it calls taking a line among more than two values. The site is
 * that constructor, first thing.
 */
internal fun BytecodePatchContext.findReelLine(): ReelLineSite {
    val sliding = lineShape(CYCLIC_LINE)
    val lineType = sliding.interfaces.singleOrNull() ?: refuse("the sliding reel line ${sliding.type} implements ${sliding.interfaces}")
    val kept = listOf(SPONSORED_LINE, PAID_LINE).map { opening ->
        lineShape(opening).also { if (lineType !in it.interfaces) refuse("${it.type}, \"$opening\", isn't a $lineType") }.type
    }
    val builder = classesLoadingString(REEL_LINE_BUILDER).flatMap { owner ->
        owner.methods.filter { method ->
            loadsString(method, REEL_LINE_BUILDER) &&
                method.code().any { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as TypeReference).type == sliding.type }
        }
    }.singleOrNull() ?: refuse("expected one reel author row builder loading $REEL_LINE_BUILDER and making a ${sliding.type}")
    val code = builder.code()
    val props = code.mapNotNull { instruction ->
        (instruction.reference() as? MethodReference)?.takeIf { called ->
            (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) && called.name == "<init>" &&
                called.parameterTypes.size > 2 && called.parameterTypes.count { it.toString() == lineType } == 1
        }
    }.distinctBy { "${it.definingClass}->${it.parameterTypes}" }.singleOrNull()
        ?: refuse("expected the builder ${builder.definingClass}->${builder.name} to make one row's props from a $lineType")
    val none = code.mapNotNull { instruction ->
        (instruction.reference() as? FieldReference)?.takeIf { field ->
            instruction.opcode == Opcode.SGET_OBJECT && field.type == field.definingClass &&
                classDefByOrNull(field.type)?.interfaces?.contains(lineType) == true
        }
    }.map { "${it.definingClass}->${it.name}:${it.type}" }.distinct().singleOrNull()
        ?: refuse("expected the builder to read one empty $lineType")
    val types = props.parameterTypes.map { it.toString() }
    val constructor = classDefByOrNull(props.definingClass)?.methods?.singleOrNull { method ->
        method.name == "<init>" && method.parameterTypes.map { it.toString() } == types
    } ?: refuse("${props.definingClass}'s constructor taking $types is gone")
    val registers = constructor.implementation?.registerCount ?: refuse("${props.definingClass}'s constructor has no code")
    fun size(type: String) = if (type == "J" || type == "D") 2 else 1
    val locals = registers - 1 - types.sumOf { size(it) }
    if (locals < 1) refuse("${props.definingClass}'s constructor has no local register to work in")
    val line = locals + 1 + types.take(types.indexOf(lineType)).sumOf { size(it) }
    if (line > 15) refuse("the line's register v$line needs a wide form")
    if (branchesOnto(constructor.code(), 0).isNotEmpty()) refuse("a branch lands on the first instruction of ${props.definingClass}'s constructor")
    return ReelLineSite(props.definingClass, "<init>(${types.joinToString("")})V", line, 0, lineType, none, kept)
}

/** The one class whose toString opens with [opening]: a reel line's shape. */
private fun BytecodePatchContext.lineShape(opening: String) = classesLoadingString(opening).filter { owner ->
    owner.methods.any { it.name == "toString" && loadsString(it, opening) }
}.singleOrNull() ?: refuse("expected one reel line class whose toString loads \"$opening\"")

/**
 * First thing in the reel author row's props constructor: a Sponsored or Paid partnership line goes
 * on as it is, and any other is handed to the extension with Instagram's empty line, its answer
 * taking the line's place.
 */
internal fun BytecodePatchContext.hideReelLine(site: ReelLineSite) {
    val method = mutableClassDefBy(site.owner).methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == site.constructor
    } ?: refuse("${site.owner}->${site.constructor} is gone")
    val line = "v${site.line}"
    val scratch = "v${site.scratch}"
    val passes = site.kept.joinToString("\n") { "instance-of $scratch, $line, $it\nif-nez $scratch, :line" }
    method.addInstructionsWithLabels(
        0,
        """
            $passes
            sget-object $scratch, ${site.none}
            invoke-static { $line, $scratch }, $REEL_LINE
            move-result-object $line
            check-cast $line, ${site.lineType}
        """.trimIndent(),
        ExternalLabel("line", method.getInstruction(0)),
    )
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/**
 * Takes the song, the place and the other labels off the line under the author of
 * feed posts and reels (#771), each with its own switch. See HeaderLine in the extension. Sponsored
 * and Paid partnership stay. In the default selection with both switches off.
 */
@Suppress("unused")
val hideHeaderLinePatch = bytecodePatch(
    name = "Hide the line under names",
    description = "Takes the song, the place and the other labels off the line under the " +
        "name on posts in your feed and on reels. Sponsored and Paid partnership stay, and `Keep the place` can " +
        "leave a post's place there on its own. Starts off. " +
        "Turn it on in HushGram settings > Feed and Reels.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(HEADER_LINE_STATUS)
        val feed = findHeaderLine()
        val reels = findReelLine()
        hideHeaderLine(feed)
        hideReelLine(reels)
        enableStatus(HEADER_LINE_STATUS)
    }
}
