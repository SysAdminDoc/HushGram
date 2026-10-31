/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.loop

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.media.taptoplay.ReelsLogger
import app.morphe.patches.instagram.media.taptoplay.findPlayInternal
import app.morphe.patches.instagram.media.taptoplay.findReelsLogger
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Stop videos looping"

internal const val STOP_LOOPING = "$EXTENSION_PACKAGE/media/StopLooping;"

/** Asked with the player and the loop flag it's about to hand its engine; answers the flag the engine gets. */
internal const val LOOP = "$STOP_LOOPING->loop(Ljava/lang/Object;I)Z"

/** The log line of the player engine's own setLooping, the one method of it holding this string. */
internal const val SET_LOOPING = "setLooping: %s"

/**
 * Feed videos and reels stop on their last frame instead of playing again, through
 * [stopVideoLooping]. A reel in the Reels viewer still loops while auto scroll is moving Reels on,
 * since the scroller moves on when a reel loops. Stories play through another player. In the
 * default selection with its switch off (#248, #175).
 */
@Suppress("unused")
val stopVideoLoopingPatch = bytecodePatch(
    name = "Stop videos looping",
    description = "Feed videos and reels stop at their last frame instead of playing again. Reels still loop " +
        "while auto scroll is on, so it can move on. Starts off. Turn it on in HushGram settings > Playback.",
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("stopLooping")
        stopVideoLooping()?.let { throw PatchException("$PATCH: $it") }
        enableStatus("stopLooping")
    }
}

/**
 * Where IgVideoPlayerImpl hands its engine the loop flag: [player], the class holding playInternal,
 * [setLooping], the engine's (boolean) method that reaches the core's own setLooping, and [sites],
 * each method of the player calling it with the index of each call. [logger] tells a player made
 * for the Reels viewer.
 */
internal class LoopSites(
    val player: String,
    val setLooping: String,
    val sites: List<Pair<MutableMethod, List<Int>>>,
    val logger: ReelsLogger,
)

/**
 * Finds [LoopSites]. The core's setLooping is an instance (boolean)void loading [SET_LOOPING]; on
 * 453 that's the Heaviside manager, whose method posts the flag to its handler. IgGrootPlayer, the
 * engine IgVideoPlayerImpl keeps, wraps it in a (boolean)void that reads the manager and calls it,
 * and that wrapper must be the one (boolean)void the player calls whose body calls a core.
 * playInternal calls it once with the flag it chose for the video starting; the player's own setter
 * calls it with a new flag. Every call in the player's class is a site. The sites are read from the
 * player as Tap to play and the other player patches may already have changed it, so their indices
 * hold whichever runs first.
 */
