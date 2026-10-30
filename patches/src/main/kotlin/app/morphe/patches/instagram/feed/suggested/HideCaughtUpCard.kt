/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested posts"

internal const val CAUGHT_UP_CARD = "$EXTENSION_PACKAGE/feed/CaughtUpCard;"
internal const val CAUGHT_UP_BIND = "$CAUGHT_UP_CARD->bind(Landroid/view/View;Ljava/lang/Object;)V"

/** The extension's stub the patch fills to read an end of feed card's details. */
internal const val CAUGHT_UP_DETAILS_STUB = "details"

/** The kind of the card Instagram puts at the top of Home with no new posts, which marks the card's kind enum. */
internal const val TOP_OF_FEED = "TOP_OF_FEED"

private const val OBJECT = "Ljava/lang/Object;"
private const val ENUM = "Ljava/lang/Enum;"
private val BIND_VIEW = listOf("I", "Landroid/view/View;", OBJECT, OBJECT)

/**
 * Hide the caught up card (#307), found: the end of feed card's binder, the card's class and the
 * field of the card holding its details, the object whose one enum field is the card's kind.
 *
 * [write] calls CaughtUpCard.bind with the row and the card first thing in the binder, which folds
 * the row to no height while the switch is on, and fills the extension's details stub with the read.
 * The card stays in the feed, so the load more row's rule under it and the Older posts page still
 * work as Instagram has them.
 */
internal class HideCaughtUpCard(
    val binder: String,
    val cardType: String,
    val details: String,
) {
    /** Written after the caught up card's title hook, which checks the binder's code by index. */
    fun write(context: BytecodePatchContext) = with(context) {
        val bind = mutableClassDefBy(binder).methods.single { it.isBindView() }
        // The row and the card are p2 and p3, side by side, so a range call reaches them past v15 too.
        bind.addInstructionsAtControlFlowLabel(0, "invoke-static/range { p2 .. p3 }, $CAUGHT_UP_BIND")
        staticStub(CAUGHT_UP_CARD, CAUGHT_UP_DETAILS_STUB, OBJECT).addInstructions(
            0,
            """
                check-cast p0, $cardType
                iget-object p0, p0, $details
                return-object p0
            """,
        )
    }
}

/**
 * Finds the end of feed card for Hide the caught up card. The binder is the one
 * bindView(int, View, Object, Object) loading both [END_CARD_HOLDER] and [END_CARD_UNIT], the card's
 * class the first cast after [END_CARD_UNIT], and its details the one field of the card the binder
 * reads whose class has one enum field, an enum naming [TOP_OF_FEED]. On 452's 385811006 that's
 * LX/01pR's bindView, the card LX/02bG, and its details LX/02bG->A01, an LX/06SZ whose kind is
 * LX/06SZ->A02 of LX/06SL (V2, TOP_OF_FEED, TOF_HOMECOMING_ALL and the rest). The card's class and
 * the field have to be public, since the extension reads it. Anything else is an update this patch
 * hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findHideCaughtUpCard(): HideCaughtUpCard {
    val binds = mutableListOf<Pair<ClassDef, Method>>()
    classesHolding(END_CARD_HOLDER, END_CARD_UNIT).forEach { classDef ->
        classDef.methods.filter { it.isBindView() && it.loads(END_CARD_HOLDER) && it.loads(END_CARD_UNIT) }
            .forEach { binds += classDef to it }
    }
    val (binder, bind) = binds.singleOrNull()
        ?: refuse("expected one bindView loading both end of feed card casts, found ${binds.size}")
    val code = bind.implementation?.instructions?.toList() ?: refuse("${binder.type}->bindView has no code")
    val named = code.indexOfFirst { it.loadsString(END_CARD_UNIT) }
    val cast = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.CHECK_CAST }
        ?: refuse("bindView doesn't cast the card after naming it")
    val cardType = ((code[cast] as ReferenceInstruction).reference as TypeReference).type
    val card = classDefByOrNull(cardType) ?: refuse("$cardType isn't in this build")
    if (!AccessFlags.PUBLIC.isSet(card.accessFlags)) refuse("$cardType isn't public, so the extension can't reach it")

    val reads = code.filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.definingClass == cardType }
        .distinctBy { "${it.name}:${it.type}" }
    val withKind = reads.filter { read -> classDefByOrNull(read.type)?.let { kindFields(it).size == 1 } == true }
    val details = withKind.singleOrNull()
        ?: refuse("expected one field of $cardType read in bindView holding a $TOP_OF_FEED kind, found ${withKind.size}")
    val declared = card.fields.singleOrNull { it.name == details.name && it.type == details.type }
    if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
        refuse("$cardType->${details.name} isn't a public instance field")
    }
    staticStub(CAUGHT_UP_CARD, CAUGHT_UP_DETAILS_STUB, OBJECT)
    return HideCaughtUpCard(binder.type, cardType, "$cardType->${details.name}:${details.type}")
}

/** [findHideCaughtUpCard], or null after the patch log says why, and the switch does nothing. */
internal fun BytecodePatchContext.hideCaughtUpCardOrWarn(): HideCaughtUpCard? = try {
    findHideCaughtUpCard()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hide the caught up card does nothing in this build.")
    null
}

/** The instance fields of [details] typed by an enum whose code names [TOP_OF_FEED]. */
private fun BytecodePatchContext.kindFields(details: ClassDef): List<Field> = details.fields.filter { field ->
    if (AccessFlags.STATIC.isSet(field.accessFlags)) return@filter false
    val type = classDefByOrNull(field.type) ?: return@filter false
    type.superclass == ENUM && type.methods.any { it.loads(TOP_OF_FEED) }
}

private fun Method.loads(value: String) = implementation?.instructions?.any { it.loadsString(value) } == true

private fun Instruction.loadsString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

private fun Method.isBindView(): Boolean =
    name == "bindView" && returnType == "V" && !AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == BIND_VIEW

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the caught up card: $detail")
