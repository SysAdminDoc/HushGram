/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.speed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.media.PlayerStep
import app.morphe.patches.instagram.media.receiverPath
import app.morphe.patches.instagram.media.unreachableAlong
import app.morphe.patches.instagram.media.walk
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.readsAfter
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.BitSet

private const val PATCH = "Keep the reel speed"

internal const val REEL_SPEED = "$EXTENSION_PACKAGE/reels/ReelSpeed;"
internal const val SPEED_SET = "$REEL_SPEED->speedSet(F)V"
internal const val LOCKED_UP = "$REEL_SPEED->lockedUp()V"
internal const val LOCK_UP_ENDED = "$REEL_SPEED->lockUpEnded(Ljava/lang/String;)V"
internal const val RESET_SPEED = "$REEL_SPEED->resetSpeed(F)F"
internal const val HOLD_ENDED = "$REEL_SPEED->holdEnded()V"
internal const val ITEM = "$REEL_SPEED->item(Ljava/lang/Object;)V"
internal const val RESUMING = "$REEL_SPEED->resuming(Ljava/lang/Object;)V"
internal const val SET_PLAYER_SPEED_STUB = "setPlayerSpeed"
internal const val AD_ITEM_STUB = "adItem"

/**
 * Strings only one method each loads, which name the methods the patch reads or changes. Instagram
 * 456 dropped the purge markers these methods carried.
 *
 * - [RESUME_PLAYER_TRACE]: the Reels controller's maybeResumePlayer, whose class also holds setPlaybackSpeed
 * - [LONG_PRESS_EDGE]: fast play's hold handler (onLongPressEnd), which logs a hold at a reel's edge
 * - [LOCK_UP_CANCELLED]: the reset, the first of its [LOCK_END_REASONS]
 * - [FAST_PLAY_NUX_SHOWN]: the fast-play hint (displayFastPlayNux)
 */
internal const val RESUME_PLAYER_TRACE = "ClipsVideoPlayerController.maybeResumePlayer playVideoInZeroMode: "
internal const val LONG_PRESS_EDGE = "long_press_edge"
internal const val LOCK_UP_CANCELLED = "cancel_lock_up"
internal const val FAST_PLAY_NUX_SHOWN = "instagram_clips_fast_reels_nux_impression"

/** The event fast play logs when a hold locks the speed, once in the hold handler. */
internal const val LOCKED_EVENT = "locked"

/** The event fast play logs when a hold at the edge is let go of without a lock, and when a lock ends. */
internal const val HOLD_ENDED_EVENT = "ended"

/** maybeResumePlayer: every method loading [RESUME_PLAYER_TRACE], one in a build the patch fits. */
internal fun BytecodePatchContext.reelsResumePlayers(): List<Method> = methodsLoading(RESUME_PLAYER_TRACE)

private fun BytecodePatchContext.methodsLoading(value: String): List<Method> =
    classesLoadingString(value).sortedBy { it.type }.flatMap { it.methods }.filter { loadsString(it, value) }

/** The reasons the reset logs a lock ending with, each a string of its own. A fourth, a scroll, comes from elsewhere or, on 456, a string of its own too. */
internal val LOCK_END_REASONS = listOf("cancel_lock_up", "swipe_down", "switch_tab")

/** The fast-play hint keeps a count of its own for ads, and picks it by the reel's ad flag. */
internal const val ADS_HINT_KEY = "key_clips_fast_play_ui_ads_last_shown_timestamp_ms"

private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * The speed locked with Instagram's own 2x lock stays for the next reels. See the extension's
 * ReelSpeed for the rule.
 *
 * Instagram 449's Reels player controller sets the speed of the reel on screen in setPlaybackSpeed,
 * which tells the extension each speed first thing. Fast play's hold handler tells it when a lock
 * begins and when a hold is let go of without one, where it logs those, and the reset tells it why a
 * lock ended and hands it the speed it's about to reset the reel to. The controller's
 * maybeResumePlayer hands it the reel first thing and its player just before it plays it, where the kept speed
 * goes on. The extension's stubs are filled with the player's speed setter, the call
 * setPlaybackSpeed makes on the player or, from 457, on what it reaches from the player through a
 * cast and two fields, and the reel's ad flag, the one fast play picks its ads hint by.
 *
 * Every method is found by strings only it loads, and everything is found and checked
 * before anything changes, so a build that differs stops the patch naming what it couldn't find, and
 * nothing is half done.
 */
