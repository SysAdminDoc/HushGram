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
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
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

internal const val CALL_BUTTONS = "$EXTENSION_PACKAGE/direct/CallButtons;"
internal const val BOUND = "$CALL_BUTTONS->bound(Landroid/view/View;Ljava/lang/Object;)V"
internal const val KIND_OF_STUB = "kindOf"

private const val VIEW = "Landroid/view/View;"
private const val ENUM = "Ljava/lang/Enum;"
private const val PILL = "Lcom/instagram/ui/widget/pulsingbutton/PulsingPillButton;"

/** The names two of the chat header's button kinds carry, which only the enum of those kinds loads. */
private val HEADER_KINDS = listOf("VIDEO_CALL", "AUDIO_CALL")

/**
 * A call started from a chat waits for a question while the switch is on, and the call buttons at
 * the top of a chat can be taken off with the second. Included in the default selection with both
 * switches off, so each is the user's pick.
 */
@Suppress("unused")
val askBeforeCallPatch = bytecodePatch(
    name = "Ask before a call",
    description = "Asks you to confirm before a call starts from a chat, so a stray tap on the call button " +
        "doesn't ring anyone, and can take the call buttons off the top of a chat. Both start off. Turn them on in " +
        "HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("askBeforeCall")
        // Both finders refuse before either switch changes any code.
        val chatCall = findChatCall()
        val callButtons = findCallButtons()
        askBeforeCall(chatCall)
        hideCallButtons(callButtons)
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
internal fun BytecodePatchContext.askBeforeCall(found: ChatCallTargets = findChatCall()) {
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

/** The chat header's two button binders, the model they read, its kind field, and the extension's stub. */
internal class CallButtonTargets(
    val pill: MutableMethod,
    val icon: MutableMethod,
    val model: String,
    val kind: FieldReference,
    val kindOf: MutableMethod,
)

/**
 * Finds the two methods of the chat header's binder that fill a button view from its model, before
 * anything changes. The pill one is the only non-static method taking a view and a model that sets
 * a [PILL]'s text and icon. The icon one is the only method of its class taking that view, the same
 * model and a color. The model's kind is the one field of it holding an enum, which has to be the
 * enum that loads both [HEADER_KINDS] names. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findCallButtons(): CallButtonTargets {
    val binders = classesCalling(PILL, "setButtonText").flatMap { holder ->
        holder.methods.filter { method ->
            method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.size == 2 && method.parameterTypes[0] == VIEW &&
                method.parameterTypes[1].startsWith("L") &&
                listOf("setButtonText", "setButtonResource").all { name ->
                    method.implementation?.instructions?.any { instruction ->
                        (instruction as? ReferenceInstruction)?.reference.let { ref ->
                            ref is MethodReference && ref.definingClass == PILL && ref.name == name
                        }
                    } == true
                }
        }
    }
    val pillBinder = binders.singleOrNull()
        ?: refuse("expected one pill button binder taking a view and a model, found ${binders.size}")
    val holder = pillBinder.definingClass
    val model = pillBinder.parameterTypes[1].toString()

    val kindFields = pillBinder.implementation!!.instructions.mapNotNull { instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        field?.takeIf { instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == model && !it.type.startsWith("Ljava/") && classDefBy(it.type).superclass == ENUM }
    }.distinct()
    val kind = kindFields.singleOrNull()
        ?: refuse("expected one enum field of $model read by $holder->${pillBinder.name}, found ${kindFields.size}")
    for (name in HEADER_KINDS) {
        if (classesLoadingString(name).none { it.type == kind.type }) refuse("${kind.type} does not name $name")
    }

    val icons = classDefBy(holder).methods.filter { method ->
        method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(CharSequence::toString) == listOf(VIEW, model, "I") &&
            method.implementation?.instructions?.any { instruction ->
                (instruction as? ReferenceInstruction)?.reference.let { it is FieldReference && it == kind }
            } == true
    }
    val iconBinder = icons.singleOrNull()
        ?: refuse("expected one icon button binder in $holder taking a view, $model and a color, found ${icons.size}")

    val extension = mutableClassDefBy(CALL_BUTTONS)
    if (extension.methods.none { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == BOUND &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
    ) refuse("the extension has no public static $BOUND")
    val kindOf = extension.methods.singleOrNull {
        it.name == KIND_OF_STUB && it.returnType == "Ljava/lang/String;" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(OBJECT)
    } ?: refuse("$CALL_BUTTONS has no static String $KIND_OF_STUB(Object)")

    return CallButtonTargets(
        pill = mutableClassDefBy(holder).methods.single { it.name == pillBinder.name && it.parameterTypes.map(CharSequence::toString) == pillBinder.parameterTypes.map(CharSequence::toString) },
        icon = mutableClassDefBy(holder).methods.single { it.name == iconBinder.name && it.parameterTypes.map(CharSequence::toString) == iconBinder.parameterTypes.map(CharSequence::toString) },
        model = model,
        kind = kind,
        kindOf = kindOf,
    )
}

/**
 * Fills the extension's kind stub with a read of the model's kind name (null when the model has no
 * kind), then hands the view and the model to [BOUND] first thing in both binders. Each path returns
 * on its own: one return after the branch would merge the enum and the String into an Object, and
 * ART rejects the whole class for returning that from a String method.
 */
internal fun BytecodePatchContext.hideCallButtons(found: CallButtonTargets = findCallButtons()) {
    found.kindOf.addInstructions(
        0,
        """
            check-cast p0, ${found.model}
            iget-object p0, p0, ${found.kind}
            if-eqz p0, :none
            invoke-virtual { p0 }, $ENUM->name()Ljava/lang/String;
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
    for (binder in listOf(found.pill, found.icon)) {
        binder.addInstructions(0, "invoke-static/range { p1 .. p2 }, $BOUND")
    }
}
