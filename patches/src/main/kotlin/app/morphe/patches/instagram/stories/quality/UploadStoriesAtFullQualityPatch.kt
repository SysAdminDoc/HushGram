/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Upload stories at full quality"
internal const val STORY_UPLOAD_QUALITY = "$EXTENSION_PACKAGE/stories/StoryUploadQuality;"
internal const val FULL_QUALITY = "$STORY_UPLOAD_QUALITY->fullQuality(Ljava/lang/Object;)Z"

/** The key of Instagram's own "Upload at highest quality" setting in its per-user preferences. */
internal const val HIGH_QUALITY_PREF = "high_quality_media_upload"

/** What an upload is shared as: a post, a story, a reel. The class keeps its name in Instagram's builds. */
internal const val SHARE_TYPE = "Lcom/instagram/pendingmedia/model/constants/ShareType;"

/** The bitrate, in bits a second, Instagram gives a video the high quality check says yes to. */
internal const val HIGH_QUALITY_BITRATE = 9_000_000

/** The preferences' int read, which the setting's reader asks for the key. */
private const val GET_INT = "getInt"

/**
 * Prepares video stories the way Instagram does with its own "Upload at highest quality" setting on.
 * In the default selection with its switch off.
 *
 * Before a video is transcoded for upload, Instagram's per-upload transcode settings (`0kAt` on
 * 451's 385713073) ask whether the upload gets the high quality treatment, and for a story the
 * answer is that setting. A yes lifts the story's width cap from 720 (640 on older phones) to 1080
 * and its bitrate to 9 Mbps, the values a post or a reel gets with the setting on. The patch
 * answers yes for a story at the start of that check while the switch is on. Every other share
 * type, and every server gate read after the check, keeps Instagram's own answer, so the 1440
 * experiment and the server's own caps still decide where they apply.
 *
 * Measured on 452 (2026-10-23, #37): with the switch on a 10 s story goes up at 1080x1920 and
 * 9.2 Mbps (7.1 Mbps off on a 1080 wide screen), but the server keeps one 720x1280 rendition near
 * 1.2 Mbps either way, as it does for @instagram's own stories, while reels get a ladder to 1080p.
 * Story photos already go up at 1440x2560 (quality 84) and are served at 1080x1920.
 */
@Suppress("unused")
val uploadStoriesAtFullQualityPatch = bytecodePatch(
    name = "Upload stories at full quality",
    description = "Sends the video stories you post at 1080p and a higher bitrate, as Instagram does with its own " +
        "Upload at highest quality setting on. Instagram still shrinks story videos to 720p on its servers, so " +
        "viewers see a small difference. Starts off. Turn it on in HushGram settings > Stories.",
    default = true,
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyUploadQuality")
        raiseStoryUploadQuality(findStoryUploadQuality())
        enableStatus("storyUploadQuality")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The high quality check: its class, its name, and the field holding the upload's share type. */
internal class QualityCheck(val type: String, val name: String, val shareType: FieldReference)

/**
 * The reader of Instagram's [HIGH_QUALITY_PREF] setting: the one static method answering a boolean
 * from one argument (the user's preferences) that loads the key and reads it as an int. On 451's
 * 385713073 it's `0623.A00`. The settings screens that write the key take other shapes.
 */
internal fun BytecodePatchContext.findHighQualityPref(): MethodReference {
    val readers = classesLoadingString(HIGH_QUALITY_PREF).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.size == 1 &&
                method.calls { it.name == GET_INT && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "I") } &&
                loadsString(method, HIGH_QUALITY_PREF)
        }
    }
    return readers.singleOrNull() ?: refuse("expected one reader of the $HIGH_QUALITY_PREF setting, found ${readers.size}")
}

