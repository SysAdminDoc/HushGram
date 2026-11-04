/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.feed.findFeedItemHelper
import app.morphe.patches.instagram.feed.suggested.emptiedFeedEndPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsConstStringContaining
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide the home feed"
internal const val HOME_FEED_FILTER =
    "$EXTENSION_PACKAGE/feed/HomeFeed;->filter(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * Home's feed response parser: the one reading the window Home waits before a pull to refresh,
 * next to the "feed_items" list it reads each item of through the feed item helper.
 */
internal object HomeFeedResponseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
    filters = listOf(string("pull_to_refresh_window_ms")),
    custom = { method, _ -> method.loadsConstStringContaining("feed_items") },
)

/** Home's store of the items it last showed, read back at the next start. Its name is kept. */
internal const val FEED_MEDIA_CACHE = "Lcom/instagram/mainfeed/network/FeedMediaCache;"

@Suppress("unused")
val hideHomeFeedPatch = bytecodePatch(
    name = "Hide the home feed",
    description = "Empties your home feed on purpose, so Home shows only the stories row. Profiles, Explore and " +
        "Reels still show posts. Starts off. Turn it on in HushGram settings > Feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch, emptiedFeedEndPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("homeFeed")
        filterHomeFeedItems()
        enableStatus("homeFeed")
    }
}

private fun refuse(patch: String, detail: String): Nothing = throw PatchException("$patch: $detail")

/**
 * Passes each item Home reads through HomeFeed, which answers null for every one of them while the
 * switch is on, whatever its kind: the items of Home's feed response, and the ones Home's store
 * reads back from the last run. Both skip a null item. The feed item helper itself stays as it is,
 * since Explore's chain of posts, the shop feeds and the ad feeds read their items through it too.
 * The stories row comes from a request of its own, so it stays. Once the feed is empty, the shared
 * feed end hook has Instagram draw its own empty feed card in place of a loading row that would
 * never finish. Answers how many reads it filtered, and finds every one before changing any.
 */
internal fun BytecodePatchContext.filterHomeFeedItems(): Int = findHomeFeedReads(PATCH).filterWith(HOME_FEED_FILTER)

/**
 * Home's reads of its items, found before anything changes: the feed item's type, and each reader
 * with its reads as [itemReads] answers them. Hide the home feed and Hide suggested posts' post type
 * switches each pass Home's items through a filter of their own here.
 */
internal class HomeFeedReads(val itemType: String, private val sites: List<Pair<MutableMethod, List<Pair<Int, Int>>>>) {
    /**
     * Passes each item read through [filter], a static method of the extension taking and answering
     * an Object, right after the read keeps it. Answers how many reads it filtered. A second filter
     * goes in ahead of whichever applied first, and each answers null for null, so the order
     * doesn't change what Home gets.
     */
    fun filterWith(filter: String): Int {
        sites.forEach { (reader, reads) ->
            reads.asReversed().forEach { (after, register) ->
                reader.addInstructions(
                    after,
                    """
                        invoke-static/range { v$register .. v$register }, $filter
                        move-result-object v$register
                        check-cast v$register, $itemType
                    """,
                )
            }
        }
        return sites.sumOf { it.second.size }
    }

    /** Home's feed response parser, which [findHomeFeedReads] lists first. */
    private val response: MutableMethod get() = sites.first().first

    /** How many returns Home's feed response parser has, each of which [markPages] marks. */
    fun pageReturns(): Int = response.returnsOfObjects().size

    /**
     * Marks each page of Home's feed response as it's parsed: [starts], a static method of the
     * extension taking nothing, first thing in the parser, and [parsed] right before each of its
     * returns, branches to a return included. The items read between the two are that page's, so the
     * extension can tell a page of Home's own from its store's reads and from every other feed's.
     * Goes in after the filters, since it moves the reads they were found at. Answers how many
     * returns it marked.
     */
    fun markPages(starts: String, parsed: String): Int {
        val returns = response.returnsOfObjects()
        returns.asReversed().forEach { response.addInstructionsAtControlFlowLabel(it, "invoke-static { }, $parsed") }
        response.addInstructions(0, "invoke-static { }, $starts")
        return returns.size
    }
}

private fun Method.returnsOfObjects(): List<Int> {
    val code = implementation?.instructions?.toList().orEmpty()
    return code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
}

/** Finds Home's feed response parser and the read of Home's store, and each item they read, for [patch]. */
internal fun BytecodePatchContext.findHomeFeedReads(patch: String): HomeFeedReads {
    val (itemType, helper) = findFeedItemHelper(patch, emptyList())
    val readers = listOf(
        uniqueMethod(patch, "Home's feed response parser", HomeFeedResponseFingerprint),
        homeFeedStoreRead(patch, itemType, helper),
    )
    return HomeFeedReads(itemType, readers.map { reader -> reader to itemReads(patch, reader, helper, itemType) })
}

