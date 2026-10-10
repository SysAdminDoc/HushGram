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
internal const val MESSAGE_HOOK = "$KEEP_IN_CHAT->messageRead(Ljava/lang/Object;)V"

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

/** The keys of a direct message's JSON that hold its photo or video media and whether you sent it. */
internal const val VISUAL_MEDIA_KEY = "visual_media"
internal const val MEDIA_ITEM_KEY = "message_item_dict"
internal const val SENT_BY_VIEWER = "is_sent_by_viewer"

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
        keepViewModeInChat(parse, store)
        keepSentMediaAsSent(sent)
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
 * Where a direct message's parser has stored what the extension needs to leave the media you sent
 * alone: the message, its two photo or video media fields and its sent-by-you flag. The three stores
 * come in whatever order the JSON has its keys, so each is followed by a call, and the extension
 * acts when the last of them is in.
 */
internal class SentMessage(
    val parse: MutableMethod,
    /** Where to call, in descending order: the instruction after each of the three stores. */
    val after: List<Int>,
    val message: Int,
    val messageClass: String,
    val mediaClass: String,
    val visual: FieldReference,
    val item: FieldReference,
    val sent: FieldReference,
    val viewMode: FieldReference,
)

/**
 * The message parser's three stores, found by the keys before them: the first object store after
 * "visual_media" and after "message_item_dict", into a field holding the media class the media
 * parser builds, and the first boolean store after "is_sent_by_viewer". All three go into the one
 * message the parser builds and returns, kept in a register nothing else writes. [mediaParse] and
 * [viewModeStore] are the media parser and its view mode store from [findViewModeStore].
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
    fun field(at: Int) = (code[at] as ReferenceInstruction).reference as FieldReference
    val visual = field(visualStore)
    val item = field(itemStore)
    val sent = field(sentStore)
    if (visual.type != mediaClass) refuse("the message's $VISUAL_MEDIA_KEY isn't the media the media parser builds")
    if (item.type != mediaClass) refuse("the message's $MEDIA_ITEM_KEY isn't the media the media parser builds")
    if (visual == item) refuse("$VISUAL_MEDIA_KEY and $MEDIA_ITEM_KEY go into one field")
    if (visual.definingClass != item.definingClass) refuse("$VISUAL_MEDIA_KEY and $MEDIA_ITEM_KEY go into different classes")
    if (sent.type != "Z") refuse("the message's $SENT_BY_VIEWER isn't a flag")

    val built = code.firstOrNull { it.opcode == Opcode.NEW_INSTANCE } as? ReferenceInstruction
    val messageClass = (built?.reference as? TypeReference)?.type ?: refuse("the message parser builds nothing")
    val builtAt = code.indexOf(built as Instruction)
    val message = (code[builtAt] as OneRegisterInstruction).registerA
    if (message > 15) refuse("the message is past v15")
    for (store in listOf(visualStore, itemStore, sentStore)) {
        if ((code[store] as TwoRegisterInstruction).registerB != message) refuse("a message store goes into another register than the message")
    }
    val ancestors = generateSequence(messageClass) { type -> classDefByOrNull(type)?.superclass }.toList()
    for (owner in listOf(visual.definingClass, item.definingClass, sent.definingClass)) {
        if (owner !in ancestors) refuse("a message field is in $owner, which the message $messageClass isn't")
    }
    // The register holds the message for the whole parse: only the new-instance writes it, a
    // wide result in the register before it would overwrite it too.
    val writes = code.count { instruction ->
        val register = (instruction as? OneRegisterInstruction)?.registerA ?: return@count false
        (instruction.opcode.setsRegister() && register == message) ||
            (instruction.opcode.setsWideRegister() && (register == message || register + 1 == message))
    }
    if (writes != 1) refuse("the message register is written $writes times")
    if (!code.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA == message }) {
        refuse("the message parser doesn't return the message")
    }
    val after = listOf(visualStore, itemStore, sentStore).map { it + 1 }.sortedDescending()
    if (after.any { it >= code.size }) refuse("a message store ends the parser")
    if (after.distinct().size != 3) refuse("two message stores are one instruction")
    return SentMessage(parse, after, message, messageClass, mediaClass, visual, item, sent, viewMode)
}

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * Fills the extension's message stubs with Instagram's own fields, then calls the extension after
 * each of the three stores. A store's next instruction can be where other paths jump (a skipped
 * null), and those keep their label on it, so only a path that went through the store gets the call.
 */
internal fun BytecodePatchContext.keepSentMediaAsSent(found: SentMessage) {
    val extension = mutableClassDefBy(KEEP_IN_CHAT)
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: refuse("$KEEP_IN_CHAT has no static $returns $name(${parameters.joinToString("")})")
    if (extension.methods.none { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == MESSAGE_HOOK &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
    ) refuse("the extension has no public static $MESSAGE_HOOK")
    val sentByYou = stub("sentByYou", listOf(OBJECT), "Z")
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
            "invoke-static { v${found.message} }, $MESSAGE_HOOK",
        )
    }
}
