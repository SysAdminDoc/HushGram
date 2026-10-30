/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.emojirow

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val COMMENT_EMOJI_ROW_STATUS = "commentEmojiRow"

private const val PATCH = "Hide the comment emoji row"

internal const val HIDES_EMOJI_ROW = "$EXTENSION_PACKAGE/feed/CommentEmojiRow;->hidesRow(I)Z"

/**
 * Parts the comment sheet draws above its box, by the keys Instagram gives them. Only the
 * component that lays out that area (`LX/0Fas.A0o` on 451's 385713073) loads both.
 */
internal const val REPLYING_TO_BAR = "replying_to_bar"
internal const val GROUP_MENTION_BANNER = "group_mention_banner"

private const val IS_EMPTY = "Ljava/util/List;->isEmpty()Z"
private const val LIST = "Ljava/util/List;"

/** How far past the emoji check the strip may be made and handed its emoji. */
private const val STRIP_REACH = 24

/** The above-composer component's method, the check's answer register and the branch that skips the strip. */
internal class EmojiStripSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val branch: Int,
    val answer: Int,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds where the comment sheet decides to draw its strip of quick emoji: the one method loading
 * both [REPLYING_TO_BAR] and [GROUP_MENTION_BANNER], and in it the one List.isEmpty whose answer an
 * if-nez reads to skip the strip, with that same list then handed to a new component's List field
 * (the strip, `LX/0Exx` on 385713073) within [STRIP_REACH] instructions. Nothing may jump to the
 * branch, since the hook goes right in front of it. Fails before anything changes when any of that
 * isn't so.
 */
internal fun BytecodePatchContext.findEmojiStrip(): EmojiStripSite {
    val methods = classesLoadingString(REPLYING_TO_BAR).flatMap { classDef ->
        classDef.methods.filter { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            val loaded = code.indices.mapNotNull { stringLoadedAt(code, it) }.toSet()
            REPLYING_TO_BAR in loaded && GROUP_MENTION_BANNER in loaded
        }
    }
    val method = methods.singleOrNull()
        ?: refuse("expected one method loading \"$REPLYING_TO_BAR\" and \"$GROUP_MENTION_BANNER\", found ${methods.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()

    val checks = code.indices.filter { at ->
        val answer = code.getOrNull(at + 1)
        val branch = code.getOrNull(at + 2)
        code[at].methodText() == IS_EMPTY &&
            answer is OneRegisterInstruction && answer.opcode == Opcode.MOVE_RESULT &&
            branch is OneRegisterInstruction && branch.opcode == Opcode.IF_NEZ && branch.registerA == answer.registerA
    }.filter { at -> code.handsListToNewComponent(at + 3, code[at].listRegister()) }
    val check = checks.singleOrNull()
        ?: refuse("expected one emoji check in $where handing its list to the strip, found ${checks.size}")
    val branch = check + 2
    if (branch in method.jumpTargets()) refuse("$where jumps to its emoji check's branch")
    return EmojiStripSite(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        branch, (code[check + 1] as OneRegisterInstruction).registerA,
    )
}

/** [findEmojiStrip], or null with a warning in the patch log when the build isn't one it can read. */
internal fun BytecodePatchContext.findEmojiStripOrWarn(): EmojiStripSite? = try {
    findEmojiStrip()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without taking the strip out of the comment sheet.")
    null
}

/**
 * Hands the emoji check's answer to [HIDES_EMOJI_ROW] right before its branch, so a yes takes
 * Instagram's own way past the strip, the one it takes when there are no emoji. The "Replying to"
 * bar, the group mention banner and the box are other parts and stay.
 */
internal fun BytecodePatchContext.hideEmojiStrip(site: EmojiStripSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val code = method.implementation!!.instructions.toList()
    val branch = code.getOrNull(site.branch)
    if (branch?.opcode != Opcode.IF_NEZ || (branch as OneRegisterInstruction).registerA != site.answer ||
        code[site.branch - 2].methodText() != IS_EMPTY
    ) {
        refuse("${site.type}->${site.name} changed before its emoji check could be hooked")
    }
    method.addInstructions(
        site.branch,
        """
            invoke-static/range { v${site.answer} .. v${site.answer} }, $HIDES_EMOJI_ROW
            move-result v${site.answer}
        """,
    )
}

/**
 * Takes the row of quick emoji off above the comment box (#248). See CommentEmojiRow in the extension.
 *
 * On 451 the comment sheet draws the strip as part of the area above its box, and the hook answers
 * its emoji check as empty while the switch is on, so the strip isn't drawn at all and nothing is
 * left for a gap. The older picker some sheets inflate from a ViewStub is kept gone by the
 * extension's view hider, started by the application-start hook the settings patch already writes.
 * In the default selection with its switch off.
 */
@Suppress("unused")
val hideCommentEmojiRowPatch = bytecodePatch(
    name = "Hide the comment emoji row",
    description = "Takes the row of quick emoji off above the comment box. The box itself stays. Starts off. " +
        "Turn it on in HushGram settings > Comments.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(COMMENT_EMOJI_ROW_STATUS)
        findEmojiStripOrWarn()?.let { hideEmojiStrip(it) }
        enableStatus(COMMENT_EMOJI_ROW_STATUS)
    }
}

/** Whether, from [from], a new instance is made and [register] written into one of its List fields within [STRIP_REACH]. */
private fun List<Instruction>.handsListToNewComponent(from: Int, register: Int?): Boolean {
    if (register == null) return false
    val end = minOf(size, from + STRIP_REACH)
    val made = (from until end).filter { this[it].opcode == Opcode.NEW_INSTANCE }
    return made.any { at ->
        val component = (this[at] as OneRegisterInstruction).registerA
        (at + 1 until end).any {
            val write = this[it]
            val field = (write as? ReferenceInstruction)?.reference as? FieldReference
            write.opcode == Opcode.IPUT_OBJECT && field?.type == LIST &&
                (write as TwoRegisterInstruction).registerA == register && write.registerB == component
        }
    }
}

/** The one register an invoke-interface or invoke-virtual on a list reads, or null. */
private fun Instruction.listRegister(): Int? = when (this) {
    is FiveRegisterInstruction -> registerC.takeIf { registerCount == 1 }
    is RegisterRangeInstruction -> startRegister.takeIf { registerCount == 1 }
    else -> null
}

private fun Instruction?.methodText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
