/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.emoji

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Emoji style"

internal const val EMOJI_STYLE = "$EXTENSION_PACKAGE/misc/EmojiStyle;"
internal const val REPLACE_STRATEGY = "$EMOJI_STYLE->replaceStrategy(I)I"
internal const val EMOJI_FONTS = "$EXTENSION_PACKAGE/misc/EmojiFonts;"
internal const val SPAN_DRAWN = "$EMOJI_FONTS->drawn(Ljava/lang/Object;Landroid/graphics/Canvas;Ljava/lang/CharSequence;" +
    "IIFIIILandroid/graphics/Paint;)Z"

/** Two of EmojiCompat's checks of the range process is given, which no other method holds. */
internal const val RANGE_CHECK = "start should be <= than end"
internal const val LENGTH_CHECK = "end should be < than charSequence length"

private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"

/** What ReplacementSpan's draw takes, and how EmojiCompat's span draws its emoji's two characters. */
internal val SPAN_DRAW = listOf("Landroid/graphics/Canvas;", CHAR_SEQUENCE, "I", "I", "F", "I", "I", "I", "Landroid/graphics/Paint;")
internal const val DRAW_CHARACTERS = "Landroid/graphics/Canvas;->drawText([CIIFFLandroid/graphics/Paint;)V"
private val TYPEFACE_READS = setOf(
    "Landroid/graphics/Paint;->getTypeface()Landroid/graphics/Typeface;",
    "Landroid/text/TextPaint;->getTypeface()Landroid/graphics/Typeface;",
)

/**
 * Every emoji draws in Google's style while the switch is on, or from an emoji pack or font file
 * chosen in settings (#166). Included in the default selection with its switch off and the phone's
 * emoji chosen.
 *
 * Instagram draws emoji through AndroidX EmojiCompat, which loads Google's emoji font from Google
 * Play services. Its config was built to replace only the emoji the phone's own font lacks, and
 * each piece of text goes through EmojiCompat's process with a replace strategy that falls back on
 * that config. The patch has process ask the extension for the strategy first, and with the switch
 * on or a pack chosen it answers replace-all, so EmojiCompat swaps every emoji it knows for its span.
 * Where the span is about to draw from Google's font, the patch hands it to the extension, which
 * draws the emoji from the chosen pack instead and skips the rest, or leaves it be.
 */
@Suppress("unused")
val emojiStylePatch = bytecodePatch(
    name = "Emoji style",
    description = "Shows every emoji in Google's style instead of your phone's own, or in a free emoji pack " +
        "(Twemoji, OpenMoji, Blobmoji) or a color emoji font file you pick. The full change shows after you " +
        "restart Instagram. Starts off. Turn it on in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("emojiStyle")
        styleEmoji()
        enableStatus("emojiStyle")
    }
}

