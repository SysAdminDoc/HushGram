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
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.INSTAGRAM_APPLICATION
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.declaredInHierarchy
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import kotlin.math.abs

private const val PATCH = "Full resolution photos"

internal const val LARGER_PHOTOS = "$EXTENSION_PACKAGE/feed/LargerPhotos;"
internal const val WANTED = "$LARGER_PHOTOS->wanted(I)I"
internal const val SCREEN = "$LARGER_PHOTOS->screen([Ljava/lang/Object;)V"
internal const val AT_START = "$LARGER_PHOTOS->atStart(Landroid/content/Context;)V"
internal const val SAMPLE_SIZE = "$LARGER_PHOTOS->sampleSize(Landroid/view/View;Ljava/lang/Object;I)I"

/** Instagram's image view, which keeps its name and the getter of the largest sample size its pictures decode at. */
internal const val IG_IMAGE_VIEW = "Lcom/instagram/common/ui/widget/imageview/IgImageView;"

/** The start of that getter's name. The rest names Instagram's build target. */
internal const val MAX_SAMPLE_SIZE_GETTER = "getMaxSampleSize"

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

/**
 * Where the image view hands a picture's request the largest sample size it may decode at: the
 * method, the index right after the view reads the size, the registers of the view, the picture
 * the method is handed and the size, and a free low register for each of those a plain invoke
 * can't name.
 */
internal class DecodeSite(
    val method: MutableMethod,
    val after: Int,
    val view: Int,
    val picture: Int,
    val size: Int,
    val spare: List<Int>,
)

/**
 * Finds the field [IG_IMAGE_VIEW]'s kept getter answers and every method of the view taking one
 * [IMAGE_URL] that reads it (two in 454, setting a picture by either of Instagram's pipelines).
 * Each must read it once, into a register other than the view's, with nothing jumping in right
 * after, and never write over the picture it's handed. Fails before anything changes when any of
 * it doesn't hold, or the hooks can't have the low registers they need.
 */
internal fun BytecodePatchContext.findDecodes(): List<DecodeSite> {
    val view = classDefByOrNull(IG_IMAGE_VIEW) ?: refuse("$IG_IMAGE_VIEW isn't there")
    val getters = view.methods.filter {
        it.name.startsWith(MAX_SAMPLE_SIZE_GETTER) && it.parameterTypes.isEmpty() && it.returnType == "I"
    }
    val getter = getters.singleOrNull()
        ?: refuse("expected $IG_IMAGE_VIEW to keep one getter of its largest sample size, found ${getters.size}")
    val field = getter.instructions().mapNotNull { it.intFieldRead() }.singleOrNull()
        ?.takeIf { it.definingClass == IG_IMAGE_VIEW }
        ?: refuse("${getter.name} doesn't answer one int field of the view")
    val readers = view.methods.filter { method ->
        method.parameterTypes.count { it.toString() == IMAGE_URL } == 1 &&
            method.instructions().any { it.intFieldRead()?.toString() == field.toString() }
    }
    if (readers.isEmpty()) refuse("nothing in $IG_IMAGE_VIEW handed a picture reads its largest sample size")
    val mutable = mutableClassDefBy(IG_IMAGE_VIEW)
    return readers.map { reader ->
        val method = mutable.methods.single {
            it.name == reader.name && it.parameterTypes.map(CharSequence::toString) == reader.parameterTypes.map(CharSequence::toString)
        }
        decodeSite(method, field)
    }
}

private fun decodeSite(method: MutableMethod, field: FieldReference): DecodeSite {
    val where = "${method.definingClass}->${method.name}"
    val code = method.instructions()
    val reads = code.indices.filter { code[it].intFieldRead()?.toString() == field.toString() }
    val read = reads.singleOrNull() ?: refuse("expected $where to read the largest sample size once, found ${reads.size}")
    val (size, view) = (code[read] as TwoRegisterInstruction).let { it.registerA to it.registerB }
    if (size == view) refuse("$where reads the largest sample size over the view's own register v$view")
    if (read + 1 in method.jumpTargets()) refuse("something in $where jumps in right after it reads the largest sample size")
    val picture = method.parameterRegister(IMAGE_URL)
    if (code.any { it.writesTo(picture) }) refuse("$where writes over the picture it's handed in v$picture")
    val high = listOf(view, picture, size).count { it > 15 }
    // Spares are written before the call reads the view and the size, so neither may be one.
    val spare = if (high == 0) emptyList() else method.freeLocalsAt(PATCH, read + 1, high, except = listOf(view, size))
    return DecodeSite(method, read + 1, view, picture, size, spare)
}

/**
 * Hands [SAMPLE_SIZE] the view, the picture and the size right after the view reads the size,
 * keeping the answer as the size. A register past v15 is copied into a spare one first.
 */
internal fun decodeToFit(sites: List<DecodeSite>) {
    for (site in sites) {
        val spare = site.spare.iterator()
        val moves = mutableListOf<String>()
        fun low(register: Int, move: String): Int {
            if (register <= 15) return register
            val into = spare.next()
            moves += "$move v$into, v$register"
            return into
        }
        val view = low(site.view, "move-object/from16")
        val picture = low(site.picture, "move-object/from16")
        val size = low(site.size, "move/from16")
        site.method.addInstructions(
            site.after,
            (moves + "invoke-static { v$view, v$picture, v$size }, $SAMPLE_SIZE" + "move-result v${site.size}").joinToString("\n"),
        )
    }
}

/** The register the one parameter of [type] arrives in. */
private fun Method.parameterRegister(type: String): Int {
    val widths = parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    val first = implementation!!.registerCount - widths.sum()
    val at = parameterTypes.indexOfFirst { it.toString() == type }
    return first + widths.take(at).sum()
}

private fun Instruction.writesTo(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

private fun Instruction.intFieldRead(): FieldReference? =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { opcode == Opcode.IGET && it.type == "I" }

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
