/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val REEL_LINES_PATCH = "Expand captions"

/** The question the reel caption's line count goes through: Instagram's count in, the one to use out. */
internal const val CAPTION_REEL_LINES = "$CAPTION_LENGTH->reelLines(I)I"

/** The question a reel caption render's open flag goes through: Instagram's flag in, whether to draw the caption open out. */
internal const val CAPTION_REEL_OPEN = "$CAPTION_LENGTH->reelCaptionOpen(I)Z"

private const val BOOLEAN_TO_STRING = "Ljava/lang/String;->valueOf(Z)Ljava/lang/String;"

/**
 * Where a reel caption render settles its line count (#821): in [type]->[name], the instruction at [merge]
 * every path to the count reaches, with the count in register [lines].
 */
internal class ReelCaptionLines(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val merge: Int,
    val lines: Int,
) {
    /**
     * Hands the count to the extension where the paths meet, the branches into that instruction moved onto
     * the hook, and writes the answer back over it. The place is found again in the method as it is now,
     * since Hide captions writes its own question at the top of the same render.
     */
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single {
            it.name == name && it.returnType == returnType && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        val now = context.reelCaptionLines(method)
        method.addInstructionsAtControlFlowLabel(
            now.merge,
            """
                invoke-static/range { v${now.lines} .. v${now.lines} }, $CAPTION_REEL_LINES
                move-result v${now.lines}
            """,
        )
    }
}

/**
 * Finds where each reel caption render picks its line count (#821). Instagram 456 draws a reel's caption in
 * a declarative render that loads [REEL_CAPTION_EXPANDED] (LX/0aex->A0p and LX/0Ee1->A0p on 386214574), and
 * never through the feed caption builder [findCaptionTrim] hooks, so `Expand captions on reels` didn't reach
 * it. The render hands its caption-open flag, a boolean field, to String.valueOf right before that key. Later
 * it reads the same flag and, when the caption is open, jumps to a `const` of Integer.MAX_VALUE, its "no cut";
 * otherwise the count is one line or the props' own. That const either goes on to a goto or falls through, and
 * where it lands is where every path to the count meets, which is where the hook goes. Each render must
 * have exactly one such read; anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findReelCaptionLines(): List<ReelCaptionLines> = reelCaptionRenders().map { reelCaptionLines(it) }

/**
 * A reel caption render's class and its caption-open flag (#821), whose every read in the class's methods goes
 * through [CAPTION_REEL_OPEN].
 */
