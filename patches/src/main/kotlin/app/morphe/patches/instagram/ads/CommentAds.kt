/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The key the comments sheet's settings keep their comment ads switch under when they go into a
 * Bundle. Instagram keeps it: it's the server's name for the flag.
 */
internal const val ENABLE_COMMENT_ADS = "enable_comment_ads"

internal const val COMMENT_ADS = "$ADS->commentAds(I)I"

/** The writes of a boolean into a Bundle, either spelling of it. */
private val PUT_BOOLEAN = setOf(
    "Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V",
    "Landroid/os/Bundle;->putBoolean(Ljava/lang/String;Z)V",
)

/** The comments sheet's comment ads flag: its field, and the class whose methods write it. */
internal class CommentAdsFlag(val field: String, val type: String)

private fun refuseCommentAds(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the field the comments sheet reads to know whether to fetch an ad and show it among the
 * comments (#415). Its settings object goes into a Bundle with the field put under
 * [ENABLE_COMMENT_ADS] (the key loaded, or asked of Redex's string pool), so the field is the one
 * read into the value of that `putBoolean`. Fails before anything changes when the writes name no
 * field or more than one, or the field's class never writes it.
 */
internal fun BytecodePatchContext.findCommentAds(): CommentAdsFlag {
    val fields = mutableSetOf<FieldReference>()
    classesLoadingString(ENABLE_COMMENT_ADS).forEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.indices.filter { stringLoadedAt(code, it) == ENABLE_COMMENT_ADS }.forEach { at ->
                val key = commentAdsKeyRegister(code, at) ?: return@forEach
                val call = (at + 1 until code.size).firstOrNull { code[it].commentAdsMethod() != null } ?: return@forEach
                if (code[call].commentAdsMethod().toString() !in PUT_BOOLEAN) return@forEach
                val put = code[call] as? FiveRegisterInstruction ?: return@forEach
                if (put.registerD != key) return@forEach
                val read = (call - 1 downTo maxOf(0, call - 8)).firstOrNull {
                    code[it].opcode == Opcode.IGET_BOOLEAN && (code[it] as TwoRegisterInstruction).registerA == put.registerE
                } ?: return@forEach
                fields += (code[read] as ReferenceInstruction).reference as FieldReference
            }
        }
    }
    val field = fields.singleOrNull()
        ?: refuseCommentAds("expected one field behind the comments sheet's $ENABLE_COMMENT_ADS, found ${fields.size}")
    val writes = writesOf(field).size
    if (writes == 0) refuseCommentAds("${field.definingClass} never writes ${field.name}, the comments sheet's comment ads flag")
    return CommentAdsFlag(field.toString(), field.definingClass)
}

/** [findCommentAds], or null with a warning, so the rest of Hide ads still goes in. */
internal fun BytecodePatchContext.findCommentAdsOrWarn(): CommentAdsFlag? = try {
    findCommentAds()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding the ad in the comments.")
    null
}

/**
 * Hands each value the flag's class writes into it to [COMMENT_ADS] first and writes the extension's
 * answer instead. A 0 means the comments sheet never fetches its ad, so none shows among the
 * comments. The hook takes the write's label, so a branch onto the write runs it too. Settings
 * restored from a Bundle are built through the same constructor, so they get the answer as well.
 */
internal fun BytecodePatchContext.hideCommentAds(flag: CommentAdsFlag) {
    val classDef = mutableClassDefBy(flag.type)
    classDef.methods.forEach { method ->
        val code = method.implementation?.instructions?.toList() ?: return@forEach
        code.indices.reversed().filter { code[it].writesCommentAds(flag.field) }.forEach { at ->
            val value = (code[at] as TwoRegisterInstruction).registerA
            method.addInstructionsAtControlFlowLabel(
                at,
                """
                    invoke-static/range { v$value .. v$value }, $COMMENT_ADS
                    move-result v$value
                """,
            )
        }
    }
}

private fun BytecodePatchContext.writesOf(field: FieldReference): List<Instruction> =
    classDefByOrNull(field.definingClass)?.methods?.flatMap { method ->
        method.implementation?.instructions?.filter { it.writesCommentAds(field.toString()) } ?: emptyList()
    } ?: emptyList()

private fun Instruction.writesCommentAds(field: String): Boolean =
    opcode == Opcode.IPUT_BOOLEAN && ((this as ReferenceInstruction).reference as FieldReference).toString() == field

/** The register holding the key loaded at [at]: a const-string's, or the move-result after a pool's answer. */
private fun commentAdsKeyRegister(code: List<Instruction>, at: Int): Int? = when (code[at].opcode) {
    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> (code[at] as OneRegisterInstruction).registerA
    else -> code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { (it as OneRegisterInstruction).registerA }
}

private fun Instruction.commentAdsMethod(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
