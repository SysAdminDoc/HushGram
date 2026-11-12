/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.photos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.INSTAGRAM_APPLICATION
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.declaredInHierarchy
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import kotlin.math.abs

private const val PATCH = "Full resolution photos"

internal const val LARGER_PHOTOS = "$EXTENSION_PACKAGE/feed/LargerPhotos;"
internal const val WANTED = "$LARGER_PHOTOS->wanted(I)I"
internal const val SCREEN = "$LARGER_PHOTOS->screen([Ljava/lang/Object;)V"
internal const val AT_START = "$LARGER_PHOTOS->atStart(Landroid/content/Context;)V"

/** The call the extension patch puts first in the application's onCreate, handing over the context. */
internal const val SET_CONTEXT = "$EXTENSION_CLASS_DESCRIPTOR->setContext(Landroid/content/Context;)V"

/** How Instagram's user agent writes the screen: its density, then width by height. */
internal const val SCREEN_FORMAT = "%sdpi; %sx%s"

/**
 * Instagram's size picker: static, taking a mode, a photo's sizes and the width it's shown at, and
 * answering the size nearest that width. It reads each size's width.
 */
internal object SizePickerFingerprint : Fingerprint(
    returnType = EXTENDED_IMAGE_URL,
    parameters = listOf("Ljava/lang/Integer;", "Ljava/util/List;", "I"),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation?.instructions?.any {
            val called = (it as? ReferenceInstruction)?.reference as? MethodReference
            called != null && called.definingClass == EXTENDED_IMAGE_URL && called.name == "getWidth"
        } == true
    },
)

/** The method building the device part of Instagram's user agent from a Context, which loads [SCREEN_FORMAT]. */
internal object ScreenReportFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string(SCREEN_FORMAT)),
)

/**
 * Where the larger size is asked for: the size picker and the register its width arrives in, and
 * the user agent's method, the register its screen parts land in and the index right after them.
 */
internal class LargerSizeSites(
    val picker: MutableMethod,
    val width: Int,
    val report: MutableMethod,
    val parts: Int,
    val after: Int,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the size picker and, in the user agent's method, the one three-part array made within four
 * instructions of [SCREEN_FORMAT] and kept by the very next instruction, with nothing jumping in
 * right after it. Fails before anything changes when any of it isn't there exactly once.
 */
internal fun BytecodePatchContext.findLargerSizes(): LargerSizeSites {
    val picker = uniqueMethod(PATCH, "size picker taking a mode, the sizes and a width", SizePickerFingerprint)
    val width = picker.implementation!!.registerCount - 1
    val report = uniqueMethod(PATCH, "user agent part writing the screen's size", ScreenReportFingerprint)
    val where = "${report.definingClass}->${report.name}"
    val code = report.instructions()
    val loads = code.indices.filter { code[it].string() == SCREEN_FORMAT }
    val load = loads.singleOrNull() ?: refuse("expected $where to load the screen's format once, found ${loads.size}")
    val arrays = code.indices.filter { at ->
        val instruction = code[at]
        abs(at - load) <= 4 && instruction.opcode == Opcode.FILLED_NEW_ARRAY &&
            (instruction as FiveRegisterInstruction).registerCount == 3 &&
            ((instruction as ReferenceInstruction).reference as? TypeReference)?.type == "[Ljava/lang/Object;"
    }
    val array = arrays.singleOrNull()
        ?: refuse("expected $where to make one three-part array beside the screen's format, found ${arrays.size}")
    val kept = code.getOrNull(array + 1)
    if (kept?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where doesn't keep the screen's parts")
    if (array + 2 in report.jumpTargets()) refuse("something in $where jumps in right after the screen's parts")
    return LargerSizeSites(picker, width, report, (kept as OneRegisterInstruction).registerA, array + 2)
}

/**
 * Hands [WANTED] the width at the start of the picker, keeping the answer as the width, and hands
 * [SCREEN] the screen's parts right after the user agent keeps them, so it can raise them in place.
 */
internal fun askForLarger(sites: LargerSizeSites) {
    sites.picker.addInstructions(
        0,
        """
            invoke-static/range { v${sites.width} .. v${sites.width} }, $WANTED
            move-result v${sites.width}
        """,
    )
    sites.report.addInstructions(sites.after, "invoke-static/range { v${sites.parts} .. v${sites.parts} }, $SCREEN")
}

/** Where the kept media replies are dropped: the application's onCreate and the index right after the extension gets its context. */
internal class StartSite(val method: MutableMethod, val after: Int)

/**
 * Finds the call handing the extension its context in the application's onCreate, which the
 * extension patch puts first, before Instagram's own startup opens its cache. Fails before anything
 * changes when it isn't there exactly once.
 */
internal fun BytecodePatchContext.findStart(): StartSite {
    val onCreate = declaredInHierarchy(INSTAGRAM_APPLICATION, "onCreate")
    val code = onCreate.instructions()
    val calls = code.indices.filter { code[it].methodText() == SET_CONTEXT }
    val call = calls.singleOrNull()
        ?: refuse("expected ${onCreate.definingClass}->onCreate to hand the extension its context once, found ${calls.size}")
    return StartSite(onCreate, call + 1)
}

/** Hands [AT_START] the application right after the extension gets its context. */
internal fun forgetAtStart(site: StartSite) {
    site.method.addInstruction(site.after, "invoke-static/range { p0 .. p0 }, $AT_START")
}

private fun MutableMethod.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
