/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.calls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

internal const val BLOCK_PATCH = "Call blocker"

internal const val CALL_BLOCKER = "$EXTENSION_PACKAGE/direct/CallBlocker;"
internal const val DROP_CALL = "$CALL_BLOCKER->drop(Ljava/lang/Object;)Z"

/** The action of the intent an incoming call opens its screen with, which only its ring loads. */
internal const val INCOMING_CALL_ACTION = "rtc_call_activity_intent_action_incoming_call"

/** The call object Instagram rings with, a class it keeps by name. */
internal const val CALL_ENTITY = "Lcom/instagram/rtc/signaling/models/RtcConnectionEntity;"

/**
 * Chooses who can ring your phone with a call. Included in the default selection with Everyone
 * chosen, so nothing changes until someone picks.
 */
@Suppress("unused")
val callBlockerPatch = bytecodePatch(
    name = "Call blocker",
    description = "Lets you choose who can ring your phone with an Instagram call: everyone, nobody, only people " +
        "on your list, or everyone except them. A stopped call just goes unanswered for the caller. Everyone to " +
        "start. Change it in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("callBlocker")
        stopRing(findCallRing())
        enableStatus("callBlocker")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$BLOCK_PATCH: $why")

/**
 * The method turning an incoming call into its ring: the one taking the call object and loading
 * [INCOMING_CALL_ACTION]. It needs a local for the hook's answer, and nothing may jump to its first
 * instruction.
 */
internal fun BytecodePatchContext.findCallRing(): MutableMethod {
    val rings = classesLoadingString(INCOMING_CALL_ACTION).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.parameterTypes.any { it.toString() == CALL_ENTITY } && loadsString(method, INCOMING_CALL_ACTION)
        }
    }
    val ring = rings.singleOrNull()
        ?: refuse("expected one method taking $CALL_ENTITY and loading \"$INCOMING_CALL_ACTION\", found ${rings.size}")
    val method = mutableClassDefBy(ring.definingClass).methods.single {
        it.name == ring.name && it.parameterTypes.map(Any::toString) == ring.parameterTypes.map(Any::toString) &&
            it.returnType == ring.returnType
    }
    if (method.returnType != "V") refuse("${method.definingClass}->${method.name} returns ${method.returnType}, not void")
    if (method.localRegisterCount() < 1) refuse("${method.definingClass}->${method.name} has no local for the answer")
    if (0 in method.jumpTargets()) refuse("a jump or exception handler enters ${method.definingClass}->${method.name} at its first instruction")
    return method
}

/** Asks the extension first with the call object. A yes returns before Instagram rings. */
internal fun stopRing(ring: MutableMethod) {
    val parameters = ring.parameterTypes.map(Any::toString)
    val first = if (AccessFlags.STATIC.isSet(ring.accessFlags)) 0 else 1
    val call = first + parameters.take(parameters.indexOf(CALL_ENTITY)).sumOf { if (it == "J" || it == "D") 2 as Int else 1 }
    ring.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p$call .. p$call }, $DROP_CALL
            move-result v0
            if-eqz v0, :keep
            return-void
        """,
        ExternalLabel("keep", ring.getInstruction(0)),
    )
}
