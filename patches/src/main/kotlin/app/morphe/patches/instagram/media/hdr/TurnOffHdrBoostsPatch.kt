/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.hdr

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.sendToStandIn
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Turn off HDR brightness boosts"
internal const val HDR_BOOST = "$EXTENSION_PACKAGE/media/HdrBoost;"

/** One framework call the patch sends to HdrBoost: its class, name and shape, and its stand-in there. */
internal data class HdrCall(val definingClass: String, val name: String, val shape: String, val standIn: String) {
    /** The HdrBoost method this call goes to: static, the receiver first, the same parameters. */
    val standInMethod get() = "$HDR_BOOST->$standIn($definingClass${shape.substringAfter('(')}"
}

/**
 * The three ways Instagram asks Android for HDR headroom (Android 15's SurfaceView, SurfaceControl
 * transaction and Window calls), the call that puts a window in HDR color mode, and Android 14's
 * extended range brightness, which the layer Instagram draws some videos on asks with (#85).
 */
internal val HDR_CALLS = listOf(
    HdrCall("Landroid/view/SurfaceView;", "setDesiredHdrHeadroom", "(F)V", "surfaceViewHeadroom"),
    HdrCall(
        "Landroid/view/SurfaceControl\$Transaction;", "setDesiredHdrHeadroom",
        "(Landroid/view/SurfaceControl;F)Landroid/view/SurfaceControl\$Transaction;", "transactionHeadroom",
    ),
    HdrCall("Landroid/view/Window;", "setDesiredHdrHeadroom", "(F)V", "windowHeadroom"),
    HdrCall("Landroid/view/Window;", "setColorMode", "(I)V", "colorMode"),
    HdrCall(
        "Landroid/view/SurfaceControl\$Transaction;", "setExtendedRangeBrightness",
        "(Landroid/view/SurfaceControl;FF)Landroid/view/SurfaceControl\$Transaction;", "extendedRangeBrightness",
    ),
)

@Suppress("unused")
val turnOffHdrBoostsPatch = bytecodePatch(
    name = "Turn off HDR brightness boosts",
    description = "Stops HDR photos and reels from making the screen brighter than everything else. Starts off. " +
        "Turn it on in HushGram settings > Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("hdrBoost")
        holdBackHdrBoosts()
        enableStatus("hdrBoost")
    }
}

/** The HDR call this instruction makes, or null. */
internal fun Instruction.hdrCall(): HdrCall? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val shape = call.parameterTypes.joinToString("", "(", ")") + call.returnType
    return HDR_CALLS.firstOrNull { it.definingClass == call.definingClass && it.name == call.name && it.shape == shape }
}

/**
 * Sends every HDR call in Instagram's code to HdrBoost, on the same registers in the same order, the
 * receiver first, so nothing after a call moves. Answers how many of each it sent. Fails before
 * changing anything when Instagram asks for headroom nowhere, since then the switch couldn't hold a
 * boost back. The extension's own calls are the real ones the stand-ins make, so they stay.
 */
internal fun BytecodePatchContext.holdBackHdrBoosts(): Map<HdrCall, Int> {
    val found = HDR_CALLS.flatMap { classesCalling(it.definingClass, it.name) }.distinctBy { it.type }
    val counts = HDR_CALLS.associateWith { call ->
        found.sumOf { classDef ->
            classDef.methods.sumOf { method -> method.implementation?.instructions?.count { it.hdrCall() == call } ?: 0 }
        }
    }
    if (counts.filterKeys { it.name == "setDesiredHdrHeadroom" }.values.sum() == 0) {
        throw PatchException("$PATCH: Instagram never asks Android for HDR headroom")
    }
    for (classDef in found) {
        for (method in mutableClassDefBy(classDef.type).methods) {
            val sites = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
                instruction.hdrCall()?.let { index to it }
            }.orEmpty()
            sites.asReversed().forEach { (index, call) -> method.sendToStandIn(index, call.standInMethod) }
        }
    }
    return counts
}
