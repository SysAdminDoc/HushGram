/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.media.taptoplay.findPlayInternal
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val PATCH = "Keep playing in the background"

internal const val BACKGROUND_PLAY = "$EXTENSION_PACKAGE/media/BackgroundPlay;"

/** Asked with the player and the reason before each pause and stop; yes keeps the video playing. */
internal const val HOLD = "$BACKGROUND_PLAY->hold(Ljava/lang/Object;Ljava/lang/String;)Z"
internal const val RELEASED = "$BACKGROUND_PLAY->released(Ljava/lang/Object;)V"
internal const val STARTED_PLAYING = "$BACKGROUND_PLAY->started(Ljava/lang/Object;)V"
internal const val VOLUME = "$BACKGROUND_PLAY->volume(Ljava/lang/Object;F)V"
internal const val LEAVING = "$BACKGROUND_PLAY->leaving(Landroid/app/Activity;)V"

/** The extension's foreground service, which the manifest has to declare. */
internal const val BACKGROUND_PLAY_SERVICE = "app.hushgram.extension.instagram.media.BackgroundPlayService"
internal val SERVICE_PERMISSIONS = listOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")

/** The activity every Instagram screen extends, which Redex leaves under its own name. */
private const val IG_ACTIVITY = "Lcom/instagram/base/activity/IgFragmentActivity;"

/** The strings IgVideoPlayerImpl's pause and stop compare their reason with, or log. */
internal const val FRAGMENT_PAUSED = "fragment_paused"
internal const val SINGLE_TAP_PAUSED = "single_tap_paused"
internal const val UI_THREAD = "This operation must be run on UI thread."
internal const val RESUME = "resume"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * A resource patch, so Manager decodes the manifest and writes the edited copy back. Declared
 * before [backgroundPlayPatch], since a file's vals start in order and a later one is still null
 * when the patch that depends on it is built.
 */
internal val backgroundPlayServicePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use(::addBackgroundPlayService)
    }
}

/**
 * A feed video or a reel playing with sound keeps going when you leave Instagram or turn the
 * screen off, with a notification to pause or stop it (#497).
 *
 * Instagram stops its players as each screen pauses: the fragment hands IgVideoPlayerImpl's pause
 * or stop "fragment_paused", and a few other screens release the player outright. The extension
 * keeps a list of the players that started lately (playInternal tells it) and the volume each was
 * set to, and when you leave Instagram (the activity's leave hint, or the screen going off) it
 * picks the one still playing with sound. Until that activity stops, and while the extension's
 * media service runs after it, the pause and the stop ask the extension first and return without
 * doing anything when it says to hold. Audio focus, a mute, a release, coming back to Instagram or
 * the notification's Stop all end that, and a release always goes through. The notification's
 * Pause and Play reach the player through stubs this patch fills. The manifest gets the service,
 * and the foreground service permissions when Instagram doesn't ask for them already.
 */
@Suppress("unused")
val backgroundPlayPatch = bytecodePatch(
    name = "Keep playing in the background",
    description = "A video or reel playing with sound keeps going when you leave Instagram or turn the screen off, " +
        "with a notification to pause or stop it. Starts off. Turn it on in HushGram settings > Playback.",
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch, backgroundPlayServicePatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("backgroundPlay")
        keepPlayingInBackground(findBackgroundPlaySites())
        enableStatus("backgroundPlay")
    }
}

/**
 * The methods this patch changes or calls, all IgVideoPlayerImpl's, [player], but [leaveHint]:
 * [playInternal], [pause] (String), [stop] (String, boolean), [release] (String), [play] (String,
 * boolean), [isPlaying] and [setVolume] (float, int), and [engine], its IgGrootPlayer field.
 */
internal class BackgroundPlaySites(
    val player: String,
    val playInternal: Method,
    val pause: Method,
    val stop: Method,
    val release: Method,
    val play: Method,
    val isPlaying: MethodReference,
    val setVolume: Method,
    val engine: String,
    val leaveHint: Method,
)

