/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.ring

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

private const val PATCH = "Story ring size"
internal const val RING_COLORS = "$EXTENSION_PACKAGE/stories/StoryRing;->colors([I[F)[I"

/** The view that draws the ring around a profile picture, under the name Instagram keeps for it. */
internal const val GRADIENT_SPINNER = "Lcom/instagram/ui/widget/gradientspinner/GradientSpinner;"
internal const val LINEAR_GRADIENT = "Landroid/graphics/LinearGradient;"

/** What the ring's gradient builder takes: the ring, its stops, where it starts and ends, its colors, and its width and height. */
internal val GRADIENT_PARAMETERS = listOf(GRADIENT_SPINNER, "[F", "[F", "[F", "[I", "I", "I")
internal const val STOPS_PARAMETER = 1
internal const val COLORS_PARAMETER = 4

/** The gradient the builder makes from an array of colors and an array of stops. */
internal const val GRADIENT_FROM_ARRAYS =
    "$LINEAR_GRADIENT-><init>(FFFF[I[FLandroid/graphics/Shader\$TileMode;)V"

/** The name of the ring Instagram gives a story by default, loaded where that ring's colors and stops are put together. */
internal const val DEFAULT_RING = "default_brand_update"

/**
 * The four stops of that ring's gradient, as the doubles Instagram loads them. The extension tells
 * a story's own gradient from every other ring by these, so the patch fails on a build that draws
 * its default ring with others rather than leave a choice that does nothing.
 */
internal val DEFAULT_RING_STOPS = listOf(0.1435, 0.3779, 0.6071, 0.831).map(Double::toRawBits)

/** The highest register a call that lists its registers can name. */
private const val HIGHEST_LISTED_REGISTER = 15

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Where a ring builds its gradient: the builder's name, and the registers its colors and stops arrive in. */
internal class RingGradient(val name: String, val colors: Int, val stops: Int)

/**
 * Finds the one place a ring builds its gradient, and checks it before anything changes. The ring
 * is a view that keeps its class name, and the builder is its one static method taking
 * [GRADIENT_PARAMETERS] and answering a gradient. It is called when a ring is measured and when it
 * is given a ring's colors, never once a frame. A seen story's ring is drawn with another paint and
 * doesn't come through here.
 *
 * The hook goes first, so nothing may jump back to the first instruction, and a call that lists
 * its registers has to be able to name the two it passes. The app must also still build its
 * default ring with [DEFAULT_RING_STOPS], which is what the extension knows a story's gradient by.
 */
internal fun BytecodePatchContext.findRingGradient(): RingGradient {
    val spinner = classDefByOrNull(GRADIENT_SPINNER) ?: refuse("the build has no $GRADIENT_SPINNER")
    val builders = spinner.methods.filter { it.buildsRingGradient() }
    val builder = builders.singleOrNull()
        ?: refuse("expected one static gradient builder taking stops and colors in $GRADIENT_SPINNER, found ${builders.size}")
    val where = "$GRADIENT_SPINNER->${builder.name}"
    val code = builder.implementation?.instructions?.toList() ?: refuse("$where has no body")
    if (code.none { (it as? ReferenceInstruction)?.reference?.toString() == GRADIENT_FROM_ARRAYS }) {
        refuse("$where no longer makes its gradient from an array of colors and an array of stops")
    }
    if (0 in builder.jumpTargets()) refuse("something jumps back to the first instruction of $where")
    val colors = builder.parameterRegisterNumber(COLORS_PARAMETER)
    val stops = builder.parameterRegisterNumber(STOPS_PARAMETER)
    if (colors > HIGHEST_LISTED_REGISTER || stops > HIGHEST_LISTED_REGISTER) {
        refuse("$where keeps its colors in v$colors and its stops in v$stops, past the registers a call can list")
    }
    if (findDefaultRingStops().isEmpty()) {
        refuse("no method of a class loading \"$DEFAULT_RING\" loads the default ring's four stops")
    }
    return RingGradient(builder.name, colors, stops)
}

private fun Method.buildsRingGradient() = AccessFlags.STATIC.isSet(accessFlags) && returnType == LINEAR_GRADIENT &&
    parameterTypes.map(CharSequence::toString) == GRADIENT_PARAMETERS

/**
 * The methods that put Instagram's default ring together: each is in a class loading
 * [DEFAULT_RING] and loads every one of [DEFAULT_RING_STOPS]. On 450 there is one in every build.
 */
internal fun BytecodePatchContext.findDefaultRingStops(): List<String> =
    classesLoadingString(DEFAULT_RING).flatMap { classDef ->
        classDef.methods.filter { it.loadsEveryStop() }.map { "${classDef.type}->${it.name}" }
    }

private fun Method.loadsEveryStop(): Boolean {
    val loaded = implementation?.instructions?.mapNotNullTo(HashSet()) { (it as? WideLiteralInstruction)?.wideLiteral }
        ?: return false
    return loaded.containsAll(DEFAULT_RING_STOPS)
}

/**
 * Passes the builder's colors and stops through [RING_COLORS] first thing, and builds the gradient
 * from the answer. The ring's own fields are left alone, so Instagram's colors are what the hook is
 * given every time, and a ring whose hook answers them back is exactly Instagram's.
 */
internal fun BytecodePatchContext.colorRingGradient(gradient: RingGradient) {
    val builder = mutableClassDefBy(GRADIENT_SPINNER).methods.single { it.name == gradient.name && it.buildsRingGradient() }
    builder.addInstructions(
        0,
        """
            invoke-static { v${gradient.colors}, v${gradient.stops} }, $RING_COLORS
            move-result-object v${gradient.colors}
        """,
    )
}
