/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tapvolume

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.media.taptoplay.CLIPS_PAUSE
import app.morphe.patches.instagram.media.taptoplay.FUNCTION0
import app.morphe.patches.instagram.media.taptoplay.TOGGLE_PAUSE
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Control taps and volume on Reels"
internal const val REEL_TAP_AND_VOLUME = "$EXTENSION_PACKAGE/reels/ReelTapAndVolume;"
internal const val MUTE_INSTEAD_OF_PAUSE = "$REEL_TAP_AND_VOLUME->muteInsteadOfPause()Z"
internal const val KEEP_MUTED = "$REEL_TAP_AND_VOLUME->keepMuted(II)Z"

/** The purge marker, less its release, of the Reels controller's volume runnable: where it unmutes after a key press. */
internal const val VOLUME_ADJUSTED = "ClipsVideoPlayerController_onVolumeAdjustedByUser"

/** The purge marker, less its release, of the Reels controller's audio toggle: the method the audio button calls. */
internal const val TOGGLE_AUDIO = "ClipsVideoPlayerController_toggleAudio"

/** The reason the audio button hands the toggle, which the tap hands it too. */
private const val BUTTON_REASON = -3

private const val OBJECT = "Ljava/lang/Object;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Lets you choose what a tap on a reel does, and keeps a reel muted on the volume keys.
 *
 * Instagram 450 sends a single tap on a reel to its pause and mute navigator's tap method
 * ([TOGGLE_PAUSE]), which resumes a reel you paused and otherwise takes its [CLIPS_PAUSE] path and
 * pauses the one that is playing. The patch asks the extension at the start of that pause path
 * whether to mute instead. A yes calls the Reels controller's audio toggle ([TOGGLE_AUDIO]), the
 * one the audio button calls, and returns, so the reel keeps playing with its sound turned off or on.
 * A no goes on to the pause as before, so Instagram's default and Pause are Instagram's own tap.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val reelTapAndVolumePatch = bytecodePatch(
    name = "Control taps and volume on Reels",
    description = "Lets you choose what a single tap on a reel does: Instagram's default, pause, or mute. " +
        "Can also keep a reel muted when you press volume up. Both start at Instagram's behavior. " +
        "Choose them in HushGram settings > Playback.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelTapAndVolume")
        // Both sites are found before anything changes, so a build missing one changes nothing.
        val tapSite = findReelTapSite()
        val volumeSite = findVolumeSite()
        applyReelTap(tapSite)
        applyKeepMuted(volumeSite)
        enableStatus("reelTapAndVolume")
    }
}

/**
 * The Reels tap, the index where its pause path begins past the string it loads, and what the muting
 * call needs: the tap's field handing over the controller, the controller, its audio toggle, and the
 * two locals the hook may borrow there.
 */
internal class ReelTapSite(
    val tap: Method,
    val index: Int,
    val supplier: FieldReference,
    val controller: String,
    val toggle: Method,
    val scratch: List<Int>,
)

/**
 * The volume runnable, the index right after its [VOLUME_ADJUSTED] marker call, the field holding the
 * direction it gave AudioManager, and the local the hook may borrow there.
 */
internal class VolumeSite(
    val run: Method,
    val index: Int,
    val direction: FieldReference,
    val scratch: List<Int>,
    val audio: AudioState,
)

/**
 * How Instagram's own audio button reads whether Reels sound is on. [controllerField] is the runnable's
 * field holding the controller, [sessionField] the controller's field holding the user session,
 * [state] the static that hands that session's audio state over, and [isOn] the question the audio
 * toggle asks it before flipping it.
 */
internal class AudioState(
    val controllerField: FieldReference,
    val sessionField: FieldReference,
    val state: MethodReference,
    val isOn: MethodReference,
)

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * The tap is the one method marked [TOGGLE_PAUSE], and the audio toggle the one marked [TOGGLE_AUDIO],
 * an instance `(I)V` the tap's code can call. The tap loads [CLIPS_PAUSE] once, as the target of one
 * branch, and the hook goes in the straight line right after that load, where nothing jumps. The tap
 * reaches the controller through one Function0 field of its own, whose answer it casts to the
 * toggle's class, and still holds `this` there.
 */