/**
 * Finds [BackgroundPlaySites] before anything changes. The player is the class of playInternal.
 * Its pause is the one instance (String) method loading both [FRAGMENT_PAUSED] and
 * [SINGLE_TAP_PAUSED], and its stop the one (String, boolean) loading [UI_THREAD] and
 * [FRAGMENT_PAUSED]. The release is the one other (String) method calling the stop, and the play the
 * one other (String, boolean) calling playInternal and loading [RESUME]. Whether it plays is the one
 * no-argument boolean of its own that both the pause and the stop ask, and the volume setter is the
 * (float, int) method clamping the volume with Math.max and Math.min. The engine field is the one of
 * the type the pause hands its reason to. Everything the extension calls or reads from outside the
 * player's package has to be public.
 */
internal fun BytecodePatchContext.findBackgroundPlaySites(): BackgroundPlaySites {
    val playInternal = findPlayInternal(PATCH)
    val player = playInternal.definingClass
    val playerClass = classDefBy(player)
    val instance = playerClass.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }

    val pause = instance.single("a pause (String) loading \"$FRAGMENT_PAUSED\" and \"$SINGLE_TAP_PAUSED\"", player) {
        it.takes(STRING) && it.returnType == "V" && loadsString(it, FRAGMENT_PAUSED) && loadsString(it, SINGLE_TAP_PAUSED)
    }
    val stop = instance.single("a stop (String, boolean) loading \"$UI_THREAD\" and \"$FRAGMENT_PAUSED\"", player) {
        it.takes(STRING, "Z") && it.returnType == "V" && loadsString(it, UI_THREAD) && loadsString(it, FRAGMENT_PAUSED)
    }
    val release = instance.single("a release (String) calling the stop", player) { method ->
        method !== pause && method.takes(STRING) && method.returnType == "V" &&
            method.code().any { it.opcode == Opcode.INVOKE_VIRTUAL && it.methodReference()?.key() == stop.key() }
    }
    val play = instance.single("a play (String, boolean) calling playInternal and loading \"$RESUME\"", player) { method ->
        method !== stop && method.takes(STRING, "Z") && method.returnType == "V" && loadsString(method, RESUME) &&
            method.code().any { it.methodReference()?.key() == playInternal.key() }
    }
    val setVolume = instance.single("a volume setter (float, int) clamping with Math.max and Math.min", player) { method ->
        method.takes("F", "I") && method.returnType == "V" && method.code().mapNotNull { it.methodReference()?.key() }
            .containsAll(listOf("Ljava/lang/Math;->max(FF)F", "Ljava/lang/Math;->min(FF)F"))
    }

    val asked = listOf(pause, stop).map { method ->
        method.code().filter { it.opcode == Opcode.INVOKE_VIRTUAL }.mapNotNull { it.methodReference() }
            .filter { it.definingClass == player && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            .associateBy { it.key() }
    }
    val playing = asked[0].keys.intersect(asked[1].keys)
    val isPlaying = playing.singleOrNull()?.let { asked[0].getValue(it) }
        ?: throw PatchException("$PATCH: expected one ()boolean of $player both its pause and its stop ask, found ${playing.size}: $playing")

    // The pause hands its own reason on to the engine's pause, a (String) method of the engine's class.
    val reason = pause.parameterRegisterNumber(0)
    val engines = pause.code().filter { it.opcode == Opcode.INVOKE_VIRTUAL }.mapNotNull { instruction ->
        val call = instruction.methodReference() ?: return@mapNotNull null
        call.definingClass.takeIf {
            it != player && call.returnType == "V" && call.parameterTypes.map(Any::toString) == listOf(STRING) &&
                (instruction as FiveRegisterInstruction).registerD == reason
        }
    }.toSet()
    val engineType = engines.singleOrNull()
        ?: throw PatchException("$PATCH: expected the pause of $player to hand its reason to one engine, found ${engines.size}: $engines")
    val engineFields = playerClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == engineType }
    val engineField = engineFields.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $engineType field in $player, found ${engineFields.size}")

    val activity = classDefByOrNull(IG_ACTIVITY) ?: throw PatchException("$PATCH: this build has no $IG_ACTIVITY")
    val leaveHint = activity.methods.singleOrNull {
        it.name == "onUserLeaveHint" && it.returnType == "V" && it.parameterTypes.isEmpty() && it.implementation != null
    } ?: throw PatchException("$PATCH: $IG_ACTIVITY has no onUserLeaveHint of its own")

    val isPlayingMethod = playerClass.methods.firstOrNull { it.key() == isPlaying.key() }
        ?: throw PatchException("$PATCH: ${isPlaying.key()} isn't declared in $player")
    val hidden = listOf(
        player to playerClass.accessFlags,
        pause.key() to pause.accessFlags,
        play.key() to play.accessFlags,
        isPlaying.key() to isPlayingMethod.accessFlags,
        "$player->${engineField.name}" to engineField.accessFlags,
    ).filter { !AccessFlags.PUBLIC.isSet(it.second) }.map { it.first }
    if (hidden.isNotEmpty()) throw PatchException("$PATCH: the extension can't reach ${hidden.joinToString()}, not public")

    return BackgroundPlaySites(
        player, playInternal, pause, stop, release, play, isPlaying, setVolume,
        "$player->${engineField.name}:$engineType", leaveHint,
    )
}

