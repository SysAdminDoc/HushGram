/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.autoadvance

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.loop.findStoryLoop
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Stop Story auto-advance"
internal const val HOLD = "$EXTENSION_PACKAGE/stories/StoryAdvance;->hold()Z"
internal const val HOLD_UNLESS_IT_LOOPS = "$EXTENSION_PACKAGE/stories/StoryAdvance;->holdUnlessItLoops()Z"
private const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"

@Suppress("unused")
val stopStoryAutoAdvancePatch = bytecodePatch(
    name = "Stop Story auto-advance",
    description = "Keeps each story on screen until you tap or swipe. Off until you turn it on. Switch: HushGram " +
        "settings > Stories.",
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        holdFinishedStories()
        enableStatus("storyAutoAdvance")
    }
}

/**
 * Asks the extension first thing in the story viewer's handler for a finished story item, and
 * returns before the viewer moves on when it says to hold. Taps and swipes move the viewer through
 * other methods, so they still work.
 *
 * With Loop a story looping stories too, the extension says so through [HOLD_UNLESS_IT_LOOPS], and
 * the guard asks the viewer's own loop check ([loopCheck]) about the item: yes goes on into the
 * handler, where Instagram starts the item over, and no is held. The check's answer is used on the
 * spot for the item in hand.
 */
internal fun BytecodePatchContext.holdFinishedStories() {
    uniqueMethod(PATCH, "finished story handler", StoryItemDoneFingerprint).apply {
        // The handler's first act is to cast the item it was handed to a story item. Anything else
        // there means the fingerprint found some other bridge.
        val first = getInstruction(0)
        if (first.opcode != Opcode.CHECK_CAST || (first as ReferenceInstruction).reference.toString() != REEL_ITEM) {
            throw PatchException("$PATCH: $definingClass->$name doesn't start by casting its item to $REEL_ITEM")
        }
        val check = loopCheck(this)
        requireLocals(PATCH, 1)
        // p0 and p1 are next to each other, so the range call holds wherever the registers land.
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $HOLD
                move-result v0
                if-nez v0, :hold
                invoke-static { }, $HOLD_UNLESS_IT_LOOPS
                move-result v0
                if-eqz v0, :advance
                check-cast p1, $REEL_ITEM
                invoke-direct/range { p0 .. p1 }, $check
                move-result v0
                if-nez v0, :advance
                :hold
                return-void
            """,
            ExternalLabel("advance", first),
        )
    }
}

/**
 * The viewer's private check of whether a story item plays again, as the finished story handler
 * asks it: the one call in the handler's own code to a private instance method of the viewer
 * taking the story item and answering a boolean. Loop a story answers that check's flag and proves
 * from the flag that the handler asks it, so with both patches in a build the two name the same
 * method. Its flag read is proved even when Loop isn't selected. Fails before anything changes
 * when there isn't exactly one such call or it isn't the actual loop check.
 */
internal fun BytecodePatchContext.loopCheck(handler: Method): String {
    val calls = instagramCode(handler).mapNotNull { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called?.takeIf {
            (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                it.definingClass == STORY_VIEWER && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(REEL_ITEM)
        }
    }
    val where = "${handler.definingClass}->${handler.name}"
    val call = calls.singleOrNull()
        ?: throw PatchException("$PATCH: expected $where to ask one private check of the story item, found ${calls.size}")
    val target = classDefBy(STORY_VIEWER).methods.singleOrNull {
        it.name == call.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(REEL_ITEM)
    }
    if (target == null || !AccessFlags.PRIVATE.isSet(target.accessFlags) || AccessFlags.STATIC.isSet(target.accessFlags) ||
        AccessFlags.NATIVE.isSet(target.accessFlags) || AccessFlags.ABSTRACT.isSet(target.accessFlags)
    ) {
        throw PatchException("$PATCH: $where asks $STORY_VIEWER->${call.name}($REEL_ITEM)Z, which isn't a private instance method of the viewer")
    }
    val read = findStoryLoop()
    if (read.type != target.definingClass || read.name != target.name || read.parameters != listOf(REEL_ITEM)) {
        throw PatchException("$PATCH: $where asks ${call.name}, which doesn't read the story loop flag")
    }
    return "$STORY_VIEWER->${call.name}($REEL_ITEM)Z"
}

/**
 * [handler]'s instructions as Instagram wrote them, without the guard [holdFinishedStories] puts
 * in front of them. The guard starts by asking [HOLD] and ends at its own return-void, and it asks
 * the loop check itself, so a count of what the handler asks reads past it. That lets Stop and Loop
 * patch a build in either order.
 */
internal fun instagramCode(handler: Method): List<Instruction> {
    val code = handler.implementation?.instructions?.toList().orEmpty()
    val guarded = ((code.firstOrNull() as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == HOLD
    if (!guarded) return code
    return code.drop(code.indexOfFirst { it.opcode == Opcode.RETURN_VOID } + 1)
}
