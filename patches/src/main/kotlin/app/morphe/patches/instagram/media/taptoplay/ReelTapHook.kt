/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.taptoplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.media.PlayerStep
import app.morphe.patches.instagram.media.receiverPath
import app.morphe.patches.instagram.media.unreachableAlong
import app.morphe.patches.instagram.media.walk
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Member
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val RESUME_ON_TAP = "$TAP_TO_PLAY->resumeOnTap(ILjava/lang/Object;)Z"

/** The extension class holding the stubs, apart from the start gate Instagram's player calls. */
internal const val REEL_STATE_READER = "$EXTENSION_PACKAGE/media/ReelStateReader;"

/** The reader's stubs the patch fills in, each (name, parameter count). */
internal val REEL_STUBS = listOf("controllerOf" to 1, "holderOf" to 1, "playersOf" to 1, "playerFor" to 2, "stateOf" to 1)
private const val OBJECT = "Ljava/lang/Object;"
internal const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

/**
 * The trace the Reels controller (ClipsVideoPlayerController) writes when it pauses the current
 * player, which only that pause loads. Instagram 456 dropped the pause's purge marker.
 */
internal const val REELS_PLAYER_PAUSE = "ClipsVideoPlayerController.pauseCurrentPlayer pauseReason="

/** What the Reels tap loads first thing on its pause path. */
internal const val CLIPS_PAUSE = "clips_pause"

/** The Reels controller's pause: the one method loading [REELS_PLAYER_PAUSE]. [refuse] stops the patch. */
internal fun BytecodePatchContext.reelsPlayerPause(refuse: (String) -> Nothing): Method {
    val pauses = classesLoadingString(REELS_PLAYER_PAUSE).sortedBy { it.type }.flatMap { it.methods }
        .filter { loadsString(it, REELS_PLAYER_PAUSE) }
    return pauses.singleOrNull() ?: refuse("expected one method loading \"$REELS_PLAYER_PAUSE\", found ${pauses.size}")
}

/**
 * The Reels tap (PauseAndMuteNavigator's togglePause): the one method that loads [CLIPS_PAUSE] and
 * also pauses through the controller's [pause]. Instagram 456 dropped the tap's purge marker.
 */
internal fun BytecodePatchContext.reelsTap(pause: Method, refuse: (String) -> Nothing): Method {
    val pauseParameters = pause.parameterTypes.map(Any::toString)
    val taps = classesLoadingString(CLIPS_PAUSE).sortedBy { it.type }.flatMap { it.methods }.filter { method ->
        loadsString(method, CLIPS_PAUSE) && method.code().any { instruction ->
            instruction.methodReference()?.let {
                it.definingClass == pause.definingClass && it.name == pause.name && it.parameterTypes.map(Any::toString) == pauseParameters
            } == true
        }
    }
    return taps.singleOrNull()
        ?: refuse("expected one method loading \"$CLIPS_PAUSE\" and pausing through ${pause.definingClass}->${pause.name}, found ${taps.size}")
}

/**
 * Where [code] loads [CLIPS_PAUSE], when it loads it once: the first and the last index of the load.
 * A const-string is both. 456.1's Redex asks a static string pool for it instead, so the load is the
 * pool's number, the call and its move-result, and a branch to the pause path lands on the number.
 */
