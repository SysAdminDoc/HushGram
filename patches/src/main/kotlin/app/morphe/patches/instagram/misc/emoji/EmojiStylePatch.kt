/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.emoji

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Emoji style"

internal const val EMOJI_STYLE = "$EXTENSION_PACKAGE/misc/EmojiStyle;"
internal const val REPLACE_STRATEGY = "$EMOJI_STYLE->replaceStrategy(I)I"

/** Two of EmojiCompat's checks of the range process is given, which no other method holds. */
internal const val RANGE_CHECK = "start should be <= than end"
internal const val LENGTH_CHECK = "end should be < than charSequence length"

private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"

/**
 * Every emoji draws in Google's style while the switch is on. Included in the default selection
 * with its switch off.
 *
 * Instagram draws emoji through AndroidX EmojiCompat, which loads Google's emoji font from Google
 * Play services. Its config was built to replace only the emoji the phone's own font lacks, and
 * each piece of text goes through EmojiCompat's process with a replace strategy that falls back on
 * that config. The patch has process ask the extension for the strategy first, and with the switch
 * on it answers replace-all, so EmojiCompat draws every emoji it knows from Google's font.
 */
@Suppress("unused")
val emojiStylePatch = bytecodePatch(
    name = "Emoji style",
    description = "Draws every emoji in Google's style, from the emoji font Instagram gets through Google Play services, " +
        "instead of your phone's own style. Its switch, under Layout, starts off, and a change shows fully after " +
        "Instagram restarts.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("emojiStyle")
        askReplaceStrategy()
        enableStatus("emojiStyle")
    }
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
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { if (it.holds(RANGE_CHECK) && it.holds(LENGTH_CHECK)) found += it }
    }
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

private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)
private val EQUALITY = setOf(Opcode.IF_EQ, Opcode.IF_NE)

private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
    (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((it as ReferenceInstruction).reference as StringReference).string == string
} == true
