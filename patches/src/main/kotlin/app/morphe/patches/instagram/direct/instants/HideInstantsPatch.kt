/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

private const val PATCH = "Hide Instants"
internal const val HIDE_INSTANTS = "$EXTENSION_PACKAGE/direct/Instants;->hide()Z"

/** The two rollout flags Instagram 450's Instants check reads. Either one gives the account Instants. */
internal const val INSTANTS_FLAG = 0x8105bb00001ac7L
internal const val INSTANTS_SECOND_FLAG = 0x810cd8000047ddL

/** The check's shape: static, (UserSession, boolean that logs an exposure), answering a boolean. */
internal val GATE_PARAMETERS = listOf("Lcom/instagram/common/session/UserSession;", "Z")

@Suppress("unused")
val hideInstantsPatch = bytecodePatch(
    name = "Hide Instants",
    description = "Takes the stack of Instants out of your messages. Instagram is told your account doesn't " +
        "have Instants, its no-edit camera for friends.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("instants")
        holdInstantsGate(findInstantsGate())
        enableStatus("instants")
    }
}

/** Where Instagram's Instants check lives: its class and method name. */
internal class InstantsGate(val type: String, val name: String)

/**
 * Instagram 450 asks one static method whether the account has Instants, and every entry point
 * (the stack in your messages, the camera's Instants mode, the archive, the Instants feed itself)
 * goes by its answer. It's the one `(UserSession, boolean)` method answering a boolean that loads
 * both [INSTANTS_FLAG] and [INSTANTS_SECOND_FLAG]; anything else fails the patch.
 */
internal fun BytecodePatchContext.findInstantsGate(): InstantsGate {
    val gates = classesLoading(INSTANTS_FLAG).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
                method.parameterTypes.map(CharSequence::toString) == GATE_PARAMETERS &&
                method.wides().containsAll(listOf(INSTANTS_FLAG, INSTANTS_SECOND_FLAG))
        }.map { InstantsGate(classDef.type, it.name) }
    }
    return gates.singleOrNull() ?: throw PatchException("$PATCH: ${gates.size} Instants checks, not one")
}

/**
 * Makes the check answer no from its first instruction while Hide Instants is on, the answer an
 * account without Instants gets. The switch is read on every ask, so off and Pause take Instagram's
 * own path. The method has no live local at its start, so v0 is free.
 */
internal fun BytecodePatchContext.holdInstantsGate(gate: InstantsGate) {
    val method: MutableMethod = mutableClassDefBy(gate.type).methods.single {
        it.name == gate.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == GATE_PARAMETERS
    }
    method.requireLocals(PATCH, 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_INSTANTS
            move-result v0
            if-eqz v0, :ask
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("ask", method.getInstruction(0)),
    )
}

private fun Method.wides(): List<Long> =
    implementation?.instructions?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }.orEmpty()
