/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.images

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.privacy.code
import app.morphe.patches.instagram.privacy.describe
import app.morphe.patches.instagram.privacy.exactlyOne
import app.morphe.patches.instagram.privacy.methodsHolding
import app.morphe.patches.instagram.privacy.mutable
import app.morphe.patches.instagram.privacy.refuse
import app.morphe.patches.instagram.privacy.stringLoaded
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val PATCH = "Improve image viewing"
private const val IMAGE_SIZE = "$EXTENSION_PACKAGE/media/ImageSize;"
internal const val TARGET = "$IMAGE_SIZE->target(I)I"
internal const val SCREEN = "$IMAGE_SIZE->screen(Ljava/lang/Integer;)Ljava/lang/Integer;"

/** The class Instagram keeps the name of that holds one copy of a photo, and the list the picker is handed. */
internal const val EXTENDED_IMAGE_URL = "Lcom/instagram/model/mediasize/ExtendedImageUrl;"

/** The format Instagram writes the screen into its User-Agent header with: density, width, height. */
internal const val SCREEN_FORMAT = "%sdpi; %sx%s"

@Suppress("unused")
val improveImageViewingPatch = bytecodePatch(
    name = "Improve image viewing",
    description = "Photos open at the largest size Instagram sends, and a second switch asks Instagram for larger " +
        "ones by reporting a bigger screen in the header of its requests. Both have switches in HushGram's settings.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("imageViewing")
        val picker = findPicker()
        val header = findScreenHeader()
        raisePickerTarget(picker)
        raiseScreenHeader(header)
        enableStatus("imageViewing")
    }
}

/**
 * The picker is the one static method that takes the mode, the list of copies and the wanted size and
 * answers a copy: it goes through the list and keeps the copy closest to the wanted size.
 */
internal fun BytecodePatchContext.findPicker(): Method {
    val found = classesCalling(EXTENDED_IMAGE_URL, "getHeight").flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == EXTENDED_IMAGE_URL &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Integer;", "Ljava/util/List;", "I")
        }
    }
    return found.exactlyOne(PATCH, "static method choosing a photo's copy")
}

internal fun BytecodePatchContext.raisePickerTarget(picker: Method) {
    val method = mutable(picker)
    if (0 in method.jumpTargets()) refuse(PATCH, "something jumps to the start of ${method.describe()}")
    val wanted = method.parameterRegisterNumber(2)
    method.addInstructions(
        0,
        """
            invoke-static/range { v$wanted .. v$wanted }, $TARGET
            move-result v$wanted
        """,
    )
}

/** Where the width and height go into the header's array: the array's index and its two registers. */
internal class HeaderSite(val method: Method, val at: Int, val width: Int, val height: Int)

/**
 * The method that writes the User-Agent's screen size takes the context and loads [SCREEN_FORMAT].
 * Just before the format is loaded, three boxed numbers are gathered into an array: the density, the
 * width and the height, in that order. The hook boxes the second and third once more, bigger.
 */
internal fun BytecodePatchContext.findScreenHeader(): HeaderSite {
    val method = methodsHolding(SCREEN_FORMAT).filter {
        it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
    }.exactlyOne(PATCH, "method writing the screen size into the User-Agent")
    val code = method.code()
    val arrays = code.indices.filter { index ->
        val gather = code[index]
        gather.opcode == Opcode.FILLED_NEW_ARRAY && (gather as FiveRegisterInstruction).registerCount == 3 &&
            code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            code.getOrNull(index + 2)?.stringLoaded() == SCREEN_FORMAT
    }
    val at = arrays.exactlyOne(PATCH, "array of density, width and height in ${method.describe()}")
    val gather = code[at] as FiveRegisterInstruction
    return HeaderSite(method, at, gather.registerD, gather.registerE)
}

internal fun BytecodePatchContext.raiseScreenHeader(site: HeaderSite) {
    val method = mutable(site.method)
    if (site.at in method.jumpTargets()) refuse(PATCH, "something jumps to the screen size array in ${method.describe()}")
    if (site.width > 15 || site.height > 15) refuse(PATCH, "${method.describe()} keeps the size in a register past v15")
    method.addInstructions(
        site.at,
        """
            invoke-static { v${site.width} }, $SCREEN
            move-result-object v${site.width}
            invoke-static { v${site.height} }, $SCREEN
            move-result-object v${site.height}
        """,
    )
}
