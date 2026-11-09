/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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
import app.morphe.util.addInstructionsAtControlFlowLabel
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
internal const val BITRATE_HOOK = "$STORY_UPLOAD_QUALITY->bitrate(Ljava/lang/Object;I)I"
internal const val RESOLUTION_HOOK = "$STORY_UPLOAD_QUALITY->resolution(Ljava/lang/Object;I)I"
internal const val FRAME_RATE_HOOK = "$STORY_UPLOAD_QUALITY->frameRate(Ljava/lang/Object;I)I"
internal const val PHOTO_QUALITY_HOOK = "$STORY_UPLOAD_QUALITY->photoQuality(I)I"

/** The key of Instagram's own "Upload at highest quality" setting in its per-user preferences. */
internal const val HIGH_QUALITY_PREF = "high_quality_media_upload"

/** What an upload is shared as: a post, a story, a reel. The class keeps its name in Instagram's builds. */
internal const val SHARE_TYPE = "Lcom/instagram/pendingmedia/model/constants/ShareType;"

/** The bitrate, in bits a second, Instagram gives a video the high quality check says yes to. */
internal const val HIGH_QUALITY_BITRATE = 9_000_000

/** The upload step for a video. It keeps its name in Instagram's builds, and calls the width step. */
internal const val VIDEO_INGESTION_STEP = "Lcom/facebook/videolite/instagram/VideoIngestionStep;"

/** The two widths Instagram's width step knows by name. */
internal const val WIDTH_1080 = 1080
internal const val WIDTH_1440 = 1440

/** The frame rate Instagram's bitrate setter falls back on when a video's isn't set. */
internal const val DEFAULT_FRAME_RATE = 30

private const val BITMAP = "Landroid/graphics/Bitmap;"
private const val COMPRESS_FORMAT = "Landroid/graphics/Bitmap\$CompressFormat;"
private const val CONTEXT = "Landroid/content/Context;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** The two kinds of picture the photo renderer writes, both named in the one method that chooses the quality. */
private const val IMAGE_WEBP = "image/webp"
private const val IMAGE_JPEG = "image/jpeg"

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
        "Upload at highest quality setting on. Choices under it can cover posts and reels too and ask for a " +
        "bitrate up to 50 Mbps, 1440p, 60 fps or a higher photo quality. Instagram still shrinks story videos to 720p on its servers, so " +
        "viewers see a small difference. Starts off. Turn it on in HushGram settings > Stories.",
    default = true,
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyUploadQuality")
        val check = findStoryUploadQuality()
        val bitrate = findBitrateSetter(check)
        val width = findWidthStep()
        val photo = findPhotoSetup()
        raiseStoryUploadQuality(check)
        raiseBitrate(check, bitrate)
        raiseWidth(width)
        raisePhotoQuality(photo)
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
private fun ClassDef.bitrateAsks(check: Method): Boolean = bitrateSetters(check).isNotEmpty()

/** The methods of this class that ask [check] and load [HIGH_QUALITY_BITRATE]. */
private fun ClassDef.bitrateSetters(check: Method): List<Method> = methods.filter { method ->
    method.calls { it.definingClass == type && it.name == check.name && it.returnType == "Z" && it.parameterTypes.isEmpty() } &&
        method.implementation?.instructions?.any {
            it is NarrowLiteralInstruction && it.opcode.name.startsWith("const") && it.narrowLiteral == HIGH_QUALITY_BITRATE // opcode-name-ok
        } == true
}

/**
 * Instagram's bitrate setter: the method that asks the check and loads [HIGH_QUALITY_BITRATE], and so
 * writes the bitrate into the transcode params its one argument holds. [holder] is that argument's field
 * of the params' class, and [bitrate] the int fields of the params it writes (on 453's 385914557
 * `0mB3.A02` and `0CCQ.A01`, `0CCQ.A04`, always the same value), the first of them read for the value.
 */
internal class BitrateSetter(
    val name: String,
    val parameter: String,
    val holder: FieldReference,
    val bitrate: List<FieldReference>,
    val frameRate: FieldReference,
)

/**
 * Proves the setter before anything changes: one method of the check's class asking the check and
 * loading [HIGH_QUALITY_BITRATE], an instance `(P)V`, whose P holds one field of the params class it
 * writes exactly two int fields of, and which returns void somewhere.
 */
