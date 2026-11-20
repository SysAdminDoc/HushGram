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
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.THREAD_KEY_TEXT
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.direct.seen.visualString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsConstString
import app.morphe.patches.instagram.misc.extension.loadsConstStringContaining
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val CHAT_LOCKS = "$EXTENSION_PACKAGE/direct/ChatLocks;"
internal const val CHAT_OPENED = "$CHAT_LOCKS->opened(Ljava/lang/Object;)V"
internal const val CHAT_CLOSED = "$CHAT_LOCKS->closed(Ljava/lang/Object;)V"
internal const val CHAT_TRACK = "$CHAT_LOCKS->track(Landroid/app/Notification;Landroid/app/Notification;" +
    "Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"
private const val CHAT_THREAD_ID = "threadId"

/** What the chat screen logs first in onResume and onPause, as Instagram's thread controller. */
internal const val CHAT_RESUME = "DirectThreadController.onResume"
internal const val CHAT_PAUSE = "DirectThreadController.onPause"

/** The name Instagram's null check gives the chat screen's controller when it reads the field. */
internal const val CONTROLLER_NAME = "threadController"

/** What Instagram's push display logs, in the one method that shows a push's notifications. */
internal const val PUSH_DISPLAYED = "notification_displayed"
internal const val PUSH_ALERT_ONCE = "is_alert_only_once"

/**
 * The labels a push's toString writes before the fields the lock matches a chat with, and before
 * the id of the account the push was sent to, which says whose list the chat is matched against.
 */
internal const val PUSH_ACTION = "mIgAction"
internal const val PUSH_THREAD = "mThreadId"
internal const val PUSH_THREAD_IG = "mThreadIgId"
internal const val PUSH_RECIPIENT = "mIntendedRecipientUserid"

private const val NOTIFICATION = "Landroid/app/Notification;"
private const val MAP = "Ljava/util/Map;"
private const val STRING = "Ljava/lang/String;"
private const val STRING_BUILDER = "Ljava/lang/StringBuilder;"

/** The chat screen's onResume: the one method logging [CHAT_RESUME]. */
internal object ChatResumeFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string(CHAT_RESUME)),
    custom = { method, _ -> method.name == "onResume" && method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The chat screen's onPause: the one method logging [CHAT_PAUSE]. */
internal object ChatPauseFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string(CHAT_PAUSE)),
    custom = { method, _ -> method.name == "onPause" && method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The method that hands a push's notifications to Android: it logs both the display and the alert-once flag. */
internal object PushDisplayFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string(PUSH_ALERT_ONCE)),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isNotEmpty() &&
        method.loadsConstStringContaining(PUSH_DISPLAYED) },
)

/** A push's toString, whose labels name the fields the push keeps its link and chat ids in. */
internal object PushLabelsFingerprint : Fingerprint(
    returnType = STRING,
    filters = listOf(string(PUSH_ACTION)),
    custom = { method, _ -> method.name == "toString" && method.parameterTypes.isEmpty() &&
        method.loadsConstStringContaining(PUSH_THREAD) && method.loadsConstString(PUSH_THREAD_IG) },
)

/**
 * What the chat lock needs from Instagram, all proved before anything changes: the chat screen's
 * two lifecycle methods and the body of the extension's thread id bridge, and the push display
 * with the fields its hook reads. The push hook answers true for a push of a hidden chat, and the
 * display then returns before it builds or posts anything.
 */
internal class ChatLockTargets(
    val resume: MutableMethod,
    val pause: MutableMethod,
    val bridge: MutableMethod,
    val bridgeBody: String,
    val display: MutableMethod,
    val displayHook: String,
)

private fun refuse(why: String, patch: String = LOCK_PATCH): Nothing = throw PatchException("$patch: $why")

internal fun <T> List<T>.one(what: String, patch: String = LOCK_PATCH): T =
    singleOrNull() ?: refuse("expected one $what, found $size", patch)

internal fun Method.parameters() = parameterTypes.map(CharSequence::toString)

internal fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

