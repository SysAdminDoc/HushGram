/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

internal const val PEEK_CARD = "$EXTENSION_PACKAGE/misc/PeekCard;->color(J)J"

/** The test tag Instagram gives the Instagram Plus card over the menu from holding an inbox chat. */
internal const val PEEK_UPSELL_TAG = "message_peek_upsell"

private const val PEEK_PATCH = "Pure black dark mode"

/**
 * Sends the fill the Instagram Plus card reads from the context menu's Compose colors through the
 * hook, so its dark gray comes in black like the view menu under it (#543). The card is the one
 * composable loading [PEEK_UPSELL_TAG], and the one color it reads is its fill, which it hands to
 * the surface it draws. Anything else fails the patch.
 */
internal fun BytecodePatchContext.blackenPeekCards() {
    val cards = classesLoadingString(PEEK_UPSELL_TAG).flatMap { classDef -> classDef.methods.map { classDef.type to it } }
        .filter { (_, method) -> loadsString(method, PEEK_UPSELL_TAG) }
    val (type, card) = cards.singleOrNull()
        ?: throw PatchException("$PEEK_PATCH: expected one card loading \"$PEEK_UPSELL_TAG\", found ${cards.size}")
    val reads = card.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.IGET_WIDE }.toList()
    val read = reads.singleOrNull()
        ?: throw PatchException("$PEEK_PATCH: the Instagram Plus card reads ${reads.size} colors, not its one fill")
    val fill = (read.value as TwoRegisterInstruction).registerA
    val method = mutableClassDefBy(type).methods.single {
        it.name == card.name && it.returnType == card.returnType &&
            it.parameterTypes.map(Any::toString) == card.parameterTypes.map(Any::toString)
    }
    method.addInstructions(
        read.index + 1,
        """
            invoke-static/range { v$fill .. v${fill + 1} }, $PEEK_CARD
            move-result-wide v$fill
        """,
    )
}
