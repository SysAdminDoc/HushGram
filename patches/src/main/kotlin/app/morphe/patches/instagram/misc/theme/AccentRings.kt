/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.stories.ring.ComposeRing
import app.morphe.patches.instagram.stories.ring.RING_COLORS
import app.morphe.patches.instagram.stories.ring.RingGradient
import app.morphe.patches.instagram.stories.ring.colorComposeRing
import app.morphe.patches.instagram.stories.ring.colorRingGradients
import app.morphe.patches.instagram.stories.ring.findComposeRing
import app.morphe.patches.instagram.stories.ring.findRingGradients
import com.android.tools.smali.dexlib2.AccessFlags

internal const val ACCENT_RINGS = "$EXTENSION_PACKAGE/misc/AccentRings;"
internal const val ACCENT_RING_COLORS = "$ACCENT_RINGS->colors([I[F)[I"
internal const val ACCENT_DEFAULT_RING = "$ACCENT_RINGS->composeColors([I)[I"

/** Where Story rings in the accent color reaches the rings the theme doesn't: the ring view's gradients and the Compose default ring. */
internal class AccentRingHooks(val gradients: List<RingGradient>, val composeRing: ComposeRing)

/**
 * Finds the rings Story rings in the accent color has to reach outside the theme (#528). The ring
 * style [applyAccentTheme] lays on each theme reaches only the rings that read their colors from it.
 * Instagram's default ring reads its colors once, from a context no accent theme is laid on, and
 * every ring built from it (on profiles, posts and chats, and the Compose screens' rings) kept
 * Instagram's gradient. They are the same places Story ring size colors, so the same finders read
 * them, and refuse in this patch's name.
 */
internal fun BytecodePatchContext.findAccentRings(): AccentRingHooks {
    val declared = classDefByOrNull(ACCENT_RINGS)?.methods?.toList().orEmpty()
        .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    for (hook in listOf(ACCENT_RING_COLORS, ACCENT_DEFAULT_RING)) {
        if (hook !in declared) throw PatchException("Accent color: the extension has no public static $hook")
    }
    return AccentRingHooks(findRingGradients("Accent color"), findComposeRing("Accent color"))
}

/**
 * Hands the ring view's gradients and the Compose default ring's colors to the extension's accent
 * rings. In the ring view the hook goes behind Story ring size's own guard when that is in already,
 * so its guard stays first in either order and a color picked under Ring color is what this one is
 * given, and leaves alone.
 */
internal fun BytecodePatchContext.colorAccentRings(hooks: AccentRingHooks) {
    colorRingGradients(hooks.gradients, ACCENT_RING_COLORS, behind = RING_COLORS)
    colorComposeRing(hooks.composeRing, ACCENT_DEFAULT_RING)
}
