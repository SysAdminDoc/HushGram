/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val OFFER_MARK_READ = "$THREAD_SEEN->offerMarkRead(Ljava/util/List;Ljava/lang/Object;)V"
internal const val MARK_READ = "$THREAD_SEEN->markRead(" +
    "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val MARK_READ_TOGETHER = "$THREAD_SEEN->markReadTogether(" +
    "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/** The extension's bridges to Instagram's chats, whose bodies [markReadByHand] writes. */
internal const val INSTAGRAM_CHATS = "$EXTENSION_PACKAGE/direct/InstagramChats;"

/** The names of the two read actions in the enum behind a chat's long press menu, kept as written. */
internal const val MARK_AS_READ = "MARK_AS_READ"
internal const val MARK_AS_UNREAD = "MARK_AS_UNREAD"

/**
 * The log tag and the success step of Instagram's own handler for marking one chat read, which it
 * runs when a paired device asks.
 */
internal const val MARK_READ_HANDLER = "MarkThreadAsReadRequestHandler"
internal const val MARK_READ_DONE = "mark_thread_as_read_success"

/** A chat's key, which keeps its name, and how it spells itself, its thread id first. */
internal const val THREAD_KEY = "Lcom/instagram/model/direct/DirectThreadKey;"
internal const val THREAD_KEY_TEXT = "DirectThreadKey{mThreadId='"

/**
 * A chat's capabilities, which keep their name, and the one Instagram checks before it offers Mark
 * as unread on the chat's long press, as its enum names it.
 */
internal const val CAPABILITIES = "Lcom/instagram/direct/capabilities/Capabilities;"
internal const val MARK_UNREAD_CAPABILITY = "MARK_THREAD_AS_UNREAD"

/** What Instagram logs when it blocks an action on a chat, as a business inbox can. */
internal const val THREAD_ACTION_BLOCKED = "business_inbox_hmps_thread_action_blocked_dialog_impression"

/** What Instagram logs when several chats picked together are marked read. */
internal const val MARK_READ_TOGETHER_ACTION = "multiple_thread_mark_read"

/** The name the receipt's request gives the message it marks seen. */
internal const val RECEIPT_ITEM = "item_id"

private const val LIST = "Ljava/util/List;"
private const val GET_USER_ID = "getUserId"
private const val LIST_ADD = "Ljava/util/List;->add(Ljava/lang/Object;)Z"
private const val ENUM = "Ljava/lang/Enum;"
private const val STRING_TYPE = "Ljava/lang/String;"

/** The enum behind a chat's long press menu: its initializer names both read actions. */
internal object ChatMenuActionsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(MARK_AS_READ, MARK_AS_UNREAD),
    custom = { method, classDef -> method.name == "<clinit>" && classDef.superclass == ENUM },
)

/** Instagram's own handler for marking one chat read. */
internal object MarkReadRouteFingerprint : Fingerprint(
    strings = listOf(MARK_READ_HANDLER, MARK_READ_DONE),
)

/** Instagram's check whether it blocks an action on a chat: the static boolean method logging its dialog. */
internal object ThreadActionBlockedFingerprint : Fingerprint(
    returnType = "Z",
    strings = listOf(THREAD_ACTION_BLOCKED),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** Instagram's Mark as read for several chats picked together. */
internal object MarkReadTogetherFingerprint : Fingerprint(
    strings = listOf(MARK_READ_TOGETHER_ACTION),
)

/** One bridge: the stub in [INSTAGRAM_CHATS] and the body the patch writes into it. */
internal class ChatBridge(val stub: MutableMethod, val body: String)

internal data class MarkReadTargets(
    /** Instagram's own Mark as read, the enum constant the long press menu labels and passes on a tap. */
    val markAsRead: FieldReference,
    /**
     * The builder that offers Mark as unread on a chat's long press, its list of rows, where it goes
     * on once the chat can be marked unread, and the local the offer borrows there.
     */
    val builder: MutableMethod,
    val rows: Int,
    val offerAt: Int,
    val offerRegister: Int,
    /** What a tap on a row of that menu runs: the row chosen, the chat, its key and the account it reads. */
    val action: MutableMethod,
    val chosen: Int,
    val thread: Int,
    val key: Int,
    val session: FieldReference,
    /** The branch on Instagram's answer whether it blocks an action on the chat, and the locals the guard borrows there. */
    val tapAt: Int,
    val tapRegisters: List<Int>,
    /** Instagram's Mark as read for chats picked together, and its call that queues each chat's receipt. */
    val together: MutableMethod,
    val togetherAt: Int,
    val bridges: List<ChatBridge>,
)

private fun refuse(why: String): Nothing = throw PatchException("$THREAD_SEEN_PATCH: $why")
private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
private fun Instruction.call() = visualReference() as? MethodReference
private fun Instruction.field() = visualReference() as? FieldReference
private fun Instruction.type() = (visualReference() as? TypeReference)?.type
private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.parameters() = parameterTypes.map(Any::toString)
private fun Method.static() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.public() = AccessFlags.PUBLIC.isSet(accessFlags)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val DIRECT_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (destination == register || opcode.setsWideRegister() && destination + 1 == register)
}

