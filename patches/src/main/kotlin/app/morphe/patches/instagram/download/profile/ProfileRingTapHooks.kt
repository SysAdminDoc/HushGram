/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.flags.FlagRead
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.instagram.misc.flags.findFlagReads

/** The extension's side of A story ring opens the story (#823). */
internal const val RING_TAP = "$EXTENSION_PACKAGE/download/ProfileRingTap;"
internal const val RING_TAP_HOOK = "$RING_TAP->storyFirst(I)Z"

/**
 * The server flag the profile picture's tap reads before it looks for the account's story in
 * Instagram's own store of stories, when the header's state says to show the picture. Off, a tap
 * on a ringed picture opens the picture until the header has the story. 0x81172b00007a9f on 456.1.
 */
internal const val STORY_FIRST_FLAG = 0x81170500007b6aL

/** What the tap logs, which names the method on every 456 and 457 build. */
internal const val TAP_PICTURE = "tap_profile_pic"
internal const val PROFILE_HEADER = "user_profile_header"

private const val RING_PATCH = "$PROFILE_PICTURE_PATCH: A story ring opens the story"

/**
 * Finds the profile picture tap's one read of [STORY_FIRST_FLAG], or null, after the patch log says
 * why, so the rest of Save profile picture goes in without it. The read is the flag's only read in
 * the method loading both [TAP_PICTURE] and [PROFILE_HEADER].
 */
internal fun BytecodePatchContext.findRingTap(): FlagRead? {
    fun skip(why: String): FlagRead? {
        patchLog.warning("$RING_PATCH: $why. The patch goes in without it.")
        return null
    }
    classDefByOrNull(RING_TAP) ?: throw PatchException("$RING_PATCH: the extension has no $RING_TAP")
    val reads = try {
        findFlagReads(RING_PATCH, listOf(STORY_FIRST_FLAG))
    } catch (changed: PatchException) {
        return skip(changed.message ?: "the flag's read changed")
    }
    val inTap = reads.filter { read ->
        val method = classDefBy(read.type).methods.single {
            it.name == read.name && it.returnType == read.returnType &&
                it.parameterTypes.map(CharSequence::toString) == read.parameters
        }
        loadsString(method, TAP_PICTURE) && loadsString(method, PROFILE_HEADER)
    }
    return when (inTap.size) {
        1 -> inTap.single()
        0 -> skip("no profile picture tap reads ${STORY_FIRST_FLAG.toString(16)}")
        else -> skip("${inTap.size} profile picture taps read ${STORY_FIRST_FLAG.toString(16)}")
    }
}

/** Passes the tap's read of the flag through [RING_TAP_HOOK], which answers yes while the switch is on. */
internal fun BytecodePatchContext.applyRingTap(read: FlagRead) = answerFlagReads(listOf(read), RING_TAP_HOOK)
