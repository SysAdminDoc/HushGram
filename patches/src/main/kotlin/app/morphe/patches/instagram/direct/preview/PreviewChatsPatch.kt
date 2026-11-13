/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.preview

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.direct.history.MessageFields
import app.morphe.patches.instagram.direct.history.findMessageFields
import app.morphe.patches.instagram.direct.history.messageStubs
import app.morphe.patches.instagram.direct.history.writeMessageBridges
import app.morphe.patches.instagram.direct.keep.USER_SESSION
import app.morphe.patches.instagram.direct.seen.CAPABILITIES
import app.morphe.patches.instagram.direct.seen.ChatMenuActionsFingerprint
import app.morphe.patches.instagram.direct.seen.MARK_AS_UNREAD
import app.morphe.patches.instagram.direct.seen.AccountIdBridge
import app.morphe.patches.instagram.direct.seen.STORE_SEEN_MISSING
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.findAccountId
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PREVIEW_CHATS_PATCH = "Preview chats"
internal const val CHAT_PREVIEW = "$EXTENSION_PACKAGE/direct/ChatPreview;"
internal const val PREVIEW_MESSAGES = "$EXTENSION_PACKAGE/direct/PreviewMessages;"
internal const val PREVIEW_OFFER = "$CHAT_PREVIEW->offer(Ljava/util/List;Ljava/lang/Object;)V"
internal const val PREVIEW_LABEL = "$CHAT_PREVIEW->label(Ljava/util/List;[Ljava/lang/String;)V"
internal const val PREVIEW_OPEN = "$CHAT_PREVIEW->open(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"

/** Instagram's thread debug row: in the release app's chat menu enum, and never offered there. */
internal const val DEBUG_ROW = "THREAD_LEVEL_DEBUG"

/** The label Instagram's chat menu label builder gives that row. */
internal const val DEBUG_LABEL = "[DEBUG] Thread info"

/** The bucket Instagram's inbox composer sends a typed message under. */
internal const val INBOX_COMPOSER = "COMPOSER_INBOX_BUCKET"

private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LABELS = "[Ljava/lang/String;"
private const val LIST_ADD = "Ljava/util/List;->add(Ljava/lang/Object;)Z"

private fun refuse(why: String): Nothing = throw PatchException("$PREVIEW_CHATS_PATCH: $why")
private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
private fun Method.parameters() = parameterTypes.map(Any::toString)
private fun Method.static() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()
private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun MethodReference.signature() = "$name(${parameterTypes.joinToString("")})$returnType"
private fun MethodReference.key() = "$definingClass->${signature()}"

/** Instagram's chat store read: the getter of the account's store and its list of a chat's messages. */
internal class StoreRead(val getter: MethodReference, val messages: MethodReference)

/** Instagram's inbox composer send: the getter of the account's sender and its send. */
internal class SenderCall(val getter: MethodReference, val send: MethodReference)

internal class PreviewTargets(
    /** The builder of a chat's long press menu, its list of rows, and the locals the offer borrows at its start. */
    val builder: MutableMethod,
    val rows: Int,
    val builderLocals: List<Int>,
    val menu: String,
    /** The menu's label builder, its rows, the return the label hook goes before and the labels it returns. */
    val labels: MutableMethod,
    val labelRows: Int,
    val labelAt: Int,
    val labelArray: Int,
    /** The menu's tap handler, the row it's handed, the chat key, the account it keeps and the locals the hook borrows. */
    val action: MutableMethod,
    val chosen: Int,
    val key: Int,
    val session: FieldReference,
    val actionLocals: List<Int>,
    val store: StoreRead,
    val sender: SenderCall,
    val fields: MessageFields,
    val messages: MutableMethod,
    val send: MutableMethod,
    val readers: Map<String, MutableMethod>,
    /** The session's account id, so the box can tell the account's own messages from the stored sender. */
    val accountId: AccountIdBridge,
)

/**
 * Preview in a chat's long press menu. Instagram's chat menu enum carries a thread debug row the
 * release app never offers. The patch puts that row at the top of the menu, labels it Preview and
 * takes its tap, which opens a box with the chat's newest messages read from the chat store this
 * phone keeps. The chat isn't opened, so nothing is fetched and no seen receipt goes out. A reply
 * goes through Instagram's own inbox composer send. Off to start.
 */
