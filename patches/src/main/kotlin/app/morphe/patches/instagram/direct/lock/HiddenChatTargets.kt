/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val HIDDEN_CHATS = "$EXTENSION_PACKAGE/direct/HiddenChats;"

/** The filter for the chats the inbox screen turns into rows, handed the holder that keeps them too. */
internal const val HIDDEN_INBOX = "$HIDDEN_CHATS->inbox(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;"

/** The bridge that hands the inbox's holder a list and runs its row factory on it, as Instagram's own subscriber does. */
internal const val HIDDEN_REDRAW = "$HIDDEN_CHATS->redraw(Ljava/lang/Object;Ljava/util/List;)V"

/** The filter for one folder tab's chats, which Instagram counts the tab's unread chats from. */
internal const val HIDDEN_FOLDER = "$HIDDEN_CHATS->folder(Ljava/util/List;)Ljava/util/List;"

/** The filter for the chats Instagram's unread badge snapshot reads. */
internal const val HIDDEN_FILTER = "$HIDDEN_CHATS->filter(Ljava/util/ArrayList;)Ljava/util/ArrayList;"
private const val HIDDEN_THREAD_ID = "threadId"

/**
 * What the inbox's row factory logs while it turns the inbox's list of chats into the rows the
 * screen draws (a runnable posted by the list's holder each time the list changes).
 */
internal const val INBOX_ROWS = "DirectThreadRowViewModelFactory.createList"

/**
 * What Instagram's thread store logs around both methods that hand out its thread summaries: the
 * one that takes a filter and a sort, and the one the others call with a list of thread kinds. They
 * are found only to learn the store and its summary type. Neither is hooked, because Instagram's own
 * inbox save reads them too (it deletes the inbox's rows, then writes back only what they return),
 * so a filter there would erase a hidden chat from the phone's local copy.
 */
internal const val THREAD_SUMMARIES = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries"

private fun refuse(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")

private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val STRING = "Ljava/lang/String;"
private const val COMPARATOR = "Ljava/util/Comparator;"
private const val LINKED_HASH_SET = "Ljava/util/LinkedHashSet;"
private const val SET = "Ljava/util/Set;"
private const val UNMODIFIABLE_LIST = "Ljava/util/Collections;->unmodifiableList(Ljava/util/List;)Ljava/util/List;"

/** The store's readers of the sorted thread summaries: each logs [THREAD_SUMMARIES] and answers a new list. */
internal object ThreadSummariesFingerprint : Fingerprint(
    returnType = ARRAY_LIST,
    filters = listOf(string(THREAD_SUMMARIES)),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * Instagram's unread badge snapshot (00b3.A03 on 450): the one instance call that reads the store's
 * sorted summaries (the reader that takes a filter, a sort, a comparator, a list and a flag), wraps
 * them in an unmodifiable list, and goes on to collect the unread chats' keys in a linked set.
 * Nothing else that reads the store does all three. The store is checked against its readers
 * afterwards. Its snapshot feeds the unread badge and, through a method of its own, the unread
 * counts on the inbox's folder tabs. It is not the list the inbox draws its rows from. The wrap is
 * a filter as well, which [badgeReadAt] needs anyway, so the patcher's index picks the classes to
 * read rather than every method being decoded (#173).
 */
internal object InboxBadgeFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/Collections;",
            name = "unmodifiableList",
            parameters = listOf("Ljava/util/List;"),
            returnType = "Ljava/util/List;",
        ),
    ),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.badgeReadAt() != null
    },
)

