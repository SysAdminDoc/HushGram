/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.saved

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.privacy.code
import app.morphe.patches.instagram.privacy.describe
import app.morphe.patches.instagram.privacy.exactlyOne
import app.morphe.patches.instagram.privacy.fieldReference
import app.morphe.patches.instagram.privacy.methodReference
import app.morphe.patches.instagram.privacy.methodsHolding
import app.morphe.patches.instagram.privacy.mutable
import app.morphe.patches.instagram.privacy.refuse
import app.morphe.patches.instagram.privacy.stringLoaded
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Save deleted messages"
private const val SAVED = "$EXTENSION_PACKAGE/direct/SavedMessages;"
internal const val PARSED = "$SAVED->parsed(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val HIDDEN = "$SAVED->hidden(Ljava/lang/String;Ljava/lang/String;)V"

/** The names a direct message's JSON carries, which Instagram keeps, and the log strings around it. */
internal const val ITEM_ID = "item_id"
internal const val USER_ID = "user_id"
internal const val TIMESTAMP = "timestamp"
internal const val TEXT = "text"
internal const val HIDE_IN_THREAD = "hide_in_thread"
internal const val SENT_BY_VIEWER = "is_sent_by_viewer"
internal const val THREAD_KEY = "thread_key"
internal const val ITEM_TYPE = "item_type"
internal val MESSAGE_KEYS = arrayOf(ITEM_ID, HIDE_IN_THREAD, THREAD_KEY, "client_context")

internal val LIVE_MESSAGE = arrayOf("DirectMessage.postprocess.%s", "Encountered DirectMessage with null type")
internal const val HIDE_BY_ID = "Both message ID and client context is null."
private const val THREAD_KEY_TYPE = "Lcom/instagram/model/direct/DirectThreadKey;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

@Suppress("unused")
val saveDeletedMessagesPatch = bytecodePatch(
    name = "Save deleted messages",
    description = "Keeps the text of messages other people send you on this phone, and marks the ones they later " +
        "delete, so you can read them in HushGram's settings. Nothing leaves the phone, and only text is kept. " +
        "Has its own switch in HushGram's settings.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("deletedMessages")
        // Everything is found before anything is changed.
        val parser = findParser()
        val live = findLiveMessage(parser)
        val hide = findHide()
        keepParsed(parser)
        keepLive(live, parser.layout)
        reportHidden(hide)
        enableStatus("deletedMessages")
    }
}

/** The JSON parser: its method, the register of the message it returns, the return, and what names its fields. */
internal class ParserSite(
    val method: Method,
    val message: Int,
    val returnAt: Int,
    val layout: String,
    val messageType: String,
    val itemType: String,
)

/**
 * The parser that reads a direct message from its JSON is the one method that loads all of
 * [MESSAGE_KEYS] and answers an object. For each key it tests with `String.equals`, the code that
 * handles it, right after the test or at the branch's target, stores the value in a field of the
 * message under construction; those fields are what the layout names. The message returns in one
 * place, where the register it was built in is returned.
 */
