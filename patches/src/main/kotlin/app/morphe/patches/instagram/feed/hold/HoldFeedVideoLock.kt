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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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
 *
 * The reset also puts the post's 2x look back to rest (456's 06vJ.A01 reads the feed item the press
 * stored on the controller and calls its A0S(003K) with INACTIVE; the press set ACTIVE, which shows
 * the time and hides the name over the video). A kept lift does just that part of the reset itself,
 * so the name comes back while the video goes on at 2x (#552).
 *
 * Keep the name while you hold (#552): once the press entry has the video at 2x, it hands the same
 * setter the hold state (003K.A03 ACTIVE). The patch asks the extension right before that call, and
 * a yes hands it the resting state instead, so the post keeps its look while the video plays fast.
 */

internal const val LOCK_PRESSED = "$FEED_HOLD->pressed(Ljava/util/concurrent/atomic/AtomicBoolean;)V"
internal const val LOCK_LIFTED = "$FEED_HOLD->lifted(Ljava/util/concurrent/atomic/AtomicBoolean;)Z"
internal const val LOOK_KEPT = "$FEED_HOLD->keepsLook()Z"

/** What the feed's 2x controller logs when putting the speed back fails, loaded in its reset. */
internal const val SPEED_RESET_TAG = "FeedVideo2xSpeed"

private const val LOCK_PATCH = "Hold for 2x on feed videos"
private const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"
private const val GET_AND_SET = "$ATOMIC_BOOLEAN->getAndSet(Z)Z"
private const val COMPARE_AND_SET = "$ATOMIC_BOOLEAN->compareAndSet(ZZ)Z"
private const val FEED_MEDIA = "Lcom/instagram/feed/media/Media;"
private const val LOOK_STATE_ANY = "Ljava/lang/Object;"

/**
 * The press entry and the release of the feed's hold, the controller's speed reset both call
 * ([reset], as "type->name(params)return"), the controller's fast flag ([fast], as
 * "type->name:type"), how the reset puts the post's 2x look to rest ([look]), when it does, and the
 * hold state the press entry hands the post through the same setter ([held], a static field), when
 * it does that once.
 */
internal class FeedLock(
    val press: Method,
    val release: Method,
    val reset: String,
    val fast: String,
    val look: FeedLook?,
    val held: String? = null,
)

/**
 * How the reset puts the post's 2x look to rest: it reads the feed item off the controller ([item],
 * a field), and when there is one hands it the resting state ([rest], a static field of the state's
 * enum) through [show], the item's one-argument setter. 457's x86_64 386314286 inlines that setter:
 * there [holder] is the item's field holding the object the setter called, and [show] that object's
 * method taking the item and the state.
 */
internal data class FeedLook(val item: String, val rest: String, val show: String, val holder: String? = null)

/**
 * Where code goes in front of a method's one call to the reset: the call's index [at], the
 * controller's register, the locals [free] the code may borrow there, and the register the call's
 * answer goes to ([result]), when it is kept.
 */
private class ResetCall(val at: Int, val controller: Int, val free: List<Int>, val result: Int?)

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
    val look = reset.findLook(controller.type)
    val found = FeedLock(press, release, reset.signature(), fast, look, look?.let { press.findHeld(it) })
    press.planReset(found.reset, keepsAnswer = false)
    release.planReset(found.reset, keepsAnswer = true, borrow = found.borrows())
    return found
}

/**
 * The locals a kept lift borrows: the flag's, the resting state's when it puts the look back, and
 * the setter's object's where the item's setter is inlined.
 */
private fun FeedLock.borrows() = when {
    look == null -> 1
    look.holder == null -> 2
    else -> 3
}

/**
 * The reset's one call putting the post's 2x look to rest: a two-register call to a one-argument
 * void method, handed a static field of that argument's own type loaded right before, on a feed item
 * last loaded from a field of the [controller] holding that method's type. Where the item's setter is
 * inlined (457's x86_64), a three-register call to a void method taking the item and an object, on an
 * object of the method's type read off the item right before, handed the item and a static field of
 * an enum's own type loaded before that read. Null when there isn't exactly one such call, so a kept
 * lift only skips the reset.
 */
private fun Method.findLook(controller: String): FeedLook? {
    val code = code()
    return code.indices.mapNotNull { at -> code.lookAt(at, controller) }.distinct().singleOrNull()
}

private fun List<Instruction>.lookAt(at: Int, controller: String): FeedLook? {
    val call = this[at]
    if (call.opcode != Opcode.INVOKE_VIRTUAL) return null
    val show = (call as ReferenceInstruction).reference as? MethodReference ?: return null
    val registers = call as FiveRegisterInstruction
    val parameters = show.parameterTypes.map(Any::toString)
    if (show.returnType != "V") return null
    val holder: FieldReference?
    val itemType: String
    when {
        parameters.size == 1 && registers.registerCount == 2 -> {
            holder = null
            itemType = show.definingClass
        }
        parameters.size == 2 && parameters[1] == LOOK_STATE_ANY && registers.registerCount == 3 -> {
            val read = getOrNull(at - 1) ?: return null
            val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return null
            if (read.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerA != registers.registerC ||
                read.registerB != registers.registerD || field.definingClass != parameters[0] || field.type != show.definingClass
            ) {
                return null
            }
            holder = field
            itemType = parameters[0]
        }
        else -> return null
    }
    val look = FeedLook("", "", show.toString(), holder?.toString())
    val load = getOrNull(look.stateLoadAt(at)) ?: return null
    val rest = (load as? ReferenceInstruction)?.reference as? FieldReference ?: return null
    if (load.opcode != Opcode.SGET_OBJECT || (load as OneRegisterInstruction).registerA != look.stateRegister(call) ||
        rest.definingClass != rest.type || (holder == null && rest.type != parameters.single())
    ) {
        return null
    }
    val itemRegister = if (holder == null) registers.registerC else registers.registerD
    val read = subList(0, at).lastOrNull { it.puts(itemRegister) } ?: return null
    val item = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return null
    if (read.opcode != Opcode.IGET_OBJECT || item.definingClass != controller || item.type != itemType) return null
    return look.copy(item = item.toString(), rest = rest.toString())
}

/** Whether instruction [at] calls [this] look's setter the way the reset does, through the item's holder where it has one. */
private fun FeedLook.calledAt(code: List<Instruction>, at: Int): Boolean {
    val call = code[at]
    if (call.opcode != Opcode.INVOKE_VIRTUAL || ((call as ReferenceInstruction).reference as? MethodReference)?.toString() != show) {
        return false
    }
    val registers = call as FiveRegisterInstruction
    if (holder == null) return registers.registerCount == 2
    val read = code.getOrNull(at - 1)
    return registers.registerCount == 3 && read?.opcode == Opcode.IGET_OBJECT &&
        ((read as ReferenceInstruction).reference as? FieldReference)?.toString() == holder &&
        (read as TwoRegisterInstruction).registerA == registers.registerC && read.registerB == registers.registerD
}

/** The register a call of [this] look's setter hands the state in. */
private fun FeedLook.stateRegister(call: Instruction): Int =
    (call as FiveRegisterInstruction).let { if (holder == null) it.registerD else it.registerE }

/** Where the state handed to the setter called at [at] is loaded: right before the call, or before the holder's read. */
private fun FeedLook.stateLoadAt(at: Int): Int = if (holder == null) at - 1 else at - 2

/** The static field a sget-object loads into [register], when this is one. */
private fun Instruction?.loadsState(register: Int): String? {
    if (this?.opcode != Opcode.SGET_OBJECT || (this as OneRegisterInstruction).registerA != register) return null
    return ((this as ReferenceInstruction).reference as? FieldReference)?.toString()
}

/**
 * The hold state this press entry hands the post through [look]'s setter: its one call to the setter,
 * handed a static field of the state's enum other than the resting one, loaded right before (before
 * the holder's read where the setter is inlined). Null when there isn't exactly one such call or
 * something jumps straight onto it, so the press keeps its look.
 */
private fun Method.findHeld(look: FeedLook): String? {
    val code = code()
    val state = look.rest.substringAfterLast(':')
    val calls = code.indices.filter { look.calledAt(code, it) }
    val at = calls.singleOrNull() ?: return null
    val held = code.getOrNull(look.stateLoadAt(at)).loadsState(look.stateRegister(code[at])) ?: return null
    if (held == look.rest || held.substringBefore("->") != state || held.substringAfterLast(':') != state || at in jumpTargets()) {
        return null
    }
    return held
}

/** Whether this instruction writes [register], one of a wide pair included. */
private fun Instruction.puts(register: Int): Boolean {
    if (!opcode.setsRegister() || this !is OneRegisterInstruction) return false
    return registerA == register || (opcode.setsWideRegister() && registerA + 1 == register)
}

/**
 * The one call to [reset] in this method and what code in front of it reads and may borrow
 * ([borrow] locals). With [keepsAnswer] the call's answer has to go to a move-result with an
 * instruction after it, which a kept lift jumps to.
 */
private fun Method.planReset(reset: String, keepsAnswer: Boolean, borrow: Int = 1): ResetCall {
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
    if (!keepsAnswer) return ResetCall(at, controller, freeLocalsAt(LOCK_PATCH, at, borrow), null)
    val result = code.getOrNull(at + 1)
    if (result?.opcode != Opcode.MOVE_RESULT || code.getOrNull(at + 2) == null) {
        refuseLock("$where calls $reset and drops the answer")
    }
    val free = freeLocalsAt(LOCK_PATCH, at, borrow, targets = listOf(at + 2))
    return ResetCall(at, controller, free, (result as OneRegisterInstruction).registerA)
}

/**
 * Loads one of the controller's fields ([field]) into the borrowed local [into], through it when the
 * controller is above v15.
 */
private fun ResetCall.fieldInto(field: String, into: Int = free.first()): String =
    if (controller <= 15) {
        "iget-object v$into, v$controller, $field"
    } else {
        "move-object/from16 v$into, v$controller\niget-object v$into, v$into, $field"
    }

private fun BytecodePatchContext.editable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

/**
 * Asks [LOOK_KEPT] right before the press entry hands the post its hold state ([held]): a yes hands
 * it [look]'s resting state instead. Both ways load the state into the call's own register, so the
 * call always gets one and nothing else is borrowed.
 */
private fun MutableMethod.keepLook(look: FeedLook, held: String) {
    val code = code()
    val at = code.indices.single {
        look.calledAt(code, it) && code.getOrNull(look.stateLoadAt(it)).loadsState(look.stateRegister(code[it])) == held
    }
    val state = look.stateRegister(code[at])
    addInstructionsWithLabels(
        at,
        """
            invoke-static { }, $LOOK_KEPT
            move-result v$state
            if-eqz v$state, :held
            sget-object v$state, ${look.rest}
            goto :show
            :held
            sget-object v$state, $held
        """,
        ExternalLabel("show", getInstruction(at)),
    )
}

/**
 * Tells [LOCK_PRESSED] the fast flag before the press entry's reset, and asks [LOCK_LIFTED] before
 * the release's: a yes puts the post's 2x look to rest the way the reset does ([FeedLock.look]) and
 * skips the rest of the reset, its answer set to yes the way a fast lift's is. When the press hands
 * the post its hold state ([FeedLock.held]), [LOOK_KEPT] may swap it for the resting one. Each call is
 * found again by its reference, so other patches' code in either method doesn't matter.
 */
internal fun BytecodePatchContext.lockFast(found: FeedLock) {
    val press = editable(found.press)
    val pressed = press.planReset(found.reset, keepsAnswer = false)
    val told = pressed.free.first()
    press.addInstructions(
        pressed.at,
        """
            ${pressed.fieldInto(found.fast)}
            invoke-static { v$told }, $LOCK_PRESSED
        """,
    )
    found.held?.let { held -> press.keepLook(found.look!!, held) }

    val release = editable(found.release)
    val lift = release.planReset(found.reset, keepsAnswer = true, borrow = found.borrows())
    val result = lift.result!!
    val asked = lift.free.first()
    val rests = found.look?.let { look ->
        val state = lift.free[1]
        val show = look.holder?.let { holder ->
            val setter = lift.free[2]
            "iget-object v$setter, v$asked, $holder\ninvoke-virtual { v$setter, v$asked, v$state }, ${look.show}"
        } ?: "invoke-virtual { v$asked, v$state }, ${look.show}"
        """
            ${lift.fieldInto(look.item)}
            if-eqz v$asked, :rested
            sget-object v$state, ${look.rest}
            $show
            :rested
        """
    }.orEmpty()
    release.addInstructionsWithLabels(
        lift.at,
        """
            ${lift.fieldInto(found.fast)}
            invoke-static { v$asked }, $LOCK_LIFTED
            move-result v$asked
            if-eqz v$asked, :reset
            $rests
            ${if (result <= 15) "const/4" else "const/16"} v$result, 0x1
            goto :kept
        """,
        ExternalLabel("reset", release.getInstruction(lift.at)),
        ExternalLabel("kept", release.getInstruction(lift.at + 2)),
    )
}