/** The index of the call that reads the store's sorted summaries and wraps the answer, for a method shaped like the unread badge snapshot. */
internal fun Method.badgeReadAt(): Int? {
    val code = implementation?.instructions?.toList() ?: return null
    if (code.none { (it.visualReference() as? TypeReference)?.type == LINKED_HASH_SET }) return null
    if (code.none { (it.visualReference() as? MethodReference)?.returnType == THREAD_KEY }) return null
    val at = code.indices.filter { index ->
        val call = code[index].visualReference() as? MethodReference
        index + 2 < code.size && call != null &&
            (code[index].opcode == Opcode.INVOKE_VIRTUAL || code[index].opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            call.returnType == ARRAY_LIST && call.parameterTypes.size == 5 &&
            call.parameterTypes[2] == COMPARATOR && call.parameterTypes[3] == "Ljava/util/List;" && call.parameterTypes[4] == "Z" &&
            code[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[index + 2].opcode == Opcode.INVOKE_STATIC && code[index + 2].visualReference().toString() == UNMODIFIABLE_LIST
    }
    return at.singleOrNull()
}

/** A static call that lists chats and where it returns: the method, the return, and the register its one return hands back. */
internal class SummaryList(val method: MutableMethod, val returnAt: Int, val register: Int)

/** A list on its way somewhere: the method, the instruction the filter goes in front of, and the register holding the list. */
internal class ListSite(val method: MutableMethod, val at: Int, val register: Int)

/** One write of the inbox's list of chats: [site], the register holding the list, and the register holding the holder it goes to. */
internal class RowsWrite(val site: ListSite, val holder: Int)

/**
 * Every write of the inbox's list of chats, the holder's list field, and the holder's own starter
 * of the row factory, which Instagram calls on the holder right after each write.
 */
internal class InboxRows(val writes: List<RowsWrite>, val list: FieldReference, val starter: Method)

/**
 * What hiding chats from the inbox needs from Instagram, proved before anything changes: each write
 * of the list the inbox draws its rows from and the holder it goes to, the unread badge snapshot's
 * read of the store's summaries, its read of each folder tab's chats for the tabs' unread counts,
 * the body of the extension's bridge from a summary to its chat's thread id, and the body of its
 * bridge that has the inbox draw its rows again.
 */
internal class HiddenChatTargets(
    val rows: List<RowsWrite>,
    val badge: ListSite,
    val folders: ListSite,
    val bridge: MutableMethod,
    val bridgeBody: String,
    val redraw: MutableMethod,
    val redrawBody: String,
)

/**
 * The store's two readers by what they log, the summary type by what they read (the one type whose
 * fields they load that answers the chat's key, with the chat key's own thread id), the inbox's
 * list of chats by the row factory that reads it ([findInboxRows]), and the unread badge snapshot by
 * what it does with the reader's answer. The filters go on those lists and nowhere in the store, so
 * the disk save and the per-user updates still see every chat.
 */
internal fun BytecodePatchContext.findHiddenChatTargets(): HiddenChatTargets {
    val readers = ThreadSummariesFingerprint.matchAllOrNull().orEmpty().map { it.method }
    if (readers.size != 2) {
        refuse("expected two readers of the thread store's sorted summaries, found ${readers.size}")
    }
    if (readers.map { it.definingClass }.distinct().size != 1) {
        refuse("the thread store's two summary readers are in different classes")
    }
    val store = readers.first().definingClass

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

    val view = uniqueMethod(LOCK_PATCH, "inbox unread badge", InboxBadgeFingerprint)
    val code = view.visualCode()
    val read = view.badgeReadAt() ?: refuse("the inbox unread badge lost its shape")
    val call = code[read].visualReference() as MethodReference
    if (call.definingClass != store || readers.none { it.name == call.name && it.parameterTypes == call.parameterTypes }) {
        refuse("the inbox unread badge reads summaries from ${call.definingClass}->${call.name}, not from the thread store's own reader")
    }
    val after = read + 2
    if (after in view.jumpTargets()) refuse("something jumps into the inbox unread badge right after it reads the store's summaries")
    val register = (code[read + 1] as OneRegisterInstruction).registerA
    if (register > 255) refuse("the inbox unread badge keeps the summaries in v$register, past v255")
    val folders = findFolderCounts(view, call, store)
    val rows = findInboxRows()

    val bridge = mutableClassDefBy(HIDDEN_CHATS).methods.filter {
        it.name == HIDDEN_THREAD_ID && it.parameters() == listOf("Ljava/lang/Object;") && it.returnType == STRING &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.one("extension's thread summary bridge")
    val invoke = if (AccessFlags.INTERFACE.isSet(summary.accessFlags)) "invoke-interface" else "invoke-virtual"
    val bridgeBody = """
        check-cast p0, $summaryType
        $invoke { p0 }, ${getter.signature()}
        move-result-object p0
        if-nez p0, :key
        const/4 p0, 0x0
        return-object p0
        :key
        iget-object p0, p0, ${idField.signature()}
        return-object p0
    """.trimIndent()
    val redraw = mutableClassDefBy(HIDDEN_CHATS).methods.filter {
        it.name == HIDDEN_REDRAW_NAME && it.parameters() == listOf("Ljava/lang/Object;", LIST) && it.returnType == "V" &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.one("extension's inbox redraw bridge")
    requireFilter()
    return HiddenChatTargets(
        rows.writes, ListSite(view, after, register), folders, bridge, bridgeBody, redraw, redrawBody(rows),
    )
}

private const val HIDDEN_REDRAW_NAME = "redraw"

/**
 * The redraw bridge's body: the two steps Instagram's subscriber takes when the inbox's state hands
 * the holder a list, the write of the holder's list and the call of its starter on the holder. The
 * starter does nothing while the inbox is paused and the inbox calls it again when it resumes, so a
 * list handed over while you're in HushGram settings is drawn when you're back.
 */
private fun redrawBody(rows: InboxRows) = """
    check-cast p0, ${rows.list.definingClass}
    iput-object p1, p0, ${rows.list.signature()}
    invoke-static { p0 }, ${rows.starter.signature()}
    return-void
""".trimIndent()

/** The no-argument, non-static calls of [type] that answer a chat key. */
private fun keyGetters(type: ClassDef): List<Method> = type.methods.filter {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == THREAD_KEY
}

/**
 * The inbox's one list of chats, by what reads it. Every list Instagram hands the inbox screen (the
 * one read from the phone at start, a fresh page from the server, a pull to refresh, a switch of
 * folder tab) ends up in one field of one holder (07HZ.A03 on 450). One of the field's readers
 * starts the row factory on it, a runnable logging [INBOX_ROWS] that turns each chat into a row.
 * Reading back from the factory: the one method making it (07HZ.A01) loads the list from its own
 * class's one List field, and that field has one write in the whole app, the holder's subscriber
 * to the inbox's state (a case of 09kY.accept). The filter goes right in front of that write.
 *
 * 452's 385810270 keeps the list Instagram hands over in a second field (06XM.A09) and writes the
 * drawn list (06XM.A0A) from it twice: in the subscriber (0Ayk.A00) and when the inbox's own filter
 * changes (06XM.A05), each time as the selected filter allows, then starts the factory. Each write
 * gets the filter, and each has to be followed by the starter on the holder, as on 450. Both are
 * jumped to from the branch that picks the list, so the filter takes the write's place as the
 * jumps' target and every way to the write passes through it.
 *
 * The starter isn't the field's only reader. On 450 six others read it too: 07HZ.A05, 07Ml.A05,
 * 04BN.A06 (onPause), 04BN.onAppBackgrounded, 09iQ.invoke and 09wL.FOY. Each reads the field
 * itself, so each gets the same filtered list the rows are drawn from and none of them sees a
 * hidden chat. 04BN sets 08CL.A00 from the unread count of that list, so a hidden chat's unread
 * messages don't count there either. The inbox's state, its pagination and Instagram's save to the
 * phone keep every chat. The write's value must be read nowhere after it, so handing the field the
 * filter's answer changes nothing else the method does.
 *
 * The filter is handed the holder too, from the write's own register, so the extension can hand it
 * a list again when the hidden chats change. It does that the way the subscriber does, by writing
 * the list and calling the starter on the holder, so the starter has to be a public static call
 * taking the holder (07HZ.A01) and the list a public field. The starter checks a flag of its holder
 * that the inbox's onPause turns off and its resume turns back on, starting the factory again.
 */
internal fun BytecodePatchContext.findInboxRows(): InboxRows {
    val factory = uniqueMethod(LOCK_PATCH, "inbox row factory", InboxRowsFingerprint)
    val factoryType = factory.definingClass
    val starters = classesCreating(factoryType).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.visualCode().any { it.opcode == Opcode.NEW_INSTANCE && (it.visualReference() as? TypeReference)?.type == factoryType }
        }
    }
    val starter = starters.one("method starting the inbox row factory $factoryType")
    val list = starter.visualCode().filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { it.visualReference() as? FieldReference }.filter { it.type == LIST }
        .distinctBy { it.signature() }.one("list of chats the inbox row factory is started on")
    if (list.definingClass != starter.definingClass) {
        refuse("the inbox row factory is started on ${list.signature()}, which isn't its starter's own list")
    }

    val writes = classesAccessing(list.definingClass, list.name, Opcode.IPUT_OBJECT).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.visualCode()
            code.indices.filter { at ->
                code[at].opcode == Opcode.IPUT_OBJECT && (code[at].visualReference() as? FieldReference)?.signature() == list.signature()
            }.map { method to it }
        }
    }
    if (writes.isEmpty()) refuse("expected one write of the inbox's list of chats ${list.signature()}, found none")
    val sites = writes.map { (native, at) -> rowsWrite(native, at, starter) }
    if (!AccessFlags.STATIC.isSet(starter.accessFlags) || starter.parameters() != listOf(list.definingClass) || starter.returnType != "V") {
        refuse("the inbox row factory's starter ${starter.signature()} isn't a static call taking its holder")
    }
    requirePublic(list.definingClass, list)
    requirePublic(list.definingClass, starter)
    return InboxRows(sites, list, starter)
}

