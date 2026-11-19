/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.comments

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.comment.arguments
import app.morphe.patches.instagram.misc.comment.call
import app.morphe.patches.instagram.misc.comment.code
import app.morphe.patches.instagram.misc.comment.discovering
import app.morphe.patches.instagram.misc.comment.parameters
import app.morphe.patches.instagram.misc.comment.reference
import app.morphe.patches.instagram.misc.comment.refuse
import app.morphe.patches.instagram.misc.comment.writes
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val LIKES_PATCH = "Hide comments"

/** The SettingsStatus flag the likes part turns on when it goes in. */
internal const val COMMENT_LIKES_STATUS = "commentLikes"

/** Asked right before a comment row adds its heart and like count: true skips the add. */
internal const val COMMENT_LIKES_HIDDEN = "$EXTENSION_PACKAGE/feed/CommentLikes;->hidden()Z"

/** The test tag on the heart beside a comment. */
internal const val COMMENT_LIKE_TAG = "row_comment_like_button"

/** The test tag on the like count under that heart, loaded by the same component's code. */
internal const val COMMENT_LIKE_COUNT_TAG = "row_comment_textview_like_count"

private fun MethodReference.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private val ADDS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE)

/**
 * The method that builds a comment row's right-hand column, and the one add in it that puts the
 * heart and like count into that column, with a register a check in front of the add may borrow.
 * Plain text and numbers only, like [CommentRowReplyPlan].
 */
internal class CommentRowLikesPlan(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val component: String,
    val addAt: Int,
    val add: String,
    val free: Int,
)

/**
 * Where [method] adds a new [component] to a list, or null when it doesn't: one new-instance of it,
 * then straight code that fills its fields without writing its register again or branching, up to
 * a collection add that takes it as its one argument and whose answer nothing reads.
 */
private fun likesAddAt(method: Method, component: String): Int? {
    val code = method.code()
    val made = code.indices.filter { code[it].opcode == Opcode.NEW_INSTANCE && code[it].reference()?.toString() == component }
    val at = made.singleOrNull() ?: return null
    val register = (code[at] as OneRegisterInstruction).registerA
    val targets = method.jumpTargets()
    for (index in at + 1 until code.size) {
        val instruction = code[index]
        if (index in targets || instruction is OffsetInstruction || instruction.writes(register)) return null
        val call = instruction.call() ?: continue
        val arguments = instruction.arguments()
        if (register !in arguments) continue
        if (call.name == "<init>" && arguments.first() == register) continue
        val add = instruction.opcode in ADDS && call.name == "add" && call.returnType == "Z" &&
            call.parameters() == listOf("Ljava/lang/Object;") && arguments.getOrNull(1) == register
        val unread = code.getOrNull(index + 1)?.opcode != Opcode.MOVE_RESULT
        return index.takeIf { add && unread && index + 1 < code.size }
    }
    return null
}

/**
 * Finds every place a comment row puts a heart and like count into its column. 456 draws them with
 * two components, each a class with a method loading both [COMMENT_LIKE_TAG] and
 * [COMMENT_LIKE_COUNT_TAG]: one the row's own text column adds under its Reply line, and one the
 * row's container, and a second row, add on the right. Every method making one of them has to add
 * it the one way [likesAddAt] reads, so the switch takes every heart off or goes in for none. Every
 * refusal happens here, before anything changes.
 */
internal fun BytecodePatchContext.findCommentRowLikes(): List<CommentRowLikesPlan> = discovering(LIKES_PATCH) {
    // A static pool of shared strings answers both too, and it answers them as strings.
    val components = classesLoadingString(COMMENT_LIKE_TAG)
        .filter { type ->
            type.methods.any {
                it.returnType != "Ljava/lang/String;" && loadsString(it, COMMENT_LIKE_TAG) && loadsString(it, COMMENT_LIKE_COUNT_TAG)
            }
        }
        .map { it.type }.distinct()
    if (components.isEmpty()) refuse("expected a component loading both \"$COMMENT_LIKE_TAG\" and \"$COMMENT_LIKE_COUNT_TAG\", found 0")
    components.flatMap { component ->
        val makers = classesCreating(component).flatMap { it.methods }.filter { method ->
            method.code().any { it.opcode == Opcode.NEW_INSTANCE && it.reference()?.toString() == component }
        }
        if (makers.isEmpty()) refuse("nothing makes $component, the heart loading \"$COMMENT_LIKE_TAG\": found 0")
        makers.map { row ->
            val addAt = likesAddAt(row, component)
                ?: refuse("${row.text()} makes $component, the heart loading \"$COMMENT_LIKE_TAG\", without one plain add to its column")
            val code = row.code()
            val free = row.freeLocalsAt("$LIKES_PATCH: like add in ${row.text()}", addAt, 1, listOf(addAt + 1), 255, code[addAt].arguments()).single()
            CommentRowLikesPlan(
                row.definingClass, row.name, row.parameterTypes.map(Any::toString), row.returnType,
                component, addAt, code[addAt].call()!!.text(), free,
            )
        }
    }
}

/** [findCommentRowLikes], or null with a warning, so Hide comments goes in leaving the hearts on comments alone. */
internal fun BytecodePatchContext.findCommentRowLikesOrWarn(): List<CommentRowLikesPlan>? = try {
    findCommentRowLikes()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $LIKES_PATCH goes in leaving the heart and like count on comments alone.")
    null
}

/**
 * Hooks every add [findCommentRowLikes] planned, the last one in a method first, so a hook going in
 * never moves an add still waiting for its own.
 */
internal fun BytecodePatchContext.hideCommentRowLikes(plans: List<CommentRowLikesPlan>) =
    plans.sortedByDescending { it.addAt }.forEach { hideCommentRowLikes(it) }

/**
 * Puts a [COMMENT_LIKES_HIDDEN] check in front of the add: on a yes the column goes on without the
 * heart and its count, and the row is built as before. Nothing jumps onto the add (the find makes
 * sure), so the check goes in on its own.
 */
internal fun BytecodePatchContext.hideCommentRowLikes(plan: CommentRowLikesPlan) = discovering(LIKES_PATCH) {
    val method = mutableClassDefBy(plan.type).methods.single {
        it.name == plan.name && it.returnType == plan.returnType && it.parameterTypes.map(Any::toString) == plan.parameters
    }
    if (method.getInstruction(plan.addAt).call()?.text() != plan.add) refuse("${method.text()} changed before the like add could be hooked")
    method.addInstructionsWithLabels(
        plan.addAt,
        """
            invoke-static { }, $COMMENT_LIKES_HIDDEN
            move-result v${plan.free}
            if-nez v${plan.free}, :skip
        """,
        ExternalLabel("skip", method.getInstruction(plan.addAt + 1)),
    )
}
