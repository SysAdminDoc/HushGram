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
import app.morphe.patches.instagram.misc.comment.FUNCTION
import app.morphe.patches.instagram.misc.comment.arguments
import app.morphe.patches.instagram.misc.comment.call
import app.morphe.patches.instagram.misc.comment.code
import app.morphe.patches.instagram.misc.comment.discovering
import app.morphe.patches.instagram.misc.comment.one
import app.morphe.patches.instagram.misc.comment.parameters
import app.morphe.patches.instagram.misc.comment.refuse
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val REPLY_PATCH = "Hide comments"

/** The SettingsStatus flag the Reply part turns on when it goes in. */
internal const val COMMENT_REPLY_STATUS = "commentReply"

/** Asked right before a comment's line draws Reply: true skips drawing it. */
internal const val COMMENT_REPLY_HIDDEN = "$EXTENSION_PACKAGE/feed/CommentReply;->hidden()Z"

/**
 * The Compose test tag on the Reply text button under a comment, loaded only by the composable
 * that draws a comment's bottom line, CommentBottomOptions in the app's own traces.
 */
internal const val COMMENT_REPLY_TAG = "row_comment_textview_reply_button"

/** The test tag on the newer reply icon the same line draws instead, inside a composable of its own. */
internal const val COMMENT_THREADS_REPLY_TAG = "row_comment_threads_reply_button"

/** What a static call of the line is, by name, parameters and return type. */
private fun MethodReference.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.staticCall(): MethodReference? =
    call()?.takeIf { opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE }

/**
 * The comment line's composable and the two calls that draw Reply in it, with a register each hook
 * may borrow. Plain text and numbers only, so keeping it holds none of the app's code.
 */
internal class CommentReplyPlan(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val buttonAt: Int,
    val button: String,
    val buttonFree: Int,
    val iconAt: Int,
    val icon: String,
    val iconFree: Int,
)

/**
 * Where [method] draws the Reply text button, or null when it doesn't: [COMMENT_REPLY_TAG] goes
 * into one static call that answers a modifier, and the first call after it is the static,
 * void button call taking that modifier and an onClick lambda.
 */
private fun BytecodePatchContext.replyButtonAt(method: Method): Int? {
    val code = method.code()
    return code.indices.filter { stringLoadedAt(code, it) == COMMENT_REPLY_TAG }
        .mapNotNull { replyButtonAfter(code, it) }.distinct().singleOrNull()
}

