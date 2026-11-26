/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.hdr

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep HDR on video uploads"
internal const val HDR_VIDEOS = "$EXTENSION_PACKAGE/media/HdrVideos;"

/** Asked, where the encoder search marks a candidate SDR, with whether HLG was asked for; answers the mark. */
internal const val HDR_VIDEOS_HLG = "$HDR_VIDEOS->hlg(I)I"

/** Loaded by the encoder search, which skips MediaTek encoders by this piece of their name when asked to. */
internal const val ENCODER_SEARCH_MARKER = ".mtk."

/** The input color formats an encoder lists, which the search reads for P010. */
internal const val COLOR_FORMATS = "Landroid/media/MediaCodecInfo\$CodecCapabilities;->colorFormats:[I"

/** MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010, 10-bit YUV. */
internal const val P010 = 0x36

private const val SDK_INT = "Landroid/os/Build\$VERSION;->SDK_INT:I"
private const val HASH_SET = "Ljava/util/HashSet;"

/** A method by its class, name and parameter types, which is how a hook finds it again to change it. */
internal data class VideoMethod(val type: String, val name: String, val parameters: List<String>)

/**
 * Where the patch goes in. [search] is the encoder search. Its candidate's HLG mark is [flag], set
 * to 1 right after the P010 test and to 0 once, right before the `goto` at [markedAt] that goes on
 * where the 1 does. [wanted] is the boolean parameter saying whether HLG is asked for.
 */
internal data class VideoHdrSite(
    val search: VideoMethod,
    val markedAt: Int,
    val flag: Int,
    val wanted: Int,
)

@Suppress("unused")
val keepHdrOnVideoUploadsPatch = bytecodePatch(
    name = "Keep HDR on video uploads",
    description = "Encodes an HDR video you post in HDR on Android 13 and newer, on phones where Instagram " +
        "would make it SDR. Experimental. Starts off. Turn it on in HushGram settings > Playback.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("hdrVideos")
        keepHdrOnVideoUploads(findHdrVideos())
        enableStatus("hdrVideos")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the encoder search's SDR mark before anything changes, and throws naming what isn't there.
 *
 * The search (457 arm64 `0KCB.A00`, x86_64 `0KEq.A00`) is the one static method returning a
 * HashSet that loads [ENCODER_SEARCH_MARKER] and reads [COLOR_FORMATS]. Instagram's HDR capability
 * check for its camera (`HdrCapabilityUtil`, 457 arm64 `0bHZ.A03`) loads and reads the same, but
 * returns a list of encoder names.
 *
 * For each encoder and profile level that fits, the search marks the candidate as keeping HLG: 1
 * right after the one test of a color format against [P010], and 0 at the one place that writes 0
 * to the same register, reached when HLG isn't asked for, the Android version is below 13, or no
 * listed format is P010. That 0 is followed by a `goto` back to where the 1 goes on. Whether HLG is
 * asked for is the `if-eqz` on a boolean parameter, right before the Android version read, that
 * lands on the 0. Nothing in the method writes that parameter, so it still holds the caller's value
 * at the mark.
 */
internal fun BytecodePatchContext.findHdrVideos(): VideoHdrSite {
    val extension = classDefByOrNull(HDR_VIDEOS) ?: refuse("the extension has no $HDR_VIDEOS")
    if (extension.methods.none { it.descriptor() == HDR_VIDEOS_HLG && it.isPublicStatic() }) refuse("the extension has no public static $HDR_VIDEOS_HLG")

    val searches = classesLoadingString(ENCODER_SEARCH_MARKER).flatMap { holder ->
        holder.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == HASH_SET &&
                method.code().any { (it.reference() as? FieldReference)?.toString() == COLOR_FORMATS } && loadsString(method, ENCODER_SEARCH_MARKER)
        }
    }
    val search = searches.singleOrNull()
        ?: refuse("expected one static method returning a HashSet that loads \"$ENCODER_SEARCH_MARKER\" and reads $COLOR_FORMATS, found ${searches.size}")
    search.requireUnhooked()
    val code = search.code()

    // The P010 test and the HLG mark right after it.
    val tests = code.indices.filter { at ->
        code[at].isLiteral(P010) && code.getOrNull(at + 1)?.let { branch ->
            branch.opcode == Opcode.IF_NE && (branch as TwoRegisterInstruction).let {
                (code[at] as OneRegisterInstruction).registerA in listOf(it.registerA, it.registerB)
            }
        } == true
    }
    val test = tests.singleOrNull() ?: refuse("expected one test of a color format against P010 in ${search.descriptor()}, found ${tests.size}")
    val hlg = code.getOrNull(test + 2)
    if (hlg == null || !hlg.isLiteral(1)) refuse("the P010 test in ${search.descriptor()} isn't followed by the HLG mark")
    val flag = (hlg as OneRegisterInstruction).registerA

    // The SDR mark, and its goto to where the HLG mark goes on.
    val sdrs = code.indices.filter { code[it].isLiteral(0) && (code[it] as OneRegisterInstruction).registerA == flag }
    val sdr = sdrs.singleOrNull() ?: refuse("expected one SDR mark on v$flag in ${search.descriptor()}, found ${sdrs.size}")
    val markedAt = sdr + 1
    val join = code.getOrNull(markedAt)
    if (join == null || join.opcode !in GOTOS || code.landing(markedAt) != test + 3) {
        refuse("the SDR mark in ${search.descriptor()} doesn't go on where the HLG mark does")
    }
    if (markedAt in search.jumpTargets()) refuse("something in ${search.descriptor()} jumps past the SDR mark")

    // Whether HLG is asked for: the parameter tested right before the Android version read.
    val asks = code.indices.filter { at ->
        (code[at].reference() as? FieldReference)?.toString() == SDK_INT &&
            code.getOrNull(at - 1)?.opcode == Opcode.IF_EQZ && code.landing(at - 1) == sdr
    }.map { it - 1 }
    val ask = asks.singleOrNull() ?: refuse("expected one test of whether HLG is asked for before the Android version read in ${search.descriptor()}, found ${asks.size}")
    val wanted = (code[ask] as OneRegisterInstruction).registerA
    val type = search.parameterIn(wanted) ?: refuse("v$wanted, whether HLG is asked for in ${search.descriptor()}, isn't a parameter")
    if (type != "Z") refuse("v$wanted, whether HLG is asked for in ${search.descriptor()}, is a $type, not a boolean")
    if (code.any { it.writes(wanted) }) refuse("something in ${search.descriptor()} writes v$wanted, whether HLG is asked for")
    if (wanted == flag) refuse("the HLG mark in ${search.descriptor()} is the parameter itself")

    return VideoHdrSite(search.site(), markedAt, flag, wanted)
}

