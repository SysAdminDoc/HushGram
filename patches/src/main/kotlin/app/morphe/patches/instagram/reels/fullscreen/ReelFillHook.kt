/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fullscreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsConstString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val FILL_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->fill(F)Z"
internal const val FIT_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->fit()Z"

/** The purge flag Instagram's reel crop decision names first thing. */
internal const val SHOULD_CROP = "android_purge_26_q2_ClipsMediaResizingUtils_shouldCropDynamic"

/** The one it names where it works out how far the viewer's shape is from the reel's. */
internal const val CROP_PERCENTAGE = "android_purge_26_q2_ClipsMediaResizingUtils_getCropPercentage"

/**
 * The method that decides whether a reel's video fills the viewer (true) or fits inside it with bars
 * (false), 08JU.A02 on 452. It's the one method naming both purge flags.
 */
internal object ReelCropDecisionFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string(SHOULD_CROP)),
    custom = { method, _ -> method.loadsConstString(CROP_PERCENTAGE) },
)

/**
 * Fill the screen (#288). After the crop percentage name, the decision divides the difference
 * between the viewer's aspect ratio and the reel's by the reel's and multiplies it by 100, then
 * crops only below thresholds Instagram's server sends. With Full screen Reels on the viewer is
 * taller, so a 9:16 reel often lands past them and shows bars. Right after that multiply the
 * decision asks [FILL_HOOK] with the percentage, and returns true (fill) when it says so. Otherwise
 * Instagram's own checks run as before.
 */
internal fun BytecodePatchContext.fillReelScreen() {
    val decision = uniqueMethod(FULL_SCREEN_BARS_PATCH, "reel crop decision", ReelCropDecisionFingerprint)
    val code = decision.implementation?.instructions?.toList().orEmpty()
    val named = code.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == CROP_PERCENTAGE
    }
    val multiply = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.MUL_FLOAT_2ADDR }
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel crop decision multiplies no float after $CROP_PERCENTAGE")
    val percent = (code[multiply] as TwoRegisterInstruction).registerA
    if (percent > 15) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel crop decision keeps its percentage in v$percent, past what an invoke names")
    }
    val at = multiply + 1
    val answer = decision.freeLocalsAt(FULL_SCREEN_BARS_PATCH, at, 1, except = listOf(percent)).first()
    decision.addInstructionsWithLabels(
        at,
        """
            invoke-static { v$percent }, $FILL_HOOK
            move-result v$answer
            if-eqz v$answer, :instagram
            return v$answer
        """,
        ExternalLabel("instagram", decision.getInstruction(at)),
    )
}

/**
 * Show the whole reel (piko #2183). Before the percentage exists the decision already answers true
 * for media Instagram lets crop, so the ask goes in at its first instruction: [FIT_HOOK] says yes
 * and the decision answers false (fit with bars), ahead of Instagram's crop and of [fillReelScreen].
 * The first local is free there, and nothing jumps back to it.
 */
internal fun BytecodePatchContext.fitReelScreen() {
    val decision = uniqueMethod(FULL_SCREEN_BARS_PATCH, "reel crop decision", ReelCropDecisionFingerprint)
    decision.requireLocals(FULL_SCREEN_BARS_PATCH, 1)
    if (0 in decision.jumpTargets()) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: something jumps back to the reel crop decision's first instruction")
    }
    decision.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $FIT_HOOK
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", decision.getInstruction(0)),
    )
}
