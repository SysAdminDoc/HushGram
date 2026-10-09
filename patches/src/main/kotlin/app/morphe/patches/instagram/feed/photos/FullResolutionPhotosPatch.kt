/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.photos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.IMAGE_INFO
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.imageBridges
import app.morphe.patches.instagram.download.pickerSizesBridge
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Full resolution photos"
internal const val FULL_RESOLUTION = "$EXTENSION_PACKAGE/feed/FullResolution;"
internal const val PHOTO = "$FULL_RESOLUTION->photo(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

private const val CONTEXT = "Landroid/content/Context;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** A size of a picture: its address, width and height. Instagram keeps the name. */
internal const val EXTENDED_IMAGE_URL = "Lcom/instagram/model/mediasize/ExtendedImageUrl;"

/** The Kotlin file of Media's helpers. Instagram keeps the name, not the helpers' own. */
internal const val MEDIA_EXT = "Lcom/instagram/feed/media/MediaExtKt;"

/**
 * What the feed's image use case logs when a post's picture has no address. Only the method that
 * picks a feed photo's address loads it, with its trailing space.
 */
internal const val NO_IMAGE_URL =
    "instagram.features.feed.ui.rows.image.FeedImageUseCase#getImageSource() Could not generate imageUrl for mediaId= "

/**
 * Loads feed photos at the largest size the server sends rather than the one Instagram picks for
 * the screen. Included in the default selection with its switch initially off, since a larger
 * photo means more data. Only the pixels loaded change: the view's size comes from the post. A
 * second switch, also off to start, asks for a larger size on a phone narrower than 1440 pixels
 * (see LargerPhotos.kt).
 */
@Suppress("unused")
val fullResolutionPhotosPatch = bytecodePatch(
    name = "Full resolution photos",
    description = "Loads photos in the largest size Instagram sends instead of one picked for your screen. A " +
        "second switch asks for a larger size on screens under 1440 pixels wide. Uses more data. Starts off. Turn " +
        "it on in HushGram settings > Feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("fullResolution")
        val site = findFullResolution()
        val pickerSizes = findPickerSizes(site)
        val larger = findLargerSizes()
        val writeBridges = imageBridges(PATCH)
        val writePickerBridge = pickerSizesBridge(PATCH, pickerSizes)
        loadFullResolution(site)
        askForLarger(larger)
        writeBridges()
        writePickerBridge()
        enableStatus("fullResolution")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The feed image use case's instance method taking a Context, the session and a post, which logs [NO_IMAGE_URL]. */
internal object FeedImageSourceFingerprint : Fingerprint(
    parameters = listOf(CONTEXT, USER_SESSION, MEDIA),
    strings = listOf(NO_IMAGE_URL),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.instructions().any { it.string() == NO_IMAGE_URL }
    },
)

/**
 * Where the photo's size is picked: the method, the index of the move-result after the one call to
 * Media's helpers that takes a Context and the post and answers a size, the register the post is
 * in, the register the size lands in and the helper called.
 */
internal class FullResolutionSite(
    val definingClass: String,
    val name: String,
    val pick: Int,
    val media: Int,
    val size: Int,
    val forScreen: MethodReference,
)

