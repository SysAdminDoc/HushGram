/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val FEED_ENDED = "$EXTENSION_PACKAGE/feed/FeedSuggestions;->feedEnded(I)I"

/** Handed the home feed object right before each read of its flag, so feedEnded can ask it whether it's empty. */
internal const val HOME_FEED_READ = "$EXTENSION_PACKAGE/feed/FeedSuggestions;->homeFeedRead(Ljava/lang/Object;)V"

/** The extension's stub the patch fills with the feed's own empty check. */
internal const val FEED_EMPTY_STUB = "feedEmpty"

/** The log tag of the home feed adapter's model builder, and the key of the loading row it adds. */
internal const val BUILD_MODELS = "MainfeedAdapter.buildModels"
internal const val SHIMMER_KEY = "shimmer"

internal const val MORE_AFTER_FOLLOWING = "$EXTENSION_PACKAGE/feed/FeedSuggestions;->moreAfterFollowing(I)I"
internal const val END_CARD_RULE = "$EXTENSION_PACKAGE/feed/FeedSuggestions;->endCardRule(I)I"

/**
 * The paging source of Following's pages, which the load more row's show check compares the
 * latest page's source against. The pages past Following's end card come from another source.
 */
internal const val FOLLOWING_FEED = "homecoming_following"

/**
 * Writes [endEmptiedFeed] once for every patch that can take all of Home's posts out (Hide suggested
 * posts and Hide the home feed), so the flag's reads get one hook between them.
 */
internal val emptiedFeedEndPatch = bytecodePatch {
    dependsOn(instagramExtensionPatch)

    execute {
        endEmptiedFeed(findFeedEnd())
    }
}

/**
 * The home feed adapter, the flag it reads to tell an empty feed that's finished (no next page)
 * from one still loading, and the question it asks the same feed object right after: whether it's
 * empty.
 */
internal class FeedEnd(val adapter: String, val flag: FieldReference, val empty: MethodReference)

/**
 * Finds the one method holding [BUILD_MODELS] and [SHIMMER_KEY]. With the feed empty it adds a
 * loading row keyed [SHIMMER_KEY], unless the feed has no next page, nothing is loading and there's
 * nothing else to show, when it adds Instagram's own empty feed card instead. The flag is the last
 * boolean field it reads before that key, right before asking the same object whether it's empty, which
 * it asks again just before adding the loading row.
 */
internal fun BytecodePatchContext.findFeedEnd(): FeedEnd {
    val found = mutableListOf<Pair<String, Method>>()
    classesHolding(BUILD_MODELS, SHIMMER_KEY).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.holds(BUILD_MODELS) && method.holds(SHIMMER_KEY)) found += classDef.type to method
        }
    }
    val (adapter, method) = found.singleOrNull()
        ?: refuse("expected one method holding $BUILD_MODELS and $SHIMMER_KEY, found ${found.size}")
    val code = method.implementation!!.instructions.toList()
    val where = "$adapter->${method.name}"
    val shimmer = code.indexOfFirst { (it as? ReferenceInstruction)?.reference.let { r -> r is StringReference && r.string == SHIMMER_KEY } }
    val read = (shimmer - 1 downTo 0).firstOrNull { code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: refuse("$where reads no flag before its loading row")
    val owner = (code[read] as TwoRegisterInstruction).registerB
    val asked = code.getOrNull(read + 2)
    val empty = (asked as? ReferenceInstruction)?.reference as? MethodReference
    if (asked?.opcode != Opcode.INVOKE_VIRTUAL || (asked as FiveRegisterInstruction).registerC != owner ||
        empty?.returnType != "Z" || empty.parameterTypes.isNotEmpty()
    ) {
        refuse("$where doesn't ask its feed whether it's empty after reading the flag")
    }
    val again = (read + 3 until shimmer).any { code[it].calls(empty) }
    if (!again) refuse("$where adds its loading row without asking whether the feed is empty")
    return FeedEnd(adapter, (code[read] as ReferenceInstruction).reference as FieldReference, empty!!)
}