internal fun FieldReference.signature() = "$definingClass->$name:$type"

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
        if-nez p0, :key
        const/4 p0, 0x0
        return-object p0
        :key
        iget-object p0, p0, ${idField.signature()}
        return-object p0
    """.trimIndent()
    requireHooks()

    val display = findPushDisplay(LOCK_PATCH)
    return ChatLockTargets(resume, pause, bridge, bridgeBody, display.method, display.hookCalling(CHAT_TRACK, answers = true))
}

/**
 * The method that hands a push's notifications to Android, and what its hooks read there: the push's
 * two notifications and the map of others it posts with them, from the result the display takes
 * first, and the deep link, the chat ids and the id of the account it was sent to, from the push that
 * result keeps. The chat lock and Chat bubbles each hook it, so a hook goes in first and leaves the
 * method's own code, and each other hook, as they were.
 */
internal class PushDisplay(
    val method: MutableMethod,
    private val result: String,
    private val notifications: List<FieldReference>,
    private val others: FieldReference,
    private val holder: FieldReference,
    private val action: FieldReference,
    private val thread: FieldReference,
    private val threadIg: FieldReference,
    private val recipient: FieldReference,
) {
    /**
     * Smali handing [hook] the two notifications, the others, then the deep link, the two chat ids and
     * the account, in v0 to v6. A push with no payload goes straight on to what's next, at `:skip`.
     * With [answers], the hook's true ends the display there, before anything is built or posted.
     */
    fun hookCalling(hook: String, answers: Boolean): String = buildString {
        appendLine("move-object/from16 v4, $result")
        appendLine("iget-object v0, v4, ${notifications[0].signature()}")
        appendLine("iget-object v1, v4, ${notifications[1].signature()}")
        appendLine("iget-object v2, v4, ${others.signature()}")
        appendLine("iget-object v6, v4, ${holder.signature()}")
        appendLine("if-eqz v6, :skip")
        appendLine("iget-object v3, v6, ${action.signature()}")
        appendLine("iget-object v4, v6, ${thread.signature()}")
        appendLine("iget-object v5, v6, ${threadIg.signature()}")
        appendLine("iget-object v6, v6, ${recipient.signature()}")
        append("invoke-static/range { v0 .. v6 }, $hook")
        if (answers) append("\nmove-result v0\nif-eqz v0, :skip\nreturn-void")
    }
}

/**
 * The push display and the fields of its result and push, each found by what Instagram writes in or
 * about them, and refused in [patch]'s name when this build has moved them.
 */
internal fun BytecodePatchContext.findPushDisplay(patch: String): PushDisplay {
    val display = uniqueMethod(patch, "push notification display", PushDisplayFingerprint)
    if (0 in display.jumpTargets()) refuse("something jumps back to the push notification display's first instruction", patch)
    display.requireLocals(patch, 7)
    val result = display.parameters().first()
    val resultClass = classDefByOrNull(result) ?: refuse("$result, the push display's result, isn't in this build", patch)
    val toString = uniqueMethod(patch, "push notification labels", PushLabelsFingerprint)
    val payload = toString.definingClass
    val fields = resultClass.instanceFields.toList()
    val notifications = fields.filter { it.type == NOTIFICATION }
    if (notifications.size != 2) refuse("$result holds ${notifications.size} notifications, expected two", patch)
    val others = fields.filter { it.type == MAP }.one("map of other notifications in $result", patch)
    val holder = fields.filter { it.type == payload }.one("push $result keeps", patch)
    val action = labelField(toString, PUSH_ACTION, payload, patch)
    val thread = labelField(toString, PUSH_THREAD, payload, patch)
    val threadIg = labelField(toString, PUSH_THREAD_IG, payload, patch)
    val recipient = labelField(toString, PUSH_RECIPIENT, payload, patch)
    for (field in notifications + others + holder) requirePublic(result, field, patch)
    for (field in listOf(action, thread, threadIg, recipient)) requirePublic(payload, field, patch)
    return PushDisplay(display, display.parameterRegister(0), notifications, others, holder, action, thread, threadIg, recipient)
}

/** Puts [hook] first in the push display, a push with no payload going on to the code that was first. */
internal fun PushDisplay.hookFirst(hook: String) {
    method.addInstructionsWithLabels(0, hook, ExternalLabel("skip", method.getInstruction(0)))
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
        val returns = hook.substringAfter(")")
        if (extension.methods.none {
                it.name == name && it.parameterTypes.joinToString("") == parameters && it.returnType == returns &&
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
internal fun threadIdField(key: ClassDef): FieldReference {
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
    // The other shapes: the fields are read first, then the label and the field go to one concatenation
    // call, or (452) the label starts a StringBuilder and the field is the next thing appended to it.
    val labelRegister = (code[label] as OneRegisterInstruction).registerA
    val idRegister = concatenatedAfter(code, label, labelRegister) ?: builtAfter(code, label, labelRegister)
        ?: refuse("the chat key's label isn't followed by anything that writes its thread id")
    val read = (label - 1 downTo 0).firstOrNull { (code[it] as? OneRegisterInstruction)?.registerA == idRegister }?.let { code[it] }
    val field = read?.visualReference() as? FieldReference
    if (read == null || field == null || read.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerB != self ||
        field.definingClass != key.type || field.type != STRING
    ) refuse("expected one thread id the chat key writes after its label, found 0")
    return field
}

/** The register a concatenation call takes right after the label in [labelRegister], when one does. */
private fun concatenatedAfter(code: List<Instruction>, label: Int, labelRegister: Int): Int? =
    (label + 1 until code.size).firstOrNull { at ->
        (code[at] as? FiveRegisterInstruction)?.let { it.registerCount >= 2 && it.registerC == labelRegister } == true
    }?.let { (code[it] as FiveRegisterInstruction).registerD }

/**
 * 452's toString: the label is the very next instruction's argument to a new StringBuilder's
 * `<init>(String)` (right after its new-instance), and the next instruction appends one String
 * register to that builder, the one this answers. Nothing else may come between.
 */
private fun builtAfter(code: List<Instruction>, label: Int, labelRegister: Int): Int? {
    val created = code.getOrNull(label + 1) ?: return null
    val init = code.getOrNull(label + 2) as? FiveRegisterInstruction ?: return null
    val append = code.getOrNull(label + 3) as? FiveRegisterInstruction ?: return null
    val builder = (created as? OneRegisterInstruction)?.registerA ?: return null
    val initCall = init.visualReference() as? MethodReference ?: return null
    val appendCall = append.visualReference() as? MethodReference ?: return null
    if (created.opcode != Opcode.NEW_INSTANCE || (created.visualReference() as? TypeReference)?.type != STRING_BUILDER) return null
    if (init.opcode != Opcode.INVOKE_DIRECT || initCall.definingClass != STRING_BUILDER || initCall.name != "<init>" ||
        initCall.parameterTypes.map(CharSequence::toString) != listOf(STRING) || init.registerCount != 2 ||
        init.registerC != builder || init.registerD != labelRegister
    ) return null
    if (append.opcode != Opcode.INVOKE_VIRTUAL || appendCall.definingClass != STRING_BUILDER || appendCall.name != "append" ||
        appendCall.parameterTypes.map(CharSequence::toString) != listOf(STRING) || append.registerCount != 2 ||
        append.registerC != builder
    ) return null
    return append.registerD
}

/** The field a push's toString reads right after the string [label]. */
private fun labelField(toString: Method, label: String, owner: String, patch: String): FieldReference {
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
    return reads.distinctBy { it.signature() }.one("field a push writes after $label", patch)
}

/** Refuses unless [type] is a public class and [member], when given, is a public member the extension can reach. */
internal fun BytecodePatchContext.requirePublic(type: String, member: Any?, patch: String = LOCK_PATCH) {
    val owner = classDefByOrNull(type) ?: refuse("$type is missing", patch)
    val flags = when (member) {
        null -> AccessFlags.PUBLIC.value
        is FieldReference -> owner.fields.firstOrNull { it.name == member.name && it.type == member.type }?.accessFlags
        is MethodReference -> owner.methods.firstOrNull {
            it.name == member.name && it.parameters() == member.parameterTypes.map(CharSequence::toString) && it.returnType == member.returnType
        }?.accessFlags
        else -> null
    } ?: refuse("$type doesn't declare ${(member as? FieldReference)?.signature() ?: (member as? MethodReference)?.signature()}", patch)
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(flags)) {
        refuse("${(member as? FieldReference)?.signature() ?: (member as? MethodReference)?.signature() ?: type} isn't public, so the extension can't reach it", patch)
    }
}
