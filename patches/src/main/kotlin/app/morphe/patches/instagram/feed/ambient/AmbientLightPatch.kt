/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.ambient

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.media.surface.videoSurfacePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Ambient light"
internal const val ROUNDED_LAYOUT = "Lcom/instagram/ui/widget/roundedcornerlayout/RoundedCornerFrameLayout;"
internal const val CORNER_RADIUS = "$EXTENSION_PACKAGE/feed/AmbientLight;->cornerRadius(Landroid/view/View;I)V"
private const val SET_RADIUS = "setCornerRadius"

/**
 * Puts a soft glow in a post's own colors around its photo or video, and in the bands around a reel that
 * doesn't fill the screen (#416). See AmbientLight in the extension.
 *
 * The extension finds each feed post's media frame by its class name (`MediaFrameLayout`, kept by the
 * minifier) and each reel's video by the viewer's frame id (`clips_viewer_video_layout`, the one Rounded
 * corners on Reels uses), samples a tiny copy of the picture a few times a second and draws the glow behind
 * it as the background of the row or the viewer. The application-start hook the settings patch already
 * writes is what starts the watching. Of Instagram's code the patch changes only its rounded layout's
 * setCornerRadius, which first tells the extension the radius: the story viewer's media layout paints its
 * corners solid black over the story at that radius, and the glow covers them (#630). In the default
 * selection with its switch off.
 */
@Suppress("unused")
val ambientLightPatch = bytecodePatch(
    name = "Ambient light",
    description = "Puts a soft glow in a post's own colors around its photo or video in Home and Following, and in " +
        "the bands around a reel that doesn't fill the screen. The glow follows a video as it plays, and you pick " +
        "how far it spreads and how fast it changes. A second switch fills the bands above and below a story that " +
        "doesn't fill the screen. Both start off. Turn them on in HushGram settings > Feed and Stories.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch, videoSurfacePatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("ambientLight")
        hookCornerRadius()
        enableStatus("ambientLight")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Puts the call telling the extension the radius first in the rounded layout's own setCornerRadius(I),
 * refused before anything changes when the build has no such method, when it already calls the
 * extension or when something in it jumps back to its start.
 */
internal fun BytecodePatchContext.hookCornerRadius() {
    val layout = classDefByOrNull(ROUNDED_LAYOUT) ?: refuse("this build has no $ROUNDED_LAYOUT")
    val method = layout.methods.singleOrNull { it.setsRadius() } ?: refuse("$ROUNDED_LAYOUT has no $SET_RADIUS(I)V of its own")
    val code = method.implementation!!.instructions.toList()
    if (code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == CORNER_RADIUS }) {
        refuse("$ROUNDED_LAYOUT->$SET_RADIUS already calls the extension")
    }
    if (0 in method.jumpTargets()) refuse("something in $ROUNDED_LAYOUT->$SET_RADIUS jumps back to its start")
    mutableClassDefBy(ROUNDED_LAYOUT).methods.single { it.setsRadius() }
        .addInstructions(0, "invoke-static/range { p0 .. p1 }, $CORNER_RADIUS")
}

/** Whether this is the layout's own setCornerRadius(I)V, with a body. */
internal fun Method.setsRadius(): Boolean =
    name == SET_RADIUS && returnType == "V" && parameterTypes.map(CharSequence::toString) == listOf("I") &&
        !AccessFlags.STATIC.isSet(accessFlags) && implementation != null