/**
 * Passes each read of the flag in the home feed adapter through [FEED_ENDED], which says the feed
 * has no next page once Hide suggested posts or Hide the home feed has taken items out. Instagram still checks that the
 * feed is empty and that nothing is loading, so a feed with posts left, or one waiting on a page,
 * keeps what it draws. An emptied feed then gets Instagram's own empty feed card instead of its
 * loading placeholder, which it would keep for good: nothing asks for the next page of an empty feed.
 *
 * Right before each read, [HOME_FEED_READ] gets the feed object the flag is read from, taking any
 * branch to the read with it, and the extension's [FEED_EMPTY_STUB] is filled with the feed's own
 * empty check ([tellFeedEmptiness]), so feedEnded knows whether the feed it answers for is empty.
 */
internal fun BytecodePatchContext.endEmptiedFeed(end: FeedEnd) {
    var hooked = 0
    mutableClassDefBy(end.adapter).methods.forEach { method ->
        val code = method.implementation?.instructions?.toList() ?: return@forEach
        code.indices.filter { code[it].opcode == Opcode.IGET_BOOLEAN && code[it].reads(end.flag) }.reversed().forEach { at ->
            val read = code[at] as TwoRegisterInstruction
            val flag = read.registerA
            method.addInstructions(
                at + 1,
                """
                    invoke-static/range { v$flag .. v$flag }, $FEED_ENDED
                    move-result v$flag
                """,
            )
            // Before the read, since the read can write the flag over the register holding the feed.
            method.addInstructionsAtControlFlowLabel(at, "invoke-static/range { v${read.registerB} .. v${read.registerB} }, $HOME_FEED_READ")
            hooked++
        }
    }
    if (hooked == 0) refuse("${end.adapter} never reads ${end.flag.name}")
    tellFeedEmptiness(end)
}

/**
 * Fills the extension's [FEED_EMPTY_STUB] with the question the adapter asks its feed right after
 * reading the flag, so the extension can ask it too. Answers whether it did. A build where that
 * question or the stub can't be reached leaves the stub answering that it can't tell, with a
 * warning: Home still ends, and the report doesn't count it.
 */
