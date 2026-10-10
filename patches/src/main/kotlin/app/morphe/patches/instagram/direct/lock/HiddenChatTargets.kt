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
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val HIDDEN_CHATS = "$EXTENSION_PACKAGE/direct/HiddenChats;"

/** The filter for the chats the inbox screen turns into rows. */
internal const val HIDDEN_INBOX = "$HIDDEN_CHATS->inbox(Ljava/util/List;)Ljava/util/List;"

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
    strings = listOf(THREAD_SUMMARIES),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * Instagram's unread badge snapshot (00b3.A03 on 450): the one instance call that reads the store's
 * sorted summaries (the reader that takes a filter, a sort, a comparator, a list and a flag), wraps
 * them in an unmodifiable list, and goes on to collect the unread chats' keys in a linked set.
 * Nothing else that reads the store does all three. The store is checked against its readers
 * afterwards. Its snapshot feeds the unread badge and, through a method of its own, the unread
 * counts on the inbox's folder tabs. It is not the list the inbox draws its rows from.
 */
internal object InboxBadgeFingerprint : Fingerprint(
    returnType = "L",
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

/**
 * What hiding chats from the inbox needs from Instagram, proved before anything changes: the write
 * of the list the inbox draws its rows from, the unread badge snapshot's read of the store's
 * summaries, its read of each folder tab's chats for the tabs' unread counts, and the body of the
 * extension's bridge from a summary to its chat's thread id.
 */
internal class HiddenChatTargets(
    val rows: ListSite,
    val badge: ListSite,
    val folders: ListSite,
    val bridge: MutableMethod,
    val bridgeBody: String,
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
    requireFilter()
    return HiddenChatTargets(rows, ListSite(view, after, register), folders, bridge, bridgeBody)
}

/** The no-argument, non-static calls of [type] that answer a chat key. */
private fun keyGetters(type: ClassDef): List<Method> = type.methods.filter {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == THREAD_KEY
}

/**
 * The inbox's one list of chats, by what reads it. Every list Instagram hands the inbox screen (the
 * one read from the phone at start, a fresh page from the server, a pull to refresh, a switch of
 * folder tab) ends up in one field of one holder (07HZ.A03 on 450), and the holder's only use of it
 * is to start the row factory on it, a runnable logging [INBOX_ROWS] that turns each chat into a
 * row. Reading back from the factory: the one method making it (07HZ.A01) loads the list from its
 * own class's one List field, and that field has one write in the whole app, the holder's
 * subscriber to the inbox's state (a case of 09kY.accept). The filter goes right in front of that
 * write, so the field, the rows and the counts read from the field all leave a hidden chat out,
 * while the inbox's state, its pagination and Instagram's save to the phone keep every chat. The
 * write's value must be read nowhere after it, so handing the field the filter's answer changes
 * nothing else the method does.
 */
internal fun BytecodePatchContext.findInboxRows(): ListSite {
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
    val (native, at) = writes.one("write of the inbox's list of chats ${list.signature()}")
    val writer = mutableClassDefBy(native.definingClass).methods.single {
        it.name == native.name && it.parameters() == native.parameters() && it.returnType == native.returnType
    }
    val put = writer.visualCode()[at] as TwoRegisterInstruction
    val value = put.registerA
    if (value == put.registerB) refuse("the write of the inbox's list of chats stores v$value into itself")
    if (at in writer.jumpTargets()) refuse("something jumps straight to the write of the inbox's list of chats")
    if (value in writer.liveAcrossInjection(at + 1)) {
        refuse("${writer.definingClass}->${writer.name} reads the inbox's list of chats again after it writes it")
    }
    return ListSite(writer, at, value)
}

/** The inbox's row factory: a runnable logging [INBOX_ROWS]. */
internal object InboxRowsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(INBOX_ROWS),
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
 * Writes the thread summary bridge and puts the filters in. The inbox's list of chats goes through
 * the extension right before it's written to its holder, the list the badge snapshot has just read
 * goes through it before it is wrapped, and each folder's chats go through it right after the
 * folder count reads them. Each answer comes back in the register the list was in, a List where a
 * List was, an ArrayList where an ArrayList was.
 */
internal fun hideChatsFromInbox(targets: HiddenChatTargets) {
    targets.bridge.addInstructionsWithLabels(0, targets.bridgeBody)
    for ((site, filter) in listOf(targets.rows to HIDDEN_INBOX, targets.badge to HIDDEN_FILTER, targets.folders to HIDDEN_FOLDER)) {
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

/** Throws unless the extension has the public static filters. */
private fun BytecodePatchContext.requireFilter() {
    val extension = classDefByOrNull(HIDDEN_CHATS) ?: refuse("the extension has no $HIDDEN_CHATS")
    for ((signature, returns) in listOf(HIDDEN_FILTER to ARRAY_LIST, HIDDEN_INBOX to LIST, HIDDEN_FOLDER to LIST)) {
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
    strings = listOf("ibc_chats_context_lines"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The See all screen's reader of the same results: it loads them from the search state and casts them to a list. */
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
 * list, straight after the call that answers them and before the screen itself is cast. Null for
 * a method without that shape.
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
    }
    return at.singleOrNull()?.plus(2)
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
