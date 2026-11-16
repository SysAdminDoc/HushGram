/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.hold

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Keep 2x after you let go (#552). On 454's 386014758 the feed's 2x lives in one controller per feed
 * (07kE): an AtomicBoolean that says the video plays fast, and a static (controller, boolean)
 * boolean reset (07kE.A01) that clears it, puts the player back to 1x and answers whether it was
 * fast. The press entry calls the reset first thing, then sets the flag with compareAndSet and the
 * speed to 2x. The release (02g8.A09) calls the reset, and an answer of yes keeps the lift from
 * opening the post in Reels. The patch asks the extension before the release's reset whether this
 * lift keeps the speed, and a yes skips the reset with Instagram's own "it was fast" answer. The
 * press entry tells the extension before its own reset, so it can forget a kept flag Instagram has
 * since cleared (the video left the screen) and the next lift is a fresh one.
 */

internal const val LOCK_PRESSED = "$FEED_HOLD->pressed(Ljava/util/concurrent/atomic/AtomicBoolean;)V"
internal const val LOCK_LIFTED = "$FEED_HOLD->lifted(Ljava/util/concurrent/atomic/AtomicBoolean;)Z"

/** What the feed's 2x controller logs when putting the speed back fails, loaded in its reset. */
internal const val SPEED_RESET_TAG = "FeedVideo2xSpeed"

private const val LOCK_PATCH = "Hold for 2x on feed videos"
private const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"
private const val GET_AND_SET = "$ATOMIC_BOOLEAN->getAndSet(Z)Z"
private const val COMPARE_AND_SET = "$ATOMIC_BOOLEAN->compareAndSet(ZZ)Z"
private const val FEED_MEDIA = "Lcom/instagram/feed/media/Media;"

/**
 * The press entry and the release of the feed's hold, the controller's speed reset both call
 * ([reset], as "type->name(params)return") and the controller's fast flag ([fast], as
 * "type->name:type").
 */
internal class FeedLock(
    val press: Method,
    val release: Method,
    val reset: String,
    val fast: String,
)

/**
 * Where code goes in front of a method's one call to the reset: the call's index [at], the
 * controller's register, a local [free] the code may borrow there, and the register the call's
 * answer goes to ([result]), when it is kept.
 */
private class ResetCall(val at: Int, val controller: Int, val free: Int, val result: Int?)

private fun refuseLock(detail: String): Nothing = throw PatchException("$LOCK_PATCH: $detail")

private fun Method.code() = implementation?.instructions?.toList().orEmpty()

private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.reads(field: String) = code().any {
    it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.toString() == field
}

private fun Method.callsVirtual(target: String) = code().any {
    (it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        ((it as ReferenceInstruction).reference as? MethodReference)?.toString() == target
}

/**
 * Finds the speed reset the [press] entry calls, the controller's fast flag and the release, and
 * checks that code can go in front of both calls to the reset. Fails before anything changes
 * when any of it isn't so.
 */
internal fun BytecodePatchContext.findFeedLock(press: Method): FeedLock {
    val where = "${press.definingClass}->${press.name}"
    val tagged = classesLoadingString(SPEED_RESET_TAG).mapTo(HashSet()) { it.type }
    val resets = press.code().filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { call ->
            call.returnType == "Z" && call.definingClass in tagged &&
                call.parameterTypes.map(Any::toString) == listOf(call.definingClass, "Z")
        }
        .distinctBy { it.toString() }
        .mapNotNull { call ->
            classDefByOrNull(call.definingClass)?.methods?.singleOrNull {
                it.name == call.name && it.returnType == "Z" && AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
            }
        }
        .filter { loadsString(it, SPEED_RESET_TAG) }
    val reset = resets.singleOrNull()
        ?: refuseLock("expected $where to call one static speed reset loading \"$SPEED_RESET_TAG\", found ${resets.size}")
    val controller = classDefBy(reset.definingClass)
    val flags = controller.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == ATOMIC_BOOLEAN }
    val flag = flags.singleOrNull()
        ?: refuseLock("expected one $ATOMIC_BOOLEAN field in ${controller.type}, found ${flags.size}")
    val fast = "${flag.definingClass}->${flag.name}:${flag.type}"
    if (!reset.reads(fast) || !reset.callsVirtual(GET_AND_SET)) {
        refuseLock("${reset.signature()} doesn't clear $fast with getAndSet")
    }
    if (!press.reads(fast) || !press.callsVirtual(COMPARE_AND_SET)) {
        refuseLock("$where doesn't set $fast with compareAndSet")
    }

    val p = press.parameterTypes.map(Any::toString)
    val shape = listOf(FEED_MEDIA, p[2], p.last(), "Z")
    val releases = classDefBy(press.definingClass).methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.implementation != null &&
            it.parameterTypes.map(Any::toString) == shape
    }
    val release = releases.singleOrNull()
        ?: refuseLock("expected one press release (${shape.joinToString(", ")}) in ${press.definingClass}, found ${releases.size}")
    val found = FeedLock(press, release, reset.signature(), fast)
    press.planReset(found.reset, keepsAnswer = false)
    release.planReset(found.reset, keepsAnswer = true)
    return found
}