@Suppress("unused")
val previewChatsPatch = bytecodePatch(
    name = "Preview chats",
    description = "Long press a chat in your messages and tap Preview to read its newest messages without opening " +
        "it, so nobody sees Seen. You can reply from there too. Starts off. Turn it on in HushGram settings > Messages.",
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("previewChats")
        // Every finder refuses before any code changes.
        val found = findPreview()
        writePreview(found)
        enableStatus("previewChats")
    }
}

/** Everything the preview needs, all proved before any edit. */
internal fun BytecodePatchContext.findPreview(): PreviewTargets {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classes += it }
    val byType = classes.associateBy { it.type }
    val methods = classes.asSequence().flatMap { it.methods.asSequence() }.filter { it.implementation != null }.toList()

    val menuInit = uniqueMethod(PREVIEW_CHATS_PATCH, "chat menu action enum", ChatMenuActionsFingerprint)
    val menu = menuInit.definingClass
    if (!loadsString(menuInit, DEBUG_ROW)) refuse("the chat menu enum has no $DEBUG_ROW row")

    val builder = mutableMethod(chatMenuBuilder(PREVIEW_CHATS_PATCH, menuInit, methods))
    val rows = builder.parameters().indexOf(LIST)
    if (0 in builder.jumpTargets()) refuse("a jump reaches the start of the chat menu builder")
    val builderLocals = builder.freeLocalsAt(PREVIEW_CHATS_PATCH, 0, 2)

    // The labels: the static method handing back the menu's labels for its rows, the one that
    // labels the debug row.
    val labelsRef = classesLoadingString(DEBUG_LABEL).flatMap { owner ->
        owner.methods.filter { method ->
            method.static() && method.returnType == LABELS && method.parameters().count { it == LIST } == 1 &&
                loadsString(method, DEBUG_LABEL)
        }
    }.one("chat menu label builder")
    val labels = mutableMethod(labelsRef)
    val labelRows = labels.parameters().indexOf(LIST)
    val labelCode = labels.code()
    val labelAt = labelCode.indices.filter { labelCode[it].opcode == Opcode.RETURN_OBJECT }.one("return of the chat menu labels")
    val labelArray = (labelCode[labelAt] as OneRegisterInstruction).registerA
    labels.requireParameterIntact(PREVIEW_CHATS_PATCH, labelRows, listOf(labelAt))
    if (labelArray > 15 || labels.parameterRegisterNumber(labelRows) > 15) {
        refuse("the chat menu label builder keeps its rows or labels past v15")
    }

    // The tap handler: the one instance method handed one row of the menu enum.
    val actionRef = methods.filter { method ->
        !method.static() && method.name != "<init>" && method.returnType == "V" && method.parameters().count { it == menu } == 1
    }.one("chat menu action handler")
    val action = mutableMethod(actionRef)
    val parameters = action.parameters()
    if (parameters.count { it == THREAD_KEY } != 1) refuse("chat menu action handler doesn't take one chat key")
    val chosen = parameters.indexOf(menu)
    val key = parameters.indexOf(THREAD_KEY)
    val session = (byType[action.definingClass] ?: refuse("chat menu action class is missing")).instanceFields
        .filter { it.type == USER_SESSION }.toList().one("account the chat menu action handler keeps")
    if (0 in action.jumpTargets()) refuse("a jump reaches the start of the chat menu action handler")
    val actionLocals = action.freeLocalsAt(PREVIEW_CHATS_PATCH, 0, 3)

    val store = storeRead(methods, byType)
    val sender = senderCall()
    val fields = try {
        findMessageFields()
    } catch (e: PatchException) {
        refuse("the message's fields: ${e.message}")
    }

    requirePublic(byType, store.getter.definingClass, store.getter)
    requirePublic(byType, store.messages.definingClass, store.messages)
    requirePublic(byType, sender.getter.definingClass, sender.getter)
    requirePublic(byType, sender.send.definingClass, sender.send)
    requirePublic(byType, THREAD_KEY, null)

    val preview = byType[CHAT_PREVIEW] ?: refuse("the extension has no $CHAT_PREVIEW")
    requireHook(preview, "offer", listOf(LIST, OBJECT), "V")
    requireHook(preview, "label", listOf(LIST, LABELS), "V")
    requireHook(preview, "open", listOf(OBJECT, OBJECT, OBJECT), "Z")
    val bridges = byType[PREVIEW_MESSAGES] ?: refuse("the extension has no $PREVIEW_MESSAGES")
    val stubs = mutableClassDefBy(bridges).methods
    fun stub(name: String, parameters: List<String>, returns: String) = stubs.filter {
        it.name == name && it.parameters() == parameters && it.returnType == returns &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.one("public static $name bridge")
    val messages = stub("messages", listOf(OBJECT, OBJECT, "Z"), LIST)
    val send = stub("send", listOf(OBJECT, OBJECT, STRING, STRING), "V")
    val readers = messageStubs(PREVIEW_CHATS_PATCH, bridges)
    val accountId = findAccountId(PREVIEW_CHATS_PATCH)

    return PreviewTargets(builder, rows, builderLocals, menu, labels, labelRows, labelAt, labelArray,
        action, chosen, key, session, actionLocals, store, sender, fields, messages, send, readers, accountId)
}

