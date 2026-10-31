/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.history

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.direct.keep.findMessageParser
import app.morphe.patches.instagram.direct.lock.ChatPauseFingerprint
import app.morphe.patches.instagram.direct.lock.ChatResumeFingerprint
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val CHAT_HISTORY_PATCH = "Chat history tools"
internal const val CHAT_PAGER = "$EXTENSION_PACKAGE/direct/ChatPager;"
internal const val CHAT_MESSAGES = "$EXTENSION_PACKAGE/direct/ChatMessages;"
internal const val PAGER_OPENED = "$CHAT_PAGER->opened(Ljava/lang/Object;)V"
internal const val PAGER_CLOSED = "$CHAT_PAGER->closed(Ljava/lang/Object;)V"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val ENUM = "Ljava/lang/Enum;"

/** The JSON keys of a direct message the history reads, in the parser that builds it. */
internal const val KEY_ID = "item_id"
internal const val KEY_TIME = "timestamp"
internal const val KEY_SENDER = "user_id"
internal const val KEY_SENT = "is_sent_by_viewer"
internal const val KEY_TEXT = "text"
internal const val KEY_KIND = "item_type"

/** The message class and the fields the extension reads, all in it. [kind] is null when the type isn't an enum. */
internal class MessageFields(
    val owner: String,
    val id: FieldReference,
    val time: FieldReference,
    val sender: FieldReference,
    val sent: FieldReference,
    val text: FieldReference,
    val kind: FieldReference?,
)

internal class ChatHistoryTargets(
    val resume: MutableMethod,
    val pause: MutableMethod,
    val fields: MessageFields,
    val bridges: Map<String, MutableMethod>,
)

private fun refuse(why: String): Nothing = throw PatchException("$CHAT_HISTORY_PATCH: $why")

/**
 * Long press a chat's name to jump to its first message or a date.
 * Starts off. The messages are read from the objects the chat's list holds, so no request is made
 * that Instagram doesn't make itself.
 */
@Suppress("unused")
val chatHistoryPatch = bytecodePatch(
    name = "Chat history tools",
    description = "Long press a chat's name to jump to its first message or to a date, or to save the chat as a " +
        "text file. The chat loads its older messages on its own, with a count and a Cancel button. Both start off. " +
        "Turn them on in HushGram settings > Messages.",
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("chatHistory")
        // Every finder refuses before any code changes.
        val found = findChatHistory()
        writeChatHistory(found)
        enableStatus("chatHistory")
    }
}