internal fun BytecodePatchContext.tellFeedEmptiness(end: FeedEnd): Boolean {
    val empty = end.empty
    val feed = classDefByOrNull(empty.definingClass)
    var owner = feed
    var declared: Method? = null
    while (owner != null && declared == null) {
        declared = owner.methods.singleOrNull { it.name == empty.name && it.parameterTypes.isEmpty() && it.returnType == "Z" }
        owner = owner.superclass?.let { classDefByOrNull(it) }
    }
    val reachable = feed != null && AccessFlags.PUBLIC.isSet(feed.accessFlags) && declared != null &&
        AccessFlags.PUBLIC.isSet(declared.accessFlags) && !AccessFlags.STATIC.isSet(declared.accessFlags)
    val stub = classDefByOrNull(FEED_SUGGESTIONS)?.let {
        mutableClassDefBy(FEED_SUGGESTIONS).methods.singleOrNull { method ->
            method.name == FEED_EMPTY_STUB && AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        }
    }
    if (!reachable || stub == null) {
        patchLog.warning("Home feed end: ${if (stub == null) "$FEED_SUGGESTIONS has no $FEED_EMPTY_STUB(Object)I" else "$empty isn't public"}, so the report won't say when an emptied Home ended.")
        return false
    }
    stub.addInstructions(
        0,
        """
            check-cast p0, ${empty.definingClass}
            invoke-virtual { p0 }, $empty
            move-result p0
            return p0
        """,
    )
    return true
}

/**
 * Lets the load more row's end card rule hide the row under an end of feed card with no posts
 * above it once Hide suggested posts has emptied what came after. The row's show check is the one
 * instance method answering a boolean that holds [FOLLOWING_FEED]. It applies the rule only when the
 * latest page came from Following ([END_CARD_RULE] answers its comparison) and there's no next page
 * ([MORE_AFTER_FOLLOWING] answers that question, the one its class's isLoading() asks too).
 * Everything past the card is a suggested post, paged from another source, so with Hide suggested
 * posts on the pages came in empty while a next page was still promised, and the row kept its
 * spinner under the card for good.
 */
internal fun BytecodePatchContext.endFollowingAtItsCard() {
    val (type, shows) = findLoadMoreRow().let { it.first.type to it.second }
    val loading = classDefBy(type).methods.singleOrNull { it.name == "isLoading" && it.returnType == "Z" && it.parameterTypes.isEmpty() }
        ?: refuse("$type has no isLoading()")
    val asked = (loading.ownQuestions(type) intersect shows.ownQuestions(type)).singleOrNull()
        ?: refuse("$type->isLoading and ${shows.name} don't share one question")
    val method = mutableClassDefBy(type).methods.single { it.name == shows.name && it.parameterTypes.isEmpty() }
    val code = method.implementation!!.instructions.toList()
    val where = "$type->${shows.name}"
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == FOLLOWING_FEED }
    val compared = (named + 1 until code.size).firstOrNull { code[it].calls(STRING_EQUALS) }
        ?: refuse("$where doesn't compare its feed's source with $FOLLOWING_FEED")
    val more = code.indices.filter { code[it].calls("$type->$asked()Z") }.singleOrNull()
        ?: refuse("$where doesn't ask $asked once")
    if (more < compared) refuse("$where asks $asked before comparing its feed's source")
    // The later call first, so the earlier index still holds.
    for ((at, hook) in listOf(more to MORE_AFTER_FOLLOWING, compared to END_CARD_RULE)) {
        val answer = code.getOrNull(at + 1) as? OneRegisterInstruction
        if (answer == null || code[at + 1].opcode != Opcode.MOVE_RESULT) refuse("$where drops the answer at $at")
        method.addInstructions(
            at + 2,
            """
                invoke-static/range { v${answer.registerA} .. v${answer.registerA} }, $hook
                move-result v${answer.registerA}
            """,
        )
    }
}

/**
 * The load more row's state class and its show check: the one instance boolean method without
 * parameters that holds [FOLLOWING_FEED], in the one class that has one.
 */
internal fun BytecodePatchContext.findLoadMoreRow(): Pair<ClassDef, Method> {
    val found = mutableListOf<Pair<ClassDef, Method>>()
    classesHolding(FOLLOWING_FEED).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (!AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.isEmpty() &&
                method.holds(FOLLOWING_FEED)
            ) {
                found += classDef to method
            }
        }
    }
    return found.singleOrNull()
        ?: refuse("expected one instance boolean method holding $FOLLOWING_FEED, found ${found.size}")
}

/** The extension's answer to "is the feed loading" in the contextual feed's model builder. */
internal const val OLDER_PAGE_LOADING = "$EXTENSION_PACKAGE/feed/FeedSuggestions;->olderPageLoading(I)I"

/**
 * A model builder that asks the load more policy whether its feed is loading, and for that answer
 * asks the adapter whether it's empty before it adds the full-screen loading row. [at] is the index
 * of the move-result that takes the answer.
 */
internal class LoadingRow(val type: String, val name: String, val parameters: List<String>, val at: Int)

/**
 * The Older Posts page is a contextual feed (the fragment Instagram opens a profile's posts in,
 * module feed_timeline_older), and its model builder draws a full-screen loading row while its fetch
 * is loading and its adapter is empty. The fetch's answer comes back through Home's parser, and a page
 * that loses every post to the switches leaves that builder run once with the fetch still loading and
 * nothing to run it again, so the row stays for good. The builders are the methods
 * that ask the load more policy ([findLoadMoreRow]'s class, through the interface it implements)
 * isLoading(), then branch on the answer, then ask the adapter isEmpty(). Instagram 450 has two, one
 * for each of the contextual feed's adapters. One or two are taken, none or more fails.
 */
