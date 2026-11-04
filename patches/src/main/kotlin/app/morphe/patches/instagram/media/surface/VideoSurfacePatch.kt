/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.surface

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal const val VIDEO_SURFACE = "$EXTENSION_PACKAGE/media/VideoSurface;"
internal const val TEXTURE_ONLY = "$VIDEO_SURFACE->textureOnly()Z"

/**
 * Loaded by the method choosing a SurfaceView for a screen's video: the phone model it treats on its
 * own, ahead of the server option and the list of screens.
 */
internal const val SURFACE_CHOICE_MARKER = "Pixel 10"

private val SURFACE_CHOICE_PARAMETERS = listOf("Lcom/instagram/common/session/UserSession;", "Ljava/lang/String;")
private const val BUILD_MODEL = "Landroid/os/Build;->MODEL:Ljava/lang/String;"
private const val SDK_INT = "Landroid/os/Build\$VERSION;->SDK_INT:I"

/**
 * The static (UserSession, String)Z loading [SURFACE_CHOICE_MARKER], or null unless there's exactly
 * one. Some 454 builds drop the phone model test and its marker but still read the model on the
 * way to the server option (LX/04h7;->A00 on 386014758): with no method loading the marker, it's the
 * one static (UserSession, String)Z reading both Build.MODEL and Build.VERSION.SDK_INT.
 */
internal fun BytecodePatchContext.findSurfaceChoice(): Method? {
    val marked = classesLoadingString(SURFACE_CHOICE_MARKER).flatMap { classDef ->
        classDef.methods.filter { method -> method.isSurfaceChoiceShape() && loadsString(method, SURFACE_CHOICE_MARKER) }
    }
    if (marked.isNotEmpty()) return marked.singleOrNull()
    return classesAccessing("Landroid/os/Build;", "MODEL", Opcode.SGET_OBJECT).flatMap { classDef ->
        classDef.methods.filter { method -> method.isSurfaceChoiceShape() && method.reads(BUILD_MODEL) && method.reads(SDK_INT) }
    }.singleOrNull()
}

private fun Method.isSurfaceChoiceShape(): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "Z" && parameterTypes.map { it.toString() } == SURFACE_CHOICE_PARAMETERS

private fun Method.reads(field: String): Boolean = implementation?.instructions?.any { instruction ->
    (instruction.opcode == Opcode.SGET || instruction.opcode == Opcode.SGET_OBJECT) &&
        (instruction as? ReferenceInstruction)?.reference?.toString() == field
} == true

/**
 * Puts VideoSurface first in Instagram's choice of a SurfaceView, so it answers no while a switch
 * here needs a video it can read. The player, its warmup and the reel's super white all ask this one
 * method, so they stay in agreement. Answers the method it changed, or null when it can't be found or
 * can't take the hook: every video then stays Instagram's choice, and the log says so.
 */
internal fun BytecodePatchContext.keepVideoOnTexture(): MutableMethod? {
    val choice = findSurfaceChoice() ?: run {
        patchLog.warning("Video surface: found no one method loading \"$SURFACE_CHOICE_MARKER\" or reading the phone model, so a SurfaceView video stays out of the glass")
        return null
    }
    val shape = choice.parameterTypes.map { it.toString() }
    val method = mutableClassDefBy(choice.definingClass).methods.first {
        it.name == choice.name && it.parameterTypes.map { type -> type.toString() } == shape
    }
    if (method.localRegisterCount() < 1 || 0 in method.jumpTargets()) {
        patchLog.warning("Video surface: ${method.definingClass}->${method.name} can't take the hook, so a SurfaceView video stays out of the glass")
        return null
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $TEXTURE_ONLY
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
    return method
}

/**
 * Keeps video on a TextureView for the patches that read it or want it off HDR. It has no switch of
 * its own: each of those patches names it, and their switches decide.
 */
internal val videoSurfacePatch = bytecodePatch {
    dependsOn(instagramExtensionPatch)

    execute {
        keepVideoOnTexture()
    }
}
