/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.photolocation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal const val PHOTO_LOCATIONS_PATCH = "Keep photo locations from Instagram"
internal const val PHOTO_LOCATIONS = "$EXTENSION_PACKAGE/misc/PhotoLocations;"
internal const val ASK_FOR_ORIGINAL = "$PHOTO_LOCATIONS->original(I)Z"
internal const val GALLERY_ITEM_PLACE = "$PHOTO_LOCATIONS->galleryItem()Z"
internal const val EXIF_TAGS_PLACE = "$PHOTO_LOCATIONS->exifTags()Z"

/** What the image loader's gallery file fetcher logs when it can't build a fetcher for an item. */
internal const val FETCHER_FAILED = "Failed to build fetcher for: "

/** What a gallery item logs when it can't work out where it was taken. */
internal const val MEDIA_LOCATION_FAILED = "Could not extract media location."

/** The EXIF tag the reader of a file's place asks for first. */
internal const val GPS_LATITUDE = "GPSLatitude"

/** Android's ask for a gallery file with its location left in, which needs ACCESS_MEDIA_LOCATION. */
internal const val REQUIRE_ORIGINAL = "Landroid/provider/MediaStore;->setRequireOriginal(Landroid/net/Uri;)Landroid/net/Uri;"

/**
 * Keeps where the photos and videos in the gallery were taken away from Instagram (#628). Included
 * in the default selection with its switch off, so it's the user's pick.
 */
@Suppress("unused")
val keepPhotoLocationsPatch = bytecodePatch(
    name = "Keep photo locations from Instagram",
    description = "Stops Instagram from reading where the photos and videos in your gallery were taken. " +
        "Starts off. Turn it on in HushGram settings > Ads and privacy.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("photoLocations")
        val request = findOriginalRequest()
        val galleryItem = findGalleryItemPlace()
        val exifTags = findExifTagsPlace()
        askBeforeOpeningTheOriginal(request)
        answerNoPlace(galleryItem, GALLERY_ITEM_PLACE)
        answerNoPlace(exifTags, EXIF_TAGS_PLACE)
        enableStatus("photoLocations")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PHOTO_LOCATIONS_PATCH: $why")

private fun BytecodePatchContext.mutable(method: Method): MutableMethod {
    val types = method.parameterTypes.map(CharSequence::toString)
    return mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
}

private fun Instruction.asksForOriginal() =
    (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
        (this as ReferenceInstruction).reference.toString() == REQUIRE_ORIGINAL

/** Where the gallery file fetcher tests the permission: the hook goes at [index], and [register] holds the yes or no. */
internal class OriginalRequest(val method: MutableMethod, val index: Int, val register: Int)

/**
 * The image loader's gallery file fetcher (0cf8.Em0 on 454), the one method loading [FETCHER_FAILED]
 * that asks MediaStore for an original. Right before that ask it tests whether Instagram holds the
 * media location permission, and a no skips just the ask and its result, so the file opens with its
 * location taken out. All of it is proved here before anything changes.
 */
internal fun BytecodePatchContext.findOriginalRequest(): OriginalRequest {
    val found = classesLoadingString(FETCHER_FAILED).flatMap { it.methods }.filter { method ->
        method.implementation?.instructions?.any { it.asksForOriginal() } == true && loadsString(method, FETCHER_FAILED)
    }
    val fetch = found.singleOrNull()
        ?: refuse("expected one method loading \"$FETCHER_FAILED\" that asks for an original, found ${found.size}")
    val method = mutable(fetch)
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val asks = code.indices.filter { code[it].asksForOriginal() }
    val ask = asks.singleOrNull() ?: refuse("$where asks for an original ${asks.size} times")
    val test = code.getOrNull(ask - 1)
    if (test?.opcode != Opcode.IF_EQZ) refuse("$where doesn't test the permission right before asking for the original")
    if (code.getOrNull(ask + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where doesn't keep the original's address")
    if (ControlFlow.of(method).normal[ask - 1].firstOrNull() != ask + 2) refuse("$where doesn't skip just the ask on a no")
    if (ask - 1 in method.jumpTargets()) refuse("$where jumps onto its test of the permission")
    return OriginalRequest(method, ask - 1, (test as OneRegisterInstruction).registerA)
}

/** The yes or no goes through the extension before the test reads it, and stays a boolean. */
internal fun askBeforeOpeningTheOriginal(request: OriginalRequest) {
    request.method.addInstructions(
        request.index,
        """
            invoke-static/range { v${request.register} .. v${request.register} }, $ASK_FOR_ORIGINAL
            move-result v${request.register}
        """,
    )
}

/**
 * A gallery item's own reader of where it was taken (Medium.A0B on 454), the one method loading
 * [MEDIA_LOCATION_FAILED]: it takes the content resolver and answers the latitude and longitude, or
 * null when there's no place, which every caller checks for.
 */
internal fun BytecodePatchContext.findGalleryItemPlace(): MutableMethod {
    val found = classesLoadingString(MEDIA_LOCATION_FAILED).flatMap { it.methods }.filter { loadsString(it, MEDIA_LOCATION_FAILED) }
    val reader = found.singleOrNull() ?: refuse("expected one method loading \"$MEDIA_LOCATION_FAILED\", found ${found.size}")
    val method = mutable(reader)
    val where = "${method.definingClass}->${method.name}"
    if (AccessFlags.STATIC.isSet(method.accessFlags)) refuse("$where is static")
    if (method.returnType != "[D" || method.parameterTypes.map(CharSequence::toString) != listOf("Landroid/content/ContentResolver;")) {
        refuse("$where doesn't take the content resolver and answer a place")
    }
    return readyForEarlyReturn(method)
}

/**
 * The reader of a file's place from its EXIF tags (0E1h.A04 on 454): the one static method loading
 * [GPS_LATITUDE] that takes the tags and answers the latitude and longitude, or null when there's no
 * place. The class's other reader of [GPS_LATITUDE] writes the camera's place into a new photo and
 * answers nothing, and a gallery item's reader is an instance method; neither is this shape.
 */
internal fun BytecodePatchContext.findExifTagsPlace(): MutableMethod {
    val found = classesLoadingString(GPS_LATITUDE).flatMap { it.methods }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "[D" && method.parameterTypes.size == 1 &&
            loadsString(method, GPS_LATITUDE)
    }
    val reader = found.singleOrNull()
        ?: refuse("expected one static method loading \"$GPS_LATITUDE\" that takes one thing and answers a place, found ${found.size}")
    val method = mutable(reader)
    if (!method.parameterTypes.single().startsWith("L")) {
        refuse("${method.definingClass}->${method.name} doesn't take the EXIF tags")
    }
    return readyForEarlyReturn(method)
}

/** The hook goes in front of the first instruction, so nothing may jump back to it, and it borrows v0. */
private fun readyForEarlyReturn(method: MutableMethod): MutableMethod {
    if (0 in method.jumpTargets()) refuse("${method.definingClass}->${method.name} jumps back to its first instruction")
    method.requireLocals(PHOTO_LOCATIONS_PATCH, 1)
    return method
}

/** Asks [hook] first, and on a yes answers null, the reader's own answer for a file with no place. */
internal fun answerNoPlace(method: MutableMethod, hook: String) {
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :read
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("read", method.getInstruction(0)),
    )
}
