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
    private val base = "Lfixture/BarBase;"
    private val spotlight = "Lfixture/SpotlightBar;"
    private val info = "Lfixture/Info;"
    private val adapter = "Lfixture/Adapter;"
    private val item = "Lfixture/Item;"
    private val wrapper = "Lfixture/Wrapper;"
    private val owner = "Lfixture/Collector;"
    private val initCall = "$info-><init>(Lfixture/Sender;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;)V"
    private val state = "Lfixture/State;"
    private val source = "Lfixture/Source;"
    private val sender = "Lfixture/Sender;"
    private val user = "Lcom/instagram/user/model/User;"
    private val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

    @Test
    fun theHooksAndBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val wanted = listOf(COLLECTED, REPLY_TO, REPLY_INFO).map { it.substringAfter("->") } +
            listOf("messageOf(Ljava/lang/Object;)Ljava/lang/String;", "count(Ljava/lang/Object;)I", "item(Ljava/lang/Object;I)Ljava/lang/Object;",
                OWN_MESSAGE.substringAfter("->"))
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
     * extension right after its hint update, in that call's own form, and the bridges read a message's
     * time, whether an object is a message, and the controller's source and adapter.
     */
    @Test
    fun thePageSelectHandsItsPageToTheNoticeAfterTheHint() {
        for (range in listOf(false, true)) {
            val context = context(rangeHint = range)
            val collector = collector()
            val replies = context.findReplyBar(collector)
            val found = context.findReplyPage(collector, replies)
            assertEquals("select", found.select.name)
            val before = found.select.visualCode().map { it.opcode }

            context.pointOutMessagesAfterReels(collector, replies, found)

            val code = found.select.visualCode()
            val hook = if (range) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC
            assertEquals("range $range: Instagram's hint update, then the hook", before.take(2) + hook + before.drop(2), code.map { it.opcode })
            assertEquals("$bar->hint(I)V", code[1].text())
            assertEquals(NOTICE_PAGE, code[2].text())
            assertEquals("range $range: handed p0 and p1, the controller and the page", listOf(1, 2), code[2].registers())
        }

        val context = context()
        val collector = collector()
        val replies = context.findReplyBar(collector)
        context.pointOutMessagesAfterReels(collector, replies, context.findReplyPage(collector, replies))
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

    /**
     * Redex hoists the page select into the bar's superclass, casting this to the bar first (454's
     * 386014757 and 386014759), or merges another viewer's bar into it behind an instance-of (386014760).
     * The hook goes after the bar's own hint update, on the cast register, and the other viewer's branch
     * keeps Instagram's code alone.
     */
    @Test
    fun aPageSelectRedexHoistedHandsTheCastBarOn() {
        for (hoisted in listOf("cast", "merged")) {
            val context = context(hoisted = hoisted)
            val collector = collector()
            val replies = context.findReplyBar(collector)
            val found = context.findReplyPage(collector, replies)
            assertEquals("$hoisted: the superclass's select", base, found.select.definingClass)
            val before = found.select.visualCode()
            val call = before.indexOfFirst { it.text() == "$bar->hint(I)V" }

            context.pointOutMessagesAfterReels(collector, replies, found)

            val code = found.select.visualCode()
            assertEquals("$hoisted: added", before.size + 1, code.size)
            assertEquals("$hoisted: the hint update", "$bar->hint(I)V", code[call].text())
            assertEquals("$hoisted: then the hook", NOTICE_PAGE, code[call + 1].text())
            assertEquals("$hoisted: handed the cast bar and the page", listOf(1, 3), code[call + 1].registers())
            assertEquals("$hoisted: hooked once", 1, code.count { it.text() == NOTICE_PAGE })
            if (hoisted == "merged") {
                val other = code.indexOfFirst { it.text() == "$spotlight->hint(I)V" }
                assertEquals("the other viewer's branch goes on as it was", Opcode.RETURN_VOID, code[other + 1].opcode)
            }
        }
    }

    @Test
    fun aPageSelectItCantReadFailsBeforeAnythingChanges() {
        val select = "expected one instance method of $bar or a class it extends taking a page first and returning nothing to call " +
            "$bar->hint(I)V, its page select, found"
        val cases = listOf(
            context(pages = 0) to "$select 0",
            context(pages = 2) to "$select 2",
            context(pageApart = true) to "$select 0",
            context(hintCalls = 0) to "$select 0",
            context(hintCalls = 2) to "expected $bar->select to call $bar->hint(I)V once, found 2",
            context(handsPage = false) to "$bar->select doesn't hand its page to $bar->hint(I)V",
            context(pageSpoiled = true) to "$bar->select hands $bar->hint(I)V its page: $bar->select writes over parameter 0 (v2)",
            context(pageJumped = true) to "a jump or exception handler enters $bar->select right after it calls $bar->hint(I)V",
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
        assertEquals(listOf("$bar->adapter:$adapter"), found.adapter.map { "$it" })
        assertEquals("$adapter->count()I", "${found.count}")
        assertEquals("$adapter->at(I)$item", "${found.item}")
        assertEquals("$bar->source:$VIEWER_SOURCE", found.source)
        assertEquals("$bar->currentItem($bar)$item", found.currentItem)
        assertEquals("the info built", 1, found.built)
        assertEquals("the collector's free locals", listOf(2, 3), listOf(found.wrapper, found.chat))
        assertEquals(1, found.hintInfo)
        assertEquals(listOf(0, 2, 3), found.hintFree)
        assertEquals(1, found.currentInfo)
        assertEquals(listOf(0, 2), found.currentFree)

        context.giveSwipedReelsTheirReplies(collector, found)

        val collect = collector.collect.visualCode()
        assertEquals("the collector: added", 11, collect.size)
        val copy = collect[5] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, collect[5].opcode)
        assertEquals("the wrapper copied", listOf(2, 5), listOf(copy.registerA, copy.registerB))
        val self = collect[6] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, collect[6].opcode)
        assertEquals("the collector itself copied, one per chat (#658)", listOf(3, 4), listOf(self.registerA, self.registerB))
        assertEquals(COLLECTED, collect[7].text())
        assertEquals("handed the info, the wrapper and the collector", listOf(1, 2, 3), collect[7].arguments())
        assertEquals("then Instagram's", Opcode.GOTO, collect[8].opcode)

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

    /**
     * A reel paged in past the chat's loaded messages has no info anywhere, so its quick reactions did
     * nothing (#804). The extension's bridge builds one from the message id, first sender and time the
     * reel carries, the way Instagram's data source builds it, and answers null when any is missing.
     */
    @Test
    fun aReelsOwnMessageIsBuiltLikeInstagramsDataSourceBuildsIt() {
        val context = context()
        val collector = collector()
        val replies = context.findReplyBar(collector)
        val found = context.findReelMessage(collector, replies)
        assertEquals("$source->build", found.builder)
        assertEquals("$item->message:Ljava/lang/String;", "${found.message}")
        assertEquals("$item->senders:Ljava/util/List;", "${found.senders}")
        assertEquals("$item->sent:Ljava/lang/Long;", "${found.sent}")
        assertEquals(sender, found.sender)
        assertEquals("Ljava/lang/Object;-><init>()V", "${found.senderInit}")
        assertEquals("$sender->user:$user", "${found.user}")
        assertEquals("$sender->time:J", "${found.time}")

        context.buildRepliesFromReels(found)

        val bridge = context.mutableClassDefBy(CHAT_REELS).methods.single { it.name == "ownMessage" }
        val code = bridge.visualCode()
        assertEquals(listOf(item, "$item->message:Ljava/lang/String;"), code.take(2).map { it.text() })
        val texts = code.mapNotNull { it.text() }
        for (wanted in listOf("$item->senders:Ljava/util/List;", "$item->sent:Ljava/lang/Long;", sender, "Ljava/lang/Object;-><init>()V",
            "$sender->user:$user", "$sender->time:J", info, initCall)) {
            assertTrue("the bridge uses $wanted: $texts", wanted in texts)
        }
        val returns = code.filter { it.opcode == Opcode.RETURN_OBJECT }
        assertEquals("the info, or null", 2, returns.size)
        val nulls = code.indices.filter { code[it].opcode == Opcode.CONST_4 && (code[it] as OneRegisterInstruction).registerA == 0 }
        assertTrue("null when the reel carries nothing", nulls.any { code.getOrNull(it + 1)?.opcode == Opcode.RETURN_OBJECT })
        assertTrue("static", AccessFlags.STATIC.isSet(bridge.accessFlags))

        assertEquals("two builders reading the same way agree", "$source->build", context(builders = listOf("message", "message")).let {
            it.findReelMessage(collector, it.findReplyBar(collector)).builder
        })
    }

    @Test
    fun aReelsOwnMessageItCantReadKeepsInstagramsReplyBar() {
        val collector = collector()
        val way = "expected one way to build a $info from a $item's own message, found"
        val cases = listOf(
            context(builders = emptyList()) to "$way 0",
            context(builders = listOf("message", "other")) to "$way 2",
            context(itemFlags = 0) to "$item->",
            context(senderFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) to "$sender->",
        )
        for ((context, expected) in cases) {
            val replies = context.findReplyBar(collector)
            val refusal = assertThrows(PatchException::class.java) { context.findReelMessage(collector, replies) }
            assertTrue("${refusal.message} should start with $expected", refusal.message!!.contains(expected))
            assertEquals("the rest of the patch still goes in", null, context.findReelMessageOrWarn(collector, replies))
        }
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

    /**
     * On 456 the reel on screen reads the adapter off the controller's viewer state (`LX/0I4a;->A11`,
     * then `LX/08YO;->A0d` on 386214574), so the hint update's hook and the adapter's bridge read both.
     */
    @Test
    fun anAdapterBehindTheViewerStateIsReadThroughIt() {
        val context = context(throughState = true)
        val collector = collector()
        val found = context.findReplyBar(collector)
        assertEquals(listOf("$bar->state:$state", "$state->adapter:$adapter"), found.adapter.map { "$it" })

        context.giveSwipedReelsTheirReplies(collector, found)
        context.pointOutMessagesAfterReels(collector, found, context.findReplyPage(collector, found))

        val hint = found.hint.visualCode()
        assertEquals(listOf("$bar->state:$state", "$state->adapter:$adapter", "$bar->source:$VIEWER_SOURCE"), hint.subList(5, 8).map { it.text() })
        val adapterAt = hint[6] as TwoRegisterInstruction
        assertEquals("the state read into the adapter's register", adapterAt.registerA, adapterAt.registerB)
        assertEquals(REPLY_TO, hint[9].text())
        assertEquals("handed Instagram's info, the source, the adapter and the page", listOf(1, 3, 2, 0), hint[9].arguments())
        val bridge = context.mutableClassDefBy(CHAT_REELS_NOTICE).methods.single { it.name == "adapterOf" }.visualCode()
        assertEquals(listOf(bar, "$bar->state:$state", "$state->adapter:$adapter"), bridge.take(3).map { it.text() })
        assertEquals(Opcode.RETURN_OBJECT, bridge[3].opcode)

        assertRefused("$state->adapter:$adapter, on the way to the reply bar's adapter, isn't a public instance field the extension can read") {
            context(throughState = true, statePublic = false).findReplyBar(collector())
        }
    }

    @Test
    fun aBuildItCantReadFailsBeforeAnythingChanges() {
        val loading = "expected one method loading \"$REPLY_INFO_MISSING\", the reply bar's reply info, found"
        val hints = "expected $bar to have one instance (I)V method, its hint update, found"
        val cases = listOf(
            context(loaders = 0) to "$loading 0",
            context(loaders = 2) to "$loading 2",
            context(hints = 0) to "$hints 0",
            context(hints = 2) to "$hints 2",
            context(page = "Z") to "$hints 0",
            context(apart = true) to "expected $bar to have one static method taking it and answering a $item, its reel on screen, found 0",
            context(adapterSpoiled = true) to "$bar->currentItem doesn't reach its $adapter through fields of $bar",
            context(sources = 2) to "$bar holds 2 $VIEWER_SOURCE fields, not one",
            context(inits = 2) to "$info has 2 constructors, not one",
            context(joined = false) to "$bar->hint has no one place its ways to a $info meet",
        )
        for ((context, expected) in cases) assertRefused(expected) { context.findReplyBar(collector()) }
        assertRefused("expected $bar to have one static method taking it and answering a Lfixture/Other;, its reel on screen, found 0") {
            context().findReplyBar(collector(itemType = "Lfixture/Other;"))
        }
        assertRefused("something in $owner->collect jumps to just after it builds a $info") {
            context().findReplyBar(collector(jumped = true))
        }
        assertRefused("$owner->collect writes over this (v4)") {
            context().findReplyBar(collector(thisSpoiled = true))
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

    /** A call's registers, in its five-register or its range form. */
    private fun Instruction.registers(): List<Int> =
        if (this is RegisterRangeInstruction) List(registerCount) { startRegister + it } else arguments()

    /** Shaped like 453's 02YM.A01 where it builds a 0W3G: the info built, then a goto on to Instagram's use of it. */
    private fun collector(itemType: String = item, jumped: Boolean = false, time: String = "Ljava/lang/String;", thisSpoiled: Boolean = false): ReelCollector {
        val spoil = if (thisSpoiled) "const/4 p0, 0x0\n" else ""
        val body = spoil + if (jumped) {
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
            MutableMethod(method(owner, "collect", listOf(wrapper), "V", 4, final, body)),
            ImmutableFieldReference(wrapper, "m", "Lfixture/Message;"),
            fields,
            ImmutableMethodReference(wrapper, "reel", emptyList(), item),
            ImmutableFieldReference(itemType, "media", MEDIA),
            ImmutableFieldReference(wrapper, "session", "Lcom/instagram/common/session/UserSession;"),
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
        rangeHint: Boolean = false,
        hintCalls: Int = 1,
        pageSpoiled: Boolean = false,
        hoisted: String? = null,
        loaders: Int = 1,
        throughState: Boolean = false,
        statePublic: Boolean = true,
        adapterSpoiled: Boolean = false,
        builders: List<String> = listOf("message"),
        senderFlags: Int = AccessFlags.PUBLIC.value,
        itemFlags: Int = AccessFlags.PUBLIC.value,
    ): BytecodePatchContext {
        val pageOwner = if (pageApart) "Lfixture/Pages;" else if (hoisted != null) base else bar
        val handed = if (handsPage) "p1" else "p2"
        val hand = if (rangeHint) "invoke-virtual/range {p0 .. p1}, $bar->hint(I)V" else "invoke-virtual {p0, $handed}, $bar->hint(I)V"
        // Shaped like 453's 0T5D.Fxj: the page handed on to the hint update.
        val direct = """
            ${if (pageSpoiled) "const/4 p1, 0x0" else ""}
            const/4 v0, 0x0
            ${if (pageJumped) "if-eqz p2, :after" else ""}
            ${List(hintCalls) { hand }.joinToString("\n")}
            ${if (pageJumped) ":after" else ""}
            return-void
        """
        // Shaped like 454's 386014757 0R2Z.Fyu: this cast to the bar, then the page handed on with it.
        val cast = """
            move-object v1, p0
            check-cast v1, $bar
            const/4 v0, 0x0
            invoke-virtual {v1, p1}, $bar->hint(I)V
            invoke-virtual {v1}, $bar->show()V
            return-void
        """
        // Shaped like 454's 386014760 and 456's 0I4X.GAC: another viewer's bar merged in behind an instance-of.
        val merged = """
            const/4 v0, 0x0
            instance-of v1, p0, $bar
            if-eqz v1, :other
            move-object v1, p0
            check-cast v1, $bar
            invoke-virtual {v1, p1}, $bar->hint(I)V
            return-void
            :other
            move-object v1, p0
            check-cast v1, $spotlight
            invoke-virtual {v1, p1}, $spotlight->hint(I)V
            return-void
        """
        val body = when (hoisted) { "cast" -> cast; "merged" -> merged; else -> direct }
        val select = List(pages) { index ->
            method(pageOwner, if (index == 0) "select" else "select$index", listOf("I", "I"), "V", if (hoisted != null) 2 else 1, final, body)
        }
        // On 456 the hint update's other way asks the reply info for the reel on screen (0I4a.A1G calls A03).
        val other = if (joined) "invoke-static {p0}, $bar->current($bar)$info\nmove-result-object v1\ngoto :join" else "return-void"
        val hint = List(hints) { index ->
            method(
                bar, if (index == 0) "hint" else "hint$index", listOf(page), "V", 4, final,
                """
                    const/4 v0, 0x0
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
        // Shaped like 456's 0I4a.A03: the info kept cast and sent on with a goto, or looked up, then tested,
        // and its missing info reported.
        val current = method(
            bar, "current", listOf(bar), info, 3, static,
            """
                iget-object v1, p0, $bar->first:Ljava/lang/Object;
                if-eqz v1, :look
                check-cast v1, $info
                goto :join
                :look
                invoke-static {p0}, $bar->lookup($bar)$info
                move-result-object v1
                :join
                if-nez v1, :done
                ${if (loaders > 0) "const-string v0, \"$REPLY_INFO_MISSING\"" else ""}
                return-object v1
                :done
                return-object v1
            """,
        )
        val itemOwner = if (apart) "Lfixture/Elsewhere;" else bar
        // Shaped like 456's 0I4a.A02: the adapter read off the viewer state there.
        val reach = when {
            adapterSpoiled -> "invoke-static {p0}, $bar->adapterOf($bar)$adapter\nmove-result-object v1"
            throughState -> "iget-object v1, p0, $bar->state:$state\niget-object v1, v1, $state->adapter:$adapter"
            else -> "iget-object v1, p0, $bar->adapter:$adapter"
        }
        val currentItem = method(
            itemOwner, "currentItem", listOf(bar), item, 3, static,
            """
                const/4 v0, 0x0
                $reach
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
            ImmutableField(bar, "state", state, final, null, null, null) +
            ImmutableField(bar, "first", "Ljava/lang/Object;", final, null, null, null)
        val stateAdapter = ImmutableField(state, "adapter", adapter, if (statePublic) final else AccessFlags.FINAL.value, null, null, null)
        // A static (int)String pool of shared strings holds the report too, and isn't the reply info.
        val pool = method("Lfixture/Pool;", "A00", listOf("I"), "Ljava/lang/String;", 1, static, "const-string v0, \"$REPLY_INFO_MISSING\"\nreturn-object v0")
        val report = method("Lfixture/Report;", "report", emptyList(), "V", 1, final, "const-string v0, \"$REPLY_INFO_MISSING\"\nreturn-void")
        val barMethods = hint + current + show + (if (apart) emptyList() else listOf(currentItem)) + (if (pageOwner == bar) select else emptyList())
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
        // Shaped like 456.1's 07NS.A1Z: the data source builds a chat reel's info from what the reel carries.
        val built = builders.mapIndexed { index, field ->
            method(
                source, if (index == 0) "build" else "build$index", listOf(item), info, 7, static,
                """
                    iget-object v0, p0, $item->senders:Ljava/util/List;
                    if-eqz v0, :none
                    const/4 v1, 0x0
                    invoke-interface {v0, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, $user
                    iget-object v1, p0, $item->sent:Ljava/lang/Long;
                    if-eqz v1, :none
                    invoke-virtual {v1}, Ljava/lang/Long;->longValue()J
                    move-result-wide v1
                    new-instance v3, $sender
                    invoke-direct {v3}, Ljava/lang/Object;-><init>()V
                    iput-object v0, v3, $sender->user:$user
                    iput-wide v1, v3, $sender->time:J
                    iget-object v4, p0, $item->$field:Ljava/lang/String;
                    const-string v5, ""
                    const/4 v6, 0x0
                    new-instance v0, $info
                    invoke-direct {v0, v3, v4, v5, v6}, $initCall
                    return-object v0
                    :none
                    const/4 v0, 0x0
                    return-object v0
                """,
            )
        }
        val itemFields = listOf("message" to "Ljava/lang/String;", "other" to "Ljava/lang/String;", "senders" to "Ljava/util/List;", "sent" to "Ljava/lang/Long;")
            .map { (name, type) -> ImmutableField(item, name, type, itemFlags, null, null, null) }
        val senderFields = listOf(ImmutableField(sender, "user", user, senderFlags, null, null, null), ImmutableField(sender, "time", "J", senderFlags, null, null, null))
        val barSuper = if (hoisted != null) base else "Ljava/lang/Object;"
        val classes: List<ClassDef> = listOf(classDef(bar, barMethods, barFields, barSuper), classDef(info, constructors)) +
            classDef(item, emptyList(), itemFields) + classDef(sender, emptyList(), senderFields) +
            classDef(source, built) +
            (if (apart) listOf(classDef(itemOwner, listOf(currentItem))) else emptyList()) +
            (if (pageOwner != bar) listOf(classDef(pageOwner, select)) else emptyList()) +
            classDef(state, emptyList(), listOf(stateAdapter)) + classDef("Lfixture/Pool;", listOf(pool)) +
            (if (loaders > 1) listOf(classDef("Lfixture/Report;", listOf(report))) else emptyList()) +
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

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList(), superclass: String = "Ljava/lang/Object;"): ClassDef =
        ImmutableClassDef(type, final, superclass, emptyList(), null, null, fields, methods)
}