/**
 * Puts the hooks in, each first in its method after the hooks other patches put first there: the
 * pause and the stop return at once when [HOLD] says to, the release tells [RELEASED], playInternal
 * tells [STARTED_PLAYING], the volume setter tells [VOLUME] what it was asked for, and the
 * activity's leave hint tells [LEAVING]. Then fills the extension's stubs, which ask whether a
 * player plays, pause and play it, and read its engine.
 *
 * Resume long videos puts its stopped hook first in the pause and the stop, and Tap to play its
 * start guard first in playInternal, and their contracts want them first. Going in after them
 * leaves the same code whichever patch runs first.
 */
internal fun BytecodePatchContext.keepPlayingInBackground(sites: BackgroundPlaySites) {
    for (method in listOf(sites.pause, sites.stop)) {
        mutable(method).apply {
            requireLocals(PATCH, 1)
            addAfterLeadingHooks(
                """
                    invoke-static/range { p0 .. p1 }, $HOLD
                    move-result v0
                    if-eqz v0, :start
                    return-void
                """,
                guard = true,
            )
        }
    }
    mutable(sites.release).addAfterLeadingHooks("invoke-static/range { p0 .. p0 }, $RELEASED")
    mutable(sites.playInternal).addAfterLeadingHooks("invoke-static/range { p0 .. p0 }, $STARTED_PLAYING")
    mutable(sites.setVolume).addAfterLeadingHooks("invoke-static/range { p0 .. p1 }, $VOLUME")
    mutable(sites.leaveHint).addAfterLeadingHooks("invoke-static/range { p0 .. p0 }, $LEAVING")

    val player = sites.player
    fillStub(
        BACKGROUND_PLAY, "playing", "Z", 2,
        """
            instance-of v0, p0, $player
            if-eqz v0, :answer
            check-cast p0, $player
            invoke-virtual { p0 }, ${sites.isPlaying.key()}
            move-result v0
            :answer
            return v0
        """,
    )
    fillStub(
        BACKGROUND_PLAY, "pause", "V", 2,
        """
            instance-of v0, p0, $player
            if-eqz v0, :done
            check-cast p0, $player
            const-string v0, "$FRAGMENT_PAUSED"
            invoke-virtual { p0, v0 }, ${sites.pause.key()}
            :done
            return-void
        """,
    )
    fillStub(
        BACKGROUND_PLAY, "play", "V", 3,
        """
            instance-of v0, p0, $player
            if-eqz v0, :done
            check-cast p0, $player
            const-string v0, "$RESUME"
            const/4 v1, 0x0
            invoke-virtual { p0, v0, v1 }, ${sites.play.key()}
            :done
            return-void
        """,
    )
    fillStub(
        BACKGROUND_PLAY, "engine", OBJECT, 2,
        """
            instance-of v0, p0, $player
            if-nez v0, :player
            const/4 v0, 0x0
            return-object v0
            :player
            check-cast p0, $player
            iget-object v0, p0, ${sites.engine}
            return-object v0
        """,
    )
}

