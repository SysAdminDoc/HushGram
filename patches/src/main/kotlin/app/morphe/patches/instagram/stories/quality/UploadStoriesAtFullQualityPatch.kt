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
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Upload stories at full quality"
internal const val STORY_UPLOAD_QUALITY = "$EXTENSION_PACKAGE/stories/StoryUploadQuality;"
internal const val FULL_QUALITY = "$STORY_UPLOAD_QUALITY->fullQuality(Ljava/lang/Object;)Z"
internal const val BITRATE_HOOK = "$STORY_UPLOAD_QUALITY->bitrate(Ljava/lang/Object;I)I"
internal const val RESOLUTION_HOOK = "$STORY_UPLOAD_QUALITY->resolution(Ljava/lang/Object;I)I"
internal const val RENDER_WIDTH_HOOK = "$STORY_UPLOAD_QUALITY->renderWidth(II)I"
internal const val FRAME_RATE_HOOK = "$STORY_UPLOAD_QUALITY->frameRate(Ljava/lang/Object;I)I"
internal const val PHOTO_QUALITY_HOOK = "$STORY_UPLOAD_QUALITY->photoQuality(I)I"
internal const val PHOTO_TIER_HOOK = "$STORY_UPLOAD_QUALITY->photoTier(II)I"
internal const val UPLOAD_SIZE_HOOK = "$STORY_UPLOAD_QUALITY->uploadSize(Ljava/lang/Object;II)I"

/** The key the server's upload settings reply gives the encode's size under, read where Instagram builds that encode. */
internal const val TRANSCODE_DIMENSION = "transcode_dimension"

private const val JSON_OBJECT = "Lorg/json/JSONObject;"

/** The error Instagram's photo size step builds when the size it worked out has the wrong shape. */
internal const val PHOTO_SIZE_ERROR = "Output aspect ratio error: "

/**
 * The photo size step's larger sizes the patch can turn on, longest side in pixels. Its 2048 is left
 * alone, as it's shorter than the 2560 a story photo already goes up at.
 */
internal val PHOTO_TIERS = listOf(3072, 4096, 8192)

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

