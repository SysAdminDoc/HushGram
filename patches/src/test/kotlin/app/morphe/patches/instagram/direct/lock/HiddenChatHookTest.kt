/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hidden chats, on each declared build's own dex and on every other build of the same Instagram
 * version: the inbox's list of chats goes through the extension right before it is written to the
 * holder the row factory reads it from, the unread badge snapshot hands the list it has just read
 * from the thread store to the extension, and so does its folder count with each folder tab's
 * chats. The store's own two readers stay untouched (Instagram's inbox save reads them too and
 * would otherwise erase a hidden chat from the phone), and the extension's bridge from a summary to
 * its chat's thread id is written from Instagram's own summary type and chat key.
 */
class HiddenChatHookTest {
    @Test
    fun theFilterAndTheBridgeAreInTheExtension() {
        val declared = ExtensionDex.classDef(HIDDEN_CHATS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (filter in listOf(HIDDEN_INBOX, HIDDEN_FOLDER, HIDDEN_FILTER)) {
            assertTrue("$filter is not in the extension: $declared", filter.substringAfter("->") in declared)
        }
        assertTrue("no thread summary bridge: $declared", "threadId(Ljava/lang/Object;)Ljava/lang/String;" in declared)
    }

    @Test
    fun eachDeclaredBuildFiltersItsInbox() = fixtures { bundle -> check(bundle.name, summaryClasses(bundle)) }

    @Test
    fun eachOtherBuildDoesToo() {
        for (bundle in Fixtures.otherBuilds()) check(bundle.parentFile.name, summaryClasses(bundle))
    }

    /**
     * Across each whole APK, not only the classes the other tests slice out: one method logs the row
     * factory's name, one method starts the factory, it starts it on one list of its own class, and
     * one instruction anywhere writes that list.
     */
    @Test
    fun everyBuildHasOneRowFactoryOneStarterAndOneWriteOfItsList() {
        val bundles = mutableListOf<Pair<String, File>>()
        fixtures { bundles += it.name to it }
        for (bundle in Fixtures.otherBuilds()) bundles += bundle.parentFile.name to bundle
        assertTrue("no other build", bundles.size > 1)
        for ((name, bundle) in bundles) {
            val rows = rowAnchors(bundle)
            assertEquals("$name: ${rows.factories.map { it.signature() }}", 1, rows.factories.size)
            val factory = rows.factories.single()
            assertTrue("$name: the row factory is a runnable's run", factory.returnType == "V" && factory.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(factory.accessFlags))
            assertEquals("$name: ${rows.starters.map { it.signature() }}", 1, rows.starters.size)
            assertEquals("$name: ${rows.lists.map { it.signature() }}", 1, rows.lists.size)
            assertEquals("$name: ${rows.writes.map { (method, at) -> "${method.signature()}@$at" }}", 1, rows.writes.size)
        }
    }

    @Test
    fun dexBackedInstructionReReadsResolveTheSameTargets() = fixtures { bundle ->
        val types = summaryClasses(bundle).keys - HIDDEN_CHATS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(HIDDEN_CHATS))
        val found = context.findHiddenChatTargets()
        hideChatsFromInbox(found)
        for (site in listOf(found.rows, found.badge, found.folders)) {
            assertEquals(Opcode.INVOKE_STATIC_RANGE, site.method.visualCode()[site.at].opcode)
        }
    }