internal fun BytecodePatchContext.findReelTapSite(): ReelTapSite {
    val taps = mutableListOf<Method>()
    val toggles = mutableListOf<Method>()
    val marked = typesMarked(TOGGLE_PAUSE, TOGGLE_AUDIO)
    classDefForEach { classDef ->
        if (classDef.type !in marked) return@classDefForEach
        classDef.methods.forEach { method ->
            val markers = method.markers()
            if (TOGGLE_PAUSE in markers) taps += method
            if (TOGGLE_AUDIO in markers) toggles += method
        }
    }
    val tap = taps.singleOrNull() ?: refuse("the Reels tap: expected one method marked $TOGGLE_PAUSE, found ${taps.size}")
    val toggle = toggles.singleOrNull() ?: refuse("the Reels audio toggle: expected one method marked $TOGGLE_AUDIO, found ${toggles.size}")
    val where = "${tap.definingClass}->${tap.name}"
    if (AccessFlags.STATIC.isSet(tap.accessFlags) || tap.returnType != "V") refuse("$where isn't an instance method returning nothing")
    val controller = toggle.definingClass
    if (AccessFlags.STATIC.isSet(toggle.accessFlags) || !AccessFlags.PUBLIC.isSet(toggle.accessFlags) || toggle.returnType != "V" ||
        toggle.parameterTypes.map(Any::toString) != listOf("I")
    ) {
        refuse("$controller->${toggle.name}, the audio toggle, isn't a public instance void (int)")
    }
    val controllerPublic = classDefBy(controller).let { AccessFlags.PUBLIC.isSet(it.accessFlags) }
    if (!controllerPublic && controller.substringBeforeLast('/') != tap.definingClass.substringBeforeLast('/')) {
        refuse("$controller isn't public, so $where can't call its audio toggle")
    }

    val code = tap.code()
    val pausePath = code.indices.filter { code[it].stringLoaded() == CLIPS_PAUSE }.singleOrNull()
        ?: refuse("$where doesn't load \"$CLIPS_PAUSE\" once")
    val branches = code.indices.filter { code[it].opcode == Opcode.IF_EQZ && code.target(it) == pausePath }
    if (branches.size != 1) refuse("expected one branch to the pause path of $where, found ${branches.size}")
    val index = pausePath + 1
    if (index !in code.indices) refuse("$where ends on its load of \"$CLIPS_PAUSE\"")
    if (index in tap.jumpTargets()) refuse("something in $where jumps to the instruction after its load of \"$CLIPS_PAUSE\"")

    val suppliers = code.indices.mapNotNull { at ->
        val field = code[at].fieldReference() ?: return@mapNotNull null
        if (code[at].opcode != Opcode.IGET_OBJECT || field.definingClass != tap.definingClass || field.type != FUNCTION0) return@mapNotNull null
        val invoked = code.getOrNull(at + 1)?.methodReference()
        val cast = code.getOrNull(at + 3)
        if (invoked?.definingClass != FUNCTION0 || cast?.opcode != Opcode.CHECK_CAST ||
            ((cast as ReferenceInstruction).reference as TypeReference).type != controller
        ) return@mapNotNull null
        field
    }.distinctBy { it.toString() }
    val supplier = suppliers.singleOrNull() ?: refuse("expected one field of $where handing over $controller, found ${suppliers.size}")

    // The hook reads the supplier from `this` with an iget, so `this` must sit at or below v15 and be intact.
    if (tap.localRegisterCount() > 15) refuse("$where keeps this past v15")
    tap.requireThisIntact(PATCH, listOf(index))
    val scratch = tap.freeLocalsAt(PATCH, index, 2)
    return ReelTapSite(tap, index, supplier, controller, toggle, scratch)
}

/**
 * At the start of the tap's pause path: ask [MUTE_INSTEAD_OF_PAUSE], and on a yes toggle the
 * controller's audio and return. A no falls through to the pause.
 */
internal fun BytecodePatchContext.applyReelTap(site: ReelTapSite) {
    val (first, second) = site.scratch
    mutable(site.tap).apply {
        addInstructionsWithLabels(
            site.index,
            """
                invoke-static { }, $MUTE_INSTEAD_OF_PAUSE
                move-result v$first
                if-eqz v$first, :pause
                iget-object v$first, p0, ${site.supplier}
                invoke-interface { v$first }, $FUNCTION0->invoke()$OBJECT
                move-result-object v$first
                check-cast v$first, ${site.controller}
                const/4 v$second, $BUTTON_REASON
                invoke-virtual { v$first, v$second }, ${site.controller}->${site.toggle.name}(I)V
                return-void
            """,
            ExternalLabel("pause", getInstruction(site.index)),
        )
    }
}

/**
 * The volume runnable is the one `run()V` marked [VOLUME_ADJUSTED]. It adjusts the stream volume first,
 * giving AudioManager an int it reads from one field of its own class, then runs the controller's
 * unmute under the marker. The hook goes right after the marker's call, past the adjustment, where
 * nothing jumps, so the key still does its work and only the unmute can be skipped.
 */