/**
 * The write of the inbox's list at [at] in [native]: its value must not be read after it, both
 * registers must fit the filter's call, and the next instruction must start the row factory on the
 * holder written to, so the write is one the inbox draws.
 */
private fun BytecodePatchContext.rowsWrite(native: Method, at: Int, starter: Method): RowsWrite {
    val writer = mutableClassDefBy(native.definingClass).methods.single {
        it.name == native.name && it.parameters() == native.parameters() && it.returnType == native.returnType
    }
    val code = writer.visualCode()
    val put = code[at] as TwoRegisterInstruction
    val value = put.registerA
    val holder = put.registerB
    val where = "${writer.definingClass}->${writer.name}"
    if (value == holder) refuse("the write of the inbox's list of chats in $where stores v$value into itself")
    if (value > 15 || holder > 15) refuse("the write of the inbox's list of chats in $where uses v$value and v$holder, past v15")
    if (value in writer.liveAcrossInjection(at + 1)) refuse("$where reads the inbox's list of chats again after it writes it")
    val next = code.getOrNull(at + 1)
    val call = next?.visualReference() as? MethodReference
    val starts = next?.opcode == Opcode.INVOKE_STATIC && call != null && call.definingClass == starter.definingClass &&
        call.name == starter.name && call.parameterTypes.map(CharSequence::toString) == starter.parameters() &&
        (next as FiveRegisterInstruction).registerCount == 1 && next.registerC == holder
    if (!starts) refuse("$where doesn't start the inbox row factory on its holder right after it writes the list of chats")
    return RowsWrite(ListSite(writer, at, value), holder)
}

