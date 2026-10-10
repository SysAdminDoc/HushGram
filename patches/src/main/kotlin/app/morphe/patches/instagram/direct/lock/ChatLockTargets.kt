/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.THREAD_KEY_TEXT
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.direct.seen.visualString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val CHAT_LOCKS = "$EXTENSION_PACKAGE/direct/ChatLocks;"
internal const val CHAT_OPENED = "$CHAT_LOCKS->opened(Ljava/lang/Object;)V"
internal const val CHAT_CLOSED = "$CHAT_LOCKS->closed(Ljava/lang/Object;)V"
internal const val CHAT_TRACK = "$CHAT_LOCKS->track(Landroid/app/Notification;Landroid/app/Notification;" +
    "Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
private const val CHAT_THREAD_ID = "threadId"

/** What the chat screen logs first in onResume and onPause, as Instagram's thread controller. */
internal const val CHAT_RESUME = "DirectThreadController.onResume"
internal const val CHAT_PAUSE = "DirectThreadController.onPause"

/** The name Instagram's null check gives the chat screen's controller when it reads the field. */
internal const val CONTROLLER_NAME = "threadController"

/** What Instagram's push display logs, in the one method that shows a push's notifications. */
internal const val PUSH_DISPLAYED = "notification_displayed"
internal const val PUSH_ALERT_ONCE = "is_alert_only_once"

/** The labels a push's toString writes before the fields the lock matches a chat with. */
internal const val PUSH_ACTION = "mIgAction"
internal const val PUSH_THREAD = "mThreadId"
internal const val PUSH_THREAD_IG = "mThreadIgId"

private const val NOTIFICATION = "Landroid/app/Notification;"
private const val MAP = "Ljava/util/Map;"
private const val STRING = "Ljava/lang/String;"

/** The chat screen's onResume: the one method logging [CHAT_RESUME]. */
internal object ChatResumeFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(CHAT_RESUME),
    custom = { method, _ -> method.name == "onResume" && method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The chat screen's onPause: the one method logging [CHAT_PAUSE]. */
internal object ChatPauseFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(CHAT_PAUSE),
    custom = { method, _ -> method.name == "onPause" && method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The method that hands a push's notifications to Android: it logs both the display and the alert-once flag. */
internal object PushDisplayFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(PUSH_ALERT_ONCE, PUSH_DISPLAYED),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isNotEmpty() },
)

/** A push's toString, whose labels name the fields the push keeps its link and chat ids in. */
internal object PushLabelsFingerprint : Fingerprint(
    returnType = STRING,
    strings = listOf(PUSH_ACTION, PUSH_THREAD, PUSH_THREAD_IG),
    custom = { method, _ -> method.name == "toString" && method.parameterTypes.isEmpty() },
)

/**
 * What the chat lock needs from Instagram, all proved before anything changes: the chat screen's
 * two lifecycle methods and the body of the extension's thread id bridge, and the push display
 * with the fields its hook reads.
 */
internal class ChatLockTargets(
    val resume: MutableMethod,
    val pause: MutableMethod,
    val bridge: MutableMethod,
    val bridgeBody: String,
    val display: MutableMethod,
    val displayHook: String,
)