internal class ReelCaptionOpen(val type: String, val flag: String) {
    /**
     * Puts the question right after each read of [flag] in [type]'s methods, as they are now, its answer written
     * back over the flag's register. A branch onto the instruction after a read still lands there, past the hook,
     * as it never carried this read's value. Returns how many reads it hooked.
     */
    fun write(context: BytecodePatchContext): Int {
        var hooked = 0
        for (method in context.mutableClassDefBy(type).methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            for (at in code.indices.reversed()) {
                val read = code[at]
                if (read.opcode != Opcode.IGET_BOOLEAN || (read as ReferenceInstruction).reference.toString() != flag) continue
                val register = (read as OneRegisterInstruction).registerA
                method.addInstructions(
                    at + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $CAPTION_REEL_OPEN
                        move-result v$register
                    """,
                )
                hooked++
            }
        }
        if (hooked == 0) refuseLines("$type no longer reads $flag")
        return hooked
    }
}

/**
 * Finds the classes of the reel caption renders and their caption-open flag (#821). A longer line count
 * ([findReelCaptionLines]) left the caption with Instagram's "...", which opens the caption and comments sheet
 * on 456, since the render also reads the flag to pick the closed caption's truncation, its "more" text and
 * the box it sits in. The render's class reads the same flag in its helpers too (the date under an open caption,
 * the text with its "more"), so every read in the class goes through the question. One entry per class.
 */
internal fun BytecodePatchContext.findReelCaptionOpens(): List<ReelCaptionOpen> =
    reelCaptionRenders().map { ReelCaptionOpen(it.definingClass, reelCaptionFlag(it)) }.distinctBy { it.type }

/** The reel caption renders: each method loading [REEL_CAPTION_EXPANDED]. */
private fun BytecodePatchContext.reelCaptionRenders(): List<Method> {
    val renders = classesLoadingString(REEL_CAPTION_EXPANDED).flatMap { classDef ->
        classDef.methods.filter { method ->
            val code = method.implementation?.instructions?.toList() ?: return@filter false
            code.indices.any { stringLoadedAt(code, it) == REEL_CAPTION_EXPANDED }
        }
    }
    if (renders.isEmpty()) refuseLines("expected a render holding $REEL_CAPTION_EXPANDED, found 0")
    return renders
}

/** The render's caption-open flag: the boolean field it hands to String.valueOf right before [REEL_CAPTION_EXPANDED]. */
private fun BytecodePatchContext.reelCaptionFlag(method: Method): String = with(method) {
    val code = implementation!!.instructions.toList()
    val key = code.indices.first { stringLoadedAt(code, it) == REEL_CAPTION_EXPANDED }
    val flagRead = (maxOf(0, key - 6) until key).lastOrNull { at ->
        code[at].opcode == Opcode.IGET_BOOLEAN && code.getOrNull(at + 1)?.calls() == BOOLEAN_TO_STRING
    } ?: refuseLines("$definingClass->$name doesn't hand a boolean field to String.valueOf before $REEL_CAPTION_EXPANDED")
    (code[flagRead] as ReferenceInstruction).reference.toString()
}

private fun BytecodePatchContext.reelCaptionLines(method: Method): ReelCaptionLines = with(method) {
    val where = "$definingClass->$name"
    val code = implementation!!.instructions.toList()
    val flag = reelCaptionFlag(method)

    val addresses = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> addresses[index + 1] = addresses[index] + instruction.codeUnits }
    val atAddress = HashMap<Int, Int>().apply { code.indices.forEach { put(addresses[it], it) } }
    fun target(at: Int): Int? = atAddress[addresses[at] + (code[at] as OffsetInstruction).codeOffset]

    val sites = code.indices.mapNotNull { at ->
        val read = code[at]
        if (read.opcode != Opcode.IGET_BOOLEAN || (read as ReferenceInstruction).reference.toString() != flag) return@mapNotNull null
        val branch = code.getOrNull(at + 1) ?: return@mapNotNull null
        if (branch.opcode != Opcode.IF_NEZ || (branch as OneRegisterInstruction).registerA != (read as OneRegisterInstruction).registerA) {
            return@mapNotNull null
        }
        val whole = target(at + 1) ?: return@mapNotNull null
        val constant = code[whole]
        if (constant.opcode != Opcode.CONST || (constant as NarrowLiteralInstruction).narrowLiteral != Int.MAX_VALUE) return@mapNotNull null
        val next = code.getOrNull(whole + 1) ?: return@mapNotNull null
        val merge = if (next.opcode == Opcode.GOTO || next.opcode == Opcode.GOTO_16 || next.opcode == Opcode.GOTO_32) {
            target(whole + 1) ?: return@mapNotNull null
        } else {
            whole + 1
        }
        merge to (constant as OneRegisterInstruction).registerA
    }
    val (merge, lines) = sites.singleOrNull()
        ?: refuseLines("expected $where to open its caption at one line count, found ${sites.size}")
    if (merge == 0) refuseLines("$where settles its line count at its first instruction")
    ReelCaptionLines(definingClass, name, parameterTypes.map(CharSequence::toString), returnType, merge, lines)
}

private fun Instruction.calls(): String? =
    if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) ((this as ReferenceInstruction).reference as? MethodReference)?.toString()
    else null

private fun refuseLines(detail: String): Nothing = throw PatchException("$REEL_LINES_PATCH: the reel caption's lines: $detail")
