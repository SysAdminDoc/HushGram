/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.autoscroll

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.feed.seekbar.COVER_REPORT
import app.morphe.patches.instagram.media.resume.PLAYBACK_COMPLETED
import app.morphe.patches.instagram.media.resume.PLAYBACK_LOOPING
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Auto scroll in Home"

internal const val FEED_AUTO_SCROLL = "$EXTENSION_PACKAGE/feed/FeedAutoScroll;"
internal const val LOOPED_HOOK = "$FEED_AUTO_SCROLL->looped(Ljava/lang/Object;I)V"
internal const val ENDED_HOOK = "$FEED_AUTO_SCROLL->ended(Ljava/lang/Object;)V"
internal const val ATTACH_HOOK = "$FEED_AUTO_SCROLL->attach(Landroid/view/View;)V"
internal const val TOUCH_HOOK = "$FEED_AUTO_SCROLL->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)V"

/** Home's list, which Redex leaves under its own name. */
internal const val HOME_LIST = "Lcom/instagram/common/ui/widget/recyclerview/MainFeedRecyclerView;"

private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private val INTERFACE_CALLS = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)

/**
 * Where Auto scroll in Home hooks in, found: the feed video player [player] and its callbacks for a
 * video starting over ([looped], taking the count) and ending ([ended]); Home's list constructors,
 * by their parameters ([constructors]); and where in IgFragmentActivity's dispatchTouchEvent the
 * touch hook goes ([touchAt]).
 */
internal class FeedAutoScrollSites(
    val player: String,
    val looped: String,
    val ended: String,
    val constructors: List<List<String>>,
    val touchAt: Int,
) {
    /**
     * First thing in each player callback, hands the player (and the count) to the extension. Before
     * each return of each of Home's list constructors, hands the new list over; the hook takes the
     * return's label, so a branch to the return runs it too. First thing in the touch dispatch, or
     * right after Tap to play's call when that's first, hands the activity and the event over.
     */
    fun write(context: BytecodePatchContext) {
        val playerClass = context.mutableClassDefBy(player)
        playerClass.methods.single { it.isInstance() && it.name == looped && it.shape() == "(I)V" }
            .addInstructions(0, "invoke-static/range { p0 .. p1 }, $LOOPED_HOOK")
        playerClass.methods.single { it.isInstance() && it.name == ended && it.shape() == "()V" }
            .addInstructions(0, "invoke-static/range { p0 .. p0 }, $ENDED_HOOK")

        val list = context.mutableClassDefBy(HOME_LIST)
        for (parameters in constructors) {
            val constructor = list.methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == parameters }
            for (at in constructor.returns().sortedDescending()) {
                constructor.addInstructionsAtControlFlowLabel(at, "invoke-static/range { p0 .. p0 }, $ATTACH_HOOK")
            }
        }

        touchOf(context.mutableClassDefBy(FRAGMENT_ACTIVITY).methods)
            .addInstructions(touchAt, "invoke-static/range { p0 .. p1 }, $TOUCH_HOOK")
    }
}

