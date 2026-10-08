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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.feed.findFeedItemHelper
import app.morphe.patches.instagram.feed.suggested.emptiedFeedEndPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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
    strings = listOf("feed_items", "pull_to_refresh_window_ms"),
)

/** Home's store of the items it last showed, read back at the next start. Its name is kept. */
internal const val FEED_MEDIA_CACHE = "Lcom/instagram/mainfeed/network/FeedMediaCache;"

@Suppress("unused")
val hideHomeFeedPatch = bytecodePatch(
    name = "Hide the home feed",
    description = "Empties your home feed on purpose, so Home shows the stories row and nothing under it. " +
        "Profiles, Explore and Reels still show posts.",
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
}

/** Finds Home's feed response parser and the read of Home's store, and each item they read, for [patch]. */
internal fun BytecodePatchContext.findHomeFeedReads(patch: String): HomeFeedReads {
    val (itemType, helper) = findFeedItemHelper(patch, emptyList())
    val readers = listOf(
        uniqueMethod(patch, "Home's feed response parser", HomeFeedResponseFingerprint),
        homeFeedStoreRead(patch, itemType, helper),
    )
    return HomeFeedReads(itemType, readers.map { reader -> reader to itemReads(patch, reader, helper) })
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
 * Each call [reader] makes to [helper], as the index right after the move-result keeping the item
 * and the register it's kept in. Fails when there's none, or when a call's item isn't kept by the
 * next instruction. The filter goes in at that index without taking its label, so a branch landing
 * there, as Home's store does after its other read, still lands after the filter and its item
 * passes once.
 */
internal fun itemReads(patch: String, reader: Method, helper: Method): List<Pair<Int, Int>> {
    val where = "${reader.definingClass}->${reader.name}"
    val code = reader.implementation?.instructions?.toList().orEmpty()
    val reads = code.indices.filter { code[it].calls(helper) }.map { at ->
        val kept = code.getOrNull(at + 1)
        if (kept?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse(patch, "$where doesn't keep the item ${helper.name} answers")
        at + 2 to (kept as OneRegisterInstruction).registerA
    }
    if (reads.isEmpty()) refuse(patch, "$where reads no item through ${helper.definingClass}->${helper.name}")
    return reads
}

private fun Instruction.calls(helper: Method): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return called.definingClass == helper.definingClass && called.name == helper.name &&
        called.returnType == helper.returnType &&
        called.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString)
}
