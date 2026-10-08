/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val STORY_SEEN_RINGS = "$EXTENSION_PACKAGE/stories/StorySeenRings;"
internal const val RING_SEEN = "$STORY_SEEN_RINGS->seen(Ljava/lang/Object;Ljava/lang/Object;JLjava/lang/Object;)V"

/** The trace section Instagram's store of the stories seen on the phone saves itself under, held by its constructor. */
internal const val LOCAL_SEEN_STORE = "LocalReelSeenStateSerialize"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

private fun refuseRings(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * What keeping a watched story's ring new works on, found before anything changes.
 *
 * The story viewer, the method [viewerName] taking [viewerParameters] on [viewer], writes the time
 * of the story it's showing into the reel at instruction [at], through the reel's own write
 * [write] (on [reelClass]): the reel in register [reel], the account in [session], the time in
 * [time] and the one after it, read right before from the story in [item]. [markSeen] is the
 * extension's stub that makes the write when the hook lets it.
 */
internal class StoryRingTargets(
    val viewer: String,
    val viewerName: String,
    val viewerParameters: List<String>,
    val viewerReturn: String,
    val at: Int,
    val reel: Int,
    val session: Int,
    val time: Int,
    val item: Int,
    val reelClass: String,
    val write: String,
    internal val markSeen: MutableMethod,
)

/**
 * Finds the story viewer's write of what you've watched into the store that greys a ring.
 *
 * Instagram keeps a seen time per reel on the phone, in a store it saves to disk (its constructor,
 * the one taking the account and the store's state, holds [LOCAL_SEEN_STORE]). The state has one
 * static write of a reel's time. Besides the store's own (resetting a NUX), its one caller is the
 * reel's write of its seen time for an account, which only moves the time forward. That write is
 * called when Instagram reads a tray from the server, with the time the server sent, and from the
 * story viewer, with the time of the story it's showing, read off the story right before. The
 * viewer's call is the one whose time comes from a story getter, and there must be exactly one.
 */
internal fun BytecodePatchContext.findStoryRings(): StoryRingTargets {
    val constructors = classesLoadingString(LOCAL_SEEN_STORE).flatMap { classDef ->
        classDef.methods.filter { method ->
            val parameters = method.parameterTypes.map(Any::toString)
            method.name == "<init>" && parameters.size == 2 && parameters[0] == USER_SESSION &&
                parameters[1].startsWith("L") && parameters[1] != USER_SESSION
        }
    }
    val store = constructors.singleOrNull()
        ?: refuseRings("expected one class loading \"$LOCAL_SEEN_STORE\" made from an account and its state, found ${constructors.size}")
    val state = store.parameterTypes[1].toString()
    val stateClass = classDefByOrNull(state) ?: refuseRings("$state, the state of the stories seen on the phone, isn't in this build")
    val stateWrites = stateClass.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(state, STRING, "J")
    }
    val stateWrite = stateWrites.singleOrNull()
        ?: refuseRings("expected $state to have one static write of a reel's seen time, found ${stateWrites.size}")

    val reelWrites = classesCalling(state, stateWrite.name)
        .filter { it.type != store.definingClass && it.type != state }
        .flatMap { classDef -> classDef.methods.filter { method -> method.body().any { it.calledMethod()?.sameCall(stateWrite) == true } } }
    val reelWrite = reelWrites.singleOrNull()
        ?: refuseRings("expected one write of a reel's seen time calling $state->${stateWrite.name}, found ${reelWrites.size}")
    if (AccessFlags.STATIC.isSet(reelWrite.accessFlags) || reelWrite.returnType != "V" ||
        reelWrite.parameterTypes.map(Any::toString) != listOf(USER_SESSION, "J")
    ) {
        refuseRings("${reelWrite.descriptor()}, which writes a reel's seen time, isn't the reel's write for an account")
    }
    val reelClass = classDefByOrNull(reelWrite.definingClass)
        ?: refuseRings("${reelWrite.definingClass}, the reel, isn't in this build")
    if (!AccessFlags.PUBLIC.isSet(reelClass.accessFlags) || AccessFlags.INTERFACE.isSet(reelClass.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(reelWrite.accessFlags)
    ) {
        refuseRings("${reelWrite.descriptor()}, the reel's write of its seen time, isn't public for the extension to call")
    }

    val sites = classesCalling(reelWrite.definingClass, reelWrite.name).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.body()
            code.indices.filter { at -> code[at].calledMethod()?.sameCall(reelWrite) == true && code.storyTimeAt(at) }.map { method to it }
        }
    }
    val (viewer, at) = sites.singleOrNull()
        ?: refuseRings("expected the story viewer to write one story's time as its reel's seen time, found ${sites.size}")
    val code = viewer.body()
    if (REEL_ITEM !in viewer.parameterTypes.map(Any::toString)) {
        refuseRings("${viewer.descriptor()}, which writes a story's time as its reel's seen time, isn't handed the story")
    }
    val call = code[at] as? FiveRegisterInstruction
        ?: refuseRings("${viewer.descriptor()} writes the reel's seen time with a range call, which the hook can't take the story into")
    val getter = code[at - 2] as? FiveRegisterInstruction
        ?: refuseRings("${viewer.descriptor()} reads the story's time with a range call")
    val time = (code[at - 1] as OneRegisterInstruction).registerA
    if (call.registerCount != 4 || call.registerE != time || call.registerF != time + 1) {
        refuseRings("${viewer.descriptor()} doesn't write the story's time it just read at instruction $at")
    }
    val item = getter.registerC
    if (item == time || item == time + 1) {
        refuseRings("${viewer.descriptor()} reads the story's time over the story (v$item) before the write at instruction $at")
    }
    if (at in viewer.jumpTargets()) {
        refuseRings("a branch lands on ${viewer.descriptor()}'s write of the seen time at instruction $at, past the story's time")
    }
    return StoryRingTargets(
        viewer = viewer.definingClass,
        viewerName = viewer.name,
        viewerParameters = viewer.parameterTypes.map(Any::toString),
        viewerReturn = viewer.returnType,
        at = at,
        reel = call.registerC,
        session = call.registerD,
        time = time,
        item = item,
        reelClass = reelWrite.definingClass,
        write = reelWrite.descriptor(),
        markSeen = storyRingStub(),
    )
}