/**
 * Finds everything before anything changes:
 *
 * - the one instance method loading [PLAYBACK_LOOPING], IgVideoPlayerImpl's callback for a video
 *   starting over, and the one interface (int) call it makes, which tells the player's listener;
 * - the one instance method of that class loading [PLAYBACK_COMPLETED], its callback for a video that
 *   ended, and the one no-argument interface call it makes on that listener;
 * - the one instance (int, int, boolean) method loading [COVER_REPORT], the feed video player
 *   IgFeedVideoPlayer's progress callback, whose class has to take the listener's calls and
 *   declare both of them itself, with nothing jumping back to their start;
 * - [HOME_LIST] and its constructors, each returning and keeping `this` in its register to every
 *   return;
 * - [FRAGMENT_ACTIVITY]'s one dispatchTouchEvent, with nothing jumping to where the hook goes.
 *
 * None of it may call the extension yet. Anything else is a build this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findFeedAutoScroll(): FeedAutoScrollSites {
    val loopers = classesLoadingString(PLAYBACK_LOOPING).flatMap { classDef ->
        // Instance methods only: where Redex pools the string, the pool's own static method loads it too.
        classDef.methods.filter { it.isInstance() && it.implementation != null && loadsString(it, PLAYBACK_LOOPING) }
    }
    val looper = loopers.singleOrNull() ?: refuse("expected one method loading \"$PLAYBACK_LOOPING\", found ${loopers.size}")
    val loopCalls = looper.code().filter { it.opcode in INTERFACE_CALLS }.mapNotNull { it.method() }
        .filter { it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == "V" }.distinctBy(Any::toString)
    val loopCall = loopCalls.singleOrNull()
        ?: refuse("expected ${looper.definingClass}->${looper.name} to make one interface (int) call, found ${loopCalls.size}")
    val listener = loopCall.definingClass

    val impl = classDefByOrNull(looper.definingClass) ?: refuse("this build has no ${looper.definingClass}")
    val enders = impl.methods.filter { it.isInstance() && it.implementation != null && loadsString(it, PLAYBACK_COMPLETED) }
    val ender = enders.singleOrNull()
        ?: refuse("expected one method of ${impl.type} loading \"$PLAYBACK_COMPLETED\", found ${enders.size}")
    val endCalls = ender.code().filter { it.opcode in INTERFACE_CALLS }.mapNotNull { it.method() }
        .filter { it.definingClass == listener && it.parameterTypes.isEmpty() && it.returnType == "V" }.distinctBy(Any::toString)
    val endCall = endCalls.singleOrNull()
        ?: refuse("expected ${impl.type}->${ender.name} to make one no-argument call on $listener, found ${endCalls.size}")

    val callbacks = classesLoadingString(COVER_REPORT).flatMap { classDef ->
        classDef.methods.filter { it.isInstance() && it.shape() == "(IIZ)V" && loadsString(it, COVER_REPORT) }
    }
    val callback = callbacks.singleOrNull()
        ?: refuse("expected one instance (int, int, boolean) method loading \"$COVER_REPORT\", found ${callbacks.size}")
    val player = classDefByOrNull(callback.definingClass) ?: refuse("this build has no ${callback.definingClass}")
    if (!takesCallsOf(player, listener)) refuse("${player.type}, the feed video player, isn't a $listener")
    val looped = player.methods.singleOrNull { it.isInstance() && it.implementation != null && it.name == loopCall.name && it.shape() == "(I)V" }
        ?: refuse("${player.type} has no ${loopCall.name}(I)V of its own")
    val ended = player.methods.singleOrNull { it.isInstance() && it.implementation != null && it.name == endCall.name && it.shape() == "()V" }
        ?: refuse("${player.type} has no ${endCall.name}()V of its own")
    for (method in listOf(looped, ended)) {
        if (method.callsExtension()) refuse("${player.type}->${method.name} already calls $FEED_AUTO_SCROLL")
        if (0 in method.jumpTargets()) refuse("something in ${player.type}->${method.name} jumps back to its start")
    }

    val list = classDefByOrNull(HOME_LIST) ?: refuse("this build has no $HOME_LIST")
    val constructors = list.methods.filter { it.name == "<init>" && it.implementation != null }
    if (constructors.isEmpty()) refuse("$HOME_LIST has no constructor")
    for (constructor in constructors) {
        val described = "$HOME_LIST-><init>${constructor.shape()}"
        if (constructor.callsExtension()) refuse("$described already calls $FEED_AUTO_SCROLL")
        val returns = constructor.returns()
        if (returns.isEmpty()) refuse("$described never returns")
        constructor.requireThisIntact(PATCH, returns)
    }

    val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: refuse("this build has no $FRAGMENT_ACTIVITY")
    val touch = touchOf(activity.methods)
    if (touch.callsExtension()) refuse("$FRAGMENT_ACTIVITY->dispatchTouchEvent already calls $FEED_AUTO_SCROLL")
    val touchAt = touch.touchHookAt()
    if (touchAt in touch.jumpTargets()) refuse("something in $FRAGMENT_ACTIVITY->dispatchTouchEvent jumps to where the hook goes")

    return FeedAutoScrollSites(
        player.type,
        looped.name,
        ended.name,
        constructors.map { constructor -> constructor.parameterTypes.map(Any::toString) },
        touchAt,
    )
}

/** [FRAGMENT_ACTIVITY]'s one dispatchTouchEvent of its own among [methods]. */
private fun <M : Method> touchOf(methods: Iterable<M>): M {
    val touches = methods.filter {
        it.name == "dispatchTouchEvent" && it.returnType == "Z" &&
            it.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT) && it.implementation != null
    }
    return touches.singleOrNull()
        ?: refuse("expected $FRAGMENT_ACTIVITY to have one dispatchTouchEvent of its own, found ${touches.size}")
}

/** Where the touch hook goes: right after Tap to play's call when that's first, else first. */
private fun Method.touchHookAt(): Int {
    val first = code().firstOrNull()?.method() ?: return 0
    return if ("${first.definingClass}->${first.name}(${first.parameterTypes.joinToString("")})${first.returnType}" == TAP_CLOCK) 1 else 0
}

/** Whether [classDef], a class above it or an interface of any of them is [type]. */
private fun BytecodePatchContext.takesCallsOf(classDef: ClassDef, type: String): Boolean {
    val seen = HashSet<String>()
    val pending = ArrayDeque(listOf(classDef.type))
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (next == type) return true
        if (!seen.add(next)) continue
        val found = classDefByOrNull(next) ?: continue
        found.superclass?.let { pending += it }
        pending += found.interfaces
    }
    return false
}

private fun Method.returns(): List<Int> {
    val code = code()
    return code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
}

private fun Method.callsExtension(): Boolean = code().any { it.method()?.definingClass == FEED_AUTO_SCROLL }

private fun Method.isInstance(): Boolean = !AccessFlags.STATIC.isSet(accessFlags)

private fun Method.shape(): String = "(${parameterTypes.joinToString("")})$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
