/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** What the privacy patches ask, see Ghost in the extension. */
internal const val GHOST = "$EXTENSION_PACKAGE/privacy/Ghost;"
internal const val HOLD_CHAT_SEEN = "$GHOST->holdChatSeen()Z"
internal const val HOLD_TYPING = "$GHOST->holdTyping()Z"
internal const val HOLD_SCREENSHOTS = "$GHOST->holdScreenshots()Z"
internal const val GATE = "$GHOST->gate(Ljava/net/URI;)V"

internal fun refuse(patch: String, detail: String): Nothing = throw PatchException("$patch: $detail")

internal fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

internal fun Method.describe(): String = "$definingClass->$name"

internal fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

internal fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

internal fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Every method outside the extension that loads all of [strings], in the app's order. */
internal fun BytecodePatchContext.methodsHolding(vararg strings: String): List<Method> =
    classesHolding(*strings).flatMap { classDef ->
        classDef.methods.filter { method ->
            val held = method.code().mapNotNull { it.stringLoaded() }.toSet()
            strings.all { it in held }
        }
    }

/** The one method [candidates] holds, or a refusal naming what wasn't found exactly once. */
internal fun <T> List<T>.exactlyOne(patch: String, what: String): T =
    singleOrNull() ?: refuse(patch, "expected one $what, found $size")

internal fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

/**
 * Makes the method return at once while the [hook] answers yes. The hook is the first thing it does,
 * so v0, a local nothing has written yet, takes the answer; on a no the method goes on with its own
 * first instruction. Refuses a method with no local to borrow, and one something jumps to the start
 * of, which the guard would be skipped by.
 */
internal fun MutableMethod.returnVoidWhen(patch: String, hook: String) {
    if (returnType != "V") refuse(patch, "${describe()} doesn't return void")
    requireLocals(patch, 1)
    if (0 in jumpTargets()) refuse(patch, "something jumps to the start of ${describe()}")
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", getInstruction(0)),
    )
}