/** Whether a story's time, read by a getter on the story taking nothing, goes into the call at [at] right before it. */
private fun List<Instruction>.storyTimeAt(at: Int): Boolean {
    if (at < 2 || this[at].opcode !in VIRTUAL_CALLS || this[at - 1].opcode != Opcode.MOVE_RESULT_WIDE) return false
    val getter = this[at - 2].calledMethod() ?: return false
    return this[at - 2].opcode in VIRTUAL_CALLS && getter.definingClass == REEL_ITEM && getter.parameterTypes.isEmpty() &&
        getter.returnType == "J"
}

/** The extension's stub making Instagram's write, after checking the hook the patch calls is there. */
private fun BytecodePatchContext.storyRingStub(): MutableMethod {
    val rings = classDefByOrNull(STORY_SEEN_RINGS)?.let { mutableClassDefBy(STORY_SEEN_RINGS) } ?: refuseRings("$STORY_SEEN_RINGS isn't in the extension")
    rings.methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == RING_SEEN.substringAfter("->") &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } ?: refuseRings("$STORY_SEEN_RINGS has no public static hook ${RING_SEEN.substringAfter("->")}")
    return rings.methods.singleOrNull {
        it.name == "markSeen" && it.returnType == "V" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(OBJECT, OBJECT, "J")
    } ?: refuseRings("$STORY_SEEN_RINGS has no static V markSeen(${OBJECT}${OBJECT}J)")
}

/**
 * Hands the story viewer's write of a reel's seen time to the extension, with the story it's
 * showing, in the call's own place: the extension skips it while views are held back and the story
 * hasn't been sent as seen, and makes it otherwise. Instagram's tray reads, which write the time the
 * server sent, stay as they are. The stub makes the write through the reel's own method.
 */
internal fun BytecodePatchContext.keepStoriesNew(found: StoryRingTargets) {
    val viewer = mutableClassDefBy(found.viewer).methods.single {
        it.name == found.viewerName && it.parameterTypes.map(Any::toString) == found.viewerParameters && it.returnType == found.viewerReturn
    }
    val write = viewer.body().getOrNull(found.at)?.calledMethod()?.descriptor()
    if (write != found.write) throw PatchException("$PATCH: ${found.viewer}->${found.viewerName} changed before its write of the seen time was hooked")
    viewer.replaceInstruction(
        found.at,
        "invoke-static { v${found.reel}, v${found.session}, v${found.time}, v${found.time + 1}, v${found.item} }, $RING_SEEN",
    )
    found.markSeen.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.reelClass}
            check-cast p1, $USER_SESSION
            invoke-virtual { p0, p1, p2, p3 }, ${found.write}
            return-void
        """,
    )
}

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.calledMethod(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.descriptor(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.sameCall(other: MethodReference): Boolean = descriptor() == other.descriptor()
