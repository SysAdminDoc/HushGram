/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.speed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
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

/** The markers, after Instagram's release prefix, of the methods the patch reads or changes. */
internal const val SET_PLAYBACK_SPEED = "ClipsVideoPlayerController_setPlaybackSpeed"
internal const val MAYBE_RESUME_PLAYER = "ClipsVideoPlayerController_maybeResumePlayer"
internal const val LONG_PRESS_END = "ClipsLongPressController_onLongPressEnd"
internal const val LOCK_UP_BEGIN = "ClipsFastPlayLogger_logFastPlayLockUpBegin"
internal const val RESET = "ClipsLongPressToFeatureController_reset"
internal const val LOCK_UP_END = "ClipsFastPlayLogger_logFastPlayLockUpEnd"
internal const val FAST_PLAY_NUX = "ClipsLongPressToFeatureController_displayFastPlayNux"

/** The event fast play logs when a hold at the edge is let go of without a lock. */
internal const val HOLD_ENDED_EVENT = "ended"

/** The reasons the reset logs a lock ending with, each a string of its own. A fourth, a scroll, comes from elsewhere. */
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
 * maybeResumePlayer hands it the reel and its player just before it plays them, where the kept speed
 * goes on. The extension's stubs are filled with the player's speed setter, the interface call
 * setPlaybackSpeed makes, and the reel's ad flag, the one fast play picks its ads hint by.
 *
 * Every method is found by Instagram's own markers and strings, and everything is found and checked
 * before anything changes, so a build that differs stops the patch naming what it couldn't find, and
 * nothing is half done.
 */