/** The inbox's row factory: a runnable logging [INBOX_ROWS]. */
internal object InboxRowsFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string(INBOX_ROWS)),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() },
)

/**
 * The unread counts on the inbox's folder tabs. The badge snapshot counts them in a method of its
 * own class it calls with the folders to count (00b3.A01 on 450, taking the account, the store and
 * a set of folders): for each folder it asks a part of the store (00CN, a field of the store) for
 * that folder's chats with the same filter type the store's sorted reader takes, and counts the
 * unread ones. The folder pills say "1 unread message" from those counts, and All is the chats of
 * the others put together. The folder's chats go through the extension right after they're read,
 * in that method only. The store's part and its other callers aren't touched.
 */
private fun BytecodePatchContext.findFolderCounts(badge: Method, storeRead: MethodReference, store: String): ListSite {
    val counters = badge.visualCode().filter { it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE }
        .mapNotNull { it.visualReference() as? MethodReference }
        .filter { it.definingClass == badge.definingClass && it.returnType.startsWith("L") && it.parameterTypes.lastOrNull()?.toString() == SET }
        .distinctBy { it.signature() }
    val counted = counters.one("method of the inbox unread badge counting its folders")
    val counter = mutableClassDefBy(badge.definingClass).methods.single {
        it.name == counted.name && it.parameters() == counted.parameterTypes.map(CharSequence::toString) && it.returnType == counted.returnType
    }
    val parts = (classDefByOrNull(store) ?: refuse("$store is missing")).fields.mapTo(HashSet()) { it.type }
    val filterType = storeRead.parameterTypes[1].toString()
    val code = counter.visualCode()
    val read = code.indices.filter { index ->
        val call = code[index].visualReference() as? MethodReference
        (code[index].opcode == Opcode.INVOKE_VIRTUAL || code[index].opcode == Opcode.INVOKE_VIRTUAL_RANGE) && call != null &&
            call.returnType == LIST && call.parameterTypes.size == 2 && call.parameterTypes[1].toString() == filterType &&
            call.definingClass in parts && index + 2 < code.size && code[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT
    }.one("read of a folder's chats in the inbox unread badge's folder count")
    val after = read + 2
    if (after in counter.jumpTargets()) refuse("something jumps into the folder count right after it reads a folder's chats")
    val register = (code[read + 1] as OneRegisterInstruction).registerA
    if (register > 255) refuse("the folder count keeps a folder's chats in v$register, past v255")
    return ListSite(counter, after, register)
}

/**
 * Writes the thread summary bridge and the redraw bridge and puts the filters in. The inbox's list
 * of chats goes through the extension right before it's written to its holder, with the holder, the
 * list the badge snapshot has just read goes through it before it is wrapped, and each folder's
 * chats go through it right after the folder count reads them. Each answer comes back in the
 * register the list was in, a List where a List was, an ArrayList where an ArrayList was.
 */
internal fun hideChatsFromInbox(targets: HiddenChatTargets) {
    targets.bridge.addInstructionsWithLabels(0, targets.bridgeBody)
    targets.redraw.addInstructions(0, targets.redrawBody)
    // Last write first, so an earlier write in the same method keeps its index. The filter takes
    // the write's place as a jump target, so a branch to the write runs it too.
    for (write in targets.rows.sortedByDescending { it.site.at }) {
        val rows = write.site
        rows.method.addInstructionsAtControlFlowLabel(
            rows.at,
            """
                invoke-static { v${write.holder}, v${rows.register} }, $HIDDEN_INBOX
                move-result-object v${rows.register}
            """,
        )
    }
    for ((site, filter) in listOf(targets.badge to HIDDEN_FILTER, targets.folders to HIDDEN_FOLDER)) {
        val register = "v${site.register}"
        site.method.addInstructions(
            site.at,
            """
                invoke-static/range { $register .. $register }, $filter
                move-result-object $register
            """,
        )
    }
}

/** Throws unless the extension has the public static filters and the redraw bridge. */
private fun BytecodePatchContext.requireFilter() {
    val extension = classDefByOrNull(HIDDEN_CHATS) ?: refuse("the extension has no $HIDDEN_CHATS")
    for ((signature, returns) in listOf(HIDDEN_FILTER to ARRAY_LIST, HIDDEN_INBOX to LIST, HIDDEN_FOLDER to LIST, HIDDEN_REDRAW to "V")) {
        val name = signature.substringAfter("->").substringBefore("(")
        val parameters = signature.substringAfter("(").substringBefore(")")
        if (extension.methods.none {
                it.name == name && it.parameterTypes.joinToString("") == parameters && it.returnType == returns &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }
        ) refuse("the extension has no public static $signature")
    }
}

// ------------------------------------------------------------------ the inbox search

internal const val HIDDEN_SEARCH_RESULTS = "$HIDDEN_CHATS->searchResults(Ljava/util/List;)Ljava/util/List;"
internal const val HIDDEN_SEARCH_HITS = "$HIDDEN_CHATS->searchHits(Ljava/util/ArrayList;)Ljava/util/ArrayList;"
private const val HIDDEN_TARGET_THREAD_ID = "targetThreadId"

/** A chat as the inbox search lists it, which Instagram keeps under its own name. */
internal const val SHARE_TARGET = "Lcom/instagram/model/direct/DirectShareTarget;"

/** Chats whose messages matched what was typed, which Instagram also keeps under their own names. */
internal const val MESSAGE_HIT_THREAD = "Lcom/instagram/model/direct/DirectMessageSearchThread;"
internal const val MESSAGE_HIT_MESSAGE = "Lcom/instagram/model/direct/DirectMessageSearchMessage;"

private const val LIST = "Ljava/util/List;"
internal const val SEE_ALL_SCREEN = "Linstagram/features/direct/inbox/fragment/DirectSearchInboxSeeAllFragment;"

/**
 * The inbox search's row builder: the one instance call that takes the typed text, a second text,
 * the list of results and the list of sections, and names the chats' context lines in the same
 * method.
 */
internal object SearchRowsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(STRING, STRING, LIST, LIST),
    filters = listOf(string("ibc_chats_context_lines")),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * The See all screen's reader of the same results: it loads them from the search state and casts
 * them to a list, itself (451) or through the state's own static helper (452).
 */
internal object SeeAllRowsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", STRING, "Z"),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.seeAllListAt() != null
    },
)