/** The message Instagram's handler marks a chat read up to, and the two things it reads off that message. */
private class Pick(val message: MethodReference, val id: MethodReference, val sender: FieldReference)

/**
 * Resolve everything a chat marked read by hand needs, before any edit: Instagram's own Mark as
 * read, which 450 offers only when several chats are picked, the builder that offers Mark as unread
 * on one chat's long press and its check that the chat can be, where a tap on that menu lands once
 * Instagram has allowed an action on the chat, Instagram's Mark as read for chats picked together,
 * and the calls Instagram's own handler for marking a chat read makes. Those calls go into the
 * bridges' bodies: the receipt itself goes out through [seen]'s live sender, the one the hold
 * already guards, and its handler sends it. The hold matches it by the message id the handler sends
 * and the account it sends it for.
 */
internal fun BytecodePatchContext.findMarkRead(seen: ThreadSeenTargets): MarkReadTargets {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classes += it }
    val byType = classes.associateBy { it.type }
    val methods = classes.asSequence().flatMap { it.methods.asSequence() }.filter { it.implementation != null }.toList()

    val menuInit = uniqueMethod(THREAD_SEEN_PATCH, "chat menu action enum", ChatMenuActionsFingerprint)
    val menu = byType[menuInit.definingClass] ?: refuse("chat menu action enum is missing")
    val markAsRead = enumConstant(menu, MARK_AS_READ)
    val markAsUnread = enumConstant(menu, MARK_AS_UNREAD)

    // Instagram's own handler for marking a chat read: the message it picks, and the receipt and
    // unread calls it makes with it.
    val route = uniqueMethod(THREAD_SEEN_PATCH, "Instagram handler for marking a chat read", MarkReadRouteFingerprint)
    val sender = seen.creator
    if (!sender.static() || !sender.public() || sender.returnType != "V" ||
        sender.parameters().let { it.size != 5 || it[0] != USER_SESSION || !it[1].startsWith("L") || it.drop(2) != List(3) { STRING_TYPE } }
    ) refuse("live receipt sender doesn't take an account, a detail and three ids")
    val pick = pickMessage(route, sender)
    val unread = unreadCall(route)

    val builderRef = rowBuilder(methods, markAsUnread)
    val builder = mutableMethod(builderRef)
    val rows = builder.parameters().indexOf(LIST)
    if (builder.parameterRegisterNumber(rows) > 15) refuse("chat menu builder keeps its rows past v15")
    val offerAt = capabilityGate(builder, byType)
    builder.requireParameterIntact(THREAD_SEEN_PATCH, rows, listOf(offerAt))
    val offerRegister = builder.freeLocalsAt(THREAD_SEEN_PATCH, offerAt, 1).single()

    val actionRef = methods.filter { method ->
        !method.static() && method.name != "<init>" && method.returnType == "V" && method.parameters().count { it == menu.type } == 1
    }.one("chat menu action handler")
    val action = mutableMethod(actionRef)
    val parameters = action.parameters()
    val chosen = parameters.indexOf(menu.type)
    if (parameters.count { it == THREAD_KEY } != 1) refuse("chat menu action handler doesn't take one chat key")
    val key = parameters.indexOf(THREAD_KEY)
    val chat = pick.message.parameterTypes.single().toString()
    val thread = parameters.indices.filter { at ->
        parameters[at] == chat || byType[parameters[at]]?.let { type ->
            AccessFlags.INTERFACE.isSet(type.accessFlags) && chat in type.interfaces
        } == true
    }.one("chat the menu action handler is given")
    if (action.visualCode().none { it.call()?.key() == unread.key() }) {
        refuse("chat menu action handler never takes a chat's unread mark through Instagram's call")
    }
    val session = (byType[action.definingClass] ?: refuse("chat menu action class is missing")).instanceFields
        .filter { it.type == USER_SESSION }.toList().one("account the chat menu action handler keeps")
    val blocked = uniqueMethod(THREAD_SEEN_PATCH, "check for a chat action Instagram blocks", ThreadActionBlockedFingerprint)
    val tapAt = blockedBranch(action, blocked)
    for (read in listOf(chosen, thread, key)) action.requireParameterIntact(THREAD_SEEN_PATCH, read, listOf(tapAt))
    action.requireThisIntact(THREAD_SEEN_PATCH, listOf(tapAt))
    val tapRegisters = action.freeLocalsAt(THREAD_SEEN_PATCH, tapAt, 5)

    val together = uniqueMethod(THREAD_SEEN_PATCH, "Instagram's Mark as read for chats picked together", MarkReadTogetherFingerprint)
    if (together.key() == builder.key() || together.key() == action.key()) {
        refuse("Instagram's Mark as read for chats picked together is also the chat menu's builder or tap handler")
    }
    val pickedCode = together.visualCode()
    val togetherAt = pickedCode.indices.filter { pickedCode[it].call()?.key() == sender.key() }
        .one("receipt Instagram's Mark as read for chats picked together queues")

    val keyClass = byType[THREAD_KEY] ?: refuse("$THREAD_KEY is missing")
    val threadId = threadIdField(keyClass)
    val receipt = byType[seen.mutation] ?: refuse("receipt mutation class is missing")
    val receiptKey = receipt.methods.filter {
        !it.static() && it.public() && it.parameterTypes.isEmpty() && it.returnType == THREAD_KEY
    }.one("receipt's chat key")
    val (details, message) = receiptMessage(seen, receipt, byType)
    val account = byType[USER_SESSION] ?: refuse("$USER_SESSION is missing")
    if (AccessFlags.INTERFACE.isSet(account.accessFlags)) refuse("$USER_SESSION is an interface")
    val accountId = account.methods.filter {
        it.name == GET_USER_ID && it.parameterTypes.isEmpty() && it.returnType == STRING_TYPE && !it.static()
    }.one("account id $USER_SESSION answers")

    requirePublic(byType, keyClass.type, threadId)
    requirePublic(byType, receipt.type, receiptKey)
    requirePublic(byType, receipt.type, details)
    requirePublic(byType, details.type, null)
    requirePublic(byType, message.definingClass, message)
    requirePublic(byType, USER_SESSION, accountId)
    requirePublic(byType, pick.message.parameterTypes.single().toString(), null)
    requirePublic(byType, pick.message.definingClass, pick.message)
    requirePublic(byType, pick.id.definingClass, pick.id)
    requirePublic(byType, pick.sender.definingClass, pick.sender)
    requirePublic(byType, sender.parameterTypes[1].toString(), null)
    requirePublic(byType, unread.definingClass, unread)

    val extension = byType[THREAD_SEEN] ?: refuse("extension is missing")
    requireHook(extension, "offerMarkRead", listOf(LIST, JAVA_OBJECT_TYPE), "V")
    requireHook(extension, "markRead", List(5) { JAVA_OBJECT_TYPE }, "Z")
    requireHook(extension, "markReadTogether", listOf(JAVA_OBJECT_TYPE, JAVA_OBJECT_TYPE, STRING_TYPE, STRING_TYPE, STRING_TYPE), "V")
    val bridges = chatBridges(listOf(
        Triple("accountId", listOf(JAVA_OBJECT_TYPE), STRING_TYPE) to """
            check-cast p0, $USER_SESSION
            invoke-virtual { p0 }, ${accountId.key()}
            move-result-object p0
            return-object p0
        """,
        Triple("threadId", listOf(JAVA_OBJECT_TYPE), STRING_TYPE) to """
            check-cast p0, $THREAD_KEY
            iget-object p0, p0, $threadId
            return-object p0
        """,
        Triple("receiptKey", listOf(JAVA_OBJECT_TYPE), JAVA_OBJECT_TYPE) to """
            check-cast p0, ${receipt.type}
            invoke-virtual { p0 }, ${receiptKey.definingClass}->${receiptKey.name}()$THREAD_KEY
            move-result-object p0
            return-object p0
        """,
        Triple("receiptMessage", listOf(JAVA_OBJECT_TYPE), STRING_TYPE) to """
            check-cast p0, ${receipt.type}
            iget-object p0, p0, $details
            iget-object p0, p0, $message
            return-object p0
        """,
        Triple("lastMessage", listOf(JAVA_OBJECT_TYPE), JAVA_OBJECT_TYPE) to """
            check-cast p0, $chat
            invoke-static { p0 }, ${pick.message.key()}
            move-result-object p0
            return-object p0
        """,
        Triple("messageId", listOf(JAVA_OBJECT_TYPE), STRING_TYPE) to """
            check-cast p0, ${pick.id.definingClass}
            invoke-virtual { p0 }, ${pick.id.key()}
            move-result-object p0
            return-object p0
        """,
        Triple("senderId", listOf(JAVA_OBJECT_TYPE), STRING_TYPE) to """
            check-cast p0, ${pick.sender.definingClass}
            iget-object p0, p0, ${pick.sender}
            return-object p0
        """,
        Triple("sendSeen", listOf(JAVA_OBJECT_TYPE, JAVA_OBJECT_TYPE, STRING_TYPE, STRING_TYPE, STRING_TYPE), "V") to """
            check-cast p0, $USER_SESSION
            check-cast p1, ${sender.parameterTypes[1]}
            invoke-static { p0, p1, p2, p3, p4 }, ${sender.key()}
            return-void
        """,
        Triple("markUnread", listOf(JAVA_OBJECT_TYPE, JAVA_OBJECT_TYPE, "Z"), "V") to """
            check-cast p0, $USER_SESSION
            check-cast p1, $THREAD_KEY
            invoke-static { p0, p1, p2 }, ${unread.key()}
            return-void
        """,
    ))
    return MarkReadTargets(markAsRead, builder, rows, offerAt, offerRegister, action, chosen, thread, key, session,
        tapAt, tapRegisters, together, togetherAt, bridges)
}

