/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Accent color"

/** The extension's answer to the color the Messages folder chip paints its unread dot with (#671). */
internal const val SWAP_BADGE = "$ACCENT_THEME->composeBadge(J)J"

/** The trace name Compose gives the content of a Messages folder chip (Primary, Requests), which draws the unread dot. */
internal const val FOLDER_CHIP_CONTENT =
    "instagram.features.direct.inbox.feature.folderrowpill.ui.FolderPillChip.<anonymous> (FolderRowPillRow.kt:434)"

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** Where a folder chip's content paints its dot: in [method], the background call at [call], its color in the pair from [color]. */
internal class ChipDot(val method: Method, val call: Int, val color: Int)

/**
 * The unread dot on the Messages inbox's folder chips (#671). The chip is Compose, and its content
 * paints the dot as a background whose color comes from Instagram's Compose palette (`LX/05Ah;->A0j`
 * on 386014758, or Prism's token read), never from a theme attribute, so the badge style Badges in the
 * accent color lays on the theme can't reach it. The content is the method loading
 * [FOLDER_CHIP_CONTENT]; of its background calls (see [paletteBackgrounds]), the chip's own reads its
 * color from the content's own field and the dot's from a palette object's long field.
 * Answers null with a warning when there's not exactly one such call or something jumps onto it, and
 * refuses without the extension's hook.
 */
internal fun BytecodePatchContext.findChipDot(): ChipDot? {
    val contents = classesLoadingString(FOLDER_CHIP_CONTENT).flatMap { type ->
        type.methods.filter { loadsString(it, FOLDER_CHIP_CONTENT) }
    }
    val dots = contents.flatMap { method -> method.paletteBackgrounds().map { (call, color) -> ChipDot(method, call, color) } }
    if (dots.size != 1) {
        patchLog.warning("$PATCH: ${dots.size} folder chip dots found, not one, so the Messages chips keep Instagram's red dot")
        return null
    }
    if (classDefByOrNull(ACCENT_THEME)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SWAP_BADGE &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $SWAP_BADGE")
    }
    val dot = dots.single()
    if (dot.call in dot.method.jumpTargets()) {
        patchLog.warning("$PATCH: something jumps onto the folder chip's dot, so the Messages chips keep Instagram's red dot")
        return null
    }
    return dot
}

/**
 * Hands the color the folder chip's dot is about to be painted with to the extension right before
 * the background call, and paints with its answer instead (#671). Nothing jumps there, and both of
 * the palette's paths have joined by then. Leaves the chips as they are when there's no one dot.
 */
internal fun BytecodePatchContext.tintChipDot() {
    val dot = findChipDot() ?: return
    val method = mutableClassDefBy(dot.method.definingClass).methods.single {
        it.name == dot.method.name && it.parameterTypes.map(Any::toString) == dot.method.parameterTypes.map(Any::toString) &&
            it.returnType == dot.method.returnType
    }
    method.addInstructions(
        dot.call,
        """
            invoke-static/range { v${dot.color} .. v${dot.color + 1} }, $SWAP_BADGE
            move-result-wide v${dot.color}
        """,
    )
}

/**
 * This method's background calls painting a color read from a palette: each static
 * (Object, Object, long)Object, or (Object, long)Object as the armeabi-v7a build calls it, whose long
 * pair was last written, going back through the code, by an `iget-wide` of a long field of another
 * class. Answers the call's index and the pair's first register.
 */
internal fun Method.paletteBackgrounds(): List<Pair<Int, Int>> {
    val code = implementation?.instructions?.toList().orEmpty()
    return code.indices.mapNotNull { at ->
        val call = code[at]
        if (call.opcode != Opcode.INVOKE_STATIC) return@mapNotNull null
        val called = (call as ReferenceInstruction).reference as MethodReference
        val parameters = called.parameterTypes.map(Any::toString)
        if (parameters.size !in 2..3 || parameters.last() != "J" || !parameters.dropLast(1).all { it.startsWith("L") } ||
            !called.returnType.startsWith("L")
        ) {
            return@mapNotNull null
        }
        val passed = call as FiveRegisterInstruction
        if (passed.registerCount != parameters.size + 1) return@mapNotNull null
        val registers = listOf(passed.registerC, passed.registerD, passed.registerE, passed.registerF).take(passed.registerCount)
        val color = registers[registers.size - 2]
        if (registers.last() != color + 1) return@mapNotNull null
        val read = code.lastWriteOf(at, color) ?: return@mapNotNull null
        if (read.opcode != Opcode.IGET_WIDE || (read as OneRegisterInstruction).registerA != color) return@mapNotNull null
        val field = (read as ReferenceInstruction).reference as FieldReference
        if (field.type != "J" || field.definingClass == definingClass) return@mapNotNull null
        at to color
    }
}

/** The last instruction before [at] writing [register], as one register or half of a pair, or null for none. */
private fun List<Instruction>.lastWriteOf(at: Int, register: Int): Instruction? =
    subList(0, at).lastOrNull { instruction ->
        val written = (instruction as? OneRegisterInstruction)?.registerA
        instruction.opcode.setsRegister() && written != null &&
            (written == register || written == register + 1 || instruction.opcode.setsWideRegister() && written + 1 == register)
    }