/** The call that turns the server's message matches into results: it makes both kinds of message hit. */
internal object MessageHitsFingerprint : Fingerprint(
    returnType = ARRAY_LIST,
    parameters = listOf("Lcom/instagram/common/session/UserSession;", LIST),
    custom = { method, _ -> method.buildsMessageHits() },
)

/** A static call that makes both kinds of message hit. */
internal fun Method.buildsMessageHits(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags)) return false
    val made = implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { (it.visualReference() as? TypeReference)?.type }
    return MESSAGE_HIT_THREAD in made && MESSAGE_HIT_MESSAGE in made
}

/**
 * Where the See all screen reads the search state's results: the instruction that casts them to a
 * list, straight after the call that answers them and before the screen itself is cast, or on 452
 * the move-result keeping what the state's helper answers. Null for a method without that shape,
 * or with more than one such read.
 */
internal fun Method.seeAllListAt(): Int? {
    val code = implementation?.instructions?.toList() ?: return null
    if (code.none { (it.visualReference() as? TypeReference)?.type == SEE_ALL_SCREEN }) return null
    val at = code.indices.filter { index ->
        val call = code[index].visualReference() as? MethodReference
        index + 4 < code.size && code[index].opcode == Opcode.INVOKE_INTERFACE && call != null &&
            call.parameterTypes.isEmpty() && call.returnType == "Ljava/lang/Object;" &&
            code[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[index + 2].opcode == Opcode.CHECK_CAST && (code[index + 2].visualReference() as? TypeReference)?.type == LIST &&
            code[index + 3].opcode == Opcode.IGET_OBJECT &&
            code[index + 4].opcode == Opcode.CHECK_CAST && (code[index + 4].visualReference() as? TypeReference)?.type == SEE_ALL_SCREEN
    }.map { it + 2 } + seeAllHelperReads(code)
    return at.singleOrNull()
}

/**
 * 452's form of the same read (`0Sa5.G0L` on 385810270): the search state's own static helper
 * (`0Wpm.A00`, checked by [requireSeeAllListHelper]) loads the results and casts them, and the
 * reader keeps what it answers, then casts the screen. The index of the move-result holding the
 * list, for each place that reads it so.
 */
private fun Method.seeAllHelperReads(code: List<Instruction>): List<Int> = code.indices.filter { index ->
    val call = code[index].visualReference() as? MethodReference
    index + 3 < code.size && code[index].opcode == Opcode.INVOKE_STATIC && call != null && call.isSeeAllListHelperOf(this) &&
        code[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        code[index + 2].opcode == Opcode.IGET_OBJECT &&
        code[index + 3].opcode == Opcode.CHECK_CAST && (code[index + 3].visualReference() as? TypeReference)?.type == SEE_ALL_SCREEN
}.map { it + 1 }

/** A static call on the search state the reader is handed first, taking that state and answering a list. */
private fun MethodReference.isSeeAllListHelperOf(reader: Method): Boolean {
    val state = reader.parameterTypes.firstOrNull()?.toString() ?: return false
    return definingClass == state && parameterTypes.map(CharSequence::toString) == listOf(state) && returnType == LIST
}

/**
 * The 452 helper the See all reader calls at [listAt]'s invoke, which must be nothing but the
 * state's own read of its results and their cast to a list, so the list it answers is the one the
 * older reader cast itself. Refuses otherwise.
 */
private fun BytecodePatchContext.requireSeeAllListHelper(reader: Method, listAt: Int) {
    val call = reader.visualCode()[listAt - 1].visualReference() as MethodReference
    val helper = classDefByOrNull(call.definingClass)?.methods?.singleOrNull {
        it.name == call.name && it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
            it.returnType == call.returnType
    } ?: refuse("the See all search reader's list helper ${call.definingClass}->${call.name} is missing")
    val code = helper.visualCode()
    val read = code.getOrNull(0)
    val readCall = read?.visualReference() as? MethodReference
    val state = helper.implementation!!.registerCount - 1
    val shaped = AccessFlags.STATIC.isSet(helper.accessFlags) && code.size == 4 &&
        read?.opcode == Opcode.INVOKE_INTERFACE && readCall != null && readCall.definingClass == call.definingClass &&
        readCall.parameterTypes.isEmpty() && readCall.returnType == "Ljava/lang/Object;" &&
        (read as FiveRegisterInstruction).registerCount == 1 && read.registerC == state &&
        code[1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        code[2].opcode == Opcode.CHECK_CAST && (code[2].visualReference() as? TypeReference)?.type == LIST &&
        code[3].opcode == Opcode.RETURN_OBJECT &&
        setOf((code[1] as OneRegisterInstruction).registerA, (code[2] as OneRegisterInstruction).registerA,
            (code[3] as OneRegisterInstruction).registerA).size == 1
    if (!shaped) refuse("the See all search reader's list helper ${call.definingClass}->${call.name} doesn't just read and cast the results")
}

/** A place that hands the search's results to the extension: the method, where, and the register the list is in. */
internal class SearchSite(val method: MutableMethod, val at: Int, val register: String)

/**
 * What hiding chats from the inbox search needs from Instagram, proved before anything changes: the
 * row builder, the See all screen's reader, the builder of message matches and where it returns,
 * and the body of the extension's bridge from a search result to its chat's thread id.
 */
internal class SearchTargets(
    val rows: SearchSite,
    val seeAll: SearchSite,
    val hits: SummaryList,
    val bridge: MutableMethod,
    val bridgeBody: String,
)

/**
 * The search's three ways to a screen, each by what Instagram writes in it. The row builder gets
 * its list of results filtered before it reads any, so the cached, local and server results that
 * all pass through it are covered together, the See all screen's list is filtered as it is read,
 * and the message matches leave their builder through the filter.
 */
internal fun BytecodePatchContext.findSearchTargets(): SearchTargets {
    val rows = uniqueMethod(LOCK_PATCH, "inbox search row builder", SearchRowsFingerprint)
    if (0 in rows.jumpTargets()) refuse("something jumps back to the inbox search row builder's first instruction")
    val seeAll = uniqueMethod(LOCK_PATCH, "See all search reader", SeeAllRowsFingerprint)
    val cast = seeAll.seeAllListAt() ?: refuse("the See all search reader lost its shape")
    if (seeAll.visualCode()[cast].opcode == Opcode.MOVE_RESULT_OBJECT) requireSeeAllListHelper(seeAll, cast)
    if (cast + 1 in seeAll.jumpTargets()) refuse("something jumps into the See all search reader after it reads its results")
    val listRegister = (seeAll.visualCode()[cast] as OneRegisterInstruction).registerA
    if (listRegister > 255) refuse("the See all search reader keeps its results in v$listRegister, past v255")

    val hitsMethod = uniqueMethod(LOCK_PATCH, "message match builder", MessageHitsFingerprint)
    val code = hitsMethod.visualCode()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.size != 1) refuse("the message match builder returns its list in ${returns.size} places, expected one")
    val hitsRegister = (code[returns.single()] as OneRegisterInstruction).registerA
    if (hitsRegister > 255) refuse("the message match builder returns its list from v$hitsRegister, past v255")

    val keyClass = classDefByOrNull(THREAD_KEY) ?: refuse("$THREAD_KEY is missing")
    val idField = threadIdField(keyClass)
    val target = classDefByOrNull(SHARE_TARGET) ?: refuse("$SHARE_TARGET is missing")
    // Two of its calls answer a chat key. The one that answers null when the result is no chat yet is
    // the one that only knows chats; the other builds a key from the people.
    val getter = keyGetters(target).filter { it.answersNullWithoutChat() }.one("call answering the key of the chat a search result is")
    requirePublic(SHARE_TARGET, getter)
    requirePublic(THREAD_KEY, idField)

    val bridge = mutableClassDefBy(HIDDEN_CHATS).methods.filter {
        it.name == HIDDEN_TARGET_THREAD_ID && it.parameters() == listOf("Ljava/lang/Object;") && it.returnType == STRING &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.one("extension's search result bridge")
    val bridgeBody = """
        check-cast p0, $SHARE_TARGET
        invoke-virtual { p0 }, ${getter.signature()}
        move-result-object p0
        if-nez p0, :key
        const/4 p0, 0x0
        return-object p0
        :key
        iget-object p0, p0, ${idField.signature()}
        return-object p0
    """.trimIndent()
    requireSearchFilters()
    return SearchTargets(
        SearchSite(rows, 0, rows.parameterRegister(2)),
        SearchSite(seeAll, cast + 1, "v$listRegister"),
        SummaryList(hitsMethod, returns.single(), hitsRegister),
        bridge,
        bridgeBody,
    )
}

/** A call of the search result type that returns null on a path that never touches a list. */
private fun Method.answersNullWithoutChat(): Boolean {
    val code = visualCode()
    return code.indices.any { index ->
        code[index].opcode == Opcode.CONST_4 && (code[index] as NarrowLiteralInstruction).narrowLiteral == 0 &&
            index + 1 < code.size && code[index + 1].opcode == Opcode.RETURN_OBJECT
    }
}

/**
 * Writes the search result bridge and puts the filters in: the row builder's list is replaced by
 * the extension's answer before anything reads it, the See all reader's the same just after its
 * cast, and the message matches go through the extension where they are returned.
 */
internal fun hideChatsFromSearch(targets: SearchTargets) {
    targets.bridge.addInstructionsWithLabels(0, targets.bridgeBody)
    for (site in listOf(targets.rows, targets.seeAll)) {
        site.method.addInstructions(
            site.at,
            """
                invoke-static/range { ${site.register} .. ${site.register} }, $HIDDEN_SEARCH_RESULTS
                move-result-object ${site.register}
            """,
        )
    }
    val hits = targets.hits
    val register = "v${hits.register}"
    hits.method.replaceInstruction(hits.returnAt, "invoke-static/range { $register .. $register }, $HIDDEN_SEARCH_HITS")
    hits.method.addInstructions(
        hits.returnAt + 1,
        """
            move-result-object $register
            return-object $register
        """,
    )
}

/** Throws unless the extension has the public static search filters. */
private fun BytecodePatchContext.requireSearchFilters() {
    val extension = classDefByOrNull(HIDDEN_CHATS) ?: refuse("the extension has no $HIDDEN_CHATS")
    for ((signature, returns) in listOf(HIDDEN_SEARCH_RESULTS to LIST, HIDDEN_SEARCH_HITS to ARRAY_LIST)) {
        val name = signature.substringAfter("->").substringBefore("(")
        val parameters = signature.substringAfter("(").substringBefore(")")
        if (extension.methods.none {
                it.name == name && it.parameterTypes.joinToString("") == parameters && it.returnType == returns &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }
        ) refuse("the extension has no public static $signature")
    }
}

// ------------------------------------------------------------------ the inbox search's recents

internal const val HIDDEN_RECENTS = "$HIDDEN_CHATS->recents(Ljava/util/List;)Ljava/util/List;"
private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val IMMUTABLE_COPY = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"

/**
 * The reader of the recent searches that takes how many to give: it wraps each recent chat or
 * person and sorts them, which is the list the search shows before anything is typed. It is the
 * one instance call that takes an int, answers a list, casts to the chat type, and copies a list
 * into an immutable one.
 */
internal object RecentSearchesFingerprint : Fingerprint(
    returnType = LIST,
    parameters = listOf("I"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) && method.readsRecentSearches() },
)

/** A method shaped like the recent searches reader: it casts to the chat type and a long, and copies into an immutable list. */
internal fun Method.readsRecentSearches(): Boolean {
    val code = implementation?.instructions?.toList() ?: return false
    val casts = code.filter { it.opcode == Opcode.CHECK_CAST }.mapNotNull { (it.visualReference() as? TypeReference)?.type }
    return SHARE_TARGET in casts && "Ljava/lang/Long;" in casts &&
        code.any { it.opcode == Opcode.INVOKE_STATIC && it.visualReference().toString() == IMMUTABLE_COPY }
}

/**
 * The same store's plain reader: nothing to pass, an immutable copy of the store's own list of
 * recent chats back. It is never hooked. Opening a chat walks this list by position and writes
 * each entry back to the store at that position, so a list with a hidden chat left out would put
 * entries in the wrong places. Tests use it to prove it stays as Instagram wrote it.
 */
internal fun Method.readsRecentChats(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.isNotEmpty() || returnType != IMMUTABLE_LIST) return false
    val code = implementation?.instructions?.toList() ?: return false
    return code.count { it.opcode == Opcode.RETURN_OBJECT } == 1 &&
        code.any { it.opcode == Opcode.INVOKE_STATIC && it.visualReference().toString() == IMMUTABLE_COPY }
}

/**
 * The counted reader's trim: the one static call that takes the sorted entries and the count it
 * was asked for and answers a list. Null unless there is exactly one.
 */
internal fun Method.recentsTrimAt(): Int? {
    if (parameterTypes.map { it.toString() } != listOf("I")) return null
    val count = parameterRegisterNumber(0)
    val code = visualCode()
    return code.indices.filter { index ->
        val call = code[index].visualReference() as? MethodReference
        code[index].opcode == Opcode.INVOKE_STATIC && call != null && call.returnType == LIST &&
            call.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Iterable;", "I") &&
            (code[index] as FiveRegisterInstruction).registerD == count
    }.singleOrNull()
}

/** Where the recent searches are filtered: the counted reader, the trim to the count, and the register holding the sorted entries. */
internal class RecentTargets(val method: MutableMethod, val trimAt: Int, val register: Int)

/**
 * The store of recent searches by its reader that takes a count. That reader builds a fresh list,
 * sorts it and trims it to the count, and the screen before you type, the search history screen and
 * the row builder's recent section read it. Its sorted entries go through the extension just before
 * the trim, so a hidden chat leaves room for the next entry and the list still fills up to the
 * count. The store's plain reader isn't touched (see [readsRecentChats]).
 */
internal fun BytecodePatchContext.findRecentTargets(): RecentTargets {
    val counted = uniqueMethod(LOCK_PATCH, "recent searches reader", RecentSearchesFingerprint)
    val trimAt = counted.recentsTrimAt() ?: refuse("the recent searches reader lost its trim to the count it is asked for")
    if (trimAt in counted.jumpTargets()) refuse("something jumps straight to the recent searches reader's trim")
    val register = (counted.visualCode()[trimAt] as FiveRegisterInstruction).registerC
    requireRecentsFilter()
    return RecentTargets(counted, trimAt, register)
}

/** Sends the counted reader's sorted entries through the extension right before it trims them to the count. */
internal fun hideChatsFromRecents(targets: RecentTargets) {
    val register = "v${targets.register}"
    targets.method.addInstructions(
        targets.trimAt,
        """
            invoke-static/range { $register .. $register }, $HIDDEN_RECENTS
            move-result-object $register
        """,
    )
}

/** Throws unless the extension has the public static recents filter. */
private fun BytecodePatchContext.requireRecentsFilter() {
    val extension = classDefByOrNull(HIDDEN_CHATS) ?: refuse("the extension has no $HIDDEN_CHATS")
    val name = HIDDEN_RECENTS.substringAfter("->").substringBefore("(")
    if (extension.methods.none {
            it.name == name && it.parameterTypes.joinToString("") == LIST && it.returnType == LIST &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        }
    ) refuse("the extension has no public static $HIDDEN_RECENTS")
}
