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
import app.morphe.patches.instagram.media.background.RESUME
import app.morphe.patches.instagram.media.resume.SEEK
import app.morphe.patches.instagram.media.resume.findPlayerVideoId
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
import app.morphe.patches.instagram.misc.extension.patchLog
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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Stop videos looping"

internal const val STOP_LOOPING = "$EXTENSION_PACKAGE/media/StopLooping;"

/** Asked with the player and the loop flag it's about to hand its engine; answers the flag the engine gets. */
internal const val LOOP = "$STOP_LOOPING->loop(Ljava/lang/Object;I)Z"

/** The log line of the player engine's own setLooping, the one method of it holding this string. */
internal const val SET_LOOPING = "setLooping: %s"

private const val STRING = "Ljava/lang/String;"

/** A day in milliseconds, past which the player's position reader answers 0. */
private const val DAY_MS = 86_400_000

/**
 * Feed videos and reels stop on their last frame instead of playing again, through
 * [stopVideoLooping]. A reel in the Reels viewer still loops while auto scroll is moving Reels on,
 * since the scroller moves on when a reel loops. A video looped from its menu's Loop this video
 * row loops anyway (#610). Stories play through another player. In the default selection with its
 * switch off (#248, #175).
 */
@Suppress("unused")
val stopVideoLoopingPatch = bytecodePatch(
    name = "Stop videos looping",
    description = "Feed videos and reels stop at their last frame instead of playing again. Reels still loop " +
        "while auto scroll is on, so it can move on. Loop this video in a video's menu loops that one anyway. " +
        "Starts off. Turn it on in HushGram settings > Playback.",
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
 * for the Reels viewer. [playInternal] is the player's own, as a key.
 */
internal class LoopSites(
    val player: String,
    val setLooping: String,
    val sites: List<Pair<MutableMethod, List<Int>>>,
    val logger: ReelsLogger,
    val playInternal: String,
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
    return LoopSites(player, setLooping, sites, logger, playInternal.key())
}

/**
 * Hands each loop flag the player gives its engine to [LOOP] first, with the player, and lets the
 * engine take the answer in the flag's own register. Writes the bodies of StopLooping.reels, which
 * tells a player made for the Reels viewer by its video logger, StopLooping.videoId, which reads
 * the media ID of what a player plays, and StopLooping.reloop, which hands a player's own flag back
 * to its setter so a loop chosen from a menu takes at once. Fills StopLooping.position, length and
 * restart too, which play a video that already ended again when it's looped from its menu, unless
 * the player's readers, seek or play can't be found, which only warns. Answers why it couldn't,
 * having changed nothing, or null.
 */
internal fun BytecodePatchContext.stopVideoLooping(): String? {
    val loop = try {
        findLoopSites()
    } catch (failure: PatchException) {
        return failure.message?.removePrefix("$PATCH: ")
    }
    val videoId = try {
        findPlayerVideoId(classDefBy(loop.player))
    } catch (failure: PatchException) {
        return "can't read the media ID of what the player plays (${failure.message})"
    }
    val (setter, flag) = loopSetter(loop) ?: return "no public (boolean)void of ${loop.player} keeps its flag in a public field and calls ${loop.setLooping}"
    val restart = findRestartOrWarn(loop)
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
    fillStub(
        STOP_LOOPING, "videoId", "Ljava/lang/String;", 2,
        """
            instance-of v0, p0, ${loop.player}
            if-eqz v0, :none
            check-cast p0, ${loop.player}
            iget-object v0, p0, ${videoId.holder}
            if-eqz v0, :none
            iget-object v0, v0, ${videoId.source}
            if-eqz v0, :none
            iget-object v0, v0, ${videoId.mediaId}
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
    fillStub(
        STOP_LOOPING, "reloop", "V", 2,
        """
            instance-of v0, p0, ${loop.player}
            if-eqz v0, :done
            check-cast p0, ${loop.player}
            iget-boolean v0, p0, $flag
            invoke-virtual { p0, v0 }, ${setter.key()}
            :done
            return-void
        """,
    )
    restart?.let { fillRestart(loop.player, it) }
    return null
}

/**
 * What StopLooping.restart needs of the player, all public instance methods: [position] and
 * [length], its no-argument int readers in milliseconds, [seek] (int, boolean, boolean) and [play]
 * (String, boolean), the one Instagram resumes a video through.
 */
internal class RestartSites(val position: Method, val length: Method, val seek: Method, val play: Method)

/**
 * Finds [RestartSites] in the player the way Resume long videos and Play in background do. The
 * position is the int reader loading [DAY_MS], which it answers 0 past, and the length the other
 * one narrowing a long. The seek is the one (int, boolean, boolean) loading each of [SEEK], and the
 * play the one (String, boolean) loading [RESUME] and calling playInternal.
 */
internal fun BytecodePatchContext.findRestart(loop: LoopSites): RestartSites {
    val methods = classDefBy(loop.player).methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
    fun single(what: String, filter: (Method) -> Boolean): Method {
        val found = methods.filter(filter)
        return found.singleOrNull() ?: throw PatchException("$PATCH: expected one $what in ${loop.player}, found ${found.size}")
    }
    fun Method.reads() = returnType == "I" && parameterTypes.isEmpty()
    val position = single("int reader loading $DAY_MS") { it.reads() && it.code().any { instruction -> instruction.loads(DAY_MS) } }
    val length = single("other int reader narrowing a long") { method ->
        method.reads() && method !== position && method.code().any { it.opcode == Opcode.LONG_TO_INT }
    }
    val seek = single("(int, boolean, boolean) seek loading $SEEK") { method ->
        method.returnType == "V" && method.parameterTypes.map(Any::toString) == listOf("I", "Z", "Z") &&
            SEEK.all { loadsString(method, it) }
    }
    val play = single("(String, boolean) play loading \"$RESUME\" and calling playInternal") { method ->
        method.returnType == "V" && method.parameterTypes.map(Any::toString) == listOf(STRING, "Z") &&
            loadsString(method, RESUME) && method.code().any { it.methodReference()?.key() == loop.playInternal }
    }
    val hidden = listOf(position, length, seek, play).filter { !AccessFlags.PUBLIC.isSet(it.accessFlags) }
    if (hidden.isNotEmpty()) throw PatchException("$PATCH: the extension can't call ${hidden.joinToString { it.key() }}, not public")
    return RestartSites(position, length, seek, play)
}

/** [findRestart], or null with a warning in the patch log when the build isn't one it can read. */
private fun BytecodePatchContext.findRestartOrWarn(loop: LoopSites): RestartSites? = try {
    findRestart(loop)
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without playing an ended video again when it's looped from its menu.")
    null
}

/**
 * Fills StopLooping.position and length with the player's own readers, -1 for anything else, and
 * StopLooping.restart with a seek to the start and a play, as Instagram resumes a video. The seek
 * neither logs nor jumps exactly, as Resume long videos seeks.
 */
private fun BytecodePatchContext.fillRestart(player: String, restart: RestartSites) {
    for ((name, reader) in listOf("position" to restart.position, "length" to restart.length)) {
        fillStub(
            STOP_LOOPING, name, "I", 2,
            """
                instance-of v0, p0, $player
                if-eqz v0, :none
                check-cast p0, $player
                invoke-virtual { p0 }, ${reader.key()}
                move-result v0
                return v0
                :none
                const/4 v0, -0x1
                return v0
            """,
        )
    }
    fillStub(
        STOP_LOOPING, "restart", "V", 3,
        """
            instance-of v0, p0, $player
            if-eqz v0, :done
            check-cast p0, $player
            const/4 v0, 0x0
            invoke-virtual { p0, v0, v0, v0 }, ${restart.seek.key()}
            const-string v0, "$RESUME"
            const/4 v1, 0x0
            invoke-virtual { p0, v0, v1 }, ${restart.play.key()}
            :done
            return-void
        """,
    )
}

/**
 * The player's own loop setter, a public (boolean)void site that stores its flag in a public
 * boolean field of the player before handing it on, and that field. StopLooping.reloop calls it
 * with the flag it already holds, so a video looped from its menu changes at once. Null when there
 * isn't exactly one.
 */
private fun BytecodePatchContext.loopSetter(loop: LoopSites): Pair<Method, FieldReference>? {
    val fields = classDefBy(loop.player).fields
    return loop.sites.mapNotNull { (method, _) ->
        if (!method.takesOneBoolean() || AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) {
            return@mapNotNull null
        }
        val parameter = (method.implementation?.registerCount ?: return@mapNotNull null) - 1
        val stored = method.code().filter { it.opcode == Opcode.IPUT_BOOLEAN && (it as TwoRegisterInstruction).registerA == parameter }
            .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
            .filter { it.definingClass == loop.player && it.type == "Z" }
            .distinctBy { it.toString() }.singleOrNull() ?: return@mapNotNull null
        val declared = fields.singleOrNull { it.name == stored.name && it.type == "Z" }
        if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) null
        else method to stored
    }.singleOrNull()
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

private fun Instruction.loads(value: Int): Boolean =
    (opcode == Opcode.CONST || opcode == Opcode.CONST_HIGH16) && (this as NarrowLiteralInstruction).narrowLiteral == value

private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