private const val JAVA_OBJECT_TYPE = "Ljava/lang/Object;"

private fun BytecodePatchContext.mutableMethod(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameters() == method.parameters() && it.returnType == method.returnType
    }

/**
 * The static field of [enum] that holds its constant named [name]: Instagram's initializer loads the
 * name, builds a new constant with it, handed where the constructor takes its first String, and
 * stores that constant before building the next one.
 */
private fun enumConstant(enum: ClassDef, name: String): FieldReference {
    val init = enum.methods.filter { it.name == "<clinit>" }.one("initializer of the enum naming $name")
    val code = init.visualCode()
    val named = code.indices.filter { code[it].visualString() == name }.one("$name in its enum's initializer")
    val text = (code[named] as OneRegisterInstruction).registerA
    val built = (named + 1 until code.size).firstOrNull {
        code[it].opcode in DIRECT_CALLS && code[it].call()?.let { call -> call.definingClass == enum.type && call.name == "<init>" } == true
    } ?: refuse("$name is never built")
    val arguments = code[built].namedRegisters()
    val before = code[built].call()!!.parameterTypes.map(Any::toString).let { constructor ->
        constructor.take(constructor.indexOf(STRING_TYPE).takeIf { it >= 0 } ?: refuse("$name's constructor takes no name"))
    }
    val nameAt = 1 + before.size + before.count { it == "J" || it == "D" }
    if (arguments.getOrNull(nameAt) != text) refuse("$name isn't the name its constant is built with")
    requireOrigin(THREAD_SEEN_PATCH, init, built, text, named, "$name's name")
    val stored = (built + 1 until code.size).firstOrNull { code[it].opcode == Opcode.SPUT_OBJECT }
        ?: refuse("$name is never stored")
    val field = code[stored].field()!!
    if (field.definingClass != enum.type || field.type != enum.type) refuse("$name is stored outside its enum")
    val constant = (code[stored] as OneRegisterInstruction).registerA
    val allocated = (named + 1 until built).filter {
        code[it].opcode == Opcode.NEW_INSTANCE && code[it].type() == enum.type
    }.one("allocation of $name")
    if ((code[allocated] as OneRegisterInstruction).registerA != constant) refuse("$name stores another object than it builds")
    requireOrigin(THREAD_SEEN_PATCH, init, stored, constant, allocated, "$name's constant")
    val receiver = arguments.first()
    if (receiver == constant) {
        requireOrigin(THREAD_SEEN_PATCH, init, built, receiver, allocated, "$name's constant")
    } else {
        val copied = (built - 1 downTo allocated + 1).firstOrNull { code[it].writes(receiver) }
        if (copied == null || code[copied].opcode !in OBJECT_MOVES || (code[copied] as TwoRegisterInstruction).registerB != constant) {
            refuse("$name is built on another object than it stores")
        }
        requireOrigin(THREAD_SEEN_PATCH, init, copied, constant, allocated, "$name's constant")
        requireOrigin(THREAD_SEEN_PATCH, init, built, receiver, copied, "$name's constant")
    }
    return field
}

