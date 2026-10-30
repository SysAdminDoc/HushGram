/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.hdr

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.sendToStandIn
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Turn off HDR brightness boosts"
internal const val HDR_BOOST = "$EXTENSION_PACKAGE/media/HdrBoost;"

/**
 * One framework call the patch sends to HdrBoost: its class, name and shape, and its stand-in there.
 * A [screenCheck] asks whether the screen shows HDR, and the HDR recording helper's own checks of
 * that kind stay Instagram's (see [recordingHelpers]).
 */
internal data class HdrCall(
    val definingClass: String,
    val name: String,
    val shape: String,
    val standIn: String,
    val screenCheck: Boolean = false,
) {
    /** The HdrBoost method this call goes to: static, the receiver first, the same parameters. */
    val standInMethod get() = "$HDR_BOOST->$standIn($definingClass${shape.substringAfter('(')}"
}

/**
 * Loaded by the helper Instagram's camera and video creation ask about HDR recording: whether the
 * phone has an HEVC encoder for HDR, and whether the screen shows HLG. Its checks of the screen
 * decide what the camera offers to record, so they keep Instagram's answers.
 */
internal const val RECORDING_HDR_MARKER = "Exception in #getEncoderWithHdrCapabilitiesInternal(), hevc codec"

/**
 * The three ways Instagram asks Android for HDR headroom (Android 15's SurfaceView, SurfaceControl
 * transaction and Window calls), the call that puts a window in HDR color mode, Android 14's
 * extended range brightness, which the layer Instagram draws some videos on asks with, and the
 * screen's HDR/SDR ratio, which that layer draws its frames for and the AV1 decoder's synthetic HDR
 * backs off by (#85). Then the dataspace that layer gives its frames, extended range sRGB, and
 * Instagram's three checks of whether the screen shows HDR, which decide whether synthetic HDR may
 * run, whether Dolby Vision streams and decoders are picked and what video and image requests say
 * the screen can show (#85).
 */
internal val HDR_CALLS = listOf(
    HdrCall("Landroid/view/SurfaceView;", "setDesiredHdrHeadroom", "(F)V", "surfaceViewHeadroom"),
    HdrCall(
        "Landroid/view/SurfaceControl\$Transaction;", "setDesiredHdrHeadroom",
        "(Landroid/view/SurfaceControl;F)Landroid/view/SurfaceControl\$Transaction;", "transactionHeadroom",
    ),
    HdrCall("Landroid/view/Window;", "setDesiredHdrHeadroom", "(F)V", "windowHeadroom"),
    HdrCall("Landroid/view/Window;", "setColorMode", "(I)V", "colorMode"),
    HdrCall(
        "Landroid/view/SurfaceControl\$Transaction;", "setExtendedRangeBrightness",
        "(Landroid/view/SurfaceControl;FF)Landroid/view/SurfaceControl\$Transaction;", "extendedRangeBrightness",
    ),
    HdrCall("Landroid/view/Display;", "getHdrSdrRatio", "()F", "hdrSdrRatio"),
    HdrCall(
        "Landroid/view/SurfaceControl\$Transaction;", "setDataSpace",
        "(Landroid/view/SurfaceControl;I)Landroid/view/SurfaceControl\$Transaction;", "dataSpace",
    ),
    HdrCall("Landroid/view/Display;", "isHdr", "()Z", "isHdr", screenCheck = true),
    HdrCall("Landroid/view/Display\$HdrCapabilities;", "getSupportedHdrTypes", "()[I", "hdrTypes", screenCheck = true),
    HdrCall("Landroid/view/Display\$Mode;", "getSupportedHdrTypes", "()[I", "modeHdrTypes", screenCheck = true),
)

@Suppress("unused")
val turnOffHdrBoostsPatch = bytecodePatch(
    name = "Turn off HDR brightness boosts",
    description = "Stops HDR photos and reels from making the screen brighter than everything else. Starts off. " +
        "Turn it on in HushGram settings > Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("hdrBoost")
        holdBackHdrBoosts()
        holdHdrOptionsOff()
        enableStatus("hdrBoost")
    }
}

/**
 * "SDR super white on HDR content" (ig_android_clips_sdr_super_white_on_hdr_content), the option that
 * gives ordinary reels a white brighter than the screen's (#85).
 */
internal const val SUPER_WHITE_OPTION = 68885