@Suppress("unused")
val keepReelSpeedPatch = bytecodePatch(
    name = "Keep the reel speed",
    description = "Keeps the 2x speed on for the next reels after you lock a reel at 2x (hold its edge, then " +
        "slide down), until you slide the lock off. On by default. Turn it off in HushGram settings > Reels.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("keepReelSpeed")
        keepReelSpeed()
        enableStatus("keepReelSpeed")
    }
}

/** Finds and checks every hook and both stubs, then writes them. A build that fails any check is left as it was. */
internal fun BytecodePatchContext.keepReelSpeed() {
    val found = findReelSpeedHooks()
    val stubs = reelSpeedStubs()
    applyReelSpeedHooks(found)
    stubs.fill(found)
}

/** Every place the patch changes, by method and instruction index, and what the stubs reach. */
internal class ReelSpeedHooks(
    /** setPlaybackSpeed, and the register of its speed parameter. */
    val setter: Method,
    val speedParameter: String,
    /** The player's speed setter, the one call setPlaybackSpeed hands its speed to, and whether it's on an interface. */
    val playerSpeed: MethodReference,
    val speedOnInterface: Boolean,
    /** The player interface maybeResumePlayer plays the reel through, and the casts and fields from it to the speed setter. */
    val player: String,
    val playerPath: List<PlayerStep>,
    /** maybeResumePlayer, the index of its call that plays the reel, and that call's player register. */
    val resume: Method,
    val play: Int,
    val playerRegister: Int,
    /** The hold handler, the index after its lock-begin marker, and the index after its "ended" event. */
    val hold: Method,
    val lockBegun: Int,
    val holdEnded: Int,
    /** The reset, the index after its lock-end marker, the reason's register there, and its own setter call. */
    val reset: Method,
    val lockEnded: Int,
    val reason: Int,
    val resetCall: Int,
    val resetSpeed: Int,
    /** The reel's ad flag. */
    val adFlag: FieldReference,
)

