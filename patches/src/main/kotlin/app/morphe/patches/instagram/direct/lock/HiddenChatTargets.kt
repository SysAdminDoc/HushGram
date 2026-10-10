/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val HIDDEN_CHATS = "$EXTENSION_PACKAGE/direct/HiddenChats;"
internal const val HIDDEN_FILTER = "$HIDDEN_CHATS->filter(Ljava/util/ArrayList;)Ljava/util/ArrayList;"
private const val HIDDEN_THREAD_ID = "threadId"

/**
 * What Instagram's thread store logs around both methods that hand the inbox its thread summaries:
 * the one that takes a filter and a sort, and the one the others call with a list of thread kinds.
 */
internal const val THREAD_SUMMARIES = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries"

private fun refuse(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")

private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val STRING = "Ljava/lang/String;"

/** The store's readers of the sorted thread summaries: each logs [THREAD_SUMMARIES] and answers a new list. */
internal object ThreadSummariesFingerprint : Fingerprint(
    returnType = ARRAY_LIST,
    strings = listOf(THREAD_SUMMARIES),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** A reader of the summaries and the register its one return hands back. */
internal class SummaryList(val method: MutableMethod, val returnAt: Int, val register: Int)

/**
 * What hiding chats from the inbox needs from Instagram, proved before anything changes: the two
 * readers of the store's sorted thread summaries and where each returns its list, and the body of
 * the extension's bridge from a summary to its chat's thread id.
 */
internal class HiddenChatTargets(
    val lists: List<SummaryList>,
    val bridge: MutableMethod,
    val bridgeBody: String,
)

/**
 * The store's two readers by what they log, and the summary type by what they read: the one type
 * whose fields they load that answers the chat's key, with the chat key's own thread id. Each reader
 * must return in exactly one place, so the filter sits on the only way the list leaves.
 */
internal fun BytecodePatchContext.findHiddenChatTargets(): HiddenChatTargets {
    val readers = ThreadSummariesFingerprint.matchAllOrNull().orEmpty().map { it.method }
    if (readers.size != 2) {
        refuse("expected two readers of the thread store's sorted summaries, found ${readers.size}")
    }
    if (readers.map { it.definingClass }.distinct().size != 1) {
        refuse("the thread store's two summary readers are in different classes")
    }
    val lists = readers.map { reader ->
        val code = reader.visualCode()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        if (returns.size != 1) refuse("${reader.definingClass}->${reader.name} returns its list in ${returns.size} places, expected one")
        val register = (code[returns.single()] as OneRegisterInstruction).registerA
        if (register > 255) refuse("${reader.definingClass}->${reader.name} returns its list from v$register, past v255")
        SummaryList(reader, returns.single(), register)
    }

    val keyClass = classDefByOrNull(THREAD_KEY) ?: refuse("$THREAD_KEY is missing")
    val idField = threadIdField(keyClass)
    val summaryTypes = readers.flatMap { reader ->
        reader.visualCode().mapNotNull { (it.visualReference() as? FieldReference)?.type }
    }.distinct().filter { type -> classDefByOrNull(type)?.let { keyGetters(it).isNotEmpty() } == true }
    val summaryType = summaryTypes.one("kind of thread summary the store's readers load")
    val summary = classDefByOrNull(summaryType) ?: refuse("$summaryType is missing")
    val getter = keyGetters(summary).one("call answering the chat's key on $summaryType")
    requirePublic(summaryType, getter)
    requirePublic(THREAD_KEY, idField)

    val bridge = mutableClassDefBy(HIDDEN_CHATS).methods.filter {
        it.name == HIDDEN_THREAD_ID && it.parameters() == listOf("Ljava/lang/Object;") && it.returnType == STRING &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.one("extension's thread summary bridge")
    val call = if (AccessFlags.INTERFACE.isSet(summary.accessFlags)) "invoke-interface" else "invoke-virtual"
    val bridgeBody = """
        check-cast p0, $summaryType
        $call { p0 }, ${getter.signature()}
        move-result-object p0
        if-eqz p0, :none
        iget-object p0, p0, ${idField.signature()}
        :none
        return-object p0
    """.trimIndent()
    requireFilter()
    return HiddenChatTargets(lists, bridge, bridgeBody)
}

/** The no-argument, non-static calls of [type] that answer a chat key. */
private fun keyGetters(type: ClassDef): List<Method> = type.methods.filter {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == THREAD_KEY
}

/**
 * Writes the thread summary bridge and puts the filter in. Each reader's return is replaced rather
 * than preceded, because a jump straight to it would skip anything put in front: the list goes
 * through the extension and comes back in the same register.
 */
internal fun hideChatsFromInbox(targets: HiddenChatTargets) {
    targets.bridge.addInstructionsWithLabels(0, targets.bridgeBody)
    targets.lists.forEach { list ->
        val register = "v${list.register}"
        list.method.replaceInstruction(list.returnAt, "invoke-static/range { $register .. $register }, $HIDDEN_FILTER")
        list.method.addInstructions(
            list.returnAt + 1,
            """
                move-result-object $register
                return-object $register
            """,
        )
    }
}

/** Throws unless the extension has the public static filter. */
private fun BytecodePatchContext.requireFilter() {
    val extension = classDefByOrNull(HIDDEN_CHATS) ?: refuse("the extension has no $HIDDEN_CHATS")
    val name = HIDDEN_FILTER.substringAfter("->").substringBefore("(")
    val parameters = HIDDEN_FILTER.substringAfter("(").substringBefore(")")
    if (extension.methods.none {
            it.name == name && it.parameterTypes.joinToString("") == parameters && it.returnType == ARRAY_LIST &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        }
    ) refuse("the extension has no public static $HIDDEN_FILTER")
}
