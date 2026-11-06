/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.history.MessageFields
import app.morphe.patches.instagram.direct.history.findMessageFields
import app.morphe.patches.instagram.direct.keep.findMessageParser
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Only received reels (#460): the method that registers a chat's messages for the chain asks the extension first. */
class OnlyReceivedReelsTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val gates = "Lfixture/Gates;"
    private val opener = "Lfixture/Opener;"
    private val collector = "Lfixture/Collector;"
    private val wrapper = "Lfixture/Wrapper;"
    private val base = "Lfixture/BaseMessage;"
    private val message = "Lfixture/Message;"
    private val reel = "Lfixture/Reel;"
    private val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

    private val fields = MessageFields(
        base,
        ImmutableFieldReference(base, "id", "Ljava/lang/String;"),
        ImmutableFieldReference(base, "time", "Ljava/lang/String;"),
        ImmutableFieldReference(base, "sender", "Ljava/lang/String;"),
        ImmutableFieldReference(base, "sent", "Z"),
        ImmutableFieldReference(base, "text", "Ljava/lang/String;"),
        null,
    )

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(RECEIVED, LEAVE_OUT_SENT)) assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
    }

    /** The collector asks first with the wrapper's message and returns on a no; a yes lands on Instagram's first instruction. */
    @Test
    fun theCollectorAsksFirstAndTheBridgesAreWritten() {
        val context = context()
        val found = context.findReelCollector(context.findChatReels(), fields)
        assertEquals("$wrapper->reel()$reel", "${found.reel}")
        assertEquals("$reel->media:$MEDIA", "${found.media}")
        val before = found.collect.implementation!!.instructions.count()
        context.skipSentReels(found)
        assertHooked("stand-in", found.collect, before)

        val bridges = context.mutableClassDefBy(CHAT_REELS).methods.associateBy { it.name }
        assertTrue(bridges.getValue("sentByViewer").visualCode().any { it.opcode == Opcode.IGET_BOOLEAN })
        assertTrue(bridges.getValue("itemId").visualCode().any { it.opcode == Opcode.IGET_OBJECT })
        assertEquals(1, bridges.getValue("itemId").visualCode().count { it.opcode == Opcode.CHECK_CAST })
        assertBridges("stand-in", context.mutableClassDefBy(CHAT_REELS).methods, found)
    }

    @Test
    fun aBuildItCantReadFailsBeforeAnythingChanges() {
        val where = "$collector->collect"
        val cases = listOf(
            context(collectors = 0) to "expected one method registering a chat's messages for the chain, found 0",
            context(collectors = 2) to "expected one method registering a chat's messages for the chain, found 2",
            context(locals = 1) to "$where has fewer than two locals for the hook",
            context(entered = true) to "a jump or exception handler enters $where at its first instruction",
            context(held = 0) to "$wrapper holds 0 $base messages, not one",
            context(held = 2) to "$wrapper holds 2 $base messages, not one",
            context(reels = 0) to "$where asks $wrapper for 0 reel items holding a $MEDIA, not one",
            context(reels = 2) to "$where asks $wrapper for 2 reel items holding a $MEDIA, not one",
            context(media = 0) to "$where asks $wrapper for 0 reel items holding a $MEDIA, not one",
            context(media = 2) to "$where asks $wrapper for 0 reel items holding a $MEDIA, not one",
            context(mediaId = false) to "$MEDIA has no getId() for the extension to key a sent reel by",
        )
        for ((context, expected) in cases) {
            PatchContexts.use(context)
            val failure = assertThrows(expected, PatchException::class.java) { context.findReelCollector(context.findChatReels(), fields) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** The adapter's add has to take the reel items the wrappers answer, or nothing changes. */
    @Test
    fun theAddHasToTakeTheReelItems() {
        val context = context()
        val found = context.findReelCollector(context.findChatReels(), fields)
        fun add(casts: String) = Placeholders(
            MutableMethod(method("Lfixture/Adapter;", "add", listOf("Ljava/util/List;", "Z"), "V", 1, final, "const/4 v0, 0x0\ncheck-cast v0, $casts\nreturn-void")),
            MutableMethod(method("Lfixture/Adapter;", "shimmer", listOf("Ljava/lang/Integer;"), "V", 1, final, "return-void")),
            "Lfixture/Adapter;->config:Lfixture/Config;",
            "Lfixture/Config;->source:Lfixture/Source;",
            MutableMethod(method("Lfixture/Adapter;", "update", listOf("Ljava/util/List;", "Z"), "V", 2, final, "return-void")),
            MutableMethod(method("Lfixture/Adapter;", "insert", listOf("Ljava/util/List;", "I"), "V", 2, final, "return-void")),
        )
        checkAddTakesReels(add(reel), found)
        val expected = "Lfixture/Adapter;->add doesn't take $reel, the reel items a chat's messages hold"
        val failure = assertThrows(PatchException::class.java) { checkAddTakesReels(add("Lfixture/Other;"), found) }
        assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
    }

    private fun assertHooked(what: String, method: Method, before: Int) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: added", before + 6, code.size)
        assertEquals("$what: hooks", 1, code.count { it.text() == RECEIVED })
        assertEquals("$what: the wrapper copied", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: the message read", Opcode.IGET_OBJECT, code[1].opcode)
        assertEquals("$what: the hook", RECEIVED, code[2].text())
        val wrapperCopy = (code[0] as TwoRegisterInstruction).registerA
        val messageRead = (code[1] as TwoRegisterInstruction)
        assertEquals("$what: the message read from the wrapper", wrapperCopy, messageRead.registerB)
        val asks = code[2] as FiveRegisterInstruction
        assertEquals("$what: handed the message and the wrapper", listOf(messageRead.registerA, wrapperCopy), listOf(asks.registerC, asks.registerD))
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[3].opcode)
        assertEquals("$what: the test", Opcode.IF_NEZ, code[4].opcode)
        assertEquals("$what: the drop", Opcode.RETURN_VOID, code[5].opcode)
    }

    /** The reel bridge asks the wrapper for its reel item; the media id bridge reads the item's Media and a null one answers null. */
    private fun assertBridges(what: String, methods: Iterable<Method>, found: ReelCollector) {
        val bridges = methods.associateBy { it.name }
        val reelCode = bridges.getValue("reel").visualCode()
        assertEquals("$what: the reel bridge's cast", found.reel.definingClass, reelCode[0].text())
        assertEquals("$what: the reel bridge's ask", "${found.reel}", reelCode[1].text())
        assertEquals("$what: the reel bridge's answer", Opcode.RETURN_OBJECT, reelCode[3].opcode)
        val idCode = bridges.getValue("mediaId").visualCode()
        assertEquals("$what: the media id bridge's cast", found.media.definingClass, idCode[0].text())
        assertEquals("$what: the media read", "${found.media}", idCode[1].text())
        assertEquals("$what: no media", listOf(Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN_OBJECT), idCode.subList(2, 5).map { it.opcode })
        assertEquals("$what: the id", "$MEDIA->getId()Ljava/lang/String;", idCode[5].text())
        assertEquals("$what: the id's answer", Opcode.RETURN_OBJECT, idCode[7].opcode)
    }

    private fun Instruction.text(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /**
     * Shaped like 453's 02YM: its constructor, handed the session, asks the chat reels test and keeps the
     * answer in a flag, and its one method handed the message wrapper reads that flag, then asks the
     * wrapper for its reel item, which holds a Media. The wrapper holds the message, a subclass of the
     * message class the parser fills.
     */
    private fun context(
        collectors: Int = 1,
        locals: Int = 2,
        entered: Boolean = false,
        held: Int = 1,
        reels: Int = 1,
        media: Int = 1,
        mediaId: Boolean = true,
    ) = PatchContexts.of(
        classes(collectors, locals, entered, held, reels, media, mediaId) + ImmutableClassDef.of(ExtensionDex.classDef(CHAT_REELS)),
    )

    private fun classes(collectors: Int, locals: Int, entered: Boolean, held: Int, reels: Int, media: Int, mediaId: Boolean): List<ClassDef> {
        val test = method(
            gates, "chain", listOf(session), "Z", 3, final,
            """
                invoke-static {v4}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                const-wide v0, 0x${CHAT_REELS_FLAG.toString(16)}L
                check-cast v2, $configs
                invoke-interface {v2, v0, v1}, $configs->Bae(J)Z
                move-result v0
                return v0
            """.trimIndent(),
        )
        val open = method(
            opener, "open", listOf(THREAD_KEY, MESSAGE_ID, gates), "V", 1, final,
            "const/4 v0, 0x0\ninvoke-virtual {p3, v0}, $gates->chain($session)Z\nreturn-void",
        )
        val owners = List(collectors) { if (it == 0) collector else "Lfixture/Collector$it;" }
        val collecting = owners.map { owner ->
            val ctor = method(
                owner, "<init>", listOf(session, gates), "V", 1, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                "invoke-virtual {p2, p1}, $gates->chain($session)Z\nmove-result v0\niput-boolean v0, p0, $owner->on:Z\nreturn-void",
            )
            val asks = List(reels) { "invoke-virtual {p1}, $wrapper->${if (it == 0) "reel" else "reel$it"}()$reel\nmove-result-object v0" }
            val body = if (entered) ":top\niget-boolean v0, p0, $owner->on:Z\nif-eqz v0, :top\nreturn-void"
            else "iget-boolean v0, p0, $owner->on:Z\nif-eqz v0, :out\n${asks.joinToString("\n")}\n:out\nreturn-void"
            val collect = method(owner, "collect", listOf(wrapper), "V", locals, final, body)
            classDef(owner, listOf(ctor, collect), fields = listOf(ImmutableField(owner, "on", "Z", AccessFlags.PRIVATE.value, null, null, null)))
        }
        val wrapperFields = List(held) { ImmutableField(wrapper, "m$it", message, AccessFlags.PUBLIC.value, null, null, null) }
        val getters = List(maxOf(reels, 1)) {
            method(wrapper, if (it == 0) "reel" else "reel$it", emptyList(), reel, 1, final, "const/4 v0, 0x0\nreturn-object v0")
        }
        // A string beside the Media, which the finder passes over.
        val reelFields = List(media) { ImmutableField(reel, "media$it".replace("media0", "media"), MEDIA, final, null, null, null) } +
            ImmutableField(reel, "id", "Ljava/lang/String;", final, null, null, null)
        val ids = if (mediaId) listOf(method(MEDIA, "getId", emptyList(), "Ljava/lang/String;", 1, final, "const/4 v0, 0x0\nreturn-object v0")) else emptyList()
        return listOf(classDef(gates, listOf(test)), classDef(opener, listOf(open))) + collecting +
            classDef(wrapper, getters, fields = wrapperFields) +
            classDef(reel, emptyList(), fields = reelFields) +
            classDef(MEDIA, ids) +
            ImmutableClassDef(message, final, base, emptyList(), null, null, emptyList(), emptyList()) +
            classDef(base, emptyList())
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

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, final, "Ljava/lang/Object;", emptyList(), null, null, fields, methods)

    @Test fun declaredBuildsFindTheCollector() = fixtures { check(it) }

    @Test fun eachOtherBuildFindsTheCollector() = otherBuilds { check(it) }

    /** The real APK's chat reels test, the method registering a chat's messages for its chain, and the message fields. */
    private fun check(bundle: File) {
        val name = bundle.parentFile.name
        val flag = CHAT_REELS_FLAG
        val classes = mutableMapOf<String, ClassDef>()
        val callers = mutableListOf<ClassDef>()
        var gateType: String? = null
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { m -> m.visualCode().any { it.opcode == Opcode.CONST_WIDE && (it as com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction).wideLiteral == flag } }
                val opens = classDef.methods.any { m ->
                    val takes = m.parameterTypes.map(CharSequence::toString)
                    THREAD_KEY in takes && MESSAGE_ID in takes
                }
                if (loads) gateType = classDef.type
                if (loads || opens) classes[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        val gate = gateType ?: throw AssertionError("$name: no class loads the chat reels flag")
        val read = PatchContexts.of(classes.values).findChatReels()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val asks = classDef.methods.any { m ->
                    m.name == "<init>" && m.visualCode().any {
                        val ref = it.visualReference() as? MethodReference
                        ref != null && ref.definingClass == read.type && ref.name == read.name
                    }
                }
                if (asks) callers += ImmutableClassDef.of(classDef)
            }
        }
        assertTrue("$name: no constructor asks $gate", callers.isNotEmpty())
        for (classDef in callers) classes[classDef.type] = classDef

        // The wrappers the collectors take, their message fields' types and their ancestors.
        val wrappers = callers.flatMap { c -> c.methods.flatMap { m -> m.parameterTypes.map(CharSequence::toString) } }.toSet()
        var more = FixtureDex.classes(bundle, wrappers - classes.keys)
        classes += more
        more = FixtureDex.classes(bundle, more.values.flatMap { c -> c.fields.map { it.type } }.toSet() - classes.keys)
        classes += more
        more = FixtureDex.classes(bundle, more.values.mapNotNull { it.superclass }.toSet() - classes.keys)
        classes += more
        // The reel items the wrappers answer, and the Media they hold.
        val answers = classes.filterKeys { it in wrappers }.values.flatMap { c -> c.methods.map { it.returnType } }
        classes += FixtureDex.classes(bundle, answers.filter { it.startsWith("L") }.toSet() + MEDIA - classes.keys)

        // The message class, as the chat history test reads it.
        val parserBase = (FixtureDex.classesLoading(bundle, "message_item_dict")).distinctBy { it.type }
        val parserClasses = FixtureDex.withStringPools(bundle, parserBase).associateBy { it.type }.toMutableMap()
        for ((type, classDef) in parserClasses) classes.putIfAbsent(type, classDef)
        val withParser = PatchContexts.of(parserClasses.values)
        val named = mutableSetOf<String>()
        for (instruction in withParser.findMessageParser().visualCode()) {
            val field = instruction.visualReference() as? FieldReference ?: continue
            if (instruction.opcode == Opcode.IPUT_OBJECT || instruction.opcode == Opcode.IPUT_BOOLEAN) {
                named += field.definingClass
                named += field.type
            }
        }
        more = FixtureDex.classes(bundle, named - classes.keys)
        classes += more
        more = FixtureDex.classes(bundle, more.values.mapNotNull { it.superclass }.toSet() - classes.keys)
        classes += more
        classes[CHAT_REELS] = ImmutableClassDef.of(ExtensionDex.classDef(CHAT_REELS))

        // The reply bar under a reel opened from a chat, and the reply info class it answers (#488), with
        // its page select (#489) and the classes they extend, where Redex hoists the select on some builds.
        val replyMarkers = setOf(REPLY_HINT, REPLY_CURRENT_INFO, REPLY_CURRENT_ITEM, REPLY_PAGE)
        val replyClasses = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holds = dex.stringSection.any { it.startsWith("android_purge_") && PURGE_MARKER.find(it)?.groupValues?.get(1) in replyMarkers }
            if (!holds) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { m -> m.markers().any { it in replyMarkers } }) replyClasses += ImmutableClassDef.of(classDef)
            }
        }
        for (classDef in replyClasses) classes[classDef.type] = classDef
        var supers = replyClasses.mapNotNull { it.superclass }.toSet() - classes.keys
        while (supers.isNotEmpty()) {
            val got = FixtureDex.classes(bundle, supers)
            classes += got
            supers = got.values.mapNotNull { it.superclass }.toSet() - classes.keys
        }
        val infos = replyClasses.flatMap { c -> c.methods.filter { REPLY_CURRENT_INFO in it.markers() }.map { it.returnType } }
        classes += FixtureDex.classes(bundle, infos.toSet() - classes.keys)
        classes[CHAT_REELS_NOTICE] = ImmutableClassDef.of(ExtensionDex.classDef(CHAT_REELS_NOTICE))

        val context = PatchContexts.of(classes.values)
        val (found, replies, page) = try {
            val found = context.findReelCollector(context.findChatReels(), context.findMessageFields())
            val replies = context.findReplyBar(found)
            Triple(found, replies, context.findReplyPage(found, replies))
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        assertEquals("$name: the collector takes the wrapper", 1, found.collect.parameterTypes.size)
        assertEquals("$name: the message is a ${found.fields.owner}", "Z", found.fields.sent.type)

        val before = found.collect.implementation!!.instructions.count { it.opcode != Opcode.NOP }
        context.skipSentReels(found)
        val code = found.collect.implementation!!.instructions.toList()
        assertEquals("$name: added", before + 6, code.count { it.opcode != Opcode.NOP })
        assertEquals("$name: hooks", 1, code.count { it.text() == RECEIVED })
        assertEquals("$name: the hook goes first", RECEIVED, code[2].text())
        val hooked = classes.keys.flatMap { type -> context.mutableClassDefBy(type).methods }
            .count { m -> m.visualCode().any { it.text() == RECEIVED } }
        assertEquals("$name: methods hooked", 1, hooked)
        assertBridges(name, context.mutableClassDefBy(CHAT_REELS).methods, found)

        // Each info the collector builds goes to the extension, and the bar asks it twice (#488).
        context.giveSwipedReelsTheirReplies(found, replies)
        val replied = found.collect.implementation!!.instructions.toList()
        assertEquals("$name: added for the reply bar", before + 8, replied.count { it.opcode != Opcode.NOP })
        assertEquals("$name: infos handed over", 1, replied.count { it.text() == COLLECTED })
        val all = classes.keys.flatMap { type -> context.mutableClassDefBy(type).methods }
        for (hook in listOf(REPLY_TO, REPLY_INFO)) {
            assertEquals("$name: methods asking $hook", 1, all.count { m -> m.visualCode().any { it.text() == hook } })
        }
        assertEquals("$name: the hint update asks", replies.hint.name, all.single { m -> m.visualCode().any { it.text() == REPLY_TO } }.name)

        // The page select tells the extension which reel came on screen, right after its hint update (#489).
        context.pointOutMessagesAfterReels(found, replies, page)
        val told = classes.keys.flatMap { type -> context.mutableClassDefBy(type).methods }
            .filter { m -> m.visualCode().any { it.text() == NOTICE_PAGE } }
        assertEquals("$name: methods telling the notice", listOf(page.select.name), told.map { it.name })
        val select = page.select.visualCode()
        val hook = select.indexOfFirst { it.text() == NOTICE_PAGE }
        assertEquals("$name: hooked once", 1, select.count { it.text() == NOTICE_PAGE })
        assertEquals("$name: right after the hint update", "${replies.hint.definingClass}->${replies.hint.name}(I)V", select[hook - 1].text())

        // The adapter's add takes the reel items the wrappers answer.
        val adds = PatchContexts.of(FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, ADD_REELS)))
        try {
            checkAddTakesReels(adds.findPlaceholders(), found)
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
    }
}
