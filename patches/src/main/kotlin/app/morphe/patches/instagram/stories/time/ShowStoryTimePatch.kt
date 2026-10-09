/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.time

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Show a story's exact time"
internal const val STORY_TIME = "$EXTENSION_PACKAGE/stories/StoryTime;"
internal const val TIME_LABEL = "$STORY_TIME->label(J)Ljava/lang/String;"
internal const val RELATIVE_HEADER = "$STORY_TIME->relativeHeader(I)Z"

/** A story, photo or video, as the story viewer and its headers hold it. Redex keeps its name. */
internal const val STORY_ITEM = "Lcom/instagram/model/reels/ReelItem;"

/**
 * The server flag that has a story header format the time with a second relative formatter
 * instead of asking the story item for its label. Read once on 450, in the header's builder.
 */
internal const val RELATIVE_HEADER_FLAG = 0x8114a000016bc7L

private const val STRING = "Ljava/lang/String;"

/**
 * Shows the date and time a story was posted in its header instead of how long ago, or, by the
 * extension's choice under the switch, the time left before it expires or only the time it went
 * up. In the default selection with its switch off: Instagram's "3h" is its own design, so the
 * date is the user's pick. The choice lives wholly in the extension, which gets the posted time either way.
 */
@Suppress("unused")
val showStoryTimePatch = bytecodePatch(
    name = "Show a story's exact time",
    description = "Shows the date and time a story was posted in its header, like Oct 2, 3:45 PM, instead of how " +
        "long ago. A choice under its switch can show the time left before the story expires, or only the time it " +
        "went up. It follows your phone's language and 12 or 24-hour setting. Off until you turn it on. Switch: " +
        "HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyTime")
        val sites = findStoryTime()
        showStoryTime(sites)
        enableStatus("storyTime")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The story item's one instance method taking a Context and answering its time label. */
internal object StoryTimeLabelFingerprint : Fingerprint(
    returnType = STRING,
    parameters = listOf("Landroid/content/Context;"),
    custom = { method, classDef -> classDef.type == STORY_ITEM && !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * Where the label is worked out: the label method, the index of its long-to-double, the register
 * pair holding the posted time there, the local the hook's answer may borrow, and the header's
 * read of [RELATIVE_HEADER_FLAG].
 */
internal class StoryTimeSites(
    val name: String,
    val conversion: Int,
    val seconds: Int,
    val answer: Int,
    val header: FlagLoad,
)

/**
 * Finds the story item's label method, its one long-to-double, and the formatting it feeds: the
 * very next instruction calls a method taking that double and answering a String, and the method
 * returns that String straight away. Nothing may jump to the conversion, so every way to the label
 * passes the hook. Then the one read of [RELATIVE_HEADER_FLAG], which must be its own and sit in a
 * method that also asks the story item for its label, the header's builder. Fails before anything
 * changes when any of it isn't there exactly once, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryTime(): StoryTimeSites {
    val label = uniqueMethod(PATCH, "story item's time label taking a Context", StoryTimeLabelFingerprint)
    val where = "$STORY_ITEM->${label.name}"
    val code = label.instructions()
    val conversions = code.indices.filter { code[it].opcode == Opcode.LONG_TO_DOUBLE }
    val conversion = conversions.singleOrNull()
        ?: refuse("expected $where to turn one long into a double, found ${conversions.size}")
    val double = (code[conversion] as TwoRegisterInstruction).registerA
    val seconds = (code[conversion] as TwoRegisterInstruction).registerB

    val format = code.getOrNull(conversion + 1)
    val formatter = format?.methodReference()
    if (formatter == null || formatter.returnType != STRING || "D" !in formatter.parameterTypes.map(CharSequence::toString) ||
        !format.arguments().windowed(2).contains(listOf(double, double + 1))
    ) {
        refuse("$where doesn't hand the double it makes straight to a formatter answering a String")
    }
    val result = code.getOrNull(conversion + 2)
    val returned = code.getOrNull(conversion + 3)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || returned?.opcode != Opcode.RETURN_OBJECT ||
        (result as OneRegisterInstruction).registerA != (returned as OneRegisterInstruction).registerA
    ) {
        refuse("$where doesn't return what its formatter answers")
    }
    if (conversion in label.jumpTargets()) refuse("something in $where jumps to its long-to-double")
    // A local for the hook's answer, which the move-result, the check and the return can name up to v255.
    val answer = label.freeLocalsAt(PATCH, conversion, 1, highest = 255).single()

    val reads = findFlagLoads(PATCH, RELATIVE_HEADER_FLAG, "Z")
    val header = reads.singleOrNull()
        ?: refuse("expected one read of the story header's flag ${RELATIVE_HEADER_FLAG.toString(16)}, found ${reads.size}")
    if (header.shared) refuse("the read of ${RELATIVE_HEADER_FLAG.toString(16)} in ${header.type}->${header.name} is shared with another flag")
    val builder = classDefBy(header.type).methods.single {
        it.name == header.name && it.returnType == header.returnType && it.parameterTypes.map(CharSequence::toString) == header.parameters
    }
    val asksLabel = builder.instructions().any {
        val called = it.methodReference()
        called != null && called.definingClass == STORY_ITEM && called.name == label.name &&
            called.returnType == STRING && called.parameterTypes.map(CharSequence::toString) == label.parameterTypes.map(CharSequence::toString)
    }
    if (!asksLabel) refuse("${header.type}->${header.name} reads ${RELATIVE_HEADER_FLAG.toString(16)} but never asks $where for the label")

    return StoryTimeSites(label.name, conversion, seconds, answer, header)
}

/**
 * Puts a call to [TIME_LABEL] in front of the conversion, handed the posted time: a label it
 * answers is returned there and then, and null goes on to the conversion and Instagram's own
 * formatting. Then the header's flag read is answered through [RELATIVE_HEADER].
 */
internal fun BytecodePatchContext.showStoryTime(sites: StoryTimeSites) {
    val label = mutableClassDefBy(STORY_ITEM).methods.single {
        it.name == sites.name && it.returnType == STRING && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
    }
    label.addInstructionsWithLabels(
        sites.conversion,
        """
            invoke-static/range { v${sites.seconds} .. v${sites.seconds + 1} }, $TIME_LABEL
            move-result-object v${sites.answer}
            if-eqz v${sites.answer}, :instagram_label
            return-object v${sites.answer}
        """,
        ExternalLabel("instagram_label", label.getInstruction(sites.conversion)),
    )
    answerFlagLoads(listOf(sites.header to RELATIVE_HEADER))
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke hands over, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
