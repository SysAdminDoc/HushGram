/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.loop

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.instagram.stories.autoadvance.StoryItemDoneFingerprint
import app.morphe.patches.instagram.stories.autoadvance.instagramCode
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Loop a story"
internal const val STORY_LOOP = "$EXTENSION_PACKAGE/stories/StoryLoop;"
internal const val LOOP = "$STORY_LOOP->loop(I)Z"

/** A story, photo or video, as the story viewer holds it. Redex keeps its name. */
private const val STORY_ITEM = "Lcom/instagram/model/reels/ReelItem;"

/**
 * The server flag behind Instagram's own story loop test. On 450 it's read once, in the story
 * viewer's private check of whether a story item plays again, after the check has already said no
 * for an ad and for a few special kinds of story.
 */
internal const val STORY_LOOP_FLAG = 0x8110170001571aL

/**
 * Plays a story again when it ends instead of moving on. In the default selection with its switch
 * off, since moving on is how stories work. It answers Instagram's own loop test rather than rewinding
 * anything itself: the same check decides whether a video loops in the player and whether a photo
 * that's done starts over, so both get Instagram's own handling.
 */
@Suppress("unused")
val loopStoryPatch = bytecodePatch(
    name = "Loop a story",
    description = "A story plays again from the start when it ends, instead of moving on to the next one. Tap or " +
        "swipe to move on. Off until you turn it on. Switch: HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyLoop")
        loopStories()
        enableStatus("storyLoop")
    }
}

/** Finds the loop test's read with [findStoryLoop] and has the extension answer it through [LOOP]. */
internal fun BytecodePatchContext.loopStories() {
    val read = findStoryLoop()
    answerFlagLoads(listOf(read to LOOP))
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the one read of [STORY_LOOP_FLAG]. It has to be the flag's own read, in a method of the
 * story viewer taking one story item and answering a boolean, and the viewer's handler for a
 * finished story item (the one Stop Story auto-advance guards) has to ask that method exactly once,
 * since that's where a photo starts over. Fails before anything changes when any of it isn't so,
 * since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryLoop(): FlagLoad {
    val done = uniqueMethod(PATCH, "finished story handler", StoryItemDoneFingerprint)
    val flag = STORY_LOOP_FLAG.toString(16)
    val reads = findFlagLoads(PATCH, STORY_LOOP_FLAG, "Z")
    val read = reads.singleOrNull() ?: refuse("expected one read of the story loop flag $flag, found ${reads.size}")
    val where = "${read.type}->${read.name}"
    if (read.shared) refuse("the read of $flag in $where is shared with another flag")
    if (read.type != STORY_VIEWER || read.parameters != listOf(STORY_ITEM) || read.returnType != "Z") {
        refuse("$flag is read in $where(${read.parameters.joinToString("")})${read.returnType}, not in the story viewer's check of one story item")
    }
    // Stop Story auto-advance's guard asks the check too when both are on; only Instagram's own ask counts.
    val asks = instagramCode(done).count {
        val called = (it as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == STORY_VIEWER && called.name == read.name && called.returnType == "Z" &&
            called.parameterTypes.map(CharSequence::toString) == listOf(STORY_ITEM)
    }
    if (asks != 1) refuse("expected the finished story handler $STORY_VIEWER->${done.name} to ask $where once, found $asks")
    return read
}