internal fun BytecodePatchContext.findReelSpeedHooks(): ReelSpeedHooks {
    fun single(value: String): Method {
        val found = methodsLoading(value)
        return found.singleOrNull() ?: refuse("expected one method loading \"$value\", found ${found.size}")
    }

    // maybeResumePlayer's class is the controller, and setPlaybackSpeed(Function1, float) its one instance
    // method of that shape: the speed set on the reel on screen, through one call handed the speed.
    val resume = single(RESUME_PLAYER_TRACE)
    val setters = classDefBy(resume.definingClass).methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(FUNCTION1, "F")
    }
    val setter = setters.singleOrNull()
        ?: refuse("expected one instance (Function1, float) method returning nothing in ${resume.definingClass}, found ${setters.size}")
    val setterCode = setter.code()
    val speed = setter.parameterRegisterNumber(1)
    val speedCalls = setterCode.indices.filter { at ->
        val call = setterCode[at].methodReference()
        call != null && setterCode[at].isInstanceCall() && call.returnType == "V" && call.parameterTypes.map(Any::toString) == listOf("F") &&
            setterCode[at].registers().getOrNull(1) == speed
    }
    val speedAt = speedCalls.singleOrNull()
        ?: refuse("${setter.text()} makes ${speedCalls.size} calls handing on its float, expected one: the player's speed")
    val playerSpeed = setterCode[speedAt].methodReference()!!
    val speedOnInterface = setterCode[speedAt].isInterfaceCall()
    // 456 calls the player itself. 457 casts it to its one implementation, reads its video holder
    // and the holder's player, and calls that.
    val (playerType, playerPath) = receiverPath(setterCode, speedAt)
        ?: refuse("${setter.text()}'s speed call works on something other than a player reached through casts and fields")
    val playerClass = classDefByOrNull(playerType) ?: refuse("the player interface $playerType isn't in the app")
    if (!AccessFlags.INTERFACE.isSet(playerClass.accessFlags) || !AccessFlags.PUBLIC.isSet(playerClass.accessFlags)) {
        refuse("the player ${playerClass.type} isn't a public interface, so the extension can't call it")
    }
    requireReachable(playerSpeed, speedOnInterface, playerPath)

    // maybeResumePlayer(reel, ...): plays the reel through the player's (String, boolean) call. 457 made it static.
    if (resume.parameterTypes.isEmpty()) refuse("${resume.text()} takes no reel")
    val itemType = resume.parameterTypes.first().toString()
    val resumeCode = resume.code()
    val plays = resumeCode.indices.filter { index ->
        val call = resumeCode[index].takeIf { it.isInterfaceCall() }?.methodReference()
        call != null && call.definingClass == playerClass.type && call.returnType == "Z" &&
            call.parameterTypes.map(Any::toString) == listOf(STRING, "Z")
    }
    val play = plays.singleOrNull()
        ?: refuse("${resume.text()} makes ${plays.size} (String, boolean) calls on the player, expected one")
    val playerRegister = resumeCode[play].registers().first()

    // The hold handler logs a lock beginning as "locked", and a hold let go of without one as "ended".
    // It's the lambda loading the long-press edge, or where Redex moved that lambda's cases into
    // statics of their own (457's x86_64 386314286), the one in its class logging a lock.
    val edge = single(LONG_PRESS_EDGE)
    val holds = classDefBy(edge.definingClass).methods.filter { method -> method.code().any { it.stringLoaded() == LOCKED_EVENT } }
    val hold = holds.singleOrNull()
        ?: refuse("expected one method in ${edge.definingClass} logging \"$LOCKED_EVENT\", found ${holds.size}")
    val lockBegun = hold.singleString(LOCKED_EVENT) + 1
    hold.requireOnlyFrom("the \"$LOCKED_EVENT\" event", lockBegun)
    val holdEnded = hold.singleString(HOLD_ENDED_EVENT) + 1
    hold.requireOnlyFrom("the \"$HOLD_ENDED_EVENT\" event", holdEnded)

    // The reset logs why a lock ended as "ended", then may set the reel back to normal speed through setPlaybackSpeed.
    val reset = single(LOCK_UP_CANCELLED)
    val lockEnded = reset.singleString(HOLD_ENDED_EVENT) + 1
    reset.requireOnlyFrom("the \"$HOLD_ENDED_EVENT\" event", lockEnded)
    val reason = reset.reasonRegister(lockEnded)
    val resetCode = reset.code()
    val resetCalls = resetCode.indices.filter { resetCode[it].methodReference()?.let { call -> call.text() == setter.text() } == true }
    val resetCall = resetCalls.singleOrNull()
        ?: refuse("${reset.text()} calls ${setter.text()} ${resetCalls.size} times, expected once")
    val resetSpeed = resetCode[resetCall].registers().getOrNull(2)
        ?: refuse("${reset.text()}'s call to ${setter.text()} names no speed")
    val readLater = reset.readsAfter(resetCall, resetSpeed)
    if (readLater.isNotEmpty()) refuse("${reset.text()} reads v$resetSpeed again after its reset, at ${readLater.joinToString()}")

    // The fast-play hint picks its ads count by the reel's ad flag, tested just before the ads key.
    val nux = single(FAST_PLAY_NUX_SHOWN)
    val adFlag = nux.adFlag(itemType)
    val itemClass = classDefByOrNull(itemType) ?: refuse("the reel $itemType isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(itemClass.accessFlags)) refuse("the reel $itemType isn't public, so the extension can't reach it")
    val declared = itemClass.fields.singleOrNull { it.name == adFlag.name && it.type == "Z" }
        ?: refuse("$itemType doesn't declare ${adFlag.name}:Z")
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
        refuse("$itemType->${adFlag.name} isn't a public instance field, so the extension can't read it")
    }

    return ReelSpeedHooks(
        setter, setter.parameterRegister(1), playerSpeed, speedOnInterface, playerType, playerPath, resume, play, playerRegister,
        hold, lockBegun, holdEnded, reset, lockEnded, reason, resetCall, resetSpeed, adFlag,
    )
}

