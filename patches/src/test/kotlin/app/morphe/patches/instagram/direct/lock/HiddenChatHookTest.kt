/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.direct.seen.visualString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hidden chats, on each declared build's own dex and on every other build of the same Instagram
 * version: the inbox screen's view model hands the list it has just read from the thread store to
 * the extension, the store's own two readers stay untouched (Instagram's inbox save reads them too
 * and would otherwise erase a hidden chat from the phone), and the extension's bridge from a
 * summary to its chat's thread id is written from Instagram's own summary type and chat key.
 */
class HiddenChatHookTest {
    @Test
    fun theFilterAndTheBridgeAreInTheExtension() {
        val declared = ExtensionDex.classDef(HIDDEN_CHATS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDDEN_FILTER is not in the extension: $declared", HIDDEN_FILTER.substringAfter("->") in declared)
        assertTrue("no thread summary bridge: $declared", "threadId(Ljava/lang/Object;)Ljava/lang/String;" in declared)
    }

    @Test
    fun eachDeclaredBuildFiltersItsInbox() = fixtures { bundle -> check(bundle.name, summaryClasses(bundle)) }

    @Test
    fun eachOtherBuildDoesToo() {
        for (bundle in Fixtures.otherBuilds()) check(bundle.parentFile.name, summaryClasses(bundle))
    }

    @Test
    fun dexBackedInstructionReReadsResolveTheSameTargets() = fixtures { bundle ->
        val types = summaryClasses(bundle).keys - HIDDEN_CHATS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(HIDDEN_CHATS))
        val found = context.findHiddenChatTargets()
        hideChatsFromInbox(found)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, found.inbox.method.visualCode()[found.inbox.resultAt].opcode)
    }

    @Test
    fun eachDeclaredBuildFiltersItsInboxSearch() = fixtures { bundle -> checkSearch(bundle.name, searchClasses(bundle)) }

    @Test
    fun eachOtherBuildFiltersItsInboxSearchToo() {
        for (bundle in Fixtures.otherBuilds()) checkSearch(bundle.parentFile.name, searchClasses(bundle))
    }

    @Test
    fun searchHooksResolveTheSameTargetsOnDexBackedReReads() = fixtures { bundle ->
        val types = searchClasses(bundle).keys - HIDDEN_CHATS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(HIDDEN_CHATS))
        val found = context.findSearchTargets()
        hideChatsFromSearch(found)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, found.rows.method.visualCode()[found.rows.at].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, found.seeAll.method.visualCode()[found.seeAll.at].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, found.hits.method.visualCode()[found.hits.returnAt].opcode)
    }

    @Test
    fun theSearchExtensionHasItsFiltersAndBridge() {
        val declared = ExtensionDex.classDef(HIDDEN_CHATS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDDEN_SEARCH_RESULTS is not in the extension: $declared", HIDDEN_SEARCH_RESULTS.substringAfter("->") in declared)
        assertTrue("$HIDDEN_SEARCH_HITS is not in the extension: $declared", HIDDEN_SEARCH_HITS.substringAfter("->") in declared)
        assertTrue("no search result bridge: $declared", "targetThreadId(Ljava/lang/Object;)Ljava/lang/String;" in declared)
    }

    @Test
    fun aBuildWithoutTheSearchResultTypeFailsThePatch() = refusesSearch("$SHARE_TARGET is missing") { it.type != SHARE_TARGET }

    @Test
    fun aBuildWithoutTheRowBuilderFailsThePatch() = refusesSearch("expected exactly one inbox search row builder") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == "ibc_chats_context_lines" } }
    }

    @Test
    fun aBuildWithoutTheMessageMatchBuilderFailsThePatch() = refusesSearch("expected exactly one message match builder") { classDef ->
        classDef.methods.none { it.buildsMessageHits() }
    }

    @Test
    fun eachDeclaredBuildFiltersItsRecentSearches() = fixtures { bundle -> checkRecents(bundle.name, recentClasses(bundle)) }

    @Test
    fun eachOtherBuildFiltersItsRecentSearchesToo() {
        for (bundle in Fixtures.otherBuilds()) checkRecents(bundle.parentFile.name, recentClasses(bundle))
    }

    @Test
    fun eachBuildHasOneRecentSearchesReaderAndOnePlainOne() = fixtures { bundle ->
        val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == SHARE_TARGET } }) { it.readsRecentSearches() }
        assertEquals("${bundle.name}: ${readers.map { it.definingClass + "->" + it.name }}", 1, readers.size)
    }

    @Test
    fun recentSearchHooksResolveTheSameTargetsOnDexBackedReReads() = fixtures { bundle ->
        val types = recentClasses(bundle).keys - HIDDEN_CHATS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(HIDDEN_CHATS))
        val found = context.findRecentTargets()
        hideChatsFromRecents(found)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, found.entries.method.visualCode()[found.entries.returnAt].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, found.chats.method.visualCode()[found.chats.returnAt].opcode)
    }

    @Test
    fun theExtensionHasTheRecentSearchesFilter() {
        val declared = ExtensionDex.classDef(HIDDEN_CHATS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDDEN_RECENTS is not in the extension: $declared", HIDDEN_RECENTS.substringAfter("->") in declared)
    }

    @Test
    fun aBuildWithoutTheRecentSearchesReaderFailsThePatch() = fixtures { bundle ->
        val context = PatchContexts.of(recentClasses(bundle).values.filter { classDef -> classDef.methods.none { it.readsRecentSearches() } })
        val refusal = assertThrows(PatchException::class.java) { context.findRecentTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains("expected exactly one recent searches reader"))
    }

    @Test
    fun aBuildWithoutTheChatKeyFailsThePatch() = refuses("$THREAD_KEY is missing") { it.type != THREAD_KEY }

    @Test
    fun aBuildWithoutTheInboxViewModelFailsThePatch() = refuses("expected exactly one inbox view model") { classDef ->
        classDef.methods.none { it.inboxReadAt() != null }
    }

    @Test
    fun aBuildWithoutTheThreadStoreFailsThePatch() = refuses("expected two readers") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == THREAD_SUMMARIES } }
    }

    /** The hooks that keep a hidden chat out of the recent searches, on one build: where each lands and that nothing else moved. */
    private fun checkRecents(name: String, classes: Map<String, ClassDef>) {
        val context = PatchContexts.of(classes.values)
        val found = context.findRecentTargets()
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val hooked = listOf(found.entries.method, found.chats.method).map { "${it.definingClass}->${it.name}" }
        assertEquals("$name: both readers are in one store", found.entries.method.definingClass, found.chats.method.definingClass)
        assertTrue("$name: two different readers", found.entries.method.name != found.chats.method.name)
        val entriesBefore = found.entries.method.visualCode().map(::text)
        val chatsBefore = found.chats.method.visualCode().map(::text)

        hideChatsFromRecents(found)

        val entries = found.entries.method.visualCode()
        val at = found.entries.returnAt
        val call = entries[at]
        assertEquals("$name: the recents leave through the filter", HIDDEN_RECENTS, (call as ReferenceInstruction).reference.toString())
        assertEquals((call as RegisterRangeInstruction).startRegister, found.entries.register)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, entries[at + 1].opcode)
        assertEquals(found.entries.register, (entries[at + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_OBJECT, entries[at + 2].opcode)
        assertEquals("$name: nothing else in the recents reader moved", entriesBefore.size + 2, entries.size)
        assertEquals(entriesBefore.take(at), entries.take(at).map(::text))

        val chats = found.chats.method.visualCode()
        val chatsAt = found.chats.returnAt
        assertEquals("$name: the recent chats leave through the filter", HIDDEN_RECENTS, (chats[chatsAt] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, chats[chatsAt + 1].opcode)
        assertEquals("$name: and are copied back into an immutable list",
            "Lcom/google/common/collect/ImmutableList;->copyOf(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableList;",
            chats[chatsAt + 2].visualReference().toString())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, chats[chatsAt + 3].opcode)
        assertEquals(Opcode.RETURN_OBJECT, chats[chatsAt + 4].opcode)
        assertEquals("$name: nothing else in the plain reader moved", chatsBefore.size + 4, chats.size)
        assertEquals(chatsBefore.take(chatsAt), chats.take(chatsAt).map(::text))

        for ((type, original) in before) {
            if (type == HIDDEN_CHATS) continue
            val methods = context.mutableClassDefBy(type).methods.toList()
            assertEquals("$name: $type lost or gained a method", original.size, methods.size)
            methods.forEachIndexed { index, method ->
                if (hooked.none { it == "${method.definingClass}->${method.name}" }) {
                    assertEquals("$name: native $type changed", original[index], method.visualCode().map(::text))
                }
            }
        }
    }
    /** The patch refuses for the reason given on the first declared build with these classes left out, before anything changes. */
    private fun refuses(reason: String, keep: (ClassDef) -> Boolean) = fixtures { bundle ->
        val classes = summaryClasses(bundle).values.filter(keep)
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.findHiddenChatTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun refusesSearch(reason: String, keep: (ClassDef) -> Boolean) = fixtures { bundle ->
        val context = PatchContexts.of(searchClasses(bundle).values.filter(keep))
        val refusal = assertThrows(PatchException::class.java) { context.findSearchTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /** The hooks that keep a hidden chat out of the inbox search, on one build: where each lands and that nothing else moved. */
    private fun checkSearch(name: String, classes: Map<String, ClassDef>) {
        val context = PatchContexts.of(classes.values)
        val found = context.findSearchTargets()
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val hooked = listOf(found.rows.method, found.seeAll.method, found.hits.method, found.bridge).map { "${it.definingClass}->${it.name}" }
        val rowsBefore = found.rows.method.visualCode().map(::text)
        val seeAllBefore = found.seeAll.method.visualCode().map(::text)
        val hitsBefore = found.hits.method.visualCode().map(::text)

        hideChatsFromSearch(found)

        val rows = found.rows.method.visualCode()
        val results = rows[0] as ReferenceInstruction
        assertEquals("$name: the row builder hands its results over first", HIDDEN_SEARCH_RESULTS, results.reference.toString())
        assertEquals("$name: the third parameter", found.rows.method.parameterRegisterNumber(2), (results as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the answer goes back where the list was", Opcode.MOVE_RESULT_OBJECT, rows[1].opcode)
        assertEquals((results as RegisterRangeInstruction).startRegister, (rows[1] as OneRegisterInstruction).registerA)
        assertEquals("$name: the row builder's own code follows", rowsBefore, rows.drop(2).map(::text))

        val seeAll = found.seeAll.method.visualCode()
        val at = found.seeAll.at
        assertEquals("$name: the See all reader casts its results to a list first", Opcode.CHECK_CAST, seeAll[at - 1].opcode)
        assertEquals("$name: then hands them over", HIDDEN_SEARCH_RESULTS, (seeAll[at] as ReferenceInstruction).reference.toString())
        assertEquals(found.seeAll.register, "v" + (seeAll[at] as RegisterRangeInstruction).startRegister)
        assertEquals("$name: and takes the answer back", Opcode.MOVE_RESULT_OBJECT, seeAll[at + 1].opcode)
        assertEquals("$name: the screen is cast next", Opcode.IGET_OBJECT, seeAll[at + 2].opcode)
        assertEquals("$name: nothing else in the See all reader moved", seeAllBefore, seeAll.take(at).map(::text) + seeAll.drop(at + 2).map(::text))

        val hits = found.hits.method.visualCode()
        val call = hits[found.hits.returnAt]
        assertEquals("$name: the message matches leave through the filter", HIDDEN_SEARCH_HITS, (call as ReferenceInstruction).reference.toString())
        assertEquals((call as RegisterRangeInstruction).startRegister, found.hits.register)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, hits[found.hits.returnAt + 1].opcode)
        assertEquals(Opcode.RETURN_OBJECT, hits[found.hits.returnAt + 2].opcode)
        assertEquals("$name: nothing else in the match builder moved", hitsBefore.size + 2, hits.size)
        assertEquals(hitsBefore.take(found.hits.returnAt), hits.take(found.hits.returnAt).map(::text))

        val bridge = found.bridge.visualCode()
        assertEquals("$name: the bridge casts to the search result", SHARE_TARGET, (bridge[0].visualReference() as TypeReference).type)
        assertTrue("$name: the bridge asks the result for its chat key",
            bridge.any { (it.visualReference() as? MethodReference)?.let { call -> call.definingClass == SHARE_TARGET && call.returnType == THREAD_KEY } == true })
        assertTrue("$name: the bridge reads the key's thread id",
            bridge.any { (it.visualReference() as? FieldReference)?.let { field -> field.definingClass == THREAD_KEY && field.type == "Ljava/lang/String;" } == true })
        assertEquals("$name: the bridge answers", Opcode.RETURN_OBJECT, bridge.last { it.opcode == Opcode.RETURN_OBJECT }.opcode)

        for ((type, original) in before) {
            if (type == HIDDEN_CHATS) continue
            val methods = context.mutableClassDefBy(type).methods.toList()
            assertEquals("$name: $type lost or gained a method", original.size, methods.size)
            methods.forEachIndexed { index, method ->
                if (hooked.none { it == "${method.definingClass}->${method.name}" }) {
                    assertEquals("$name: native $type changed", original[index], method.visualCode().map(::text))
                }
            }
        }
    }

    private fun check(name: String, classes: Map<String, ClassDef>) {
        val context = PatchContexts.of(classes.values)
        val found = context.findHiddenChatTargets()
        val readers = classes.values.flatMap { classDef ->
            classDef.methods.filter { m -> m.visualCode().any { it.visualString() == THREAD_SUMMARIES } }
        }
        assertEquals("$name: both store readers exist", 2, readers.size)
        val readerIds = readers.map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})" }
        val view = found.inbox.method
        assertTrue("$name: the hook is not in a store reader", "${view.definingClass}->${view.name}" !in readerIds.map { it.substringBefore("(") })
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val hooked = listOf(view, found.bridge).map { "${it.definingClass}->${it.name}" }
        val readersBefore = readers.map { reader -> reader.visualCode().map(::text) }
        val viewBefore = view.visualCode().map(::text)

        hideChatsFromInbox(found)

        val readersAfter = readers.map { reader ->
            context.mutableClassDefBy(reader.definingClass).methods.single { it.name == reader.name && it.parameterTypes.toList() == reader.parameterTypes.toList() }
        }
        assertEquals("$name: the store's readers are untouched", readersBefore, readersAfter.map { it.visualCode().map(::text) })
        assertTrue("$name: no store reader calls the filter",
            readersAfter.none { reader -> reader.visualCode().any { it.visualReference()?.toString() == HIDDEN_FILTER } })

        val code = view.visualCode()
        val at = found.inbox.resultAt
        val call = code[at]
        assertEquals("$name: the list goes through the filter", HIDDEN_FILTER, (call as ReferenceInstruction).reference.toString())
        assertEquals("$name: the filter is handed the list", found.inbox.register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the answer goes back in the same register", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals(found.inbox.register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: the store's read comes right before", Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
        assertEquals("$name: the list is wrapped next",
            "Ljava/util/Collections;->unmodifiableList(Ljava/util/List;)Ljava/util/List;", code[at + 2].visualReference().toString())
        assertEquals("$name: nothing else in the view model moved", viewBefore, code.take(at).map(::text) + code.drop(at + 2).map(::text))

        val bridge = found.bridge.visualCode()
        assertEquals("$name: the bridge casts to the summary", Opcode.CHECK_CAST, bridge[0].opcode)
        assertTrue("$name: the bridge reads the key's thread id",
            bridge.any { (it.visualReference() as? FieldReference)?.let { field -> field.definingClass == THREAD_KEY && field.type == "Ljava/lang/String;" } == true })
        assertTrue("$name: the bridge asks the summary for its key",
            bridge.any { (it.visualReference() as? MethodReference)?.returnType == THREAD_KEY })
        assertEquals("$name: the bridge answers", Opcode.RETURN_OBJECT, bridge.last { it.opcode == Opcode.RETURN_OBJECT }.opcode)

        for ((type, original) in before) {
            if (type == HIDDEN_CHATS) continue
            val methods = context.mutableClassDefBy(type).methods.toList()
            assertEquals("$name: $type lost or gained a method", original.size, methods.size)
            methods.forEachIndexed { index, method ->
                if (hooked.none { it == "${method.definingClass}->${method.name}" }) {
                    assertEquals("$name: native $type changed", original[index], method.visualCode().map(::text))
                }
            }
        }
    }

    private fun text(instruction: Instruction): String = when (val reference = instruction.visualReference()) {
        null -> instruction.opcode.name
        is MethodReference, is FieldReference -> "${instruction.opcode.name} $reference"
        else -> instruction.opcode.name + " " + (instruction.visualString() ?: reference.toString())
    }

    companion object {
        private val cached = mutableMapOf<String, Map<String, ClassDef>>()
        private val searchCached = mutableMapOf<String, Map<String, ClassDef>>()

        private val recentCached = mutableMapOf<String, Map<String, ClassDef>>()

        /** The recent searches store, the chat type and the extension's class. */
        private fun recentClasses(bundle: File): Map<String, ClassDef> = recentCached.getOrPut(bundle.absolutePath) {
            val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == SHARE_TARGET } }) { it.readsRecentSearches() }
            val classes = mutableMapOf<String, ClassDef>()
            classes += FixtureDex.classes(bundle, (readers.map { it.definingClass } + SHARE_TARGET).toSet())
            classes[HIDDEN_CHATS] = ExtensionDex.classDef(HIDDEN_CHATS)
            classes
        }

        /**
         * The inbox search's row builder, the See all reader, the builder of message matches, the
         * search result type, the chat key and the extension's class.
         */
        private fun searchClasses(bundle: File): Map<String, ClassDef> = searchCached.getOrPut(bundle.absolutePath) {
            val classes = mutableMapOf<String, ClassDef>()
            FixtureDex.classesHolding(bundle, "ibc_chats_context_lines").forEach { classes[it.type] = it }
            val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == SEE_ALL_SCREEN } }) { it.seeAllListAt() != null }
            val builders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == MESSAGE_HIT_THREAD } }) { it.buildsMessageHits() }
            val named = (readers + builders).map { it.definingClass }.toMutableSet()
            named += setOf(THREAD_KEY, SHARE_TARGET)
            classes += FixtureDex.classes(bundle, named.filter { it !in classes }.toSet())
            classes[HIDDEN_CHATS] = ExtensionDex.classDef(HIDDEN_CHATS)
            classes
        }

        /** The thread store, the chat key, every type the store's readers load fields of, and the extension's class. */
        private fun summaryClasses(bundle: File): Map<String, ClassDef> = cached.getOrPut(bundle.absolutePath) {
            val classes = mutableMapOf<String, ClassDef>()
            FixtureDex.classesHolding(bundle, THREAD_SUMMARIES).forEach { classes[it.type] = it }
            val named = mutableSetOf(THREAD_KEY)
            for (classDef in classes.values.toList()) {
                for (method in classDef.methods) {
                    if (method.visualCode().none { it.visualString() == THREAD_SUMMARIES }) continue
                    method.visualCode().mapNotNullTo(named) { (it.visualReference() as? FieldReference)?.type }
                }
            }
            val views = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == "Ljava/util/LinkedHashSet;" } }) { it.inboxReadAt() != null }
            named += views.map { it.definingClass }
            classes += FixtureDex.classes(bundle, named.filter { it.startsWith("L") && it !in classes }.toSet())
            classes[HIDDEN_CHATS] = ExtensionDex.classDef(HIDDEN_CHATS)
            classes
        }
    }
}