/**
 * Declares the extension's media service in [manifest], not exported, for media playback, and asks
 * for the two foreground service permissions it needs when the manifest doesn't already. Instagram
 * 454 asks for both itself, so on it only the service is new. Run twice, it changes nothing more.
 */
internal fun addBackgroundPlayService(manifest: Document) {
    val root = manifest.documentElement
    val applications = root.elements("application")
    val application = applications.singleOrNull()
        ?: throw PatchException("$PATCH: expected one application in the manifest, found ${applications.size}")
    val asked = root.elements("uses-permission").mapTo(HashSet()) { it.getAttribute("android:name") }
    for (permission in SERVICE_PERMISSIONS.filter { it !in asked }) {
        root.insertBefore(manifest.createElement("uses-permission").apply { setAttribute("android:name", permission) }, application)
    }
    if (application.elements("service").any { it.getAttribute("android:name") == BACKGROUND_PLAY_SERVICE }) return
    application.appendChild(
        manifest.createElement("service").apply {
            setAttribute("android:name", BACKGROUND_PLAY_SERVICE)
            setAttribute("android:exported", "false")
            setAttribute("android:foregroundServiceType", "mediaPlayback")
        },
    )
}

private fun Element.elements(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.key() == method.key() }

/** What a hook another patch put first may load its arguments with before its call. */
private val LEADING_LOADS = setOf(
    Opcode.IGET, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN, Opcode.IGET_WIDE,
    Opcode.SGET, Opcode.SGET_OBJECT, Opcode.SGET_BOOLEAN,
    Opcode.INSTANCE_OF, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_STRING,
    Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16,
)

private val LEADING_RESULTS = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE)

/**
 * Where the hooks other patches put first in this method end: past each call into a HushGram
 * extension at the top, with the loads before it, its move-result, and the branch and return-void
 * of a guard. Loads after the last call are the method's own, so they stay after.
 */
private fun MutableMethod.afterLeadingHooks(): Int {
    val code = implementation?.instructions?.toList() ?: emptyList()
    var end = 0
    var index = 0
    while (index < code.size) {
        val instruction = code[index]
        val call = instruction.methodReference()
        if ((instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call != null && call.definingClass.startsWith(EXTENSION_ROOT)
        ) {
            index++
            if (index < code.size && code[index].opcode in LEADING_RESULTS) {
                index++
                if (index + 1 < code.size && (code[index].opcode == Opcode.IF_EQZ || code[index].opcode == Opcode.IF_NEZ) &&
                    code[index + 1].opcode == Opcode.RETURN_VOID
                ) {
                    index += 2
                }
            }
            end = index
        } else if (instruction.opcode in LEADING_LOADS) {
            index++
        } else {
            break
        }
    }
    return end
}

/**
 * Puts [smali] in after [afterLeadingHooks]. A [guard]'s `:start` lands on what came next, the
 * method's own code, and a jump an earlier guard made past itself onto that code lands on [smali]
 * instead, so its path still runs the hook.
 */
private fun MutableMethod.addAfterLeadingHooks(smali: String, guard: Boolean = false) {
    val at = afterLeadingHooks()
    val code = implementation!!
    val before = code.instructions.size
    if (guard) addInstructionsWithLabels(at, smali, ExternalLabel("start", getInstruction(at))) else addInstructions(at, smali)
    if (at == 0) return
    val next = at + code.instructions.size - before
    for (index in 0 until at) {
        val jump = code.instructions[index] as? BuilderInstruction21t ?: continue
        if (jump.target.location.index != next) continue
        code.replaceInstruction(index, BuilderInstruction21t(jump.opcode, jump.registerA, code.newLabelForIndex(at)))
    }
}

private fun List<Method>.single(what: String, player: String, wanted: (Method) -> Boolean): Method {
    val found = filter(wanted)
    return found.singleOrNull()
        ?: throw PatchException("$PATCH: expected $what in $player, found ${found.size}${found.joinToString(prefix = ": ") { it.name }.takeIf { found.isNotEmpty() }.orEmpty()}")
}

private fun Method.takes(vararg types: String) = parameterTypes.map(Any::toString) == types.toList()

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
