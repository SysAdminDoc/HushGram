/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Clean up Reels"

/**
 * The view id Instagram gives the Create button on the screen's own top bar over a reel
 * (`clips_viewer_camera_button`, the same in all four 456 builds; 0x7f0b0c7c in 455). Only the
 * method building the button loads it. 455 also logged a purge marker where the bar updated the
 * button, which 456 dropped.
 */
internal const val CAMERA_BUTTON_ID = 0x7f0b0c77

internal const val CAMERA_BUTTON_HOOK = "$EXTENSION_PACKAGE/reels/ReelsTopBar;->keepsCameraButton(Ljava/lang/Object;)Z"

private const val VIEW = "Landroid/view/View;"

/**
 * Where Create (+) goes on the screen's own top bar over a reel (#620): [method] builds the button
 * and keeps it in a View field of its class. [holderField] is that class's field for the viewer's
 * setup, and [sourceField] the setup's ClipsViewerSource.
 */
internal class CameraButtonSite(
    val method: Method,
    val holderField: String,
    val sourceField: String,
)

/**
 * Finds the one place Instagram adds Create to the bar a reel opened from Saved, a profile or a post
 * gets, failing before anything changes when it isn't there. An instance `()V` method of the bar's
 * class loads the button's id, [CAMERA_BUTTON_ID], builds the button and stores it in the class's
 * own View field. The class holds the viewer's setup in one field, whose class carries the viewer's
 * source.
 */
internal fun BytecodePatchContext.findCameraButton(): CameraButtonSite {
    val sites = classesLoading(CAMERA_BUTTON_ID.toLong()).flatMap { classDef ->
        val builders = classDef.methods.filter { it.loadsCameraButtonId() && it.buildsOwnView(classDef.type) }
        if (builders.isEmpty()) return@flatMap emptyList()
        val holders = classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.mapNotNull { field ->
            val holder = classDefByOrNull(field.type)?.takeIf { !AccessFlags.INTERFACE.isSet(it.accessFlags) } ?: return@mapNotNull null
            val source = holder.sourceFields().singleOrNull() ?: return@mapNotNull null
            "${classDef.type}->${field.name}:${field.type}" to "${holder.type}->${source.name}:${source.type}"
        }
        val holder = holders.singleOrNull() ?: return@flatMap emptyList()
        builders.map { CameraButtonSite(it, holder.first, holder.second) }
    }
    return sites.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one place adding Create to the top bar of a reel opened from Saved or a profile, found ${sites.size}",
    )
}

private fun ClassDef.sourceFields() = fields.filter { it.type == CLIPS_VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun Method.loadsCameraButtonId() = implementation?.instructions?.any {
    it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == CAMERA_BUTTON_ID
} ?: false

private fun Method.buildsOwnView(owner: String): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || parameterTypes.isNotEmpty()) return false
    return implementation?.instructions?.any { instruction ->
        if (instruction.opcode != Opcode.IPUT_OBJECT) return@any false
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        field.definingClass == owner && field.type == VIEW
    } ?: false
}

/**
 * Asks [CAMERA_BUTTON_HOOK] with the viewer's source before anything else, and returns before the
 * button is built when it says no. Instagram's own first check returns the same way, so whatever
 * reads the button's field already copes with it unset. The first local is free at the start, and
 * nothing jumps back there. A setup not made yet keeps the button.
 */
internal fun BytecodePatchContext.hideCameraButton(site: CameraButtonSite) {
    val found = site.method
    val method = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.parameterTypes.isEmpty() && it.returnType == found.returnType
    }
    method.requireLocals(PATCH, 1)
    if (0 in method.jumpTargets()) {
        throw PatchException("$PATCH: something jumps back to the start of ${found.definingClass}->${found.name}")
    }
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${site.holderField}
            if-eqz v0, :instagram
            iget-object v0, v0, ${site.sourceField}
            invoke-static { v0 }, $CAMERA_BUTTON_HOOK
            move-result v0
            if-nez v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
}