/** Process asks for the strategy, and the span draws through the extension. Both are found and checked before either changes. */
internal fun BytecodePatchContext.styleEmoji() {
    val draw = findEmojiSpanDraw(findEmojiProcess().method.definingClass)
    askReplaceStrategy()
    drawEmojiSpans(draw)
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** EmojiCompat's process, and the `p` register of the replace strategy it's handed. */
internal class EmojiProcess(val method: MutableMethod, val strategy: String)

/**
 * Finds EmojiCompat's process and checks it before anything changes: the one method holding
 * [RANGE_CHECK] and [LENGTH_CHECK], an instance method taking the text, its start, its end and the
 * replace strategy and answering the text. The build folded the most emoji to replace into a
 * constant, so a fourth number means a build this wasn't written for. The start and the end are
 * the numbers process checks for being negative. The strategy is the one it doesn't, and process
 * compares it, or a copy of it, for equality with a strategy. Nothing jumps to process's start.
 */
internal fun BytecodePatchContext.findEmojiProcess(): EmojiProcess {
    // Only classes holding both strings are read, which the patcher's index names. Reading every method
    // in the app was a measured share of patching (#173).
    val found = classesHolding(RANGE_CHECK, LENGTH_CHECK).flatMap { it.methods }.filter { it.holds(RANGE_CHECK) && it.holds(LENGTH_CHECK) }
    val process = found.singleOrNull()
        ?: refuse("expected one method holding \"$RANGE_CHECK\" and \"$LENGTH_CHECK\", found ${found.size}")
    val where = "${process.definingClass}->${process.name}"
    val parameters = process.parameterTypes.map(Any::toString)
    if (AccessFlags.STATIC.isSet(process.accessFlags) || process.returnType != CHAR_SEQUENCE ||
        parameters != listOf(CHAR_SEQUENCE, "I", "I", "I")
    ) {
        refuse("$where, EmojiCompat's process, doesn't take the text, its range and a strategy")
    }
    val code = process.implementation?.instructions?.toList().orEmpty()
    val numbers = (1..3).associateWith { process.parameterRegisterNumber(it) }
    val tested = code.filter { it.opcode == Opcode.IF_LTZ }.map { (it as OneRegisterInstruction).registerA }.toSet()
    val untested = numbers.filterValues { it !in tested }
    val strategy = untested.keys.singleOrNull()
        ?: refuse("$where checks ${3 - untested.size} of its numbers for being negative, not the start and the end")
    val register = numbers.getValue(strategy)
    val copies = code.filter { it.opcode in MOVES && (it as TwoRegisterInstruction).registerB == register }
        .map { (it as OneRegisterInstruction).registerA }.toSet() + register
    val compared = code.any { instruction ->
        instruction.opcode in EQUALITY && (instruction as TwoRegisterInstruction).let { it.registerA in copies || it.registerB in copies }
    }
    if (!compared) refuse("$where never compares its strategy, parameter $strategy, with another")
    if (0 in process.jumpTargets()) refuse("a jump lands on the start of $where")
    if (classDefByOrNull(EMOJI_STYLE)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == REPLACE_STRATEGY &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $REPLACE_STRATEGY")
    }
    val method = mutableClassDefBy(process.definingClass).methods.single {
        it.name == process.name && it.parameterTypes.map(Any::toString) == parameters && it.returnType == process.returnType
    }
    return EmojiProcess(method, process.parameterRegister(strategy))
}

/**
 * First thing in process, hands the strategy Instagram asked for to the extension and keeps the
 * one it answers. The range form names a register past v15, and move-result can write it.
 */
internal fun BytecodePatchContext.askReplaceStrategy() {
    val found = findEmojiProcess()
    found.method.addInstructions(
        0,
        """
            invoke-static/range { ${found.strategy} .. ${found.strategy} }, $REPLACE_STRATEGY
            move-result ${found.strategy}
        """,
    )
}

/** EmojiCompat's span's draw, the typeface read the hook goes in front of, and the paint it draws with there. */
internal class EmojiSpanDraw(val method: MutableMethod, val at: Int, val paint: Int)

/**
 * Finds the draw of EmojiCompat's span, [compat] being EmojiCompat, and checks it before anything
 * changes. EmojiCompat's get is its one static method taking nothing and answering it. The draw is
 * the one instance `draw` taking what ReplacementSpan's does that asks get (for its debug outline)
 * and draws characters ([DRAW_CHARACTERS]). It reads its paint's typeface once, to put it back after
 * drawing in EmojiCompat's font, and the hook goes in front of that read, which nothing jumps to.
 * From there to its return the code runs straight, no try block covers it, it draws the characters
 * and never names the paint parameter, which the hook takes over. `this` and every other parameter
 * still hold what draw was handed when the hook reads them.
 */
internal fun BytecodePatchContext.findEmojiSpanDraw(compat: String): EmojiSpanDraw {
    val gets = classDefBy(compat).methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == compat
    }
    val get = gets.singleOrNull() ?: refuse("expected one static method of $compat answering it, EmojiCompat's get, found ${gets.size}")
    val asked = "$compat->${get.name}()$compat"
    val found = classesCalling(compat, get.name).flatMap { it.methods }.filter {
        it.name == "draw" && !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == SPAN_DRAW && it.calls(asked) && it.calls(DRAW_CHARACTERS)
    }
    val draw = found.singleOrNull()
        ?: refuse("expected one span draw asking $asked and drawing characters, found ${found.size}")
    val where = "${draw.definingClass}->draw"
    val code = draw.implementation?.instructions?.toList().orEmpty()
    val reads = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() in TYPEFACE_READS }
    val at = reads.singleOrNull() ?: refuse("$where reads a paint's typeface ${reads.size} times, not once")
    val paint = code[at].namedRegisters().first()
    if (at in draw.jumpTargets()) refuse("a jump lands where $where reads its paint's typeface")
    val end = (at until code.size).firstOrNull { code[it].opcode == Opcode.RETURN_VOID }
        ?: refuse("$where never returns after it reads its paint's typeface")
    val rest = code.subList(at, end)
    if (rest.any { it is OffsetInstruction || !it.opcode.canContinue() }) {
        refuse("$where doesn't run straight from its typeface read to its return")
    }
    if (rest.none { (it as? ReferenceInstruction)?.reference?.toString() == DRAW_CHARACTERS }) {
        refuse("$where doesn't draw the characters after it reads its paint's typeface")
    }
    val flow = ControlFlow.of(draw)
    if ((at..end).any { flow.exceptional[it].isNotEmpty() }) refuse("a try block covers where $where draws")
    val taken = draw.parameterRegisterNumber(SPAN_DRAW.lastIndex)
    if (taken > 255) refuse("$where keeps its paint in v$taken, past what the hook can write")
    if (rest.any { taken in it.namedRegisters() }) refuse("$where uses its paint parameter, v$taken, after the typeface read")
    draw.requireThisIntact(PATCH, listOf(at))
    for (parameter in 0 until SPAN_DRAW.lastIndex) draw.requireParameterIntact(PATCH, parameter, listOf(at))
    if (classDefByOrNull(EMOJI_FONTS)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SPAN_DRAWN &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $SPAN_DRAWN")
    }
    val method = mutableClassDefBy(draw.definingClass).methods.single {
        it.name == draw.name && it.parameterTypes.map(Any::toString) == SPAN_DRAW && it.returnType == "V"
    }
    return EmojiSpanDraw(method, at, paint)
}

/**
 * In front of the span's typeface read, hands the span, what its draw was handed and the paint it
 * draws with to the extension, in the paint parameter's place, and returns when the extension drew
 * the emoji. Otherwise the span draws as before; the answer left in the paint parameter is never read.
 */
internal fun drawEmojiSpans(found: EmojiSpanDraw) {
    val method = found.method
    val last = method.parameterRegister(SPAN_DRAW.lastIndex)
    method.addInstructionsWithLabels(
        found.at,
        """
            move-object/from16 $last, v${found.paint}
            invoke-static/range { p0 .. $last }, $SPAN_DRAWN
            move-result $last
            if-eqz $last, :keep
            return-void
        """,
        ExternalLabel("keep", method.getInstruction(found.at)),
    )
}

private fun Method.calls(reference: String): Boolean = implementation?.instructions?.any {
    (it as? ReferenceInstruction)?.reference?.toString() == reference
} == true

private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)
private val EQUALITY = setOf(Opcode.IF_EQ, Opcode.IF_NE)

private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
    (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((it as ReferenceInstruction).reference as StringReference).string == string
} == true
