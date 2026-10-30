/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.loadsConstString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal const val REPLIED = "$THREAD_SEEN->replied(Ljava/lang/Object;Ljava/lang/Object;I)V"

private const val EDITABLE = "Landroid/text/Editable;"
private const val LONG_TYPE = "Ljava/lang/Long;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/** What the composer's send logs, and what its scheduled send logs, which only that method holds together. */
private const val COMPOSER_SEND = "direct_composer_send_text"
private const val COMPOSER_SCHEDULE = "direct_schedule_message"
private const val OPEN_THREAD = "open_thread_id"

/** The composer's send: takes the typed text, a time and a string, answers whether it went. */
internal object ComposerSendFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string(COMPOSER_SEND)),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(Any::toString) == listOf(EDITABLE, LONG_TYPE, STRING) &&
            method.loadsConstString(COMPOSER_SCHEDULE)
    },
)

/** What Mark a chat seen when you reply needs from this build, resolved before any edit. */
internal data class ReplySeenTargets(
    val send: MutableMethod,
    /** The return that answers the send, and the register it answers from. */
    val returnAt: Int,
    val answer: Int,
    /** Where the composer keeps the account and the object it names its chat by. */
    val session: FieldReference,
    val source: FieldReference,
    val scratch: List<Int>,
    val bridges: List<ChatBridge>,
)

private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Method.static() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun ClassDef.publicClass() = AccessFlags.PUBLIC.isSet(accessFlags)

/**
 * Resolves the composer's send, the field it keeps the account in, the field it keeps its chat's
 * handle in and the static call that reads a thread id off that handle, which the send itself logs
 * as open_thread_id. Null when this build's composer doesn't show them, which leaves the switch
 * with nothing to hook: the receipt then stays held, as it does when a reply can't be matched.
 * Throws when two methods answer for the composer.
 */
internal fun BytecodePatchContext.findReplySeen(seen: ThreadSeenTargets): ReplySeenTargets? {
    val matches = ComposerSendFingerprint.matchAllOrNull().orEmpty()
    if (matches.isEmpty()) return null
    if (matches.size != 1) {
        throw app.morphe.patcher.patch.PatchException(
            "$THREAD_SEEN_PATCH: expected one composer send, found ${matches.joinToString { it.originalMethod.definingClass }}",
        )
    }
    val send = matches.single().method
    val classes = HashMap<String, ClassDef>()
    classDefForEach { classes[it.type] = it }
    val owner = classes[send.definingClass] ?: return null
    val code = send.implementation?.instructions?.toList() ?: return null

    // The thread id read off the composer's chat handle: a static call answering a String, whose
    // result the send logs right after as open_thread_id.
    val reads = code.indices.mapNotNull { at ->
        val name = code[at]
        if (name.opcode != Opcode.CONST_STRING && name.opcode != Opcode.CONST_STRING_JUMBO) return@mapNotNull null
        if (((name as ReferenceInstruction).reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string != OPEN_THREAD) {
            return@mapNotNull null
        }
        if (at < 2 || code[at - 1].opcode != Opcode.MOVE_RESULT_OBJECT || code[at - 2].opcode != Opcode.INVOKE_STATIC) return@mapNotNull null
        code[at - 2].call()?.takeIf { it.parameterTypes.size == 1 && it.returnType == STRING && it.parameterTypes[0].startsWith("L") }
    }.distinctBy { it.key() }
    val read = reads.singleOrNull() ?: return null
    val handle = read.parameterTypes.single().toString()
    val source = owner.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == handle }.singleOrNull() ?: return null
    val session = owner.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == USER_SESSION }.singleOrNull() ?: return null
    val readOwner = classes[read.definingClass] ?: return null
    val readMethod = readOwner.methods.firstOrNull { it.key() == read.key() } ?: return null
    if (!readOwner.publicClass() || !AccessFlags.PUBLIC.isSet(readMethod.accessFlags) || !readMethod.static()) return null
    val handleClass = classes[handle] ?: return null
    if (!handleClass.publicClass()) return null

    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN }
    val returnAt = returns.lastOrNull() ?: return null
    val answer = (code[returnAt] as OneRegisterInstruction).registerA
    send.requireThisIntact(THREAD_SEEN_PATCH, listOf(returnAt))
    val scratch = send.freeLocalsAt(THREAD_SEEN_PATCH, returnAt, 3)

    val extension = classes[THREAD_SEEN] ?: return null
    requireHook(extension, "replied", listOf(OBJECT, OBJECT, "I"), "V")
    val bridges = mutableListOf<ChatBridge>()
    bridges += chatBridges(listOf(
        Triple("composerThread", listOf(OBJECT), STRING) to """
            check-cast p0, $handle
            invoke-static { p0 }, ${read.key()}
            move-result-object p0
            return-object p0
        """,
    ))
    receiptSender(seen, classes)?.let { field ->
        bridges += chatBridges(listOf(
            Triple("receiptSender", listOf(OBJECT), STRING) to """
                check-cast p0, ${seen.mutation}
                iget-object p0, p0, $field
                return-object p0
            """,
        ))
    }
    // Without the sender the receipt can't be sent again, so no reply may let it go.
    if (bridges.size != 2) return null
    return ReplySeenTargets(send, returnAt, answer, session, source, scratch, bridges)
}