internal fun BytecodePatchContext.findVolumeSite(): VolumeSite {
    val runs = mutableListOf<Method>()
    val marked = typesMarked(VOLUME_ADJUSTED)
    classDefForEach { classDef ->
        if (classDef.type !in marked) return@classDefForEach
        classDef.methods.forEach { method -> if (VOLUME_ADJUSTED in method.markers()) runs += method }
    }
    val run = runs.singleOrNull() ?: refuse("the volume runnable: expected one method marked $VOLUME_ADJUSTED, found ${runs.size}")
    val where = "${run.definingClass}->${run.name}"
    if (run.name != "run" || run.returnType != "V" || run.parameterTypes.isNotEmpty() || AccessFlags.STATIC.isSet(run.accessFlags)) {
        refuse("$where isn't an instance run()V")
    }
    val code = run.code()
    val adjusts = code.indices.filter { code[it].methodReference()?.let { call -> call.name == "adjustStreamVolume" && call.definingClass == AUDIO_MANAGER } == true }
    val adjust = adjusts.singleOrNull() ?: refuse("$where doesn't call AudioManager.adjustStreamVolume once")
    val call = code[adjust] as FiveRegisterInstruction
    if (call.registerCount != 4) refuse("$where gives adjustStreamVolume an unexpected number of registers")
    val directionRegister = call.registerE
    // The direction register is loaded by the nearest write before the call, which must be an iget of this class's int.
    val load = (adjust - 1 downTo 0).firstOrNull { at ->
        (code[at] as? com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)?.registerA == directionRegister
    } ?: refuse("$where never loads the direction it gives adjustStreamVolume")
    val direction = code[load].fieldReference()
    if (code[load].opcode != Opcode.IGET || direction == null || direction.definingClass != run.definingClass || direction.type != "I") {
        refuse("$where doesn't take the direction from an int field of its own")
    }
    val markerAt = code.indices.filter { code[it].stringLoaded() == markerString(VOLUME_ADJUSTED) }.singleOrNull()
        ?: refuse("$where doesn't load the $VOLUME_ADJUSTED marker once")
    if (adjust > markerAt) refuse("$where adjusts the volume after the $VOLUME_ADJUSTED marker")
    val index = markerAt + 2
    if (code.getOrNull(markerAt + 1)?.opcode != Opcode.INVOKE_STATIC || index !in code.indices) {
        refuse("$where doesn't report the $VOLUME_ADJUSTED marker with a static call")
    }
    if (index in run.jumpTargets()) refuse("something in $where jumps to just past the $VOLUME_ADJUSTED marker")
    if (run.localRegisterCount() > 15) refuse("$where keeps this past v15")
    run.requireThisIntact(PATCH, listOf(index))
    val scratch = run.freeLocalsAt(PATCH, index, 2)
    val audio = findAudioState(run, code)
    return VolumeSite(run, index, direction, scratch, audio)
}

/**
 * The audio toggle ([TOGGLE_AUDIO]) flips the reels' sound by taking the user session's audio state
 * object, asking it whether sound is on, and setting its opposite: a static taking the session and
 * returning the state, a no-argument boolean on the state, an xor with 1, then a boolean setter on the
 * state. The volume runnable's unmute sets that same state to on. The controller reaches the session
 * through one field of its own, and the runnable reaches the controller through one field of its own.
 * Found once, and reachable from the runnable, or the patch stops.
 */