internal fun BytecodePatchContext.findParser(): ParserSite {
    val method = methodsHolding(*MESSAGE_KEYS).filter {
        it.returnType == OBJECT && it.parameterTypes.size == 1 && it.name.contains("parseFromJson", ignoreCase = true)
    }.exactlyOne(PATCH, "parser of a direct message")
    val code = method.code()
    val id = method.fieldHandling(ITEM_ID)
    val message = id.register
    fun field(key: String) = method.fieldHandling(key).also {
        if (it.register != message) refuse(PATCH, "\"$key\" is stored in another object than \"$ITEM_ID\" in ${method.describe()}")
    }
    val user = field(USER_ID)
    val time = field(TIMESTAMP)
    val text = field(TEXT)
    val hide = field(HIDE_IN_THREAD)
    val mine = field(SENT_BY_VIEWER)
    val thread = field(THREAD_KEY)
    val type = field(ITEM_TYPE)

    val base = id.name.substringBefore("->")
    val created = code.firstNotNullOfOrNull { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? TypeReference
        reference?.type?.takeIf { instruction.opcode == Opcode.NEW_INSTANCE && classDefByOrNull(it)?.superclass == base }
    } ?: refuse(PATCH, "${method.describe()} never makes a message of the class it fills in")
    val content = contentField(created, type.typeOf())

    val returns = code.indices.filter {
        code[it].opcode == Opcode.RETURN_OBJECT && (code[it] as OneRegisterInstruction).registerA == message
    }
    val returnAt = returns.exactlyOne(PATCH, "return of the parsed message in ${method.describe()}")
    val layout = listOf(
        "i" to id, "u" to user, "t" to time, "x" to text, "h" to hide, "m" to mine, "k" to thread,
    ).joinToString(";") { (letter, field) -> "$letter=${field.fieldName()}" } + ";c=$content;d=${threadIdField()}"
    return ParserSite(method, message, returnAt, layout, created, type.typeOf())
}

/** The field a key's value was stored in, the name of its type and the register of the object it went in. */
internal class Handled(val name: String, val register: Int)

internal fun Handled.fieldName(): String = name.substringAfter("->").substringBefore(":")
internal fun Handled.typeOf(): String = name.substringAfter(":")

internal fun Method.fieldHandling(key: String): Handled {
    val code = code()
    val starts = IntArray(code.size + 1)
    for (index in code.indices) starts[index + 1] = starts[index] + code[index].codeUnits
    val checks = code.indices.filter { index ->
        code[index].stringLoaded() == key && code.getOrNull(index + 1)?.methodReference()?.name == "equals" &&
            code.getOrNull(index + 2)?.opcode == Opcode.MOVE_RESULT &&
            code.getOrNull(index + 3)?.opcode.let { it == Opcode.IF_EQZ || it == Opcode.IF_NEZ }
    }
    val at = checks.exactlyOne(PATCH, "test of \"$key\" in ${describe()}")
    val test = code[at + 3]
    val handler = if (test.opcode == Opcode.IF_EQZ) at + 4 else {
        val target = starts[at + 3] + (test as OffsetInstruction).codeOffset
        starts.indexOf(target).also { if (it < 0 || it >= code.size) refuse(PATCH, "the branch for \"$key\" in ${describe()} goes nowhere") }
    }
    for (index in handler until minOf(code.size, handler + 14)) {
        val instruction = code[index]
        if (instruction.opcode.name.startsWith("iput")) {
            val field = instruction.fieldReference()!!
            return Handled("${field.definingClass}->${field.name}:${field.type}", (instruction as TwoRegisterInstruction).registerB)
        }
        if (instruction.opcode.name.startsWith("goto") || instruction.opcode.name.startsWith("return")) break
    }
    refuse(PATCH, "the code for \"$key\" in ${describe()} stores no field")
}

/**
 * The thread id field of a thread key. The key's `toString` writes "mThreadId" and then "mThreadV2Id", and
 * reads the id fields it prints in that order, so the first string field it reads is the thread's id.
 */
internal fun BytecodePatchContext.threadIdField(): String {
    val key = classDefByOrNull(THREAD_KEY_TYPE) ?: refuse(PATCH, "$THREAD_KEY_TYPE isn't in this build")
    val printer = key.methods.filter { it.name == "toString" && it.parameterTypes.isEmpty() }
        .exactlyOne(PATCH, "toString of $THREAD_KEY_TYPE")
    val code = printer.code()
    if (code.none { it.stringLoaded()?.contains("mThreadId") == true }) {
        refuse(PATCH, "${printer.describe()} doesn't print a mThreadId")
    }
    val read = code.firstNotNullOfOrNull { instruction ->
        instruction.fieldReference()?.takeIf {
            instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == THREAD_KEY_TYPE && it.type == STRING
        }
    } ?: refuse(PATCH, "${printer.describe()} reads no string field")
    return read.name
}

