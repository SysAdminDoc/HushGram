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

/**
 * The keys the comments sheet's settings keep the type of the card shown above the comments under
 * (#857): an ad's in-app browser card ("iab_card"), and the shop page a post links to
 * ("product_iab_card"). Instagram's names, kept as the server sends them.
 */
internal const val AD_CARD_TYPE = "ad_card_type"
internal const val ORGANIC_CARD_TYPE = "organic_card_type"

internal const val COMMENT_CARD = "$ADS->commentCard(Ljava/lang/String;)Ljava/lang/String;"

/** The writes of a boolean into a Bundle, either spelling of it. */
private val PUT_BOOLEAN = setOf(
    "Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V",
    "Landroid/os/Bundle;->putBoolean(Ljava/lang/String;Z)V",
)

/** The writes of a String into a Bundle, either spelling of it. */
private val PUT_STRING = setOf(
    "Landroid/os/BaseBundle;->putString(Ljava/lang/String;Ljava/lang/String;)V",
    "Landroid/os/Bundle;->putString(Ljava/lang/String;Ljava/lang/String;)V",
)

/** The comments sheet's comment ads flag: its field, and the class whose methods write it. */
internal class CommentAdsFlag(val field: String, val type: String)

/** The comments sheet's two card types: their fields, [AD_CARD_TYPE]'s first, and the class whose methods write them. */
internal class CommentCards(val fields: List<String>, val type: String)

private fun refuseCommentAds(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the field the comments sheet reads to know whether to fetch an ad and show it among the
 * comments (#415). Its settings object goes into a Bundle with the field put under
 * [ENABLE_COMMENT_ADS] (the key loaded, or asked of Redex's string pool), so the field is the one
 * read into the value of that `putBoolean`. Fails before anything changes when the writes name no
 * field or more than one, or the field's class never writes it.
 */
internal fun BytecodePatchContext.findCommentAds(): CommentAdsFlag {
    val fields = fieldsPutUnder(ENABLE_COMMENT_ADS, PUT_BOOLEAN, Opcode.IGET_BOOLEAN)
    val field = fields.singleOrNull()
        ?: refuseCommentAds("expected one field behind the comments sheet's $ENABLE_COMMENT_ADS, found ${fields.size}")
    val writes = writesOf(field, Opcode.IPUT_BOOLEAN).size
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
        code.indices.reversed().filter { code[it].writes(flag.field, Opcode.IPUT_BOOLEAN) }.forEach { at ->
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

/**
 * Finds the two String fields the comments sheet's settings keep the type of the card above the
 * comments in (#857), the ones read into the values of the `putString` calls under [AD_CARD_TYPE]
 * and [ORGANIC_CARD_TYPE], as [findCommentAds] finds the flag. Instagram shows that card, an in-app
 * browser on an ad's page or the shop page a post links to, at the top of the comments, with the
 * comments under it. Fails before anything changes when a key has no field or more than one, the
 * two are kept in different classes, or a field is never written there.
 */
internal fun BytecodePatchContext.findCommentCards(): CommentCards {
    val fields = listOf(AD_CARD_TYPE, ORGANIC_CARD_TYPE).map { key ->
        val found = fieldsPutUnder(key, PUT_STRING, Opcode.IGET_OBJECT)
        found.singleOrNull() ?: refuseCommentAds("expected one field behind the comments sheet's $key, found ${found.size}")
    }
    val owners = fields.map { it.definingClass }.distinct()
    val type = owners.singleOrNull() ?: refuseCommentAds("the comments sheet's card types are kept in $owners")
    fields.forEach { field ->
        if (writesOf(field, Opcode.IPUT_OBJECT).isEmpty()) refuseCommentAds("$type never writes ${field.name}, a comments sheet card type")
    }
    return CommentCards(fields.map { it.toString() }, type)
}

/** [findCommentCards], or null with a warning, so the rest of Hide ads still goes in. */
internal fun BytecodePatchContext.findCommentCardsOrWarn(): CommentCards? = try {
    findCommentCards()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without taking the card off the top of the comments.")
    null
}

/**
 * Hands each card type the class writes to [COMMENT_CARD] first and writes the extension's answer
 * instead. A null type is what a sheet with no card has, so the comments open on their own, with
 * every reader's own check for none. Like [hideCommentAds], the hook takes the write's label.
 */
internal fun BytecodePatchContext.hideCommentCards(cards: CommentCards) {
    val classDef = mutableClassDefBy(cards.type)
    classDef.methods.forEach { method ->
        val code = method.implementation?.instructions?.toList() ?: return@forEach
        code.indices.reversed().filter { at -> cards.fields.any { code[at].writes(it, Opcode.IPUT_OBJECT) } }.forEach { at ->
            val value = (code[at] as TwoRegisterInstruction).registerA
            method.addInstructionsAtControlFlowLabel(
                at,
                """
                    invoke-static/range { v$value .. v$value }, $COMMENT_CARD
                    move-result-object v$value
                """,
            )
        }
    }
}

/**
 * The fields put into a Bundle under [key] by one of [puts]: each is the field a [read] puts in the
 * call's value register, within 8 instructions before the call, which takes the key's register.
 */
private fun BytecodePatchContext.fieldsPutUnder(key: String, puts: Set<String>, read: Opcode): Set<FieldReference> {
    val fields = mutableSetOf<FieldReference>()
    classesLoadingString(key).forEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.indices.filter { stringLoadedAt(code, it) == key }.forEach { at ->
                val keyRegister = commentAdsKeyRegister(code, at) ?: return@forEach
                val call = (at + 1 until code.size).firstOrNull { code[it].commentAdsMethod() != null } ?: return@forEach
                if (code[call].commentAdsMethod().toString() !in puts) return@forEach
                val put = code[call] as? FiveRegisterInstruction ?: return@forEach
                if (put.registerD != keyRegister) return@forEach
                val readAt = (call - 1 downTo maxOf(0, call - 8)).firstOrNull {
                    code[it].opcode == read && (code[it] as TwoRegisterInstruction).registerA == put.registerE
                } ?: return@forEach
                fields += (code[readAt] as ReferenceInstruction).reference as FieldReference
            }
        }
    }
    return fields
}

private fun BytecodePatchContext.writesOf(field: FieldReference, write: Opcode): List<Instruction> =
    classDefByOrNull(field.definingClass)?.methods?.flatMap { method ->
        method.implementation?.instructions?.filter { it.writes(field.toString(), write) } ?: emptyList()
    } ?: emptyList()

private fun Instruction.writes(field: String, write: Opcode): Boolean =
    opcode == write && ((this as ReferenceInstruction).reference as FieldReference).toString() == field

/** The register holding the key loaded at [at]: a const-string's, or the move-result after a pool's answer. */
private fun commentAdsKeyRegister(code: List<Instruction>, at: Int): Int? = when (code[at].opcode) {
    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> (code[at] as OneRegisterInstruction).registerA
    else -> code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { (it as OneRegisterInstruction).registerA }
}

private fun Instruction.commentAdsMethod(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
