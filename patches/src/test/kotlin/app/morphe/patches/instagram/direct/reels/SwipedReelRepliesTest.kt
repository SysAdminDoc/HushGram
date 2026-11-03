/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.direct.history.MessageFields
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.misc.extension.jumpTargets
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply bar under a reel swiped to from a chat asks the extension for that reel's own message (#488). */
class SwipedReelRepliesTest {
    private val bar = "Lfixture/Bar;"
    private val info = "Lfixture/Info;"
    private val adapter = "Lfixture/Adapter;"
    private val item = "Lfixture/Item;"
    private val wrapper = "Lfixture/Wrapper;"
    private val owner = "Lfixture/Collector;"
    private val initCall = "$info-><init>(Lfixture/Sender;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;)V"
    private val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

    private fun marker(name: String) = "android_purge_26_q3_$name"

    @Test
    fun theHooksAndBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val wanted = listOf(COLLECTED, REPLY_TO, REPLY_INFO).map { it.substringAfter("->") } +
            listOf("messageOf(Ljava/lang/Object;)Ljava/lang/String;", "count(Ljava/lang/Object;)I", "item(Ljava/lang/Object;I)Ljava/lang/Object;")
        for (hook in wanted) assertTrue("$hook is not in the extension: $declared", hook in declared)
        val notice = ExtensionDex.classDef(CHAT_REELS_NOTICE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val bridges = listOf(NOTICE_PAGE.substringAfter("->"), "timeOf(Ljava/lang/Object;)Ljava/lang/String;", "isMessage(Ljava/lang/Object;)Z",
            "sourceOf(Ljava/lang/Object;)Ljava/lang/Object;", "adapterOf(Ljava/lang/Object;)Ljava/lang/Object;")
        for (hook in bridges) assertTrue("$hook is not in the extension: $notice", hook in notice)
    }

    /**
     * Messages after a reel (#489): the bar's page select hands the controller and its page to the
     * extension first, and the bridges read a message's time, whether an object is a message, and the
     * controller's source and adapter.
     */
    @Test
    fun thePageSelectHandsItsPageToTheNoticeFirst() {
        val context = context()
        val collector = collector()
        val replies = context.findReplyBar(collector)
        val found = context.findReplyPage(collector, replies)
        assertEquals("select", found.select.name)
        val before = found.select.visualCode().map { it.opcode }

        context.pointOutMessagesAfterReels(collector, replies, found)

        val code = found.select.visualCode()
        assertEquals("the hook, then Instagram's", listOf(Opcode.INVOKE_STATIC_RANGE) + before, code.map { it.opcode })
        assertEquals(NOTICE_PAGE, code[0].text())
        val range = code[0] as RegisterRangeInstruction
        assertEquals("handed p0 and p1, the controller and the page", listOf(1, 2), listOf(range.startRegister, range.registerCount))
        assertEquals("$bar->hint(I)V", code[2].text())

        val bridges = context.mutableClassDefBy(CHAT_REELS_NOTICE).methods.groupBy { it.name }
        fun bridge(name: String) = bridges.getValue(name).single().visualCode()
        assertEquals(listOf("Lfixture/Message;", "Lfixture/Message;->time:Ljava/lang/String;"), bridge("timeOf").take(2).map { it.text() })
        assertEquals(Opcode.RETURN_OBJECT, bridge("timeOf")[2].opcode)
        assertEquals(listOf(Opcode.INSTANCE_OF, Opcode.RETURN), bridge("isMessage").take(2).map { it.opcode })
        assertEquals("Lfixture/Message;", bridge("isMessage")[0].text())
        assertEquals(listOf(bar, "$bar->source:$VIEWER_SOURCE"), bridge("sourceOf").take(2).map { it.text() })
        assertEquals(Opcode.RETURN_OBJECT, bridge("sourceOf")[2].opcode)
        assertEquals(listOf(bar, "$bar->adapter:$adapter"), bridge("adapterOf").take(2).map { it.text() })
        assertEquals(Opcode.RETURN_OBJECT, bridge("adapterOf")[2].opcode)
    }

