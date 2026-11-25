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
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep HDR on photo uploads"
internal const val HDR_PHOTOS = "$EXTENSION_PACKAGE/media/HdrPhotos;"

/** Asked with Instagram's answer to whether photos keep Ultra HDR, where that check returns. */
internal const val HDR_PHOTOS_ULTRA_HDR = "$HDR_PHOTOS->ultraHdr(I)I"

/** Asked with the renderer's flag for gain maps in color, right after it's read. */
internal const val HDR_PHOTOS_COLOR_GAINMAP = "$HDR_PHOTOS->colorGainmap(I)I"

/** Told of each photo JPEG the renderer writes with Android's encoder: the picture, the file and whether it went through. */
internal const val HDR_PHOTOS_SAVED = "$HDR_PHOTOS->saved(Landroid/graphics/Bitmap;Ljava/lang/String;I)V"

/**
 * The photo preview after the editor, whose onResume puts the window in HDR color mode when the
 * check for keeping Ultra HDR on photos says yes. It's the only caller of that check besides the
 * editor's photo source.
 */
internal const val THUMBNAIL_PREVIEW = "Linstagram/features/creation/fragment/preview/ThumbnailPreviewFragment;"
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** Loaded by the renderer that draws the edited photo and its gain map and saves them. */
internal const val FINAL_RENDERER_MARKER = "ImageFinalRenderer"

/** Loaded by the photo save step where it writes a JPEG with Android's encoder. */
internal const val PHOTO_SAVE_MARKER = "UltraHDR"

internal const val SET_GAINMAP = "Landroid/graphics/Bitmap;->setGainmap(Landroid/graphics/Gainmap;)V"
private const val SDK_INT = "Landroid/os/Build\$VERSION;->SDK_INT:I"
private const val COMPRESS_FORMAT = "Landroid/graphics/Bitmap\$CompressFormat;"
internal const val JPEG_FORMAT = "$COMPRESS_FORMAT->JPEG:$COMPRESS_FORMAT"
private const val BITMAP = "Landroid/graphics/Bitmap;"
private const val FILE = "Ljava/io/File;"
private const val STRING = "Ljava/lang/String;"

/** A method by its class, name and parameter types, which is how a hook finds it again to change it. */
internal data class PhotoMethod(val type: String, val name: String, val parameters: List<String>)

/**
 * Where the patch goes in. [gate] is the check for keeping Ultra HDR on photos, its one return at
 * [gateReturn] on [gateRegister]. [renderer] is the final renderer, its flag for gain maps in color
 * read into [colorRegister] by the instruction before [colorBranch], the branch on it. [save] is
 * the photo save step, whose JPEG write is followed at [savedAt] by the hook, with the picture, the
 * path and the write's answer in [picture], [path] and [written].
 */
internal data class PhotoHdrSites(
    val gate: PhotoMethod,
    val gateReturn: Int,
    val gateRegister: Int,
    val renderer: PhotoMethod,
    val colorBranch: Int,
    val colorRegister: Int,
    val save: PhotoMethod,
    val savedAt: Int,
    val picture: Int,
    val path: Int,
    val written: Int,
)

