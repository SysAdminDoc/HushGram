/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.keep

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val KEEP_IN_CHAT_PATCH = "Keep in chat"
internal const val KEEP_IN_CHAT = "$EXTENSION_PACKAGE/direct/KeepInChat;"
internal const val VIEW_MODE_HOOK = "$KEEP_IN_CHAT->viewMode(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"
internal const val MESSAGE_HOOK = "$KEEP_IN_CHAT->messageRead(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val SAVE_HOOK = "$KEEP_IN_CHAT->storedViewMode(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"

/** The account a reader parses for, and the field that holds its user id: both keep their names. */
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
internal const val USER_ID = "$USER_SESSION->userId:Ljava/lang/String;"

/** The JSON key of a photo or video message's view mode, and keys only that message's media has with it. */
internal const val VIEW_MODE = "view_mode"
/** Its keys. seen_user_ids sets it apart from the voice message parser, which reads the first four too. */
internal val VISUAL_MEDIA_KEYS = listOf(VIEW_MODE, "seen_count", "url_expire_at_secs", "expiring_media_action_summary",
    "seen_user_ids")

/** The parser of a photo or video message's media: the one (reader) parse returning an object and holding its keys. */
internal object VisualMediaParseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = VISUAL_MEDIA_KEYS,
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

/** The keys of a direct message's JSON that hold its photo or video media, whether you sent it and who did. */
internal const val VISUAL_MEDIA_KEY = "visual_media"
internal const val MEDIA_ITEM_KEY = "message_item_dict"
internal const val SENT_BY_VIEWER = "is_sent_by_viewer"
internal const val SENDER_KEY = "user_id"

/**
 * The parser of a direct message: the one (reader) parse returning an object and holding all four keys.
 * The reply preview's parser also holds the media keys but not is_sent_by_viewer.
 */
internal object MessageParseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf(VISUAL_MEDIA_KEY, MEDIA_ITEM_KEY, SENT_BY_VIEWER, "raven_media"),
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

@Suppress("unused")
val keepInChatPatch = bytecodePatch(
    name = "Keep in chat",
    description = "Keeps view once and replayable photos and videos in your chats after you open them, so they " +
        "don't disappear. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("keepInChat")
        val (parse, store) = findViewModeStore()
        val sent = findSentMessage(parse, store)
        val (writer, read) = findViewModeWrite(parse, store)
        keepViewModeInChat(parse, store)
        keepSentMediaAsSent(sent)
        keepServerViewModeInCache(writer, read)
        enableStatus("keepInChat")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$KEEP_IN_CHAT_PATCH: $why")

private fun Instruction.string(): String? =
    if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) ((this as ReferenceInstruction).reference as StringReference).string else null

/**
 * Where the parser of a photo or video message's media stores the view mode it read: the first
 * object store after the parser loads "view_mode", into a text field of the media it builds, and
 * the parser's one store into that field. Nothing may jump straight to the store, which would skip
 * the hook put in front of it.
 */
internal fun BytecodePatchContext.findViewModeStore(): Pair<MutableMethod, Int> {
    val parse = uniqueMethod(KEEP_IN_CHAT_PATCH, "photo and video message parser", VisualMediaParseFingerprint)
    val code = parse.implementation!!.instructions.toList()
    val key = code.indices.filter { code[it].string() == VIEW_MODE }.singleOrNull()
        ?: refuse("the parser doesn't load \"$VIEW_MODE\" once")
    val store = (key + 1 until code.size).firstOrNull { code[it].opcode == Opcode.IPUT_OBJECT }
        ?: refuse("the parser stores no view mode")
    val field = (code[store] as ReferenceInstruction).reference as FieldReference
    if (field.type != "Ljava/lang/String;") refuse("the parser's view mode isn't stored as text")
    val built = code.firstOrNull { it.opcode == Opcode.NEW_INSTANCE }?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
    if (field.definingClass != built) refuse("the view mode goes into ${field.definingClass}, not the media the parser builds")
    val stores = code.count { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference == field }
    if (stores != 1) refuse("the parser stores the view mode $stores times")
    if (store in parse.jumpTargets()) refuse("something jumps straight to the view mode's store")
    if ((code[store] as TwoRegisterInstruction).registerA > 15) refuse("the view mode is past v15")
    if ((code[store] as TwoRegisterInstruction).registerB > 15) refuse("the media is past v15")
    return parse to store
}

/** The view mode and the media that holds it pass through the extension on the way in. */
internal fun keepViewModeInChat(parse: MutableMethod, store: Int) {
    val put = parse.implementation!!.instructions[store] as TwoRegisterInstruction
    val mode = put.registerA
    val media = put.registerB
    parse.addInstructions(
        store,
        """
            invoke-static { v$mode, v$media }, $VIEW_MODE_HOOK
            move-result-object v$mode
        """,
    )
}

/**
 * Where Instagram reads a photo or video message's view mode to write it out, which is how its
 * messages go to its cache: the one static (writer, media) method of the media parser's class
 * that reads the view mode field [findViewModeStore] found, and its one read of it. [parse] and
 * [store] are that parser and its store.
 */
internal fun BytecodePatchContext.findViewModeWrite(parse: MutableMethod, store: Int): Pair<MutableMethod, Int> {
    val field = (parse.implementation!!.instructions.toList()[store] as ReferenceInstruction).reference as FieldReference
    fun reads(instruction: Instruction) =
        instruction.opcode == Opcode.IGET_OBJECT && (instruction as ReferenceInstruction).reference == field
    val writers = mutableClassDefBy(parse.definingClass).methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.size == 2 &&
            method.parameterTypes[1].toString() == field.definingClass && method.implementation?.instructions?.any(::reads) == true
    }
    val writer = writers.singleOrNull()
        ?: refuse("the media parser's class has ${writers.size} methods writing out the view mode, not one")
    val code = writer.implementation!!.instructions.toList()
    val read = code.indices.filter { reads(code[it]) }.singleOrNull()
        ?: refuse("the media writer reads the view mode more than once")
    if (code.none { it.string() == VIEW_MODE }) refuse("the media writer doesn't write \"$VIEW_MODE\"")
    val get = code[read] as TwoRegisterInstruction
    if (get.registerA > 15) refuse("the written view mode is past v15")
    if (get.registerB > 15) refuse("the written media is past v15")
    if (get.registerA == get.registerB) refuse("the written view mode takes the media's register")
    if (read + 1 >= code.size) refuse("the view mode read ends the media writer")
    return writer to read
}

