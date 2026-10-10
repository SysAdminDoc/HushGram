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
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

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
        if-eqz p0, :none
        iget-object p0, p0, ${idField.signature()}
        :none
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
