/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.misc.translatedstart

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.handleTargets
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.TargetCoverage
import app.morphe.patches.instagram.misc.extension.writeTargetCoverage
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal const val TRANSLATED_START_NAME = "Start on x86 devices"

internal const val PROTECT_CODE = "$EXTENSION_PACKAGE/misc/TranslatedStart;->protectCode()I"

/** Instagram's code protection step. Native, so its class and name are kept in every build. */
internal const val MPROTECT_EXEC_CODE = "Lcom/facebook/common/dextricks/RuntimeInternals;->mprotectExecCode()V"

@Suppress("unused")
val translatedStartPatch = bytecodePatch(
    name = "Start on x86 devices",
    description = "Keeps Instagram from crashing or freezing on an x86 device that runs its arm code through a " +
        "translator, such as an x86 Chromebook or an emulator, by skipping the one code protection step that breaks " +
        "there. Phones and tablets with arm chips run it as before. It works as soon as you patch, with no switch.",
    default = true,
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("translatedStart")
        guardCodeProtection { writeTargetCoverage("translatedStart", it) }
        enableStatus("translatedStart")
    }
}

/**
 * Puts [PROTECT_CODE] in front of every call Instagram makes to [MPROTECT_EXEC_CODE], and skips the
 * call when it answers 0. On 449 that's the idle task named "mprotect", which Instagram queues on
 * its first trip to the foreground, and one other caller. Instagram's own classes only: the
 * extension never calls it. A build that never calls it stops the patch; a call that can't be
 * guarded is named in the patch log, and the rest are still guarded.
 *
 * @return how many calls were guarded
 */
internal fun BytecodePatchContext.guardCodeProtection(coverage: (TargetCoverage) -> Unit = {}): Int {
    val callers = mutableListOf<Method>()
    classesCalling(MPROTECT_EXEC_CODE.substringBefore("->"), MPROTECT_EXEC_CODE.substringAfter("->").substringBefore('(')).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.implementation?.instructions?.any { it.callsCodeProtection() } == true) callers += method
        }
    }
    if (callers.isEmpty()) throw PatchException("$TRANSLATED_START_NAME: this Instagram build never calls $MPROTECT_EXEC_CODE")
    // Last call first in each method, so the code put in front of one doesn't move those still to guard.
    val calls = callers.flatMap { found ->
        val method = mutableClassDefBy(found.definingClass).methods.single {
            it.name == found.name && it.returnType == found.returnType &&
                it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString)
        }
        method.implementation!!.instructions.withIndex()
            .filter { it.value.callsCodeProtection() }
            .map { method to it.index }
            .reversed()
    }
    return handleTargets(TRANSLATED_START_NAME, "calls to the code protection step", calls.withIndex().toList(),
        label = { "code protection call ${it.index + 1}" }, coverage = coverage) { (_, target) ->
        val (method, call) = target
        try {
            method.skipUnlessProtected(call)
            null
        } catch (failure: PatchException) {
            failure.message ?: "${method.definingClass}->${method.name} couldn't be guarded at instruction $call"
        }
    }
}

/**
 * Asks [PROTECT_CODE] in front of the call at [call], and jumps over the call on a 0. The answer goes
 * in a local nothing reads after the call. The code takes the call's own label, so a branch that
 * reached the call asks first too.
 */
internal fun MutableMethod.skipUnlessProtected(call: Int) {
    val after = call + 1
    if (after >= implementation!!.instructions.size) {
        throw PatchException("$TRANSLATED_START_NAME: $definingClass->$name ends on its call to $MPROTECT_EXEC_CODE")
    }
    val answer = freeLocalsAt(TRANSLATED_START_NAME, call, 1, targets = listOf(after), highest = 255).single()
    addInstructionsAtControlFlowLabel(
        call,
        """
            invoke-static { }, $PROTECT_CODE
            move-result v$answer
            if-eqz v$answer, :skip
        """,
        ExternalLabel("skip", getInstruction(after)),
    )
}

private fun Instruction.callsCodeProtection(): Boolean =
    (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
        (this as ReferenceInstruction).reference.toString() == MPROTECT_EXEC_CODE