@Suppress("unused")
val keepHdrOnPhotoUploadsPatch = bytecodePatch(
    name = "Keep HDR on photo uploads",
    description = "Lets an Ultra HDR photo you post keep its HDR gain map on Android 14 and newer. " +
        "Experimental. Starts off. Turn it on in HushGram settings > Playback.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("hdrPhotos")
        keepHdrOnPhotoUploads(findHdrPhotos())
        enableStatus("hdrPhotos")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds all three places before anything changes, and throws naming the one that isn't there.
 *
 * The check (457 `0RjC.A00`) is the one static `(UserSession)Z` method [THUMBNAIL_PREVIEW]'s
 * onResume calls. It reads the Android version and returns once. The renderer (457 `0UGd.H39`) is
 * the one method loading [FINAL_RENDERER_MARKER] that calls [SET_GAINMAP]. Its gain map is drawn
 * when the source's gain map is one channel, an `if-nez` on a boolean field, or when the flag for
 * gain maps in color says yes, the one other `if-nez` landing on the same instruction, right after
 * a call answering a boolean. The save step (457 `0D3E.A05`) is the one method loading
 * [PHOTO_SAVE_MARKER] that reads [JPEG_FORMAT]: its one JPEG write takes the picture, and the file
 * it writes to is made from the path just before.
 */
internal fun BytecodePatchContext.findHdrPhotos(): PhotoHdrSites {
    val extension = classDefByOrNull(HDR_PHOTOS) ?: refuse("the extension has no $HDR_PHOTOS")
    for (hook in listOf(HDR_PHOTOS_ULTRA_HDR, HDR_PHOTOS_COLOR_GAINMAP, HDR_PHOTOS_SAVED)) {
        if (extension.methods.none { it.descriptor() == hook && it.isPublicStatic() }) refuse("the extension has no public static $hook")
    }

    // The check.
    val preview = classDefByOrNull(THUMBNAIL_PREVIEW) ?: refuse("there's no $THUMBNAIL_PREVIEW")
    val onResume = preview.methods.singleOrNull { it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        ?: refuse("$THUMBNAIL_PREVIEW has no onResume()")
    val checks = onResume.code().mapNotNull { instruction ->
        (instruction.reference() as? MethodReference)?.takeIf {
            instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION)
        }
    }
    val check = checks.singleOrNull() ?: refuse("expected one static ($USER_SESSION)Z call in $THUMBNAIL_PREVIEW.onResume, found ${checks.size}")
    val gate = classDefByOrNull(check.definingClass)?.methods?.singleOrNull {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION)
    } ?: refuse("the check $check isn't in the app")
    val gateCode = gate.code()
    if (!AccessFlags.STATIC.isSet(gate.accessFlags) || gateCode.none { (it.reference() as? FieldReference)?.toString() == SDK_INT }) {
        refuse("${gate.descriptor()} isn't a static check of the Android version")
    }
    gate.requireUnhooked()
    val returns = gateCode.indices.filter { gateCode[it].opcode == Opcode.RETURN }
    val gateReturn = returns.singleOrNull() ?: refuse("expected one return in ${gate.descriptor()}, found ${returns.size}")
    val gateRegister = (gateCode[gateReturn] as OneRegisterInstruction).registerA

    // The renderer's flag for gain maps in color.
    val renderers = classesLoadingString(FINAL_RENDERER_MARKER).flatMap { holder ->
        holder.methods.filter { method ->
            method.code().any { (it.reference() as? MethodReference)?.toString() == SET_GAINMAP } && loadsString(method, FINAL_RENDERER_MARKER)
        }
    }
    val renderer = renderers.singleOrNull()
        ?: refuse("expected one method loading \"$FINAL_RENDERER_MARKER\" that calls $SET_GAINMAP, found ${renderers.size}")
    renderer.requireUnhooked()
    val renderCode = renderer.code()
    val oneChannel = renderCode.indices.filter { at ->
        renderCode[at].opcode == Opcode.IF_NEZ && renderCode.getOrNull(at - 1)?.let {
            it.opcode == Opcode.IGET_BOOLEAN && (it as OneRegisterInstruction).registerA == (renderCode[at] as OneRegisterInstruction).registerA
        } == true
    }.map { renderCode.landing(it) }.toSet()
    val colorBranches = renderCode.indices.filter { at -> renderCode.isFlagBranch(at) && renderCode.landing(at) in oneChannel }
    val colorBranch = colorBranches.singleOrNull()
        ?: refuse("expected one branch on the flag for gain maps in color in ${renderer.descriptor()}, found ${colorBranches.size}")
    if (colorBranch in renderer.jumpTargets()) refuse("something in ${renderer.descriptor()} jumps onto the branch on the color flag")
    val colorRegister = (renderCode[colorBranch] as OneRegisterInstruction).registerA

    // The photo save step's JPEG write.
    val saves = classesLoadingString(PHOTO_SAVE_MARKER).flatMap { holder ->
        holder.methods.filter { method ->
            method.code().any { (it.reference() as? FieldReference)?.toString() == JPEG_FORMAT } && loadsString(method, PHOTO_SAVE_MARKER)
        }
    }
    val save = saves.singleOrNull() ?: refuse("expected one method loading \"$PHOTO_SAVE_MARKER\" that reads $JPEG_FORMAT, found ${saves.size}")
    save.requireUnhooked()
    val saveCode = save.code()
    val writes = saveCode.indices.filter { saveCode.writesJpeg(it) }
    val write = writes.singleOrNull() ?: refuse("expected one JPEG write in ${save.descriptor()}, found ${writes.size}")
    val result = saveCode.getOrNull(write + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) refuse("the JPEG write in ${save.descriptor()} isn't followed by its answer")
    val written = (result as OneRegisterInstruction).registerA
    val savedAt = write + 2
    if (savedAt >= saveCode.size || savedAt in save.jumpTargets()) refuse("something in ${save.descriptor()} jumps right past the JPEG write's answer")
    val picture = saveCode[write].pictureRegister()
    val files = (0 until write).filter { saveCode[it].makesFileFromPath() }
    val file = files.lastOrNull() ?: refuse("${save.descriptor()} makes no file from a path before the JPEG write")
    val path = saveCode[file].registers().last()
    if ((file + 1..write).any { saveCode[it].writes(path) }) refuse("the path in ${save.descriptor()} changes before the JPEG write")
    if (written == picture || written == path) refuse("the JPEG write's answer in ${save.descriptor()} lands on the picture or the path")
    if (listOf(picture, path, written).any { it > 15 }) refuse("the picture, path or answer in ${save.descriptor()} sits past v15")

    return PhotoHdrSites(
        gate.site(), gateReturn, gateRegister,
        renderer.site(), colorBranch, colorRegister,
        save.site(), savedAt, picture, path, written,
    )
}