@Suppress("unused")
val keepReelSpeedPatch = bytecodePatch(
    name = "Keep the reel speed",
    description = "Lock a reel at 2x with Instagram's own lock (hold its edge, then slide down) and the next reels " +
        "play at 2x too, until you slide the lock off, hold the edge and let go, or Instagram restarts. On to start. " +
        "Switch: HushGram settings > Reels.",
    default = true,
) {
    category("Reels")
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
    /** The player interface's speed setter, the one interface call setPlaybackSpeed makes. */
    val playerSpeed: MethodReference,
    /** maybeResumePlayer, the index of its call that plays the reel, and that call's player register. */
    val resume: Method,
    val play: Int,
    val player: Int,
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
    val wanted = setOf(SET_PLAYBACK_SPEED, MAYBE_RESUME_PLAYER, LOCK_UP_BEGIN, RESET, FAST_PLAY_NUX)
    val marked = mutableMapOf<String, MutableList<Method>>()
    val holders = typesMarked(*wanted.toTypedArray())
    classDefForEach { classDef ->
        if (classDef.type !in holders) return@classDefForEach
        classDef.methods.forEach { method ->
            method.markers().filter { it in wanted }.distinct().forEach { marked.getOrPut(it) { mutableListOf() } += method }
        }
    }
    fun single(marker: String): Method {
        val found = marked[marker].orEmpty()
        return found.singleOrNull() ?: refuse("expected one method marked $marker, found ${found.size}")
    }

    // setPlaybackSpeed(Function1, float): the speed set on the reel on screen, through one interface call.
    val setter = single(SET_PLAYBACK_SPEED)
    if (AccessFlags.STATIC.isSet(setter.accessFlags) || setter.returnType != "V" ||
        setter.parameterTypes.map(Any::toString) != listOf(FUNCTION1, "F")
    ) {
        refuse("${setter.text()}, marked $SET_PLAYBACK_SPEED, isn't an instance (Function1, float) method returning nothing")
    }
    val speedCalls = setter.code().filter { it.isInterfaceCall() }.mapNotNull { it.methodReference() }
        .filter { it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("F") }
    val playerSpeed = speedCalls.singleOrNull()
        ?: refuse("${setter.text()} makes ${speedCalls.size} interface calls taking a float, expected one: the player's speed")
    val playerClass = classDefByOrNull(playerSpeed.definingClass)
        ?: refuse("the player interface ${playerSpeed.definingClass} isn't in the app")
    if (!AccessFlags.INTERFACE.isSet(playerClass.accessFlags) || !AccessFlags.PUBLIC.isSet(playerClass.accessFlags)) {
        refuse("the player ${playerClass.type} isn't a public interface, so the extension can't call it")
    }
    if (playerClass.methods.none { it.name == playerSpeed.name && it.parameterTypes.map(Any::toString) == listOf("F") }) {
        refuse("the player interface ${playerClass.type} doesn't declare ${playerSpeed.name}(F)V")
    }

    // maybeResumePlayer(reel, ...): plays the reel through the player's (String, boolean) call.
    val resume = single(MAYBE_RESUME_PLAYER)
    if (resume.definingClass != setter.definingClass) refuse("$MAYBE_RESUME_PLAYER isn't in ${setter.definingClass}")
    if (AccessFlags.STATIC.isSet(resume.accessFlags) || resume.parameterTypes.isEmpty()) {
        refuse("${resume.text()} isn't an instance method taking the reel first")
    }
    val itemType = resume.parameterTypes.first().toString()
    val resumeCode = resume.code()
    val plays = resumeCode.indices.filter { index ->
        val call = resumeCode[index].takeIf { it.isInterfaceCall() }?.methodReference()
        call != null && call.definingClass == playerClass.type && call.returnType == "Z" &&
            call.parameterTypes.map(Any::toString) == listOf(STRING, "Z")
    }
    val play = plays.singleOrNull()
        ?: refuse("${resume.text()} makes ${plays.size} (String, boolean) calls on the player, expected one")
    val player = resumeCode[play].registers().first()
    resume.requireParameterIntact(PATCH, 0, listOf(play))

    // The hold handler logs a lock beginning, and a hold let go of without one as "ended".
    val hold = single(LOCK_UP_BEGIN)
    if (LONG_PRESS_END !in hold.markers()) refuse("${hold.text()}, marked $LOCK_UP_BEGIN, isn't marked $LONG_PRESS_END")
    val lockBegun = hold.singleString("android_purge_", LOCK_UP_BEGIN) + 1
    hold.requireOnlyFrom("the lock-begin marker", lockBegun)
    val holdEnded = hold.singleString(null, HOLD_ENDED_EVENT) + 1
    hold.requireOnlyFrom("the \"$HOLD_ENDED_EVENT\" event", holdEnded)

    // The reset logs why a lock ended, then may set the reel back to normal speed through setPlaybackSpeed.
    val reset = single(RESET)
    if (LOCK_UP_END !in reset.markers()) refuse("${reset.text()}, marked $RESET, isn't marked $LOCK_UP_END")
    val lockEnded = reset.singleString("android_purge_", LOCK_UP_END) + 1
    reset.requireOnlyFrom("the lock-end marker", lockEnded)
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
    val nux = single(FAST_PLAY_NUX)
    val adFlag = nux.adFlag(itemType)
    val itemClass = classDefByOrNull(itemType) ?: refuse("the reel $itemType isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(itemClass.accessFlags)) refuse("the reel $itemType isn't public, so the extension can't reach it")
    val declared = itemClass.fields.singleOrNull { it.name == adFlag.name && it.type == "Z" }
        ?: refuse("$itemType doesn't declare ${adFlag.name}:Z")
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
        refuse("$itemType->${adFlag.name} isn't a public instance field, so the extension can't read it")
    }

    return ReelSpeedHooks(
        setter, setter.parameterRegister(1), playerSpeed, resume, play, player, hold, lockBegun, holdEnded,
        reset, lockEnded, reason, resetCall, resetSpeed, adFlag,
    )
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
    mutable(found.resume).addInstructionsAtControlFlowLabel(
        found.play,
        """
            invoke-static/range { $item .. $item }, $ITEM
            invoke-static/range { v${found.player} .. v${found.player} }, $RESUMING
        """,
    )

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
        val player = found.playerSpeed.definingClass
        setPlayerSpeed.addInstructions(
            0,
            """
                check-cast p0, $player
                invoke-interface { p0, p1 }, $player->${found.playerSpeed.name}(F)V
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

/** The index of the one const-string loading [string], or a purge marker ending in it when [prefix] is given. */
private fun Method.singleString(prefix: String?, string: String): Int {
    val code = code()
    val loads = code.indices.filter { index ->
        val loaded = code[index].stringLoaded() ?: return@filter false
        if (prefix == null) loaded == string else loaded.startsWith(prefix) && loaded.endsWith("_$string")
    }
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
 * loaded into, and on every path there either one of them or a string a call just returned.
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
        at !in loads && !(at > 0 && code[at].opcode == Opcode.MOVE_RESULT_OBJECT && code[at - 1].methodReference()?.returnType == STRING)
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
    val key = singleString(null, ADS_HINT_KEY)
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

/** The registers an invoke names, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
