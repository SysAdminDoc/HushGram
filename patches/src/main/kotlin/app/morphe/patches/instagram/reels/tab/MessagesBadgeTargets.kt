/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide the Reels tab"

/**
 * The lambda a tab button makes as it puts a count on its badge. Kotlin named it, so it keeps its
 * name on Redex builds, and only the tab button's count update makes one.
 */
internal const val BADGE_UPDATE = "Lcom/instagram/mainactivity/maintab/ui/DefaultMainTab\$handleUpdatingBadge\$1;"

/** What the Messages tab's badge controller keeps in place of the unread count, see MessagesBadge.count. */
internal const val BADGE_COUNT = "$EXTENSION_PACKAGE/reels/MessagesBadge;->count(I)I"

/** The parameters of the tab button's count update: the count, and a time for its animation. */
private val UPDATE_PARAMETERS = listOf("I", "Ljava/lang/Long;")

/**
 * Where the Messages tab's badge controller keeps the unread count, for Hide the Messages badge:
 * [controller], its method taking a count, [store] the index of the int field write keeping it, and
 * [count] the register holding it. The controller's own test of that register follows the write.
 */
internal class MessagesBadgeHook(val controller: Method, val store: Int, val count: Int)

/**
 * Finds where the Messages tab's badge count is kept, failing before anything changes when any of
 * it isn't there exactly once:
 *
 *  - the tab button's count update is the one instance `(int, Long)V` method making [BADGE_UPDATE];
 *  - the badge controller is the one method calling it, with the count as the call's first argument
 *    (on 456 `01uD.A03`, which only the Messages tab's badge feeds, since the other tabs show a dot);
 *  - before that call the controller writes the count to its own int field and tests it with
 *    `if-lez`, calling the update above zero and hiding the badge otherwise. The write is where the
 *    hook goes, so the field, the test and the call all see the answer.
 */
internal fun BytecodePatchContext.findMessagesBadge(): MessagesBadgeHook {
    val updates = classesCreating(BADGE_UPDATE).flatMap { it.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == UPDATE_PARAMETERS &&
            method.code().any { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).typeName() == BADGE_UPDATE }
    }
    val update = updates.singleOrNull()
        ?: refuse("expected one tab button method making $BADGE_UPDATE from a count, found ${updates.size}")
    val calls = classesCalling(update.definingClass, update.name, UPDATE_PARAMETERS, "V").flatMap { it.methods }
        .flatMap { method -> method.code().withIndex().filter { it.value.calls(update) }.map { method to it.index } }
    val (controller, call) = calls.singleOrNull()
        ?: refuse("expected one call to ${update.describe()}, the Messages tab's badge update, found ${calls.size}")
    val code = controller.code()
    val count = when (val invoke = code[call]) {
        is FiveRegisterInstruction -> invoke.registerD
        is RegisterRangeInstruction -> invoke.startRegister + 1
        else -> refuse("the call to ${update.describe()} in ${controller.describe()} isn't an invoke")
    }
    val tests = (0 until call).filter { code[it].opcode == Opcode.IF_LEZ && (code[it] as OneRegisterInstruction).registerA == count }
    val test = tests.singleOrNull()
        ?: refuse("expected one test of the count in v$count before the badge update in ${controller.describe()}, found ${tests.size}")
    val store = test - 1
    val write = code.getOrNull(store)
    val field = (write as? ReferenceInstruction)?.reference as? FieldReference
    if (write?.opcode != Opcode.IPUT || field == null || field.definingClass != controller.definingClass || field.type != "I" ||
        (write as TwoRegisterInstruction).registerA != count || write.registerB != controller.localRegisterCount()
    ) {
        refuse("${controller.describe()} doesn't keep the count in v$count in its own int field right before testing it")
    }
    // The hook goes in front of the write, so a jump straight to the test would pass it by.
    if (test in controller.jumpTargets()) {
        refuse("something jumps to the test of the badge count in ${controller.describe()}, past where the hook would go")
    }
    // move-result names its register in eight bits.
    if (count > 255) refuse("${controller.describe()} keeps the badge count in v$count, past v255")
    return MessagesBadgeHook(controller, store, count)
}

/**
 * Hands the count to [BADGE_COUNT] right before the controller keeps it, and keeps the answer. The
 * write can be a jump target (on 456 the path that reads the count from the badge store jumps
 * there), so the hook takes the write's label and every way in goes through it. [findMessagesBadge]
 * already made sure nothing jumps to the test itself, past the hook.
 */
internal fun BytecodePatchContext.hideMessagesBadge(hook: MessagesBadgeHook) {
    val controller = mutableClassDefBy(hook.controller.definingClass).methods.single {
        it.name == hook.controller.name && it.returnType == hook.controller.returnType &&
            it.parameterTypes.map(CharSequence::toString) == hook.controller.parameterTypes.map(CharSequence::toString)
    }
    controller.addInstructionsAtControlFlowLabel(
        hook.store,
        """
            invoke-static/range { v${hook.count} .. v${hook.count} }, $BADGE_COUNT
            move-result v${hook.count}
        """,
    )
}

private fun Instruction.calls(method: Method): Boolean {
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
        called.definingClass == method.definingClass && called.name == method.name && called.returnType == method.returnType &&
        called.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
}

private fun ReferenceInstruction.typeName(): String? = (reference as? TypeReference)?.type

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.describe(): String = "$definingClass->$name"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