/**
 * Finds the high quality check: in a class holding exactly one instance field of [SHARE_TYPE], the
 * one instance `()Z` method that asks [findHighQualityPref]'s reader and reads that field, itself
 * or through a static helper of its class answering the field's ordinal ([ordinalReaders]). It has
 * to switch on the share type's ordinal, as Instagram picks the share types the setting covers, and
 * a method of the same class has to ask it and load [HIGH_QUALITY_BITRATE], the bitrate a yes gives.
 * Fails before anything changes when any of it isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryUploadQuality(): QualityCheck {
    val pref = findHighQualityPref()
    val found = classesCalling(pref.definingClass, pref.name).mapNotNull { classDef ->
        val field = classDef.fields.singleOrNull { it.type == SHARE_TYPE && !AccessFlags.STATIC.isSet(it.accessFlags) }
            ?: return@mapNotNull null
        val ordinals = classDef.ordinalReaders(field)
        val checks = classDef.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.isEmpty() &&
                method.calls { it.sameAs(pref) } && (method.reads(field) || method.calls { call -> ordinals.any { call.sameAs(it) } })
        }
        when (checks.size) {
            0 -> null
            1 -> Triple(classDef, checks.single(), field)
            else -> refuse("${classDef.type} has ${checks.size} methods asking the $HIGH_QUALITY_PREF setting about its share type")
        }
    }
    val (classDef, check, field) = found.singleOrNull()
        ?: refuse("expected one method asking the $HIGH_QUALITY_PREF setting about an upload's share type, found ${found.size}")
    val where = "${classDef.type}->${check.name}"
    val ordinals = classDef.ordinalReaders(field)
    if (!check.calls { call -> call.isOrdinal() || ordinals.any { call.sameAs(it) } }) {
        refuse("$where doesn't pick share types by their ordinal")
    }
    if (!classDef.bitrateAsks(check)) {
        refuse("no method of ${classDef.type} asks $where and gives the $HIGH_QUALITY_BITRATE bps high quality bitrate")
    }
    return QualityCheck(classDef.type, check.name, field)
}

/**
 * The static methods of this class taking one of it that read the share type [field] and answer its
 * ordinal. On 451's 385713073 the check asks `0kAt.A01` for the ordinal; on 450 it read the field
 * and asked ordinal() itself.
 */
private fun ClassDef.ordinalReaders(field: FieldReference): List<Method> = methods.filter { method ->
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(type) && method.reads(field) && method.calls { it.isOrdinal() }
}

private fun MethodReference.isOrdinal() = definingClass == "Ljava/lang/Enum;" && name == "ordinal"

/** Whether a method of this class asks [check] and loads [HIGH_QUALITY_BITRATE]: Instagram's bitrate setter. */
private fun ClassDef.bitrateAsks(check: Method): Boolean = methods.any { method ->
    method.calls { it.definingClass == type && it.name == check.name && it.returnType == "Z" && it.parameterTypes.isEmpty() } &&
        method.implementation?.instructions?.any {
            it is NarrowLiteralInstruction && it.opcode.name.startsWith("const") && it.narrowLiteral == HIGH_QUALITY_BITRATE
        } == true
}

private fun Method.calls(matches: (MethodReference) -> Boolean): Boolean = implementation?.instructions?.any {
    it.opcode.name.startsWith("invoke") && ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let(matches) == true
} == true

private fun Method.reads(field: FieldReference): Boolean = implementation?.instructions?.any {
    it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { read ->
        read.definingClass == field.definingClass && read.name == field.name && read.type == field.type
    }
} == true

private fun MethodReference.sameAs(other: MethodReference) = definingClass == other.definingClass && name == other.name &&
    returnType == other.returnType && parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/**
 * First thing in the check, hands [FULL_QUALITY] the upload's share type, and returns yes when it
 * says so. Otherwise Instagram's own check runs from its first instruction. Proved before anything
 * changes: nothing jumps back to that instruction, there's a local for the answer, and `this` is in
 * a register an instance field read can name.
 */
internal fun BytecodePatchContext.raiseStoryUploadQuality(check: QualityCheck) {
    val method = mutableClassDefBy(check.type).methods.single {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val where = "${check.type}->${check.name}"
    if (0 in method.jumpTargets()) refuse("something jumps back to the first instruction of $where")
    method.requireLocals(PATCH, 1)
    val registers = method.implementation!!.registerCount
    if (registers > 16) refuse("$where has $registers registers, so this isn't in one an instance field read can name")
    method.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, ${check.shareType.definingClass}->${check.shareType.name}:$SHARE_TYPE
            invoke-static { v0 }, $FULL_QUALITY
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
}