private fun refuse(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")

private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")

private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

private fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.signature() = "$definingClass->$name:$type"

/**
 * The chat screen's lifecycle methods and the push display, and the reads between them: the thread
 * controller the screen keeps, the call that answers the chat's key, the key's thread id, and the
 * fields of a push that carry its link and chat ids. Each is found by what Instagram writes in it,
 * so a build that moves or renames them still resolves, and one that changes their shape fails here.
 */
internal fun BytecodePatchContext.findChatLockTargets(): ChatLockTargets {
    val resume = uniqueMethod(LOCK_PATCH, "chat screen onResume", ChatResumeFingerprint)
    val pause = uniqueMethod(LOCK_PATCH, "chat screen onPause", ChatPauseFingerprint)
    if (resume.definingClass != pause.definingClass) refuse("the chat screen's onResume and onPause are in different classes")
    for (method in listOf(resume, pause)) {
        if (0 in method.jumpTargets()) refuse("something jumps back to the chat screen's ${method.name} first instruction")
    }
    val screen = resume.definingClass
    val controller = controllerField(listOf(resume, pause), screen)
    val getter = keyGetter(listOf(resume, pause), controller.type)
    val keyClass = classDefByOrNull(THREAD_KEY) ?: refuse("$THREAD_KEY is missing")
    val idField = threadIdField(keyClass)
    requirePublic(screen, null)
    requirePublic(controller.definingClass, controller)
    requirePublic(controller.type, getter)
    requirePublic(THREAD_KEY, idField)

    val bridge = mutableClassDefBy(CHAT_LOCKS).methods.filter {
        it.name == CHAT_THREAD_ID && it.parameters() == listOf("Ljava/lang/Object;") && it.returnType == STRING &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.one("extension's chat thread id bridge")
    val bridgeBody = """
        check-cast p0, $screen
        iget-object p0, p0, ${controller.signature()}
        invoke-virtual { p0 }, ${getter.signature()}
        move-result-object p0
        if-eqz p0, :none
        iget-object p0, p0, ${idField.signature()}
        :none
        return-object p0
    """.trimIndent()
    requireHooks()

    val display = uniqueMethod(LOCK_PATCH, "push notification display", PushDisplayFingerprint)
    if (0 in display.jumpTargets()) refuse("something jumps back to the push notification display's first instruction")
    display.requireLocals(LOCK_PATCH, 6)
    val result = display.parameters().first()
    val resultClass = classDefByOrNull(result) ?: refuse("$result, the push display's result, isn't in this build")
    val toString = uniqueMethod(LOCK_PATCH, "push notification labels", PushLabelsFingerprint)
    val payload = toString.definingClass
    val fields = resultClass.instanceFields.toList()
    val notifications = fields.filter { it.type == NOTIFICATION }
    if (notifications.size != 2) refuse("$result holds ${notifications.size} notifications, expected two")
    val others = fields.filter { it.type == MAP }.one("map of other notifications in $result")
    val holder = fields.filter { it.type == payload }.one("push $result keeps")
    val action = labelField(toString, PUSH_ACTION, payload)
    val thread = labelField(toString, PUSH_THREAD, payload)
    val threadIg = labelField(toString, PUSH_THREAD_IG, payload)
    for (field in notifications + others + holder) requirePublic(result, field)
    for (field in listOf(action, thread, threadIg)) requirePublic(payload, field)

    val hook = """
        move-object/from16 v4, ${display.parameterRegister(0)}
        iget-object v0, v4, ${notifications[0].signature()}
        iget-object v1, v4, ${notifications[1].signature()}
        iget-object v2, v4, ${others.signature()}
        iget-object v5, v4, ${holder.signature()}
        if-eqz v5, :skip
        iget-object v3, v5, ${action.signature()}
        iget-object v4, v5, ${thread.signature()}
        iget-object v5, v5, ${threadIg.signature()}
        invoke-static/range { v0 .. v5 }, $CHAT_TRACK
    """.trimIndent()
    return ChatLockTargets(resume, pause, bridge, bridgeBody, display, hook)
}

/** Writes the thread id bridge and puts the hooks in: both lifecycle methods, and the push display. */
internal fun hookChatLocks(targets: ChatLockTargets) {
    targets.bridge.addInstructionsWithLabels(0, targets.bridgeBody)
    targets.resume.addInstructions(0, "invoke-static/range { p0 .. p0 }, $CHAT_OPENED")
    targets.pause.addInstructions(0, "invoke-static/range { p0 .. p0 }, $CHAT_CLOSED")
    targets.display.addInstructionsWithLabels(
        0,
        targets.displayHook,
        ExternalLabel("skip", targets.display.getInstruction(0)),
    )
}

/** Throws unless the extension has the three hooks, public and static. */
private fun BytecodePatchContext.requireHooks() {
    val extension = classDefByOrNull(CHAT_LOCKS) ?: refuse("the extension has no $CHAT_LOCKS")
    for (hook in listOf(CHAT_OPENED, CHAT_CLOSED, CHAT_TRACK)) {
        val name = hook.substringAfter("->").substringBefore("(")
        val parameters = hook.substringAfter("(").substringBefore(")")
        if (extension.methods.none {
                it.name == name && it.parameterTypes.joinToString("") == parameters && it.returnType == "V" &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }
        ) refuse("the extension has no public static $hook")
    }
}

/**
 * The field the chat screen keeps its thread controller in: the one `this` field read where
 * Instagram's null check names it, [CONTROLLER_NAME], right after the read.
 */
private fun controllerField(methods: List<Method>, screen: String): FieldReference {
    val found = methods.flatMap { method ->
        val code = method.visualCode()
        val self = method.localRegisterCount()
        code.indices.mapNotNull { at ->
            val read = code[at]
            val field = read.visualReference() as? FieldReference ?: return@mapNotNull null
            if (read.opcode != Opcode.IGET_OBJECT || field.definingClass != screen || (read as TwoRegisterInstruction).registerB != self) {
                return@mapNotNull null
            }
            field.takeIf { (at + 1..minOf(at + 4, code.lastIndex)).any { code[it].visualString() == CONTROLLER_NAME } }
        }
    }
    return found.distinctBy { it.signature() }.one("thread controller field of the chat screen")
}

/** The call on the controller that answers the chat's key: no arguments, returning [THREAD_KEY]. */
private fun keyGetter(methods: List<Method>, controller: String): MethodReference {
    val found = methods.flatMap { method ->
        method.visualCode().mapNotNull { instruction ->
            val call = instruction.visualReference() as? MethodReference ?: return@mapNotNull null
            call.takeIf {
                instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == controller &&
                    it.parameterTypes.isEmpty() && it.returnType == THREAD_KEY
            }
        }
    }
    return found.distinctBy { it.signature() }.one("call answering the chat's key on $controller")
}

/** The field a chat key's toString writes right after [THREAD_KEY_TEXT]: its own thread id. */
private fun threadIdField(key: ClassDef): FieldReference {
    val toString = key.methods.filter { it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING }
        .one("chat key's toString")
    val code = toString.visualCode()
    val label = code.indices.filter { code[it].visualString() == THREAD_KEY_TEXT }.one("chat key's thread id label")
    val self = toString.localRegisterCount()
    val reads = (label + 1 until code.size).takeWhile { code[it].visualString() == null }.mapNotNull { at ->
        val field = code[at].visualReference() as? FieldReference ?: return@mapNotNull null
        field.takeIf {
            code[at].opcode == Opcode.IGET_OBJECT && (code[at] as TwoRegisterInstruction).registerB == self &&
                it.definingClass == key.type && it.type == STRING
        }
    }
    if (reads.isNotEmpty()) return reads.distinctBy { it.signature() }.one("thread id the chat key writes after its label")
    // The other shape: the fields are read first and the label and the field go to one concatenation call.
    val labelRegister = (code[label] as OneRegisterInstruction).registerA
    val joined = (label + 1 until code.size).firstOrNull { at ->
        (code[at] as? FiveRegisterInstruction)?.let { it.registerCount >= 2 && it.registerC == labelRegister } == true
    } ?: refuse("the chat key's label isn't followed by anything that writes its thread id")
    val idRegister = (code[joined] as FiveRegisterInstruction).registerD
    val read = (label - 1 downTo 0).firstOrNull { (code[it] as? OneRegisterInstruction)?.registerA == idRegister }?.let { code[it] }
    val field = read?.visualReference() as? FieldReference
    if (read == null || field == null || read.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerB != self ||
        field.definingClass != key.type || field.type != STRING
    ) refuse("expected one thread id the chat key writes after its label, found 0")
    return field
}

/** The field a push's toString reads right after the string [label]. */
private fun labelField(toString: Method, label: String, owner: String): FieldReference {
    val code = toString.visualCode()
    val at = code.indices.filter { code[it].visualString() == label }.one("$label in a push's toString")
    val self = toString.localRegisterCount()
    val reads = (at + 1..minOf(at + 4, code.lastIndex)).takeWhile { code[it].visualString() == null }.mapNotNull { index ->
        val field = code[index].visualReference() as? FieldReference ?: return@mapNotNull null
        field.takeIf {
            code[index].opcode == Opcode.IGET_OBJECT && (code[index] as TwoRegisterInstruction).registerB == self &&
                it.definingClass == owner && it.type == STRING
        }
    }
    return reads.distinctBy { it.signature() }.one("field a push writes after $label")
}

/** Refuses unless [type] is a public class and [member], when given, is a public member the extension can reach. */
private fun BytecodePatchContext.requirePublic(type: String, member: Any?) {
    val owner = classDefByOrNull(type) ?: refuse("$type is missing")
    val flags = when (member) {
        null -> AccessFlags.PUBLIC.value
        is FieldReference -> owner.fields.firstOrNull { it.name == member.name && it.type == member.type }?.accessFlags
        is MethodReference -> owner.methods.firstOrNull {
            it.name == member.name && it.parameters() == member.parameterTypes.map(CharSequence::toString) && it.returnType == member.returnType
        }?.accessFlags
        else -> null
    } ?: refuse("$type doesn't declare ${(member as? FieldReference)?.signature() ?: (member as? MethodReference)?.signature()}")
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(flags)) {
        refuse("${(member as? FieldReference)?.signature() ?: (member as? MethodReference)?.signature() ?: type} isn't public, so the extension can't reach it")
    }
}