/**
 * The one call to [reset] in this method and what code in front of it reads and may borrow. With
 * [keepsAnswer] the call's answer has to go to a move-result with an instruction after it, which a
 * kept lift jumps to.
 */
private fun Method.planReset(reset: String, keepsAnswer: Boolean): ResetCall {
    val where = "$definingClass->$name"
    val code = code()
    val calls = code.indices.filter { at ->
        (code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            ((code[at] as ReferenceInstruction).reference as? MethodReference)?.toString() == reset
    }
    val at = calls.singleOrNull() ?: refuseLock("expected $where to call $reset once, found ${calls.size}")
    if (at in jumpTargets()) refuseLock("$where jumps straight to its call to $reset")
    val controller = when (val call = code[at]) {
        is RegisterRangeInstruction -> call.startRegister
        is FiveRegisterInstruction -> call.registerC
        else -> refuseLock("$where calls $reset with no registers")
    }
    if (!keepsAnswer) return ResetCall(at, controller, freeLocalsAt(LOCK_PATCH, at, 1).single(), null)
    val result = code.getOrNull(at + 1)
    if (result?.opcode != Opcode.MOVE_RESULT || code.getOrNull(at + 2) == null) {
        refuseLock("$where calls $reset and drops the answer")
    }
    val free = freeLocalsAt(LOCK_PATCH, at, 1, targets = listOf(at + 2)).single()
    return ResetCall(at, controller, free, (result as OneRegisterInstruction).registerA)
}

/** Loads the controller's fast flag into the borrowed local, through it when the controller is above v15. */
private fun ResetCall.flagInto(fast: String): String =
    if (controller <= 15) {
        "iget-object v$free, v$controller, $fast"
    } else {
        "move-object/from16 v$free, v$controller\niget-object v$free, v$free, $fast"
    }

private fun BytecodePatchContext.editable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

/**
 * Tells [LOCK_PRESSED] the fast flag before the press entry's reset, and asks [LOCK_LIFTED] before
 * the release's: a yes skips the reset, its answer set to yes the way a fast lift's is. Each call is
 * found again by its reference, so other patches' code in either method doesn't matter.
 */
internal fun BytecodePatchContext.lockFast(found: FeedLock) {
    val press = editable(found.press)
    val pressed = press.planReset(found.reset, keepsAnswer = false)
    press.addInstructions(
        pressed.at,
        """
            ${pressed.flagInto(found.fast)}
            invoke-static { v${pressed.free} }, $LOCK_PRESSED
        """,
    )

    val release = editable(found.release)
    val lift = release.planReset(found.reset, keepsAnswer = true)
    val result = lift.result!!
    release.addInstructionsWithLabels(
        lift.at,
        """
            ${lift.flagInto(found.fast)}
            invoke-static { v${lift.free} }, $LOCK_LIFTED
            move-result v${lift.free}
            if-eqz v${lift.free}, :reset
            ${if (result <= 15) "const/4" else "const/16"} v$result, 0x1
            goto :kept
        """,
        ExternalLabel("reset", release.getInstruction(lift.at)),
        ExternalLabel("kept", release.getInstruction(lift.at + 2)),
    )
}