    @Test
    fun aBuildWithoutTheRowFactoryFailsThePatch() = refuses("expected exactly one inbox row factory") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == INBOX_ROWS } }
    }

    @Test
    fun aBuildWhereNothingWritesTheInboxsListFailsThePatch() = fixtures { bundle ->
        val writers = rowAnchors(bundle).writes.mapTo(HashSet()) { (method, _) -> method.definingClass }
        val context = PatchContexts.of(summaryClasses(bundle).values.filter { it.type !in writers })
        val refusal = assertThrows(PatchException::class.java) { context.findHiddenChatTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains("expected one write of the inbox's list of chats"))
    }

    /**
     * The filter's answer replaces the list in the register the write stores, so the writer must not
     * read that register again after the write. A writer that does is refused before anything changes.
     */
    @Test
    fun aWriterThatReadsTheListAgainAfterTheWriteFailsThePatch() = fixtures { bundle ->
        val context = PatchContexts.of(summaryClasses(bundle).values)
        val (native, at) = rowAnchors(bundle).writes.single()
        val writer = context.mutableClassDefBy(native.definingClass).methods.single { it.signature() == native.signature() }
        val value = (writer.visualCode()[at] as TwoRegisterInstruction).registerA
        writer.addInstructions(at + 1, "invoke-interface/range { v$value .. v$value }, Ljava/util/List;->size()I")
        val refusal = assertThrows(PatchException::class.java) { context.findHiddenChatTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains("reads the inbox's list of chats again after it writes it"))
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
        assertEquals(Opcode.INVOKE_STATIC_RANGE, found.method.visualCode()[found.trimAt].opcode)
    }

    /**
     * Opening a chat walks the plain reader's list by position and writes each entry back to the
     * store at that position, so that reader has to answer the store's list exactly as it is.
     */
    @Test
    fun thePlainRecentChatsReaderStaysAsInstagramWroteItOnEveryBuild() {
        fixtures { bundle -> checkPlainReader(bundle.name, recentClasses(bundle)) }
        for (bundle in Fixtures.otherBuilds()) checkPlainReader(bundle.parentFile.name, recentClasses(bundle))
    }

    @Test
    fun aRecentSearchesReaderThatNoLongerTrimsToItsCountFailsThePatch() = fixtures { bundle ->
        val classes = recentClasses(bundle)
        val native = classes.values.flatMap { it.methods }.single { it.parameterTypes.map(Any::toString) == listOf("I") && it.readsRecentSearches() }
        val context = PatchContexts.of(classes.values)
        val reader = context.mutableClassDefBy(native.definingClass).methods.single { it.name == native.name && it.parameterTypes.map(Any::toString) == listOf("I") }
        val code = reader.visualCode()
        val trim = code.indices.single { code[it].visualReference().toString().endsWith("(Ljava/lang/Iterable;I)Ljava/util/List;") }
        // The trim now reads another register than the count the reader was asked for.
        reader.replaceInstruction(trim, "invoke-static { v0, v0 }, ${code[trim].visualReference()}")
        val refusal = assertThrows(PatchException::class.java) { context.findRecentTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains("lost its trim"))
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
    fun aBuildWithoutTheUnreadBadgeSnapshotFailsThePatch() = refuses("expected exactly one inbox unread badge") { classDef ->
        classDef.methods.none { it.badgeReadAt() != null }
    }

    @Test
    fun aBuildWithoutTheThreadStoreFailsThePatch() = refuses("expected two readers") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == THREAD_SUMMARIES } }
    }

    /**
     * The hook that keeps a hidden chat out of the recent searches, on one build: the counted
     * reader's sorted entries go through the filter right before the trim to the count, so the
     * list still fills up to the count, and nothing else moved.
     */
    private fun checkRecents(name: String, classes: Map<String, ClassDef>) {
        val context = PatchContexts.of(classes.values)
        val found = context.findRecentTargets()
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val hooked = "${found.method.definingClass}->${found.method.name}"
        val readerBefore = found.method.visualCode().map(::text)
        val native = found.method.visualCode()
        val at = found.trimAt
        val sort = native[at - 2].visualReference() as MethodReference
        assertEquals("$name: the entries are sorted first", listOf("Ljava/lang/Iterable;", "Ljava/util/Comparator;"), sort.parameterTypes.map(Any::toString))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, native[at - 1].opcode)
        assertEquals("$name: the trim reads the sorted entries", (native[at - 1] as OneRegisterInstruction).registerA, found.register)
        assertEquals("$name: and the count the reader was asked for", found.method.parameterRegisterNumber(0), (native[at] as FiveRegisterInstruction).registerD)

        hideChatsFromRecents(found)

        val reader = found.method.visualCode()
        val call = reader[at]
        assertEquals("$name: the sorted entries go through the filter", HIDDEN_RECENTS, (call as ReferenceInstruction).reference.toString())
        assertEquals((call as RegisterRangeInstruction).startRegister, found.register)
        assertEquals(1, call.registerCount)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, reader[at + 1].opcode)
        assertEquals(found.register, (reader[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: then the trim to the count", readerBefore[at], text(reader[at + 2]))
        assertEquals("$name: nothing else in the recents reader moved", readerBefore.size + 2, reader.size)
        assertEquals(readerBefore.take(at), reader.take(at).map(::text))
        assertEquals(readerBefore.drop(at), reader.drop(at + 2).map(::text))
        assertEquals("$name: the filter is called once", 1, reader.count { it.visualReference().toString() == HIDDEN_RECENTS })

        for ((type, original) in before) {
            if (type == HIDDEN_CHATS) continue
            val methods = context.mutableClassDefBy(type).methods.toList()
            assertEquals("$name: $type lost or gained a method", original.size, methods.size)
            methods.forEachIndexed { index, method ->
                if ("${method.definingClass}->${method.name}" != hooked) {
                    assertEquals("$name: native $type changed", original[index], method.visualCode().map(::text))
                }
            }
        }
    }

    /** The store's plain reader answers the store's list as Instagram wrote it, after the recents hook went in. */
    private fun checkPlainReader(name: String, classes: Map<String, ClassDef>) {
        val context = PatchContexts.of(classes.values)
        val found = context.findRecentTargets()
        val store = context.mutableClassDefBy(found.method.definingClass)
        val plain = store.methods.filter { it.readsRecentChats() }
        assertEquals("$name: one plain reader in ${store.type}", 1, plain.size)
        val before = plain.single().visualCode().map(::text)

        hideChatsFromRecents(found)

        val after = plain.single().visualCode()
        assertEquals("$name: the plain reader is untouched", before, after.map(::text))
        assertTrue("$name: the plain reader never calls the filter", after.none { it.visualReference().toString() == HIDDEN_RECENTS })
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
        val readerIds = readers.map { it.signature() }
        val writer = found.rows.method
        val badge = found.badge.method
        val counter = found.folders.method
        val hooked = listOf(writer, badge, counter, found.bridge).map { it.signature() }
        assertEquals("$name: three hooks in three methods", 4, hooked.toSet().size)
        for (method in listOf(writer, badge, counter)) {
            assertTrue("$name: ${method.signature()} is a store reader", method.signature() !in readerIds)
        }
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val readersBefore = readers.map { reader -> reader.visualCode().map(::text) }
        val writerBefore = writer.visualCode().map(::text)
        val badgeBefore = badge.visualCode().map(::text)
        val counterBefore = counter.visualCode().map(::text)

        // What each place is before anything changes. The rows: a write of a list into the holder's
        // list, the one the row factory is started on, from the register the filter will be handed.
        val put = writer.visualCode()[found.rows.at]
        assertEquals("$name: the rows hook goes in front of a write", Opcode.IPUT_OBJECT, put.opcode)
        val list = put.visualReference() as FieldReference
        assertEquals("$name: of a list", "Ljava/util/List;", list.type)
        assertEquals("$name: from the hooked register", found.rows.register, (put as TwoRegisterInstruction).registerA)
        val factory = classes.values.flatMap { it.methods }.single { method -> method.visualCode().any { it.visualString() == INBOX_ROWS } }
        val starter = classes.values.flatMap { it.methods }.single { method -> method.visualCode().any { it.makesOneOf(setOf(factory.definingClass)) } }
        assertTrue("$name: ${starter.signature()} starts the row factory on ${list.signature()}",
            starter.visualCode().any { it.opcode == Opcode.IGET_OBJECT && (it.visualReference() as? FieldReference)?.signature() == list.signature() })
        // The folders: a folder's chats, just read into the hooked register by the snapshot's own
        // folder count, which the snapshot calls.
        val counterCode = counter.visualCode()
        val folderRead = counterCode[found.folders.at - 2].visualReference() as MethodReference
        assertEquals("$name: the folder count reads a list", "Ljava/util/List;", folderRead.returnType)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, counterCode[found.folders.at - 1].opcode)
        assertEquals("$name: into the hooked register", found.folders.register, (counterCode[found.folders.at - 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: the folder count is the snapshot's own", badge.definingClass, counter.definingClass)
        assertTrue("$name: the snapshot calls the folder count",
            badge.visualCode().any { (it.visualReference() as? MethodReference)?.signature() == counter.signature() })

        hideChatsFromInbox(found)

        val readersAfter = readers.map { reader ->
            context.mutableClassDefBy(reader.definingClass).methods.single { it.signature() == reader.signature() }
        }
        assertEquals("$name: the store's readers are untouched", readersBefore, readersAfter.map { it.visualCode().map(::text) })
        val filters = setOf(HIDDEN_INBOX, HIDDEN_FOLDER, HIDDEN_FILTER)
        assertTrue("$name: no store reader calls a filter", readersAfter.none { reader ->
            reader.visualCode().any { instruction -> instruction.visualReference()?.toString()?.let { it in filters } == true }
        })

        val rows = writer.visualCode()
        checkFiltered(name, "inbox's list of chats", rows, writerBefore, found.rows, HIDDEN_INBOX)
        assertEquals("$name: then the list is written", writerBefore[found.rows.at], text(rows[found.rows.at + 2]))

        val code = badge.visualCode()
        val at = found.badge.at
        checkFiltered(name, "unread badge's summaries", code, badgeBefore, found.badge, HIDDEN_FILTER)
        assertEquals("$name: the store's read comes right before", Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
        assertEquals("$name: the list is wrapped next",
            "Ljava/util/Collections;->unmodifiableList(Ljava/util/List;)Ljava/util/List;", code[at + 2].visualReference().toString())

        checkFiltered(name, "folder's chats", counter.visualCode(), counterBefore, found.folders, HIDDEN_FOLDER)

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
                if (method.signature() !in hooked) {
                    assertEquals("$name: native ${method.signature()} changed", original[index], method.visualCode().map(::text))
                }
            }
        }
    }

    /** The list in [site]'s register goes through [filter] at the site and comes back in the same register, and nothing else in the method moved. */
    private fun checkFiltered(name: String, what: String, code: List<Instruction>, before: List<String>, site: ListSite, filter: String) {
        val call = code[site.at]
        assertEquals("$name: the $what goes through the filter", filter, (call as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$name: the filter is handed the $what", site.register, (call as RegisterRangeInstruction).startRegister)
        assertEquals(1, call.registerCount)
        assertEquals("$name: the answer goes back in the same register", Opcode.MOVE_RESULT_OBJECT, code[site.at + 1].opcode)
        assertEquals(site.register, (code[site.at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: nothing else around the $what moved", before, code.take(site.at).map(::text) + code.drop(site.at + 2).map(::text))
        assertEquals("$name: the filter is called once there", 1, code.count { it.visualReference()?.toString() == filter })
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

        /**
         * The inbox's row factory and what leads to it, searched across the whole APK: the methods
         * logging [INBOX_ROWS], the methods making one of their classes, the List fields of their
         * own class those read, and every write of one of those fields (the method and the index).
         */
        private class RowAnchors(val factories: List<Method>, val starters: List<Method>, val lists: List<FieldReference>, val writes: List<Pair<Method, Int>>)

        private val rowCached = mutableMapOf<String, RowAnchors>()

        private fun rowAnchors(bundle: File): RowAnchors = rowCached.getOrPut(bundle.absolutePath) {
            val holders = FixtureDex.classesHolding(bundle, INBOX_ROWS)
            val factories = holders.flatMap { classDef -> classDef.methods.filter { method -> method.visualCode().any { it.visualString() == INBOX_ROWS } } }
            val factoryTypes = holders.mapTo(HashSet()) { it.type }
            val starters = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in factoryTypes } }) { method ->
                method.visualCode().any { it.makesOneOf(factoryTypes) }
            }
            val lists = starters.flatMap { starter ->
                starter.visualCode().filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.visualReference() as? FieldReference }
                    .filter { it.type == "Ljava/util/List;" && it.definingClass == starter.definingClass }
            }.distinctBy { it.signature() }
            val listIds = lists.mapTo(HashSet()) { it.signature() }
            val owners = lists.mapTo(HashSet()) { it.definingClass }
            val writers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in owners } }) { method ->
                method.visualCode().any { it.writesOneOf(listIds) }
            }
            val writes = writers.flatMap { method ->
                val code = method.visualCode()
                code.indices.filter { code[it].writesOneOf(listIds) }.map { method to it }
            }
            RowAnchors(factories, starters, lists, writes)
        }

        private fun Instruction.makesOneOf(types: Set<String>): Boolean =
            opcode == Opcode.NEW_INSTANCE && (visualReference() as? TypeReference)?.type?.let { it in types } == true

        private fun Instruction.writesOneOf(fields: Set<String>): Boolean =
            opcode == Opcode.IPUT_OBJECT && (visualReference() as? FieldReference)?.signature()?.let { it in fields } == true

        /**
         * The thread store, the chat key, every type the store's readers load fields of, the unread
         * badge snapshot's class, the row factory with the classes starting it and writing its list,
         * and the extension's class.
         */
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
            val views = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == "Ljava/util/LinkedHashSet;" } }) { it.badgeReadAt() != null }
            named += views.map { it.definingClass }
            val rows = rowAnchors(bundle)
            named += (rows.factories + rows.starters + rows.writes.map { it.first }).map { it.definingClass }
            classes += FixtureDex.classes(bundle, named.filter { it.startsWith("L") && it !in classes }.toSet())
            classes[HIDDEN_CHATS] = ExtensionDex.classDef(HIDDEN_CHATS)
            classes
        }
    }
}