/**
 * Hands the check's answer to [HDR_PHOTOS_ULTRA_HDR] where it returns, and the renderer's flag for
 * gain maps in color to [HDR_PHOTOS_COLOR_GAINMAP] before its branch, each answer going back into
 * its own register, and tells [HDR_PHOTOS_SAVED] of each JPEG the save step writes. With the switch
 * off the extension answers what Instagram did.
 */
internal fun BytecodePatchContext.keepHdrOnPhotoUploads(sites: PhotoHdrSites) {
    mutableClassMethod(sites.gate).addInstructionsAtControlFlowLabel(
        sites.gateReturn,
        """
            invoke-static/range { v${sites.gateRegister} .. v${sites.gateRegister} }, $HDR_PHOTOS_ULTRA_HDR
            move-result v${sites.gateRegister}
        """,
    )
    mutableClassMethod(sites.renderer).addInstructions(
        sites.colorBranch,
        """
            invoke-static/range { v${sites.colorRegister} .. v${sites.colorRegister} }, $HDR_PHOTOS_COLOR_GAINMAP
            move-result v${sites.colorRegister}
        """,
    )
    mutableClassMethod(sites.save).addInstructions(
        sites.savedAt,
        "invoke-static { v${sites.picture}, v${sites.path}, v${sites.written} }, $HDR_PHOTOS_SAVED",
    )
}

private fun BytecodePatchContext.mutableClassMethod(site: PhotoMethod) =
    mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }

private fun Method.site() = PhotoMethod(definingClass, name, parameterTypes.map(CharSequence::toString))

private fun Method.requireUnhooked() {
    if (code().any { (it.reference() as? MethodReference)?.definingClass == HDR_PHOTOS }) refuse("${descriptor()} already calls $HDR_PHOTOS")
}

private fun Method.isPublicStatic() = AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags)
private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/** An `if-nez` on a boolean a call answered right before it: the call, a `move-result` into the register, the branch. */
private fun List<Instruction>.isFlagBranch(at: Int): Boolean {
    val branch = this[at]
    if (branch.opcode != Opcode.IF_NEZ) return false
    val result = getOrNull(at - 1) ?: return false
    if (result.opcode != Opcode.MOVE_RESULT || (result as OneRegisterInstruction).registerA != (branch as OneRegisterInstruction).registerA) return false
    val call = getOrNull(at - 2)?.reference() as? MethodReference ?: return false
    return call.returnType == "Z"
}

/** A JPEG write with Android's encoder at [at]: a call taking a format, a picture and a stream, handed the JPEG format read before it. */
private fun List<Instruction>.writesJpeg(at: Int): Boolean {
    val call = this[at].reference() as? MethodReference ?: return false
    val parameters = call.parameterTypes.map(CharSequence::toString)
    val takesPicture = call.definingClass == BITMAP || BITMAP in parameters
    if (call.returnType != "Z" || COMPRESS_FORMAT !in parameters || "Ljava/io/OutputStream;" !in parameters || !takesPicture) return false
    val format = this[at].registers().getOrNull(parameters.indexOf(COMPRESS_FORMAT) + if (this[at].opcode.isStaticCall()) 0 else 1) ?: return false
    val read = (0 until at).lastOrNull { this[it].writes(format) } ?: return false
    return (this[read].reference() as? FieldReference)?.toString() == JPEG_FORMAT
}

/** The register holding the picture a JPEG write takes: its Bitmap argument, or the Bitmap it's called on. */
private fun Instruction.pictureRegister(): Int {
    val call = reference() as MethodReference
    val parameters = call.parameterTypes.map(CharSequence::toString)
    return if (opcode.isStaticCall()) registers()[parameters.indexOf(BITMAP)] else registers()[0]
}

/** A file made from a path: a static `(String)File` call or `new File(String)`. */
private fun Instruction.makesFileFromPath(): Boolean {
    val call = reference() as? MethodReference ?: return false
    val parameters = call.parameterTypes.map(CharSequence::toString)
    return parameters == listOf(STRING) && (
        (opcode.isStaticCall() && call.returnType == FILE) ||
            (call.definingClass == FILE && call.name == "<init>")
        )
}

private fun Opcode.isStaticCall() = this == Opcode.INVOKE_STATIC || this == Opcode.INVOKE_STATIC_RANGE

private fun Instruction.writes(register: Int): Boolean =
    opcode.setsRegister() && (this as? OneRegisterInstruction)?.registerA.let {
        it == register || (opcode.setsWideRegister() && it != null && it + 1 == register)
    }

private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** The index the branch at [index] lands on. */
private fun List<Instruction>.landing(index: Int): Int {
    var address = 0
    val addresses = map { instruction -> address.also { address += instruction.codeUnits } }
    val target = addresses[index] + (this[index] as OffsetInstruction).codeOffset
    return addresses.indexOf(target).takeIf { it >= 0 } ?: refuse("a branch lands between instructions")
}
