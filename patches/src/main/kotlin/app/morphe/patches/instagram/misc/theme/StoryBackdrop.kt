/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21ih
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** A trace label only the story viewer's item animator logs under, kept in every build. */
internal const val STORY_ANIMATOR_TAG = "ReelViewerAnimator.animateReveal"

private const val GET_COLOR = "Landroid/content/Context;->getColor(I)I"

private const val PATCH_NAME = "Pure black dark mode"

/** One getColor result the story animator stores as the color behind a story: the method and its move-result's index. */
internal class StoryBackdrop(val type: String, val method: Method, val index: Int)

private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Method.holds(string: String) = code().any { instruction ->
    (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((instruction as ReferenceInstruction).reference as StringReference).string == string
}

/** Whether [instruction] loads [PURE_BLACK] as an int. */
private fun isPureBlack(instruction: Instruction) =
    (instruction.opcode == Opcode.CONST_HIGH16 || instruction.opcode == Opcode.CONST) &&
        (instruction as WideLiteralInstruction).wideLiteral.toInt() == PURE_BLACK.toInt()

/**
 * The int fields of [animator] its own code fills with [PURE_BLACK]: a black load straight into an
 * iput of the same register.
 */
private fun blackFields(animator: ClassDef): Set<String> = animator.methods.flatMap { method ->
    method.code().zipWithNext().mapNotNull { (load, put) ->
        val field = put.field()
        if (isPureBlack(load) && put.opcode == Opcode.IPUT && field != null && field.definingClass == animator.type && field.type == "I" &&
            (put as TwoRegisterInstruction).registerA == (load as OneRegisterInstruction).registerA
        ) field.name else null
    }
}.toSet()

/**
 * The story viewer's item animator, the one class holding [STORY_ANIMATOR_TAG], keeps the color
 * behind an opening story in an int field. It sets that field to #ff000000 in the dark theme, and
 * under a server flag to igds_prism_black from Context.getColor instead, so a story opens on
 * #0c1014 for a moment before the black (#360). Answers each getColor result stored into that
 * field. Fails when the animator isn't exactly one class, when its black field isn't exactly one,
 * or when no getColor reaches the field, since each is an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryBackdrops(): List<StoryBackdrop> {
    val animators = mutableListOf<ClassDef>()
    classDefForEach { classDef -> if (classDef.methods.any { it.holds(STORY_ANIMATOR_TAG) }) animators += classDef }
    val animator = animators.singleOrNull()
        ?: throw PatchException("$PATCH_NAME: expected one class logging $STORY_ANIMATOR_TAG, found ${animators.size}")
    val field = blackFields(animator).singleOrNull()
        ?: throw PatchException("$PATCH_NAME: expected one black backdrop field in ${animator.type}, found ${blackFields(animator)}")
    val found = animator.methods.flatMap { method ->
        method.code().windowed(3).withIndex().mapNotNull { (index, window) ->
            val (call, result, put) = window
            val target = ((call as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
            }
            val stored = put.field()
            if (call.opcode == Opcode.INVOKE_VIRTUAL && target == GET_COLOR && result.opcode == Opcode.MOVE_RESULT &&
                put.opcode == Opcode.IPUT && stored != null && stored.definingClass == animator.type && stored.name == field &&
                (put as TwoRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA
            ) StoryBackdrop(animator.type, method, index + 1) else null
        }
    }
    if (found.isEmpty()) throw PatchException("$PATCH_NAME: no getColor reaches ${animator.type}->$field")
    return found
}

/**
 * Loads [PURE_BLACK] where each of [backdrops]' getColor results was moved into its register. The
 * call stays and its result goes unread, so nothing else in the method moves.
 */
internal fun BytecodePatchContext.blackenStoryBackdrops(backdrops: List<StoryBackdrop>) {
    for (backdrop in backdrops) {
        val method = mutableClassDefBy(backdrop.type).methods.single {
            it.name == backdrop.method.name && it.parameters() == backdrop.method.parameters() &&
                it.returnType == backdrop.method.returnType
        }
        val register = (method.implementation!!.instructions[backdrop.index] as OneRegisterInstruction).registerA
        method.replaceInstruction(backdrop.index, BuilderInstruction21ih(Opcode.CONST_HIGH16, register, PURE_BLACK.toInt()))
    }
}
