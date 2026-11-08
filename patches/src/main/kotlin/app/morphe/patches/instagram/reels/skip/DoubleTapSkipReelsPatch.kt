/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.skip

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Double tap to skip on Reels"
internal const val REEL_SKIP = "$EXTENSION_PACKAGE/reels/ReelSkip;"
internal const val SKIP = "$REEL_SKIP->skip(I)Z"
internal const val SECONDS = "$REEL_SKIP->seconds(F)F"
internal const val SECONDS_DOUBLE = "$REEL_SKIP->secondsDouble(D)D"
internal const val EDGE = "$REEL_SKIP->edge(F)F"
internal const val EDGE_DOUBLE = "$REEL_SKIP->edgeDouble(D)D"

/**
 * Whether a reel gets double tap to skip, read by DoubleTapToSkipUseCase_getUiState for each reel
 * (05KP.A0V on 454's 386014758). Off on the accounts that don't have it, which is most of them.
 */
internal const val SKIP_FLAG = 0x811254000164c9L

/**
 * How far a double tap skips, in seconds: read where the gesture navigator works out a double tap
 * (ClipsGestureNavigator_onDoubleTapToSkip, 0DXI.invoke on 386014758) and again by the use case.
 */
internal const val SECONDS_FLAG = 0x84125400020474L

/** How wide the strip along each edge is, as a share of the reel's width. Read by the navigator alone. */
internal const val EDGE_FLAG = 0x84125400000473L

/** What a fraction is read as: a float on some 454 builds, a double on others. */
private val FRACTION = setOf("F", "D")

/**
 * Turns on Instagram's own double tap to skip on Reels, which it keeps behind a server flag, and
 * answers its two numbers: 10 seconds a skip, and strips a third of the reel wide unless
 * Instagram's own leave a middle for the like. Read on each reel and each double tap, so a change
 * shows on the next reel. In the default selection with its switch off.
 */
@Suppress("unused")
val doubleTapSkipReelsPatch = bytecodePatch(
    name = "Double tap to skip on Reels",
    description = "A double tap on the left side of a reel goes back 10 seconds and on the right side goes ahead " +
        "10 seconds, with Instagram's own skip. A double tap in the middle still likes it. Starts off. " +
        "Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelDoubleTapSkip")
        skipReels()
        enableStatus("reelDoubleTapSkip")
    }
}

/** Finds every read with [findReelSkip] and has the extension answer each. */
internal fun BytecodePatchContext.skipReels() {
    answerFlagLoads(findReelSkip())
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Every read of [SKIP_FLAG], [SECONDS_FLAG] and [EDGE_FLAG], each with the hook that answers it.
 * Each flag has to be read at least once, and the edge where the seconds are read too, which is
 * where a double tap is worked out. Fails before anything changes when any of it isn't so, since
 * that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findReelSkip(): List<Pair<FlagLoad, String>> {
    val skips = findFlagLoads(PATCH, SKIP_FLAG, "Z")
    val seconds = findFlagLoads(PATCH, SECONDS_FLAG, FRACTION)
    val edges = findFlagLoads(PATCH, EDGE_FLAG, FRACTION)
    for ((loads, flag) in listOf(skips to SKIP_FLAG, seconds to SECONDS_FLAG, edges to EDGE_FLAG)) {
        if (loads.isEmpty()) refuse("nothing reads ${flag.toString(16)}")
    }
    fun FlagLoad.where() = Triple(type, name, parameters)
    val secondsRead = seconds.map { it.where() }.toSet()
    edges.forEach { edge ->
        if (edge.where() !in secondsRead) {
            refuse("${edge.type}->${edge.name} reads ${EDGE_FLAG.toString(16)} without ${SECONDS_FLAG.toString(16)}")
        }
    }
    return skips.map { it to SKIP } +
        seconds.map { it to if (it.answer == "D") SECONDS_DOUBLE else SECONDS } +
        edges.map { it to if (it.answer == "D") EDGE_DOUBLE else EDGE }
}