/**
 * The builder that offers Mark as unread on a chat's long press: the one static method taking one
 * list that adds [markAsUnread] to it straight after loading it.
 */
private fun rowBuilder(methods: List<Method>, markAsUnread: FieldReference): Method = methods.filter { method ->
    if (!method.static() || method.returnType != "V" || method.parameters().count { it == LIST } != 1) return@filter false
    val rows = method.parameterRegisterNumber(method.parameters().indexOf(LIST))
    val code = method.visualCode()
    code.indices.any { at ->
        val next = code.getOrNull(at + 1)
        code[at].opcode == Opcode.SGET_OBJECT && code[at].field()?.toString() == markAsUnread.toString() &&
            next?.opcode == Opcode.INVOKE_INTERFACE && next.call()?.key() == LIST_ADD &&
            next.namedRegisters() == listOf(rows, (code[at] as OneRegisterInstruction).registerA)
    }
}.one("chat menu builder offering Mark as unread")

/**
 * Where the chat menu builder goes on only for a chat Instagram lets be marked unread: just past the
 * branch on its [MARK_UNREAD_CAPABILITY] check of the capabilities it's handed. Nothing else enters
 * there, so Mark as read is offered only where that check lets Mark as unread be.
 */
private fun capabilityGate(builder: Method, classes: Map<String, ClassDef>): Int {
    val parameters = builder.parameters()
    if (parameters.count { it == CAPABILITIES } != 1) refuse("chat menu builder isn't handed one chat's capabilities")
    val capabilities = parameters.indexOf(CAPABILITIES)
    val code = builder.visualCode()
    val asks = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL && code[at].call()?.let {
            it.definingClass == CAPABILITIES && it.returnType == "Z" && it.parameterTypes.size == 1
        } == true
    }
    val kind = asks.map { code[it].call()!!.parameterTypes.single().toString() }.distinct()
        .one("kind of capability the chat menu builder asks about")
    val unread = enumConstant(classes[kind] ?: refuse("$kind is missing"), MARK_UNREAD_CAPABILITY)
    val (at, loaded) = asks.mapNotNull { at ->
        val asked = code[at].namedRegisters().getOrNull(1) ?: return@mapNotNull null
        val load = (at - 1 downTo 0).firstOrNull { code[it].writes(asked) } ?: return@mapNotNull null
        (at to load).takeIf { code[load].opcode == Opcode.SGET_OBJECT && code[load].field()?.toString() == unread.toString() }
    }.one("check of the chat's $MARK_UNREAD_CAPABILITY capability")
    requireOrigin(THREAD_SEEN_PATCH, builder, at, code[at].namedRegisters()[1], loaded, "capability the chat menu builder checks")
    if (code[at].namedRegisters()[0] != builder.parameterRegisterNumber(capabilities)) {
        refuse("chat menu builder checks other capabilities than the chat's")
    }
    builder.requireParameterIntact(THREAD_SEEN_PATCH, capabilities, listOf(at))
    val answer = (code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction)?.registerA
        ?: refuse("chat menu builder drops its capability answer")
    val branch = code.getOrNull(at + 2)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != answer) {
        refuse("chat menu builder doesn't stop for a chat that can't be marked unread")
    }
    val gate = at + 3
    val targets = builder.jumpTargets()
    if (gate >= code.size || at + 2 in targets || gate in targets) refuse("chat menu builder can be entered past its capability check")
    return gate
}

