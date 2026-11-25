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
import app.morphe.patches.instagram.misc.comment.code
import app.morphe.patches.instagram.misc.comment.discovering
import app.morphe.patches.instagram.misc.comment.field
import app.morphe.patches.instagram.misc.comment.one
import app.morphe.patches.instagram.misc.comment.reference
import app.morphe.patches.instagram.misc.comment.refuse
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.share.BUTTON_ROLE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val ABOUT_PATCH = "Hide comments"

/** The SettingsStatus flag the (i) part turns on when it goes in. */
internal const val COMMENT_ABOUT_BUTTON_STATUS = "commentAboutButton"

/** Handed what the row above a sheet's comments explains behind its (i), right after the row reads it: answers it, or null. */
internal const val COMMENT_ABOUT_BUTTON_DISCLOSURE =
    "$EXTENSION_PACKAGE/feed/CommentAboutButton;->disclosure(Ljava/lang/Object;)Ljava/lang/Object;"

/** The comment sheet's view model, a name Redex keeps. */
internal const val COMMENT_LIST_VIEW_MODEL = "Lcom/instagram/comments/mvvm/viewmodel/CommentListViewModel;"

/** What Meta's Kotlin data classes extend, a name Redex keeps. */
internal const val DATA_CLASS_SUPER = "Lcom/facebook/kotlin/compilerplugins/dataclassgenerate/superclass/DataClassSuper;"

private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * The row component that draws the (i), its read of the explanation, and the register it goes into.
 * Plain text and numbers only, like [CommentAiLabelPlan].
 */
internal class CommentAboutPlan(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val readAt: Int,
    val field: String,
    val explanation: String,
    val register: Int,
)

/**
 * The types of what a comment sheet's (i) explains: a data class of two strings, its title and its
 * text, that [COMMENT_LIST_VIEW_MODEL] makes and answers from a method of its own (457's A06, making
 * 0RG7). Empty when the view model is missing or makes no such thing.
 */
internal fun BytecodePatchContext.commentExplanationTypes(): Set<String> {
    val model = classDefByOrNull(COMMENT_LIST_VIEW_MODEL) ?: return emptySet()
    return model.methods.mapNotNullTo(linkedSetOf()) { method ->
        val type = method.returnType
        val made = method.code().any { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as? TypeReference)?.type == type }
        if (!type.startsWith("L") || !made) return@mapNotNullTo null
        val explanation = classDefByOrNull(type) ?: return@mapNotNullTo null
        val fields = explanation.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
        type.takeIf { explanation.superclass == DATA_CLASS_SUPER && fields.size == 2 && fields.all { it.type == STRING } }
    }
}

/**
 * Where [method] reads an explanation of [types] from a field of its own class and tests it for
 * null, with nothing in between but loads into other registers (457's 0TQj.A0p: the iget, the
 * button role string, the if-eqz past the (i)).
 */
private fun explanationReadsIn(method: Method, types: Set<String>): List<Int> {
    val code = method.code()
    return code.indices.filter { at ->
        if (code[at].opcode != Opcode.IGET_OBJECT) return@filter false
        val field = code[at].field() ?: return@filter false
        if (field.definingClass != method.definingClass || field.type !in types) return@filter false
        val register = (code[at] as TwoRegisterInstruction).registerA
        val test = (at + 1..minOf(at + 3, code.lastIndex)).firstOrNull { code[it].opcode == Opcode.IF_EQZ } ?: return@filter false
        (code[test] as OneRegisterInstruction).registerA == register &&
            (at + 1 until test).none { between ->
                code[between].opcode.setsRegister() && (code[between] as? OneRegisterInstruction)?.registerA == register
            }
    }
}

/**
 * Finds the row above a comment sheet's comments that draws the (i): the one method in the app
 * loading [BUTTON_ROLE] that reads an explanation of a [commentExplanationTypes] type from its own
 * field and tests it, once. The read's next instruction mustn't be a jump target, since the hook
 * goes in right there. Every refusal happens here, before anything changes.
 */
internal fun BytecodePatchContext.findCommentAbout(): CommentAboutPlan = discovering(ABOUT_PATCH) {
    val types = commentExplanationTypes()
    if (types.isEmpty()) refuse("$COMMENT_LIST_VIEW_MODEL makes no data class of two strings for its (i)")
    val (row, readAt) = classesLoadingString(BUTTON_ROLE).flatMap { it.methods }
        .filter { loadsString(it, BUTTON_ROLE) }
        .mapNotNull { method -> explanationReadsIn(method, types).singleOrNull()?.let { method to it } }
        .one("row loading \"$BUTTON_ROLE\" that reads and tests the comment sheet's (i) explanation once")
    if (readAt + 1 in row.jumpTargets()) refuse("${row.text()} jumps to right after its read of the (i) explanation")
    val read = row.code()[readAt]
    val field = read.field()!!
    CommentAboutPlan(
        row.definingClass, row.name, row.parameterTypes.map(Any::toString), row.returnType,
        readAt, field.toString(), field.type, (read as TwoRegisterInstruction).registerA,
    )
}

/** [findCommentAbout], or null with a warning, so Hide comments goes in leaving the (i) alone. */
internal fun BytecodePatchContext.findCommentAboutOrWarn(): CommentAboutPlan? = try {
    findCommentAbout()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $ABOUT_PATCH goes in leaving the (i) on comment sheets alone.")
    null
}

/**
 * Hands the explanation to [COMMENT_ABOUT_BUTTON_DISCLOSURE] right after the row reads it and puts
 * the answer, cast back to the explanation's type, in the same register, so on a null the row's own
 * test skips the (i) and the rest of the row is built as before. Nothing jumps onto the hook (the
 * find makes sure).
 */
internal fun BytecodePatchContext.hideCommentAbout(plan: CommentAboutPlan) = discovering(ABOUT_PATCH) {
    val method = mutableClassDefBy(plan.type).methods.single {
        it.name == plan.name && it.returnType == plan.returnType && it.parameterTypes.map(Any::toString) == plan.parameters
    }
    val read = method.getInstruction(plan.readAt)
    if (read.opcode != Opcode.IGET_OBJECT || read.field().toString() != plan.field ||
        (read as TwoRegisterInstruction).registerA != plan.register
    ) refuse("${method.text()} changed before its (i) could be hooked")
    method.addInstructions(
        plan.readAt + 1,
        """
            invoke-static/range { v${plan.register} .. v${plan.register} }, $COMMENT_ABOUT_BUTTON_DISCLOSURE
            move-result-object v${plan.register}
            check-cast v${plan.register}, ${plan.explanation}
        """,
    )
}
