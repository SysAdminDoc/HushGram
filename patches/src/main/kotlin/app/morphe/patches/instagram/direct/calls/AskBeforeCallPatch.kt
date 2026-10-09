/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.calls

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val CALL_PATCH = "Ask before a call"

internal const val CALL_CONFIRM = "$EXTENSION_PACKAGE/direct/CallConfirm;"
internal const val HOLD_CALL =
    "$CALL_CONFIRM->hold(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)Z"
internal const val START_CALL_STUB = "startCall"
internal const val CONTEXT_OF_STUB = "contextOf"

/** The co-watch arguments a call can start with, a class Instagram keeps by name. */
internal const val CO_WATCH = "Lcom/instagram/model/rtc/cowatch/RtcStartCoWatchPlaybackArguments;"

/** What the chat's call delegate logs when it's asked to start a call after the chat has gone. */
internal const val CALL_DELEGATE_TEXT = "DirectStartCallDelegate.startCall called while in cleared state. Entry point: %s"

internal const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
internal const val GET_CONTEXT = "$FRAGMENT->getContext()Landroid/content/Context;"
private const val OBJECT = "Ljava/lang/Object;"

/** The chat's call start: an instance method taking the chat, the entry point, the co-watch arguments and whether it's video. */
internal object ChatCallStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L", CO_WATCH, "Z"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * A call started from a chat waits for a question while the switch is on. Included in the default
 * selection with its switch off, so asking is the user's pick.
 */
@Suppress("unused")
val askBeforeCallPatch = bytecodePatch(
    name = "Ask before a call",
    description = "Asks you to confirm before a call starts from a chat, so a stray tap on the call button " +
        "doesn't ring anyone. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("askBeforeCall")
        askBeforeCall()
        enableStatus("askBeforeCall")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$CALL_PATCH: $why")

/** The chat's call start, the field holding its chat screen, and the extension's two stubs. */
internal class ChatCallTargets(
    val start: MutableMethod,
    val screen: FieldReference,
    val startCall: MutableMethod,
    val contextOf: MutableMethod,
)

/**
 * Finds the chat's call start and checks it before anything changes: it's the one method of its
 * shape, its class keeps the chat's call delegate (the class that logs [CALL_DELEGATE_TEXT]), it
 * has a local for the hook's answer and nothing jumps to its first instruction, and it reads its
 * chat screen from one field of its own class right before asking that screen for its context.
 */
internal fun BytecodePatchContext.findChatCall(): ChatCallTargets {
    val start = uniqueMethod(CALL_PATCH, "chat call start", ChatCallStartFingerprint)
    val starter = start.definingClass
    val delegates = classesHolding(CALL_DELEGATE_TEXT).map { it.type }.toSet()
    if (classDefBy(starter).fields.none { it.type in delegates }) {
        refuse("$starter keeps no call delegate logging \"$CALL_DELEGATE_TEXT\"")
    }
    if (start.localRegisterCount() < 1) refuse("$starter->${start.name} has no local for the answer")
    if (0 in start.jumpTargets()) refuse("a jump or exception handler enters $starter->${start.name} at its first instruction")

    val code = start.implementation!!.instructions.toList()
    val screens = code.indices.mapNotNull { at ->
        val read = code[at]
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
        val next = code.getOrNull(at + 1)
        val call = (next as? ReferenceInstruction)?.reference as? MethodReference
        if (read.opcode == Opcode.IGET_OBJECT && field?.definingClass == starter &&
            next?.opcode == Opcode.INVOKE_VIRTUAL && call?.toString() == GET_CONTEXT &&
            (next as FiveRegisterInstruction).registerC == (read as TwoRegisterInstruction).registerA
        ) field else null
    }.distinct()
    val screen = screens.singleOrNull()
        ?: refuse("expected one chat screen read right before $GET_CONTEXT in $starter->${start.name}, found ${screens.size}")

    val extension = mutableClassDefBy(CALL_CONFIRM)
    if (extension.methods.none { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == HOLD_CALL &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
    ) refuse("the extension has no public static $HOLD_CALL")
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: refuse("$CALL_CONFIRM has no static $returns $name(${parameters.joinToString("")})")
    return ChatCallTargets(
        start = start,
        screen = screen,
        startCall = stub(START_CALL_STUB, listOf(OBJECT, OBJECT, OBJECT, OBJECT, "Z"), "V"),
        contextOf = stub(CONTEXT_OF_STUB, listOf(OBJECT), OBJECT),
    )
}

/**
 * Fills the stubs with Instagram's own call start and the chat screen's context, then asks the
 * extension first in the call start, with its own arguments. A yes returns before Instagram does
 * anything.
 */
internal fun BytecodePatchContext.askBeforeCall() {
    val found = findChatCall()
    val start = found.start
    val starter = start.definingClass
    val parameters = start.parameterTypes.map(Any::toString)
    found.startCall.addInstructions(
        0,
        """
            check-cast p0, $starter
            check-cast p1, ${parameters[0]}
            check-cast p2, ${parameters[1]}
            check-cast p3, ${parameters[2]}
            invoke-virtual { p0, p1, p2, p3, p4 }, $starter->${start.name}(${parameters.joinToString("")})V
            return-void
        """,
    )
    found.contextOf.addInstructions(
        0,
        """
            check-cast p0, $starter
            iget-object p0, p0, ${found.screen}
            invoke-virtual { p0 }, $GET_CONTEXT
            move-result-object p0
            return-object p0
        """,
    )
    start.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p4 }, $HOLD_CALL
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", start.getInstruction(0)),
    )
}