    @Test
    fun aPageSelectItCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            context(pages = 0) to "expected one method holding the $REPLY_PAGE marker, found 0",
            context(pages = 2) to "expected one method holding the $REPLY_PAGE marker, found 2",
            context(pageApart = true) to "the $REPLY_PAGE marker sits in Lfixture/Pages;, not in the reply bar $bar",
            context(handsPage = false) to "$bar->select doesn't hand its page to $bar->hint(I)V",
            context(pageJumped = true) to "a jump or exception handler enters $bar->select at its first instruction",
        )
        for ((context, expected) in cases) {
            val collector = collector()
            val replies = context.findReplyBar(collector)
            assertRefused(expected) { context.findReplyPage(collector, replies) }
        }
        val collector = collector(time = "J")
        val context = context()
        assertRefused("the message keeps its time as J, not as text") { context.findReplyPage(collector, context.findReplyBar(collector)) }
    }

    /**
     * The collector hands over each info it builds with the wrapper, and both reply methods ask where
     * their two ways to the info meet, so the jump from the other way lands on the hook too.
     */
    @Test
    fun theBarAsksForTheReelOnScreensOwnMessage() {
        val context = context()
        val collector = collector()
        val found = context.findReplyBar(collector)
        assertEquals(info, found.info)
        assertEquals("$info->message:Ljava/lang/String;", "${found.message}")
        assertEquals("$bar->adapter:$adapter", "${found.adapter}")
        assertEquals("$adapter->count()I", "${found.count}")
        assertEquals("$adapter->at(I)$item", "${found.item}")
        assertEquals("$bar->source:$VIEWER_SOURCE", found.source)
        assertEquals("$bar->currentItem($bar)$item", found.currentItem)
        assertEquals("the info built", 1, found.built)
        assertEquals("the collector's free local", 2, found.wrapper)
        assertEquals(1, found.hintInfo)
        assertEquals(listOf(0, 2, 3), found.hintFree)
        assertEquals(1, found.currentInfo)
        assertEquals(listOf(0, 2), found.currentFree)

        context.giveSwipedReelsTheirReplies(collector, found)

        val collect = collector.collect.visualCode()
        assertEquals("the collector: added", 10, collect.size)
        val copy = collect[5] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, collect[5].opcode)
        assertEquals("the wrapper copied", listOf(2, 4), listOf(copy.registerA, copy.registerB))
        assertEquals(COLLECTED, collect[6].text())
        assertEquals("handed the info and the wrapper", listOf(1, 2), collect[6].arguments())
        assertEquals("then Instagram's", Opcode.GOTO, collect[7].opcode)

        val hint = found.hint.visualCode()
        assertEquals(REPLY_TO, hint[8].text())
        assertEquals("handed Instagram's info, the source, the adapter and the page", listOf(1, 3, 2, 0), hint[8].arguments())
        assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.MOVE_FROM16), hint.subList(4, 8).map { it.opcode })
        assertEquals("$bar->adapter:$adapter", hint[5].text())
        assertEquals("$bar->source:$VIEWER_SOURCE", hint[6].text())
        assertTaken("hint", hint, found.hint, 4)

        val current = found.current.visualCode()
        assertEquals(Opcode.MOVE_OBJECT_FROM16, current[6].opcode)
        assertEquals("$bar->currentItem($bar)$item", current[7].text())
        assertEquals("$bar->source:$VIEWER_SOURCE", current[9].text())
        assertEquals(REPLY_INFO, current[10].text())
        assertEquals("handed Instagram's info, the source and the reel", listOf(1, 0, 2), current[10].arguments())
        assertTaken("current", current, found.current, 6)

        val bridges = context.mutableClassDefBy(CHAT_REELS).methods.groupBy { it.name }
        fun bridge(name: String) = bridges.getValue(name).single().visualCode()
        assertEquals(listOf(info, "$info->message:Ljava/lang/String;"), bridge("messageOf").take(2).map { it.text() })
        assertEquals(Opcode.RETURN_OBJECT, bridge("messageOf")[2].opcode)
        assertEquals(listOf(adapter, "$adapter->count()I"), bridge("count").take(2).map { it.text() })
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.RETURN), bridge("count").subList(2, 4).map { it.opcode })
        assertEquals(listOf(adapter, "$adapter->at(I)$item"), bridge("item").take(2).map { it.text() })
        assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), bridge("item").subList(2, 4).map { it.opcode })
    }

    /** The hook sits at [at], takes the answer back into the info's register and casts it before Instagram's test, and the jump lands on it. */
    private fun assertTaken(what: String, code: List<Instruction>, method: Method, at: Int) {
        val test = at + 7
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[test - 2].opcode)
        assertEquals("$what: into the info's register", 1, (code[test - 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: cast", info, code[test - 1].text())
        assertTrue("$what: Instagram's test after", code[test].opcode == Opcode.IF_EQZ || code[test].opcode == Opcode.IF_NEZ)
        assertEquals("$what: Instagram's test of the info", 1, (code[test] as OneRegisterInstruction).registerA)
        val targets = method.jumpTargets()
        assertTrue("$what: the jump lands on the hook: $targets", at in targets)
        assertFalse("$what: nothing jumps past it: $targets", test in targets)
    }

    @Test
    fun aBuildItCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            context(hints = 0) to "expected one method holding the $REPLY_HINT marker, found 0",
            context(hints = 2) to "expected one method holding the $REPLY_HINT marker, found 2",
            context(apart = true) to "the reply bar's markers sit in $bar, Lfixture/Elsewhere; and $bar, not one class",
            context(page = "Z") to "$bar->hint isn't an instance method taking a page and returning nothing",
            context(sources = 2) to "$bar holds 2 $VIEWER_SOURCE fields, not one",
            context(inits = 2) to "$info has 2 constructors, not one",
            context(joined = false) to "$bar->hint has no one place its ways to a $info meet",
        )
        for ((context, expected) in cases) assertRefused(expected) { context.findReplyBar(collector()) }
        assertRefused("$bar->currentItem isn't a static method taking $bar and answering a Lfixture/Other;") {
            context().findReplyBar(collector(itemType = "Lfixture/Other;"))
        }
        assertRefused("something in $owner->collect jumps to just after it builds a $info") {
            context().findReplyBar(collector(jumped = true))
        }
    }

    private fun assertRefused(expected: String, find: () -> Unit) {
        val failure = assertThrows(expected, PatchException::class.java) { find() }
        assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
    }

    private fun Instruction.text(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.arguments(): List<Int> {
        val call = this as FiveRegisterInstruction
        return listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
    }

    /** Shaped like 453's 02YM.A01 where it builds a 0W3G: the info built, then a goto on to Instagram's use of it. */
    private fun collector(itemType: String = item, jumped: Boolean = false, time: String = "Ljava/lang/String;"): ReelCollector {
        val body = if (jumped) {
            """
                invoke-virtual {p1}, $wrapper->reel()$item
                move-result-object v0
                new-instance v1, $info
                const/4 v2, 0x0
                if-eqz v0, :after
                invoke-direct {v1, v2, v2, v2, v2}, $initCall
                :after
                invoke-static {v1, v0}, Lfixture/Chain;->add(Ljava/lang/Object;Ljava/lang/Object;)V
                return-void
            """
        } else {
            """
                invoke-virtual {p1}, $wrapper->reel()$item
                move-result-object v0
                new-instance v1, $info
                const/4 v2, 0x0
                invoke-direct {v1, v2, v2, v2, v2}, $initCall
                goto :use
                :use
                invoke-static {v1, v0}, Lfixture/Chain;->add(Ljava/lang/Object;Ljava/lang/Object;)V
                return-void
            """
        }
        val fields = MessageFields(
            "Lfixture/Message;",
            ImmutableFieldReference("Lfixture/Message;", "id", "Ljava/lang/String;"),
            ImmutableFieldReference("Lfixture/Message;", "time", time),
            ImmutableFieldReference("Lfixture/Message;", "sender", "Ljava/lang/String;"),
            ImmutableFieldReference("Lfixture/Message;", "sent", "Z"),
            ImmutableFieldReference("Lfixture/Message;", "text", "Ljava/lang/String;"),
            null,
        )
        return ReelCollector(
            MutableMethod(method(owner, "collect", listOf(wrapper), "V", 3, final, body)),
            ImmutableFieldReference(wrapper, "m", "Lfixture/Message;"),
            fields,
            ImmutableMethodReference(wrapper, "reel", emptyList(), item),
            ImmutableFieldReference(itemType, "media", MEDIA),
        )
    }

    /**
     * Shaped like 453's 0T5D: the hint update for a page reads the info one way and jumps to its test
     * from the other, the reply info does the same, and the reel on screen comes from the adapter
     * field's item at its count. The info's constructor stores its first String as the message.
     */
    private fun context(
        hints: Int = 1,
        apart: Boolean = false,
        page: String = "I",
        sources: Int = 1,
        inits: Int = 1,
        joined: Boolean = true,
        pages: Int = 1,
        pageApart: Boolean = false,
        handsPage: Boolean = true,
        pageJumped: Boolean = false,
    ): BytecodePatchContext {
        val pageOwner = if (pageApart) "Lfixture/Pages;" else bar
        val handed = if (handsPage) "p1" else "p2"
        val again = if (pageJumped) "if-eqz p2, :top" else ""
        // Shaped like 453's 0T5D.Fxj: its marker, then the page handed on to the hint update.
        val select = List(pages) { index ->
            method(
                pageOwner, if (index == 0) "select" else "select$index", listOf("I", "I"), "V", 1, final,
                """
                    :top
                    const-string v0, "${marker(REPLY_PAGE)}"
                    invoke-virtual {p0, $handed}, $bar->hint(I)V
                    $again
                    return-void
                """,
            )
        }
        val other = if (joined) "const/4 v1, 0x0\ngoto :join" else "return-void"
        val hint = List(hints) { index ->
            method(
                bar, if (index == 0) "hint" else "hint$index", listOf(page), "V", 4, final,
                """
                    const-string v0, "${marker(REPLY_HINT)}"
                    if-nez p1, :other
                    iget-object v1, p0, $bar->first:Ljava/lang/Object;
                    check-cast v1, $info
                    :join
                    if-eqz v1, :hide
                    invoke-virtual {p0}, $bar->show()V
                    return-void
                    :hide
                    return-void
                    :other
                    $other
                """,
            )
        }
        val current = method(
            bar, "current", listOf(bar), info, 3, static,
            """
                const-string v0, "${marker(REPLY_CURRENT_INFO)}"
                iget-object v1, p0, $bar->first:Ljava/lang/Object;
                if-nez v1, :cast
                const/4 v1, 0x0
                goto :join
                :cast
                check-cast v1, $info
                :join
                if-nez v1, :done
                return-object v1
                :done
                return-object v1
            """,
        )
        val itemOwner = if (apart) "Lfixture/Elsewhere;" else bar
        val currentItem = method(
            itemOwner, "currentItem", listOf(bar), item, 3, static,
            """
                const-string v0, "${marker(REPLY_CURRENT_ITEM)}"
                iget-object v1, p0, $bar->adapter:$adapter
                invoke-virtual {v1}, $adapter->count()I
                move-result v0
                invoke-virtual {v1, v0}, $adapter->at(I)$item
                move-result-object v2
                return-object v2
            """,
        )
        val show = method(bar, "show", emptyList(), "V", 0, final, "return-void")
        val barFields = List(sources) { ImmutableField(bar, if (it == 0) "source" else "source$it", VIEWER_SOURCE, final, null, null, null) } +
            ImmutableField(bar, "adapter", adapter, final, null, null, null) +
            ImmutableField(bar, "first", "Ljava/lang/Object;", final, null, null, null)
        val barMethods = hint + current + show + (if (apart) emptyList() else listOf(currentItem)) + (if (pageApart) emptyList() else select)
        val constructors = List(inits) { index ->
            val takes = if (index == 0) listOf("Lfixture/Sender;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/List;") else emptyList()
            val stores = if (index == 0) {
                """
                    iput-object p1, p0, $info->sender:Lfixture/Sender;
                    iput-object p2, p0, $info->message:Ljava/lang/String;
                    iput-object p3, p0, $info->other:Ljava/lang/String;
                    iput-object p4, p0, $info->list:Ljava/util/List;
                """
            } else ""
            method(
                info, "<init>", takes, "V", 0, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                "invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n$stores\nreturn-void",
            )
        }
        val classes: List<ClassDef> = listOf(classDef(bar, barMethods, barFields), classDef(info, constructors)) +
            (if (apart) listOf(classDef(itemOwner, listOf(currentItem))) else emptyList()) +
            (if (pageApart) listOf(classDef(pageOwner, select)) else emptyList()) +
            ImmutableClassDef.of(ExtensionDex.classDef(CHAT_REELS)) + ImmutableClassDef.of(ExtensionDex.classDef(CHAT_REELS_NOTICE))
        return PatchContexts.of(classes)
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
}
