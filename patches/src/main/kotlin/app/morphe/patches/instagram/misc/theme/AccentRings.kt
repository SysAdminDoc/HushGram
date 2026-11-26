/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.stories.ring.ComposeRing
import app.morphe.patches.instagram.stories.ring.GRADIENT_SPINNER
import app.morphe.patches.instagram.stories.ring.RING_COLORS
import app.morphe.patches.instagram.stories.ring.RingGradient
import app.morphe.patches.instagram.stories.ring.colorComposeRing
import app.morphe.patches.instagram.stories.ring.colorRingGradients
import app.morphe.patches.instagram.stories.ring.findComposeRing
import app.morphe.patches.instagram.stories.ring.findRingGradients
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal const val ACCENT_RINGS = "$EXTENSION_PACKAGE/misc/AccentRings;"
internal const val ACCENT_RING_COLORS = "$ACCENT_RINGS->colors([I[F)[I"
internal const val ACCENT_DEFAULT_RING = "$ACCENT_RINGS->composeColors([I)[I"
internal const val ACCENT_SEEN_RING = "$ACCENT_RINGS->seen(I)I"

/** The ring view's setter for the color a seen story's ring is drawn in, under the name Instagram keeps for it. */
internal const val INACTIVE_SETTER = "setInactiveColour"

/**
 * Where Story rings in the accent color reaches the rings the theme doesn't: the ring view's gradients,
 * the Compose default ring, and the register the ring view's [INACTIVE_SETTER] gets its color in.
 */
internal class AccentRingHooks(val gradients: List<RingGradient>, val composeRing: ComposeRing, val seenRegister: Int)

/**
 * Finds the rings Story rings in the accent color has to reach outside the theme (#528). The ring
 * style [applyAccentTheme] lays on each theme reaches only the rings that read their colors from it.
 * Instagram's default ring reads its colors once, from a context no accent theme is laid on, and
 * every ring built from it (on profiles, posts and chats, and the Compose screens' rings) kept
 * Instagram's gradient. They are the same places Story ring size colors, so the same finders read
 * them, and refuse in this patch's name. A seen story's ring is drawn in one gray instead, which
 * every ring view is handed through its [INACTIVE_SETTER] (#187), from its constructor and from the
 * screens that pick a gray of their own. That setter takes one int and is the ring view's only one by
 * that name, and the hook goes first in it, so nothing may jump back to its first instruction.
 */
internal fun BytecodePatchContext.findAccentRings(): AccentRingHooks {
    val declared = classDefByOrNull(ACCENT_RINGS)?.methods?.toList().orEmpty()
        .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    for (hook in listOf(ACCENT_RING_COLORS, ACCENT_DEFAULT_RING, ACCENT_SEEN_RING)) {
        if (hook !in declared) throw PatchException("Accent color: the extension has no public static $hook")
    }
    val gradients = findRingGradients("Accent color")
    val composeRing = findComposeRing("Accent color")
    val setters = classDefByOrNull(GRADIENT_SPINNER)?.methods?.filter { it.isInactiveSetter() }.orEmpty()
    val setter = setters.singleOrNull()
        ?: throw PatchException("Accent color: expected one $INACTIVE_SETTER(I)V in $GRADIENT_SPINNER, found ${setters.size}")
    if (0 in setter.jumpTargets()) {
        throw PatchException("Accent color: something jumps back to the first instruction of $GRADIENT_SPINNER->$INACTIVE_SETTER")
    }
    return AccentRingHooks(gradients, composeRing, setter.parameterRegisterNumber(0))
}

private fun Method.isInactiveSetter() = name == INACTIVE_SETTER && returnType == "V" && implementation != null &&
    !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.map(CharSequence::toString) == listOf("I")

/**
 * Hands the ring view's gradients and the Compose default ring's colors to the extension's accent
 * rings. In the ring view the hook goes behind Story ring size's own guard when that is in already,
 * so its guard stays first in either order and a color picked under Ring color is what this one is
 * given, and leaves alone. The seen ring's gray goes to the extension first thing in
 * [INACTIVE_SETTER], which then keeps the answer.
 */
internal fun BytecodePatchContext.colorAccentRings(hooks: AccentRingHooks) {
    colorRingGradients(hooks.gradients, ACCENT_RING_COLORS, behind = RING_COLORS)
    colorComposeRing(hooks.composeRing, ACCENT_DEFAULT_RING)
    val register = hooks.seenRegister
    mutableClassDefBy(GRADIENT_SPINNER).methods.single { it.isInactiveSetter() }.addInstructions(
        0,
        """
            invoke-static/range { v$register .. v$register }, $ACCENT_SEEN_RING
            move-result v$register
        """,
    )
}
