/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.media.quality

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PATCH = "Default playback quality"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * Videos, reels and video stories start at the quality picked in HushGram's settings. See
 * QualityAnchors.kt for how Instagram's player keeps to a quality, and the extension's
 * QualityChoice for when it keeps Instagram's own.
 *
 * Included in the default selection. Its switch starts on and the quality starts as Instagram's
 * own, so nothing changes until a quality is chosen. Everything is found before anything changes,
 * so a build that differs stops the patch naming what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val defaultPlaybackQualityPatch = bytecodePatch(
    name = "Default playback quality",
    description = "Plays videos, reels and video stories at the quality you choose, such as Data saver or up to " +
        "720p, instead of the one Instagram picks as it plays. Its switch starts on, but the quality starts as " +
        "Instagram's own, so nothing changes until you pick one. Switch: HushGram settings > Playback.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("defaultPlaybackQuality")
        applyQualityAnchors(findQualityAnchors())
        enableStatus("defaultPlaybackQuality")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * What [findQualityAnchors] found, for [applyQualityAnchors] to change.
 *
 * @property firstChoice the evaluator's choice of a track, and [hookAt] the index right after its
 *           one write of [formats], where [evaluatorRegister] holds the evaluator it wrote to
 */
internal class QualityAnchors(
    val evaluator: ClassDef,
    val setter: Method,
    val formats: FieldReference,
    val labelOf: MethodReference,
    val label: FieldReference,
    val customTrack: FieldReference,
    val firstChoice: Method,
    val hookAt: Int,
    val evaluatorRegister: Int,
)

/**
 * The DASH format evaluator, its custom-quality setter, its tracks, how the setter reads a track's
 * label and where it keeps the track it found, and the one place its choice of a track first keeps
 * its tracks. Changes nothing.
 */
internal fun BytecodePatchContext.findQualityAnchors(): QualityAnchors {
    val holding = classDefByStrings(SET_CUSTOM_QUALITY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .flatMap { holder -> holder.methods.filter { holdsString(it, SET_CUSTOM_QUALITY) } }
    val hero = holding.singleOrNull()
        ?: refuse("expected one method holding \"$SET_CUSTOM_QUALITY\", found ${holding.size}")
    val calls = customQualityCalls(hero)
    val call = calls.singleOrNull()
        ?: refuse("expected one (String)V call in the method holding \"$SET_CUSTOM_QUALITY\", found ${calls.size}")
    val evaluator = classDefByOrNull(call.definingClass) ?: refuse("this build has no ${call.definingClass}")
    if (!takesAbrConfiguration(evaluator)) refuse("${evaluator.type}'s constructor takes no $ABR_CONFIGURATION")

    val setter = evaluator.methods.singleOrNull {
        it.name == call.name && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(STRING)
    } ?: refuse("${evaluator.type} has no ${call.name}(String)V")
    if (AccessFlags.STATIC.isSet(setter.accessFlags)) refuse("${evaluator.type}->${setter.name} is static")

    val formatsFields = formatsFields(setter)
    val formats = formatsFields.singleOrNull()
        ?: refuse("expected ${evaluator.type}->${setter.name} to read one array of tracks, found ${formatsFields.size}")
    val format = formats.type.removePrefix("[")
    val labels = labelReads(setter, format).distinctBy { it.toString() }
    val (labelOf, label) = labels.singleOrNull()
        ?: refuse("expected ${evaluator.type}->${setter.name} to read a track's label one way, found ${labels.size}")
    val kept = customTrackFields(setter)
    val customTrack = kept.singleOrNull()
        ?: refuse("expected ${evaluator.type}->${setter.name} to keep a track's id in one String field, found ${kept.size}")

    val writes = formatsWrites(evaluator, formats)
    val (firstChoice, write) = writes.singleOrNull()
        ?: refuse("expected ${evaluator.type} to keep its tracks in ${formats.name} in one place, found ${writes.size}")
    val where = "${evaluator.type}->${firstChoice.name}"
    if (firstChoice.name == "<init>" || AccessFlags.STATIC.isSet(firstChoice.accessFlags)) {
        refuse("$where, which keeps the tracks, isn't the evaluator's choice of a track")
    }
    val code = firstChoice.code()
    if (!writtenOnce(code, write, formats)) refuse("$where writes ${formats.name} without checking it's still empty")
    if (!reads(firstChoice, customTrack)) refuse("$where never reads ${customTrack.name}, the track the setter keeps")
    if (write + 1 >= code.size) refuse("$where ends at its write of ${formats.name}")
    val evaluatorRegister = (code[write] as TwoRegisterInstruction).registerB

    // The stubs run in the extension, so what they cast to and read has to be public.
    val formatClass = classDefByOrNull(format) ?: refuse("this build has no $format")
    val labelClass = classDefByOrNull(labelOf.definingClass) ?: refuse("this build has no ${labelOf.definingClass}")
    val infoClass = classDefByOrNull(label.definingClass) ?: refuse("this build has no ${label.definingClass}")
    listOf(evaluator, formatClass, labelClass, infoClass).distinctBy { it.type }.forEach {
        if (!AccessFlags.PUBLIC.isSet(it.accessFlags)) refuse("${it.type} isn't public, so the extension can't reach it")
    }
    val members = listOf(
        formats.toString() to evaluator.fields.singleOrNull { it.name == formats.name && it.type == formats.type }?.accessFlags,
        customTrack.toString() to evaluator.fields.singleOrNull { it.name == customTrack.name && it.type == STRING }?.accessFlags,
        label.toString() to infoClass.fields.singleOrNull { it.name == label.name && it.type == label.type }?.accessFlags,
        labelOf.toString() to labelClass.methods.singleOrNull {
            it.name == labelOf.name && it.returnType == labelOf.returnType &&
                it.parameterTypes.map(CharSequence::toString) == listOf(format)
        }?.accessFlags,
        "${evaluator.type}->${setter.name}" to setter.accessFlags,
    )
    members.forEach { (name, flags) ->
        if (flags == null || !AccessFlags.PUBLIC.isSet(flags)) refuse("$name isn't public, so the extension can't reach it")
    }

    val reader = classDefByOrNull(QUALITY_READER) ?: refuse("the extension has no $QUALITY_READER")
    listOf(
        Triple(CUSTOM_TRACK_STUB, listOf(OBJECT), STRING),
        Triple(FORMATS_STUB, listOf(OBJECT), "[$OBJECT"),
        Triple(LABEL_STUB, listOf(OBJECT), STRING),
        Triple(SETTER_STUB, listOf(OBJECT, STRING), "V"),
    ).forEach { (name, parameters, answer) ->
        if (reader.stub(name, parameters, answer) == null) {
            refuse("$QUALITY_READER has no static $answer $name(${parameters.joinToString("")})")
        }
    }
    classDefByOrNull(QUALITY_CHOICE)?.methods?.singleOrNull {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == FIRST_CHOICE &&
            AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("the extension has no $FIRST_CHOICE")

    return QualityAnchors(evaluator, setter, formats, labelOf, label, customTrack, firstChoice, write + 1, evaluatorRegister)
}

private fun ClassDef.stub(name: String, parameters: List<String>, answer: String): Method? = methods.singleOrNull {
    it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes.map(CharSequence::toString) == parameters
}

/**
 * The extension is told about the evaluator right after its choice of a track first keeps its
 * tracks, through the range form, which names the evaluator where it is and borrows no register.
 * It goes in front of the next instruction and leaves that instruction's labels on it, so a branch
 * from a path that didn't keep the tracks still skips it. Then the stubs are filled in.
 */
internal fun BytecodePatchContext.applyQualityAnchors(anchors: QualityAnchors) {
    val register = anchors.evaluatorRegister
    mutable(anchors.firstChoice).addInstructions(anchors.hookAt, "invoke-static/range { v$register .. v$register }, $FIRST_CHOICE")
    fillStubs(anchors)
}

/** Fills the extension's stubs. Each works in its parameter registers, cast to Instagram's own type. */
private fun BytecodePatchContext.fillStubs(anchors: QualityAnchors) {
    val reader = mutableClassDefBy(QUALITY_READER)
    fun stub(name: String): MutableMethod = reader.methods.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }
    val evaluator = anchors.evaluator.type

    stub(CUSTOM_TRACK_STUB).addInstructions(
        0,
        """
            check-cast p0, $evaluator
            iget-object p0, p0, ${anchors.customTrack}
            return-object p0
        """,
    )
    stub(FORMATS_STUB).addInstructions(
        0,
        """
            check-cast p0, $evaluator
            iget-object p0, p0, ${anchors.formats}
            return-object p0
        """,
    )
    stub(LABEL_STUB).addInstructions(
        0,
        """
            check-cast p0, ${anchors.formats.type.removePrefix("[")}
            invoke-static { p0 }, ${anchors.labelOf}
            move-result-object p0
            iget-object p0, p0, ${anchors.label}
            return-object p0
        """,
    )
    stub(SETTER_STUB).addInstructions(
        0,
        """
            check-cast p0, $evaluator
            invoke-virtual { p0, p1 }, $evaluator->${anchors.setter.name}(Ljava/lang/String;)V
            return-void
        """,
    )
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }
