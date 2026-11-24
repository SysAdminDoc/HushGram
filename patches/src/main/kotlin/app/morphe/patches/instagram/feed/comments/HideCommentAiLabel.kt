/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.comments

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.comment.STRING
import app.morphe.patches.instagram.misc.comment.arguments
import app.morphe.patches.instagram.misc.comment.call
import app.morphe.patches.instagram.misc.comment.code
import app.morphe.patches.instagram.misc.comment.discovering
import app.morphe.patches.instagram.misc.comment.field
import app.morphe.patches.instagram.misc.comment.one
import app.morphe.patches.instagram.misc.comment.parameters
import app.morphe.patches.instagram.misc.comment.refuse
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val AI_LABEL_PATCH = "Hide comments"

/** The SettingsStatus flag the AI label part turns on when it goes in. */
internal const val COMMENT_AI_LABEL_STATUS = "commentAiLabel"

/** Handed whether a comment's name line shows an AI label, right after the line reads it: answers whether to show it. */
internal const val COMMENT_AI_LABEL_SHOWN = "$EXTENSION_PACKAGE/feed/CommentAiLabel;->shown(I)Z"

/** The test tag on a comment's name, loaded by the component that builds the line with it. */
internal const val COMMENT_USERNAME_TAG = "row_comment_textview_username"

/** The test tag on the time after the name, loaded by the same component's code. */
internal const val COMMENT_TIME_TAG = "row_comment_textview_time_ago"

private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * The method that builds a comment's name line, and the read of its AI label flag in it, with the
 * register the flag goes into. Plain text and numbers only, like [CommentRowLikesPlan].
 */
internal class CommentAiLabelPlan(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val readAt: Int,
    val flag: String,
    val register: Int,
)

/**
 * Where [method] reads its AI label flag, or null when it doesn't the one way this reads it: an
 * iget-boolean of a flag, an if-eqz on it, an iget-object of the label from the same object, an
 * if-eqz on that to the same place, and, before that place, a static call handing the label alone
 * to a getter answering its text, the label's words (456.1's 04G4.A0p: A0Q, then A02 into
 * 038m.A03, with "AI creator" when the label has no words).
 */
private fun labelReadsIn(method: Method): List<Int> {
    val code = method.code()
    if (code.none { it.opcode == Opcode.IGET_BOOLEAN }) return emptyList()
    val flow = ControlFlow.of(method)
    // A branch's target comes first among where it goes on.
    fun target(at: Int): Int? = if (code.getOrNull(at)?.opcode == Opcode.IF_EQZ) flow.normal[at].firstOrNull() else null
    return code.indices.filter { at ->
        if (code[at].opcode != Opcode.IGET_BOOLEAN || at + 4 >= code.size) return@filter false
        val read = code[at] as TwoRegisterInstruction
        val test = code[at + 1] as? OneRegisterInstruction ?: return@filter false
        if (code[at + 1].opcode != Opcode.IF_EQZ || test.registerA != read.registerA) return@filter false
        if (code[at + 2].opcode != Opcode.IGET_OBJECT) return@filter false
        val label = code[at + 2] as TwoRegisterInstruction
        if (label.registerB != read.registerB) return@filter false
        val labelType = code[at + 2].field()?.type ?: return@filter false
        val nonNull = code[at + 3] as? OneRegisterInstruction ?: return@filter false
        if (code[at + 3].opcode != Opcode.IF_EQZ || nonNull.registerA != label.registerA) return@filter false
        val end = target(at + 1) ?: return@filter false
        if (target(at + 3) != end || end <= at + 4) return@filter false
        (at + 4 until end).any { index ->
            val call = code[index].call() ?: return@any false
            code[index].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) &&
                call.returnType == STRING && call.parameters() == listOf(labelType) &&
                code[index].arguments() == listOf(label.registerA)
        }
    }
}

/**
 * Finds the component that builds a comment's name line, by the one method in the app loading both
 * [COMMENT_USERNAME_TAG] and [COMMENT_TIME_TAG] that reads an AI label the way [labelReadsIn] does,
 * once. The read's next instruction mustn't be a jump target, since the hook goes in right there.
 * Every refusal happens here, before anything changes.
 */
internal fun BytecodePatchContext.findCommentAiLabel(): CommentAiLabelPlan = discovering(AI_LABEL_PATCH) {
    val (line, readAt) = classesLoadingString(COMMENT_USERNAME_TAG).flatMap { it.methods }
        .filter { loadsString(it, COMMENT_USERNAME_TAG) && loadsString(it, COMMENT_TIME_TAG) }
        .mapNotNull { method -> labelReadsIn(method).singleOrNull()?.let { method to it } }
        .one("comment name line loading \"$COMMENT_USERNAME_TAG\" and \"$COMMENT_TIME_TAG\" that reads an AI label once")
    if (readAt + 1 in line.jumpTargets()) refuse("${line.text()} jumps to the test of its AI label flag")
    val read = line.code()[readAt]
    CommentAiLabelPlan(
        line.definingClass, line.name, line.parameterTypes.map(Any::toString), line.returnType,
        readAt, read.field().toString(), (read as TwoRegisterInstruction).registerA,
    )
}

/** [findCommentAiLabel], or null with a warning, so Hide comments goes in leaving the AI label on comments alone. */
internal fun BytecodePatchContext.findCommentAiLabelOrWarn(): CommentAiLabelPlan? = try {
    findCommentAiLabel()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $AI_LABEL_PATCH goes in leaving the AI label on comments alone.")
    null
}

/**
 * Hands the flag to [COMMENT_AI_LABEL_SHOWN] right after the line reads it, and puts the answer back
 * in the flag's own register, so on a no the line's own test skips the dot and the label, and the
 * rest of the line is built as before. Nothing jumps onto the test (the find makes sure).
 */
internal fun BytecodePatchContext.hideCommentAiLabel(plan: CommentAiLabelPlan) = discovering(AI_LABEL_PATCH) {
    val method = mutableClassDefBy(plan.type).methods.single {
        it.name == plan.name && it.returnType == plan.returnType && it.parameterTypes.map(Any::toString) == plan.parameters
    }
    val read = method.getInstruction(plan.readAt)
    if (read.opcode != Opcode.IGET_BOOLEAN || read.field().toString() != plan.flag ||
        (read as TwoRegisterInstruction).registerA != plan.register
    ) refuse("${method.text()} changed before its AI label could be hooked")
    method.addInstructions(
        plan.readAt + 1,
        """
            invoke-static/range { v${plan.register} .. v${plan.register} }, $COMMENT_AI_LABEL_SHOWN
            move-result v${plan.register}
        """,
    )
}
