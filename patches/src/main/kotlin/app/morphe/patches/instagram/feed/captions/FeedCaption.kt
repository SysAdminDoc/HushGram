/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val PATCH = "Hide captions"

internal const val HIDE_CAPTIONS = "$EXTENSION_PACKAGE/feed/HideCaptions;"
internal const val HIDE_FEED_CAPTION = "$HIDE_CAPTIONS->hideFeedCaption()Z"

/**
 * The tag Instagram 453's feed caption gives its text. It only lives in the component's modifier,
 * so no view carries it and the extension's id match never sees the caption (#395). The preview
 * comments under a post carry the same tag.
 */
internal const val FEED_CAPTION_TAG = "row_feed_comment_textview_layout"

/** The name of the caption's expand and collapse animation, which only the caption has. */
internal const val CAPTION_TRANSITION = "caption_expand_collapse_transition"

/**
 * The feed caption's render, found: the instance method of a declarative component taking its
 * render scope and returning what to draw, or null for nothing, which it already returns on a path
 * of its own. [write] puts a call to HideCaptions.hideFeedCaption() at its start, and a yes returns
 * that null, so the caption isn't drawn and the rows around it close up.
 */
internal class FeedCaption(val type: String, val name: String, val parameter: String, val returnType: String) {
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single {
            it.name == name && it.returnType == returnType && it.parameterTypes.map(CharSequence::toString) == listOf(parameter)
        }
        method.requireLocals(PATCH, 1)
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $HIDE_FEED_CAPTION
                move-result v0
                if-eqz v0, :instagram
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("instagram", method.getInstruction(0)),
        )
    }
}

/**
 * Finds the render that tags its text [FEED_CAPTION_TAG] and names its animation
 * [CAPTION_TRANSITION]: on 453's 385914557 LX/018d->A0o, a final instance method of (render scope)
 * returning a component, which returns null itself when the post's data is missing. The preview
 * comment rows (LX/08FS->A0o there) carry the tag without the animation and are left alone. Anything
 * else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findFeedCaption(): FeedCaption {
    val found = mutableListOf<Method>()
    val animated = classesLoadingString(CAPTION_TRANSITION).mapTo(HashSet()) { it.type }
    classesLoadingString(FEED_CAPTION_TAG).filter { it.type in animated }.forEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val strings = code.indices.mapNotNull { stringLoadedAt(code, it) }.toSet()
            if (FEED_CAPTION_TAG in strings && CAPTION_TRANSITION in strings) found += method
        }
    }
    val method = found.singleOrNull()
        ?: refuse("expected one render holding $FEED_CAPTION_TAG and $CAPTION_TRANSITION, found ${found.size}")
    val where = "${method.definingClass}->${method.name}"
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(method.accessFlags) || parameters.size != 1 || !parameters[0].startsWith("L") ||
        !method.returnType.startsWith("L")
    ) {
        refuse("$where isn't a render taking one scope and returning a component")
    }
    val code = method.implementation!!.instructions.toList()
    if (!returnsNull(code)) refuse("$where never returns null, so leaving its caption out could break what calls it")
    return FeedCaption(method.definingClass, method.name, parameters[0], method.returnType)
}

/**
 * Whether the method returns a register it set to 0 right before, with nothing in between writing
 * it: a null of its own, which is what the hook returns too.
 */
private fun returnsNull(code: List<Instruction>): Boolean = code.indices.any { at ->
    if (code[at].opcode != Opcode.RETURN_OBJECT) return@any false
    val register = (code[at] as OneRegisterInstruction).registerA
    val set = (at - 1 downTo 0).firstOrNull { code[it].writes(register) } ?: return@any false
    val zero = code[set]
    (zero.opcode == Opcode.CONST_4 || zero.opcode == Opcode.CONST_16) && (zero as NarrowLiteralInstruction).narrowLiteral == 0
}

/** Whether this instruction's first register is [register]: a write for most, and a read the check above treats as one. */
private fun Instruction.writes(register: Int): Boolean =
    this is OneRegisterInstruction && registerA == register || this is TwoRegisterInstruction && registerA == register

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the feed caption: $detail")
