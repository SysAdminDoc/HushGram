/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val STORY_CREDIT = "$EXTENSION_PACKAGE/metaai/MetaAi;->storyCredit(Ljava/lang/Object;)Z"

/** The kinds of line a story's header shows (music, effects, Meta AI's tool credit...), an enum Redex leaves under its own name. */
internal const val ATTRIBUTION_TYPE = "Lcom/instagram/model/reels/ReelHeaderAttributionType;"

/** What the check's first parameters are: a Context, the session, the line's kind and the story. */
internal val STORY_CREDIT_PARAMETERS = listOf(
    "Landroid/content/Context;",
    "Lcom/instagram/common/session/UserSession;",
    ATTRIBUTION_TYPE,
    "Lcom/instagram/model/reels/ReelItem;",
)

/** Where the story header's line check is: its class, name and parameters. */
internal class StoryCreditCheck(val type: String, val name: String, val parameters: List<String>)

/**
 * A story's header cycles through lines of several kinds. Meta AI's tool credit is one of them
 * ("Restyled with Meta AI", a dot and Try it, built by 0B1z on 457 for the META_AI_ATTRIBUTION
 * kind), and every list of those lines (the header's own, 03U5.A00 on 386314341, and the one 0Gbw
 * builds) asks one instance method whether to show a kind's line for the story: 03U8.A0H, taking
 * (Context, session, kind, story, two more) and answering a boolean from a switch on the kind's
 * ordinal. It's the one such method that calls ordinal and switches. Fails when there isn't
 * exactly one, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryCreditCheck(): StoryCreditCheck {
    // A new fingerprint each time: it searches the patcher's current run, and a test makes several.
    val matches = Fingerprint(returnType = "Z", custom = { method, _ -> method.isStoryCreditCheck() })
        .matchAllOrNull().orEmpty().map { it.originalMethod }
    val check = matches.singleOrNull()
        ?: throw PatchException("Hide Meta AI: ${matches.size} methods decide a story header's lines (instance, ${STORY_CREDIT_PARAMETERS.joinToString()}), not one")
    return StoryCreditCheck(check.definingClass, check.name, check.parameterTypes.map(CharSequence::toString))
}

/** An instance method taking [STORY_CREDIT_PARAMETERS] first, answering a boolean, that calls ordinal and switches. */
internal fun Method.isStoryCreditCheck(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "Z") return false
    val parameters = parameterTypes.map(CharSequence::toString)
    if (parameters.take(STORY_CREDIT_PARAMETERS.size) != STORY_CREDIT_PARAMETERS) return false
    val code = implementation?.instructions?.toList() ?: return false
    val switches = code.any { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    val ordinal = code.any { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.name == "ordinal" && called.returnType == "I" && called.parameterTypes.isEmpty()
    }
    return switches && ordinal
}

/**
 * Makes the check answer false from its first instruction for Meta AI's tool credit while Hide
 * Meta AI restyle credits is on: the answer a story with no such credit gets, so no list holds
 * the line. The extension answers no for every other kind, and with the switch off or paused, so
 * those take Instagram's own path, and the early answer depends only on the kind handed in. The
 * kind is read at the method's entry, before anything can reuse its register, and the method's
 * first local is free there. Nothing jumps back to its first instruction.
 */
internal fun BytecodePatchContext.holdStoryCredit(check: StoryCreditCheck) {
    val method = mutableClassDefBy(check.type).methods.single {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == check.parameters
    }
    method.requireLocals("Hide Meta AI", 1)
    if (0 in method.jumpTargets()) throw PatchException("Hide Meta AI: something jumps back to the story header check's first instruction")
    val kind = method.parameterRegister(STORY_CREDIT_PARAMETERS.indexOf(ATTRIBUTION_TYPE))
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { $kind .. $kind }, $STORY_CREDIT
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", method.getInstruction(0)),
    )
}