private fun replyButtonAfter(code: List<Instruction>, load: Int): Int? {
    // A const-string holds the tag in its own register; a pool call hands it to the move-result after it.
    val tagAt = if (code[load].opcode == Opcode.CONST_STRING || code[load].opcode == Opcode.CONST_STRING_JUMBO) load else load + 1
    val tag = (code.getOrNull(tagAt) as? OneRegisterInstruction)?.registerA ?: return null
    if (tagAt != load && code[tagAt].opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val modifierAt = (tagAt + 1 until code.size).firstOrNull { code[it].call() != null } ?: return null
    val modifierCall = code[modifierAt].staticCall() ?: return null
    if (tag !in code[modifierAt].arguments() || !modifierCall.returnType.startsWith("L")) return null
    val modifierResult = code.getOrNull(modifierAt + 1)
    if (modifierResult?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val modifier = (modifierResult as OneRegisterInstruction).registerA
    val buttonAt = (modifierAt + 2 until code.size).firstOrNull { code[it].call() != null } ?: return null
    val button = code[buttonAt].staticCall() ?: return null
    val types = button.parameters()
    if (button.returnType != "V" || modifierCall.returnType !in types || FUNCTION !in types) return null
    if (modifier !in code[buttonAt].arguments()) return null
    return buttonAt
}

/**
 * Finds the composable that draws the line under a comment, by the one call in the app that draws
 * a button tagged [COMMENT_REPLY_TAG], and the call in it to the composable whose code loads
 * [COMMENT_THREADS_REPLY_TAG], the reply icon newer designs show there instead. Each call gets a
 * register its hook may borrow. Every refusal happens here, before anything changes.
 */
internal fun BytecodePatchContext.findCommentReply(): CommentReplyPlan = discovering(REPLY_PATCH) {
    val (line, buttonAt) = classesLoadingString(COMMENT_REPLY_TAG).flatMap { it.methods }
        .filter { loadsString(it, COMMENT_REPLY_TAG) }
        .mapNotNull { method -> replyButtonAt(method)?.let { method to it } }
        .one("composable drawing a button tagged \"$COMMENT_REPLY_TAG\"")
    val where = line.text()
    val code = line.code()

    val iconHolders = classesLoadingString(COMMENT_THREADS_REPLY_TAG).associateBy { it.type }
    val iconAt = code.indices.filter { at ->
        val call = code[at].staticCall() ?: return@filter false
        call.returnType == "V" && FUNCTION in call.parameters() &&
            iconHolders[call.definingClass]?.methods?.firstOrNull {
                it.name == call.name && it.returnType == call.returnType && it.parameterTypes.map(Any::toString) == call.parameters()
            }?.let { loadsString(it, COMMENT_THREADS_REPLY_TAG) } == true
    }.one("call in $where to the composable tagged \"$COMMENT_THREADS_REPLY_TAG\"")

    val targets = line.jumpTargets()
    fun borrow(at: Int, what: String): Int {
        if (at in targets) refuse("$where jumps to its $what")
        if (at + 1 >= code.size) refuse("$where ends at its $what")
        return line.freeLocalsAt("$REPLY_PATCH: $what in $where", at, 1, listOf(at + 1), 255, code[at].arguments()).single()
    }
    CommentReplyPlan(
        line.definingClass, line.name, line.parameterTypes.map(Any::toString), line.returnType,
        buttonAt, code[buttonAt].call()!!.text(), borrow(buttonAt, "Reply button"),
        iconAt, code[iconAt].call()!!.text(), borrow(iconAt, "reply icon"),
    )
}

/** [findCommentReply], or null with a warning, so Hide comments goes in leaving that line's Reply alone. */
internal fun BytecodePatchContext.findCommentReplyOrWarn(): CommentReplyPlan? = try {
    findCommentReply()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $REPLY_PATCH goes in leaving Reply on the composable comment line alone.")
    null
}

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/**
 * The comment row built as a component tree, the one a post's comments sheet draws on 454, and the
 * one call in it that adds Reply to the row, with a register its hook may borrow. Plain text and
 * numbers only, like [CommentReplyPlan].
 */
internal class CommentRowReplyPlan(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val addAt: Int,
    val add: String,
    val free: Int,
)

/**
 * Where [method] adds Reply to the row, or null when it doesn't. After [COMMENT_REPLY_TAG] a static
 * call answers the text button's component, its move-result goes into a goto, and the goto lands on
 * a void instance call taking that component alone. The newer reply icon reaches the same call:
 * the first call to that method after [COMMENT_THREADS_REPLY_TAG] is that very instruction.
 */
private fun BytecodePatchContext.replyAddAt(method: Method): Int? {
    val code = method.code()
    fun tagAt(tag: String) = code.indices.singleOrNull { stringLoadedAt(code, it) == tag }
    val text = tagAt(COMMENT_REPLY_TAG) ?: return null
    val icon = tagAt(COMMENT_THREADS_REPLY_TAG) ?: return null
    val flow = ControlFlow.of(method)
    fun addFrom(goto: Int): Int? {
        if (code[goto].opcode !in GOTOS || goto < 2) return null
        val result = code[goto - 1].takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction ?: return null
        if (code[goto - 2].staticCall()?.returnType?.startsWith("L") != true) return null
        val addAt = flow.normal[goto].singleOrNull() ?: return null
        val add = code[addAt].call()?.takeIf { code[addAt].opcode == Opcode.INVOKE_VIRTUAL } ?: return null
        val types = add.parameters()
        if (add.returnType != "V" || types.size != 1 || !types.single().startsWith("L")) return null
        if (code[addAt].arguments().getOrNull(1) != result.registerA) return null
        val iconAdd = (icon + 1 until code.size).firstOrNull { code[it].call()?.text() == add.text() }
        return addAt.takeIf { iconAdd == it }
    }
    return (text + 1 until code.size).firstNotNullOfOrNull { addFrom(it) }
}

/**
 * Finds the method that builds the comment row as a component tree, by the one method in the app
 * loading both reply tags whose Reply text button and reply icon go into one add, and a register
 * a check in front of that add may borrow. Every refusal happens here, before anything changes.
 */
internal fun BytecodePatchContext.findCommentRowReply(): CommentRowReplyPlan = discovering(REPLY_PATCH) {
    val (row, addAt) = classesLoadingString(COMMENT_REPLY_TAG).flatMap { it.methods }
        .filter { loadsString(it, COMMENT_REPLY_TAG) && loadsString(it, COMMENT_THREADS_REPLY_TAG) }
        .mapNotNull { method -> replyAddAt(method)?.let { method to it } }
        .one("comment row adding the Reply tagged \"$COMMENT_REPLY_TAG\" and \"$COMMENT_THREADS_REPLY_TAG\" in one call")
    val code = row.code()
    if (addAt + 1 >= code.size) refuse("${row.text()} ends at its Reply add")
    val free = row.freeLocalsAt("$REPLY_PATCH: Reply add in ${row.text()}", addAt, 1, listOf(addAt + 1), 255, code[addAt].arguments()).single()
    CommentRowReplyPlan(
        row.definingClass, row.name, row.parameterTypes.map(Any::toString), row.returnType,
        addAt, code[addAt].call()!!.text(), free,
    )
}

/** [findCommentRowReply], or null with a warning, so Hide comments goes in leaving the row's Reply alone. */
internal fun BytecodePatchContext.findCommentRowReplyOrWarn(): CommentRowReplyPlan? = try {
    findCommentRowReply()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $REPLY_PATCH goes in leaving Reply on the comment row alone.")
    null
}

/**
 * Puts a [COMMENT_REPLY_HIDDEN] check in front of the row's Reply add, on the add's own label so the
 * text button's goto lands on the check too: on a yes the row goes on without Reply, and its share
 * button, like count and the rest are added as before.
 */
internal fun BytecodePatchContext.hideCommentRowReply(plan: CommentRowReplyPlan) = discovering(REPLY_PATCH) {
    val method = mutableClassDefBy(plan.type).methods.single {
        it.name == plan.name && it.returnType == plan.returnType && it.parameterTypes.map(Any::toString) == plan.parameters
    }
    if (method.getInstruction(plan.addAt).call()?.text() != plan.add) refuse("${method.text()} changed before Reply could be hooked")
    method.addInstructionsAtControlFlowLabel(
        plan.addAt,
        """
            invoke-static { }, $COMMENT_REPLY_HIDDEN
            move-result v${plan.free}
            if-nez v${plan.free}, :skip
        """,
        ExternalLabel("skip", method.getInstruction(plan.addAt + 1)),
    )
}

/**
 * Puts a [COMMENT_REPLY_HIDDEN] check in front of both calls, highest index first so the plan's
 * other index stays good: on a yes the line goes on past the call and doesn't draw Reply. Compose
 * then has one group fewer under the line, and the rest of the line, its like count and time
 * included, is drawn as before.
 */
internal fun BytecodePatchContext.hideCommentReply(plan: CommentReplyPlan) = discovering(REPLY_PATCH) {
    val method = mutableClassDefBy(plan.type).methods.single {
        it.name == plan.name && it.returnType == plan.returnType && it.parameterTypes.map(Any::toString) == plan.parameters
    }
    listOf(Triple(plan.buttonAt, plan.button, plan.buttonFree), Triple(plan.iconAt, plan.icon, plan.iconFree))
        .sortedByDescending { it.first }
        .forEach { (at, call, free) ->
            if (method.getInstruction(at).staticCall()?.text() != call) refuse("${method.text()} changed before Reply could be hooked")
            method.addInstructionsWithLabels(
                at,
                """
                    invoke-static { }, $COMMENT_REPLY_HIDDEN
                    move-result v$free
                    if-nez v$free, :skip
                """,
                ExternalLabel("skip", method.getInstruction(at + 1)),
            )
        }
}