/**
 * The account's chat store and its list of a chat's messages. The store class is the one that
 * says [STORE_SEEN_MISSING], and the extension reaches it through the interface it implements, the
 * one a static getter taking only the account answers. That interface lists a chat's messages two
 * ways, both taking the key and whether to sort: all of them, and a capped tail that reads them all
 * first. The preview takes the full list, the one the capped read calls.
 */
private fun BytecodePatchContext.storeRead(methods: List<Method>, byType: Map<String, ClassDef>): StoreRead {
    fun lists(type: ClassDef) = type.methods.filter { it.parameters() == listOf(THREAD_KEY, "Z") && it.returnType == LIST }
    val (impl, face) = classesLoadingString(STORE_SEEN_MISSING).flatMap { owner ->
        owner.interfaces.mapNotNull { byType[it] }.filter { AccessFlags.INTERFACE.isSet(it.accessFlags) && lists(it).isNotEmpty() }
            .map { owner to it }
    }.one("chat store and the interface its messages are read through")
    val candidates = lists(face)
    val list = if (candidates.size == 1) {
        candidates.single()
    } else {
        candidates.filter { candidate ->
            candidates.any { other ->
                other !== candidate && (impl.methods.firstOrNull { it.signature() == other.signature() }?.code() ?: emptyList())
                    .any { it.call()?.signature() == candidate.signature() }
            }
        }.one("full list of a chat's messages in the chat store")
    }
    val getter = methods.filter { it.static() && it.parameters() == listOf(USER_SESSION) && it.returnType == face.type }
        .distinctBy { it.key() }.one("getter of the account's chat store")
    return StoreRead(getter, list)
}

/**
 * Instagram's inbox composer send. A method that loads [INBOX_COMPOSER] gets the account's sender
 * from a static getter on the sender's own class and hands its send the chat key, the text and the
 * bucket.
 */
private fun BytecodePatchContext.senderCall(): SenderCall = classesLoadingString(INBOX_COMPOSER).flatMap { owner ->
    owner.methods.filter { loadsString(it, INBOX_COMPOSER) }.flatMap { method ->
        val code = method.code()
        val sends = code.mapNotNull { it.call() }.filter {
            it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(THREAD_KEY, STRING, STRING)
        }
        sends.mapNotNull { send ->
            code.mapNotNull { it.call() }.firstOrNull {
                it.definingClass == send.definingClass && it.returnType == send.definingClass &&
                    it.parameterTypes.map(Any::toString) == listOf(USER_SESSION)
            }?.let { getter -> SenderCall(getter, send) }
        }
    }
}.distinctBy { it.getter.key() + it.send.key() }.one("Instagram's inbox composer send")

/**
 * The builder of a chat's long press menu: the one static method taking the chat's capabilities and
 * one list of rows that adds Mark as unread to that list. Its class has a second builder for the rows
 * after it, and every menu Instagram builds asks this one first. A row the patches add goes in here,
 * while the list is still the builder's own: the menu itself is later handed a read-only copy, kept
 * with the chat's row in the inbox (#661). [patch] names who refuses.
 */