/**
 * The view mode on its way to the cache passes through the extension with its media, so a media
 * the extension kept in the chat is written with the mode the server sent.
 */
internal fun keepServerViewModeInCache(writer: MutableMethod, read: Int) {
    val get = writer.implementation!!.instructions[read] as TwoRegisterInstruction
    writer.addInstructions(
        read + 1,
        """
            invoke-static { v${get.registerA}, v${get.registerB} }, $SAVE_HOOK
            move-result-object v${get.registerA}
        """,
    )
}

/**
 * Where a direct message's parser has stored what the extension needs to leave the media you sent
 * alone: the message, its two photo or video media fields, its sent-by-you flag and its sender's
 * user id, with the reader it parses from, which holds the signed-in account. The four stores come
 * in whatever order the JSON has its keys, so each is followed by a call, and the extension acts
 * once the media and either the flag or the sender are in.
 */
internal class SentMessage(
    val parse: MutableMethod,
    /** Where to call, in descending order: the instruction after each of the four stores. */
    val after: List<Int>,
    val message: Int,
    val messageClass: String,
    val mediaClass: String,
    val visual: FieldReference,
    val item: FieldReference,
    val sent: FieldReference,
    val sender: FieldReference,
    val viewMode: FieldReference,
    val reader: Int,
    val readerClass: String,
    val session: FieldReference,
)

/**
 * The message parser's four stores, found by the keys before them: the first object store after
 * "visual_media" and after "message_item_dict", into a field holding the media class the media
 * parser builds, the first boolean store after "is_sent_by_viewer" and the first text store after
 * "user_id". All four go into the one message the parser builds and returns, kept in a register
 * nothing else writes. The reader is the parser's parameter, cast to the reader class it reads the
 * account (a UserSession) from, in a register nothing but that cast writes. The media class and its
 * superclasses keep Object's equals and hashCode, since the extension remembers media by identity.
 * [mediaParse] and [viewModeStore] are the media parser and its view mode store from [findViewModeStore].
 *
 * Instagram tells a message you sent by its user_id matching the signed-in account's userId. The
 * live copy of one you've just sent doesn't say is_sent_by_viewer, so the flag alone isn't enough (#114).
 */
