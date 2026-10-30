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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
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
    private val viewer = "Lfixture/Viewer;"
    private val controller = "Lfixture/FlashController;"
    private val item = "Lfixture/Item;"
    private val adapter = "Lfixture/Adapter;"

    /** The hooks the patch writes are in the ChatReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(CHAIN, STOP, STOP_CACHED, PLACEHOLDER)) assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
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
     * stock side. A source shaped like 0Mfh asks for [CHAT_REELS_PATH] in that page.
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
        return listOf(classDef(fetcher, requests), classDef(chatSource, asks, chatIs))
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
        val methods = context.mutableClassDefBy(viewer).methods
        assertAsksForMore("stand-in", methods.single { it.name == "more" }, found, before)
        assertTrue("the viewer's opening was touched", methods.single { it.name == "open" }.code().none { it.referenceText() == STOP })
    }

    /** A build the more finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildTheMoreFinderCantReadFailsBeforeAnythingChanges() {
        val more = "$viewer->more"
        val shape = "isn't an instance request returning nothing"
        val cases = listOf(
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
        val loading = FixtureDex.classesLoading(bundle, MORE_REELS)
        val config = FixtureDex.classes(bundle, setOf(VIEWER_CONFIG)).values
        val context = PatchContexts.of((FixtureDex.withStringPools(bundle, loading) + config).distinctBy { it.type })

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
     * First thing: the viewer's config read, then the chat it holds, the hook asked, and a yes
     * returning. A config or chat that isn't there, or a no, lands on Instagram's own first instruction.
     */
    private fun assertAsksForMore(what: String, method: Method, found: MoreReels, before: Int) {
        val code = method.code()
        val words = method.parameterTypes.size + method.parameterTypes.count { it.toString() == "J" || it.toString() == "D" }
        val self = method.implementation!!.registerCount - words - 1
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == STOP })
        assertEquals("$what: added", before + 9, code.size)
        assertEquals("$what: the viewer's copy", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: the viewer", self, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the config read", Opcode.IGET_OBJECT, code[1].opcode)
        assertEquals("$what: the config", found.config, code[1].referenceText())
        assertEquals("$what: the chat read", Opcode.IGET_OBJECT, code[3].opcode)
        assertEquals("$what: the chat", found.direct, code[3].referenceText())
        assertEquals("$what: the hook", STOP, code[5].referenceText())
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[6].opcode)
        assertEquals("$what: the stop", Opcode.RETURN_VOID, code[8].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        for (at in listOf(2, 4, 7)) {
            assertEquals("$what: branch $at", Opcode.IF_EQZ, code[at].opcode)
            val target = address.indexOf(address[at] + (code[at] as OffsetInstruction).codeOffset)
            assertEquals("$what: branch $at goes to Instagram", 9, target)
        }
    }

    /**
     * A viewer shaped like Instagram 453's 02HP: its request for more reels loads [MORE_REELS] and
     * reads the [VIEWER_CONFIG] it was opened with (twice, as 02HP does), next to a list of its own
     * and another class's config. A config holding the chat as [DIRECT_DATA], next to a static one.
     */
    private fun viewerClasses(
        requests: Int = 1,
        static: Boolean = false,
        returns: String = "V",
        locals: Int = 2,
        entered: Boolean = false,
        reads: List<String> = listOf("config", "config"),
        chats: Int = 1,
    ): List<ClassDef> {
        val scratch = if (locals == 0) "p2" else "v${locals - 1}"
        val name = if (requests == 0) "active_section_on_end" else MORE_REELS
        val body = """
            ${if (entered) ":top" else ""}
            const-string $scratch, "$name"
            ${reads.joinToString("\n") { "iget-object $scratch, p0, $viewer->$it:$VIEWER_CONFIG" }}
            iget-object $scratch, p0, $viewer->items:Ljava/util/List;
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
        return listOf(classDef(viewer, asks + open), classDef(VIEWER_CONFIG, emptyList(), fields = fields))
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
     * that calls it too and a scroll handed a position that doesn't.
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
        val update = method(adapter, "update", listOf("Ljava/util/List;", "Z"), "V", 1, final, "const/4 v0, 0x0\n$addTo\nreturn-void")
        val scroll = method(adapter, "scroll", listOf("Ljava/lang/Integer;"), "V", 1, final, "const/4 v0, 0x0\nreturn-void")
        return listOf(classDef(adapter, puts + shows + update + scroll))
    }

    /** The chat reels test gets its flag answered through the extension, and nothing else changes. */
    @Test
    fun theTestIsAnswered() {
        val context = PatchContexts.of(classes())

        context.chainChatReels()

        assertAnswered("stand-in", context.mutableClassDefBy(gates).methods.single { it.name == "chain" })
        val asks = context.mutableClassDefBy(opener).methods.single().code()
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
     * through a cast config and answers it. An opener handed a chat and a message asks it.
     */
    private fun classes(
        reads: Int = 1,
        flag: Long = CHAT_REELS_FLAG,
        sharedRead: Boolean = false,
        takes: String = session,
        openerTakes: List<String> = listOf(THREAD_KEY, MESSAGE_ID),
        openerAsks: Boolean = true,
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
        return listOf(classDef(gates, listOf(test)), classDef(opener, listOf(open)))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + parameters.size
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