/** The chat screen's two lifecycle methods, the parser's stores and the extension's bridges, all proved. */
internal fun BytecodePatchContext.findChatHistory(): ChatHistoryTargets {
    val resume = uniqueMethod(CHAT_HISTORY_PATCH, "chat screen onResume", ChatResumeFingerprint)
    val pause = uniqueMethod(CHAT_HISTORY_PATCH, "chat screen onPause", ChatPauseFingerprint)
    if (resume.definingClass != pause.definingClass) refuse("the chat screen's onResume and onPause are in different classes")
    for (method in listOf(resume, pause)) {
        if (0 in method.jumpTargets()) refuse("something jumps back to the chat screen's ${method.name} first instruction")
    }
    val fields = findMessageFields()
    val extension = classDefByOrNull(CHAT_MESSAGES) ?: refuse("the extension has no $CHAT_MESSAGES")
    val pager = classDefByOrNull(CHAT_PAGER) ?: refuse("the extension has no $CHAT_PAGER")
    for (hook in listOf("opened", "closed")) {
        pager.methods.singleOrNull {
            it.name == hook && it.parameterTypes.map(Any::toString) == listOf(OBJECT) && it.returnType == "V" &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("the extension has no public static $hook")
    }
    val stubs = mutableClassDefBy(extension).methods
    val wanted = mapOf(
        "isMessage" to "Z", "id" to STRING, "sender" to STRING, "text" to STRING, "time" to STRING, "kind" to STRING,
        "sentByViewer" to "Z",
    )
    val bridges = wanted.mapValues { (name, returns) ->
        stubs.singleOrNull {
            it.name == name && it.parameterTypes.map(Any::toString) == listOf(OBJECT) && it.returnType == returns &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("the extension has no public static $name bridge")
    }
    return ChatHistoryTargets(resume, pause, fields, bridges)
}

/** Puts the two hooks in and writes the bridge bodies. */
internal fun writeChatHistory(found: ChatHistoryTargets) {
    val f = found.fields
    found.resume.addInstructions(0, "invoke-static/range { p0 .. p0 }, $PAGER_OPENED")
    found.pause.addInstructions(0, "invoke-static/range { p0 .. p0 }, $PAGER_CLOSED")
    fun body(name: String, text: String) = found.bridges.getValue(name).addInstructionsWithLabels(0, text.trimIndent())
    body("isMessage", """
        instance-of p0, p0, ${f.owner}
        return p0
    """)
    for ((name, field) in listOf("id" to f.id, "sender" to f.sender, "text" to f.text, "time" to f.time)) {
        body(name, """
            check-cast p0, ${f.owner}
            iget-object p0, p0, ${field.definingClass}->${field.name}:${field.type}
            return-object p0
        """)
    }
    body("sentByViewer", """
        check-cast p0, ${f.owner}
        iget-boolean p0, p0, ${f.sent.definingClass}->${f.sent.name}:Z
        return p0
    """)
    val kind = f.kind
    if (kind == null) {
        body("kind", """
            const/4 p0, 0x0
            return-object p0
        """)
    } else {
        body("kind", """
            check-cast p0, ${f.owner}
            iget-object p0, p0, ${kind.definingClass}->${kind.name}:${kind.type}
            if-nez p0, :named
            const/4 p0, 0x0
            return-object p0
            :named
            invoke-virtual { p0 }, $ENUM->name()Ljava/lang/String;
            move-result-object p0
            return-object p0
        """)
    }
}

/**
 * The fields a direct message keeps what the history reads in, found in the message parser by the
 * keys it reads them under. A key's field is the first object store after the key's test: the
 * store itself when the test skips it, the target of the branch when the test jumps to a store
 * shared with another key (text, and the type). All of them sit in one public class.
 */
internal fun BytecodePatchContext.findMessageFields(): MessageFields {
    val parse = findMessageParser()
    val code = parse.implementation?.instructions?.toList() ?: refuse("the message parser has no code")
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    fun keyAt(key: String): Int = code.indices.filter { stringLoadedAt(code, it) == key }.singleOrNull()
        ?: refuse("the message parser doesn't load \"$key\" once")
    fun field(at: Int) = (code[at] as ReferenceInstruction).reference as FieldReference
    fun isStore(instruction: Instruction, store: Opcode) = instruction.opcode == store
    fun storeFrom(from: Int, store: Opcode, key: String): FieldReference {
        val end = minOf(code.size, from + 12)
        for (at in from until end) {
            if (isStore(code[at], store)) return field(at)
            if (code[at].opcode == Opcode.GOTO || code[at].opcode == Opcode.GOTO_16 || code[at].opcode == Opcode.GOTO_32) break
        }
        refuse("the message parser has no store for \"$key\"")
    }
    fun storeFor(key: String, store: Opcode): FieldReference {
        val at = keyAt(key)
        val branch = (at + 1 until minOf(code.size, at + 8)).firstOrNull {
            code[it].opcode == Opcode.IF_NEZ || code[it].opcode == Opcode.IF_EQZ
        } ?: refuse("the message parser's \"$key\" test isn't followed by a branch")
        if (code[branch].opcode == Opcode.IF_EQZ) return storeFrom(branch + 1, store, key)
        val target = address.indexOf(address[branch] + (code[branch] as OffsetInstruction).codeOffset)
        if (target < 0) refuse("the message parser's \"$key\" branch lands between instructions")
        return storeFrom(target, store, key)
    }
    val id = storeFor(KEY_ID, Opcode.IPUT_OBJECT)
    val time = storeFor(KEY_TIME, Opcode.IPUT_OBJECT)
    val sender = storeFor(KEY_SENDER, Opcode.IPUT_OBJECT)
    val sent = storeFor(KEY_SENT, Opcode.IPUT_BOOLEAN)
    val text = storeFor(KEY_TEXT, Opcode.IPUT_OBJECT)
    val kind = storeFor(KEY_KIND, Opcode.IPUT_OBJECT)
    for ((key, field) in listOf(KEY_ID to id, KEY_TIME to time, KEY_SENDER to sender, KEY_TEXT to text)) {
        if (field.type != STRING) refuse("the message's $key isn't text")
    }
    if (sent.type != "Z") refuse("the message's $KEY_SENT isn't a flag")
    val owner = id.definingClass
    for (field in listOf(time, sender, sent, text, kind)) {
        if (field.definingClass != owner) refuse("the message keeps ${field.name} in ${field.definingClass}, not in $owner")
    }
    val ownerClass = classDefByOrNull(owner) ?: refuse("$owner isn't in this build")
    if (!AccessFlags.PUBLIC.isSet(ownerClass.accessFlags)) refuse("$owner isn't public")
    for (field in listOfNotNull(id, time, sender, sent, text, kind)) {
        val declared = ownerClass.fields.firstOrNull { it.name == field.name && it.type == field.type }
            ?: refuse("$owner doesn't declare ${field.name}")
        if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
            refuse("${field.name} isn't a public instance field, so the extension can't reach it")
        }
    }
    val kindClass = classDefByOrNull(kind.type)
    val kindField = kind.takeIf { kindClass != null && kindClass.superclass == ENUM && AccessFlags.PUBLIC.isSet(kindClass.accessFlags) }
    return MessageFields(owner, id, time, sender, sent, text, kindField)
}
