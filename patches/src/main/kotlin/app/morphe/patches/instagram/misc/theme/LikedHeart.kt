/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val LIKED_HEART_CLASS = "$EXTENSION_PACKAGE/misc/LikedHeart;"
internal const val LIKED_HEART = "$LIKED_HEART_CLASS->outline(Landroid/graphics/drawable/Drawable;Landroid/graphics/Canvas;F)V"

private const val PATCH = "Accent color"
private const val HEART_DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val HEART_CANVAS = "Landroid/graphics/Canvas;"

/**
 * Where the like button's heart draws its line heart: [index] is the draw call in [method], [outline]
 * the register holding the line heart and [canvas] the canvas'. The instruction after it reads the
 * heart's liked progress, [progress], into [into], which nothing reads before then.
 */
internal class LikedHeartSite(
    val method: Method,
    val index: Int,
    val outline: Int,
    val canvas: Int,
    val owner: Int,
    val into: Int,
    val progress: FieldReference,
)

/**
 * The like button's heart (03o7 on 454's 386014760) is a Drawable holding a line heart and a filled
 * one. Its draw always draws the line heart first, at full strength, then lays the filled heart over
 * it at the liked progress, which is one once the post is liked. It's the one Drawable that calls
 * Canvas.saveLayerAlpha whose draw draws one of its own Drawable fields and reads its own float right
 * after. Null, with a warning, when no class or more than one has that shape.
 */
internal fun BytecodePatchContext.findLikedHeart(): LikedHeartSite? {
    val sites = classesCalling(HEART_CANVAS, "saveLayerAlpha")
        .filter { it.superclass == HEART_DRAWABLE }
        .mapNotNull { likedHeartSite(it) }
    if (sites.size != 1) {
        patchLog.warning("$PATCH: ${sites.size} like hearts draw their line heart under the filled one, not one, so a liked heart keeps its light edge")
        return null
    }
    return sites.single()
}

private fun likedHeartSite(classDef: ClassDef): LikedHeartSite? {
    val draw = classDef.methods.singleOrNull {
        it.name == "draw" && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(HEART_CANVAS)
    } ?: return null
    val code = draw.implementation?.instructions?.toList() ?: return null
    val at = code.indexOfFirst { it.callsDraw() }
    if (at < 1 || at + 1 >= code.size) return null
    val call = code[at] as FiveRegisterInstruction
    val read = code[at - 1]
    val next = code[at + 1]
    if (read.opcode != Opcode.IGET_OBJECT || next.opcode != Opcode.IGET) return null
    val outlineField = (read as ReferenceInstruction).reference as FieldReference
    val progress = (next as ReferenceInstruction).reference as FieldReference
    if (outlineField.definingClass != classDef.type || outlineField.type != HEART_DRAWABLE) return null
    if (progress.definingClass != classDef.type || progress.type != "F") return null
    if ((read as TwoRegisterInstruction).registerA != call.registerC) return null
    val into = (next as TwoRegisterInstruction).registerA
    return LikedHeartSite(draw, at, call.registerC, call.registerD, next.registerB, into, progress)
}

private fun Instruction.callsDraw(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val target = (this as ReferenceInstruction).reference as MethodReference
    return target.definingClass == HEART_DRAWABLE && target.name == "draw" &&
        target.parameterTypes.map(CharSequence::toString) == listOf(HEART_CANVAS)
}

/**
 * Hands the like button's line heart to the extension in place of drawing it, with the heart's liked
 * progress, so a liked heart in the accent color no longer shows the line heart's light edge around it
 * (#519). Leaves the heart as it is, with a warning, when [findLikedHeart] finds none or the site can't
 * take the call.
 */
internal fun BytecodePatchContext.dropLikedHeartOutline() {
    val hook = classDefByOrNull(LIKED_HEART_CLASS)?.methods?.singleOrNull {
        it.name == "outline" && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(HEART_DRAWABLE, HEART_CANVAS, "F") &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }
    if (hook == null) throw PatchException("$PATCH: the extension has no public static $LIKED_HEART")
    val site = findLikedHeart() ?: return
    val registers = listOf(site.outline, site.canvas, site.owner, site.into)
    val clash = site.into == site.outline || site.into == site.canvas || site.into == site.owner
    val tries = site.method.implementation?.tryBlocks?.isNotEmpty() == true
    if (registers.any { it > 15 } || clash || tries || site.index in site.method.jumpTargets()) {
        patchLog.warning("$PATCH: ${site.method.definingClass}->draw can't take the liked heart's call, so a liked heart keeps its light edge")
        return
    }
    val method = mutableClassDefBy(site.method.definingClass).methods.single {
        it.name == "draw" && it.parameterTypes.map(CharSequence::toString) == listOf(HEART_CANVAS)
    }
    method.removeInstruction(site.index)
    method.addInstructions(
        site.index,
        """
            iget v${site.into}, v${site.owner}, ${site.progress.definingClass}->${site.progress.name}:F
            invoke-static { v${site.outline}, v${site.canvas}, v${site.into} }, $LIKED_HEART
        """,
    )
}