internal fun BytecodePatchContext.findSentMessage(mediaParse: MutableMethod, viewModeStore: Int): SentMessage {
    val viewMode = (mediaParse.implementation!!.instructions.toList()[viewModeStore] as ReferenceInstruction).reference as FieldReference
    val mediaClass = viewMode.definingClass
    val parse = uniqueMethod(KEEP_IN_CHAT_PATCH, "direct message parser", MessageParseFingerprint)
    val code = parse.implementation!!.instructions.toList()
    fun keyAt(key: String): Int = code.indices.filter { code[it].string() == key }.singleOrNull()
        ?: refuse("the message parser doesn't load \"$key\" once")
    fun storeAfter(key: String, opcode: Opcode): Int = (keyAt(key) + 1 until code.size).firstOrNull { code[it].opcode == opcode }
        ?: refuse("the message parser has no store after \"$key\"")
    val visualStore = storeAfter(VISUAL_MEDIA_KEY, Opcode.IPUT_OBJECT)
    val itemStore = storeAfter(MEDIA_ITEM_KEY, Opcode.IPUT_OBJECT)
    val sentStore = storeAfter(SENT_BY_VIEWER, Opcode.IPUT_BOOLEAN)
    val senderStore = storeAfter(SENDER_KEY, Opcode.IPUT_OBJECT)
    fun field(at: Int) = (code[at] as ReferenceInstruction).reference as FieldReference
    val visual = field(visualStore)
    val item = field(itemStore)
    val sent = field(sentStore)
    val sender = field(senderStore)
    if (visual.type != mediaClass) refuse("the message's $VISUAL_MEDIA_KEY isn't the media the media parser builds")
    if (item.type != mediaClass) refuse("the message's $MEDIA_ITEM_KEY isn't the media the media parser builds")
    if (visual == item) refuse("$VISUAL_MEDIA_KEY and $MEDIA_ITEM_KEY go into one field")
    if (visual.definingClass != item.definingClass) refuse("$VISUAL_MEDIA_KEY and $MEDIA_ITEM_KEY go into different classes")
    if (sent.type != "Z") refuse("the message's $SENT_BY_VIEWER isn't a flag")
    if (sender.type != STRING) refuse("the message's $SENDER_KEY isn't text")

    val built = code.firstOrNull { it.opcode == Opcode.NEW_INSTANCE } as? ReferenceInstruction
    val messageClass = (built?.reference as? TypeReference)?.type ?: refuse("the message parser builds nothing")
    val builtAt = code.indexOf(built as Instruction)
    val message = (code[builtAt] as OneRegisterInstruction).registerA
    if (message > 15) refuse("the message is past v15")
    val stores = listOf(visualStore, itemStore, sentStore, senderStore)
    for (store in stores) {
        if ((code[store] as TwoRegisterInstruction).registerB != message) refuse("a message store goes into another register than the message")
    }
    fun ancestors(type: String) = generateSequence(type) { classDefByOrNull(it)?.superclass }.toList()
    val messageAncestors = ancestors(messageClass)
    for (owner in listOf(visual.definingClass, item.definingClass, sent.definingClass, sender.definingClass)) {
        if (owner !in messageAncestors) refuse("a message field is in $owner, which the message $messageClass isn't")
    }
    // The extension remembers each media it rewrote in a WeakHashMap, which finds a key by its
    // equals and hashCode. Object's compare by identity, so one media never answers for another,
    // but a media class or superclass with its own could hand one media another's view mode.
    for (type in ancestors(mediaClass).takeWhile { it != OBJECT }) {
        val classDef = classDefByOrNull(type) ?: refuse("can't read $type, so can't tell whether the media $mediaClass compares by identity")
        val own = classDef.methods.filter { method ->
            val parameters = method.parameterTypes.map(Any::toString)
            (method.name == "equals" && method.returnType == "Z" && parameters == listOf(OBJECT)) ||
                (method.name == "hashCode" && method.returnType == "I" && parameters.isEmpty())
        }.map { it.name }.sorted()
        if (own.isNotEmpty()) {
            refuse("the media $mediaClass has its own ${own.joinToString(" and ")} in $type, so the extension can't remember it by identity")
        }
    }
    // The register holds the message for the whole parse: only the new-instance writes it, a
    // wide result in the register before it would overwrite it too.
    fun writes(register: Int, except: Opcode? = null) = code.count { instruction ->
        val written = (instruction as? OneRegisterInstruction)?.registerA ?: return@count false
        instruction.opcode != except && ((instruction.opcode.setsRegister() && written == register) ||
            (instruction.opcode.setsWideRegister() && (written == register || written + 1 == register)))
    }
    val messageWrites = writes(message)
    if (messageWrites != 1) refuse("the message register is written $messageWrites times")
    if (!code.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA == message }) {
        refuse("the message parser doesn't return the message")
    }

    // The reader is the one parameter, in the last register, cast in place to the class holding the account.
    val reader = parse.implementation!!.registerCount - 1
    if (reader > 15) refuse("the message parser's reader is past v15")
    val cast = code.firstOrNull { it.opcode == Opcode.CHECK_CAST && (it as OneRegisterInstruction).registerA == reader }
        ?: refuse("the message parser never casts its reader")
    val readerClass = ((cast as ReferenceInstruction).reference as TypeReference).type
    val readerWrites = writes(reader, Opcode.CHECK_CAST)
    if (readerWrites != 0) refuse("the reader register is written $readerWrites times")
    val sessions = code.filter { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT && (instruction as TwoRegisterInstruction).registerB == reader &&
            ((instruction as ReferenceInstruction).reference as FieldReference).type == USER_SESSION
    }.map { (it as ReferenceInstruction).reference as FieldReference }.distinct()
    val session = sessions.singleOrNull()
        ?: refuse("the message parser reads ${sessions.size} accounts from its reader, not one")
    if (session.definingClass !in ancestors(readerClass)) refuse("the reader's account is in ${session.definingClass}, which the reader $readerClass isn't")
    val userSession = classDefByOrNull(USER_SESSION) ?: refuse("there's no $USER_SESSION")
    if (userSession.fields.none { it.name == "userId" && it.type == STRING }) refuse("$USER_SESSION has no userId")

    val after = stores.map { it + 1 }.sortedDescending()
    if (after.any { it >= code.size }) refuse("a message store ends the parser")
    if (after.distinct().size != stores.size) refuse("two message stores are one instruction")
    return SentMessage(parse, after, message, messageClass, mediaClass, visual, item, sent, sender, viewMode, reader, readerClass, session)
}

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * Fills the extension's message stubs with Instagram's own fields, then calls the extension with
 * the message and the reader after each of the four stores. A store's next instruction can be where
 * other paths jump (a skipped null), and those keep their label on it, so only a path that went
 * through the store gets the call.
 */