internal fun BytecodePatchContext.findLoadingRows(): List<LoadingRow> {
    val policy = findLoadMoreRow().first
    val rows = mutableListOf<LoadingRow>()
    for (policyInterface in policy.interfaces) {
        val asked = "$policyInterface->isLoading()Z"
        classesCalling(policyInterface, "isLoading").forEach { classDef ->
            classDef.methods.forEach { method ->
                val code = method.implementation?.instructions?.toList() ?: return@forEach
                code.indices.filter { code[it].calls(asked) }.forEach { call ->
                    val answer = code.getOrNull(call + 1)
                    val branch = code.getOrNull(call + 2)
                    val empty = (code.getOrNull(call + 3) as? ReferenceInstruction)?.reference as? MethodReference
                    if (answer?.opcode == Opcode.MOVE_RESULT && branch?.opcode == Opcode.IF_EQZ &&
                        (branch as OneRegisterInstruction).registerA == (answer as OneRegisterInstruction).registerA &&
                        code[call + 3].opcode == Opcode.INVOKE_VIRTUAL && empty?.name == "isEmpty" &&
                        empty.returnType == "Z" && empty.parameterTypes.isEmpty()
                    ) {
                        rows += LoadingRow(classDef.type, method.name, method.parameterTypes.map { it.toString() }, call + 1)
                    }
                }
            }
        }
    }
    if (rows.isEmpty() || rows.size > 2) {
        refuse("expected one or two model builders asking isLoading() before isEmpty(), found ${rows.size}")
    }
    return rows
}

/**
 * Passes each builder's isLoading() answer through [OLDER_PAGE_LOADING], which answers not loading
 * once a page of Home's response has lost every post to the switches, so the Older Posts page's
 * loading row isn't drawn over an empty page. The builder still asks the adapter whether it's empty,
 * and nothing asks for another page.
 */
internal fun BytecodePatchContext.endOlderPostsSpinner(rows: List<LoadingRow>) {
    // The later row first within a method, so an earlier index still holds.
    for (row in rows.sortedByDescending { it.at }) {
        val method = mutableClassDefBy(row.type).methods.single { it.name == row.name && it.parameterTypes.map(CharSequence::toString) == row.parameters }
        val answer = (method.implementation!!.instructions.toList()[row.at] as OneRegisterInstruction).registerA
        method.addInstructions(
            row.at + 1,
            """
                invoke-static/range { v$answer .. v$answer }, $OLDER_PAGE_LOADING
                move-result v$answer
            """,
        )
    }
}

/** [findLoadingRows] and [endOlderPostsSpinner], or a warning and Instagram's own spinner where a build has no such builder. */
internal fun BytecodePatchContext.endOlderPostsSpinnerOrWarn() {
    val rows = try {
        findLoadingRows()
    } catch (refused: PatchException) {
        patchLog.warning("${refused.message}. An Older Posts page that loses every post keeps its loading row.")
        return
    }
    endOlderPostsSpinner(rows)
}

private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

/** The names of the boolean methods without parameters this method calls on [type]. */
private fun Method.ownQuestions(type: String): Set<String> = implementation?.instructions?.toList().orEmpty().mapNotNull {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)
        ?.takeIf { called -> called.definingClass == type && called.returnType == "Z" && called.parameterTypes.isEmpty() }?.name
}.toSet()

private fun Instruction.calls(reference: String) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
} == true

private fun Instruction.calls(method: MethodReference) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == method.toString()

private fun Instruction.reads(field: FieldReference) =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == field.toString()

private fun refuse(detail: String): Nothing = throw PatchException("Home feed end: $detail")
