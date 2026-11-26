/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val REELS_PIP_BUTTONS = "$EXTENSION_PACKAGE/media/ReelsPipButtons;"

/** Asked at the top of the window's settings builder with its activity and Instagram's answer for its own buttons (#873). */
internal const val PIP_WITH_ACTIONS = "$REELS_PIP_BUTTONS->withActions(Landroid/app/Activity;I)Z"

/** Handed the window's list of buttons right before it's set, and answers the list to set. */
internal const val PIP_ACTIONS = "$REELS_PIP_BUTTONS->actions(Ljava/util/List;)Ljava/util/List;"

/** The tag ClipsViewerPipUtil logs a failed picture in picture call under, which its class loads. */
internal const val PIP_UTIL_TAG = "ClipsViewerPipUtil"

private const val PIP_ACTIVITY = "Landroid/app/Activity;"
private const val PIP_PARAMS = "Landroid/app/PictureInPictureParams;"
private const val PIP_PARAMS_BUILDER = "Landroid/app/PictureInPictureParams\$Builder;"

/**
 * Where the window's buttons go: ClipsViewerPipUtil's settings builder, an (Activity, ..., boolean)
 * method answering the window's PictureInPictureParams, which sets Instagram's buttons only when its
 * boolean, the server flag's answer, says yes. [setActions] is the index of its one setActions call.
 */
internal class ReelsPipButtonSites(val builder: Method, val setActions: Int)

/**
 * Finds the settings builder before anything changes: the one method of a class loading
 * [PIP_UTIL_TAG] that takes an Activity first and a boolean last, answers PictureInPictureParams and
 * calls the params builder's setActions once. Fails naming [patch] when it isn't found once, when
 * the call can be jumped onto, or when the list isn't in a register a plain call can read.
 */
internal fun BytecodePatchContext.findReelsPipButtons(patch: String): ReelsPipButtonSites {
    val builders = classesLoadingString(PIP_UTIL_TAG).flatMap { it.methods }.filter { method ->
        val parameters = method.parameterTypes.map(Any::toString)
        method.returnType == PIP_PARAMS && parameters.firstOrNull() == PIP_ACTIVITY && parameters.lastOrNull() == "Z" &&
            method.pipCode().count { it.setsPipActions() } == 1
    }
    val builder = builders.singleOrNull()
        ?: throw PatchException("$patch: expected one picture in picture settings builder setting its buttons, found ${builders.size}")
    val code = builder.implementation!!.instructions.toList()
    val setActions = code.indexOfFirst { it.setsPipActions() }
    if (setActions in builder.jumpTargets()) throw PatchException("$patch: the settings builder can jump onto its setActions")
    val list = (code[setActions] as FiveRegisterInstruction).registerD
    if (list > 255) throw PatchException("$patch: the settings builder's button list is in v$list")
    return ReelsPipButtonSites(builder, setActions)
}

/**
 * Makes the settings builder ask [PIP_WITH_ACTIONS] at its top, with its activity and its boolean,
 * and use the answer as that boolean, so the list of buttons is set whenever a button of ours is
 * on, and hand the list to [PIP_ACTIONS] right before it's set. Nothing holds a local before a
 * method's first instruction, so the first two carry the arguments into a plain call.
 */
internal fun BytecodePatchContext.addReelsPipButtons(patch: String, sites: ReelsPipButtonSites) {
    val builder = mutable(sites.builder)
    builder.requireLocals(patch, 2)
    val list = builder.getInstruction<FiveRegisterInstruction>(sites.setActions).registerD
    builder.addInstructions(
        sites.setActions,
        """
            invoke-static/range { v$list .. v$list }, $PIP_ACTIONS
            move-result-object v$list
        """,
    )
    val activity = builder.parameterRegister(0)
    val wanted = builder.parameterRegister(builder.parameterTypes.lastIndex)
    builder.addInstructions(
        0,
        """
            move-object/from16 v0, $activity
            move/from16 v1, $wanted
            invoke-static { v0, v1 }, $PIP_WITH_ACTIONS
            move-result $wanted
        """,
    )
}

private fun Method.pipCode(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.setsPipActions(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.definingClass == PIP_PARAMS_BUILDER && call.name == "setActions" &&
        call.parameterTypes.map(Any::toString) == listOf("Ljava/util/List;")
}