/**
 * Where the chat menu's tap handler has asked [check] whether Instagram blocks an action on the
 * chat: the branch on that answer, which goes on to the menu's rows when it's no and returns when
 * it's yes. The guard goes in front of it, so it runs only once Instagram has let the action
 * through, and hands its own answer to the same branch.
 */
private fun blockedBranch(action: Method, check: Method): Int {
    val code = action.visualCode()
    val at = code.indices.filter { code[it].call()?.key() == check.key() }
        .one("check for a blocked chat action in the chat menu action handler")
    val answer = (code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction)?.registerA
        ?: refuse("chat menu action handler drops Instagram's answer on a blocked action")
    val branch = code.getOrNull(at + 2)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != answer ||
        code.getOrNull(at + 3)?.opcode != Opcode.RETURN_VOID
    ) refuse("chat menu action handler doesn't stop when Instagram blocks the action")
    if (at + 2 in action.jumpTargets()) refuse("a jump reaches the chat menu action handler's blocked branch past Instagram's check")
    return at + 2
}

/**
 * Where a queued receipt keeps the id of the message it marks seen: the field [seen]'s handler sends
 * as its request's [RECEIPT_ITEM], read off the details the receipt holds, which the live sender
 * fills in from the message it's handed. A mark is matched against that id.
 */