private const val POINT = "Landroid/graphics/Point;"
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
 *
 * The photo size step (`0D3B.A01` on 454's 386014758) has larger sizes the server can turn on, 2048
 * to 8192 on the longest side, each keeping a photo at least nine tenths that big at its own size up
 * to it. The photo renderer passes them all off. Photo size turns on the 3072, 4096 and 8192 ones up
 * to its choice, through [PHOTO_TIER_HOOK] at the start of the step.
 *
 * Traced on 454 (2026-11-11, #37): the video that goes up is encoded from the server's upload settings
 * reply (`0K64.A01`), at its [TRANSCODE_DIMENSION] on the short side (1080) and the 30 fps default, not
 * from the width step's answer. Video resolution and Video frame rate reach that encode through
 * [UPLOAD_SIZE_HOOK] and [FRAME_RATE_HOOK] right before it's built.
 */
@Suppress("unused")
val uploadStoriesAtFullQualityPatch = bytecodePatch(
    name = "Upload stories at full quality",
    description = "Sends the video stories you post at 1080p and a higher bitrate, as Instagram does with its own " +
        "Upload at highest quality setting on. Choices under it can cover posts and reels too and ask for a " +
        "bitrate up to 50 Mbps, up to 2160p and 120 fps where your phone's video encoder can take them, or a higher photo " +
        "quality and size. Instagram still shrinks story videos to 720p on its servers, so " +
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
        val size = findPhotoSizeStep()
        val settings = findUploadSettings()
        raiseStoryUploadQuality(check)
        raiseBitrate(check, bitrate)
        raiseWidth(width)
        raisePhotoQuality(photo)
        raisePhotoSize(size)
        raiseUploadSize(settings)
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
 * Instagram's width cap in the width step (`0MiN.A00` on 454): the call's index, the register its
 * answer lands in, and the register holding the clip's width it was handed.
 */
internal class WidthCap(val at: Int, val width: Int, val source: Int)

/**
 * The width step: the one static method [VIDEO_INGESTION_STEP] calls that takes the context, the session,
 * an upload, an int and four booleans, loads both widths it knows ([WIDTH_1080] and [WIDTH_1440]), caps
 * the width after that ([widthCap]), and whose upload class has one instance getter for its [SHARE_TYPE].
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
    method.widthCap(where)
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

/**
 * The width cap: the first static `(Context, I, Z, Z, Z)I` call after the width step loads [WIDTH_1440]
 * (454's landscape branch caps too, before that, and never reads the width asked for), its answer moved
 * straight into a register no wider than v15 that isn't the clip's width it was handed, with nothing
 * jumping to just after that move.
 */
private fun Method.widthCap(where: String): WidthCap {
    val code = implementation?.instructions?.toList().orEmpty()
    val step = code.indexOfFirst { (it as? NarrowLiteralInstruction)?.narrowLiteral == WIDTH_1440 }
    val at = code.indices.firstOrNull { at ->
        step >= 0 && at > step && code[at].opcode == Opcode.INVOKE_STATIC &&
            ((code[at] as ReferenceInstruction).reference as MethodReference).isWidthCapShape()
    } ?: refuse("expected a width cap after the $WIDTH_1440 step in $where")
    val result = code.getOrNull(at + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) refuse("$where doesn't keep its width cap's answer")
    if (at + 2 >= code.size || (at + 2) in jumpTargets()) refuse("something jumps to just after the width cap in $where")
    val width = (result as OneRegisterInstruction).registerA
    val source = (code[at] as FiveRegisterInstruction).registerD
    if (width > 15 || source > 15) refuse("the width cap in $where works in v$width and v$source, past what a call can name")
    if (width == source) refuse("the width cap in $where answers over the clip's width")
    return WidthCap(at, width, source)
}

private fun MethodReference.isWidthCapShape() =
    returnType == "I" && parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, "I", "Z", "Z", "Z")

/**
 * First thing in the width step, swaps the width it was asked for with [RESOLUTION_HOOK]'s answer, a
 * null upload asking with a null share type, so every width step starts the hook afresh. Right after
 * the width cap, hands [RENDER_WIDTH_HOOK] the cap's answer and the clip's width and takes its answer.
 */
internal fun BytecodePatchContext.raiseWidth(step: WidthStep) {
    val method = mutableClassDefBy(step.method.definingClass).methods.single { it.sameAs(step.method) }
    val where = "${step.method.definingClass}->${step.method.name}"
    if (0 in method.jumpTargets()) refuse("something jumps back to the first instruction of $where")
    method.requireLocals(PATCH, 2)
    val cap = method.widthCap(where)
    method.addInstructions(
        cap.at + 2,
        """
            invoke-static { v${cap.width}, v${cap.source} }, $RENDER_WIDTH_HOOK
            move-result v${cap.width}
        """,
    )
    val invoke = if (step.onInterface) "invoke-interface" else "invoke-virtual"
    method.addInstructionsWithLabels(
        0,
        """
            const/4 v0, 0x0
            move-object/from16 v1, p2
            if-eqz v1, :ask
            $invoke { v1 }, ${step.shareType.definingClass}->${step.shareType.name}()$SHARE_TYPE
            move-result-object v0
            :ask
            move/from16 v1, p3
            invoke-static { v0, v1 }, $RESOLUTION_HOOK
            move-result v0
            move/from16 p3, v0
        """,
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

/** The photo size step, and the register of the flag for each of [PHOTO_TIERS]. */
internal class PhotoSizeStep(val method: MethodReference, val tiers: Map<Int, Int>)

/**
 * The photo size step: the one method that loads [PHOTO_SIZE_ERROR] and takes a point, the session,
 * two ints and booleans, answering a point. For each of [PHOTO_TIERS] it branches past the size on a
 * boolean parameter of its own, loads the size and hands it with two other ints to a static `(III)Z`
 * size test. Those parameters are the flags [raisePhotoSize] sets.
 */
internal fun BytecodePatchContext.findPhotoSizeStep(): PhotoSizeStep {
    val steps = classesLoadingString(PHOTO_SIZE_ERROR).flatMap { classDef ->
        classDef.methods.filter { it.isPhotoSizeStepShape() && loadsString(it, PHOTO_SIZE_ERROR) }
    }
    val method = steps.singleOrNull() ?: refuse("expected one photo size step loading \"$PHOTO_SIZE_ERROR\", found ${steps.size}")
    val where = "${method.definingClass}->${method.name}"
    val types = method.parameterTypes.map(CharSequence::toString)
    // Every parameter of the shape takes one register, so the first one's is this far past `this`.
    val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    val first = method.implementation!!.registerCount - types.size - self
    val code = method.implementation!!.instructions.toList()
    val tiers = PHOTO_TIERS.associateWith { tier ->
        val flags = code.indices.mapNotNull { at ->
            val load = code[at] as? NarrowLiteralInstruction ?: return@mapNotNull null
            val branch = code.getOrNull(at - 1) ?: return@mapNotNull null
            val call = code.getOrNull(at + 1) ?: return@mapNotNull null
            val test = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
            if (load.narrowLiteral != tier || branch.opcode != Opcode.IF_EQZ || call.opcode != Opcode.INVOKE_STATIC) return@mapNotNull null
            if (test.returnType != "Z" || test.parameterTypes.map(CharSequence::toString) != listOf("I", "I", "I")) return@mapNotNull null
            val flag = (branch as OneRegisterInstruction).registerA
            flag.takeIf { it - first - self in types.indices && types[it - first - self] == "Z" }
        }
        flags.singleOrNull() ?: refuse("$where tests the $tier px size on ${flags.size} boolean parameters, expected one")
    }
    if (tiers.values.distinct().size != tiers.size) refuse("$where tests two of its sizes on one parameter")
    return PhotoSizeStep(method, tiers)
}

private fun Method.isPhotoSizeStepShape(): Boolean {
    val types = parameterTypes.map(CharSequence::toString)
    return returnType == POINT && types.size > 4 && types[0] == POINT && types[1] == USER_SESSION &&
        types[2] == "I" && types[3] == "I" && types.drop(4).all { it == "Z" }
}

/**
 * First thing in the photo size step, hands [PHOTO_TIER_HOOK] each of [PHOTO_TIERS] with its flag and
 * puts the answer in the flag. Nothing else of the step changes.
 */
internal fun BytecodePatchContext.raisePhotoSize(step: PhotoSizeStep) {
    val method = mutableClassDefBy(step.method.definingClass).methods.single { it.sameAs(step.method) }
    if (0 in method.jumpTargets()) refuse("something jumps back to the first instruction of ${step.method.definingClass}->${step.method.name}")
    method.requireLocals(PATCH, 2)
    method.addInstructions(
        0,
        step.tiers.entries.joinToString("\n") { (tier, flag) ->
            """
                const/16 v0, $tier
                move/from16 v1, v$flag
                invoke-static { v0, v1 }, $PHOTO_TIER_HOOK
                move-result v0
                move/from16 v$flag, v0
            """
        },
    )
}

/**
 * Where Instagram builds the encode from the server's upload settings (`0K64.A01` on 454): the method,
 * the index of its call to the transcode builder, the registers that call hands the upload, size,
 * frame rate, expand flag and short side flag in, the fields from the upload to its share type (the
 * upload's network settings, `0Wqj.A0L:0Wua` on 454, and their share type), and two registers free at
 * the call.
 */
internal class UploadSettings(
    val method: MethodReference,
    val at: Int,
    val upload: Int,
    val size: Int,
    val frameRate: Int,
    val expand: Int,
    val shortSide: Int,
    val holder: FieldReference,
    val shareType: FieldReference,
    val scratch: Int,
    val answer: Int,
)

/**
 * The one instance method loading [TRANSCODE_DIMENSION] and reading the server's reply with
 * JSONObject.optInt, its one call to a transcode builder ([isTranscodeBuilderShape]) handed a size read
 * from that reply, and the route from the upload that call is handed to its share type: the upload's
 * one field of a class declaring a share type field, and that field. Builds differ in how the method
 * reaches the upload (a field of `this` on most, a field of `this` cast to a merged class on some) and
 * in whether the builder tests its media source for a share type at all, so the route starts at the
 * call. Fails before anything changes when any of it isn't so.
 */
internal fun BytecodePatchContext.findUploadSettings(): UploadSettings {
    val readers = classesLoadingString(TRANSCODE_DIMENSION).flatMap { classDef ->
        classDef.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && loadsString(method, TRANSCODE_DIMENSION) &&
                method.calls { it.definingClass == JSON_OBJECT && it.name == "optInt" }
        }
    }
    val method = readers.singleOrNull()
        ?: refuse("expected one method reading \"$TRANSCODE_DIMENSION\" from the server's upload settings, found ${readers.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val calls = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL_RANGE && ((code[at] as ReferenceInstruction).reference as MethodReference).isTranscodeBuilderShape()
    }
    val at = calls.singleOrNull() ?: refuse("expected one transcode builder call in $where, found ${calls.size}")
    val call = (code[at] as ReferenceInstruction).reference as MethodReference
    // The builder's own register comes first, then one for each argument.
    val first = (code[at] as RegisterRangeInstruction).startRegister + 1
    val upload = first + 2
    val size = first + 4
    val frameRate = first + 6
    val expand = first + 7
    val shortSide = first + 8
    if (shortSide > 15) refuse("the transcode builder call in $where passes its size and frame rate in v$size to v$shortSide, past what a call can name")
    if (!code.readsOptIntInto(at, size)) refuse("the size $where hands the transcode builder in v$size isn't read from the server's reply")
    if (at in method.jumpTargets()) refuse("something jumps to the transcode builder call in $where")

    val uploadType = call.parameterTypes[2].toString()
    val uploadClass = classDefByOrNull(uploadType) ?: refuse("the upload $uploadType isn't in this build")
    val routes = uploadClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.mapNotNull { holder ->
        val shareTypes = classDefByOrNull(holder.type)?.fields?.filter { it.type == SHARE_TYPE && !AccessFlags.STATIC.isSet(it.accessFlags) }
        shareTypes?.singleOrNull()?.let { holder to it }
    }
    val (holder, shareType) = routes.singleOrNull()
        ?: refuse("$uploadType holds ${routes.size} fields of a class holding a share type, expected one")

    val (scratch, answer) = method.freeLocalsAt("the transcode builder call in $where", at, 2)
    return UploadSettings(method, at, upload, size, frameRate, expand, shortSide, holder, shareType, scratch, answer)
}

/**
 * The transcode builder's shape (`0WeX.A00` on 454): three objects, the GOP length as a float, the
 * size, the bitrate and the frame rate, whether to scale a smaller clip up to the size, and whether the
 * size caps the short side, answering the list of encodes.
 */
private fun MethodReference.isTranscodeBuilderShape(): Boolean {
    val types = parameterTypes.map(CharSequence::toString)
    return types.size == 9 && types.take(3).all { it.startsWith("L") } && types[3] == "F" &&
        types.subList(4, 7).all { it == "I" } && types.subList(7, 9).all { it == "Z" } && returnType.startsWith("L")
}

/** Whether the last write of [register] before instruction [at] takes the answer of a JSONObject.optInt call, with nothing writing it after. */
private fun List<Instruction>.readsOptIntInto(at: Int, register: Int): Boolean {
    val write = (at - 1 downTo 0).firstOrNull { this[it].writes(register) } ?: return false
    if (this[write].opcode != Opcode.MOVE_RESULT || write == 0) return false
    val read = (this[write - 1] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return read.definingClass == JSON_OBJECT && read.name == "optInt"
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/**
 * Right before the transcode builder call, reads the upload's share type (null when the upload has no
 * network settings) and hands [UPLOAD_SIZE_HOOK] that, the size and the short side flag. A different
 * answer replaces the size and turns the scale-up off, so a smaller clip keeps its own size. Then hands
 * [FRAME_RATE_HOOK] the share type and the frame rate and takes its answer. Nothing else of the method
 * changes.
 */
internal fun BytecodePatchContext.raiseUploadSize(settings: UploadSettings) {
    val method = mutableClassDefBy(settings.method.definingClass).methods.single { it.sameAs(settings.method) }
    val share = settings.scratch
    val answer = settings.answer
    method.addInstructionsWithLabels(
        settings.at,
        """
            move-object/from16 v$share, v${settings.upload}
            iget-object v$share, v$share, ${settings.holder.definingClass}->${settings.holder.name}:${settings.holder.type}
            if-eqz v$share, :ask
            iget-object v$share, v$share, ${settings.shareType.definingClass}->${settings.shareType.name}:$SHARE_TYPE
            :ask
            invoke-static { v$share, v${settings.size}, v${settings.shortSide} }, $UPLOAD_SIZE_HOOK
            move-result v$answer
            if-eq v$answer, v${settings.size}, :rate
            move v${settings.size}, v$answer
            const/4 v${settings.expand}, 0x0
            :rate
            invoke-static { v$share, v${settings.frameRate} }, $FRAME_RATE_HOOK
            move-result v${settings.frameRate}
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
