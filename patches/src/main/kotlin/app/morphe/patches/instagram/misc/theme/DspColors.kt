/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Accent color"

/** Meta's design system color: one color for a light screen and one for a dark one. */
internal const val DSP_COLOR = "Lcom/facebook/dsp/core/ColorData;"
private const val SPECTRUM_REF = "Lcom/facebook/dsp/core/DspSpectrumRef;"
private val DSP_COLOR_PARAMETERS = listOf(SPECTRUM_REF, SPECTRUM_REF, "I", "I")

/** The extension's answers to a design system color's light and dark colors (#187). */
internal const val SWAP_DSP_LIGHT = "$ACCENT_THEME->dspLight(I)I"
internal const val SWAP_DSP_DARK = "$ACCENT_THEME->dspDark(I)I"

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/**
 * The one constructor of [DSP_COLOR], taking two spectrum references, the light color and the dark one.
 * Meta's own sheets in Instagram, the account switcher among them, build their colors as these from
 * tables written into the code, so no theme read or style reaches them and they kept Meta's blue and
 * dark gray (#187). Answers null with a warning when the class or that one constructor isn't there or
 * something jumps onto its start, and refuses without the extension's hooks.
 */
internal fun BytecodePatchContext.findDspColor(): MutableMethod? {
    val constructors = classDefByOrNull(DSP_COLOR)?.methods?.filter { it.name == "<init>" }.orEmpty()
    val constructor = constructors.singleOrNull()?.takeIf { it.parameterTypes.map(Any::toString) == DSP_COLOR_PARAMETERS && it.implementation != null }
    if (constructor == null) {
        patchLog.warning("$PATCH: found no one $DSP_COLOR constructor taking a light and a dark color, so Meta's menus keep their own colors")
        return null
    }
    for (hook in listOf(SWAP_DSP_LIGHT, SWAP_DSP_DARK)) {
        if (classDefByOrNull(ACCENT_THEME)?.methods?.any {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
            } != true
        ) {
            refuse("the extension has no public static $hook")
        }
    }
    if (0 in constructor.jumpTargets()) {
        patchLog.warning("$PATCH: something jumps onto the start of $DSP_COLOR's constructor, so Meta's menus keep their own colors")
        return null
    }
    return mutableClassDefBy(DSP_COLOR).methods.single { it.name == "<init>" }
}

/**
 * Hands a design system color's light color to AccentTheme.dspLight and its dark one to dspDark at the
 * constructor's start, and keeps the answers, so the color holds the accent's shades from the start
 * (#187). Neither touches `this`, which is fine before the super call. Leaves the colors as they are
 * when there's no such constructor.
 */
internal fun BytecodePatchContext.swapDspColors() {
    findDspColor()?.addInstructions(
        0,
        """
            invoke-static/range { p3 .. p3 }, $SWAP_DSP_LIGHT
            move-result p3
            invoke-static/range { p4 .. p4 }, $SWAP_DSP_DARK
            move-result p4
        """,
    )
}