private fun receiptMessage(seen: ThreadSeenTargets, receipt: ClassDef, classes: Map<String, ClassDef>): Pair<FieldReference, FieldReference> {
    val code = seen.handler.visualCode()
    val named = code.indices.filter { code[it].visualString() == RECEIPT_ITEM }.one("$RECEIPT_ITEM the receipt handler names")
    val name = (code[named] as OneRegisterInstruction).registerA
    val put = (named + 1 until code.size).firstOrNull { code[it].call() != null && code[it].namedRegisters().lastOrNull() == name }
        ?: refuse("receipt handler never sends its $RECEIPT_ITEM")
    requireOrigin(THREAD_SEEN_PATCH, seen.handler, put, name, named, "receipt's $RECEIPT_ITEM name")
    val arguments = code[put].namedRegisters()
    val value = arguments.getOrNull(arguments.size - 2) ?: refuse("receipt handler sends no $RECEIPT_ITEM")
    val read = (put - 1 downTo 0).firstOrNull { code[it].writes(value) }
        ?.takeIf { code[it].opcode == Opcode.IGET_OBJECT && code[it].field()?.type == STRING_TYPE }
        ?: refuse("receipt handler's $RECEIPT_ITEM isn't read off the receipt's details")
    requireOrigin(THREAD_SEEN_PATCH, seen.handler, put, value, read, "receipt's $RECEIPT_ITEM")
    val message = code[read].field()!!
    val details = receipt.fields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && extends(field.type, message.definingClass, classes)
    }.one("details the receipt keeps its message in")
    if (code.none { it.opcode == Opcode.IGET_OBJECT && it.field()?.toString() == details.toString() }) {
        refuse("receipt handler never reads the receipt's details")
    }
    if (seen.creator.visualCode().count { it.opcode == Opcode.IPUT_OBJECT && it.field()?.toString() == details.toString() } != 1) {
        refuse("live receipt sender doesn't fill in the receipt's details once")
    }
    return details to message
}

/** [type] is [ancestor] or extends it, through the classes this build declares. */
private fun extends(type: String, ancestor: String, classes: Map<String, ClassDef>): Boolean {
    var current: String? = type
    val visited = mutableSetOf<String>()
    while (current != null && visited.add(current)) {
        if (current == ancestor) return true
        current = classes[current]?.superclass
    }
    return false
}

/**
 * The message [route] marks a chat read up to: a static call on the chat answering a message whose
 * id getter and sender field give the two ids [route] hands [sender], in that order.
 */