internal fun BytecodePatchContext.findLoopSites(): LoopSites {
    val playInternal = findPlayInternal(PATCH)
    val player = playInternal.definingClass
    val cores = classesLoadingString(SET_LOOPING).flatMap { classDef ->
        classDef.methods.filter { it.takesOneBoolean() && !AccessFlags.STATIC.isSet(it.accessFlags) && loadsString(it, SET_LOOPING) }
    }.mapTo(HashSet()) { it.key() }
    if (cores.isEmpty()) throw PatchException("$PATCH: no instance (boolean)void loads \"$SET_LOOPING\"")

    val playerClass = mutableClassDefBy(player)
    val called = playerClass.methods.flatMap { method ->
        method.code().filter { it.opcode == Opcode.INVOKE_VIRTUAL }.mapNotNull { it.methodReference() }
    }.filter { it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Z") }.distinctBy { it.key() }
    val wrappers = called.filter { target ->
        val declared = classDefByOrNull(target.definingClass)?.methods?.firstOrNull { it.key() == target.key() }
        declared != null && declared.code().any { it.methodReference()?.key() in cores }
    }
    val setLooping = wrappers.singleOrNull()?.key()
        ?: throw PatchException(
            "$PATCH: expected one (boolean)void $player calls that calls the core's setLooping, found ${wrappers.size}: " +
                wrappers.joinToString { it.key() },
        )

    val sites = playerClass.methods.mapNotNull { method ->
        val code = method.code()
        code.indices.filter { code[it].methodReference()?.key() == setLooping }.takeIf { it.isNotEmpty() }?.let { method to it }
    }
    val inPlayInternal = sites.singleOrNull { it.first.key() == playInternal.key() }?.second.orEmpty()
    if (inPlayInternal.size != 1) {
        throw PatchException("$PATCH: playInternal, $player->${playInternal.name}, calls $setLooping ${inPlayInternal.size} times, expected once")
    }
    sites.forEach { (method, indices) -> indices.forEach { method.requirePlaceable(it) } }

    // StopLooping.reels reads the logger from outside the player's package.
    val logger = findReelsLogger(player)
    val loggerField = playerClass.fields.single { it.name == logger.field.name && it.type == logger.field.type }
    val hidden = listOf(player to playerClass.accessFlags, logger.field.toString() to loggerField.accessFlags, logger.type to classDefBy(logger.type).accessFlags)
        .filter { !AccessFlags.PUBLIC.isSet(it.second) }.map { it.first }
    if (hidden.isNotEmpty()) throw PatchException("$PATCH: the extension can't read ${hidden.joinToString()}, not public")
    return LoopSites(player, setLooping, sites, logger)
}

/**
 * Hands each loop flag the player gives its engine to [LOOP] first, with the player, and lets the
 * engine take the answer in the flag's own register. Writes the body of StopLooping.reels, which
 * tells a player made for the Reels viewer by its video logger. Answers why it couldn't, having
 * changed nothing, or null.
 */
internal fun BytecodePatchContext.stopVideoLooping(): String? {
    val loop = try {
        findLoopSites()
    } catch (failure: PatchException) {
        return failure.message?.removePrefix("$PATCH: ")
    }
    for ((target, indices) in loop.sites) {
        val self = target.localRegisterCount()
        // Last call first, so an earlier one keeps its index.
        for (index in indices.sortedDescending()) {
            val flag = (target.getInstruction(index) as FiveRegisterInstruction).registerD
            target.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static { v$self, v$flag }, $LOOP
                    move-result v$flag
                """,
            )
        }
    }
    fillStub(
        STOP_LOOPING, "reels", "Z", 2,
        """
            instance-of v0, p0, ${loop.player}
            if-eqz v0, :answer
            check-cast p0, ${loop.player}
            iget-object v0, p0, ${loop.logger.field}
            instance-of v0, v0, ${loop.logger.type}
            :answer
            return v0
        """,
    )
    return null
}

/**
 * Throws unless the call at [index] can take the hook in front of it: the player, the method's
 * first parameter or `this`, still in its own register and in v15 or below, and the flag in a
 * register nothing reads after the call, since the hook's answer stays in it.
 */
private fun Method.requirePlaceable(index: Int) {
    val call = code()[index] as? FiveRegisterInstruction
        ?: throw PatchException("$PATCH: $definingClass->$name's call at $index is a ${code()[index].opcode}, expected a plain invoke")
    if (call.registerCount != 2) throw PatchException("$PATCH: $definingClass->$name's call at $index passes ${call.registerCount} registers, expected the engine and the flag")
    if (AccessFlags.STATIC.isSet(accessFlags)) {
        if (parameterTypes.firstOrNull()?.toString() != definingClass) {
            throw PatchException("$PATCH: $definingClass->$name is static and doesn't take the player first")
        }
        requireParameterIntact(PATCH, 0, listOf(index))
    } else {
        requireThisIntact(PATCH, listOf(index))
    }
    val self = localRegisterCount()
    if (self > 15) throw PatchException("$PATCH: $definingClass->$name keeps the player in v$self, past v15")
    if (call.registerD in liveAcrossInjection(index + 1)) {
        throw PatchException("$PATCH: $definingClass->$name reads the loop flag, v${call.registerD}, after its call at $index")
    }
}

private fun Method.takesOneBoolean() = returnType == "V" && parameterTypes.map(Any::toString) == listOf("Z")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