/**
 * The field of [messageType] real-time messages keep what they say in: the one object field its
 * method that takes the item type enum puts a value in right after calling the type's setter.
 */
internal fun BytecodePatchContext.contentField(messageType: String, itemType: String): String {
    val classDef = classDefByOrNull(messageType) ?: refuse(PATCH, "$messageType isn't in this build")
    val fields = classDef.methods.flatMap { method ->
        val code = method.code()
        code.indices.mapNotNull { index ->
            val called = code[index].methodReference()
            if (called?.definingClass != messageType || called.parameterTypes.map(CharSequence::toString) != listOf(itemType)) return@mapNotNull null
            (index + 1 until minOf(code.size, index + 5)).firstNotNullOfOrNull { next ->
                val put = code[next]
                put.fieldReference()?.takeIf {
                    put.opcode == Opcode.IPUT_OBJECT && it.type == OBJECT && it.definingClass == messageType
                }?.name
            }
        }
    }.distinct()
    return fields.exactlyOne(PATCH, "field holding a real-time message's content in $messageType")
}

internal class LiveSite(val method: Method, val returns: List<Int>, val self: Int)

/**
 * The method that finishes a message arriving in real time loads both [LIVE_MESSAGE] strings and
 * answers the message class the parser builds. It returns the message it was called on, in each
 * place its register is returned.
 */
internal fun BytecodePatchContext.findLiveMessage(parser: ParserSite): LiveSite {
    val method = methodsHolding(*LIVE_MESSAGE).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.definingClass == parser.messageType && it.returnType == parser.messageType
    }.exactlyOne(PATCH, "method finishing a real-time message")
    val self = method.localRegisterCount()
    val code = method.code()
    val returns = code.indices.filter {
        code[it].opcode == Opcode.RETURN_OBJECT && (code[it] as OneRegisterInstruction).registerA == self
    }
    if (returns.isEmpty()) refuse(PATCH, "${method.describe()} never returns the message it was called on")
    return LiveSite(method, returns, self)
}

internal fun BytecodePatchContext.findHide(): Method =
    methodsHolding(HIDE_BY_ID).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(THREAD_KEY_TYPE, STRING, STRING)
    }.exactlyOne(PATCH, "method hiding a message by its id")

/**
 * The return stays the return: its register is copied to a local, the layout is loaded into another,
 * both go to the extension, and the message is returned as before. A jump to the old return lands on
 * the first of these.
 */
private fun BytecodePatchContext.hookReturn(method: Method, returnAt: Int, register: Int, layout: String) {
    val mutable = mutable(method)
    val (copy, text) = mutable.freeLocalsAt(PATCH, returnAt, 2)
    mutable.replaceInstruction(returnAt, "move-object/from16 v$copy, v$register")
    mutable.addInstructions(
        returnAt + 1,
        """
            const-string v$text, "$layout"
            invoke-static { v$copy, v$text }, $PARSED
            return-object v$register
        """,
    )
}

internal fun BytecodePatchContext.keepParsed(site: ParserSite) = hookReturn(site.method, site.returnAt, site.message, site.layout)

internal fun BytecodePatchContext.keepLive(site: LiveSite, layout: String) {
    // From the last return to the first, so an earlier index doesn't move under a later edit.
    for (returnAt in site.returns.sortedDescending()) hookReturn(site.method, returnAt, site.self, layout)
}

internal fun BytecodePatchContext.reportHidden(method: Method) {
    val mutable = mutable(method)
    mutable.requireLocals(PATCH, 2)
    if (0 in mutable.jumpTargets()) refuse(PATCH, "something jumps to the start of ${mutable.describe()}")
    mutable.addInstructions(
        0,
        """
            move-object/from16 v0, ${mutable.parameterRegister(1)}
            move-object/from16 v1, ${mutable.parameterRegister(2)}
            invoke-static { v0, v1 }, $HIDDEN
        """,
    )
}