/** "Ultra HDR always on" (ig_android_uhdr_always_on) (#85). */
internal const val ULTRA_HDR_OPTION = 109125

/**
 * Loaded by the String read of the class Instagram's Java code reads its server options through. That
 * read's first parameter type is the boolean read's first too.
 */
internal const val OPTION_READER_MARKER = "__fbt_null__"

/** The fewest option ids the table lists. Instagram's lists nearly 6000. */
private const val OPTION_TABLE_MIN = 1000

/** The HdrBoost method the boolean option read asks first: the specifier and the two indexes. */
internal const val OPTION_OFF = "$HDR_BOOST->optionOff(JII)Z"

/**
 * Where [ids] sit in Instagram's option table: the class initializer whose int arrays list every
 * option id the app knows, one after another, so that an option's place among them is the index its
 * specifiers carry in bits 32 to 47. The ARMv7 builds keep the first 5000 ids in a same-class `()[I`
 * helper the initializer calls before it fills the rest, so the ids are read in the order the
 * initializer builds them. Every index is -1 when no table lists them all.
 */
internal fun BytecodePatchContext.optionIndexes(vararg ids: Int): List<Int> {
    var table: List<Int>? = null
    classDefForEach { classDef ->
        if (table != null) return@classDefForEach
        val initializer = classDef.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
        if (initializer.implementation?.instructions?.none { it is ArrayPayload && it.elementWidth == 4 } != false) return@classDefForEach
        val listed = classDef.listedIds(initializer, helpers = true)
        if (listed.size >= OPTION_TABLE_MIN && ids.all { it in listed }) table = listed
    }
    return ids.map { id -> table?.indexOf(id) ?: -1 }
}

/**
 * The int ids [method] builds, in order: a same-class `()[I` helper's ids where it calls one (when
 * [helpers]), each int array where it's filled, then the int arrays nothing fills.
 */
private fun ClassDef.listedIds(method: Method, helpers: Boolean): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    var address = 0
    val addresses = instructions.map { instruction -> address.also { address += instruction.codeUnits } }
    val payloads = sortedMapOf<Int, ArrayPayload>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction is ArrayPayload && instruction.elementWidth == 4) payloads[addresses[index]] = instruction
    }
    val listed = ArrayList<Int>()
    instructions.forEachIndexed { index, instruction ->
        when (instruction.opcode) {
            Opcode.FILL_ARRAY_DATA -> payloads.remove(addresses[index] + (instruction as OffsetInstruction).codeOffset)
                ?.let { payload -> payload.arrayElements.mapTo(listed) { it.toInt() } }
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> if (helpers) {
                val called = (instruction as ReferenceInstruction).reference as MethodReference
                if (called.definingClass == type && called.parameterTypes.isEmpty() && called.returnType == "[I") {
                    methods.firstOrNull { it.name == called.name && it.parameterTypes.isEmpty() && it.returnType == "[I" }
                        ?.let { helper -> listed += listedIds(helper, helpers = false) }
                }
            }
            else -> Unit
        }
    }
    payloads.values.forEach { payload -> payload.arrayElements.mapTo(listed) { it.toInt() } }
    return listed
}

/**
 * Puts HdrBoost in front of Instagram's boolean option read, the one every Java read of a server
 * switch ends in, so "SDR super white on HDR content" and "Ultra HDR always on" read as off while
 * the switch is on (#85). The read sits in the class holding [OPTION_READER_MARKER] and takes that
 * class's reader type, the specifier and the default. Answers the read it changed, or null when the
 * table or the read can't be found, which leaves the rest of the patch working and says so in the log.
 */