/** Throws unless the extension can reach the speed call along [path]: every class, field and the call public. */
private fun BytecodePatchContext.requireReachable(call: MethodReference, onInterface: Boolean, path: List<PlayerStep>) {
    unreachableAlong(path).firstOrNull()?.let { refuse("the extension can't reach $it on the way to the player's speed") }
    val callClass = classDefByOrNull(call.definingClass) ?: refuse("the player's speed ${call.definingClass} isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(callClass.accessFlags)) refuse("the player's speed ${callClass.type} isn't public")
    val method = callClass.methods.singleOrNull { it.name == call.name && it.parameterTypes.map(Any::toString) == listOf("F") && it.returnType == "V" }
        ?: refuse("${callClass.type} doesn't declare ${call.name}(F)V")
    if (onInterface != AccessFlags.INTERFACE.isSet(callClass.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) {
        refuse("${call.definingClass}->${call.name}(F)V isn't a public method the extension can call")
    }
}

/**
 * Each hook hands the extension what it needs through the range form, which names any register and
 * borrows none. A hook going in after an instruction goes where nothing branches to, so it runs on
 * the one path; one going in front of a call takes that call's label, so every path to it runs it.
 * Within a method the later index goes first, so the earlier ones still point where they did.
 */
internal fun BytecodePatchContext.applyReelSpeedHooks(found: ReelSpeedHooks) {
    mutable(found.setter).addInstructions(0, "invoke-static/range { ${found.speedParameter} .. ${found.speedParameter} }, $SPEED_SET")

    val item = found.resume.parameterRegister(0)
    val resume = mutable(found.resume)
    // Found again in the method as it stands now: the speaker icon's hook (#608) may sit ahead of the play call.
    val playCall = found.resume.code()[found.play].methodReference().toString()
    val play = resume.code().indexOfFirst { it.methodReference()?.toString() == playCall }
    if (play < 0) refuse("${found.resume.text()} lost its play call")
    resume.addInstructionsAtControlFlowLabel(
        play,
        "invoke-static/range { v${found.playerRegister} .. v${found.playerRegister} }, $RESUMING",
    )
    // The reel goes over first thing, while its parameter still holds it: 457's static
    // maybeResumePlayer moves it to a local and reuses the parameter before the play call.
    resume.addInstructions(0, "invoke-static/range { $item .. $item }, $ITEM")

    val hold = mutable(found.hold)
    listOf(found.lockBegun to LOCKED_UP, found.holdEnded to HOLD_ENDED).sortedByDescending { it.first }.forEach { (index, hook) ->
        hold.addInstructions(index, "invoke-static { }, $hook")
    }

    val reset = mutable(found.reset)
    val speed = found.resetSpeed
    val writes = listOf<Pair<Int, () -> Unit>>(
        found.resetCall to {
            reset.addInstructionsAtControlFlowLabel(
                found.resetCall,
                """
                    invoke-static/range { v$speed .. v$speed }, $RESET_SPEED
                    move-result v$speed
                """,
            )
        },
        found.lockEnded to {
            reset.addInstructions(found.lockEnded, "invoke-static/range { v${found.reason} .. v${found.reason} }, $LOCK_UP_ENDED")
        },
    )
    writes.sortedByDescending { it.first }.forEach { it.second() }
}

/** The extension's stubs, found before anything changes, and the step that fills them. */
internal class ReelSpeedStubs(val setPlayerSpeed: MutableMethod, val adItem: MutableMethod) {
    fun fill(found: ReelSpeedHooks) {
        val call = found.playerSpeed
        val invoke = if (found.speedOnInterface) "invoke-interface" else "invoke-virtual"
        val path = found.playerPath.walk("p0", ":none")
        val cast = if (found.playerPath.firstOrNull()?.type != null) "" else "check-cast p0, ${found.player}\n"
        setPlayerSpeed.addInstructionsWithLabels(
            0,
            """
                $cast$path$invoke { p0, p1 }, ${call.definingClass}->${call.name}(F)V
                :none
                return-void
            """,
        )
        val item = found.adFlag.definingClass
        adItem.addInstructions(
            0,
            """
                check-cast p0, $item
                iget-boolean p0, p0, $item->${found.adFlag.name}:Z
                return p0
            """,
        )
    }
}

internal fun BytecodePatchContext.reelSpeedStubs(): ReelSpeedStubs {
    val extension = mutableClassDefBy(REEL_SPEED)
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: refuse("$REEL_SPEED has no static $returns $name(${parameters.joinToString("")})")
    return ReelSpeedStubs(
        setPlayerSpeed = stub(SET_PLAYER_SPEED_STUB, listOf(OBJECT, "F"), "V"),
        adItem = stub(AD_ITEM_STUB, listOf(OBJECT), "Z"),
    )
}

/** The index of the one const-string loading [string]. */
private fun Method.singleString(string: String): Int {
    val code = code()
    val loads = code.indices.filter { index -> code[index].stringLoaded() == string }
    return loads.singleOrNull() ?: refuse("${text()} loads \"$string\" ${loads.size} times, expected once")
}

/** Throws unless the instruction at [index] is reached only from the one before it, so code put in front of it runs on that path alone. */
private fun Method.requireOnlyFrom(what: String, index: Int) {
    val flow = ControlFlow.of(this)
    if (index >= flow.instructions.size) refuse("${text()} ends at $what")
    val from = flow.instructions.indices.filter { index in flow.normal[it] || index in flow.exceptional[it] }
    if (from != listOf(index - 1)) refuse("${text()} reaches the instruction after $what from ${from.joinToString()}, expected only ${index - 1}")
}

/**
 * The register holding the reason the reset logs at [index]: the one each of [LOCK_END_REASONS] is
 * loaded into, and on every path there either a string it loads itself (456 loads the scroll's
 * "scroll_away" too) or a string a call just returned.
 */
private fun Method.reasonRegister(index: Int): Int {
    val code = code()
    val loads = LOCK_END_REASONS.map { reason ->
        val at = code.indices.filter { code[it].stringLoaded() == reason }
        at.singleOrNull() ?: refuse("${text()} loads \"$reason\" ${at.size} times, expected once")
    }
    val registers = loads.map { (code[it] as OneRegisterInstruction).registerA }.distinct()
    val register = registers.singleOrNull()
        ?: refuse("${text()} loads its lock-end reasons into ${registers.joinToString { "v$it" }}, expected one register")
    val reaching = writesReaching(index, register)
    val strange = reaching.filter { at ->
        at !in loads && code[at].stringLoaded() == null &&
            !(at > 0 && code[at].opcode == Opcode.MOVE_RESULT_OBJECT && code[at - 1].methodReference()?.returnType == STRING)
    }
    if (strange.isNotEmpty() || !reaching.containsAll(loads)) {
        refuse("${text()}'s v$register holds something other than the lock-end reason where it logs the end, from ${reaching.joinToString()}")
    }
    return register
}

/**
 * The reel's ad flag: [this], the fast-play hint, tests a boolean just before it loads [ADS_HINT_KEY],
 * and that boolean is the one field of the reel, its [itemType] parameter, read into it on every path.
 */
private fun Method.adFlag(itemType: String): FieldReference {
    val code = code()
    val key = singleString(ADS_HINT_KEY)
    val test = code.getOrNull(key - 1)
    if (test == null || (test.opcode != Opcode.IF_EQZ && test.opcode != Opcode.IF_NEZ)) {
        refuse("${text()} doesn't test a flag just before it loads \"$ADS_HINT_KEY\"")
    }
    val flag = (test as OneRegisterInstruction).registerA
    val at = writesReaching(key - 1, flag).singleOrNull()?.takeIf { it >= 0 && code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: refuse("${text()}'s flag before \"$ADS_HINT_KEY\" isn't one boolean field read")
    val read = code[at]
    val field = (read as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != itemType) refuse("${text()}'s flag before \"$ADS_HINT_KEY\" is ${field.definingClass}->${field.name}, not the reel's")
    val parameter = parameterTypes.indexOfFirst { it.toString() == itemType }
    if (parameter < 0 || (read as TwoRegisterInstruction).registerB != parameterRegisterNumber(parameter)) {
        refuse("${text()} reads its ad flag from something other than its $itemType parameter")
    }
    requireParameterIntact(PATCH, parameter, listOf(at))
    return field
}

/**
 * Every instruction whose write to [register] can be what the instruction at [index] finds there,
 * along branches, switches and exception handlers; -1 when the method's entry value can be. A write
 * reached by a throw never happened, so the search goes on past it.
 */
private fun Method.writesReaching(index: Int, register: Int): Set<Int> {
    val flow = ControlFlow.of(this)
    val count = flow.instructions.size
    val normalFrom = Array(count) { mutableListOf<Int>() }
    val thrownFrom = Array(count) { mutableListOf<Int>() }
    for (at in 0 until count) {
        flow.normal[at].forEach { normalFrom[it] += at }
        flow.exceptional[at].forEach { thrownFrom[it] += at }
    }
    val writes = sortedSetOf<Int>()
    val entered = BitSet()
    val pending = ArrayDeque<Int>()
    fun enter(at: Int) {
        if (entered[at]) return
        entered.set(at)
        pending += at
    }
    fun before(at: Int) {
        if (at == 0) writes += -1
        normalFrom[at].forEach { from -> if (flow.instructions[from].writes(register)) writes += from else enter(from) }
        thrownFrom[at].forEach(::enter)
    }
    before(index)
    while (pending.isNotEmpty()) before(pending.removeFirst())
    return writes
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.isInterfaceCall(): Boolean = opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_INTERFACE_RANGE

private fun Instruction.isInstanceCall(): Boolean =
    isInterfaceCall() || opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_VIRTUAL_RANGE

/** The registers an invoke names, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