internal fun BytecodePatchContext.findBitrateSetter(check: QualityCheck): BitrateSetter {
    val classDef = classDefBy(check.type)
    val checkMethod = classDef.methods.single {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val setters = classDef.bitrateSetters(checkMethod).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.size == 1
    }
    val setter = setters.singleOrNull() ?: refuse("expected one bitrate setter in ${check.type}, found ${setters.size}")
    val where = "${check.type}->${setter.name}"
    val parameter = setter.parameterTypes.single().toString()
    val code = setter.implementation!!.instructions.toList()
    val written = code.filter { it.opcode == Opcode.IPUT && ((it as ReferenceInstruction).reference as FieldReference).type == "I" }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .distinctBy { "${it.definingClass}->${it.name}" }
    if (written.size != 2 || written.map { it.definingClass }.distinct().size != 1) {
        refuse("$where writes ${written.size} int fields of the transcode params, expected two of one class")
    }
    val holders = code.filter { it.opcode == Opcode.IGET_OBJECT }.map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.definingClass == parameter && it.type == written.first().definingClass }
        .distinctBy { it.name }
    val holder = holders.singleOrNull() ?: refuse("$where reads ${holders.size} transcode params fields of its argument, expected one")
    if (code.none { it.opcode == Opcode.RETURN_VOID }) refuse("$where never returns")
    val rates = code.indices.filter { at ->
        code[at].opcode == Opcode.IGET && at + 2 < code.size && code[at + 1].opcode == Opcode.IF_GTZ &&
            ((code[at] as ReferenceInstruction).reference as FieldReference).let { it.definingClass == written.first().definingClass && it.type == "I" } &&
            (code[at + 2] as? NarrowLiteralInstruction)?.narrowLiteral == DEFAULT_FRAME_RATE
    }.map { (code[it] as ReferenceInstruction).reference as FieldReference }.distinctBy { it.name }
    val rate = rates.singleOrNull() ?: refuse("$where reads ${rates.size} frame rates with the $DEFAULT_FRAME_RATE fps fallback, expected one")
    return BitrateSetter(setter.name, parameter, holder, written, rate)
}

/**
 * Before every return of the setter, hands [BITRATE_HOOK] the upload's share type and the bitrate the
 * setter wrote, and writes both fields again when the answer is different, then does the same for the
 * frame rate with [FRAME_RATE_HOOK]. A null params object is left alone. Nothing else of the setter changes.
 */
internal fun BytecodePatchContext.raiseBitrate(check: QualityCheck, setter: BitrateSetter) {
    val method = mutableClassDefBy(check.type).methods.single {
        it.name == setter.name && it.returnType == "V" && it.parameterTypes.size == 1 && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val where = "${check.type}->${setter.name}"
    method.requireLocals(PATCH, 3)
    val registers = method.implementation!!.registerCount
    if (registers > 16) refuse("$where has $registers registers, so this isn't in one an instance field read can name")
    val first = setter.bitrate[0]
    val second = setter.bitrate[1]
    val rate = setter.frameRate
    val returns = method.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    for (at in returns.reversed()) {
        method.addInstructionsAtControlFlowLabel(
            at,
            """
                iget-object v0, p1, ${setter.holder.definingClass}->${setter.holder.name}:${setter.holder.type}
                if-eqz v0, :done
                iget v1, v0, ${first.definingClass}->${first.name}:I
                iget-object v2, p0, ${check.shareType.definingClass}->${check.shareType.name}:$SHARE_TYPE
                invoke-static { v2, v1 }, $BITRATE_HOOK
                move-result v2
                if-eq v2, v1, :rate
                iput v2, v0, ${first.definingClass}->${first.name}:I
                iput v2, v0, ${second.definingClass}->${second.name}:I
                :rate
                iget v1, v0, ${rate.definingClass}->${rate.name}:I
                iget-object v2, p0, ${check.shareType.definingClass}->${check.shareType.name}:$SHARE_TYPE
                invoke-static { v2, v1 }, $FRAME_RATE_HOOK
                move-result v2
                if-eq v2, v1, :done
                iput v2, v0, ${rate.definingClass}->${rate.name}:I
                :done
                return-void
            """,
        )
    }
}

/** Instagram's width step for an upload: the method, and the getter for the upload's share type. */
internal class WidthStep(val method: MethodReference, val shareType: MethodReference, val onInterface: Boolean)

/**
 * The width step: the one static method [VIDEO_INGESTION_STEP] calls that takes the context, the session,
 * an upload, an int and four booleans, loads both widths it knows ([WIDTH_1080] and [WIDTH_1440]), and
 * whose upload class has one instance getter for its [SHARE_TYPE].
 */
internal fun BytecodePatchContext.findWidthStep(): WidthStep {
    val step = classDefByOrNull(VIDEO_INGESTION_STEP) ?: refuse("$VIDEO_INGESTION_STEP isn't in this build")
    val calls = step.methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty()
            .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
    }.filter { it.isWidthStepShape() }.distinctBy { "${it.definingClass}->${it.name}" }
    val call = calls.singleOrNull() ?: refuse("expected one width step called from $VIDEO_INGESTION_STEP, found ${calls.size}")
    val where = "${call.definingClass}->${call.name}"
    val method = classDefBy(call.definingClass).methods.single { it.sameAs(call) }
    val literals = method.implementation?.instructions?.toList().orEmpty().mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
    if (WIDTH_1080 !in literals || WIDTH_1440 !in literals) refuse("$where doesn't load both $WIDTH_1080 and $WIDTH_1440")
    val upload = call.parameterTypes[2].toString()
    val uploadClass = classDefByOrNull(upload) ?: refuse("$upload isn't in this build")
    val getters = uploadClass.methods
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == SHARE_TYPE && it.parameterTypes.isEmpty() }
    val getter = getters.singleOrNull() ?: refuse("$upload has ${getters.size} share type getters, expected one")
    return WidthStep(call, getter, AccessFlags.INTERFACE.isSet(uploadClass.accessFlags))
}