/**
 * The field of the receipt mutation Instagram's live sender stores its fifth argument, the id of
 * the message's sender, in. Null unless that argument goes straight into one public String field of
 * the receipt's class.
 */
private fun receiptSender(seen: ThreadSeenTargets, classes: Map<String, ClassDef>): FieldReference? {
    val creator = seen.creator
    val code = creator.implementation?.instructions?.toList() ?: return null
    if (creator.parameterTypes.size != 5) return null
    val sender = creator.parameterRegisterNumber(4)
    // Instagram copies the argument into a scratch register before it stores it, so the stored
    // register counts when it is the parameter itself or the one copy of it that nothing rewrote.
    fun holdsSender(put: Int, register: Int): Boolean {
        if (register == sender) return code.subList(0, put).none { it.writes(sender) }
        val copyAt = code.indices.lastOrNull { at ->
            at < put && code[at].writes(register)
        } ?: return false
        val copy = code[copyAt]
        if (copy.opcode != Opcode.MOVE_OBJECT && copy.opcode != Opcode.MOVE_OBJECT_FROM16 && copy.opcode != Opcode.MOVE_OBJECT_16) return false
        if ((copy as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerB != sender) return false
        return code.subList(0, copyAt).none { it.writes(sender) }
    }
    val puts = code.indices.filter { at ->
        val put = code[at]
        put.opcode == Opcode.IPUT_OBJECT && put.field()?.let { it.definingClass == seen.mutation && it.type == STRING } == true &&
            holdsSender(at, (put as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA)
    }
    val at = puts.singleOrNull() ?: return null
    val field = code[at].field() ?: return null
    val owner = classes[seen.mutation] ?: return null
    val declared = owner.fields.firstOrNull { it.name == field.name && it.type == field.type } ?: return null
    if (!owner.publicClass() || !AccessFlags.PUBLIC.isSet(declared.accessFlags)) return null
    return field
}

/**
 * After the composer answers that a message went, hands the extension the account, the chat handle
 * and the answer, so it can let the chat's held receipt through.
 */
internal fun writeReplySeen(found: ReplySeenTargets) {
    val send = found.send
    val (a, b, c) = found.scratch
    val self = send.localRegisterCount()
    send.addInstructionsAtControlFlowLabel(
        found.returnAt,
        """
            move-object/from16 v$a, v$self
            iget-object v$b, v$a, ${found.session}
            iget-object v$a, v$a, ${found.source}
            move/from16 v$c, v${found.answer}
            invoke-static { v$b, v$a, v$c }, $REPLIED
        """,
    )
    found.bridges.forEach { it.stub.addInstructions(0, it.body) }
}

/** Whether this instruction puts a new value into the register (a rough test: any first-register write). */
private fun Instruction.writes(register: Int): Boolean {
    if (this !is OneRegisterInstruction || registerA != register) return false
    return when (opcode) {
        Opcode.IPUT, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT, Opcode.IPUT_WIDE,
        Opcode.SPUT, Opcode.SPUT_OBJECT, Opcode.SPUT_BOOLEAN, Opcode.SPUT_WIDE, Opcode.APUT, Opcode.APUT_OBJECT,
        Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE, Opcode.IF_EQZ, Opcode.IF_NEZ,
        Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.THROW, Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT, Opcode.CHECK_CAST -> false
        else -> true
    }
}
