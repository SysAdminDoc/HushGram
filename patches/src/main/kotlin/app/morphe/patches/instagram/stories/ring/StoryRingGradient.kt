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

/** What the ring's own setter takes: the builder's parameters without the ring, which is `this` there. */
internal val SETTER_PARAMETERS = GRADIENT_PARAMETERS.drop(1)

/**
 * Where a ring builds its gradient: the method's name and parameter types, and the registers its
 * colors and stops arrive in.
 */
internal class RingGradient(val name: String, val parameters: List<String>, val colors: Int, val stops: Int)

/**
 * Finds every place a ring builds its gradient, and checks each before anything changes. The ring
 * is a view that keeps its class name. Its builder is its one static method taking
 * [GRADIENT_PARAMETERS] and answering a gradient. Most of 454's builds also give the view a private
 * setter taking [SETTER_PARAMETERS] that makes the same gradient with its own copy of the builder's
 * code and keeps it (#518). That setter is what the stories row's rings go through, from the ring's
 * measure and from every ring spec it's given, and the builder only serves a couple of other
 * callers. Where the setter calls the builder instead, as on 386014704, only the builder makes
 * a gradient. Both are called when a ring is measured or given its colors, never once a frame. A
 * seen story's ring is drawn with another paint and doesn't come through here.
 *
 * No other method of the view may make a gradient from colors and stops, since the patch wouldn't
 * know where its colors are. Each hook goes first, so nothing may jump back to a first
 * instruction, and a call that lists its registers has to be able to name the two it passes. The
 * app must also still build its default ring with [DEFAULT_RING_STOPS], which is what the
 * extension knows a story's gradient by.
 */
internal fun BytecodePatchContext.findRingGradients(): List<RingGradient> {
    val spinner = classDefByOrNull(GRADIENT_SPINNER) ?: refuse("the build has no $GRADIENT_SPINNER")
    val builders = spinner.methods.filter { it.isRingBuilder() }
    val builder = builders.singleOrNull()
        ?: refuse("expected one static gradient builder taking stops and colors in $GRADIENT_SPINNER, found ${builders.size}")
    if (!builder.makesGradientFromArrays()) {
        refuse("$GRADIENT_SPINNER->${builder.name} no longer makes its gradient from an array of colors and an array of stops")
    }
    val setters = spinner.methods.filter { it.isRingSetter() && it.makesGradientFromArrays() }
    if (setters.size > 1) {
        refuse("expected at most one setter in $GRADIENT_SPINNER making its own gradient from stops and colors, found ${setters.size}")
    }
    val unread = spinner.methods.filter { it.makesGradientFromArrays() && !it.isRingBuilder() && !it.isRingSetter() }
    if (unread.isNotEmpty()) {
        refuse("${unread.joinToString { "$GRADIENT_SPINNER->${it.name}" }} makes a gradient from colors and stops the patch can't find")
    }
    val gradients = (builders + setters).map { it.ringGradient() }
    if (findDefaultRingStops().isEmpty()) {
        refuse("no method of a class loading \"$DEFAULT_RING\" loads the default ring's four stops")
    }
    return gradients
}

/** Reads where the colors and stops arrive in a builder or the setter, refusing what the hook can't go into. */
private fun Method.ringGradient(): RingGradient {
    val where = "$GRADIENT_SPINNER->$name"
    if (0 in jumpTargets()) refuse("something jumps back to the first instruction of $where")
    // The setter has no ring parameter, so each of its parameters is one place earlier.
    val shift = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    val colors = parameterRegisterNumber(COLORS_PARAMETER - shift)
    val stops = parameterRegisterNumber(STOPS_PARAMETER - shift)
    if (colors > HIGHEST_LISTED_REGISTER || stops > HIGHEST_LISTED_REGISTER) {
        refuse("$where keeps its colors in v$colors and its stops in v$stops, past the registers a call can list")
    }
    return RingGradient(name, parameterTypes.map(CharSequence::toString), colors, stops)
}

private fun Method.isRingBuilder() = AccessFlags.STATIC.isSet(accessFlags) && returnType == LINEAR_GRADIENT &&
    parameterTypes.map(CharSequence::toString) == GRADIENT_PARAMETERS

private fun Method.isRingSetter() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
    parameterTypes.map(CharSequence::toString) == SETTER_PARAMETERS

private fun Method.makesGradientFromArrays() =
    implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == GRADIENT_FROM_ARRAYS } == true

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
 * Passes each builder's colors and stops through [RING_COLORS] first thing, and builds the
 * gradient from the answer. The ring's own fields are left alone, so Instagram's colors are what
 * the hook is given every time, and a ring whose hook answers them back is exactly Instagram's.
 */
internal fun BytecodePatchContext.colorRingGradients(gradients: List<RingGradient>) {
    val spinner = mutableClassDefBy(GRADIENT_SPINNER)
    for (gradient in gradients) {
        val builder = spinner.methods.single {
            it.name == gradient.name && it.parameterTypes.map(CharSequence::toString) == gradient.parameters
        }
        builder.addInstructions(
            0,
            """
                invoke-static { v${gradient.colors}, v${gradient.stops} }, $RING_COLORS
                move-result-object v${gradient.colors}
            """,
        )
    }
}