/**
 * The method of Home's store that turns the bytes it saved back into an item: in the one class
 * [FEED_MEDIA_CACHE] makes that has it, the one method taking bytes, answering an item and
 * reading it through [helper].
 */
internal fun BytecodePatchContext.homeFeedStoreRead(patch: String, itemType: String, helper: Method): MutableMethod {
    val cache = classDefByOrNull(FEED_MEDIA_CACHE) ?: refuse(patch, "Instagram has no $FEED_MEDIA_CACHE")
    val made = cache.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .distinct()
    val reads = made.mapNotNull { classDefByOrNull(it) }.flatMap { store ->
        store.methods.filter { method ->
            method.returnType == itemType && method.parameterTypes.map(CharSequence::toString) == listOf("[B") &&
                method.implementation?.instructions?.any { it.calls(helper) } == true
        }.map { store.type to it.name }
    }
    val (type, name) = reads.singleOrNull()
        ?: refuse(patch, "expected one method of $FEED_MEDIA_CACHE's store reading an item from bytes, found $reads")
    return mutableClassDefBy(type).methods.single {
        it.name == name && it.returnType == itemType && it.parameterTypes.map(CharSequence::toString) == listOf("[B")
    }
}

/**
 * Each item [reader] reads, in order, as the index right after the instruction keeping it and the
 * register it's kept in: each call to [helper], kept by the next move-result, and each copy of the
 * helper's own read that Redex inlined into [reader], kept and cast to [itemType]. 454's store reads
 * its item through the helper on one path and through such a copy on the other; 453's called the
 * helper on both. Fails when there's none, or when a read's item isn't kept. The filter goes in at
 * that index without taking its label, so a branch landing there, as Home's store does after its
 * other read, still lands after the filter and its item passes once.
 */
internal fun itemReads(patch: String, reader: Method, helper: Method, itemType: String): List<Pair<Int, Int>> {
    val where = "${reader.definingClass}->${reader.name}"
    val code = reader.implementation?.instructions?.toList().orEmpty()
    val calls = code.indices.filter { code[it].calls(helper) }.map { at ->
        val kept = code.getOrNull(at + 1)
        if (kept?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse(patch, "$where doesn't keep the item ${helper.name} answers")
        at + 2 to (kept as OneRegisterInstruction).registerA
    }
    val reads = (calls + inlinedReads(patch, where, code, helper, itemType)).sortedBy { it.first }
    if (reads.isEmpty()) refuse(patch, "$where reads no item through ${helper.definingClass}->${helper.name}")
    return reads
}

/**
 * Each copy in [code] of [helper]'s own read: the sget-object of the parser instance the helper
 * reads with, that parser's parseFromJsonParser on it, the answer kept and cast to [itemType]. As
 * the index after the cast and the item's register. A copy whose answer isn't kept and cast fails.
 * None when the helper doesn't load its parser right before the parse.
 */
private fun inlinedReads(patch: String, where: String, code: List<Instruction>, helper: Method, itemType: String): List<Pair<Int, Int>> {
    val own = helper.implementation?.instructions?.toList().orEmpty()
    val parse = own.indexOfFirst { (it.referenceOrNull() as? MethodReference)?.name == "parseFromJsonParser" }
    val load = own.getOrNull(parse - 1)?.takeIf { it.opcode == Opcode.SGET_OBJECT } ?: return emptyList()
    val parser = load.referenceOrNull().toString()
    val parseCall = own[parse].referenceOrNull().toString()
    return code.indices.filter { at ->
        val loaded = code[at]
        val call = code.getOrNull(at + 1)
        loaded.opcode == Opcode.SGET_OBJECT && loaded.referenceOrNull().toString() == parser &&
            call?.referenceOrNull()?.toString() == parseCall && call?.receiver() == (loaded as OneRegisterInstruction).registerA
    }.map { at ->
        val kept = code.getOrNull(at + 2)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
        val cast = code.getOrNull(at + 3)?.takeIf { it.opcode == Opcode.CHECK_CAST }
        if (kept == null || cast == null || (cast as OneRegisterInstruction).registerA != kept.registerA ||
            (cast.referenceOrNull() as TypeReference).type != itemType
        ) {
            refuse(patch, "$where doesn't keep the item its inlined copy of ${helper.name} answers as $itemType")
        }
        at + 4 to kept.registerA
    }
}

private fun Instruction.referenceOrNull() = (this as? ReferenceInstruction)?.reference

/** The receiver of a virtual call, or null for anything else. */
private fun Instruction.receiver(): Int? = when {
    opcode == Opcode.INVOKE_VIRTUAL -> (this as FiveRegisterInstruction).registerC
    opcode == Opcode.INVOKE_VIRTUAL_RANGE -> (this as RegisterRangeInstruction).startRegister
    else -> null
}

private fun Instruction.calls(helper: Method): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return called.definingClass == helper.definingClass && called.name == helper.name &&
        called.returnType == helper.returnType &&
        called.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString)
}