private fun pickMessage(route: Method, sender: Method): Pick {
    val code = route.visualCode()
    val sent = code.indices.filter { code[it].call()?.key() == sender.key() }.one("receipt sent by Instagram's handler for marking a chat read")
    val ids = code[sent].namedRegisters()
    if (ids.size != 5) refuse("Instagram's handler for marking a chat read sends its receipt with ${ids.size} arguments")
    val picks = code.indices.mapNotNull { at ->
        val get = code[at].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.call() ?: return@mapNotNull null
        if (get.parameterTypes.size != 1 || !get.parameterTypes[0].startsWith("L") || !get.returnType.startsWith("L")) return@mapNotNull null
        val message = (code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA
            ?: return@mapNotNull null
        val idAt = (at + 2 until sent).firstOrNull { code[it].writes(message) || code[it].opcode == Opcode.INVOKE_VIRTUAL && code[it].namedRegisters() == listOf(message) }
            ?: return@mapNotNull null
        val id = code[idAt].takeIf { it.opcode == Opcode.INVOKE_VIRTUAL }?.call() ?: return@mapNotNull null
        if (id.parameterTypes.isNotEmpty() || id.returnType != STRING_TYPE) return@mapNotNull null
        if ((code.getOrNull(idAt + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA != ids[3]) {
            return@mapNotNull null
        }
        val senderAt = (idAt + 2 until sent).firstOrNull {
            code[it].writes(message) || code[it].opcode == Opcode.IGET_OBJECT && (code[it] as TwoRegisterInstruction).registerB == message
        } ?: return@mapNotNull null
        val from = code[senderAt].takeIf { it.opcode == Opcode.IGET_OBJECT }?.field() ?: return@mapNotNull null
        if (from.type != STRING_TYPE || (code[senderAt] as OneRegisterInstruction).registerA != ids[4]) return@mapNotNull null
        Triple(at, idAt, senderAt) to Pick(get, id, from)
    }
    val (found, pick) = picks.one("message Instagram's handler for marking a chat read sends its receipt for")
    requireOrigin(THREAD_SEEN_PATCH, route, found.second, (code[found.first + 1] as OneRegisterInstruction).registerA, found.first + 1, "marked message")
    requireOrigin(THREAD_SEEN_PATCH, route, found.third, (code[found.first + 1] as OneRegisterInstruction).registerA, found.first + 1, "marked message")
    return pick
}

/** The call [route] takes a chat's unread mark off with: Instagram's (account, chat key, boolean), handed false. */
private fun unreadCall(route: Method): MethodReference {
    val code = route.visualCode()
    val at = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_STATIC && code[index].call()?.let {
            it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION, THREAD_KEY, "Z")
        } == true
    }.one("call Instagram's handler for marking a chat read takes the unread mark off with")
    val flag = code[at].namedRegisters()[2]
    val set = (at - 1 downTo 0).firstOrNull { code[it].writes(flag) }
    if (set == null || code[set].opcode != Opcode.CONST_4 || (code[set] as NarrowLiteralInstruction).narrowLiteral != 0) {
        refuse("Instagram's handler for marking a chat read doesn't hand its unread call false")
    }
    requireOrigin(THREAD_SEEN_PATCH, route, at, flag, set, "false handed to the unread call")
    return code[at].call()!!
}

/** The field a chat key's toString writes right after [THREAD_KEY_TEXT]: its own thread id. */
private fun threadIdField(key: ClassDef): FieldReference {
    val toString = key.methods.filter { it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING_TYPE }
        .one("chat key's toString")
    val code = toString.visualCode()
    val labelAt = code.indices.filter { code[it].visualString() == THREAD_KEY_TEXT }.one("chat key's thread id label")
    val label = (code[labelAt] as OneRegisterInstruction).registerA
    val joined = (labelAt + 1 until code.size).firstOrNull {
        code[it].call() != null && code[it].namedRegisters().firstOrNull() == label
    } ?: refuse("chat key's toString never uses its thread id label")
    val id = code[joined].namedRegisters().getOrNull(1) ?: refuse("chat key's toString writes nothing after its thread id label")
    val read = (joined - 1 downTo 0).firstOrNull { code[it].writes(id) }?.takeIf { at ->
        code[at].opcode == Opcode.IGET_OBJECT && (code[at] as TwoRegisterInstruction).registerB == toString.localRegisterCount() &&
            code[at].field()?.let { it.definingClass == key.type && it.type == STRING_TYPE } == true
    } ?: refuse("chat key's toString doesn't write its own thread id after the label")
    requireOrigin(THREAD_SEEN_PATCH, toString, joined, id, read, "chat key's thread id")
    toString.requireThisIntact(THREAD_SEEN_PATCH, listOf(read))
    return code[read].field()!!
}

/** Refuses unless [type] is a public class and [member], when given, is public too, so the extension can reach it. */
private fun requirePublic(classes: Map<String, ClassDef>, type: String, member: Any?) {
    val owner = classes[type] ?: refuse("$type is missing")
    val flags = when (member) {
        null -> AccessFlags.PUBLIC.value
        is FieldReference -> owner.fields.firstOrNull { it.name == member.name && it.type == member.type }?.accessFlags
        is MethodReference -> owner.methods.firstOrNull { it.name == member.name && it.parameters() == member.parameterTypes.map(Any::toString) && it.returnType == member.returnType }?.accessFlags
        else -> null
    } ?: refuse("$type doesn't declare $member")
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(flags)) {
        refuse("${member ?: type} isn't public, so the extension can't reach it")
    }
}