internal fun BytecodePatchContext.keepSentMediaAsSent(found: SentMessage) {
    val extension = mutableClassDefBy(KEEP_IN_CHAT)
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: refuse("$KEEP_IN_CHAT has no static $returns $name(${parameters.joinToString("")})")
    for (hook in listOf(MESSAGE_HOOK, SAVE_HOOK)) {
        if (extension.methods.none { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ) refuse("the extension has no public static $hook")
    }
    val sentByYou = stub("sentByYou", listOf(OBJECT), "Z")
    val senderId = stub("senderId", listOf(OBJECT), STRING)
    val viewerId = stub("viewerId", listOf(OBJECT), STRING)
    val visualMedia = stub("visualMedia", listOf(OBJECT), OBJECT)
    val itemMedia = stub("itemMedia", listOf(OBJECT), OBJECT)
    val setViewMode = stub("setViewMode", listOf(OBJECT, STRING), "V")

    sentByYou.addInstructions(
        0,
        """
            check-cast p0, ${found.messageClass}
            iget-boolean p0, p0, ${found.sent}
            return p0
        """,
    )
    senderId.addInstructions(
        0,
        """
            check-cast p0, ${found.messageClass}
            iget-object p0, p0, ${found.sender}
            return-object p0
        """,
    )
    // A reader with no account answers null on a path of its own.
    viewerId.addInstructions(
        0,
        """
            check-cast p0, ${found.readerClass}
            iget-object p0, p0, ${found.session}
            if-nez p0, :account
            const/4 p0, 0x0
            return-object p0
            :account
            iget-object p0, p0, $USER_ID
            return-object p0
        """,
    )
    visualMedia.addInstructions(
        0,
        """
            check-cast p0, ${found.messageClass}
            iget-object p0, p0, ${found.visual}
            return-object p0
        """,
    )
    itemMedia.addInstructions(
        0,
        """
            check-cast p0, ${found.messageClass}
            iget-object p0, p0, ${found.item}
            return-object p0
        """,
    )
    setViewMode.addInstructions(
        0,
        """
            check-cast p0, ${found.mediaClass}
            iput-object p1, p0, ${found.viewMode}
            return-void
        """,
    )
    // Highest first, so the earlier indices stay where they were found.
    for (at in found.after) {
        found.parse.addInstructions(
            at,
            "invoke-static { v${found.message}, v${found.reader} }, $MESSAGE_HOOK",
        )
    }
}
