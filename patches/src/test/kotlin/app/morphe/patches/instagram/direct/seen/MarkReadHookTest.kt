/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.ThreadTrace
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.assertThreadGuard
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.traceThreadGuard
import app.morphe.patches.instagram.direct.seen.VisualSeenHookTest.Companion.snapshot
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import app.morphe.patches.instagram.direct.seen.ChatSeenFixture as F
import app.morphe.patches.instagram.direct.seen.MarkReadFixture as M

class MarkReadHookTest {
    @Test fun markAsReadIsOfferedAndHandledFirstAndNothingElseChanges() {
        val input = M.classes()
        val context = PatchContexts.of(input)
        val seen = context.findThreadSeen()
        val found = context.findMarkRead(seen)
        assertEquals(M.READ.toString(), found.markAsRead.toString())
        assertEquals(M.ROWS, found.builder.definingClass)
        assertEquals(2, found.rows)
        assertEquals("past the capability check", 4, found.offerAt)
        assertEquals(M.ACTIONS, found.action.definingClass)
        assertEquals(listOf(0, 1, 2), listOf(found.chosen, found.thread, found.key))
        assertEquals(M.SESSION.toString(), found.session.toString())
        assertEquals("on the branch past Instagram's check for a blocked action", 4, found.tapAt)
        assertEquals(M.PICKED, found.together.definingClass)
        assertEquals(7, found.togetherAt)
        assertEquals(9, found.bridges.size)

        val before = input.associate { it.type to snapshot(it.methods) }
        val original = input.associate { it.type to it.methods.associate { method -> method.name to method.visualCode() } }
        val first = original.getValue(F.HANDLER).getValue("send").first()
        context.readWithoutSeenReceipt()

        val handler = context.mutableClassDefBy(F.HANDLER).methods.single { it.name == "send" }
        assertThreadGuard(handler, F.COMPLETE.toString(), first, F.ACCOUNT.toString())
        assertEquals(ThreadTrace(completed = 1, sent = 0), traceThreadGuard(handler, true))
        assertEquals(ThreadTrace(completed = 0, sent = 1), traceThreadGuard(handler, false))

        val rows = context.mutableClassDefBy(M.ROWS).methods.single { it.name == "rows" }
        assertRowOffer(rows, 2, M.READ.toString(), original.getValue(M.ROWS).getValue("rows"), 4)
        assertOfferGated(rows, 4)
        val read = "offer ${M.READ}"
        val unread = "add ${M.UNREAD}"
        assertEquals(listOf(read, unread), traceRows(rows, capable = true, flag = false))
        assertEquals("Mark as read doesn't wait on the checks after the capability", listOf(read), traceRows(rows, capable = true, flag = true))
        assertEquals("a chat that can't be marked unread gets neither", emptyList<String>(), traceRows(rows, capable = false, flag = false))
        assertEquals(emptyList<String>(), traceRows(rows, capable = false, flag = true))

        val act = context.mutableClassDefBy(M.ACTIONS).methods.single { it.name == "act" }
        assertTapGuard(act, M.READ.toString(), M.SESSION.toString(), original.getValue(M.ACTIONS).getValue("act"), 4)
        val handed = listOf(CHOSEN, M.READ.toString(), SESSION, CHAT, KEY)
        assertEquals(TapTrace(handed, returned = true), traceTapGuard(act, 4, 0, 1, 2, blocked = false, handled = true))
        assertEquals(TapTrace(handed, returned = false), traceTapGuard(act, 4, 0, 1, 2, blocked = false, handled = false))
        for (handled in listOf(true, false)) {
            assertEquals("a blocked action never reaches the extension", TapTrace(emptyList(), returned = true),
                traceTapGuard(act, 4, 0, 1, 2, blocked = true, handled = handled))
        }

        val picked = context.mutableClassDefBy(M.PICKED).methods.single { it.name == "onClick" }
        assertTogetherHook(picked, original.getValue(M.PICKED).getValue("onClick"), 7)
        assertEquals("the next chat picked comes back through the extension's call", listOf(7),
            ControlFlow.of(picked).normal[9].filter { it != 10 })

        val bridges = context.mutableClassDefBy(INSTAGRAM_CHATS).methods.associateBy { it.name }
        assertBridge(bridges, "accountId", listOf(Opcode.CHECK_CAST to USER_SESSION, Opcode.INVOKE_VIRTUAL to M.USER_ID.toString(),
            Opcode.MOVE_RESULT_OBJECT to null, Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "threadId", listOf(Opcode.CHECK_CAST to THREAD_KEY, Opcode.IGET_OBJECT to M.THREAD_ID.toString(), Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "receiptKey", listOf(Opcode.CHECK_CAST to F.MUTATION, Opcode.INVOKE_VIRTUAL to M.RECEIPT_KEY.toString(),
            Opcode.MOVE_RESULT_OBJECT to null, Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "receiptMessage", listOf(Opcode.CHECK_CAST to F.MUTATION, Opcode.IGET_OBJECT to F.DETAILS.toString(),
            Opcode.IGET_OBJECT to F.ITEM_ID.toString(), Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "lastMessage", listOf(Opcode.CHECK_CAST to M.CHAT, Opcode.INVOKE_STATIC to M.LAST.toString(),
            Opcode.MOVE_RESULT_OBJECT to null, Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "messageId", listOf(Opcode.CHECK_CAST to M.MESSAGE, Opcode.INVOKE_VIRTUAL to M.MESSAGE_ID.toString(),
            Opcode.MOVE_RESULT_OBJECT to null, Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "senderId", listOf(Opcode.CHECK_CAST to M.MESSAGE, Opcode.IGET_OBJECT to M.SENDER_ID.toString(), Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "sendSeen", listOf(Opcode.CHECK_CAST to USER_SESSION, Opcode.CHECK_CAST to M.DETAIL,
            Opcode.INVOKE_STATIC to M.SEND.toString(), Opcode.RETURN_VOID to null))
        assertBridge(bridges, "markUnread", listOf(Opcode.CHECK_CAST to USER_SESSION, Opcode.CHECK_CAST to THREAD_KEY,
            Opcode.INVOKE_STATIC to M.UNREAD_CALL.toString(), Opcode.RETURN_VOID to null))
        assertEquals("each argument goes to Instagram's sender in its place", listOf(0, 1, 2, 3, 4), bridges.getValue("sendSeen").visualCode()[2].namedRegisters())
        assertEquals(listOf(0, 1, 2), bridges.getValue("markUnread").visualCode()[2].namedRegisters())

        val hooked = setOf("${F.HANDLER}->send(", "${M.ROWS}->rows(", "${M.ACTIONS}->act(", "${M.PICKED}->onClick(")
        for (candidate in input) {
            val now = snapshot(context.mutableClassDefBy(candidate.type).methods)
            if (candidate.type == INSTAGRAM_CHATS) continue
            assertEquals("${candidate.type} changed", before.getValue(candidate.type).filterNot { (name, _) -> hooked.any(name::startsWith) },
                now.filterNot { (name, _) -> hooked.any(name::startsWith) })
        }
        assertEquals("one guard each", 1, context.callsTo(HOLD_THREAD_SEEN, input))
        assertEquals(1, context.callsTo(OFFER_MARK_READ, input))
        assertEquals(1, context.callsTo(MARK_READ, input))
        assertEquals(1, context.callsTo(MARK_READ_TOGETHER, input))
    }

    @Test fun aMenuWithoutMarkAsReadIsRefused() = changed(M.MENU, "<clinit>", "$MENU_REFUSAL, found none") { it[6] = text(2, "MARK_AS_SEEN") }
    @Test fun aSecondMenuNamingBothIsRefused() = refuses(M.classes() + M.menu("Lfixture/OtherChatMenuAction;"), "$MENU_REFUSAL, found L")
    @Test fun markAsReadBuiltWithAnotherNameIsRefused() = changed(M.MENU, "<clinit>", "MARK_AS_READ isn't the name its constant is built with") {
        it[9] = call(Opcode.INVOKE_DIRECT, listOf(0, 1, 2), M.MENU, "<init>", listOf(STRING, "I"), "V")
    }
    @Test fun markAsReadStoredFromAnotherObjectIsRefused() = changed(M.MENU, "<clinit>", "MARK_AS_READ stores another object than it builds") {
        it[10] = field(Opcode.SPUT_OBJECT, 1, M.READ)
    }
    @Test fun markAsUnreadBuiltOnAnotherObjectIsRefused() = changed(M.MENU, "<clinit>", "MARK_AS_UNREAD is built on another object than it stores") {
        it[3] = ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 1)
    }

    @Test fun aBuilderWithoutMarkAsUnreadIsRefused() = changed(M.ROWS, "rows", "$BUILDER_REFUSAL, found 0") { it[5] = field(Opcode.SGET_OBJECT, 0, M.READ) }
    @Test fun aBuilderAddingToAnotherListIsRefused() = changed(M.ROWS, "rows", "$BUILDER_REFUSAL, found 0") {
        it[6] = call(Opcode.INVOKE_INTERFACE, listOf(1, 0), M.LIST, "add", listOf(OBJECT), "Z")
    }
    @Test fun aSecondBuilderOfferingMarkAsUnreadIsRefused() = refuses(M.classes() + M.rows("Lfixture/OtherChatMenuRows;"), "$BUILDER_REFUSAL, found 2")
    @Test fun aBuilderNotHandedTheChatsCapabilitiesIsRefused() =
        refuses(M.classes().map { if (it.type == M.ROWS) M.rows(parameters = listOf(USER_SESSION, OBJECT, M.LIST, "Z")) else it },
            "chat menu builder isn't handed one chat's capabilities")
    @Test fun aBuilderThatNeverChecksTheUnreadCapabilityIsRefused() =
        changed(M.ROWS, "rows", "expected one check of the chat's $MARK_UNREAD_CAPABILITY capability, found 0") {
            it[0] = field(Opcode.SGET_OBJECT, 0, M.CAN_MOVE)
        }
    @Test fun aCapabilityEnumWithoutMarkThreadAsUnreadIsRefused() =
        refuses(M.classes().map { if (it.type == M.CAPABILITY) M.capability("MARK_THREAD_AS_SPAM") else it },
            "expected one $MARK_UNREAD_CAPABILITY in its enum's initializer, found 0")
    @Test fun aBuilderCheckingOtherCapabilitiesIsRefused() = changed(M.ROWS, "rows", "chat menu builder checks other capabilities than the chat's") {
        it[1] = call(Opcode.INVOKE_VIRTUAL, listOf(1, 0), M.HAS)
    }
    @Test fun aBuilderThatDropsTheCapabilityAnswerIsRefused() = changed(M.ROWS, "rows", "chat menu builder drops its capability answer") {
        it[2] = ImmutableInstruction11n(Opcode.CONST_4, 0, 1)
    }
    @Test fun aBuilderThatGoesOnWithoutTheCapabilityIsRefused() =
        changed(M.ROWS, "rows", "chat menu builder doesn't stop for a chat that can't be marked unread") {
            it[3] = ImmutableInstruction21t(Opcode.IF_NEZ, 0, offset(it, 3, 7))
        }
    @Test fun aBuilderWhoseNoLandsOnTheOfferIsRefused() = changed(M.ROWS, "rows", GATE_REFUSAL) {
        it[3] = ImmutableInstruction21t(Opcode.IF_EQZ, 0, offset(it, 3, 4))
    }
    @Test fun aBuilderEnteredAtItsCapabilityBranchIsRefused() = changed(M.ROWS, "rows", GATE_REFUSAL) {
        it[4] = ImmutableInstruction21t(Opcode.IF_NEZ, 4, offset(it, 4, 3))
    }
    @Test fun aBuilderThatReadsAScratchRegisterPastItsCheckIsRefused() =
        changed(M.ROWS, "rows", "${M.ROWS}->rows has 0 local register(s) up to v15 that nothing reads after instruction 4, needs 1") {
            it.add(4, touch(0))
            it[3] = ImmutableInstruction21t(Opcode.IF_EQZ, 0, offset(it, 3, 8))
            it[5] = ImmutableInstruction21t(Opcode.IF_NEZ, 4, offset(it, 5, 8))
        }

    @Test fun aMissingTapHandlerIsRefused() = refuses(M.classes().filter { it.type != M.ACTIONS }, "$HANDLER_REFUSAL, found 0")
    @Test fun aSecondTapHandlerIsRefused() =
        refuses(M.classes().map { if (it.type == M.ACTIONS) M.actions(second = true) else it }, "$HANDLER_REFUSAL, found 2")
    @Test fun aTapHandlerWithoutAChatKeyIsRefused() = refuses(M.classes().map {
        if (it.type == M.ACTIONS) M.actions(parameters = listOf(M.MENU, M.INBOX_CHAT, OBJECT)) else it
    }, "chat menu action handler doesn't take one chat key")
    @Test fun aTapHandlerWithoutTheChatIsRefused() = refuses(M.classes().map {
        if (it.type == M.ACTIONS) M.actions(parameters = listOf(M.MENU, OBJECT, THREAD_KEY)) else it
    }, "expected one chat the menu action handler is given, found 0")
    @Test fun aTapHandlerThatNeverTakesTheUnreadMarkOffIsRefused() =
        changed(M.ACTIONS, "act", "chat menu action handler never takes a chat's unread mark through Instagram's call") {
            it[8] = call(Opcode.INVOKE_STATIC, listOf(1, 9, 0), M.SENDER, "pin", listOf(USER_SESSION, THREAD_KEY, "Z"), "V")
        }
    @Test fun aTapHandlerKeepingTwoAccountsIsRefused() = refuses(M.classes().map { if (it.type == M.ACTIONS) M.actions(sessions = 2) else it },
        "expected one account the chat menu action handler keeps, found 2")
    @Test fun aMissingBlockCheckIsRefused() = refuses(M.classes().filter { it.type != M.BLOCKS },
        "expected exactly one check for a chat action Instagram blocks in this Instagram build, found none")
    @Test fun aTapHandlerThatNeverAsksWhetherTheActionIsBlockedIsRefused() =
        changed(M.ACTIONS, "act", "expected one check for a blocked chat action in the chat menu action handler, found 0") {
            it[2] = call(Opcode.INVOKE_STATIC, listOf(1, 0), M.BLOCKS, "logged", listOf(USER_SESSION, "I"), "Z")
        }
    @Test fun aTapHandlerThatDropsTheBlockedAnswerIsRefused() =
        changed(M.ACTIONS, "act", "chat menu action handler drops Instagram's answer on a blocked action") {
            it[3] = ImmutableInstruction11n(Opcode.CONST_4, 2, 0)
        }
    @Test fun aTapHandlerThatGoesOnWhenBlockedIsRefused() =
        changed(M.ACTIONS, "act", "chat menu action handler doesn't stop when Instagram blocks the action") {
            it[5] = ImmutableInstruction10x(Opcode.NOP)
        }
    @Test fun aJumpPastTheBlockCheckIsRefused() =
        changed(M.ACTIONS, "act", "a jump reaches the chat menu action handler's blocked branch past Instagram's check") {
            it.add(9, ImmutableInstruction21t(Opcode.IF_NEZ, 0, 0))
            it[9] = ImmutableInstruction21t(Opcode.IF_NEZ, 0, offset(it, 9, 4))
        }
    @Test fun aTapHandlerThatReusesTheChatsRegisterIsRefused() =
        changed(M.ACTIONS, "act", "${M.ACTIONS}->act writes over parameter 1 (v8) at instruction(s) 0, before instruction(s) 5") {
            it.add(0, ImmutableInstruction12x(Opcode.MOVE_OBJECT, 8, 7))
        }
    @Test fun aTapHandlerThatReadsAScratchRegisterPastTheCheckIsRefused() =
        changed(M.ACTIONS, "act", "${M.ACTIONS}->act has 4 local register(s) up to v15 that nothing reads after instruction 4, needs 5") {
            it.add(6, touch(5))
        }

    @Test fun aMissingMarkReadTogetherIsRefused() = refuses(M.classes().filter { it.type != M.PICKED }, "$TOGETHER_REFUSAL, found none")
    @Test fun aSecondMarkReadTogetherIsRefused() = refuses(M.classes() + M.picked("Lfixture/OtherPickedChats;"), "$TOGETHER_REFUSAL, found L")
    @Test fun aMarkReadTogetherThatQueuesNoReceiptIsRefused() = changed(M.PICKED, "onClick", "$QUEUE_REFUSAL, found 0") {
        it[7] = call(Opcode.INVOKE_STATIC, listOf(1, 2, 3, 4, 5), M.SENDER, "log", listOf(USER_SESSION, M.DETAIL, STRING, STRING, STRING), "V")
    }
    @Test fun aMarkReadTogetherThatQueuesTwiceIsRefused() = changed(M.PICKED, "onClick", "$QUEUE_REFUSAL, found 2") {
        it.add(7, call(Opcode.INVOKE_STATIC, listOf(1, 2, 3, 4, 5), M.SEND))
    }
    @Test fun aTapHandlerThatIsAlsoMarkReadTogetherIsRefused() =
        refuses(M.edit(M.classes().filter { it.type != M.PICKED }, M.ACTIONS, "act") { it.add(0, text(0, MARK_READ_TOGETHER_ACTION)) },
            "Instagram's Mark as read for chats picked together is also the chat menu's builder or tap handler")

    @Test fun aMissingMarkReadRouteIsRefused() = refuses(M.classes().filter { it.type != M.ROUTE }, "$ROUTE_REFUSAL, found none")
    @Test fun aSecondMarkReadRouteIsRefused() = refuses(M.classes() + M.route("Lfixture/OtherMarkReadRequests;"), "$ROUTE_REFUSAL, found L")
    @Test fun aRouteThatSendsTheIdsSwappedIsRefused() = changed(M.ROUTE, "handle", PICK_REFUSAL) {
        it[12] = call(Opcode.INVOKE_STATIC, listOf(7, 0, 8, 2, 5), M.SEND)
    }
    @Test fun aRouteThatLosesItsMessageIsRefused() = changed(M.ROUTE, "handle", PICK_REFUSAL) { it.add(8, ImmutableInstruction11n(Opcode.CONST_4, 4, 0)) }
    @Test fun aRouteThatMarksTheChatUnreadIsRefused() =
        changed(M.ROUTE, "handle", "Instagram's handler for marking a chat read doesn't hand its unread call false") {
            it[13] = ImmutableInstruction11n(Opcode.CONST_4, 0, 1)
        }
    @Test fun aRouteThatNeverSendsTheReceiptIsRefused() =
        changed(M.ROUTE, "handle", "expected one receipt sent by Instagram's handler for marking a chat read, found 0") {
            it[12] = call(Opcode.INVOKE_STATIC, listOf(7, 0, 8, 5, 2), M.SENDER, "log", listOf(USER_SESSION, M.DETAIL, STRING, STRING, STRING), "V")
        }

    @Test fun aKeyWithoutItsThreadIdLabelIsRefused() = changed(THREAD_KEY, "toString", "expected one chat key's thread id label, found 0") {
        it[2] = text(2, "DirectThreadKey{id='")
    }
    @Test fun aKeyThatSpellsSomethingElseAfterItsLabelIsRefused() =
        changed(THREAD_KEY, "toString", "chat key's toString doesn't write its own thread id after the label") {
            it[5] = call(Opcode.INVOKE_STATIC, listOf(2, 1, 4, 3, 0), M.TEXT, "join", List(5) { STRING }, STRING)
        }
    @Test fun aReceiptWithoutItsChatKeyIsRefused() =
        refuses(M.classes().map { if (it.type == F.MUTATION) M.mutation(withKey = false) else it }, "expected one receipt's chat key, found 0")

    @Test fun aHandlerThatNamesNoItemIdIsRefused() = changed(F.HANDLER, "send", "expected one $RECEIPT_ITEM the receipt handler names, found 0") {
        it[9] = text(0, "item_ids")
    }
    @Test fun aHandlerThatNeverSendsItsItemIdIsRefused() = changed(F.HANDLER, "send", "receipt handler never sends its $RECEIPT_ITEM") {
        it[10] = call(Opcode.INVOKE_STATIC, listOf(0, 1), "Lfixture/Network;", "put", listOf(STRING, OBJECT), "V")
    }
    @Test fun aHandlerWhoseItemIdIsntTheReceiptsIsRefused() =
        changed(F.HANDLER, "send", "receipt handler's $RECEIPT_ITEM isn't read off the receipt's details") { it[8] = text(1, "m1") }
    @Test fun aHandlerThatNeverReadsTheReceiptsDetailsIsRefused() = changed(F.HANDLER, "send", "receipt handler never reads the receipt's details") {
        it[7] = ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, ImmutableFieldReference(F.MUTATION, "copy", F.SEEN_ITEM))
    }
    @Test fun aReceiptWithoutDetailsIsRefused() =
        refuses(M.classes().map { if (it.type == F.MUTATION) M.mutation(withDetails = false) else it },
            "expected one details the receipt keeps its message in, found 0")
    @Test fun aSenderThatNeverFillsInTheDetailsIsRefused() =
        refuses(M.classes().map { if (it.type == M.SENDER) M.sender(fillsDetails = false) else it },
            "live receipt sender doesn't fill in the receipt's details once")

    @Test fun aMissingAccountClassIsRefused() = refuses(M.classes().filter { it.type != USER_SESSION }, "$USER_SESSION is missing")
    @Test fun anAccountWithoutAnIdIsRefused() = refuses(M.classes().map { if (it.type == USER_SESSION) M.session(userId = false) else it },
        "expected one account id $USER_SESSION answers, found 0")
    @Test fun anAccountInterfaceIsRefused() = refuses(M.classes().map {
        if (it.type == USER_SESSION) M.session(flags = AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value) else it
    }, "$USER_SESSION is an interface")

    @Test fun aMessageTheExtensionCantReachIsRefused() = refuses(M.classes().map { if (it.type == M.MESSAGE) M.message(AccessFlags.FINAL.value) else it },
        "${M.MESSAGE_ID} isn't public, so the extension can't reach it")
    @Test fun anUnreadCallTheExtensionCantReachIsRefused() =
        refuses(M.classes().map { if (it.type == M.SENDER) M.sender(unreadFlags = AccessFlags.STATIC.value) else it },
            "${M.UNREAD_CALL} isn't public, so the extension can't reach it")

    /** The hold isn't written either when Mark as read can't be, so a build gets both or neither. */
    @Test fun anInstanceSenderLeavesTheHoldUnwrittenToo() = refuses(M.classes().filter { it.type != M.SENDER } + F.creator() + M.sender(withSend = false),
        "live receipt sender doesn't take an account, a detail and three ids")
    @Test fun anExtensionWithoutTheTapHookIsRefused() = refuses(M.classes().map { if (it.type == THREAD_SEEN) M.extension(markRead = false) else it },
        "expected one extension's public static markRead, found 0")
    @Test fun anExtensionWithoutTheRowHookIsRefused() = refuses(M.classes().map { if (it.type == THREAD_SEEN) M.extension(offer = false) else it },
        "expected one extension's public static offerMarkRead, found 0")
    @Test fun anExtensionWithoutTheTogetherHookIsRefused() =
        refuses(M.classes().map { if (it.type == THREAD_SEEN) M.extension(together = false) else it },
            "expected one extension's public static markReadTogether, found 0")
    @Test fun aMissingBridgeIsRefused() = refuses(M.classes().map { if (it.type == INSTAGRAM_CHATS) M.bridges(without = "senderId") else it },
        "expected one extension's chat bridge senderId, found 0")
    @Test fun aMissingReceiptMessageBridgeIsRefused() =
        refuses(M.classes().map { if (it.type == INSTAGRAM_CHATS) M.bridges(without = "receiptMessage") else it },
            "expected one extension's chat bridge receiptMessage, found 0")
    @Test fun aMissingBridgeClassIsRefused() = refuses(M.classes().filter { it.type != INSTAGRAM_CHATS }, "extension has no $INSTAGRAM_CHATS")

    private fun changed(type: String, name: String, why: String, change: (MutableList<Instruction>) -> Unit) =
        refuses(M.edit(M.classes(), type, name, change), why)

    /** [input] is refused for [why], with nothing in it edited first. */
    private fun refuses(input: List<ClassDef>, why: String) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val refusal = assertThrows(PatchException::class.java) { context.readWithoutSeenReceipt() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$THREAD_SEEN_PATCH: "))
        assertTrue("refused for something other than \"$why\": ${refusal.message}", why in refusal.message!!)
        input.forEach { assertEquals("${it.type} was edited before refusal", before[it.type], snapshot(context.mutableClassDefBy(it.type).methods)) }
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.callsTo(reference: String, input: List<ClassDef>) =
        input.sumOf { candidate -> mutableClassDefBy(candidate.type).methods.sumOf { method -> method.visualCode().count { it.visualReference()?.toString() == reference } } }

    /** A call that reads [register], so nothing may borrow it there. */
    private fun touch(register: Int) = call(Opcode.INVOKE_STATIC, listOf(register), "Lfixture/Network;", "touch", listOf(OBJECT), "V")

    companion object {
        private const val MENU_REFUSAL = "expected exactly one chat menu action enum in this Instagram build"
        private const val BUILDER_REFUSAL = "expected one chat menu builder offering Mark as unread"
        private const val GATE_REFUSAL = "chat menu builder can be entered past its capability check"
        private const val HANDLER_REFUSAL = "expected one chat menu action handler"
        private const val TOGETHER_REFUSAL = "expected exactly one Instagram's Mark as read for chats picked together in this Instagram build"
        private const val QUEUE_REFUSAL = "expected one receipt Instagram's Mark as read for chats picked together queues"
        private const val ROUTE_REFUSAL = "expected exactly one Instagram handler for marking a chat read in this Instagram build"
        private const val PICK_REFUSAL = "expected one message Instagram's handler for marking a chat read sends its receipt for, found 0"

        /** Stand-ins the tap trace hands the guard. */
        internal const val CHOSEN = "chosen row"
        internal const val SESSION = "account"
        internal const val CHAT = "chat"
        internal const val KEY = "chat key"

        /** The builder offers Mark as read on its own list at [at], then runs [original] unchanged around it. */
        internal fun assertRowOffer(method: Method, rows: Int, markAsRead: String, original: List<Instruction>, at: Int) {
            val code = method.visualCode()
            assertEquals(listOf(Opcode.SGET_OBJECT, Opcode.INVOKE_STATIC), code.subList(at, at + 2).map { it.opcode })
            assertEquals(markAsRead, code[at].visualReference().toString())
            assertEquals(OFFER_MARK_READ, code[at + 1].visualReference().toString())
            assertEquals(listOf(method.parameterRegisterNumber(rows), (code[at] as OneRegisterInstruction).registerA), code[at + 1].namedRegisters())
            assertEquals(1, code.count { it.visualReference()?.toString() == OFFER_MARK_READ })
            assertEquals(shape(original), shape(code.take(at) + code.drop(at + 2)))
        }

        /**
         * Only the yes of the builder's check of the chat's capabilities reaches the offer at [at],
         * and its no goes past the offer.
         */
        internal fun assertOfferGated(method: Method, at: Int) {
            val code = method.visualCode()
            val flow = ControlFlow.of(method)
            assertEquals(CAPABILITIES, (code[at - 3].visualReference() as MethodReference).definingClass)
            assertEquals(Opcode.MOVE_RESULT, code[at - 2].opcode)
            assertEquals(Opcode.IF_EQZ, code[at - 1].opcode)
            assertEquals((code[at - 2] as OneRegisterInstruction).registerA, (code[at - 1] as OneRegisterInstruction).registerA)
            assertEquals("only the capability check's yes reaches the offer", listOf(at - 1), flow.normal.indices.filter { at in flow.normal[it] })
            assertTrue("a chat without the capability goes past the offer", flow.normal[at - 1].single { it != at } > at + 1)
            assertTrue(flow.exceptional.none { at in it })
        }

        /**
         * Runs the fixture's builder with the chat's capability answer [capable] and its flag
         * [flag], and lists the offers it makes and the rows it adds, in order.
         */
        internal fun traceRows(method: Method, capable: Boolean, flag: Boolean): List<String> {
            val code = method.visualCode()
            val flow = ControlFlow.of(method)
            val registers = mutableMapOf<Int, Any?>(method.parameterRegisterNumber(method.parameterTypes.size - 1) to flag)
            val done = mutableListOf<String>()
            var answer: Boolean? = null
            var at = 0
            while (true) {
                val instruction = code[at]
                when (instruction.opcode) {
                    Opcode.SGET_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = instruction.visualReference().toString()
                    Opcode.INVOKE_VIRTUAL -> answer = capable
                    Opcode.MOVE_RESULT -> registers[(instruction as OneRegisterInstruction).registerA] = answer
                    Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                        val yes = registers[(instruction as OneRegisterInstruction).registerA] == true
                        if ((instruction.opcode == Opcode.IF_EQZ) != yes) {
                            at = flow.normal[at].single { it != at + 1 }
                            continue
                        }
                    }
                    Opcode.INVOKE_STATIC -> {
                        assertEquals(OFFER_MARK_READ, instruction.visualReference().toString())
                        done += "offer ${registers[instruction.namedRegisters()[1]]}"
                    }
                    Opcode.INVOKE_INTERFACE -> done += "add ${registers[instruction.namedRegisters()[1]]}"
                    Opcode.RETURN_VOID -> return done
                    else -> error("unsupported builder instruction ${instruction.opcode}")
                }
                at++
            }
        }

        /**
         * The guard sits at [at], on the branch on Instagram's answer whether it blocks the action:
         * a blocked action goes straight to Instagram's own branch, and otherwise the extension's
         * answer goes to that branch in its place. The rest is [original], unchanged.
         */
        internal fun assertTapGuard(method: Method, markAsRead: String, session: String, original: List<Instruction>, at: Int) {
            val code = method.visualCode()
            val answer = (code[at] as OneRegisterInstruction).registerA
            assertEquals(listOf(Opcode.IF_NEZ, Opcode.MOVE_OBJECT_FROM16, Opcode.SGET_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT,
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT), code.subList(at, at + 9).map { it.opcode })
            assertEquals(markAsRead, code[at + 2].visualReference().toString())
            assertEquals(session, code[at + 4].visualReference().toString())
            assertEquals(MARK_READ, code[at + 7].visualReference().toString())
            assertEquals("the extension's answer goes where Instagram's was", answer, (code[at + 8] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.IF_EQZ, code[at + 9].opcode)
            assertEquals(answer, (code[at + 9] as OneRegisterInstruction).registerA)
            val borrowed = code[at + 7].namedRegisters()
            assertEquals("five registers of its own", 5, borrowed.toSet().size)
            assertTrue(answer !in borrowed)
            assertEquals(1, code.count { it.visualReference()?.toString() == MARK_READ })
            assertEquals("a blocked action goes straight to Instagram's own branch", setOf(at + 1, at + 9), ControlFlow.of(method).normal[at].toSet())
            assertEquals(shape(original), shape(code.take(at) + code.drop(at + 9)))
        }

        internal data class TapTrace(val arguments: List<Any?>, val returned: Boolean)

        /**
         * Runs the tap handler from the guard at [at], with Instagram's answer [blocked], stand-ins
         * in `this` and the row, chat and key parameters at [chosen], [thread] and [key], and the
         * extension answering [handled]. Returned is true when the handler returns there, as it does
         * for a blocked action, and false when it goes on to Instagram's own code for the row.
         */
        internal fun traceTapGuard(method: Method, at: Int, chosen: Int, thread: Int, key: Int, blocked: Boolean, handled: Boolean): TapTrace {
            val code = method.visualCode()
            val flow = ControlFlow.of(method)
            val self = Any()
            val registers = mutableMapOf<Int, Any?>(
                method.parameterRegisterNumber(0) - 1 to self,
                method.parameterRegisterNumber(chosen) to CHOSEN,
                method.parameterRegisterNumber(thread) to CHAT,
                method.parameterRegisterNumber(key) to KEY,
                (code[at] as OneRegisterInstruction).registerA to blocked,
            )
            var arguments: List<Any?> = emptyList()
            var index = at
            repeat(16) {
                val instruction = code[index]
                when (instruction.opcode) {
                    Opcode.IF_NEZ, Opcode.IF_EQZ -> {
                        val yes = registers[(instruction as OneRegisterInstruction).registerA] == true
                        if ((instruction.opcode == Opcode.IF_EQZ) != yes) {
                            if (instruction.opcode == Opcode.IF_EQZ) return TapTrace(arguments, false)
                            index = flow.normal[index].single { it != index + 1 }
                            return@repeat
                        }
                    }
                    Opcode.MOVE_OBJECT_FROM16 -> (instruction as TwoRegisterInstruction).let { registers[it.registerA] = registers[it.registerB] }
                    Opcode.SGET_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = instruction.visualReference().toString()
                    Opcode.IGET_OBJECT -> (instruction as TwoRegisterInstruction).let {
                        assertEquals("the account comes from the handler itself", self, registers[it.registerB])
                        registers[it.registerA] = SESSION
                    }
                    Opcode.INVOKE_STATIC -> {
                        assertEquals(MARK_READ, instruction.visualReference().toString())
                        arguments = instruction.namedRegisters().map { registers[it] }
                    }
                    Opcode.MOVE_RESULT -> registers[(instruction as OneRegisterInstruction).registerA] = handled
                    Opcode.RETURN_VOID -> return TapTrace(arguments, true)
                    else -> error("unsupported guard instruction ${instruction.opcode}")
                }
                index++
            }
            error("the guard ran on past Instagram's branch")
        }

        /**
         * The extension hears of each chat at [at], just before Instagram's own sender queues its
         * receipt, with the sender's own arguments, and every way to the sender's call goes through
         * it first. The rest is [original], unchanged.
         */
        internal fun assertTogetherHook(method: Method, original: List<Instruction>, at: Int) {
            val code = method.visualCode()
            assertEquals(MARK_READ_TOGETHER, code[at].visualReference().toString())
            assertEquals(code[at + 1].namedRegisters(), code[at].namedRegisters())
            assertEquals(1, code.count { it.visualReference()?.toString() == MARK_READ_TOGETHER })
            assertEquals(shape(original), shape(code.take(at) + code.drop(at + 1)))
            val flow = ControlFlow.of(method)
            assertEquals("only the extension's call leads to the sender's", listOf(at), flow.normal.indices.filter { at + 1 in flow.normal[it] })
        }

        /** The bridge's body is [body], ahead of the stub it replaces. */
        internal fun assertBridge(bridges: Map<String, Method>, name: String, body: List<Pair<Opcode, String?>>) {
            val code = bridges.getValue(name).visualCode()
            assertEquals(name, body, code.take(body.size).map { it.opcode to it.visualReference()?.toString() })
        }

        private val PAYLOADS = setOf(Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD)

        /**
         * Each instruction's opcode, reference and registers. A nop just ahead of a payload only aligns
         * it, and the assembler adds or drops one whenever code ahead of the payload changes length.
         */
        private fun shape(code: List<Instruction>) = code
            .filterIndexed { at, instruction -> instruction.opcode != Opcode.NOP || code.getOrNull(at + 1)?.opcode !in PAYLOADS }
            .map { Triple(it.opcode, it.visualReference()?.toString(), it.namedRegisters()) }
    }
}

/**
 * Stand-ins for a chat's long press menu in the shapes 450 has: the enum naming both read actions,
 * the builder that offers Mark as unread on one chat once its capabilities allow it, the handler a
 * tap on the menu runs once Instagram hasn't blocked the action, Instagram's Mark as read for chats
 * picked together, Instagram's own handler for marking a chat read, the account, and the
 * extension's hooks and bridges. The chat receipt's fixture comes along, with its live sender the
 * static one 450 has, which fills in the receipt's details.
 */
internal object MarkReadFixture {
    const val MENU = "Lfixture/ChatMenuAction;"
    const val ROWS = "Lfixture/ChatMenuRows;"
    const val ACTIONS = "Lfixture/ChatMenuActions;"
    const val CHAT = "Lfixture/Chat;"
    const val INBOX_CHAT = "Lfixture/InboxChat;"
    const val MESSAGES = "Lfixture/Messages;"
    const val MESSAGE = "Lfixture/Message;"
    const val DETAIL = "Lfixture/SeenDetail;"
    const val SENDER = "Lfixture/ChatSeenSender;"
    const val ROUTE = "Lfixture/MarkReadRequests;"
    const val THREADS = "Lfixture/ThreadStore;"
    const val TEXT = "Lfixture/Text;"
    const val CAPABILITY = "Lfixture/Capability;"
    const val BLOCKS = "Lfixture/ThreadActionChecks;"
    const val PICKED = "Lfixture/PickedChats;"
    const val LOG = "Lfixture/Log;"
    const val LIST = "Ljava/util/List;"
    private const val ENUM = "Ljava/lang/Enum;"
    private const val INTEGER = "Ljava/lang/Integer;"

    val READ = ImmutableFieldReference(MENU, "read", MENU)
    val UNREAD = ImmutableFieldReference(MENU, "unread", MENU)
    val SESSION = ImmutableFieldReference(ACTIONS, "session", USER_SESSION)
    val THREAD_ID = ImmutableFieldReference(THREAD_KEY, "id", STRING)
    val OTHER_TEXT = ImmutableFieldReference(THREAD_KEY, "v2", STRING)
    val SENDER_ID = ImmutableFieldReference(MESSAGE, "sender", STRING)
    val CAN_MOVE = ImmutableFieldReference(CAPABILITY, "move", CAPABILITY)
    val CAN_UNREAD = ImmutableFieldReference(CAPABILITY, "unread", CAPABILITY)
    val PICKED_SESSION = ImmutableFieldReference(PICKED, "session", USER_SESSION)
    private val MANAGER = ImmutableFieldReference(F.MANAGER, "instance", F.MANAGER)
    val SEND = ImmutableMethodReference(SENDER, "send", listOf(USER_SESSION, DETAIL, STRING, STRING, STRING), "V")
    val UNREAD_CALL = ImmutableMethodReference(SENDER, "unread", listOf(USER_SESSION, THREAD_KEY, "Z"), "V")
    val LAST = ImmutableMethodReference(MESSAGES, "last", listOf(CHAT), MESSAGE)
    val MESSAGE_ID = ImmutableMethodReference(MESSAGE, "id", emptyList(), STRING)
    val RECEIPT_KEY = ImmutableMethodReference(F.MUTATION, "key", emptyList(), THREAD_KEY)
    val HAS = ImmutableMethodReference(CAPABILITIES, "has", listOf(CAPABILITY), "Z")
    val BLOCKED = ImmutableMethodReference(BLOCKS, "blocked", listOf(USER_SESSION, "I"), "Z")
    val USER_ID = ImmutableMethodReference(USER_SESSION, "getUserId", emptyList(), STRING)
    private val ITEM = ImmutableMethodReference(F.SEEN_ITEM, "of", listOf(STRING, STRING), F.SEEN_ITEM)

    private const val PUBLIC = 0x1
    private val INTERFACE = AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value

    fun classes(): List<ClassDef> = F.classes().filter { it.type != F.CREATOR && it.type != THREAD_SEEN } + listOf(
        sender(), mutation(), seenBase(), seenItem(), session(), menu(), capability(), rows(), blocks(), actions(), picked(),
        chat(), inboxChat(), messages(), message(), detail(), key(), route(), extension(), bridges(),
    )

    /**
     * The receipt's live sender, static as 450's is, which fills in the receipt's details from the
     * thread and message ids it's handed, and the call that takes a chat's unread mark off beside
     * it. The account, the detail and the three ids are v4 to v8.
     */
    fun sender(withSend: Boolean = true, fillsDetails: Boolean = true, unreadFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value): ClassDef =
        clazz(SENDER, listOfNotNull(
            method(SENDER, "send", listOf(USER_SESSION, DETAIL, STRING, STRING, STRING), "V", 9, listOfNotNull(
                text(1, THREAD_SEEN_KEY), typed(Opcode.NEW_INSTANCE, 0, F.MUTATION),
                call(Opcode.INVOKE_DIRECT, listOf(0), F.BASE, "<init>", emptyList(), "V"),
                call(Opcode.INVOKE_STATIC, listOf(6, 7), ITEM), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                ImmutableInstruction22c(Opcode.IPUT_OBJECT, 2, 0, F.DETAILS).takeIf { fillsDetails },
                field(Opcode.SGET_OBJECT, 3, MANAGER),
                call(Opcode.INVOKE_VIRTUAL, listOf(3, 0), F.MANAGER, "dispatch", listOf(F.BASE), "Z"),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ), static = true).takeIf { withSend },
            ImmutableMethod(SENDER, "unread", listOf(USER_SESSION, THREAD_KEY, "Z").map(::parameter), "V", unreadFlags, null, null,
                ImmutableMethodImplementation(3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)),
        ))

    /** The receipt: its details, an id of its own, and the chat key it answers. */
    fun mutation(withKey: Boolean = true, withDetails: Boolean = true): ClassDef = ImmutableClassDef(F.MUTATION, PUBLIC, F.BASE, null, null, null,
        listOfNotNull(
            ImmutableField(F.MUTATION, F.DETAILS.name, F.SEEN_ITEM, AccessFlags.PUBLIC.value, null, null, null).takeIf { withDetails },
            ImmutableField(F.MUTATION, "offlineId", STRING, AccessFlags.PUBLIC.value, null, null, null),
        ),
        listOfNotNull(
            method(F.MUTATION, "key", emptyList(), THREAD_KEY, 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))).takeIf { withKey },
        ))

    /** What every seen item keeps: the chat's thread id and the message's item id. */
    fun seenBase(): ClassDef = ImmutableClassDef(F.SEEN_BASE, PUBLIC or AccessFlags.ABSTRACT.value, OBJECT, null, null, null,
        listOf("threadId", F.ITEM_ID.name).map { ImmutableField(F.SEEN_BASE, it, STRING, AccessFlags.PUBLIC.value, null, null, null) }, null)

    fun seenItem(): ClassDef = ImmutableClassDef(F.SEEN_ITEM, PUBLIC or AccessFlags.FINAL.value, F.SEEN_BASE, null, null, null, null,
        listOf(method(F.SEEN_ITEM, ITEM.name, ITEM.parameterTypes.map(Any::toString), F.SEEN_ITEM, 3, listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), static = true)))

    /** The account class, which answers its own id. */
    fun session(userId: Boolean = true, flags: Int = PUBLIC): ClassDef = ImmutableClassDef(USER_SESSION, flags, OBJECT, null, null, null, null,
        listOfNotNull(method(USER_SESSION, USER_ID.name, emptyList(), STRING, 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))).takeIf { userId }))

    /** Mark as unread is copied before it's built and Mark as read isn't, as 450 builds the two. */
    fun menu(type: String = MENU): ClassDef {
        val read = ImmutableFieldReference(type, READ.name, type)
        val unread = ImmutableFieldReference(type, UNREAD.name, type)
        val constant = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value, ENUM, null, null, null,
            listOf(read, unread).map { ImmutableField(type, it.name, type, constant, null, null, null) },
            listOf(method(type, "<clinit>", emptyList(), "V", 4, listOf(
                text(2, MARK_AS_UNREAD), ImmutableInstruction11n(Opcode.CONST_4, 1, 0), typed(Opcode.NEW_INSTANCE, 3, type),
                ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 3),
                call(Opcode.INVOKE_DIRECT, listOf(0, 2, 1), type, "<init>", listOf(STRING, "I"), "V"),
                field(Opcode.SPUT_OBJECT, 3, unread),
                text(2, MARK_AS_READ), ImmutableInstruction11n(Opcode.CONST_4, 1, 1), typed(Opcode.NEW_INSTANCE, 0, type),
                call(Opcode.INVOKE_DIRECT, listOf(0, 2, 1), type, "<init>", listOf(STRING, "I"), "V"),
                field(Opcode.SPUT_OBJECT, 0, read),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ), static = true)))
    }

    /**
     * The kinds of capability a chat has: an enum whose constructor takes a number ahead of the
     * name, and whose [unreadName] constant is built on a copy, as 450 builds it.
     */
    fun capability(unreadName: String = MARK_UNREAD_CAPABILITY): ClassDef {
        val constant = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value
        val build = ImmutableMethodReference(CAPABILITY, "<init>", listOf(INTEGER, STRING, "I"), "V")
        return ImmutableClassDef(CAPABILITY, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value, ENUM, null, null, null,
            listOf(CAN_MOVE, CAN_UNREAD).map { ImmutableField(CAPABILITY, it.name, CAPABILITY, constant, null, null, null) },
            listOf(method(CAPABILITY, "<clinit>", emptyList(), "V", 5, listOf(
                text(2, "MOVE_FOLDER"), ImmutableInstruction11n(Opcode.CONST_4, 1, 0), ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
                typed(Opcode.NEW_INSTANCE, 0, CAPABILITY), call(Opcode.INVOKE_DIRECT, listOf(0, 1, 2, 3), build),
                field(Opcode.SPUT_OBJECT, 0, CAN_MOVE),
                text(2, unreadName), ImmutableInstruction11n(Opcode.CONST_4, 3, 1), typed(Opcode.NEW_INSTANCE, 4, CAPABILITY),
                ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 4), call(Opcode.INVOKE_DIRECT, listOf(0, 1, 2, 3), build),
                field(Opcode.SPUT_OBJECT, 4, CAN_UNREAD),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ), static = true)))
    }

    /**
     * v0 is the one local; the account, the chat's capabilities, the rows and the flag are v1 to v4.
     * Mark as unread is offered only to a chat with the capability for it, and then only without the
     * flag, as 450 offers it after more checks.
     */
    fun rows(type: String = ROWS, parameters: List<String> = listOf(USER_SESSION, CAPABILITIES, LIST, "Z")): ClassDef {
        val code = mutableListOf<Instruction>(
            field(Opcode.SGET_OBJECT, 0, CAN_UNREAD), call(Opcode.INVOKE_VIRTUAL, listOf(2, 0), HAS),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction21t(Opcode.IF_EQZ, 0, 0),
            ImmutableInstruction21t(Opcode.IF_NEZ, 4, 0), field(Opcode.SGET_OBJECT, 0, UNREAD),
            call(Opcode.INVOKE_INTERFACE, listOf(3, 0), LIST, "add", listOf(OBJECT), "Z"), ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        code[3] = ImmutableInstruction21t(Opcode.IF_EQZ, 0, offset(code, 3, 7))
        code[4] = ImmutableInstruction21t(Opcode.IF_NEZ, 4, offset(code, 4, 7))
        return clazz(type, listOf(method(type, "rows", parameters, "V", 5, code, static = true)))
    }

    /** Instagram's check whether it blocks an action on a chat, which logs the dialog it shows. The account and the action are v1 and v2. */
    fun blocks(): ClassDef = clazz(BLOCKS, listOf(method(BLOCKS, BLOCKED.name, BLOCKED.parameterTypes.map(Any::toString), "Z", 3, listOf(
        text(0, THREAD_ACTION_BLOCKED), call(Opcode.INVOKE_STATIC, listOf(0), LOG, "event", listOf(STRING), "V"),
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0),
    ), static = true)))

    /**
     * v0 to v5 are locals, this is v6, and the row, the chat and its key are v7 to v9. Instagram
     * asks whether it blocks the action first, and returns when it does.
     */
    fun actions(parameters: List<String> = listOf(MENU, INBOX_CHAT, THREAD_KEY), second: Boolean = false, sessions: Int = 1): ClassDef {
        fun act(name: String): Method {
            val code = mutableListOf<Instruction>(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, SESSION), ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                call(Opcode.INVOKE_STATIC, listOf(1, 0), BLOCKED), ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
                ImmutableInstruction21t(Opcode.IF_EQZ, 2, 0), ImmutableInstruction10x(Opcode.RETURN_VOID),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, SESSION),
                call(Opcode.INVOKE_STATIC, listOf(1, 9, 0), UNREAD_CALL), ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            code[4] = ImmutableInstruction21t(Opcode.IF_EQZ, 2, offset(code, 4, 6))
            return method(ACTIONS, name, parameters, "V", 10, code)
        }
        val fields = (0 until sessions).map { ImmutableField(ACTIONS, if (it == 0) SESSION.name else "account$it", USER_SESSION,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null) }
        return ImmutableClassDef(ACTIONS, PUBLIC, OBJECT, null, null, null, fields, listOfNotNull(act("act"), act("again").takeIf { second }))
    }

    /**
     * Instagram's Mark as read for chats picked together: it logs the action, then queues each
     * picked chat's receipt through the live sender, coming back to that call for the next one.
     * v0 to v5 are locals, this is v6, and the dialog and the button are v7 and v8.
     */
    fun picked(type: String = PICKED): ClassDef {
        val code = mutableListOf<Instruction>(
            text(0, MARK_READ_TOGETHER_ACTION), call(Opcode.INVOKE_STATIC, listOf(0), LOG, "event", listOf(STRING), "V"),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, ImmutableFieldReference(type, PICKED_SESSION.name, USER_SESSION)),
            ImmutableInstruction11n(Opcode.CONST_4, 2, 0), text(3, "t1"), text(4, "m1"), text(5, "s1"),
            call(Opcode.INVOKE_STATIC, listOf(1, 2, 3, 4, 5), SEND), ImmutableInstruction21t(Opcode.IF_NEZ, 8, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        code[8] = ImmutableInstruction21t(Opcode.IF_NEZ, 8, offset(code, 8, 7))
        return ImmutableClassDef(type, PUBLIC, OBJECT, null, null, null,
            listOf(ImmutableField(type, PICKED_SESSION.name, USER_SESSION, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
            listOf(method(type, "onClick", listOf(OBJECT, "I"), "V", 9, code)))
    }

    fun chat(): ClassDef = ImmutableClassDef(CHAT, INTERFACE, OBJECT, null, null, null, null, null)
    fun inboxChat(): ClassDef = ImmutableClassDef(INBOX_CHAT, INTERFACE, OBJECT, listOf(CHAT), null, null, null, null)

    fun messages(): ClassDef = clazz(MESSAGES, listOf(method(MESSAGES, "last", listOf(CHAT), MESSAGE, 2, listOf(
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), static = true)))

    fun message(flags: Int = PUBLIC): ClassDef = ImmutableClassDef(MESSAGE, flags, OBJECT, null, null, null,
        listOf(ImmutableField(MESSAGE, SENDER_ID.name, STRING, AccessFlags.PUBLIC.value, null, null, null)),
        listOf(method(MESSAGE, "id", emptyList(), STRING, 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))))

    fun detail(): ClassDef = ImmutableClassDef(DETAIL, PUBLIC or AccessFlags.FINAL.value, OBJECT, null, null, null, null, null)

    /** this is v5: the thread id and its second id are read into v4 and v3, and spelled after their labels. */
    fun key(): ClassDef = ImmutableClassDef(THREAD_KEY, PUBLIC or AccessFlags.FINAL.value, OBJECT, null, null, null,
        listOf(THREAD_ID, OTHER_TEXT).map { ImmutableField(THREAD_KEY, it.name, STRING, AccessFlags.PUBLIC.value, null, null, null) },
        listOf(method(THREAD_KEY, "toString", emptyList(), STRING, 6, listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 4, 5, THREAD_ID), ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 5, OTHER_TEXT),
            text(2, THREAD_KEY_TEXT), text(1, "', mThreadV2Id='"), text(0, "}"),
            call(Opcode.INVOKE_STATIC, listOf(2, 4, 1, 3, 0), TEXT, "join", List(5) { STRING }, STRING),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ))))

    /** Instagram's own handler for marking a chat read. v0 to v5 are locals, this is v6, the account v7 and the thread id v8. */
    fun route(type: String = ROUTE): ClassDef = clazz(type, listOf(method(type, "handle", listOf(USER_SESSION, STRING), STRING, 9, listOf(
        text(0, MARK_READ_HANDLER), typed(Opcode.NEW_INSTANCE, 1, THREAD_KEY), ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
        call(Opcode.INVOKE_DIRECT, listOf(1, 8, 2), THREAD_KEY, "<init>", listOf(STRING, LIST), "V"),
        call(Opcode.INVOKE_STATIC, listOf(1, 2), THREADS, "find", listOf(THREAD_KEY, OBJECT), INBOX_CHAT),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
        call(Opcode.INVOKE_STATIC, listOf(3), LAST), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
        call(Opcode.INVOKE_VIRTUAL, listOf(4), MESSAGE_ID), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 4, SENDER_ID),
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0), call(Opcode.INVOKE_STATIC, listOf(7, 0, 8, 5, 2), SEND),
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0), call(Opcode.INVOKE_STATIC, listOf(7, 1, 0), UNREAD_CALL),
        text(0, MARK_READ_DONE), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    ))))

    fun extension(offer: Boolean = true, markRead: Boolean = true, together: Boolean = true): ClassDef = clazz(THREAD_SEEN, listOfNotNull(
        method(THREAD_SEEN, "hold", listOf(OBJECT, OBJECT), "Z", 3, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0)), static = true),
        method(THREAD_SEEN, "offerMarkRead", listOf(LIST, OBJECT), "V", 2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), static = true)
            .takeIf { offer },
        method(THREAD_SEEN, "markRead", List(5) { OBJECT }, "Z", 6, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0)), static = true).takeIf { markRead },
        method(THREAD_SEEN, "markReadTogether", listOf(OBJECT, OBJECT, STRING, STRING, STRING), "V", 5,
            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), static = true).takeIf { together },
    ))

    /** The extension's bridge stubs, as javac writes them: null back, or nothing done. */
    fun bridges(without: String? = null): ClassDef = clazz(INSTAGRAM_CHATS, listOf(
        "accountId" to (listOf(OBJECT) to STRING), "threadId" to (listOf(OBJECT) to STRING), "receiptKey" to (listOf(OBJECT) to OBJECT),
        "receiptMessage" to (listOf(OBJECT) to STRING), "lastMessage" to (listOf(OBJECT) to OBJECT),
        "messageId" to (listOf(OBJECT) to STRING), "senderId" to (listOf(OBJECT) to STRING),
        "sendSeen" to (listOf(OBJECT, OBJECT, STRING, STRING, STRING) to "V"), "markUnread" to (listOf(OBJECT, OBJECT, "Z") to "V"),
    ).filter { it.first != without }.map { (name, shape) ->
        val (parameters, returns) = shape
        if (returns == "V") {
            method(INSTAGRAM_CHATS, name, parameters, returns, parameters.size, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), static = true)
        } else {
            method(INSTAGRAM_CHATS, name, parameters, returns, parameters.size + 1, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), static = true)
        }
    })

    /** [replace] for this fixture's own classes, kept whole but for the one method. */
    fun edit(classes: List<ClassDef>, type: String, name: String, change: (MutableList<Instruction>) -> Unit): List<ClassDef> =
        replace(classes, type, name, change)

    private fun parameter(type: String) = com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(type, null, null)
}