private fun requireHook(extension: ClassDef, name: String, parameters: List<String>, returns: String) {
    extension.methods.filter {
        it.name == name && it.parameters() == parameters && it.returnType == returns && it.public() && it.static()
    }.one("extension's public static $name")
}

/** Each bridge's stub in [INSTAGRAM_CHATS], with the body that goes into it. */
private fun BytecodePatchContext.chatBridges(bodies: List<Pair<Triple<String, List<String>, String>, String>>): List<ChatBridge> {
    val chats = classDefByOrNull(INSTAGRAM_CHATS) ?: refuse("extension has no $INSTAGRAM_CHATS")
    val stubs = mutableClassDefBy(chats).methods
    return bodies.map { (shape, body) ->
        val (name, parameters, returns) = shape
        val stub = stubs.filter {
            it.name == name && it.parameters() == parameters && it.returnType == returns &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        }.one("extension's chat bridge $name")
        ChatBridge(stub, body)
    }
}

/**
 * Offers Instagram's own Mark as read on a chat's long press while the switch is on, once the
 * builder has found the chat can be marked unread. On a tap that Instagram doesn't block, hands the
 * extension the row, the chat, its key and the account before Instagram's own code for the row
 * runs, which does nothing for Mark as read: a tap the extension handled takes the same return a
 * blocked one does. Tells the extension about each chat Instagram's Mark as read for chats picked
 * together is about to queue a receipt for, with what it hands the sender. Then writes the bridges'
 * bodies.
 */
internal fun markReadByHand(found: MarkReadTargets) {
    found.builder.addInstructions(
        found.offerAt,
        """
            sget-object v${found.offerRegister}, ${found.markAsRead}
            invoke-static { ${found.builder.parameterRegister(found.rows)}, v${found.offerRegister} }, $OFFER_MARK_READ
        """,
    )
    val action = found.action
    val (row, markAsRead, session, chat, key) = found.tapRegisters
    val answer = (action.getInstruction(found.tapAt) as OneRegisterInstruction).registerA
    action.addInstructionsWithLabels(
        found.tapAt,
        """
            if-nez v$answer, :blocked
            move-object/from16 v$row, ${action.parameterRegister(found.chosen)}
            sget-object v$markAsRead, ${found.markAsRead}
            move-object/from16 v$session, p0
            iget-object v$session, v$session, ${found.session}
            move-object/from16 v$chat, ${action.parameterRegister(found.thread)}
            move-object/from16 v$key, ${action.parameterRegister(found.key)}
            invoke-static { v$row, v$markAsRead, v$session, v$chat, v$key }, $MARK_READ
            move-result v$answer
        """,
        ExternalLabel("blocked", action.getInstruction(found.tapAt)),
    )
    val queued = found.together.getInstruction(found.togetherAt)
    val registers = queued.namedRegisters()
    val arguments = if (queued.opcode == Opcode.INVOKE_STATIC_RANGE) {
        "invoke-static/range { v${registers.first()} .. v${registers.last()} }"
    } else {
        "invoke-static { ${registers.joinToString { "v$it" }} }"
    }
    // At the call's own label, so a jump straight to the call tells the extension too.
    found.together.addInstructionsAtControlFlowLabel(found.togetherAt, "$arguments, $MARK_READ_TOGETHER")
    found.bridges.forEach { it.stub.addInstructions(0, it.body) }
}