internal fun chatMenuBuilder(patch: String, menuInit: Method, methods: List<Method>): Method {
    val unread = menuConstant(patch, menuInit, MARK_AS_UNREAD)
    val builders = methods.filter { method ->
        val parameters = method.parameters()
        if (!method.static() || method.returnType != "V" || parameters.count { it == LIST } != 1 ||
            parameters.count { it == CAPABILITIES } != 1
        ) return@filter false
        val code = method.code()
        code.indices.any { at ->
            code[at].opcode == Opcode.SGET_OBJECT && code[at].field()?.toString() == unread &&
                code.getOrNull(at + 1)?.call()?.key() == LIST_ADD
        }
    }.distinctBy { it.key() }
    return builders.singleOrNull()
        ?: throw PatchException("$patch: expected one chat menu builder offering Mark as unread, found ${builders.size}")
}

/** The field the menu enum's initializer stores its [name] row in. */
private fun menuConstant(patch: String, init: Method, name: String): String {
    val code = init.code()
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == name }
    if (named < 0) throw PatchException("$patch: the chat menu enum has no $name row")
    val stored = (named + 1 until code.size).firstOrNull { at ->
        code[at].opcode == Opcode.SPUT_OBJECT && code[at].field()?.let { it.definingClass == init.definingClass && it.type == init.definingClass } == true
    } ?: throw PatchException("$patch: the chat menu enum never stores its $name row")
    return code[stored].field().toString()
}

private fun BytecodePatchContext.mutableMethod(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameters() == method.parameters() && it.returnType == method.returnType
    }

private fun requirePublic(classes: Map<String, ClassDef>, type: String, member: MethodReference?) {
    val owner = classes[type] ?: refuse("$type is missing")
    val flags = if (member == null) {
        AccessFlags.PUBLIC.value
    } else {
        owner.methods.firstOrNull { it.signature() == member.signature() }?.accessFlags ?: refuse("$type doesn't declare ${member.name}")
    }
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(flags)) {
        refuse("${member?.key() ?: type} isn't public, so the extension can't reach it")
    }
}

private fun requireHook(extension: ClassDef, name: String, parameters: List<String>, returns: String) {
    extension.methods.filter {
        it.name == name && it.parameters() == parameters && it.returnType == returns &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && it.static()
    }.one("extension's public static $name")
}

/**
 * Offers Preview first thing in the menu builder, relabels it as the labels are handed back, and
 * takes its tap first thing in the tap handler: a yes from the extension returns before
 * Instagram's own code for the row. Then writes the bridges' bodies.
 */
internal fun writePreview(found: PreviewTargets) {
    val (menuClass, rowsCopy) = found.builderLocals
    found.builder.addInstructions(
        0,
        """
            const-class v$menuClass, ${found.menu}
            move-object/from16 v$rowsCopy, ${found.builder.parameterRegister(found.rows)}
            invoke-static { v$rowsCopy, v$menuClass }, $PREVIEW_OFFER
        """,
    )
    found.labels.addInstructionsAtControlFlowLabel(
        found.labelAt,
        "invoke-static { ${found.labels.parameterRegister(found.labelRows)}, v${found.labelArray} }, $PREVIEW_LABEL",
    )
    val (answer, chosen, key) = found.actionLocals
    found.action.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v$answer, p0
            iget-object v$answer, v$answer, ${found.session}
            move-object/from16 v$chosen, ${found.action.parameterRegister(found.chosen)}
            move-object/from16 v$key, ${found.action.parameterRegister(found.key)}
            invoke-static { v$chosen, v$answer, v$key }, $PREVIEW_OPEN
            move-result v$answer
            if-eqz v$answer, :instagram
            return-void
        """,
        ExternalLabel("instagram", found.action.getInstruction(0)),
    )
    val store = found.store
    found.messages.addInstructions(
        0,
        """
            check-cast p0, $USER_SESSION
            invoke-static { p0 }, ${store.getter.key()}
            move-result-object p0
            check-cast p1, $THREAD_KEY
            invoke-interface { p0, p1, p2 }, ${store.messages.key()}
            move-result-object p0
            return-object p0
        """,
    )
    val sender = found.sender
    found.send.addInstructions(
        0,
        """
            check-cast p0, $USER_SESSION
            invoke-static { p0 }, ${sender.getter.key()}
            move-result-object p0
            check-cast p1, $THREAD_KEY
            const-string p3, "$INBOX_COMPOSER"
            invoke-virtual { p0, p1, p2, p3 }, ${sender.send.key()}
            return-void
        """,
    )
    writeMessageBridges(found.fields, found.readers)
    found.accountId.write()
}