internal fun BytecodePatchContext.holdHdrOptionsOff(): MutableMethod? {
    val (superWhite, ultraHdr) = optionIndexes(SUPER_WHITE_OPTION, ULTRA_HDR_OPTION)
    if (superWhite < 0 && ultraHdr < 0) {
        patchLog.warning("$PATCH: found no option table listing $SUPER_WHITE_OPTION and $ULTRA_HDR_OPTION, so Instagram's HDR options keep their values.")
        return null
    }
    val reads = classesLoadingString(OPTION_READER_MARKER).flatMap { classDef ->
        val readerTypes = classDef.methods.filter { method ->
            method.returnType == "Ljava/lang/String;" && method.parameterTypes.isNotEmpty() && loadsString(method, OPTION_READER_MARKER)
        }.mapTo(HashSet()) { it.parameterTypes[0].toString() }
        classDef.methods.filter { method ->
            val parameters = method.parameterTypes.map { it.toString() }
            method.returnType == "Z" && method.implementation != null &&
                parameters.size == 3 && parameters[0] in readerTypes && parameters[1] == "J" && parameters[2] == "Z"
        }
    }
    val read = reads.singleOrNull() ?: run {
        patchLog.warning("$PATCH: found ${reads.size} boolean option reads by \"$OPTION_READER_MARKER\", so Instagram's HDR options keep their values.")
        return null
    }
    val shape = read.parameterTypes.map { it.toString() }
    val method = mutableClassDefBy(read.definingClass).methods.first { it.name == read.name && it.parameterTypes.map { type -> type.toString() } == shape }
    val specifier = method.parameterRegisterNumber(1)
    if (method.localRegisterCount() < 2 || 0 in method.jumpTargets() || specifier + 1 > 15) {
        patchLog.warning("$PATCH: ${method.definingClass}->${method.name} can't take the option hook, so Instagram's HDR options keep their values.")
        return null
    }
    method.addInstructionsWithLabels(
        0,
        """
            const v0, $superWhite
            const v1, $ultraHdr
            invoke-static { v$specifier, v${specifier + 1}, v0, v1 }, $OPTION_OFF
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
    return method
}

/** The HDR call this instruction makes, or null. */
internal fun Instruction.hdrCall(): HdrCall? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val shape = call.parameterTypes.joinToString("", "(", ")") + call.returnType
    return HDR_CALLS.firstOrNull { it.definingClass == call.definingClass && it.name == call.name && it.shape == shape }
}

/**
 * The types of the classes holding [RECORDING_HDR_MARKER] that check the screen's HDR support. Their
 * screen checks decide what the camera offers to record, so they stay Instagram's.
 */
internal fun BytecodePatchContext.recordingHelpers(): Set<String> =
    classesLoadingString(RECORDING_HDR_MARKER).filter { classDef -> classDef.makes { it.screenCheck } }.mapTo(HashSet()) { it.type }

/**
 * Sends every HDR call in Instagram's code to HdrBoost, on the same registers in the same order, the
 * receiver first, so nothing after a call moves. Answers how many of each it sent. Fails before
 * changing anything when Instagram asks for headroom nowhere, since then the switch couldn't hold a
 * boost back. The extension's own calls are the real ones the stand-ins make, so they stay. So do
 * the HDR recording helper's screen checks, and when that helper can't be found every screen check
 * stays, so the camera never loses its HDR recording to a guess.
 */
internal fun BytecodePatchContext.holdBackHdrBoosts(): Map<HdrCall, Int> {
    val found = HDR_CALLS.flatMap { classesCalling(it.definingClass, it.name) }.distinctBy { it.type }
    val recording = recordingHelpers()
    if (recording.isEmpty() && found.any { classDef -> classDef.makes { it.screenCheck } }) {
        patchLog.warning(
            "$PATCH: found no HDR recording helper (\"$RECORDING_HDR_MARKER\"), so Instagram's checks of the screen's HDR " +
                "support keep their answers.",
        )
    }
    fun sends(type: String, call: HdrCall) = !call.screenCheck || (recording.isNotEmpty() && type !in recording)
    val counts = HDR_CALLS.associateWith { call ->
        found.filter { sends(it.type, call) }.sumOf { classDef ->
            classDef.methods.sumOf { method -> method.implementation?.instructions?.count { it.hdrCall() == call } ?: 0 }
        }
    }
    if (counts.filterKeys { it.name == "setDesiredHdrHeadroom" }.values.sum() == 0) {
        throw PatchException("$PATCH: Instagram never asks Android for HDR headroom")
    }
    for (classDef in found) {
        for (method in mutableClassDefBy(classDef.type).methods) {
            val sites = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
                instruction.hdrCall()?.takeIf { sends(classDef.type, it) }?.let { index to it }
            }.orEmpty()
            sites.asReversed().forEach { (index, call) -> method.sendToStandIn(index, call.standInMethod) }
        }
    }
    return counts
}

private fun ClassDef.makes(kind: (HdrCall) -> Boolean) =
    methods.any { method -> method.implementation?.instructions?.any { instruction -> instruction.hdrCall()?.let(kind) == true } == true }