internal fun BytecodePatchContext.clipsPauseLoad(code: List<Instruction>): IntRange? {
    val at = code.indices.filter { stringLoadedAt(code, it) == CLIPS_PAUSE }.singleOrNull() ?: return null
    if (code[at].opcode == Opcode.CONST_STRING || code[at].opcode == Opcode.CONST_STRING_JUMBO) return at..at
    if (code.getOrNull(at + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    return (at - 1)..(at + 1)
}

/** The constant names of IgVideoPlayerImpl's state enum, which Instagram's build keeps. */
internal val PLAYER_STATES = listOf("IDLE", "PREPARING", "PREPARED", "PLAYING", "PAUSED", "STOPPING")

/**
 * The Reels tap and what the extension needs to ask about the reel under it.
 *
 * @property branch the index of the tap's branch to its pause path, on [decision], its resume
 *           decision
 * @property supplier the tap's field that hands it the ClipsVideoPlayerController, [controller]
 * @property currentHolder the controller's view holder of the reel on screen
 * @property players the controller's field holding its players, and [playerFor] the player it has
 *           for a holder
 * @property state the player's state, asked of the [player] itself or, from 457, of what the
 *           player holds: [statePath] is the casts and fields from the player to what's asked
 */
internal class ReelTap(
    val tap: Method,
    val branch: Int,
    val decision: Int,
    val supplier: FieldReference,
    val controller: String,
    val currentHolder: MethodReference,
    val players: FieldReference,
    val playerFor: MethodReference,
    val playerForIsInterface: Boolean,
    val player: String,
    val state: MethodReference,
    val statePath: List<PlayerStep>,
    val stateIsInterface: Boolean,
)

/**
 * A tap on a reel resumes it only when Instagram knows you paused it yourself: its tap reads the
 * reel's paused position and otherwise takes its pause path, which pauses the player only when it's
 * playing. A reel whose start the gate held has neither, so every tap on it did nothing. The tap's
 * decision now goes past the extension first thing on the branch, with the tap's navigator, and the
 * extension's stubs ask the controller the navigator holds for the state of the reel on screen, the
 * way the controller's own accessors reach that reel's player. Each stub works in its own
 * parameters: the build compiles a stub's `return NOT_PATCHED` into its parameter's register, so it
 * has no local to lend.
 */
internal fun BytecodePatchContext.hookReelTap(found: ReelTap) {
    val extension = mutableClassDefBy(REEL_STATE_READER)
    fun stub(name: String): MutableMethod = extension.methods.single { it.isStub(name, REEL_STUBS.toMap().getValue(name)) }

    // The stubs first: the tap calls into the extension only once everything it asks is in place.
    stub("controllerOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.tap.definingClass}
            iget-object p0, p0, ${found.supplier}
            invoke-interface { p0 }, $FUNCTION0->invoke()$OBJECT
            move-result-object p0
            return-object p0
        """,
    )
    stub("holderOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.controller}
            invoke-virtual { p0 }, ${found.currentHolder}
            move-result-object p0
            return-object p0
        """,
    )
    stub("playersOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.controller}
            iget-object p0, p0, ${found.players}
            return-object p0
        """,
    )
    val invoke = if (found.playerForIsInterface) "invoke-interface" else "invoke-virtual"
    stub("playerFor").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.players.type}
            check-cast p1, ${found.playerFor.parameterTypes.single()}
            $invoke { p0, p1 }, ${found.playerFor}
            move-result-object p0
            return-object p0
        """,
    )
    val stateCast = if (found.statePath.firstOrNull()?.type != null) "" else "check-cast p0, ${found.player}\n"
    val stateInvoke = if (found.stateIsInterface) "invoke-interface" else "invoke-virtual"
    stub("stateOf").addInstructionsWithLabels(
        0,
        """
            $stateCast${found.statePath.walk("p0", ":none")}$stateInvoke { p0 }, ${found.state}
            move-result-object p0
            :none
            return-object p0
        """,
    )
    mutable(found.tap).addInstructionsAtControlFlowLabel(
        found.branch,
        """
            invoke-static { v${found.decision}, p0 }, $RESUME_ON_TAP
            move-result v${found.decision}
        """,
    )
}

/**
 * The Reels tap ([reelsTap]), and the ClipsVideoPlayerController whose pause loads
 * [REELS_PLAYER_PAUSE] ([reelsPlayerPause]). The tap must branch to its [CLIPS_PAUSE] path on one
 * register that holds only a boolean before it, since the extension reads that register as the
 * tap's yes or no, and reach the controller through one Function0 field of its own; the
 * controller's pause must get its player from one field, for the holder its no-argument accessor
 * also returns; and that player must be an interface with one method returning the state enum,
 * or, from 457, the controller must ask that state of what the player holds one way.
 */
internal fun BytecodePatchContext.findReelTap(): ReelTap {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the Reels tap: $detail")
    val pause = reelsPlayerPause(::refuse)
    val tap = reelsTap(pause, ::refuse)
    val controller = pause.definingClass
    if (AccessFlags.STATIC.isSet(tap.accessFlags) || tap.returnType != "V") refuse("${tap.definingClass}->${tap.name} isn't an instance method returning nothing")
    if (AccessFlags.STATIC.isSet(pause.accessFlags) || pause.parameterTypes.firstOrNull()?.toString() != "Ljava/lang/String;") {
        refuse("$controller->${pause.name} isn't an instance pause taking its reason first")
    }

    val code = tap.code()
    val pausePath = clipsPauseLoad(code)?.first
        ?: refuse("${tap.definingClass}->${tap.name} doesn't load \"$CLIPS_PAUSE\" once")
    val branches = code.indices.filter { code[it].opcode == Opcode.IF_EQZ && code.target(it) == pausePath }
    val branch = branches.singleOrNull() ?: refuse("expected one branch to the pause path, found ${branches.size}")
    val decision = (code[branch] as OneRegisterInstruction).registerA
    // The navigator is `this`, the first register past the locals.
    if (decision > 15 || tap.localRegisterCount() > 15) refuse("the decision or the navigator is past v15")
    if (decision >= tap.localRegisterCount()) refuse("the decision is in a parameter's register")
    val setters = code.indices.filter { it < branch && code[it].writes(decision) }
    if (setters.isEmpty() || setters.any { !code.setsBoolean(it) }) {
        refuse("v$decision holds something other than a boolean before the branch to the pause path")
    }
    tap.requireThisIntact(PATCH, listOf(branch))
    if (code.none { it.methodReference()?.let { call -> call.definingClass == controller && call.name == pause.name } == true }) {
        refuse("${tap.definingClass}->${tap.name} never pauses through $controller")
    }

    // The navigator hands over the controller through a Function0 field: read it, invoke it, cast.
    val suppliers = code.indices.mapNotNull { index ->
        val field = code[index].fieldReference() ?: return@mapNotNull null
        if (code[index].opcode != Opcode.IGET_OBJECT || field.definingClass != tap.definingClass || field.type != FUNCTION0) return@mapNotNull null
        val cast = code.getOrNull(index + 3)
        val invoked = code.getOrNull(index + 1)?.methodReference()
        if (invoked?.definingClass != FUNCTION0 || cast?.opcode != Opcode.CHECK_CAST ||
            ((cast as ReferenceInstruction).reference as TypeReference).type != controller
        ) return@mapNotNull null
        field
    }.distinctBy { it.toString() }
    val supplier = suppliers.singleOrNull() ?: refuse("expected one field handing over $controller, found ${suppliers.size}")

    // The controller's pause gets the player it pauses from a field of its own, for the holder on
    // screen, and pauses it with the reason.
    val pauseCode = pause.code()
    val lookups = pauseCode.indices.mapNotNull { index ->
        val field = pauseCode[index].fieldReference() ?: return@mapNotNull null
        val call = pauseCode.getOrNull(index + 1)?.methodReference() ?: return@mapNotNull null
        val receiver = (pauseCode[index + 1] as? FiveRegisterInstruction)?.registerC
        if (pauseCode[index].opcode != Opcode.IGET_OBJECT || field.definingClass != controller || call.definingClass != field.type ||
            call.parameterTypes.size != 1 || receiver != (pauseCode[index] as TwoRegisterInstruction).registerA
        ) return@mapNotNull null
        val paused = pauseCode.any { later ->
            later.opcode == Opcode.INVOKE_INTERFACE && later.methodReference()?.let {
                it.definingClass == call.returnType && it.returnType == "I" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;")
            } == true
        }
        if (paused) field to call else null
    }.distinctBy { (field, call) -> "$field $call" }
    val (players, playerFor) = lookups.singleOrNull() ?: refuse("expected one player lookup in $controller->${pause.name}, found ${lookups.size}")
    val holder = playerFor.parameterTypes.single().toString()
    val player = playerFor.returnType

    val controllerClass = classDefBy(controller)
    val holders = controllerClass.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == holder && it.implementation != null
    }
    val currentHolder = holders.singleOrNull() ?: refuse("expected one holder accessor on $controller, found ${holders.size}")

    val playerClass = classDefByOrNull(player) ?: refuse("this build has no $player")
    if (!AccessFlags.INTERFACE.isSet(playerClass.accessFlags)) refuse("$player isn't an interface")
    val ownStates = playerClass.methods.filter { method ->
        method.parameterTypes.isEmpty() && classDefByOrNull(method.returnType)?.namesConstants(PLAYER_STATES) == true
    }.map { it.reference() to emptyList<PlayerStep>() }
    // 457 slimmed the player interface: the state is asked of what its one implementation holds.
    val states = ownStates.ifEmpty { heldStates(controllerClass, player) }
    val (state, statePath) = states.singleOrNull() ?: refuse("expected one state accessor on $player, found ${states.size}")
    val stateClass = classDefByOrNull(state.definingClass) ?: refuse("this build has no ${state.definingClass}")
    val lookupClass = classDefByOrNull(players.type) ?: refuse("this build has no ${players.type}")

    // The stubs run in the extension, so every class they cast to and every member they name must be
    // public, and declared where they name it: one found only on a superclass counts as unreachable.
    val tapClass = classDefBy(tap.definingClass)
    val holderClass = classDefByOrNull(holder) ?: refuse("this build has no $holder")
    val unreachable = listOfNotNull(
        tapClass.takeUnless { it.isPublic() }?.type,
        controllerClass.takeUnless { it.isPublic() }?.type,
        lookupClass.takeUnless { it.isPublic() }?.type,
        holderClass.takeUnless { it.isPublic() }?.type,
        playerClass.takeUnless { it.isPublic() }?.type,
        "$supplier".takeUnless { tapClass.fields.any { it.name == supplier.name && it.type == supplier.type && it.isPublicMember() } },
        "$players".takeUnless { controllerClass.fields.any { it.name == players.name && it.type == players.type && it.isPublicMember() } },
        "$controller->${currentHolder.name}".takeUnless { currentHolder.isPublicMember() },
        "$playerFor".takeUnless {
            lookupClass.methods.any { it.name == playerFor.name && it.parameterTypes.map(Any::toString) == listOf(holder) && it.isPublicMember() }
        },
        "${state.definingClass}->${state.name}".takeUnless {
            stateClass.isPublic() && stateClass.methods.any { it.name == state.name && it.parameterTypes.isEmpty() && it.isPublicMember() }
        },
    ) + unreachableAlong(statePath)
    if (unreachable.isNotEmpty()) refuse("the extension can't reach ${unreachable.joinToString()}")

    val extension = classDefByOrNull(REEL_STATE_READER) ?: refuse("the extension has no $REEL_STATE_READER")
    REEL_STUBS.forEach { (name, count) ->
        extension.methods.singleOrNull { it.isStub(name, count) } ?: refuse("$REEL_STATE_READER has no static Object $name taking $count Object(s)")
    }

    return ReelTap(
        tap = tap,
        branch = branch,
        decision = decision,
        supplier = supplier,
        controller = controller,
        currentHolder = currentHolder.reference(),
        players = players,
        playerFor = playerFor,
        playerForIsInterface = AccessFlags.INTERFACE.isSet(lookupClass.accessFlags),
        player = player,
        state = state,
        statePath = statePath,
        stateIsInterface = AccessFlags.INTERFACE.isSet(stateClass.accessFlags),
    )
}

/**
 * The [controller]'s reads of a [player]'s state through what the player holds: each no-argument
 * call answering the state enum whose receiver the controller reaches from the player through casts
 * and fields, once for each call and path. 457's controller casts the player to its one
 * implementation and asks the implementation's engine (0ASt.A1t).
 */
private fun BytecodePatchContext.heldStates(controller: ClassDef, player: String): List<Pair<MethodReference, List<PlayerStep>>> =
    controller.methods.flatMap { method ->
        val code = method.code()
        code.indices.mapNotNull { at ->
            if (code[at].opcode != Opcode.INVOKE_VIRTUAL && code[at].opcode != Opcode.INVOKE_INTERFACE) return@mapNotNull null
            val call = code[at].methodReference() ?: return@mapNotNull null
            if (call.parameterTypes.isNotEmpty() || classDefByOrNull(call.returnType)?.namesConstants(PLAYER_STATES) != true) return@mapNotNull null
            val (from, path) = receiverPath(code, at) ?: return@mapNotNull null
            if (from == player && path.isNotEmpty()) call to path else null
        }
    }.distinctBy { (call, path) -> "$call ${path.joinToString()}" }

/** Whether [this] is an enum whose constants' kept names include every one of [names]. */
private fun ClassDef.namesConstants(names: List<String>): Boolean {
    if (!AccessFlags.ENUM.isSet(accessFlags)) return false
    val loaded = methods.filter { it.name == "<clinit>" }.flatMap { method -> method.code().mapNotNull { it.stringLoaded() } }.toSet()
    return loaded.containsAll(names)
}

private fun ClassDef.isPublic() = AccessFlags.PUBLIC.isSet(accessFlags)

private fun Member.isPublicMember() = AccessFlags.PUBLIC.isSet(accessFlags)

/** Whether [this] sets [register], or the wide pair that covers it. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val target = (this as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (opcode.setsWideRegister() && target + 1 == register)
}

/** Whether the instruction at [index] sets its register to a boolean: 0 or 1, a boolean field, or a boolean call's result. */
private fun List<Instruction>.setsBoolean(index: Int): Boolean {
    val instruction = this[index]
    return when (instruction.opcode) {
        Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST -> (instruction as NarrowLiteralInstruction).narrowLiteral in 0..1
        Opcode.IGET_BOOLEAN, Opcode.SGET_BOOLEAN -> true
        Opcode.MOVE_RESULT -> getOrNull(index - 1)?.methodReference()?.returnType == "Z"
        else -> false
    }
}

private fun Method.isStub(name: String, count: Int) =
    this.name == name && AccessFlags.STATIC.isSet(accessFlags) && returnType == OBJECT && parameterTypes.map(Any::toString) == List(count) { OBJECT }

private fun Method.reference(): MethodReference =
    ImmutableMethodReference(definingClass, name, parameterTypes.map(Any::toString), returnType)

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

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/** The index the branch at [index] lands on, or -1. */
private fun List<Instruction>.target(index: Int): Int {
    val address = IntArray(size + 1)
    forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
    return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
}