private fun MethodReference.isWidthStepShape(): Boolean {
    val types = parameterTypes.map(CharSequence::toString)
    return types.size == 8 && types[0] == CONTEXT && types[1] == USER_SESSION && types[2].startsWith("L") && types[3] == "I" &&
        types.drop(4).all { it == "Z" } && returnType.startsWith("L")
}

/** First thing in the width step, swaps the width it was asked for with [RESOLUTION_HOOK]'s answer. A null upload skips it. */
internal fun BytecodePatchContext.raiseWidth(step: WidthStep) {
    val method = mutableClassDefBy(step.method.definingClass).methods.single { it.sameAs(step.method) }
    val where = "${step.method.definingClass}->${step.method.name}"
    if (0 in method.jumpTargets()) refuse("something jumps back to the first instruction of $where")
    method.requireLocals(PATCH, 2)
    val invoke = if (step.onInterface) "invoke-interface" else "invoke-virtual"
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p2
            if-eqz v0, :instagram
            $invoke { v0 }, ${step.shareType.definingClass}->${step.shareType.name}()$SHARE_TYPE
            move-result-object v0
            move/from16 v1, p3
            invoke-static { v0, v1 }, $RESOLUTION_HOOK
            move-result v0
            move/from16 p3, v0
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
}

/**
 * Where a photo's JPEG is set up: the one static factory called from the method that names both
 * [IMAGE_WEBP] and [IMAGE_JPEG], building its own class from a bitmap, an object and two ints (the
 * quality first), in a class that compresses the bitmap itself or through a helper.
 */
internal fun BytecodePatchContext.findPhotoSetup(): MethodReference {
    val naming = classesLoadingString(IMAGE_WEBP).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, IMAGE_WEBP) && loadsString(it, IMAGE_JPEG) }
    }
    val shaped = naming.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty()
            .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
    }.filter { it.isPhotoSetupShape() }.distinctBy { "${it.definingClass}->${it.name}" }
    val found = shaped.filter { candidate ->
        classDefByOrNull(candidate.definingClass)?.methods?.any { it.compressesBitmap() } == true
    }
    return found.singleOrNull()
        ?: refuse("expected one photo setup called beside $IMAGE_WEBP and $IMAGE_JPEG, found ${found.size} (${naming.size} methods name both, ${shaped.size} calls of the shape)")
}

/** Calls Bitmap.compress, or hands a compress format to the helper some builds moved that call into. */
private fun Method.compressesBitmap(): Boolean = implementation?.instructions?.any { instruction ->
    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        is MethodReference -> reference.definingClass == BITMAP && reference.name == "compress" ||
            reference.parameterTypes.any { it.toString() == COMPRESS_FORMAT }
        is FieldReference -> reference.type == COMPRESS_FORMAT
        else -> false
    }
} == true

private fun MethodReference.isPhotoSetupShape(): Boolean {
    val types = parameterTypes.map(CharSequence::toString)
    return types.size == 4 && types[0] == BITMAP && types[1].startsWith("L") && types[2] == "I" && types[3] == "I" && returnType == definingClass
}

/** First thing in the photo setup, swaps the quality Instagram chose with [PHOTO_QUALITY_HOOK]'s answer. */
internal fun BytecodePatchContext.raisePhotoQuality(setup: MethodReference) {
    val method = mutableClassDefBy(setup.definingClass).methods.single { it.sameAs(setup) }
    if (0 in method.jumpTargets()) refuse("something jumps back to the first instruction of ${setup.definingClass}->${setup.name}")
    method.requireLocals(PATCH, 1)
    method.addInstructions(
        0,
        """
            move/from16 v0, p2
            invoke-static { v0 }, $PHOTO_QUALITY_HOOK
            move-result v0
            move/from16 p2, v0
        """,
    )
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