/**
 * Finds the method that picks a feed photo's address and, in it, the one static call to Media's
 * helpers taking a Context and the post and answering an [EXTENDED_IMAGE_URL], the size for the
 * screen's width. The very next instruction must keep that size, in a register other than the
 * post's, both low enough for a plain invoke, and nothing may jump in right after the move-result, so every
 * way on from the pick passes the hook. Fails before anything changes when any of it isn't there
 * exactly once, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findFullResolution(): FullResolutionSite {
    val method = uniqueMethod(PATCH, "feed photo address method logging that it found none", FeedImageSourceFingerprint)
    val where = "${method.definingClass}->${method.name}"
    val code = method.instructions()
    val calls = code.indices.filter { at ->
        val instruction = code[at]
        val called = instruction.methodReference()
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            called != null && called.definingClass == MEDIA_EXT &&
            called.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, MEDIA) &&
            called.returnType == EXTENDED_IMAGE_URL
    }
    val call = calls.singleOrNull()
        ?: refuse("expected $where to ask Media's helpers once for the size for a Context, found ${calls.size}")
    val result = code.getOrNull(call + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where doesn't keep the size it's handed")
    val size = (result as OneRegisterInstruction).registerA
    val media = code[call].arguments()[1]
    if (size == media) refuse("$where keeps the size in the post's own register v$media")
    if (size > 15 || media > 15) refuse("$where keeps the post in v$media and the size in v$size, past what a plain invoke can name")
    if (call + 2 in method.jumpTargets()) refuse("something in $where jumps in right after the size it's handed")
    return FullResolutionSite(method.definingClass, method.name, call + 1, media, size, code[call].methodReference()!!)
}

/**
 * Finds the helper Instagram's picker reads a post's sizes through. The helper [site] calls for
 * the screen's size makes one static call to Media's helpers with the post and a width, and that
 * one makes one static call to Media's helpers taking only the post and answering an
 * [IMAGE_INFO], whose answer it hands first to a static method answering the size. Under two of
 * Instagram's server flags that helper answers a carousel page's sizes rather than the post's own.
 * Fails before anything changes when any of it isn't there exactly once.
 */
internal fun BytecodePatchContext.findPickerSizes(site: FullResolutionSite): MethodReference {
    val forScreen = helper(site.forScreen)
    val forWidth = forScreen.instructions().mapNotNull { it.staticCall() }.filter {
        it.definingClass == MEDIA_EXT && it.parameterTypes.map(CharSequence::toString) == listOf(MEDIA, "I") &&
            it.returnType == EXTENDED_IMAGE_URL
    }.singleOrNull() ?: refuse("expected ${site.forScreen} to ask Media's helpers once for the size for a width")
    val picker = helper(forWidth)
    val code = picker.instructions()
    val reads = code.indices.filter { at ->
        val called = code[at].staticCall()
        called != null && called.definingClass == MEDIA_EXT &&
            called.parameterTypes.map(CharSequence::toString) == listOf(MEDIA) && called.returnType == IMAGE_INFO
    }
    val read = reads.singleOrNull()
        ?: refuse("expected $forWidth to ask Media's helpers once for the post's sizes, found ${reads.size}")
    val kept = code.getOrNull(read + 1)
    if (kept?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$forWidth doesn't keep the sizes it reads")
    val sizes = (kept as OneRegisterInstruction).registerA
    val handedOn = code.drop(read + 2).takeWhile { !it.writes(sizes) }.any { instruction ->
        val called = instruction.staticCall()
        called != null && called.returnType == EXTENDED_IMAGE_URL &&
            called.parameterTypes.firstOrNull()?.toString() == IMAGE_INFO && instruction.arguments().firstOrNull() == sizes
    }
    if (!handedOn) refuse("$forWidth doesn't pick the size from the sizes it reads")
    return code[read].staticCall()!!
}

/** Media's helper [reference], which must be there with code. */
private fun BytecodePatchContext.helper(reference: MethodReference): Method =
    classDefBy(reference.definingClass).methods.singleOrNull {
        it.name == reference.name && it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString) &&
            it.returnType == reference.returnType && it.implementation != null
    } ?: refuse("$reference isn't there")

/**
 * Hands [PHOTO] the post and Instagram's pick right after the pick, and keeps what it answers in
 * the pick's register. The answer is the pick or a size of its class that the post or the picker
 * lists, and the check-cast keeps the verifier's view of the register as it was.
 */
internal fun BytecodePatchContext.loadFullResolution(site: FullResolutionSite) {
    val method = mutableClassDefBy(site.definingClass).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, USER_SESSION, MEDIA)
    }
    method.addInstructions(
        site.pick + 1,
        """
            invoke-static { v${site.media}, v${site.size} }, $PHOTO
            move-result-object v${site.size}
            check-cast v${site.size}, $EXTENDED_IMAGE_URL
        """,
    )
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.staticCall(): MethodReference? =
    methodReference()?.takeIf { opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE }

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/** The registers an invoke hands over, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
