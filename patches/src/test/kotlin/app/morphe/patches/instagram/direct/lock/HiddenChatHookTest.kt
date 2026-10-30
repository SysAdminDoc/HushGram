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
import app.morphe.patches.instagram.FixtureCache
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.direct.seen.visualString
import app.morphe.patches.instagram.misc.extension.jumpTargets
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
        assertTrue("no inbox redraw bridge: $declared", HIDDEN_REDRAW.substringAfter("->") in declared)
    }

    /** Writes that something jumps to, counted by [check], so the branch check there can't pass on none. */
    private var jumpedToWrites = 0

    @Test
    fun eachDeclaredBuildFiltersItsInbox() {
        fixtures { bundle -> check(bundle.name, summaryClasses(bundle)) }
        // 452's two writes are both jumped to from the branch picking the list.
        assertTrue("no write of the inbox's list was a jump target, so the branch check proved nothing", jumpedToWrites > 0)
    }

    @Test
    fun eachOtherBuildDoesToo() {
        for (bundle in Fixtures.otherBuilds()) check(bundle.parentFile.name, summaryClasses(bundle))
    }

    /**
     * Across each whole APK, not only the classes the other tests slice out: one method logs the row
     * factory's name, one method starts the factory, it starts it on one list of its own class, and
     * every instruction anywhere writing that list (one on 450 and 451, two on 452) is followed by
     * the starter on the holder it wrote to.
     */
    @Test
    fun everyBuildHasOneRowFactoryOneStarterAndStartsItAfterEachWriteOfItsList() {
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
            assertTrue("$name: nothing writes ${rows.lists.single().signature()}", rows.writes.isNotEmpty())
            val starter = rows.starters.single()
            for ((method, at) in rows.writes) {
                val code = method.visualCode()
                val start = code.getOrNull(at + 1)
                assertTrue("$name: ${method.signature()}@$at doesn't start the row factory next",
                    start?.opcode == Opcode.INVOKE_STATIC && (start.visualReference() as? MethodReference)?.signature() == starter.signature())
                assertEquals("$name: on the holder it wrote to", (code[at] as TwoRegisterInstruction).registerB, (start as FiveRegisterInstruction).registerC)
                assertEquals(1, start.registerCount)
            }
            // The flag the starter checks: screens' onPause turn it off, so a redraw while you're in
            // HushGram settings waits for the inbox's resume, which starts the factory again.
            val holder = starter.definingClass
            assertTrue("$name: an onPause outside $holder turns its flag off: ${rows.flagWriters.map { it.signature() }}",
                rows.flagWriters.any { it.definingClass != holder && it.name == "onPause" })
        }
    }

    @Test
    fun dexBackedInstructionReReadsResolveTheSameTargets() = fixtures { bundle ->
        val types = summaryClasses(bundle).keys - HIDDEN_CHATS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(HIDDEN_CHATS))
        val found = context.findHiddenChatTargets()
        hideChatsFromInbox(found)
        assertTrue("${bundle.name}: no write of the inbox's list", found.rows.isNotEmpty())
        for (write in found.rows) assertEquals(Opcode.INVOKE_STATIC, write.site.method.visualCode()[write.site.at].opcode)
        for (site in listOf(found.badge, found.folders)) {
            assertEquals(Opcode.INVOKE_STATIC_RANGE, site.method.visualCode()[site.at].opcode)
        }
        assertEquals(Opcode.INVOKE_STATIC, found.redraw.visualCode()[2].opcode)
    }

    @Test
    fun aBuildWithoutTheRowFactoryFailsThePatch() = refuses("expected exactly one inbox row factory") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == INBOX_ROWS } }
    }

    /** Every write of the list gone (each turned into a nop, so the holder and its starter stay): refused. */
    @Test
    fun aBuildWhereNothingWritesTheInboxsListFailsThePatch() = fixtures { bundle ->
        val context = PatchContexts.of(summaryClasses(bundle).values)
        for ((native, at) in rowAnchors(bundle).writes) {
            context.mutableClassDefBy(native.definingClass).methods.single { it.signature() == native.signature() }.replaceInstruction(at, "nop")
        }
        val refusal = assertThrows(PatchException::class.java) { context.findHiddenChatTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains("expected one write of the inbox's list of chats"))
    }

    /**
     * The filter's answer replaces the list in the register the write stores, so the writer must not
     * read that register again after the write. A writer that does is refused before anything
     * changes, whichever of the writes it is.
     */
    @Test
    fun aWriterThatReadsTheListAgainAfterTheWriteFailsThePatch() = fixtures { bundle ->
        val writes = rowAnchors(bundle).writes
        assertTrue("${bundle.name}: no write of the inbox's list", writes.isNotEmpty())
        for ((native, at) in writes) {
            val context = PatchContexts.of(summaryClasses(bundle).values)
            val writer = context.mutableClassDefBy(native.definingClass).methods.single { it.signature() == native.signature() }
            val value = (writer.visualCode()[at] as TwoRegisterInstruction).registerA
            writer.addInstructions(at + 1, "invoke-interface/range { v$value .. v$value }, Ljava/util/List;->size()I")
            val refusal = assertThrows(PatchException::class.java) { context.findHiddenChatTargets() }
            assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains("reads the inbox's list of chats again after it writes it"))
        }
    }

    /**
     * A write the starter doesn't follow straight away may not be a list the inbox draws, so it's
     * refused, whichever of the writes it is.
     */
    @Test
    fun aWriteNotFollowedByTheStarterFailsThePatch() = fixtures { bundle ->
        val writes = rowAnchors(bundle).writes
        assertTrue("${bundle.name}: no write of the inbox's list", writes.isNotEmpty())
        for ((native, at) in writes) {
            val context = PatchContexts.of(summaryClasses(bundle).values)
            val writer = context.mutableClassDefBy(native.definingClass).methods.single { it.signature() == native.signature() }
            writer.addInstructions(at + 1, "nop")
            val refusal = assertThrows(PatchException::class.java) { context.findHiddenChatTargets() }
            assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains("doesn't start the inbox row factory on its holder right after"))
        }
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

    /**
     * 452's See all reader takes its results from the search state's static helper, which must do
     * nothing but read them and cast them to a list. One that does anything more is refused.
     */
    @Test
    fun aSeeAllListHelperThatDoesMoreThanReadAndCastFailsThePatch() {
        fixtures { bundle ->
            val classes = searchClasses(bundle)
            val reader = classes.values.flatMap { it.methods }.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.seeAllListAt() != null }
            val at = reader.seeAllListAt()!!
            val code = reader.visualCode()
            if (code[at].opcode != Opcode.MOVE_RESULT_OBJECT) return@fixtures
            val call = code[at - 1].visualReference() as MethodReference
            val context = PatchContexts.of(classes.values)
            val helper = context.mutableClassDefBy(call.definingClass).methods.single {
                it.name == call.name && it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString) && it.returnType == call.returnType
            }
            helper.addInstructions(helper.visualCode().size - 1, "nop")
            val refusal = assertThrows(PatchException::class.java) { context.findSearchTargets() }
            assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains("doesn't just read and cast the results"))
        }
        // 453 reads the results in place again, so this only bites on a declared build that uses the helper.
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
        // 451 casts the results itself; 452 keeps what the search state's helper answers, a List.
        val kept = seeAll[at - 1]
        val helper = seeAll.getOrNull(at - 2)?.visualReference() as? MethodReference
        assertTrue("$name: the See all reader casts its results to a list first, or takes them from a static helper answering one",
            kept.opcode == Opcode.CHECK_CAST || (kept.opcode == Opcode.MOVE_RESULT_OBJECT && seeAll[at - 2].opcode == Opcode.INVOKE_STATIC &&
                helper?.returnType == "Ljava/util/List;"))
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
        val writes = found.rows
        assertTrue("$name: no write of the inbox's list", writes.isNotEmpty())
        val writers = writes.map { it.site.method }
        assertEquals("$name: one write in each writer", writers.size, writers.map { it.signature() }.toSet().size)
        val badge = found.badge.method
        val counter = found.folders.method
        val hooked = (writers + listOf(badge, counter, found.bridge, found.redraw)).map { it.signature() }
        assertEquals("$name: each hook in its own method, and two bridges", writers.size + 4, hooked.toSet().size)
        for (method in writers + listOf(badge, counter)) {
            assertTrue("$name: ${method.signature()} is a store reader", method.signature() !in readerIds)
        }
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val readersBefore = readers.map { reader -> reader.visualCode().map(::text) }
        val writersBefore = writers.map { writer -> writer.visualCode().map(::text) }
        val targetsBefore = writers.map { writer -> writer.jumpTargets() }
        val badgeBefore = badge.visualCode().map(::text)
        val counterBefore = counter.visualCode().map(::text)

        // What each place is before anything changes. The rows: a write of a list into the holder's
        // list, the one the row factory is started on, from the register the filter will be handed.
        val factory = classes.values.flatMap { it.methods }.single { method -> method.visualCode().any { it.visualString() == INBOX_ROWS } }
        val starter = classes.values.flatMap { it.methods }.single { method -> method.visualCode().any { it.makesOneOf(setOf(factory.definingClass)) } }
        val puts = writes.map { write ->
            val put = write.site.method.visualCode()[write.site.at]
            assertEquals("$name: the rows hook goes in front of a write", Opcode.IPUT_OBJECT, put.opcode)
            val list = put.visualReference() as FieldReference
            assertEquals("$name: of a list", "Ljava/util/List;", list.type)
            assertEquals("$name: from the hooked register", write.site.register, (put as TwoRegisterInstruction).registerA)
            assertEquals("$name: into the holder the filter is handed", write.holder, put.registerB)
            assertTrue("$name: ${starter.signature()} starts the row factory on ${list.signature()}",
                starter.visualCode().any { it.opcode == Opcode.IGET_OBJECT && (it.visualReference() as? FieldReference)?.signature() == list.signature() })
            put
        }
        assertEquals("$name: every write is of the same list", 1, puts.map { text(it) }.toSet().size)
        val put = puts.first()
        // Right after each write Instagram starts the row factory on the holder. The redraw bridge
        // takes the same two steps.
        val starts = writes.map { write ->
            val start = write.site.method.visualCode()[write.site.at + 1]
            assertEquals("$name: the writer starts the row factory next", "${Opcode.INVOKE_STATIC.name} ${starter.signature()}", text(start))
            assertEquals("$name: on the holder", write.holder, (start as FiveRegisterInstruction).registerC)
            assertEquals(1, start.registerCount)
            start
        }
        val start = starts.first()
        // The starter does nothing while a flag of its holder is off, and the holder's resume turns
        // the flag on and starts the factory again.
        val starterCode = starter.visualCode()
        val flagAt = starterCode.indexOfFirst { it.opcode == Opcode.IGET_BOOLEAN }
        val flag = starterCode[flagAt].visualReference() as FieldReference
        assertEquals("$name: the starter's flag is its holder's", starter.definingClass, flag.definingClass)
        assertEquals("$name: and it branches on the flag", Opcode.IF_EQZ, starterCode[flagAt + 1].opcode)
        assertTrue("$name: before it calls anything", starterCode.take(flagAt).none { it.opcode.name.startsWith("INVOKE") })
        val resumes = classes.getValue(starter.definingClass).methods.filter { method ->
            val code = method.visualCode()
            code.any { it.opcode == Opcode.IPUT_BOOLEAN && (it.visualReference() as? FieldReference)?.signature() == flag.signature() } &&
                code.any { (it.visualReference() as? MethodReference)?.signature() == starter.signature() }
        }
        assertEquals("$name: one method of the holder turns the flag on and starts the factory", 1, resumes.size)
        assertTrue("$name: the starter is a public static call taking its holder",
            AccessFlags.STATIC.isSet(starter.accessFlags) && AccessFlags.PUBLIC.isSet(starter.accessFlags) &&
                starter.parameterTypes.map(Any::toString) == listOf(starter.definingClass))
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

        writes.forEachIndexed { index, write ->
            val site = write.site
            val rows = site.method.visualCode()
            val writerBefore = writersBefore[index]
            val call = rows[site.at]
            assertEquals("$name: the inbox's list of chats goes through the filter", HIDDEN_INBOX, (call as ReferenceInstruction).reference.toString())
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            assertEquals("$name: handed the holder", write.holder, (call as FiveRegisterInstruction).registerC)
            assertEquals("$name: and the list", site.register, call.registerD)
            assertEquals(2, call.registerCount)
            assertEquals("$name: the answer goes back in the list's register", Opcode.MOVE_RESULT_OBJECT, rows[site.at + 1].opcode)
            assertEquals(site.register, (rows[site.at + 1] as OneRegisterInstruction).registerA)
            assertEquals("$name: nothing else around the list moved", writerBefore, rows.take(site.at).map(::text) + rows.drop(site.at + 2).map(::text))
            assertEquals("$name: the filter is called once there", 1, rows.count { it.visualReference()?.toString() == HIDDEN_INBOX })
            assertEquals("$name: then the list is written", writerBefore[site.at], text(rows[site.at + 2]))
            // A branch that went to the write now lands on the filter, so no way to the write skips it.
            val expected = targetsBefore[index].mapTo(sortedSetOf()) { if (it > site.at) it + 2 else it }
            assertEquals("$name: ${site.method.signature()} branches where it did, onto the filter for the write", expected, site.method.jumpTargets())
        }
        jumpedToWrites += writes.indices.count { site -> writes[site].site.at in targetsBefore[site] }

        val redraw = found.redraw.visualCode()
        val holderParameter = found.redraw.parameterRegisterNumber(0)
        val listParameter = found.redraw.parameterRegisterNumber(1)
        assertEquals("$name: the redraw bridge casts to the holder", "${Opcode.CHECK_CAST.name} ${starter.definingClass}", text(redraw[0]))
        assertEquals(holderParameter, (redraw[0] as OneRegisterInstruction).registerA)
        assertEquals("$name: writes the holder's list as the subscriber does", text(put), text(redraw[1]))
        assertEquals("$name: the list handed over", listParameter, (redraw[1] as TwoRegisterInstruction).registerA)
        assertEquals("$name: into the holder", holderParameter, redraw[1].let { (it as TwoRegisterInstruction).registerB })
        assertEquals("$name: then starts the row factory as the subscriber does", text(start), text(redraw[2]))
        assertEquals("$name: on the holder", holderParameter, (redraw[2] as FiveRegisterInstruction).registerC)
        assertEquals(1, (redraw[2] as FiveRegisterInstruction).registerCount)
        assertEquals("$name: and returns", Opcode.RETURN_VOID, redraw[3].opcode)

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
        private val cached = FixtureCache<Map<String, ClassDef>>()
        private val searchCached = FixtureCache<Map<String, ClassDef>>()

        private val recentCached = FixtureCache<Map<String, ClassDef>>()

        /** The recent searches store, the chat type and the extension's class. */
        private fun recentClasses(bundle: File): Map<String, ClassDef> = recentCached.get(bundle) {
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
        private fun searchClasses(bundle: File): Map<String, ClassDef> = searchCached.get(bundle) {
            val classes = mutableMapOf<String, ClassDef>()
            FixtureDex.classesHolding(bundle, "ibc_chats_context_lines").forEach { classes[it.type] = it }
            val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == SEE_ALL_SCREEN } }) { it.seeAllListAt() != null }
            val builders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == MESSAGE_HIT_THREAD } }) { it.buildsMessageHits() }
            val named = (readers + builders).map { it.definingClass }.toMutableSet()
            // 452's See all reader takes its results from a static helper of the search state it's handed first.
            named += readers.mapNotNull { reader -> reader.parameterTypes.firstOrNull()?.toString()?.takeIf { it.startsWith("L") } }
            named += setOf(THREAD_KEY, SHARE_TARGET)
            classes += FixtureDex.classes(bundle, named.filter { it !in classes }.toSet())
            classes[HIDDEN_CHATS] = ExtensionDex.classDef(HIDDEN_CHATS)
            classes
        }

        /**
         * The inbox's row factory and what leads to it, searched across the whole APK: the methods
         * logging [INBOX_ROWS], the methods making one of their classes, the List fields of their
         * own class those read, every write of one of those fields (the method and the index), and
         * every method writing one of the boolean fields of their own class the starters read.
         */
        private class RowAnchors(
            val factories: List<Method>,
            val starters: List<Method>,
            val lists: List<FieldReference>,
            val writes: List<Pair<Method, Int>>,
            val flagWriters: List<Method>,
        )

        private val rowCached = FixtureCache<RowAnchors>()

        private fun rowAnchors(bundle: File): RowAnchors = rowCached.get(bundle) {
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
            val flagIds = starters.flatMap { starter ->
                starter.visualCode().filter { it.opcode == Opcode.IGET_BOOLEAN }.mapNotNull { it.visualReference() as? FieldReference }
                    .filter { it.definingClass == starter.definingClass }
            }.mapTo(HashSet()) { it.signature() }
            val owners = lists.mapTo(HashSet()) { it.definingClass }
            val writers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in owners } }) { method ->
                method.visualCode().any { it.writesOneOf(listIds) || it.setsOneOf(flagIds) }
            }
            val writes = writers.flatMap { method ->
                val code = method.visualCode()
                code.indices.filter { code[it].writesOneOf(listIds) }.map { method to it }
            }
            RowAnchors(factories, starters, lists, writes, writers.filter { method -> method.visualCode().any { it.setsOneOf(flagIds) } })
        }

        private fun Instruction.makesOneOf(types: Set<String>): Boolean =
            opcode == Opcode.NEW_INSTANCE && (visualReference() as? TypeReference)?.type?.let { it in types } == true

        private fun Instruction.writesOneOf(fields: Set<String>): Boolean =
            opcode == Opcode.IPUT_OBJECT && (visualReference() as? FieldReference)?.signature()?.let { it in fields } == true

        private fun Instruction.setsOneOf(fields: Set<String>): Boolean =
            opcode == Opcode.IPUT_BOOLEAN && (visualReference() as? FieldReference)?.signature()?.let { it in fields } == true

        /**
         * The thread store, the chat key, every type the store's readers load fields of, the unread
         * badge snapshot's class, the row factory with the classes starting it and writing its list,
         * and the extension's class.
         */
        private fun summaryClasses(bundle: File): Map<String, ClassDef> = cached.get(bundle) {
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