/**
 * Hands whether HLG is asked for to [HDR_VIDEOS_HLG] right after the SDR mark, and puts its answer
 * in the mark. With the switch off the extension answers 0, the SDR mark Instagram wrote.
 */
internal fun BytecodePatchContext.keepHdrOnVideoUploads(site: VideoHdrSite) {
    mutableClassMethod(site.search).addInstructions(
        site.markedAt,
        """
            invoke-static/range { v${site.wanted} .. v${site.wanted} }, $HDR_VIDEOS_HLG
            move-result v${site.flag}
        """,
    )
}

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
private val CONSTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

private fun BytecodePatchContext.mutableClassMethod(site: VideoMethod) =
    mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }

private fun Method.site() = VideoMethod(definingClass, name, parameterTypes.map(CharSequence::toString))

private fun Method.requireUnhooked() {
    if (code().any { (it.reference() as? MethodReference)?.definingClass == HDR_VIDEOS }) refuse("${descriptor()} already calls $HDR_VIDEOS")
}

/** The type of the parameter held in [register], or null when [register] holds no parameter (or holds `this`). */
private fun Method.parameterIn(register: Int): String? {
    val total = implementation?.registerCount ?: return null
    val types = parameterTypes.map(CharSequence::toString)
    var next = total - types.sumOf { if (it == "J" || it == "D") 2 else 1 }
    if (!AccessFlags.STATIC.isSet(accessFlags)) next++
    for (type in types) {
        if (register == next) return type
        next += if (type == "J" || type == "D") 2 else 1
    }
    return null
}

private fun Method.isPublicStatic() = AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags)
private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.isLiteral(value: Int) = opcode in CONSTS && (this as NarrowLiteralInstruction).narrowLiteral == value

private fun Instruction.writes(register: Int): Boolean =
    opcode.setsRegister() && (this as? OneRegisterInstruction)?.registerA.let {
        it == register || (opcode.setsWideRegister() && it != null && it + 1 == register)
    }

/** The index the branch at [index] lands on. */
private fun List<Instruction>.landing(index: Int): Int {
    var address = 0
    val addresses = map { instruction -> address.also { address += instruction.codeUnits } }
    val target = addresses[index] + (this[index] as OffsetInstruction).codeOffset
    return addresses.indexOf(target).takeIf { it >= 0 } ?: refuse("a branch lands between instructions")
}
