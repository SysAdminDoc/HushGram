/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.commentkeyboard

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val COMMENT_KEYBOARD_STATUS = "commentKeyboard"

private const val PATCH = "Open comments without the keyboard"

internal const val KEEPS_KEYBOARD_DOWN = "$EXTENSION_PACKAGE/feed/CommentKeyboard;->keepsKeyboardDown()Z"

/**
 * The name of the reason the comment sheet records when it brings the keyboard up by itself. The
 * enum holding it (`LX/0GLz` on 452's 385810270) builds that constant in its static initializer.
 */
internal const val KEYBOARD_AUTO_SHOW = "KEYBOARD_AUTO_SHOW"

/** How far past the name the constant may be stored. */
private const val CONSTANT_REACH = 6

/** How far before the reason is read the block asking for the keyboard may start. */
private const val BLOCK_REACH = 8

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/**
 * The method that brings the keyboard up as the comment sheet starts, the first instruction of the
 * block that does it, where that block's goto goes, the read of the reason, and a register the hook
 * may borrow.
 */
internal class AutoShowSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val start: Int,
    val read: Int,
    val skip: Int,
    val register: Int,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The static field the enum stores its [KEYBOARD_AUTO_SHOW] constant in. */
private fun BytecodePatchContext.autoShowConstant(): FieldReference {
    val found = classesLoadingString(KEYBOARD_AUTO_SHOW).flatMap { classDef ->
        classDef.methods.filter { it.name == "<clinit>" }.mapNotNull { clinit ->
            val code = clinit.implementation?.instructions?.toList().orEmpty()
            val at = code.indices.singleOrNull { stringLoadedAt(code, it) == KEYBOARD_AUTO_SHOW } ?: return@mapNotNull null
            val store = (at + 1 until minOf(code.size, at + 1 + CONSTANT_REACH)).firstOrNull { code[it].opcode == Opcode.SPUT_OBJECT }
                ?: return@mapNotNull null
            code[store].fieldReference()?.takeIf { it.definingClass == classDef.type && it.type == classDef.type }
        }
    }
    return found.singleOrNull() ?: refuse("expected one enum constant named \"$KEYBOARD_AUTO_SHOW\", found ${found.size}")
}

/**
 * Finds where the comment sheet brings the keyboard up on its own: the one read of the
 * [KEYBOARD_AUTO_SHOW] constant, stored right away in an instance field of the same enum type and
 * followed by a goto. The block it ends starts at the nearest instruction something jumps to, at
 * most [BLOCK_REACH] before the read, and runs straight through to the goto. Each way into the
 * block (a feed post opened with an empty box, a notification, a link asking for the keyboard)
 * goes through that start. On 452's 385810270 that's LX/0FzY.A1E, the sheet's onCreate work.
 * Fails before anything changes when any of that isn't so.
 */
internal fun BytecodePatchContext.findAutoShow(): AutoShowSite {
    val constant = autoShowConstant()
    val reads = classesAccessing(constant.definingClass, constant.name, Opcode.SGET_OBJECT).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices.filter { at ->
                code[at].opcode == Opcode.SGET_OBJECT &&
                    code[at].fieldReference()?.let { it.definingClass == constant.definingClass && it.name == constant.name } == true
            }.map { method to it }
        }
    }
    val (method, read) = reads.singleOrNull()
        ?: refuse("expected one read of ${constant.definingClass}->${constant.name}, found ${reads.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()

    val store = code.getOrNull(read + 1)
    if (store?.opcode != Opcode.IPUT_OBJECT || (store as TwoRegisterInstruction).registerA != (code[read] as OneRegisterInstruction).registerA ||
        store.fieldReference()?.type != constant.definingClass
    ) {
        refuse("$where doesn't store the reason it reads")
    }
    if (code.getOrNull(read + 2)?.opcode !in GOTOS) refuse("$where doesn't leave the block after storing the reason")

    val flow = ControlFlow.of(method)
    val skip = flow.normal[read + 2].single()
    val start = method.jumpTargets().filter { it <= read }.maxOrNull()
        ?: refuse("nothing in $where jumps to the block that brings the keyboard up")
    if (read - start > BLOCK_REACH) refuse("$where's block starts ${read - start} instructions before the reason")
    if (skip in start..read + 2) refuse("$where's block goes back into itself")
    val straight = (start..read + 1).all { flow.normal[it] == listOf(it + 1) }
    if (!straight) refuse("$where's block doesn't run straight through")

    val register = method.freeLocalsAt(PATCH, start, 1, targets = listOf(skip), highest = 255).single()
    return AutoShowSite(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        start, read, skip, register,
    )
}

/** [findAutoShow], or null with a warning in the patch log when the build isn't one it can read. */
internal fun BytecodePatchContext.findAutoShowOrWarn(): AutoShowSite? = try {
    findAutoShow()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without changing how comments open.")
    null
}

/**
 * Asks [KEEPS_KEYBOARD_DOWN] at the start of the block that brings the keyboard up, on the block's
 * own label so every way into it asks, and on a yes takes the block's own goto target, the way
 * Instagram goes on when it doesn't want the keyboard. Nothing in the block runs then: no request
 * for the keyboard and no reason recorded.
 */
internal fun BytecodePatchContext.keepKeyboardDown(site: AutoShowSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val code = method.implementation!!.instructions.toList()
    if (code.getOrNull(site.read)?.opcode != Opcode.SGET_OBJECT || code.getOrNull(site.read + 2)?.opcode !in GOTOS ||
        site.start !in method.jumpTargets()
    ) {
        refuse("${site.type}->${site.name} changed before its keyboard block could be hooked")
    }
    method.addInstructionsAtControlFlowLabel(
        site.start,
        """
            invoke-static { }, $KEEPS_KEYBOARD_DOWN
            move-result v${site.register}
            if-nez v${site.register}, :keyboard_down
        """,
        ExternalLabel("keyboard_down", method.getInstruction(site.skip)),
    )
}

/**
 * Opens the comment sheet with the keyboard down (#248). See CommentKeyboard in the extension.
 *
 * Instagram decides once, as the sheet starts, whether to bring the keyboard up by itself. The hook
 * goes in front of that one block, so a tap in the box, Reply and the other ways to start typing,
 * which ask for the keyboard elsewhere, still bring it up. In the default selection with its switch
 * off.
 */
@Suppress("unused")
val commentKeyboardPatch = bytecodePatch(
    name = "Open comments without the keyboard",
    description = "Comments open with the keyboard down. A tap in the comment box, or on Reply, still brings it up. " +
        "Starts off. Turn it on in HushGram settings > Comments.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(COMMENT_KEYBOARD_STATUS)
        findAutoShowOrWarn()?.let { keepKeyboardDown(it) }
        enableStatus(COMMENT_KEYBOARD_STATUS)
    }
}

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
