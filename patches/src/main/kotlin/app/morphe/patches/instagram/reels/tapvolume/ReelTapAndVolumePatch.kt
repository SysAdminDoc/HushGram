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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Control taps and volume on Reels"
internal const val REEL_TAP_AND_VOLUME = "$EXTENSION_PACKAGE/reels/ReelTapAndVolume;"
internal const val MUTE_INSTEAD_OF_PAUSE = "$REEL_TAP_AND_VOLUME->muteInsteadOfPause()Z"

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
        "Starts at Instagram's default. Choose it in HushGram settings > Playback.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelTapAndVolume")
        applyReelTap(findReelTapSite())
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
