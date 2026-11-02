/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.reels.scrolling.PAGER_SETUP
import app.morphe.patches.instagram.reels.scrolling.VIEW_PAGER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SwipeChatReelsHookTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val gates = "Lfixture/Gates;"
    private val opener = "Lfixture/Opener;"
    private val flagHex = CHAT_REELS_FLAG.toString(16)
    private val source = "Lfixture/Source;"
    private val chatSource = "Lfixture/ChatSource;"
    private val fetcher = "Lfixture/Fetcher;"
    private val items = "Lfixture/Items;"
    private val viewer = "Lfixture/Viewer;"
    private val refresher = "Lfixture/Refresher;"
    private val puller = "Lfixture/Puller;"
    private val setupOwner = "Lfixture/Setup;"
    private val holder = "Lfixture/Holder;"
    private val controller = "Lfixture/FlashController;"
    private val item = "Lfixture/Item;"
    private val adapter = "Lfixture/Adapter;"
    private val adapterItems = "Lfixture/Items;"
    private val tapped = "Landroid/view/View;"
    private val bounds = "Landroid/graphics/RectF;"

    /** The hooks the patch writes are in the ChatReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(CHAIN, STOP, STOP_CACHED, PLACEHOLDER, OPEN, REFRESH)) assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        val exit = ExtensionDex.classDef(CHAT_REELS_EXIT).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$VIEWER is not in the extension: $exit", VIEWER.substringAfter("->") in exit)
    }

    /** The dual paging request asks the extension first, for the stock side of a chat's source only (#390). */
    @Test
    fun theStockSideOfAChatAsksFirst() {
        val context = PatchContexts.of(pagingClasses())
        val found = context.findChatReelsEnd()
        val before = found.paging.implementation!!.instructions.count()

        context.stopAfterChatReels(found)

        assertEquals(chatSource, found.chat)
        assertStops("stand-in", context.mutableClassDefBy(fetcher).methods.single { it.name == "page" }, chatSource, before)
        val chat = context.mutableClassDefBy(chatSource).methods.single().code()
        assertTrue("the chat's source was touched", chat.none { it.referenceText() == STOP })
    }

    /** A build the end finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildTheEndFinderCantReadFailsBeforeAnythingChanges() {
        val paging = "$fetcher->page"
        val shape = "isn't an instance request taking a source and three flags"
        val branch = "the first branch in $paging isn't the last flag picking $source->chat, the chat's own reels"
        val cases = listOf(
            pagingClasses(pagings = 0) to "expected one method loading \"$DUAL_PAGING\", found 0",
            pagingClasses(pagings = 2) to "expected one method loading \"$DUAL_PAGING\", found 2",
            pagingClasses(static = true) to "$paging(${source}ZZZ)V $shape",
            pagingClasses(takes = listOf(source, "Z", "Z")) to "$paging(${source}ZZ)V $shape",
            pagingClasses(locals = 0) to "$paging has no local for the answer",
            pagingClasses(entered = true) to "a jump or exception handler enters $paging at its first instruction",
            pagingClasses(pages = 0) to "expected one method asking for $CHAT_REELS_PATH, found 0",
            pagingClasses(pages = 2) to "expected one method asking for $CHAT_REELS_PATH, found 2",
            pagingClasses(chatIs = emptyList()) to "$chatSource asks for $CHAT_REELS_PATH but isn't a $source, what $paging pages",
            pagingClasses(flag = "p3") to branch,
            pagingClasses(test = "if-nez") to branch,
            pagingClasses(chatOnTheJump = false) to branch,
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.stopAfterChatReels() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == STOP } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the dual paging request asks first for the stock side of a chat's source. */
    @Test
    fun eachDeclaredBuildStopsAfterAChatsReels() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                stops(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildStopsAfterAChatsReelsToo() {
        for (base in Fixtures.otherBuilds()) stops(base, base.parentFile.name)
    }

    private fun stops(bundle: File, label: String) {
        val loading = (FixtureDex.classesLoading(bundle, DUAL_PAGING) + FixtureDex.classesLoading(bundle, CHAT_REELS_PATH))
            .distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading))

        val found = context.findChatReelsEnd()
        val before = found.paging.implementation!!.instructions.count()
        context.stopAfterChatReels(found)

        val takes = found.paging.parameterTypes.map(CharSequence::toString)
        val paging = context.mutableClassDefBy(found.paging.definingClass).methods
            .single { it.name == found.paging.name && it.parameterTypes.map(CharSequence::toString) == takes }
        assertStops(label, paging, found.chat, before)
        val hooked = loading.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
            .count { method -> method.code().any { it.referenceText() == STOP } }
        assertEquals("${label}: methods hooked", 1, hooked)
    }

    /**
     * First thing: the last flag tested, the source checked against [chat], the hook asked, and a
     * yes returning. Every no lands on Instagram's own first instruction.
     */
    private fun assertStops(what: String, method: Method, chat: String, before: Int) {
        val code = method.code()
        val last = method.implementation!!.registerCount - 1
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == STOP })
        assertEquals("$what: added", before + 8, code.size)
        assertEquals("$what: the flag test", Opcode.IF_EQZ, code[0].opcode)
        assertEquals("$what: the last flag", last, (code[0] as OneRegisterInstruction).registerA)
        assertEquals("$what: the source's copy", Opcode.MOVE_OBJECT_FROM16, code[1].opcode)
        assertEquals("$what: the source", last - 3, (code[1] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the chat check", Opcode.INSTANCE_OF, code[2].opcode)
        assertEquals("$what: the chat's source", chat, code[2].referenceText())
        assertEquals("$what: the hook", STOP, code[4].referenceText())
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[5].opcode)
        assertEquals("$what: the stop", Opcode.RETURN_VOID, code[7].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        for (at in listOf(0, 3, 6)) {
            assertEquals("$what: branch $at", Opcode.IF_EQZ, code[at].opcode)
            val target = address.indexOf(address[at] + (code[at] as OffsetInstruction).codeOffset)
            assertEquals("$what: branch $at goes to Instagram", 8, target)
        }
    }

    /**
     * A fetcher shaped like Instagram 453's 0Dyx: its dual paging request loads [DUAL_PAGING], and an
     * `if-eqz` on its last flag jumps to the source's page of the chat's own reels, else asks for the
     * stock side. A source shaped like 0Mfh asks for [CHAT_REELS_PATH] in that page. The fetcher holds
     * the source it pages as the items interface the source extends, as 0Dyz holds a 0Nwk, next to a
     * field of another type and a static one.
     */
    private fun pagingClasses(
        pagings: Int = 1,
        takes: List<String> = listOf(source, "Z", "Z", "Z"),
        static: Boolean = false,
        locals: Int = 2,
        entered: Boolean = false,
        pages: Int = 1,
        chatIs: List<String> = listOf(source),
        flag: String = if (static) "p${takes.size - 1}" else "p${takes.size}",
        test: String = "if-eqz",
        chatOnTheJump: Boolean = true,
        sourceFields: Int = 1,
    ): List<ClassDef> {
        val (jump, fall) = if (chatOnTheJump) "chat" to "stock" else "stock" to "chat"
        val held = if (static) "p0" else "p1"
        val scratch = if (locals == 0) "p2" else "v${locals - 1}"
        val name = if (pagings == 0) "AbstractPaginatingClipsItemsFetcher.makeFetchRequest" else DUAL_PAGING
        val body = """
            ${if (entered) ":top" else ""}
            const-string $scratch, "$name"
            $test $flag, :chat
            invoke-interface {$held}, $source->$fall()V
            ${if (entered) "goto :top" else "return-void"}
            :chat
            invoke-interface {$held}, $source->$jump()V
            return-void
        """
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
        val requests = List(maxOf(pagings, 1)) { method(fetcher, if (it == 0) "page" else "page$it", takes, "V", locals, flags, body) }
        val asks = List(maxOf(pages, 1)) {
            val load = if (pages == 0) "" else "const-string v0, \"$CHAT_REELS_PATH\"\n"
            method(chatSource, if (it == 0) "chat" else "chat$it", emptyList(), "V", 1, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "${load}return-void")
        }
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val fields = List(sourceFields) { ImmutableField(fetcher, if (it == 0) "items" else "items$it", items, final, null, null, null) } +
            ImmutableField(fetcher, "NONE", items, final or AccessFlags.STATIC.value, null, null, null) +
            ImmutableField(fetcher, "name", "Ljava/lang/String;", final, null, null, null)
        return listOf(classDef(fetcher, requests, fields = fields), classDef(chatSource, asks, chatIs), classDef(source, emptyList(), listOf(items)))
    }

    /** A viewer opened from a chat asks the extension first when it asks for more reels (#390). */
    @Test
    fun aViewerFromAChatAsksFirstForMore() {
        val context = PatchContexts.of(viewerClasses())
        val found = context.findMoreReels()
        val before = found.request.implementation!!.instructions.count()

        context.stopMoreReels(found)

        assertEquals("$viewer->config:$VIEWER_CONFIG", found.config)
        assertEquals("$VIEWER_CONFIG->chat:$DIRECT_DATA", found.direct)
        assertEquals("$viewer->fetcher:$fetcher", found.fetcher)
        assertEquals("$fetcher->items:$items", found.source)
        assertEquals(chatSource, found.chat)
        val methods = context.mutableClassDefBy(viewer).methods
        assertAsksForMore("stand-in", methods.single { it.name == "more" }, found, before)
        assertTrue("the viewer's opening was touched", methods.single { it.name == "open" }.code().none { it.referenceText() == STOP })
    }

    /** A request handing its fetcher over in a range asks first too. */
    @Test
    fun aRangeCallOfTheFetcherAsksFirstToo() {
        val context = PatchContexts.of(viewerClasses(range = true))
        val found = context.findMoreReels()
        val before = found.request.implementation!!.instructions.count()

        context.stopMoreReels(found)

        assertEquals("$viewer->fetcher:$fetcher", found.fetcher)
        assertAsksForMore("range", context.mutableClassDefBy(viewer).methods.single { it.name == "more" }, found, before)
    }

    /** A build the more finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildTheMoreFinderCantReadFailsBeforeAnythingChanges() {
        val more = "$viewer->more"
        val shape = "isn't an instance request returning nothing"
        val cases = listOf(
            viewerClasses(fetchers = emptyList()) to "$more asks $fetcher through 0 fields of its viewer, not one",
            viewerClasses(fetchers = listOf("fetcher", "fetcher2")) to "$more asks $fetcher through 2 fields of its viewer, not one",
            viewerClasses(fetcherRead = false) to "$more asks $fetcher through 0 fields of its viewer, not one",
            viewerClasses(held = 0) to "$fetcher holds 0 fields for the source it pages, not one",
            viewerClasses(held = 2) to "$fetcher holds 2 fields for the source it pages, not one",
            viewerClasses(requests = 0) to "expected one method loading \"$MORE_REELS\", found 0",
            viewerClasses(requests = 2) to "expected one method loading \"$MORE_REELS\", found 2",
            viewerClasses(static = true) to "$more(Ljava/util/List;ZZZ)V $shape",
            viewerClasses(returns = "Z") to "$more(Ljava/util/List;ZZZ)Z $shape",
            viewerClasses(locals = 0) to "$more has no local for the answer",
            viewerClasses(entered = true) to "a jump or exception handler enters $more at its first instruction",
            viewerClasses(reads = emptyList()) to "$more reads 0 $VIEWER_CONFIG fields of its viewer, not one",
            viewerClasses(reads = listOf("config", "config2")) to "$more reads 2 $VIEWER_CONFIG fields of its viewer, not one",
            viewerClasses(chats = 0) to "$VIEWER_CONFIG holds 0 $DIRECT_DATA fields, not one",
            viewerClasses(chats = 2) to "$VIEWER_CONFIG holds 2 $DIRECT_DATA fields, not one",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.stopMoreReels() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == STOP } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the viewer's request for more reels asks first when it was opened from a chat. */
    @Test
    fun eachDeclaredBuildAsksFirstForMore() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                asksForMore(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksFirstForMoreToo() {
        for (base in Fixtures.otherBuilds()) asksForMore(base, base.parentFile.name)
    }

    private fun asksForMore(bundle: File, label: String) {
        val loading = (FixtureDex.classesLoading(bundle, MORE_REELS) + FixtureDex.classesLoading(bundle, DUAL_PAGING) +
            FixtureDex.classesLoading(bundle, CHAT_REELS_PATH)).distinctBy { it.type }
        val pooled = FixtureDex.withStringPools(bundle, loading)
        // The source the dual paging request takes, whose interfaces the fetcher's source field may be typed as.
        val paged = PatchContexts.of(pooled).findChatReelsEnd().paging.parameterTypes.first().toString()
        val config = FixtureDex.classes(bundle, setOf(VIEWER_CONFIG, paged)).values
        val context = PatchContexts.of((pooled + config).distinctBy { it.type })

        val found = context.findMoreReels()
        val before = found.request.implementation!!.instructions.count()
        context.stopMoreReels(found)

        val takes = found.request.parameterTypes.map(CharSequence::toString)
        val request = context.mutableClassDefBy(found.request.definingClass).methods
            .single { it.name == found.request.name && it.parameterTypes.map(CharSequence::toString) == takes }
        assertAsksForMore(label, request, found, before)
        val hooked = loading.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
            .count { method -> method.code().any { it.referenceText() == STOP } }
        assertEquals("${label}: methods hooked", 1, hooked)
    }

    /**
     * First thing: the viewer's fetcher read, and a fetcher paging the source of a chat's reels goes
     * on to Instagram, since the chat's own reels come through here too. Then the viewer's config
     * read, the chat it holds, the hook asked, and a yes returning. No fetcher goes on to the config;
     * a config or chat that isn't there, or a no, lands on Instagram's own first instruction.
     */
    private fun assertAsksForMore(what: String, method: Method, found: MoreReels, before: Int) {
        val code = method.code()
        val words = method.parameterTypes.size + method.parameterTypes.count { it.toString() == "J" || it.toString() == "D" }
        val self = method.implementation!!.registerCount - words - 1
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == STOP })
        assertEquals("$what: added", before + 15, code.size)
        for (at in listOf(0, 6)) {
            assertEquals("$what: the viewer's copy at $at", Opcode.MOVE_OBJECT_FROM16, code[at].opcode)
            assertEquals("$what: the viewer at $at", self, (code[at] as TwoRegisterInstruction).registerB)
        }
        assertEquals("$what: the fetcher read", Opcode.IGET_OBJECT, code[1].opcode)
        assertEquals("$what: the fetcher", found.fetcher, code[1].referenceText())
        assertEquals("$what: the fetcher's source", found.source, code[3].referenceText())
        assertEquals("$what: the chat's source check", Opcode.INSTANCE_OF, code[4].opcode)
        assertEquals("$what: the chat's source", found.chat, code[4].referenceText())
        assertEquals("$what: the config read", Opcode.IGET_OBJECT, code[7].opcode)
        assertEquals("$what: the config", found.config, code[7].referenceText())
        assertEquals("$what: the chat read", Opcode.IGET_OBJECT, code[9].opcode)
        assertEquals("$what: the chat", found.direct, code[9].referenceText())
        assertEquals("$what: the hook", STOP, code[11].referenceText())
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[12].opcode)
        assertEquals("$what: the stop", Opcode.RETURN_VOID, code[14].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        fun target(at: Int) = address.indexOf(address[at] + (code[at] as OffsetInstruction).codeOffset)
        assertEquals("$what: no fetcher", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: no fetcher goes on to the config", 6, target(2))
        assertEquals("$what: the chat's source", Opcode.IF_NEZ, code[5].opcode)
        assertEquals("$what: the chat's source goes to Instagram", 15, target(5))
        for (at in listOf(8, 10, 13)) {
            assertEquals("$what: branch $at", Opcode.IF_EQZ, code[at].opcode)
            assertEquals("$what: branch $at goes to Instagram", 15, target(at))
        }
    }

    /**
     * A viewer shaped like Instagram 453's 02HP: its request for more reels loads [MORE_REELS] and
     * reads the [VIEWER_CONFIG] it was opened with (twice, as 02HP does), next to a list of its own
     * and another class's config, and asks its fetcher, a field of its own, for the reels (as 02HQ
     * asks 029b through 0Dyz's request), after asking a list of its own something. A config holding
     * the chat as [DIRECT_DATA], next to a static one, and the fetcher and the chat's source of
     * [pagingClasses].
     */
    private fun viewerClasses(
        requests: Int = 1,
        static: Boolean = false,
        returns: String = "V",
        locals: Int = 2,
        entered: Boolean = false,
        reads: List<String> = listOf("config", "config"),
        chats: Int = 1,
        fetchers: List<String> = listOf("fetcher"),
        fetcherRead: Boolean = true,
        range: Boolean = false,
        held: Int = 1,
    ): List<ClassDef> {
        val scratch = if (locals == 0) "p2" else "v${locals - 1}"
        val name = if (requests == 0) "active_section_on_end" else MORE_REELS
        val call = if (range) "invoke-virtual/range {$scratch .. $scratch}" else "invoke-virtual {$scratch}"
        val asksFetchers = fetchers.joinToString("\n") { field ->
            val receiver = if (fetcherRead) "iget-object $scratch, p0, $viewer->$field:$fetcher" else "move-object $scratch, p0"
            "$receiver\n$call, $fetcher->fetch()V"
        }
        val body = """
            ${if (entered) ":top" else ""}
            const-string $scratch, "$name"
            ${reads.joinToString("\n") { "iget-object $scratch, p0, $viewer->$it:$VIEWER_CONFIG" }}
            iget-object $scratch, p0, $viewer->items:Ljava/util/List;
            invoke-interface {$scratch}, Ljava/util/List;->size()I
            $asksFetchers
            iget-object $scratch, p0, Lfixture/Other;->config:$VIEWER_CONFIG
            ${if (entered) "goto :top" else "return-void"}
        """
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val flags = final or (if (static) AccessFlags.STATIC.value else 0)
        val takes = listOf("Ljava/util/List;", "Z", "Z", "Z")
        val asks = List(maxOf(requests, 1)) { method(viewer, if (it == 0) "more" else "more$it", takes, returns, locals, flags, body) }
        val open = method(viewer, "open", emptyList(), "V", 1, final, "iget-object v0, p0, $viewer->config:$VIEWER_CONFIG\nreturn-void")
        val fields = List(chats) { ImmutableField(VIEWER_CONFIG, if (it == 0) "chat" else "chat$it", DIRECT_DATA, final, null, null, null) } +
            ImmutableField(VIEWER_CONFIG, "NONE", DIRECT_DATA, final or AccessFlags.STATIC.value, null, null, null) +
            ImmutableField(VIEWER_CONFIG, "thread", "Ljava/lang/String;", final, null, null, null)
        return listOf(classDef(viewer, asks + open), classDef(VIEWER_CONFIG, emptyList(), fields = fields)) + pagingClasses(sourceFields = held)
    }

    /** Every read of the viewer's permission to refresh outside the config asks the extension, handed the source (#390). */
    @Test
    fun eachReadOfThePermissionToRefreshAsks() {
        val context = PatchContexts.of(refreshClasses())
        val found = context.findRefreshReads()

        context.refreshFromChats(found)

        assertEquals("$VIEWER_CONFIG->refresh:Z", found.flag)
        assertEquals("$VIEWER_CONFIG->source:$VIEWER_SOURCE", found.source)
        assertEquals(setOf("$refresher->handle", "$puller->enable"), found.readers.map { "${it.definingClass}->${it.name}" }.toSet())
        assertAsksBeforeRefreshing("the refresh", context.mutableClassDefBy(refresher).methods.single { it.name == "handle" }, found, 1)
        assertAsksBeforeRefreshing("the pull", context.mutableClassDefBy(puller).methods.single { it.name == "enable" }, found, 2)
        val own = context.mutableClassDefBy(VIEWER_CONFIG).methods.flatMap { it.code() }
        assertTrue("the config's own read was touched", own.none { it.referenceText() == REFRESH })
    }

    /** A build the refresh finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildTheRefreshFinderCantReadFailsBeforeAnythingChanges() {
        val refresh = "$refresher->handle"
        val cases = listOf(
            refreshClasses(refreshes = 0) to "expected one method loading \"$REFRESH_NOT_ALLOWED\", found 0",
            refreshClasses(refreshes = 2) to "expected one method loading \"$REFRESH_NOT_ALLOWED\", found 2",
            refreshClasses(test = "if-nez") to "$refresh doesn't test a $VIEWER_CONFIG boolean first, its permission to refresh",
            refreshClasses(own = true) to "$refresh reads $VIEWER_CONFIG->refresh:Z into the config's own register",
            refreshClasses(sources = 0) to "$VIEWER_CONFIG holds 0 $VIEWER_SOURCE fields, not one",
            refreshClasses(sources = 2) to "$VIEWER_CONFIG holds 2 $VIEWER_SOURCE fields, not one",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.refreshFromChats() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == REFRESH } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build every read of the permission to refresh asks, the refresh's own among them. */
    @Test
    fun eachDeclaredBuildAsksBeforeRefreshing() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                asksBeforeRefreshing(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksBeforeRefreshingToo() {
        for (base in Fixtures.otherBuilds()) asksBeforeRefreshing(base, base.parentFile.name)
    }

    private fun asksBeforeRefreshing(bundle: File, label: String) {
        val refreshing = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, REFRESH_NOT_ALLOWED))
        val config = FixtureDex.classes(bundle, setOf(VIEWER_CONFIG)).values
        val flag = PatchContexts.of((refreshing + config).distinctBy { it.type }).findRefreshReads().flag
        fun reads(method: Method) = method.code().count { it.opcode == Opcode.IGET_BOOLEAN && it.referenceText() == flag }
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.fieldSection.none { it.toString() == flag }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.type != VIEWER_CONFIG && classDef.methods.any { reads(it) > 0 }) readers += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of((refreshing + config + readers).distinctBy { it.type })
        val found = context.findRefreshReads()
        val counts = readers.flatMap { it.methods }.filter { reads(it) > 0 }
            .associate { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("", "(", ")")}" to reads(it) }

        context.refreshFromChats(found)

        assertTrue("${label}: only ${counts.size} methods read $flag", counts.size >= 2)
        val hooked = readers.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
            .filter { method -> method.code().any { it.referenceText() == REFRESH } }
        assertEquals("${label}: methods asking", counts.keys, hooked.map { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("", "(", ")")}" }.toSet())
        for (method in hooked) {
            assertAsksBeforeRefreshing("$label ${method.definingClass}->${method.name}", method, found, counts.getValue("${method.definingClass}->${method.name}${method.parameterTypes.joinToString("", "(", ")")}"))
        }
        val refresh = refreshing.flatMap { it.methods }.filter { reads(it) > 0 }.map { "${it.definingClass}->${it.name}" }
        assertTrue("${label}: the refresh itself asks", hooked.any { "${it.definingClass}->${it.name}" in refresh })
    }

    /**
     * Right after each of the [reads] reads of the permission: a no goes on to Instagram as it was, a
     * yes reads the source from the same config into the answer's register, hands it to the hook, and
     * the hook's answer takes the read's place.
     */
    private fun assertAsksBeforeRefreshing(what: String, method: Method, found: RefreshReads, reads: Int) {
        val code = method.code()
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        val asks = code.indices.filter { code[it].referenceText() == REFRESH }
        assertEquals("$what: asks", reads, asks.size)
        assertEquals("$what: reads", reads, code.count { it.opcode == Opcode.IGET_BOOLEAN && it.referenceText() == found.flag })
        for (ask in asks) {
            val read = code[ask - 3]
            assertEquals("$what: the read before $ask", Opcode.IGET_BOOLEAN, read.opcode)
            assertEquals("$what: the permission before $ask", found.flag, read.referenceText())
            val answer = (read as TwoRegisterInstruction).registerA
            val test = code[ask - 2]
            assertEquals("$what: the test before $ask", Opcode.IF_EQZ, test.opcode)
            assertEquals("$what: the test's register before $ask", answer, (test as OneRegisterInstruction).registerA)
            assertEquals("$what: a no goes on to Instagram before $ask", ask + 2, address.indexOf(address[ask - 2] + (test as OffsetInstruction).codeOffset))
            val source = code[ask - 1] as TwoRegisterInstruction
            assertEquals("$what: the source before $ask", found.source, code[ask - 1].referenceText())
            assertEquals("$what: the source's register before $ask", answer, source.registerA)
            assertEquals("$what: from the same config before $ask", read.registerB, source.registerB)
            assertEquals("$what: the hook's argument at $ask", answer, (code[ask] as FiveRegisterInstruction).registerC)
            assertEquals("$what: the answer at $ask", Opcode.MOVE_RESULT, code[ask + 1].opcode)
            assertEquals("$what: the answer's register at $ask", answer, (code[ask + 1] as OneRegisterInstruction).registerA)
        }
    }

    /**
     * A refresh shaped like Instagram 453's 02HQ.A1T: it reads the config's permission first and
     * tests it, loading [REFRESH_NOT_ALLOWED] when it's no. A pull shaped like 01WS.A0j, reading the
     * permission twice and another flag between. A config with a source of [VIEWER_SOURCE] next to a
     * static one, whose own method reads the permission too.
     */
    private fun refreshClasses(
        refreshes: Int = 1,
        test: String = "if-eqz",
        own: Boolean = false,
        sources: Int = 1,
    ): List<ClassDef> {
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val answer = if (own) "v1" else "v0"
        val name = if (refreshes == 0) "refresh_allowed" else REFRESH_NOT_ALLOWED
        val body = """
            iget-object v1, p0, $refresher->config:$VIEWER_CONFIG
            iget-boolean $answer, v1, $VIEWER_CONFIG->refresh:Z
            $test $answer, :refused
            iget-boolean v0, v1, $VIEWER_CONFIG->other:Z
            return-void
            :refused
            const-string v0, "$name"
            return-void
        """
        val handles = List(maxOf(refreshes, 1)) { method(refresher, if (it == 0) "handle" else "handle$it", listOf("Z"), "V", 2, final, body) }
        val enable = method(puller, "enable", emptyList(), "V", 3, final, """
            iget-object v1, p0, $puller->config:$VIEWER_CONFIG
            iget-boolean v0, v1, $VIEWER_CONFIG->refresh:Z
            iget-boolean v2, v1, $VIEWER_CONFIG->other:Z
            and-int/2addr v0, v2
            iget-boolean v2, v1, $VIEWER_CONFIG->refresh:Z
            or-int/2addr v0, v2
            return-void
        """)
        val same = method(VIEWER_CONFIG, "same", emptyList(), "Z", 1, final, "iget-boolean v0, p0, $VIEWER_CONFIG->refresh:Z\nreturn v0")
        val fields = List(sources) { ImmutableField(VIEWER_CONFIG, if (it == 0) "source" else "source$it", VIEWER_SOURCE, final, null, null, null) } +
            ImmutableField(VIEWER_CONFIG, "NONE", VIEWER_SOURCE, final or AccessFlags.STATIC.value, null, null, null) +
            ImmutableField(VIEWER_CONFIG, "refresh", "Z", final, null, null, null) +
            ImmutableField(VIEWER_CONFIG, "other", "Z", final, null, null, null)
        return listOf(classDef(refresher, handles), classDef(puller, listOf(enable)), classDef(VIEWER_CONFIG, listOf(same), fields = fields))
    }

    /** The Reels pager setup hands the extension a viewer's pager and its source right before storing the pager (#390). */
    @Test
    fun theSetupHandsOverEachViewersPager() {
        val context = PatchContexts.of(pagerClasses())
        val found = context.findChatViewers()

        context.watchChatViewers(found)

        assertEquals("$holder->pager:$VIEW_PAGER", found.field)
        assertEquals("$holder->config:$VIEWER_CONFIG", found.config)
        assertEquals("$VIEWER_CONFIG->source:$VIEWER_SOURCE", found.source)
        assertEquals(1, found.pager)
        assertEquals(3, found.holder)
        assertEquals(0, found.free)
        assertWatches("stand-in", context.mutableClassDefBy(setupOwner).methods.single { it.name == "setup" }, found)
    }

    /** Code another patch puts ahead of the store after it was found doesn't move the hook off the store. */
    @Test
    fun codePutAheadOfThePagersStoreSinceDoesntMoveTheHook() {
        val context = PatchContexts.of(pagerClasses())
        val found = context.findChatViewers()
        found.setup.addInstructionsWithLabels(0, "const/4 v0, 0x0\nconst/4 v0, 0x1")

        context.watchChatViewers(found)

        assertWatches("moved", found.setup, found)
    }

    /** A build the pager finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePagerFinderCantReadFailsBeforeAnythingChanges() {
        val setup = "$setupOwner->setup"
        val cases = listOf(
            pagerClasses(setups = 0) to "expected one method loading \"$PAGER_SETUP\", found 0",
            pagerClasses(setups = 2) to "expected one method loading \"$PAGER_SETUP\", found 2",
            pagerClasses(stores = 0) to "expected $setup to store one $VIEW_PAGER, found 0",
            pagerClasses(stores = 2) to "expected $setup to store one $VIEW_PAGER, found 2",
            pagerClasses(entered = true) to "something in $setup jumps to its store of the pager",
            pagerClasses(configs = 0) to "$holder holds 0 $VIEWER_CONFIG fields, not one",
            pagerClasses(configs = 2) to "$holder holds 2 $VIEWER_CONFIG fields, not one",
            pagerClasses(locals = 1) to "$setup has 0 local register(s) up to v15",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.watchChatViewers() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == VIEWER } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the Reels pager setup hands over each viewer's pager right before storing it. */
    @Test
    fun eachDeclaredBuildHandsOverItsReelsPagers() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                watches(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHandsOverItsReelsPagersToo() {
        for (base in Fixtures.otherBuilds()) watches(base, base.parentFile.name)
    }

    private fun watches(bundle: File, label: String) {
        val loading = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, PAGER_SETUP))
        val holders = loading.flatMap { it.methods }.flatMap { it.code() }.mapNotNull { instruction ->
            val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            field?.definingClass?.takeIf { instruction.opcode == Opcode.IPUT_OBJECT && field.type == VIEW_PAGER }
        }.toSet()
        val context = PatchContexts.of((loading + FixtureDex.classes(bundle, holders + VIEWER_CONFIG).values).distinctBy { it.type })
        val found = context.findChatViewers()

        context.watchChatViewers(found)

        assertWatches(label, found.setup, found)
        val hooked = loading.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
            .count { method -> method.code().any { it.referenceText() == VIEWER } }
        assertEquals("${label}: methods hooked", 1, hooked)
    }

    /**
     * Right before the pager's store: the holder's config read into the free local, no config going
     * on to the store, the config's source, and the hook handed the pager and the source.
     */
    private fun assertWatches(what: String, setup: Method, found: ChatViewers) {
        val code = setup.code()
        val asks = code.indices.filter { code[it].referenceText() == VIEWER }
        assertEquals("$what: hands over the pager", 1, asks.size)
        val ask = asks.single()
        assertEquals("$what: the store after", found.field, code[ask + 1].referenceText())
        assertEquals("$what: the store after", Opcode.IPUT_OBJECT, code[ask + 1].opcode)
        val config = code[ask - 3] as TwoRegisterInstruction
        assertEquals("$what: the config", found.config, code[ask - 3].referenceText())
        assertEquals("$what: into the free local", found.free, config.registerA)
        assertEquals("$what: from the holder", found.holder, config.registerB)
        assertEquals("$what: the config test", Opcode.IF_EQZ, code[ask - 2].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        assertEquals("$what: no config goes on to the store", ask + 1, address.indexOf(address[ask - 2] + (code[ask - 2] as OffsetInstruction).codeOffset))
        assertEquals("$what: the source", found.source, code[ask - 1].referenceText())
        assertEquals("$what: the source's register", found.free, (code[ask - 1] as TwoRegisterInstruction).registerA)
        val call = code[ask] as FiveRegisterInstruction
        assertEquals("$what: handed the pager and the source", listOf(found.pager, found.free), listOf(call.registerC, call.registerD))
        assertEquals("$what: two arguments", 2, call.registerCount)
        assertTrue("$what: the free local isn't the pager or its holder", found.free != found.pager && found.free != found.holder)
        assertTrue("$what: the free local fits", found.free <= 15)
    }

    /**
     * A pager setup shaped like Instagram 453's static 01WS.A0j: it loads [PAGER_SETUP], casts the
     * pager it's handed to a [VIEW_PAGER] and stores it in the holder it's handed (048e), next to a
     * store of something else. A holder with the viewer's [VIEWER_CONFIG] next to a static one, and a
     * config with its source.
     */
    private fun pagerClasses(
        setups: Int = 1,
        stores: Int = 1,
        entered: Boolean = false,
        configs: Int = 1,
        locals: Int = 2,
    ): List<ClassDef> {
        val pager = "v${locals - 1}"
        val name = if (setups == 0) "ClipsViewerFragment.setUpViewPager" else PAGER_SETUP
        val body = """
            const-string $pager, "$name"
            move-object $pager, p0
            check-cast $pager, $VIEW_PAGER
            ${if (entered) "if-eqz $pager, :store\n:store" else ""}
            ${List(stores) { "iput-object $pager, p1, $holder->pager:$VIEW_PAGER" }.joinToString("\n")}
            iput-object p0, p1, $holder->kept:Ljava/lang/Object;
            return-void
        """
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val setUps = List(maxOf(setups, 1)) {
            method(setupOwner, if (it == 0) "setup" else "setup$it", listOf("Ljava/lang/Object;", holder), "V", locals, static, body)
        }
        val held = List(configs) { ImmutableField(holder, if (it == 0) "config" else "config$it", VIEWER_CONFIG, final, null, null, null) } +
            ImmutableField(holder, "DEFAULT", VIEWER_CONFIG, final or AccessFlags.STATIC.value, null, null, null) +
            ImmutableField(holder, "pager", VIEW_PAGER, final, null, null, null) +
            ImmutableField(holder, "kept", "Ljava/lang/Object;", final, null, null, null)
        val configFields = listOf(
            ImmutableField(VIEWER_CONFIG, "source", VIEWER_SOURCE, final, null, null, null),
            ImmutableField(VIEWER_CONFIG, "NONE", VIEWER_SOURCE, final or AccessFlags.STATIC.value, null, null, null),
        )
        return listOf(classDef(setupOwner, setUps), classDef(holder, emptyList(), fields = held), classDef(VIEWER_CONFIG, emptyList(), fields = configFields))
    }

    /** The insert of a cached reel asks the extension first, handing it the viewer's source (#390). */
    @Test
    fun aCachedReelAsksFirst() {
        val context = PatchContexts.of(cachedClasses())
        val found = context.findCachedReels()
        val before = found.insert.implementation!!.instructions.count()

        context.stopCachedReels(found)

        assertEquals(1, found.controller)
        assertEquals("$controller->source:$VIEWER_SOURCE", found.source)
        val methods = context.mutableClassDefBy(controller).methods
        assertAsksForCached("stand-in", methods.single { it.name == "insert" }, found, before)
        assertTrue("the controller's run was touched", methods.single { it.name == "execute" }.code().none { it.referenceText() == STOP_CACHED })
    }

    /** A build the cached finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildTheCachedFinderCantReadFailsBeforeAnythingChanges() {
        val insert = "$controller->insert"
        val shape = "isn't a static insert handed its controller"
        val cases = listOf(
            cachedClasses(inserts = 0) to "expected one method loading \"$CACHED_REEL\", found 0",
            cachedClasses(inserts = 2) to "expected one method loading \"$CACHED_REEL\", found 2",
            cachedClasses(static = false) to "$insert(${item}${controller}Z)V $shape",
            cachedClasses(returns = "Z") to "$insert(${item}${controller}Z)Z $shape",
            cachedClasses(takes = listOf(item, "Z")) to "$insert(${item}Z)V $shape",
            cachedClasses(takes = listOf(controller, controller, "Z")) to "$insert($controller${controller}Z)V $shape",
            cachedClasses(locals = 0) to "$insert has no local for the answer",
            cachedClasses(entered = true) to "a jump or exception handler enters $insert at its first instruction",
            cachedClasses(sources = 0) to "$controller holds 0 $VIEWER_SOURCE fields, not one",
            cachedClasses(sources = 2) to "$controller holds 2 $VIEWER_SOURCE fields, not one",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.stopCachedReels() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == STOP_CACHED } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the insert of a cached reel asks first. */
    @Test
    fun eachDeclaredBuildAsksFirstForACachedReel() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                asksForCached(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksFirstForACachedReelToo() {
        for (base in Fixtures.otherBuilds()) asksForCached(base, base.parentFile.name)
    }

    private fun asksForCached(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, CACHED_REEL)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading))

        val found = context.findCachedReels()
        val before = found.insert.implementation!!.instructions.count()
        context.stopCachedReels(found)

        val takes = found.insert.parameterTypes.map(CharSequence::toString)
        val insert = context.mutableClassDefBy(found.insert.definingClass).methods
            .single { it.name == found.insert.name && it.parameterTypes.map(CharSequence::toString) == takes }
        assertAsksForCached(label, insert, found, before)
        val hooked = loading.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
            .count { method -> method.code().any { it.referenceText() == STOP_CACHED } }
        assertEquals("${label}: methods hooked", 1, hooked)
    }

    /**
     * First thing: the controller's viewer source read and handed to the hook, and a yes returning.
     * A no lands on Instagram's own first instruction.
     */
    private fun assertAsksForCached(what: String, method: Method, found: CachedReels, before: Int) {
        val code = method.code()
        val words = method.parameterTypes.size + method.parameterTypes.count { it.toString() == "J" || it.toString() == "D" }
        val first = method.implementation!!.registerCount - words
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == STOP_CACHED })
        assertEquals("$what: added", before + 6, code.size)
        assertEquals("$what: the controller's copy", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: the controller", first + found.controller, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the source read", Opcode.IGET_OBJECT, code[1].opcode)
        assertEquals("$what: the source", found.source, code[1].referenceText())
        assertEquals("$what: the hook", STOP_CACHED, code[2].referenceText())
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[3].opcode)
        assertEquals("$what: the stop", Opcode.RETURN_VOID, code[5].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        assertEquals("$what: the test", Opcode.IF_EQZ, code[4].opcode)
        assertEquals("$what: a no goes to Instagram", 6, address.indexOf(address[4] + (code[4] as OffsetInstruction).codeOffset))
    }

    /**
     * A controller shaped like Instagram 453's 04HZ: its static insert loads [CACHED_REEL] and is
     * handed a reel, the controller and a flag. The controller holds the viewer's [VIEWER_SOURCE],
     * next to a static one and a string.
     */
    private fun cachedClasses(
        inserts: Int = 1,
        static: Boolean = true,
        returns: String = "V",
        takes: List<String> = listOf(item, controller, "Z"),
        locals: Int = 2,
        entered: Boolean = false,
        sources: Int = 1,
    ): List<ClassDef> {
        val scratch = if (locals == 0) "p0" else "v${locals - 1}"
        val name = if (inserts == 0) "ClipsViewerFlashMediaController.insertForColdStart" else CACHED_REEL
        val end = if (returns == "V") "return-void" else "const/4 $scratch, 0x0\nreturn $scratch"
        val body = """
            ${if (entered) ":top" else ""}
            const-string $scratch, "$name"
            ${if (entered) "goto :top" else end}
        """
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val flags = final or (if (static) AccessFlags.STATIC.value else 0)
        val puts = List(maxOf(inserts, 1)) { method(controller, if (it == 0) "insert" else "insert$it", takes, returns, locals, flags, body) }
        val execute = method(controller, "execute", emptyList(), "V", 1, final, "iget-object v0, p0, $controller->source:$VIEWER_SOURCE\nreturn-void")
        val fields = List(sources) { ImmutableField(controller, if (it == 0) "source" else "source$it", VIEWER_SOURCE, final, null, null, null) } +
            ImmutableField(controller, "DEFAULT", VIEWER_SOURCE, final or AccessFlags.STATIC.value, null, null, null) +
            ImmutableField(controller, "module", "Ljava/lang/String;", final, null, null, null)
        return listOf(classDef(controller, puts + execute, fields = fields))
    }

    /** The adapter's add asks the extension for its placeholder flag, and its placeholder at a position asks first (#390). */
    @Test
    fun thePlaceholdersAskFirst() {
        val context = PatchContexts.of(adapterClasses())
        val found = context.findPlaceholders()
        val add = found.add.implementation!!.instructions.count()
        val shimmer = found.shimmer.implementation!!.instructions.count()

        context.skipPlaceholders(found)

        assertEquals("$adapter->config:$VIEWER_CONFIG", found.config)
        assertEquals("$VIEWER_CONFIG->source:$VIEWER_SOURCE", found.source)
        val methods = context.mutableClassDefBy(adapter).methods
        assertAsksForPlaceholders("stand-in", methods.single { it.name == "add" }, methods.single { it.name == "shimmer" }, found, add, shimmer)
        for (name in listOf("update", "scroll")) {
            assertTrue("$name was touched", methods.single { it.name == name }.code().none { it.referenceText() in setOf<String?>(PLACEHOLDER, STOP_CACHED) })
        }
    }

    /** A build the placeholder finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePlaceholderFinderCantReadFailsBeforeAnythingChanges() {
        val add = "$adapter->add"
        val shape = "isn't an instance add handed reels and a flag"
        val shimmer = "$adapter->shimmer"
        val cases = listOf(
            adapterClasses(adds = 0) to "expected one method loading \"$ADD_REELS\", found 0",
            adapterClasses(adds = 2) to "expected one method loading \"$ADD_REELS\", found 2",
            adapterClasses(static = true) to "$add(Ljava/util/List;Z)V $shape",
            adapterClasses(returns = "Z") to "$add(Ljava/util/List;Z)Z $shape",
            adapterClasses(takes = listOf("Ljava/util/List;")) to "$add(Ljava/util/List;)V $shape",
            adapterClasses(locals = 1) to "$add has fewer than two locals for the answer",
            adapterClasses(entered = true) to "a jump or exception handler enters $add at its first instruction",
            adapterClasses(configs = emptyList()) to "$add reads 0 $VIEWER_CONFIG fields of its adapter, not one",
            adapterClasses(configs = listOf("config", "config2")) to "$add reads 2 $VIEWER_CONFIG fields of its adapter, not one",
            adapterClasses(sources = emptyList()) to "$add reads 0 $VIEWER_SOURCE fields of its config, not one",
            adapterClasses(sources = listOf("source", "source2")) to "$add reads 2 $VIEWER_SOURCE fields of its config, not one",
            adapterClasses(shimmers = 0) to "expected one method of $adapter handed a position that calls add, found 0",
            adapterClasses(shimmers = 2) to "expected one method of $adapter handed a position that calls add, found 2",
            adapterClasses(shimmerLocals = 0) to "$shimmer has no local for the answer",
            adapterClasses(shimmerEntered = true) to "a jump or exception handler enters $shimmer at its first instruction",
            adapterClasses(updateLocals = 1) to "$adapter->update has fewer than two locals for the hook",
            adapterClasses(handed = emptyList()) to "$adapter->update hands its reels to 0 fields of its adapter, not one",
            adapterClasses(handed = listOf("items", "items2")) to "$adapter->update hands its reels to 2 fields of its adapter, not one",
            adapterClasses(insertions = 0) to "expected one insert at a position reading $adapter->items:$adapterItems of $adapter handed Ljava/util/List;I, found 0",
            adapterClasses(insertions = 2) to "expected one insert at a position reading $adapter->items:$adapterItems of $adapter handed Ljava/util/List;I, found 2",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.skipPlaceholders() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() in setOf<String?>(PLACEHOLDER, STOP_CACHED) } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the adapter's add and its placeholder at a position ask first. */
    @Test
    fun eachDeclaredBuildAsksFirstForAPlaceholder() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                asksForPlaceholders(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksFirstForAPlaceholderToo() {
        for (base in Fixtures.otherBuilds()) asksForPlaceholders(base, base.parentFile.name)
    }

    private fun asksForPlaceholders(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, ADD_REELS)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading))

        val found = context.findPlaceholders()
        val add = found.add.implementation!!.instructions.count()
        val shimmer = found.shimmer.implementation!!.instructions.count()
        context.skipPlaceholders(found)

        val methods = context.mutableClassDefBy(found.add.definingClass).methods
        fun patched(method: Method) = methods.single { m ->
            m.name == method.name && m.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }
        assertAsksForPlaceholders(label, patched(found.add), patched(found.shimmer), found, add, shimmer)
        val hooked = loading.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
        assertEquals("${label}: adds hooked", 1, hooked.count { method -> method.code().any { it.referenceText() == PLACEHOLDER } })
        assertEquals("${label}: placeholders hooked", 1, hooked.count { method -> method.code().any { it.referenceText() == STOP_CACHED } })
    }

    /**
     * The adapter's add, its update and its insert at a position each hand their reels and the
     * viewer's source to the extension first and take what comes back (#460). A page from the
     * server merges in through the update, which never calls the hooked add for the new list.
     */
    @Test
    fun theAddLeavesOutSentReelsFirst() {
        val context = PatchContexts.of(adapterClasses())
        val found = context.findPlaceholders()
        val before = found.add.implementation!!.instructions.count()
        val ways = listOf(found.update, found.insert).associate { it.name to it.implementation!!.instructions.count() }
        assertEquals("the update and the insert", setOf("update", "insert"), ways.keys)
        context.leaveOutSentReels(found)
        val methods = context.mutableClassDefBy(adapter).methods
        val add = methods.single { it.name == "add" }
        assertLeavesOutSent("stand-in", add, found, before)
        for ((name, count) in ways) assertLeavesOutSent("stand-in $name", methods.single { it.name == name }, found, count)
        for (name in listOf("shimmer", "scroll")) {
            assertTrue("$name was touched", methods.single { it.name == name }.code().none { it.referenceText() == LEAVE_OUT_SENT })
        }

        // The placeholder's ask goes in front, and with no config it lands on the page's.
        context.skipPlaceholders(found)
        val code = add.code()
        assertEquals("both added", before + 16, code.size)
        assertEquals("the placeholder's ask first", PLACEHOLDER, code[5].referenceText())
        assertEquals("then the page's", LEAVE_OUT_SENT, code[13].referenceText())
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        assertEquals("no config goes on to the page's ask", 8, address.indexOf(address[2] + (code[2] as OffsetInstruction).codeOffset))
    }

    /** In each declared build the adapter's add hands its reels to the extension first. */
    @Test
    fun eachDeclaredBuildLeavesOutSentReels() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                leavesOutSent(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildLeavesOutSentReelsToo() {
        for (base in Fixtures.otherBuilds()) leavesOutSent(base, base.parentFile.name)
    }

    private fun leavesOutSent(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, ADD_REELS)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading))
        val found = context.findPlaceholders()
        val ways = listOf(found.add, found.update, found.insert)
        val before = ways.map { way -> way.implementation!!.instructions.count { it.opcode != Opcode.NOP } }
        context.leaveOutSentReels(found)
        ways.forEachIndexed { index, way -> assertLeavesOutSent("$label ${way.name}", way, found, before[index]) }
        val hooked = loading.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
        assertEquals("$label: ways in hooked", 3, hooked.count { method -> method.code().any { it.referenceText() == LEAVE_OUT_SENT } })
    }

    /**
     * First thing in the add: the adapter's config read, then its source, which the hook is handed
     * with the reels, and the answer in place of the reels. A config that isn't there lands on
     * Instagram's own first instruction with the reels untouched.
     */
    private fun assertLeavesOutSent(what: String, add: Method, found: Placeholders, before: Int) {
        val code = add.code()
        val reels = add.implementation!!.registerCount - 2
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == LEAVE_OUT_SENT })
        assertEquals("$what: added", before + 8, code.count { it.opcode != Opcode.NOP })
        assertEquals("$what: the adapter's copy", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: the adapter", reels - 1, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the config", found.config, code[1].referenceText())
        assertEquals("$what: the config test", Opcode.IF_EQZ, code[2].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        assertEquals("$what: no config goes to Instagram", 8, address.indexOf(address[2] + (code[2] as OffsetInstruction).codeOffset))
        assertEquals("$what: the source", found.source, code[3].referenceText())
        assertEquals("$what: the reels' copy", Opcode.MOVE_OBJECT_FROM16, code[4].opcode)
        assertEquals("$what: the reels", reels, (code[4] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the hook", LEAVE_OUT_SENT, code[5].referenceText())
        val hook = code[5] as FiveRegisterInstruction
        val handed = listOf((code[4] as TwoRegisterInstruction).registerA, (code[3] as TwoRegisterInstruction).registerA)
        assertEquals("$what: handed the reels and the source", handed, listOf(hook.registerC, hook.registerD))
        assertEquals("$what: its answer", Opcode.MOVE_RESULT_OBJECT, code[6].opcode)
        assertEquals("$what: the answer's copy", Opcode.MOVE_OBJECT_FROM16, code[7].opcode)
        assertEquals("$what: in place of the reels", reels, (code[7] as TwoRegisterInstruction).registerA)
        assertEquals("$what: from the answer", (code[6] as OneRegisterInstruction).registerA, (code[7] as TwoRegisterInstruction).registerB)
    }

    /**
     * First thing in the add: the adapter's config read, then its source, which the hook is handed
     * with the flag, and the answer back in the flag. A config that isn't there lands on Instagram's
     * own first instruction with the flag untouched, as does the answer. First thing in the
     * placeholder: no position goes to Instagram, else the source is handed to the hook and a yes
     * returns.
     */
    private fun assertAsksForPlaceholders(what: String, add: Method, shimmer: Method, found: Placeholders, addBefore: Int, shimmerBefore: Int) {
        val code = add.code()
        val flag = add.implementation!!.registerCount - 1
        assertEquals("$what: add hooks", 1, code.count { it.referenceText() == PLACEHOLDER })
        assertEquals("$what: add added", addBefore + 8, code.size)
        assertEquals("$what: the adapter's copy", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: the adapter", flag - 2, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the config", found.config, code[1].referenceText())
        assertEquals("$what: the config test", Opcode.IF_EQZ, code[2].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        assertEquals("$what: no config goes to Instagram", 8, address.indexOf(address[2] + (code[2] as OffsetInstruction).codeOffset))
        assertEquals("$what: the source", found.source, code[3].referenceText())
        assertEquals("$what: the flag's copy", Opcode.MOVE_FROM16, code[4].opcode)
        assertEquals("$what: the flag", flag, (code[4] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the hook", PLACEHOLDER, code[5].referenceText())
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[6].opcode)
        assertEquals("$what: the answer's copy", Opcode.MOVE_FROM16, code[7].opcode)
        assertEquals("$what: back in the flag", flag, (code[7] as TwoRegisterInstruction).registerA)
        assertEquals("$what: from the answer", (code[6] as OneRegisterInstruction).registerA, (code[7] as TwoRegisterInstruction).registerB)

        val asks = shimmer.code()
        val position = shimmer.implementation!!.registerCount - 1
        assertEquals("$what: placeholder hooks", 1, asks.count { it.referenceText() == STOP_CACHED })
        assertEquals("$what: placeholder added", shimmerBefore + 9, asks.size)
        assertEquals("$what: the position", position, (asks[0] as OneRegisterInstruction).registerA)
        assertEquals("$what: the placeholder's adapter", position - 1, (asks[1] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the placeholder's config", found.config, asks[2].referenceText())
        assertEquals("$what: the placeholder's source", found.source, asks[4].referenceText())
        assertEquals("$what: the placeholder's hook", STOP_CACHED, asks[5].referenceText())
        assertEquals("$what: the placeholder's answer", Opcode.MOVE_RESULT, asks[6].opcode)
        assertEquals("$what: the skip", Opcode.RETURN_VOID, asks[8].opcode)
        val at = IntArray(asks.size + 1)
        asks.forEachIndexed { index, instruction -> at[index + 1] = at[index] + instruction.codeUnits }
        for (branch in listOf(0, 3, 7)) {
            assertEquals("$what: placeholder branch $branch", Opcode.IF_EQZ, asks[branch].opcode)
            assertEquals("$what: placeholder branch $branch goes to Instagram", 9, at.indexOf(at[branch] + (asks[branch] as OffsetInstruction).codeOffset))
        }
    }

    /**
     * An adapter shaped like Instagram 453's 045b: its add loads [ADD_REELS] and reads the viewer's
     * [VIEWER_CONFIG] (twice, as 045b does) and the config's [VIEWER_SOURCE], next to another class's
     * config and source. Its placeholder is handed a position and calls the add, next to an update
     * that hands its reels to the adapter's list and calls the add too, an insert reading that list,
     * an insert listener that doesn't, and a scroll handed a position that calls nothing.
     */
    private fun adapterClasses(
        adds: Int = 1,
        static: Boolean = false,
        returns: String = "V",
        takes: List<String> = listOf("Ljava/util/List;", "Z"),
        locals: Int = 3,
        entered: Boolean = false,
        configs: List<String> = listOf("config", "config"),
        sources: List<String> = listOf("source"),
        shimmers: Int = 1,
        shimmerLocals: Int = 1,
        shimmerEntered: Boolean = false,
        updateLocals: Int = 2,
        handed: List<String> = listOf("items"),
        insertions: Int = 1,
    ): List<ClassDef> {
        val scratch = "v${locals - 1}"
        val name = if (adds == 0) "$ADD_REELS:addToDataSource" else ADD_REELS
        val end = if (returns == "V") "return-void" else "const/4 $scratch, 0x0\nreturn $scratch"
        val body = """
            ${if (entered) ":top" else ""}
            const-string $scratch, "$name"
            ${configs.joinToString("\n") { "iget-object $scratch, p0, $adapter->$it:$VIEWER_CONFIG" }}
            ${sources.joinToString("\n") { "iget-object $scratch, $scratch, $VIEWER_CONFIG->$it:$VIEWER_SOURCE" }}
            iget-object $scratch, p0, Lfixture/Other;->config:$VIEWER_CONFIG
            iget-object $scratch, $scratch, Lfixture/OtherConfig;->source:$VIEWER_SOURCE
            ${if (entered) "goto :top" else end}
        """
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val flags = final or (if (static) AccessFlags.STATIC.value else 0)
        val addTo = "invoke-virtual {p0, ${takes.joinToString(", ") { "v0" }}}, $adapter->add(${takes.joinToString("")})$returns"
        val puts = List(maxOf(adds, 1)) { method(adapter, if (it == 0) "add" else "add$it", takes, returns, locals, flags, body) }
        // With no locals v0 is the adapter itself, which the stand-in's call doesn't mind.
        val calls = if (shimmers == 0) "" else "const/4 v0, 0x0\n$addTo"
        val shimmerBody = """
            ${if (shimmerEntered) ":top" else ""}
            $calls
            ${if (shimmerEntered) "goto :top" else "return-void"}
        """
        val shows = List(maxOf(shimmers, 1)) {
            method(adapter, if (it == 0) "shimmer" else "shimmer$it", listOf("Ljava/lang/Integer;"), "V", shimmerLocals, final, shimmerBody)
        }
        // The update hands its reels to the adapter's list, then calls the add for the flag's empty one.
        val handsOver = handed.joinToString("\n") { "iget-object v0, p0, $adapter->$it:$adapterItems\ninvoke-virtual {v0, p1}, $adapterItems->replace(Ljava/util/List;)V" }
        val update = method(adapter, "update", listOf("Ljava/util/List;", "Z"), "V", updateLocals, final, "$handsOver\nconst/4 v0, 0x0\n$addTo\nreturn-void")
        val inserts = List(insertions) {
            method(adapter, if (it == 0) "insert" else "insert$it", listOf("Ljava/util/List;", "I"), "V", 2, final, "iget-object v0, p0, $adapter->items:$adapterItems\nreturn-void")
        }
        // Instagram's insert listener takes reels and an int too and reads other fields.
        val inserted = method(adapter, "inserted", listOf("Ljava/util/List;", "I"), "V", 2, final, "iget-object v0, p0, $adapter->watcher:$adapterItems\nreturn-void")
        val scroll = method(adapter, "scroll", listOf("Ljava/lang/Integer;"), "V", 1, final, "const/4 v0, 0x0\nreturn-void")
        return listOf(classDef(adapter, puts + shows + update + inserts + inserted + scroll))
    }

    /** The chat reels test gets its flag answered through the extension, and nothing else changes. */
    @Test
    fun theTestIsAnswered() {
        val context = PatchContexts.of(classes())

        context.chainChatReels()

        assertAnswered("stand-in", context.mutableClassDefBy(gates).methods.single { it.name == "chain" })
        val asks = context.mutableClassDefBy(opener).methods.single { it.name == "open" }.code()
        assertTrue("the opener was touched", asks.none { it.referenceText() == CHAIN })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val test = "$gates->chain"
        val cases = listOf(
            classes(reads = 0) to "expected one read of the chat reels flag $flagHex, found 0",
            classes(reads = 2) to "expected one read of the chat reels flag $flagHex, found 2",
            classes(flag = CHAT_REELS_FLAG + 1) to "expected one read of the chat reels flag $flagHex, found 0",
            classes(sharedRead = true) to "the read of $flagHex in $test is shared with another flag",
            classes(takes = "Ljava/lang/Object;") to "$flagHex is read in $test(Ljava/lang/Object;)Z, not in a test of the session",
            classes(openerTakes = listOf(THREAD_KEY)) to "nothing handed a chat and a message asks $test, so it isn't the chat reels test",
            classes(openerAsks = false) to "nothing handed a chat and a message asks $test, so it isn't the chat reels test",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.chainChatReels() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == CHAIN } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the flag's one read is in a test of the session that the opener of a
     * reel shared in a chat asks, and it's answered.
     */
    @Test
    fun eachDeclaredBuildChainsAChatsReels() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildChainsAChatsReelsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val opens = classDef.methods.any { m ->
                    val takes = m.parameterTypes.map(CharSequence::toString)
                    THREAD_KEY in takes && MESSAGE_ID in takes
                }
                if (opens || classDef.methods.any { it.loadsTheFlag() }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val loading = holders.filter { c -> c.methods.any { it.loadsTheFlag() } }
        assertEquals("${label}: classes loading $flagHex", 1, loading.size)
        val context = PatchContexts.of(holders)

        val read = context.findChatReels()
        context.chainChatReels()

        val patched = context.mutableClassDefBy(read.type).methods
        assertAnswered(label, patched.single { it.name == read.name && it.parameterTypes.map(CharSequence::toString) == listOf(session) })
        assertEquals("${label}: methods hooked", 1, patched.count { m -> m.code().any { it.referenceText() == CHAIN } })

        val tap = context.findChatMediaTap(read)
        val before = tap.tap.implementation!!.instructions.count()
        context.guardChatMediaTaps(tap)
        assertGuarded(label, tap.tap, tap, before)
        val guarded = holders.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
            .count { method -> method.code().any { it.referenceText() == OPEN } }
        assertEquals("${label}: taps guarded", 1, guarded)
    }

    /** A tap on media in a chat asks the extension first, handing it the message and the tapped view (#390). */
    @Test
    fun aTapOnAChatsMediaAsksFirst() {
        val context = PatchContexts.of(classes())
        val found = context.findChatMediaTap()
        val before = found.tap.implementation!!.instructions.count()

        context.guardChatMediaTaps(found)

        assertEquals("the view after the bounds", 2, found.view)
        assertEquals("the message after a wide word", 5, found.message)
        val methods = context.mutableClassDefBy(opener).methods
        assertGuarded("stand-in", methods.single { it.name == "tap" }, found, before)
        assertTrue("the opener was touched", methods.single { it.name == "open" }.code().none { it.referenceText() == OPEN })
    }

    /** A build the tap finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildTheTapFinderCantReadFailsBeforeAnythingChanges() {
        val tap = "$opener->tap"
        val cases = listOf(
            classes(taps = 0) to "expected one tap in $opener handed a view and a message, found 0",
            classes(taps = 2) to "expected one tap in $opener handed a view and a message, found 2",
            classes(tapTakes = listOf(tapped, tapped, MESSAGE_ID)) to "$tap($tapped$tapped$MESSAGE_ID)V is handed more than one view or message",
            classes(tapLocals = 1) to "$tap has fewer than two locals for the hook",
            classes(tapEntered = true) to "a jump or exception handler enters $tap at its first instruction",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.guardChatMediaTaps() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == OPEN } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * First thing: the message and the tapped view copied and handed to the hook, and a no
     * returning. A yes lands on Instagram's own first instruction.
     */
    private fun assertGuarded(what: String, method: Method, found: ChatMediaTap, before: Int) {
        val code = method.code()
        val words = method.parameterTypes.size + method.parameterTypes.count { it.toString() == "J" || it.toString() == "D" }
        val self = method.implementation!!.registerCount - words - 1
        assertEquals("$what: the message's type", MESSAGE_ID, method.parameterAt(found.message))
        assertEquals("$what: the view's type", tapped, method.parameterAt(found.view))
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == OPEN })
        assertEquals("$what: added", before + 6, code.size)
        assertEquals("$what: the message's copy", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: the message", self + found.message, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the view's copy", Opcode.MOVE_OBJECT_FROM16, code[1].opcode)
        assertEquals("$what: the view", self + found.view, (code[1] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the hook", OPEN, code[2].referenceText())
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[3].opcode)
        assertEquals("$what: the drop", Opcode.RETURN_VOID, code[5].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        assertEquals("$what: the test", Opcode.IF_NEZ, code[4].opcode)
        assertEquals("$what: a yes goes to Instagram", 6, address.indexOf(address[4] + (code[4] as OffsetInstruction).codeOffset))
    }

    /** The type of the parameter that comes in [register], counting from p1. */
    private fun Method.parameterAt(register: Int): String? {
        var at = 1
        for (type in parameterTypes.map(CharSequence::toString)) {
            if (at == register) return type
            at += if (type == "J" || type == "D") 2 else 1
        }
        return null
    }

    /** Right after the flag's move-result: the range call with that register, and its answer back in it. */
    private fun assertAnswered(what: String, method: Method) {
        val code = method.code()
        val load = code.indexOfFirst { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == CHAT_REELS_FLAG }
        assertTrue("$what: no load of $flagHex", load >= 0)
        val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == CHAIN })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", CHAIN, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * Gates shaped like Instagram 453's 01Oo: a test taking the session reads [CHAT_REELS_FLAG]
     * through a cast config and answers it. An opener handed a chat and a message asks it, shaped
     * like 0QbG, whose tap on a chat's media is handed the bounds, the tapped view, a wide word and
     * the message.
     */
    private fun classes(
        reads: Int = 1,
        flag: Long = CHAT_REELS_FLAG,
        sharedRead: Boolean = false,
        takes: String = session,
        openerTakes: List<String> = listOf(THREAD_KEY, MESSAGE_ID),
        openerAsks: Boolean = true,
        taps: Int = 1,
        tapTakes: List<String> = listOf(bounds, tapped, "J", MESSAGE_ID),
        tapLocals: Int = 2,
        tapEntered: Boolean = false,
    ): List<ClassDef> {
        val readFlag = List(reads) {
            """
                invoke-static {v4}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                ${if (sharedRead) "if-eqz v2, :cast$it" else ""}
                const-wide v0, 0x${flag.toString(16)}L
                ${if (sharedRead) ":cast$it" else ""}
                check-cast v2, $configs
                invoke-interface {v2, v0, v1}, $configs->Bae(J)Z
                move-result v0
            """.trimIndent()
        }.joinToString("\n")
        // Three locals, this in v3, the session in v4.
        val test = method(
            gates, "chain", listOf(takes), "Z", 3, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            if (reads == 0) "const/4 v0, 0x0\nreturn v0" else "$readFlag\nreturn v0",
        )
        // One local, this in v1, then the chat, the message and the gates.
        val open = method(
            opener, "open", openerTakes + gates, "V", 1, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            if (openerAsks) {
                "const/4 v0, 0x0\ninvoke-virtual {p${openerTakes.size + 1}, v0}, $gates->chain($takes)Z\nreturn-void"
            } else {
                "return-void"
            },
        )
        val tapBody = if (tapEntered) ":top\nconst/4 v0, 0x0\ngoto :top" else "const/4 v0, 0x0\nreturn-void"
        val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val tapsOf = List(taps) { method(opener, if (it == 0) "tap" else "tap$it", tapTakes, "V", tapLocals, final, tapBody) }
        return listOf(classDef(gates, listOf(test)), classDef(opener, listOf(open) + tapsOf))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val words = parameters.size + parameters.count { it == "J" || it == "D" }
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + words
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, interfaces: List<String> = emptyList(), fields: List<Field> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", interfaces, null, null, fields, methods)

    private fun Method.loadsTheFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == CHAT_REELS_FLAG
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