private fun BytecodePatchContext.findAudioState(run: Method, runCode: List<Instruction>): AudioState {
    val where = "${run.definingClass}->${run.name}"
    val toggles = mutableListOf<Method>()
    val marked = typesMarked(TOGGLE_AUDIO)
    classDefForEach { classDef ->
        if (classDef.type !in marked) return@classDefForEach
        classDef.methods.forEach { method -> if (TOGGLE_AUDIO in method.markers()) toggles += method }
    }
    val toggle = toggles.singleOrNull() ?: refuse("the Reels audio toggle: expected one method marked $TOGGLE_AUDIO, found ${toggles.size}")
    val controller = toggle.definingClass
    val code = toggle.code()
    val found = mutableListOf<AudioState>()
    for (at in code.indices) {
        val factory = code[at].methodReference() ?: continue
        if (code[at].opcode != Opcode.INVOKE_STATIC || factory.parameterTypes.map(Any::toString) != listOf(USER_SESSION)) continue
        val stateType = factory.returnType
        val asks = code.getOrNull(at + 2)?.methodReference()
        val answer = code.getOrNull(at + 3) as? OneRegisterInstruction
        val flip = code.getOrNull(at + 4)
        val sets = code.getOrNull(at + 5)?.methodReference()
        if (code.getOrNull(at + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT || code[at + 2].opcode != Opcode.INVOKE_VIRTUAL ||
            asks?.definingClass != stateType || asks.returnType != "Z" || asks.parameterTypes.isNotEmpty() ||
            answer == null || code[at + 3].opcode != Opcode.MOVE_RESULT ||
            flip?.opcode != Opcode.XOR_INT_LIT8 || (flip as NarrowLiteralInstruction).narrowLiteral != 1 ||
            (flip as TwoRegisterInstruction).registerB != answer.registerA ||
            code[at + 5].opcode != Opcode.INVOKE_VIRTUAL || sets?.definingClass != stateType || sets.returnType != "V" ||
            sets.parameterTypes.map(Any::toString) != listOf("Z")
        ) continue
        // The session register the static takes was loaded by the nearest write before it: an iget-object of the controller's.
        val sessionRegister = (code[at] as? FiveRegisterInstruction)?.registerC ?: continue
        val load = (at - 1 downTo 0).firstOrNull { (code[it] as? OneRegisterInstruction)?.registerA == sessionRegister } ?: continue
        val session = code[load].fieldReference() ?: continue
        if (code[load].opcode != Opcode.IGET_OBJECT || session.definingClass != controller || session.type != USER_SESSION) continue
        val controllerFields = runCode.mapNotNull { instruction ->
            instruction.fieldReference()?.takeIf { instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == run.definingClass && it.type == controller }
        }.distinctBy { it.toString() }
        found += AudioState(controllerFields.singleOrNull() ?: continue, session, factory, asks)
    }
    val audio = found.distinctBy { "${it.sessionField}${it.state}${it.isOn}" }.singleOrNull()
        ?: refuse("$where: expected the audio toggle to read Reels sound state once, found ${found.size}")
    // The runnable reaches all of it from its own class, so each piece must be public or share its package.
    val package0 = run.definingClass.substringBeforeLast('/')
    fun shares(type: String) = type.substringBeforeLast('/') == package0
    fun open(type: String, flags: Int) = shares(type) || (AccessFlags.PUBLIC.isSet(flags) && AccessFlags.PUBLIC.isSet(classDefBy(type).accessFlags))
    val sessionField = classDefBy(controller).fields.singleOrNull { it.name == audio.sessionField.name }
        ?: refuse("$where: ${audio.sessionField} isn't a field of $controller")
    val stateClass = classDefBy(audio.state.returnType)
    val factory = stateClass.methods.singleOrNull {
        it.name == audio.state.name && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$where: ${audio.state} isn't a static of ${stateClass.type}")
    val ask = stateClass.methods.singleOrNull {
        it.name == audio.isOn.name && it.parameterTypes.isEmpty() && it.returnType == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$where: ${audio.isOn} isn't an instance method of ${stateClass.type}")
    if (!open(controller, AccessFlags.PUBLIC.value) || !open(controller, sessionField.accessFlags) ||
        !open(stateClass.type, factory.accessFlags) || !open(stateClass.type, ask.accessFlags)
    ) {
        refuse("$where can't reach the audio state from its own class")
    }
    return audio
}

/**
 * Past the volume runnable's adjustment: ask [KEEP_MUTED] with the direction it used and whether Reels
 * sound is on (1, 0, or -1 where the controller or session isn't there to ask), and on a yes return
 * before the unmute. A reel whose sound is already on keeps the unmute, so a fade-in still finishes.
 */
internal fun BytecodePatchContext.applyKeepMuted(site: VolumeSite) {
    val (direction, state) = site.scratch
    val audio = site.audio
    mutable(site.run).apply {
        addInstructionsWithLabels(
            site.index,
            """
                iget v$direction, p0, ${site.direction}
                iget-object v$state, p0, ${audio.controllerField}
                if-eqz v$state, :unknown
                iget-object v$state, v$state, ${audio.sessionField}
                if-eqz v$state, :unknown
                invoke-static { v$state }, ${audio.state}
                move-result-object v$state
                invoke-virtual { v$state }, ${audio.isOn}
                move-result v$state
                goto :ask
                :unknown
                const/4 v$state, -1
                :ask
                invoke-static { v$direction, v$state }, $KEEP_MUTED
                move-result v$direction
                if-eqz v$direction, :stock
                return-void
            """,
            ExternalLabel("stock", getInstruction(site.index)),
        )
    }
}

private const val AUDIO_MANAGER = "Landroid/media/AudioManager;"

/** The const-string a purge marker loads: the release prefix and the marker. */
private fun markerString(marker: String) = "android_purge_26_q3_$marker"

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
