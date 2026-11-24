/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.reel.MUTE_CLEAR_MODE
import app.morphe.patches.instagram.download.reel.MUTE_ICON_TYPE
import app.morphe.patches.instagram.download.reel.addMuteButtonToClearMode
import app.morphe.patches.instagram.download.reel.findClearModeRowAdd
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClearModeHookTest {
    private val handler = "Lfixture/FullscreenHandler;"
    private val reason = "Lfixture/FullscreenReason;"
    private val item = "Lfixture/ClipsItem;"
    private val viewer = "Lfixture/Viewer;"
    private val pager = "Lfixture/Pager;"
    private val pagers = "Lfixture/Pagers;"
    private val actions = "Lfixture/ViewerActions;"
    private val listener = "Lfixture/PagerListener;"
    private val button = "Lfixture/ExitButton;"
    private val scope = "Lfixture/Scope;"
    private val component = "Lfixture/Component;"
    private val function1 = "Lkotlin/jvm/functions/Function1;"
    private val function2 = "Lfixture/Function2;"
    private val unit = "Lfixture/Unit;->INSTANCE:Lfixture/Unit;"
    private val purge = "Lfixture/Purge;->mark(Ljava/lang/String;)V"
    private val row = "Lfixture/ScrubberRow;"
    private val rowState = "Lfixture/RowState;"
    private val reelState = "Lfixture/ReelState;"
    private val scrubberChild = "Lfixture/Scrubber;"
    private val stateHook = "Lfixture/StateHook;"
    private val holder = "Lfixture/ReelHolder;"
    private val cell = "Lfixture/Cell;"
    private val function0 = "Lkotlin/jvm/functions/Function0;"
    private val builder = "Ljava/lang/StringBuilder;"
    private val boxed = "Ljava/lang/Boolean;"
    private val anyType = "Ljava/lang/Object;"
    private val stringType = "Ljava/lang/String;"
    private val floatValue = "Ljava/lang/Number;->floatValue()F"
    private val scrollState = "Lfixture/ScrollState;"
    private val pagerHost = "Lfixture/PagerHost;"
    private val haptics = "Lfixture/Haptics;"
    private val hooks = listOf(
        CLEAR_MODE_ENTERED, CLEAR_MODE_LEFT, CLEAR_MODE_CURRENT, CLEAR_MODE_PAGED, CLEAR_MODE_BUILT, CLEAR_MODE_SCRUBBER, CLEAR_MODE_CHROME_ALPHA,
        CLEAR_MODE_SETTLED, CLEAR_MODE_READY, CLEAR_MODE_QUIET,
    )

    private fun marker(name: String, quarter: String = "q3") = "android_purge_26_${quarter}_$name"

    /** Every hook the patch writes is in the ReelClearMode the bundle ships, public and static, and so is every stub. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(CLEAR_MODE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val stubs = listOf(
            "$CLEAR_MODE_ENTER_STUB($anyType$anyType)V", "$CLEAR_MODE_ID_STUB($anyType)$stringType",
            "$CLEAR_MODE_ACTION_STUB($anyType)$anyType", "$CLEAR_MODE_SET_ACTION_STUB($anyType$anyType)V",
            "${CLEAR_MODE_CHROME_STUB}(${anyType}F)V", "$CLEAR_MODE_LEAVE_STUB($anyType$anyType$anyType)V",
        )
        for (signature in hooks.map { it.substringAfter("->") } + stubs) {
            assertTrue("$signature is not in the extension: $declared", signature in declared)
        }
    }

    /**
     * Mute button in clear mode (#608): the speaker goes in the list the exit button's render hands
     * its row, right after the last add to it, with that list. A render shaped any other way, or an
     * extension without the speaker, is refused and the render is left as it was.
     */
    @Test
    fun theClearModeSpeakerGoesInRightAfterTheRowListsLastAdd() {
        val buttonRow = "Lfixture/ButtonRow;"
        val arrayList = "Ljava/util/ArrayList;"
        fun render(
            lists: Int = 1, answered: Boolean = true, answerOverList: Boolean = false, rowTakesList: Boolean = true,
            rowJumpedTo: Boolean = false,
        ) = classDef(button, listOf(method(button, "render", listOf(anyType), anyType, 5, buildString {
            appendLine("const-string v0, \"${marker(CLEAR_MODE_BUTTON)}\"")
            appendLine("invoke-static { v0 }, $purge")
            repeat(lists) { appendLine("new-instance v1, $arrayList\ninvoke-direct { v1 }, $arrayList-><init>()V") }
            appendLine("invoke-virtual { v1, p1 }, $arrayList->add(Ljava/lang/Object;)Z")
            appendLine("invoke-virtual { v1, p1 }, $arrayList->add(Ljava/lang/Object;)Z")
            if (answered) appendLine("move-result ${if (answerOverList) "v1" else "v2"}")
            if (rowJumpedTo) appendLine("if-eqz v2, :row\n:row")
            appendLine("new-instance v0, $buttonRow")
            appendLine(if (rowTakesList) "invoke-direct { v0, v1 }, $buttonRow-><init>(Ljava/util/List;)V" else "invoke-direct { v0 }, $buttonRow-><init>()V")
            appendLine("return-object v0")
        })))

        for (answered in listOf(true, false)) {
            val stock = render(answered = answered)
            val context = PatchContexts.of(listOf(stock, ExtensionDex.classDef(MUTE_ICON_TYPE)))
            val (_, at, list) = context.findClearModeRowAdd()
            assertEquals("answered $answered: the list is v1", 1, list)
            context.addMuteButtonToClearMode()
            val code = context.method(button, "render")
            assertEquals("answered $answered: one call more", stock.methods.single().code().size + 1, code.size)
            assertEquals("answered $answered: in front of the row", buttonRow, code[at + 1].type())
            assertEquals("answered $answered: the speaker's hook", MUTE_CLEAR_MODE, code[at].referenceText())
            assertEquals("answered $answered: by range", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
            assertEquals("answered $answered: with the list", listOf(1), code[at].registers())
            assertEquals("answered $answered: after the last add", "add", code[if (answered) at - 2 else at - 1].methodRef()?.name)
        }

        for ((what, classes) in listOf(
            "no speaker in the extension" to listOf(render()),
            "two lists" to listOf(render(lists = 2), ExtensionDex.classDef(MUTE_ICON_TYPE)),
            "the add's answer over the list" to listOf(render(answerOverList = true), ExtensionDex.classDef(MUTE_ICON_TYPE)),
            "a row that takes no list" to listOf(render(rowTakesList = false), ExtensionDex.classDef(MUTE_ICON_TYPE)),
            "a jump onto the row" to listOf(render(rowJumpedTo = true), ExtensionDex.classDef(MUTE_ICON_TYPE)),
        )) {
            val context = PatchContexts.of(classes)
            assertThrows("$what: refused", PatchException::class.java) { context.findClearModeRowAdd() }
            context.addMuteButtonToClearMode()
            assertEquals("$what: the render is as it was", classes.first().methods.single().code().map { it.opcode }, context.method(button, "render").map { it.opcode })
        }
    }

    /**
     * The handler's turn-on and turn-off each tell the extension after their action's call, where the
     * branch around a missing action still lands past the hook. The viewer's turn-on passes the reel
     * it read off the pager through the extension in front of its own test. The pager's listener and
     * the exit button's render each ask first thing. The reel holder's chrome setter passes the
     * alpha it was asked for through the extension right after reading the one it holds. The pager
     * listener's scroll state change hands over the pager right after reading it, with the state.
     * The viewer's setup hands over the handler right after giving it its turn-on action (#138).
     * The stubs call the handler's turn-on and the reel's getId.
     */
    @Test
    fun everyHookGoesInAndBothStubsAreFilled() {
        val context = PatchContexts.of(classes())

        context.keepClearMode()

        val setup = context.method(actions, "setup")
        assertEquals("the turn-on action isn't set just before", "$handler->onEnable:$function1", setup[2].referenceText())
        // One local, then the handler and the action.
        assertCall("the setup's hook", setup[3], CLEAR_MODE_READY, listOf(1))
        assertEquals(Opcode.RETURN_VOID, setup[4].opcode)

        val enable = context.method(handler, "enable")
        // One local, then this and the reason.
        assertCall("the turn-on's hook", enable[5], CLEAR_MODE_ENTERED, listOf(1, 2))
        assertEquals("the action's call doesn't come just before", "$function1->invoke($anyType)$anyType", enable[4].referenceText())
        assertEquals(Opcode.RETURN_VOID, enable[6].opcode)
        assertEquals("a handler with no action runs the hook", 6, enable.target(enable.indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        val dismiss = context.method(handler, "dismiss")
        // One local, then this, the reel and the reason.
        assertCall("the turn-off's hook", dismiss[6], CLEAR_MODE_LEFT, listOf(1, 2, 3))
        assertEquals("the action's call doesn't come just before", "$function2->invoke($anyType$anyType)$anyType", dismiss[5].referenceText())
        assertEquals(Opcode.RETURN_VOID, dismiss[7].opcode)
        assertEquals("a handler with no action runs the hook", 7, dismiss.target(dismiss.indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        val enabled = context.method(actions, "enabled")
        val asked = enabled.indexOfFirst { it.referenceText() == CLEAR_MODE_CURRENT }
        assertEquals("the reel isn't read off the pager just before", "$pager->current()$item", enabled[asked - 2].referenceText())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, enabled[asked - 1].opcode)
        assertCall("the reel handed over", enabled[asked], CLEAR_MODE_CURRENT, listOf(2))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, enabled[asked + 1].opcode)
        assertEquals("the answer goes where the reel was", 2, (enabled[asked + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CHECK_CAST, enabled[asked + 2].opcode)
        assertEquals("the answer is cast back to the reel", item, enabled[asked + 2].type())
        assertEquals(2, (enabled[asked + 2] as OneRegisterInstruction).registerA)
        assertEquals("Instagram's own test doesn't follow", Opcode.IF_EQZ, enabled[asked + 3].opcode)
        assertEquals(2, (enabled[asked + 3] as OneRegisterInstruction).registerA)
        assertEquals("no reel doesn't go straight to the return", enabled.size - 2, enabled.target(asked + 3))

        val paged = context.method(listener, "onPageSelected")
        assertCall("the page report's hook", paged[0], CLEAR_MODE_PAGED, emptyList())

        val render = context.method(button, "render")
        assertEquals(Opcode.MOVE_OBJECT_FROM16, render[0].opcode)
        // Two locals, then this and the scope.
        assertEquals(0, (render[0] as TwoRegisterInstruction).registerA)
        assertEquals("the button itself isn't what the reel is read from", 2, (render[0] as TwoRegisterInstruction).registerB)
        assertEquals("$button->reel:$item", render[1].referenceText())
        assertCall("the exit button's hook", render[2], CLEAR_MODE_BUILT, listOf(0))
        // Its buzz (#480): the extension is asked into v1, the local nothing reads past the buzz, right
        // before the call, and a yes jumps to what follows it.
        val quiet = render.indexOfFirst { it.referenceText() == CLEAR_MODE_QUIET }
        assertEquals("the haptics helper isn't read just before", "$haptics->get()$haptics", render[quiet - 2].referenceText())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, render[quiet - 1].opcode)
        assertCall("the buzz's hook", render[quiet], CLEAR_MODE_QUIET, emptyList())
        assertEquals(Opcode.MOVE_RESULT, render[quiet + 1].opcode)
        assertEquals("the answer isn't in the free local", 1, (render[quiet + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, render[quiet + 2].opcode)
        assertEquals(1, (render[quiet + 2] as OneRegisterInstruction).registerA)
        assertEquals("the buzz doesn't follow", "$haptics->tap()V", render[quiet + 3].referenceText())
        assertEquals("a yes doesn't skip the buzz alone", quiet + 4, render.target(quiet + 2))
        assertEquals("the render asks once", 1, render.count { it.referenceText() == CLEAR_MODE_QUIET })

        // The seek bar row: the reel state's flag, read off this through the row's state into v0,
        // the one local nothing reads past the test, goes to the extension with Instagram's answer,
        // and the answer it gives back is what the row tests. A missing state counts as no clear mode.
        val scrubberRow = context.method(row, "render")
        val asks = scrubberRow.indexOfFirst { it.referenceText() == CLEAR_MODE_SCRUBBER }
        assertEquals("Instagram's answer isn't unboxed just before", "$boxed->booleanValue()Z", scrubberRow[asks - 10].referenceText())
        assertEquals(Opcode.MOVE_RESULT, scrubberRow[asks - 9].opcode)
        assertEquals(Opcode.MOVE_OBJECT_FROM16, scrubberRow[asks - 8].opcode)
        assertEquals("the flag isn't read into the free local", 0, (scrubberRow[asks - 8] as TwoRegisterInstruction).registerA)
        // Five locals, then this and the scope.
        assertEquals("the row itself isn't what the flag is read from", 5, (scrubberRow[asks - 8] as TwoRegisterInstruction).registerB)
        assertEquals("$row->state:$rowState", scrubberRow[asks - 7].referenceText())
        assertEquals("no row state isn't caught", asks - 1, scrubberRow.target(asks - 6))
        assertEquals("$rowState->reel:$reelState", scrubberRow[asks - 5].referenceText())
        assertEquals("no reel state isn't caught", asks - 1, scrubberRow.target(asks - 4))
        assertEquals("the flag isn't the one the toString names", "$reelState->fullscreen:Z", scrubberRow[asks - 3].referenceText())
        assertEquals(Opcode.IGET_BOOLEAN, scrubberRow[asks - 3].opcode)
        for (at in listOf(asks - 7, asks - 5, asks - 3)) {
            val read = scrubberRow[at] as TwoRegisterInstruction
            assertEquals("the read ${at - asks} isn't from and into the free local", listOf(0, 0), listOf(read.registerA, read.registerB))
        }
        for (at in listOf(asks - 6, asks - 4)) {
            assertEquals("the null test ${at - asks} isn't of the free local", 0, (scrubberRow[at] as OneRegisterInstruction).registerA)
        }
        assertEquals("a read flag skips the no", asks, scrubberRow.target(asks - 2))
        assertEquals(Opcode.CONST_4, scrubberRow[asks - 1].opcode)
        assertEquals(0, (scrubberRow[asks - 1] as OneRegisterInstruction).registerA)
        assertCall("the seek bar's hook", scrubberRow[asks], CLEAR_MODE_SCRUBBER, listOf(1, 0))
        assertEquals(Opcode.MOVE_RESULT, scrubberRow[asks + 1].opcode)
        assertEquals("the answer goes where Instagram's was", 1, (scrubberRow[asks + 1] as OneRegisterInstruction).registerA)
        assertEquals("Instagram's own test doesn't follow", Opcode.IF_EQZ, scrubberRow[asks + 2].opcode)
        assertEquals(1, (scrubberRow[asks + 2] as OneRegisterInstruction).registerA)
        assertEquals("the scrubber isn't what a yes builds", scrubberChild, scrubberRow[asks + 3].type())
        assertEquals("the thumb's test was hooked", 1, scrubberRow.count { it.referenceText() == CLEAR_MODE_SCRUBBER })

        // The chrome setter: the holder, the alpha asked for and the one it holds, right after reading
        // that one, and the answer goes where the asked one was, ahead of Instagram's own compare and store.
        val chrome = context.method(holder, "updateChrome")
        val holds = chrome.indexOfFirst { it.referenceText() == CLEAR_MODE_CHROME_ALPHA }
        assertEquals("the held alpha isn't read just before", floatValue, chrome[holds - 2].referenceText())
        assertEquals(Opcode.MOVE_RESULT, chrome[holds - 1].opcode)
        // Three locals, then this and the alpha asked for.
        assertCall("the chrome setter's hook", chrome[holds], CLEAR_MODE_CHROME_ALPHA, listOf(3, 4, 1))
        assertEquals(Opcode.MOVE_RESULT, chrome[holds + 1].opcode)
        assertEquals("the answer goes where the asked alpha was", 4, (chrome[holds + 1] as OneRegisterInstruction).registerA)
        assertEquals("the lock isn't read next", "$holder->locked:$function0", chrome[holds + 2].referenceText())
        assertEquals("the chrome setter asks once", 1, chrome.count { it.referenceText() == CLEAR_MODE_CHROME_ALPHA })

        // The scroll state change: the pager it just read and the state, ahead of Instagram's own test of the pager.
        val scrolled = context.method(listener, "onScrollStateChanged")
        val settles = scrolled.indexOfFirst { it.referenceText() == CLEAR_MODE_SETTLED }
        assertEquals("the pager isn't read just before", "$pagerHost->pager:$VIEW_PAGER", scrolled[settles - 1].referenceText())
        // Two locals, then this and the state.
        assertCall("the scroll state's hook", scrolled[settles], CLEAR_MODE_SETTLED, listOf(1, 3))
        assertEquals("Instagram's own test of the pager doesn't follow", Opcode.IF_EQZ, scrolled[settles + 1].opcode)
        assertEquals(1, (scrolled[settles + 1] as OneRegisterInstruction).registerA)
        assertEquals("the scroll state change hands over once", 1, scrolled.count { it.referenceText() == CLEAR_MODE_SETTLED })

        val enter = context.method(CLEAR_MODE, CLEAR_MODE_ENTER_STUB)
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
            enter.take(4).map { it.opcode },
        )
        assertEquals(handler, enter[0].type())
        assertEquals(reason, enter[1].type())
        assertEquals("$handler->enable($reason)V", enter[2].referenceText())
        val reelId = context.method(CLEAR_MODE, CLEAR_MODE_ID_STUB)
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            reelId.take(4).map { it.opcode },
        )
        assertEquals(item, reelId[0].type())
        assertEquals("$item->getId()$stringType", reelId[1].referenceText())
        // The extension gives a holder its buttons back through the holder's own setter (#740).
        val setChrome = context.method(CLEAR_MODE, CLEAR_MODE_CHROME_STUB)
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
            setChrome.take(3).map { it.opcode },
        )
        assertEquals(holder, setChrome[0].type())
        assertEquals("$holder->updateChrome(F)V", setChrome[1].referenceText())
        assertEquals("the holder and the alpha go in", listOf(0, 1), setChrome[1].registers())
        // With a turn-on to hook, the action stays as Instagram set it.
        for (stub in listOf(CLEAR_MODE_ACTION_STUB, CLEAR_MODE_SET_ACTION_STUB)) {
            assertTrue("$stub was filled", context.method(CLEAR_MODE, stub).none { it.opcode == Opcode.CHECK_CAST })
        }
    }

    /**
     * 451's x86 385713117 folds the pager lookup into the read: one static call handed what the pager
     * comes from answers the reel. The reel is still handed over right before Instagram's own test.
     */
    @Test
    fun aReadFoldedIntoAStaticCallIsHookedToo() {
        val context = PatchContexts.of(classes(Shape(pagerFolded = true)))

        context.keepClearMode()

        val enabled = context.method(actions, "enabled")
        val asked = enabled.indexOfFirst { it.referenceText() == CLEAR_MODE_CURRENT }
        assertEquals("the reel isn't read just before", "$pagers->current($pager)$item", enabled[asked - 2].referenceText())
        assertCall("the reel handed over", enabled[asked], CLEAR_MODE_CURRENT, listOf(2))
        assertEquals(Opcode.CHECK_CAST, enabled[asked + 2].opcode)
        assertEquals("Instagram's own test doesn't follow", Opcode.IF_EQZ, enabled[asked + 3].opcode)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Shape(enables = 0) to "expected one method marked $CLEAR_MODE_ENABLE, found 0",
            Shape(enables = 2) to "expected one method marked $CLEAR_MODE_ENABLE, found 2",
            Shape(enablePublic = false) to "isn't a public instance method of a public class taking the reason",
            Shape(reasonInApp = false) to "the reason $reason isn't in the app",
            Shape(reasonIsEnum = false) to "the reason $reason isn't a public enum",
            Shape(reasonNamesSwipe = false) to "doesn't name $CLEAR_MODE_SWIPED_AWAY",
            Shape(enableActions = 0) to "runs 0 actions, expected one",
            Shape(enableActions = 2) to "runs 2 actions, expected one",
            Shape(enableHandsReason = false) to "doesn't hand its action what it was called with",
            Shape(enableEndsAtCall = true) to "ends at its action's call",
            Shape(enableKeepsResult = true) to "keeps what its action answers",
            Shape(reasonOverwritten = true) to "writes over parameter 0",
            Shape(dismissElsewhere = true) to "$CLEAR_MODE_DISMISS isn't in $handler",
            Shape(dismissTakesReason = false) to "isn't an instance (reel, reason) method",
            Shape(itemInApp = false) to "the reel $item isn't in the app",
            Shape(itemPublic = false) to "the reel $item isn't public",
            Shape(itemHasId = false) to "has 0 public getId() answering a string, expected one",
            Shape(handlerOverwritten = true) to "writes over this",
            Shape(enabledInDismiss = true) to "two hooks would go in one method",
            Shape(pagerReads = 0) to "reads the reel the pager is on 0 times, expected once",
            Shape(pagerReads = 2) to "reads the reel the pager is on 2 times, expected once",
            Shape(reelTested = false) to "doesn't test the reel for null right after reading it off the pager",
            Shape(jumpToTest = true) to "jumps to its test of the reel",
            Shape(noReelCalls = true) to "does more than return when the pager has no reel",
            Shape(noReelReturns = false) to "doesn't return when the pager has no reel",
            Shape(pageTakesInts = false) to "isn't an instance (int, int) method returning nothing",
            Shape(buttonTakesScope = false) to "isn't a render taking one scope and answering a component",
            Shape(buttonReels = 0) to "holds the reel in 0 fields, expected one",
            Shape(buttonReels = 2) to "holds the reel in 2 fields, expected one",
            Shape(buttonLocals = false) to "has no local register for the hook",
            Shape(rows = 0) to "expected one method marked $CLEAR_MODE_SCRUBBER_ROW, found 0",
            Shape(rowTakesScope = false) to "isn't a render taking one scope and answering a component",
            Shape(flagNames = 0) to "holds a reel state naming its clear mode flag through 0 fields, expected one",
            Shape(flagNames = 2) to "names the clear mode flag 2 times, expected once",
            Shape(flagFromCall = true) to "move-result, not a field it read",
            Shape(flagJump = true) to "jumps between reading the clear mode flag and appending it",
            Shape(flagPrivate = true) to "can't read $reelState->fullscreen:Z",
            Shape(rowReelStates = 2) to "holds a reel state naming its clear mode flag through 2 fields, expected one",
            Shape(reelStatePrivate = true) to "can't read $rowState->reel:$reelState",
            Shape(farState = true) to "can't read Lfixture/far/ReelState;->fullscreen:Z",
            Shape(flagOtherClass = true) to "appends Lfixture/OtherState;'s flag, not its own",
            Shape(nameThenOther = true) to "names something else before appending the clear mode flag",
            Shape(flagAppended = false) to "appends no yes or no after naming the clear mode flag",
            Shape(flagUnfilled = true) to "appends the clear mode flag from a register nothing filled",
            Shape(flagFieldMissing = true) to "$reelState has no boolean field fullscreen",
            Shape(childTakesState = false) to "builds nothing from the reel and its state",
            Shape(scrubberUnboxed = false) to "tests whether to draw the scrubber 0 times, expected once",
            Shape(scrubberTests = 2) to "tests whether to draw the scrubber 2 times, expected once",
            Shape(jumpToScrubber = true) to "jumps to its test of the scrubber",
            Shape(scrubberHigh = true) to "tests the scrubber in v16, which the hook's call can't name",
            Shape(rowThisOverwritten = true) to "writes over this",
            Shape(rowLocalsFree = false) to "has 0 local register(s) up to v15 that nothing reads",
            Shape(chromes = 0) to "expected one method marked $CLEAR_MODE_CHROME, found 0",
            Shape(chromes = 2) to "expected one method marked $CLEAR_MODE_CHROME, found 2",
            Shape(chromeTakesAlpha = false) to "isn't an instance method taking the alpha",
            Shape(chromeStatic = true) to "isn't an instance method taking the alpha",
            Shape(chromeReturns = "Z") to "isn't an instance method taking the alpha",
            Shape(chromeEndsAtRead = true) to "ends at reading the alpha it holds",
            Shape(alphaReads = 0) to "reads the alpha it holds 0 times, expected once",
            Shape(alphaReads = 2) to "reads the alpha it holds 2 times, expected once",
            Shape(alphaKept = false) to "doesn't keep the alpha it holds",
            Shape(jumpToHold = true) to "jumps past its read of the alpha it holds",
            Shape(alphaHigh = true) to "keeps the alphas in v18 and v1, which the hook's call can't name",
            Shape(askedOverwritten = true) to "ReelHolder;->updateChrome writes over parameter 0",
            Shape(holderOverwritten = true) to "ReelHolder;->updateChrome writes over this",
            Shape(chromePublic = false) to "isn't public, so the extension can't give a reel its buttons back",
            Shape(holderPublic = false) to "isn't public, so the extension can't give a reel its buttons back",
            Shape(alphaStored = false) to "doesn't store the alpha it was asked for",
            Shape(scrollStates = 0) to "expected one method marked $REEL_SCROLL_STATE, found 0",
            Shape(scrollStates = 2) to "expected one method marked $REEL_SCROLL_STATE, found 2",
            Shape(scrollTakesState = false) to "isn't an instance method taking the scroll state",
            Shape(stateInApp = false) to "the scroll state $scrollState isn't in the app",
            Shape(stateIsEnum = false) to "the scroll state $scrollState isn't an enum",
            Shape(stateNamesIdle = false) to "the scroll state $scrollState doesn't name $REEL_AT_REST",
            Shape(viewPagerReads = 0) to "reads the pager 0 times, expected once",
            Shape(viewPagerReads = 2) to "reads the pager 2 times, expected once",
            Shape(settleEndsAtRead = true) to "ends at reading the pager",
            Shape(jumpToSettle = true) to "jumps past its read of the pager",
            Shape(settleHigh = true) to "keeps the pager and the state in v1 and v17, which the hook's call can't name",
            Shape(stateOverwritten = true) to "PagerListener;->onScrollStateChanged writes over parameter 0",
            Shape(enableReadsPager = true) to "reads 2 fields of its own, expected its action alone",
            Shape(setups = 0) to "expected one method marked $CLEAR_MODE_SETUP, found 0",
            Shape(setups = 2) to "expected one method marked $CLEAR_MODE_SETUP, found 2",
            Shape(setupSets = 0) to "sets the handler's turn-on action 0 times, expected once",
            Shape(setupSets = 2) to "sets the handler's turn-on action 2 times, expected once",
            Shape(setupEndsAtSet = true) to "ends at setting the handler's turn-on action",
            Shape(jumpToReady = true) to "jumps past its setting of the turn-on action",
            Shape(stubs = false) to "has no static $stringType $CLEAR_MODE_ID_STUB(",
        )
        for ((shape, expected) in cases) {
            val classes = classes(shape)
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.keepClearMode() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("Clean up Reels: Keep clear mode on: "))
            val written = context.written(classes)
            assertTrue("$expected: something was written to $written", written.isEmpty())
            val stubs = context.classDefBy(CLEAR_MODE).methods.filter {
                it.name in setOf(
                    CLEAR_MODE_ENTER_STUB, CLEAR_MODE_ID_STUB, CLEAR_MODE_ACTION_STUB, CLEAR_MODE_SET_ACTION_STUB, CLEAR_MODE_CHROME_STUB,
                    CLEAR_MODE_LEAVE_STUB,
                )
            }
            assertTrue("$expected: a stub was filled", stubs.none { stub -> stub.code().any { it.opcode == Opcode.CHECK_CAST } })
        }
    }

    /**
     * An exit button whose buzz can't be told apart or hooked (#480) leaves Don't vibrate in clear
     * mode out and nothing else: every other hook goes in, and the render buzzes as Instagram has it.
     * A buzz three calls down isn't one.
     */
    @Test
    fun aBuzzThePatchCantPlaceLeavesOnlyItsHookOut() {
        for ((shape, what) in listOf(
            Shape(buzzes = 0) to "no buzz",
            Shape(buzzes = 2) to "two buzzes",
            Shape(jumpToBuzz = true) to "a jump onto the buzz",
            Shape(buzzBusy = true) to "no free local",
            Shape(buzzDeep = true) to "a buzz too deep",
        )) {
            val classes = classes(shape)
            val context = PatchContexts.of(classes)
            val stock = classes.single { it.type == button }.methods.single().implementation!!.instructions.toList()

            context.keepClearMode()

            val written = context.written(classes)
            for (method in listOf("enable", "dismiss", "enabled", "onPageSelected", "updateChrome", "onScrollStateChanged", "setup")) {
                assertTrue("$what: $method has no hook: $written", method in written)
            }
            val render = context.method(button, "render")
            assertCall("$what: the exit button's hook", render[2], CLEAR_MODE_BUILT, listOf(0))
            assertTrue("$what: the buzz was hooked", render.none { it.referenceText() == CLEAR_MODE_QUIET })
            assertEquals("$what: the render changed past its first hook", stock.map { it.opcode }, render.drop(3).map { it.opcode })
        }
    }

    /**
     * The handler's and the viewer's hooks go in by the index the find phase read. A method that
     * grew between the two would take its hook in the wrong place, so the patch stops instead.
     */
    @Test
    fun aMethodChangedAfterItsPlaceWasFoundStopsThePatch() {
        for (changed in listOf(
            handler to "enable", handler to "dismiss", actions to "enabled", row to "render", holder to "updateChrome",
            listener to "onScrollStateChanged", actions to "setup", button to "render",
        )) {
            val classes = classes()
            val context = PatchContexts.of(classes)
            val found = context.findClearMode()
            context.mutableClassDefBy(changed.first).methods.single { it.name == changed.second }.addInstructions(0, "nop")

            val failure = assertThrows(changed.second, PatchException::class.java) { context.applyClearMode(found) }

            assertTrue("${changed.second}: ${failure.message}", failure.message!!.contains("changed after its hook's place was found"))
            val written = context.written(classes)
            assertTrue("${changed.second}: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build every hook goes where the stand-ins put it: after the action's call in
     * the handler's turn-on and turn-off, in front of the viewer's own test of the pager's reel,
     * first thing in the pager listener's page report and the exit button's render, in front of
     * the seek bar row's test of whether to draw the scrubber, right after the reel holder's chrome
     * setter reads the alpha it holds, right after the pager listener's scroll state change reads
     * the pager, and right after the viewer's setup gives the handler its turn-on action. 456, which
     * has no turn-on method and draws the seek bar row inside the reel's render, gets the rest, and
     * its stubs run, read and write the handler's action.
     */
    @Test
    fun eachDeclaredBuildGetsEveryHook() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name, declared = true)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGetsEveryHookToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name, declared = false)
    }

    private fun builds(bundle: File, label: String, declared: Boolean) {
        val markers = CLEAR_MODE_MARKERS.toSet()
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holds = dex.stringSection.any { held ->
                held.startsWith("android_purge_") && PURGE_MARKER.find(held)?.groupValues?.get(1) in markers
            }
            if (!holds) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.markers().any { it in markers } }) holders += ImmutableClassDef.of(classDef)
            }
        }
        if (holders.isEmpty()) {
            // 456 drops all nine markers, and on every build the handler's turn-on is inlined into its
            // callers (386214574's LX/09HX;->A03, LX/0PHO;->A0E and LX/09Ip;->A0A read the handler's
            // action and run it themselves), so everything is found by the strings it keeps.
            assertTrue("$label: only 456 drops clear mode's markers", label.contains("-457.0.0.55.73-"))
            anchoredBuild(bundle, label, declared)
            return
        }
        val methods = holders.flatMap { it.methods }
        if (methods.count { CLEAR_MODE_ENABLE in it.markers() } != 1) {
            // On 455 only the armeabi-v7a build 386113922 inlines the turn-on into its callers (LX/08KX;->FtW,
            // LX/08Lq;->A0B and LX/0JfZ;->invoke) and keeps the markers: Clean up Reels goes in without Keep clear mode on there.
            assertTrue("$label: only 386113922 inlines the turn-on and keeps the markers", label.endsWith("-386113922"))
            val context = PatchContexts.of((holders + ExtensionDex.classDef(CLEAR_MODE)).distinctBy { it.type })
            assertNull("$label: Keep clear mode on is left out", context.findClearModeWhereItFits())
            return
        }
        val enable = methods.single { CLEAR_MODE_ENABLE in it.markers() }
        val dismiss = methods.single { CLEAR_MODE_DISMISS in it.markers() }
        val enabled = methods.single { CLEAR_MODE_ENABLED in it.markers() }
        val paged = methods.single { REEL_PAGE_SELECTED in it.markers() }
        val render = methods.single { CLEAR_MODE_BUTTON in it.markers() }
        val scrubberRow = methods.single { CLEAR_MODE_SCRUBBER_ROW in it.markers() }
        val chrome = methods.single { CLEAR_MODE_CHROME in it.markers() }
        val scrolled = methods.single { REEL_SCROLL_STATE in it.markers() }
        val setup = methods.single { CLEAR_MODE_SETUP in it.markers() }
        val handlerType = enable.definingClass
        val reasonType = enable.parameterTypes.single().toString()
        val itemType = dismiss.parameterTypes.first().toString()
        val stateType = scrolled.parameterTypes.single().toString()
        // The row's state and what it holds, what the row builds, and the reel state whose toString names the flag.
        val rowFields = holders.single { it.type == scrubberRow.definingClass }.instanceFields.map { it.type }.filter { it.startsWith("L") }.toSet()
        val heldFields = FixtureDex.classes(bundle, rowFields).values.flatMap { held -> held.instanceFields.map { it.type } }.filter { it.startsWith("L") }
        val built = scrubberRow.code().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { it.type() }
        // What the exit button's render calls, among them the haptics helper it buzzes through (#480).
        val called = render.code().mapNotNull { it.methodRef()?.definingClass }.filter { it.startsWith("LX/") }
        val extra = FixtureDex.classes(bundle, setOf(reasonType, itemType, stateType) + rowFields + heldFields + built + called).values +
            FixtureDex.classesHolding(bundle, CLEAR_MODE_FLAG_NAME)
        val context = PatchContexts.of((holders + extra + ExtensionDex.classDef(CLEAR_MODE) + ExtensionDex.classDef(MUTE_ICON_TYPE)).distinctBy { it.type })

        context.keepClearMode()

        val name = label
        val after = { method: Method -> context.now(method) }

        // The turn-on: this and the reason, right after the action's call, and the branch around a
        // missing action lands past the hook.
        val newEnable = after(enable)
        assertEquals("$name: the turn-on grew", enable.code().size + 1, newEnable.size)
        val entered = newEnable.indexOfFirst { it.referenceText() == CLEAR_MODE_ENTERED }
        val enableThis = enable.implementation!!.registerCount - 2
        assertEquals("$name: the turn-on hands over this and the reason", listOf(enableThis, enableThis + 1), newEnable[entered].registers())
        assertEquals("$name: the turn-on's action comes just before", "invoke", newEnable[entered - 1].methodRef()?.name)
        assertEquals("$name: the reason is the one the action got", newEnable[entered - 1].registers().last(), enableThis + 1)
        assertEquals("$name: a missing action skips the turn-on's hook", entered + 1, newEnable.target(newEnable.indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        val enableAction = enable.code().filter { it.opcode == Opcode.IGET_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }.single { it.definingClass == handlerType }
        sharedHooks(context, name, declared, Sites(dismiss, enabled, paged, render, chrome, scrolled, setup), handlerType, itemType, enableAction.name)

        // The seek bar row: the reel state's flag, read off this through the row's state, goes in with
        // Instagram's unboxed answer right before the row tests it, and the row tests what comes back.
        val newRow = after(scrubberRow)
        assertEquals("$name: the seek bar row grew", scrubberRow.code().size + 10, newRow.size)
        val asks = newRow.indexOfFirst { it.referenceText() == CLEAR_MODE_SCRUBBER }
        assertEquals("$name: the seek bar row asks once", 1, newRow.count { it.referenceText() == CLEAR_MODE_SCRUBBER })
        assertEquals("$name: Instagram's answer is unboxed just before", "Ljava/lang/Boolean;->booleanValue()Z", newRow[asks - 10].referenceText())
        val shown = (newRow[asks - 9] as OneRegisterInstruction).registerA
        val flag = (newRow[asks - 8] as TwoRegisterInstruction).registerA
        assertEquals("$name: the row itself is what the flag is read from", scrubberRow.implementation!!.registerCount - 2, (newRow[asks - 8] as TwoRegisterInstruction).registerB)
        val rowStateField = (newRow[asks - 7] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the row's state is the row's own", scrubberRow.definingClass, rowStateField.definingClass)
        val reelStateField = (newRow[asks - 5] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the reel's state is the row state's", rowStateField.type, reelStateField.definingClass)
        val flagField = (newRow[asks - 3] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the flag is the reel state's", reelStateField.type, flagField.definingClass)
        assertEquals("$name: the flag is a yes or no", "Z", flagField.type)
        assertTrue("$name: the flag goes in the answer's register", flag != shown)
        assertEquals("$name: the flag's read", Opcode.IGET_BOOLEAN, newRow[asks - 3].opcode)
        for (at in listOf(asks - 7, asks - 5, asks - 3)) {
            val read = newRow[at] as TwoRegisterInstruction
            assertEquals("$name: the read ${at - asks} isn't from and into v$flag", listOf(flag, flag), listOf(read.registerA, read.registerB))
        }
        for (at in listOf(asks - 6, asks - 4)) {
            assertEquals("$name: the null test ${at - asks} isn't of v$flag", flag, (newRow[at] as OneRegisterInstruction).registerA)
            assertEquals("$name: a missing state at ${at - asks} isn't a no", asks - 1, newRow.target(at))
        }
        assertEquals("$name: a read flag skips the no", asks, newRow.target(asks - 2))
        assertEquals("$name: the no goes in v$flag", flag, (newRow[asks - 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: the hook hands over the answer and the flag", listOf(shown, flag), newRow[asks].registers())
        assertEquals("$name: the answer goes back where it was", shown, (newRow[asks + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: Instagram's own test follows", Opcode.IF_EQZ, newRow[asks + 2].opcode)
        assertEquals("$name: the test is of the answer", shown, (newRow[asks + 2] as OneRegisterInstruction).registerA)

        val enter = context.method(CLEAR_MODE, CLEAR_MODE_ENTER_STUB)
        assertEquals("$name: the stub calls the handler's turn-on", "$handlerType->${enable.name}($reasonType)V", enter[2].referenceText())
        val reelId = context.method(CLEAR_MODE, CLEAR_MODE_ID_STUB)
        assertEquals("$name: the stub reads the reel's id", "$itemType->getId()$stringType", reelId[1].referenceText())
        for (stub in listOf(CLEAR_MODE_ACTION_STUB, CLEAR_MODE_SET_ACTION_STUB)) {
            assertTrue("$name: $stub was filled", context.method(CLEAR_MODE, stub).none { it.opcode == Opcode.CHECK_CAST })
        }
    }

    /**
     * 456 by the strings it keeps: the reason naming the swipe away, the reel state naming its clear
     * mode flag, and the pager listener's two traces. The slice carries those, every class reading
     * the reason's constants or taking the reason, every class holding the reel state, every class
     * touching the handler's fields, and the types the hooks' methods tell apart or call.
     */
    private fun anchoredBuild(bundle: File, label: String, declared: Boolean) {
        val name = label
        val loading = FixtureDex.classesLoadingAny(
            bundle, setOf(CLEAR_MODE_SWIPED_AWAY, CLEAR_MODE_FLAG_NAME, REEL_PAGE_SELECTED_TRACE, REEL_SCROLL_STATE_TRACE),
        )
        val byType = loading.associateBy { it.type }
        val reasonType = loading.single { reason ->
            reason.superclass == "Ljava/lang/Enum;" && reason.methods.any { it.name == "<clinit>" && FixtureDex.loads(it, CLEAR_MODE_SWIPED_AWAY, byType) }
        }.type
        val stateType = loading.single { state ->
            state.methods.any { it.name == "toString" && FixtureDex.loads(it, CLEAR_MODE_FLAG_NAME, byType) }
        }.type
        val near = walk(bundle, setOf(reasonType, stateType)) { classDef ->
            classDef.fields.any { it.type == stateType } || classDef.methods.any { method ->
                method.parameterTypes.any { it.toString() == reasonType } ||
                    method.code().any { (it as? ReferenceInstruction)?.reference.let { ref -> ref is FieldReference && ref.definingClass == reasonType } }
            }
        }
        // Each class with an action and a turn-off taking the reason may be the handler. 457's armeabi-v7a
        // (386314340) inlines the turn-off into its callers, so there it's the class with an action whose
        // other action, the turn-off's, a method touching the reason reads off it and runs with two objects.
        val turnOffActions = near.flatMap { it.methods }.flatMap { method ->
            val code = method.code()
            code.indices.mapNotNull { at ->
                val call = code[at].methodRef()
                val read = code.getOrNull(at - 2)
                if (code[at].opcode != Opcode.INVOKE_INTERFACE || call?.name != "invoke" || call.parameterTypes.size != 2 ||
                    read?.opcode != Opcode.IGET_OBJECT
                ) {
                    return@mapNotNull null
                }
                ((read as ReferenceInstruction).reference as FieldReference).takeIf { it.type == call.definingClass }
            }
        }
        // That handler takes no reason and reads none of its constants, so it isn't near them.
        val nearTypes = near.mapTo(HashSet()) { it.type }
        val inlinedHandlers = FixtureDex.classes(bundle, turnOffActions.map { it.definingClass }.filterTo(HashSet()) { it !in nearTypes }).values
        val handlers = (near + inlinedHandlers).filter { holder ->
            holder.instanceFields.any { it.type == function1 } && (
                holder.methods.any { method ->
                    method.returnType == "V" && method.parameterTypes.size == 2 && method.parameterTypes[1].toString() == reasonType
                } || turnOffActions.any { it.definingClass == holder.type }
            )
        }
        val handlerTypes = handlers.map { it.type }.toSet()
        assertTrue("$name: no class may be the handler", handlerTypes.isNotEmpty())
        val items = handlers.flatMap { it.methods }.filter { it.parameterTypes.size == 2 && it.parameterTypes[1].toString() == reasonType }
            .map { it.parameterTypes[0].toString() }
        // Where the turn-off is inlined, its action's type and every type the exit button holds, the reel among them.
        val inlinedTypes = handlers.flatMap { it.instanceFields }.map { it.type }.filter { it != function1 } +
            near.filter { holder -> holder.instanceFields.any { it.type == stateType } && holder.instanceFields.any { it.type in handlerTypes } }
                .flatMap { it.instanceFields }.map { it.type }
        val takingReason = near.flatMap { it.methods }.filter { method -> method.parameterTypes.any { it.toString() == reasonType } }
        val tagged = takingReason.flatMap { it.code() }.filter { it.opcode == Opcode.INSTANCE_OF }.mapNotNull { it.type() }
        val scrollStates = loading.flatMap { it.methods }.filter { FixtureDex.loads(it, REEL_SCROLL_STATE_TRACE, byType) }
            .flatMap { it.parameterTypes.map(Any::toString) }
        val buttons = near.filter { holder -> holder.instanceFields.any { it.type == stateType } && holder.instanceFields.any { it.type in handlerTypes } }
        val called = buttons.flatMap { it.methods }.flatMap { it.code() }.mapNotNull { it.methodRef()?.definingClass }.filter { it.startsWith("LX/") }
        val touching = walk(bundle, handlerTypes) { classDef ->
            classDef.methods.any { method ->
                method.code().any { (it as? ReferenceInstruction)?.reference.let { ref -> ref is FieldReference && ref.definingClass in handlerTypes } }
            }
        }
        val known = (loading + near + touching).mapTo(HashSet()) { it.type }
        val types = (items + inlinedTypes + handlerTypes + tagged + scrollStates + called).filter { it.startsWith("L") && it !in known }.toSet()
        // Pinch for clear mode's read of its flag sits in the action the pinch listener runs, which reads the reel state too.
        val pinchReaders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == stateType } }) { method ->
            method.code().any { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == PINCH_CLEAR_MODE_FLAG }
        }.mapTo(HashSet()) { it.definingClass }
        assertTrue("$name: nothing reads the pinch flag", pinchReaders.isNotEmpty())
        // And it writes the reel's clear mode state through an interface or the store's static write,
        // which the patch checks are public, along with everything its stub casts to.
        val writeCalls = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
        val pinchWrites = FixtureDex.classes(bundle, pinchReaders).values.flatMap { it.methods }.flatMap { it.code() }
            .filter { it.opcode in writeCalls }
            .mapNotNull { it.methodRef() }
            .filter { call -> call.returnType == "V" && call.parameterTypes.map(Any::toString).lastOrNull() == "Z" }
            .flatMap { call -> listOf(call.definingClass) + call.parameterTypes.map(Any::toString) }
            .filterTo(HashSet()) { it.startsWith("LX/") }
        val sliced = (loading + near + touching + FixtureDex.classes(bundle, types + pinchReaders + pinchWrites).values).distinctBy { it.type }
        val classes = FixtureDex.withStringPools(bundle, sliced).distinctBy { it.type } +
            ExtensionDex.classDef(CLEAR_MODE) + ExtensionDex.classDef(MUTE_ICON_TYPE) + ExtensionDex.classDef(REEL_PINCH_CLEAR_MODE)
        val context = PatchContexts.of(classes.distinctBy { it.type })

        val found = context.findClearMode()
        // Clean up Reels finds the pinch's places before Keep clear mode on changes anything.
        val pinch = context.findPinchClearMode(found)
        assertNull("$name: there's a turn-on to hook", found.enable)
        assertNull("$name: there's a seek bar row to hook", found.row)
        assertEquals("$name: the reason is the one naming the swipe away", reasonType, found.reason)
        val handlerType = found.handler
        val itemType = found.item
        val action = found.action
        assertEquals("$name: the action is the handler's own", handlerType, action.substringBefore("->"))
        assertEquals("$name: the action is a Function1", function1, action.substringAfter(":"))
        val actionName = action.substringAfter("->").substringBefore(":")
        // The handler's callers run the action themselves, with one of the reason's constants.
        val runs = classes.flatMap { it.methods }.filter { method ->
            val code = method.code()
            code.any { it.opcode == Opcode.IGET_OBJECT && it.referenceText() == action } &&
                code.any { it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.let { ref -> ref is FieldReference && ref.definingClass == reasonType } } &&
                code.any { it.methodRef()?.let { call -> call.definingClass == function1 && call.name == "invoke" } == true }
        }
        assertTrue("$name: nothing runs the handler's action with a reason", runs.isNotEmpty())
        assertTrue("$name: the paged report doesn't load its trace", FixtureDex.loads(found.paged, REEL_PAGE_SELECTED_TRACE, byType))
        assertTrue("$name: the scroll state change doesn't load its trace", FixtureDex.loads(found.scrolled, REEL_SCROLL_STATE_TRACE, byType))
        assertTrue("$name: the viewer's turn-on doesn't take the reason", reasonType in found.enabled.parameterTypes.map(Any::toString))
        assertEquals("$name: the exit button isn't one of the classes holding the reel state", true, found.button.definingClass in buttons.map { it.type })

        context.keepClearMode()

        sharedHooks(
            context, name, declared,
            Sites(found.dismiss, found.enabled, found.paged, found.button, found.chrome, found.scrolled, found.setup, found.inlined),
            handlerType, itemType, actionName,
        )

        // Neither the turn-on's hook nor the seek bar row's goes anywhere.
        for (hook in listOf(CLEAR_MODE_ENTERED, CLEAR_MODE_SCRUBBER)) {
            val calling = classes.map { it.type }.distinct().flatMap { type -> context.classDefBy(type).methods.toList() }
                .filter { method -> method.code().any { it.referenceText() == hook } }
            assertTrue("$name: $hook went into ${calling.map { it.name }}", calling.isEmpty())
        }

        // The stubs run the action the handler's callers run, and read and write it for the extension.
        val enter = context.method(CLEAR_MODE, CLEAR_MODE_ENTER_STUB)
        assertEquals(
            "$name: the turn-on stub",
            listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE, Opcode.RETURN_VOID),
            enter.take(5).map { it.opcode },
        )
        assertEquals("$name: the turn-on stub casts to the handler", handlerType, enter[0].type())
        assertEquals("$name: the turn-on stub reads the action", action, enter[1].referenceText())
        assertEquals("$name: the turn-on stub runs it", "$function1->invoke($anyType)$anyType", enter[3].referenceText())
        assertEquals("$name: the turn-on stub runs it with the reason", listOf(0, 1), enter[3].registers())
        assertEquals("$name: no action skips the run", 4, enter.target(2))
        val read = context.method(CLEAR_MODE, CLEAR_MODE_ACTION_STUB)
        assertEquals("$name: the action stub", listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), read.take(3).map { it.opcode })
        assertEquals("$name: the action stub reads the action", action, read[1].referenceText())
        val write = context.method(CLEAR_MODE, CLEAR_MODE_SET_ACTION_STUB)
        assertEquals(
            "$name: the set action stub",
            listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID),
            write.take(4).map { it.opcode },
        )
        assertEquals("$name: the set action stub casts to the handler", handlerType, write[0].type())
        assertEquals("$name: the set action stub casts the action", function1, write[1].type())
        assertEquals("$name: the set action stub writes the action", action, write[2].referenceText())
        val reelId = context.method(CLEAR_MODE, CLEAR_MODE_ID_STUB)
        assertEquals("$name: the stub reads the reel's id", "$itemType->getId()$stringType", reelId[1].referenceText())

        pinchHooks(context, name, pinch, found.button, stateType, itemType)
    }

    /**
     * Pinch for clear mode (#744) on 456: the one read of the pinch flag sits in a method asking
     * whether the reel is in clear mode and is answered through the hook right after its
     * move-result, ahead of the branch it decides. The two writes of the reel's state after that ask
     * become calls to [PINCH_CLEAR_MODE_TURN] with the same registers, and the state stub makes the
     * write. In the exit button's render, which Keep clear mode on hooked first, the icon's drawable
     * goes through [CLEAR_MODE_EXIT_ICON] right before the icon's call, and the circle and the tap
     * each go through [CLEAR_MODE_EXIT_PART] right after their move-results, with the modifier they
     * were handed, cast back. Nothing else in the render moves.
     */
    private fun pinchHooks(
        context: BytecodePatchContext,
        name: String,
        found: PinchClearModeHooks,
        button: Method,
        stateType: String,
        itemType: String,
    ) {
        val load = found.pinch
        assertEquals("$name: the pinch flag is a yes or no", "Z", load.answer)
        assertTrue("$name: the pinch flag's read is its own", !load.shared)
        assertEquals("$name: the exit button is Keep clear mode on's", "$button", "${found.button}")
        val first = found.parts ?: throw AssertionError("$name: the exit button's parts weren't found")
        val stock = context.mutableClassDefBy(load.type).methods.single { it.sameAs(load) }.code()
        assertTrue("$name: the reader asks the reel state", stock.any { (it as? ReferenceInstruction)?.reference.let { ref -> ref is FieldReference && ref.definingClass == stateType } })
        val write = found.write
        val takes = write.parameterTypes.map(Any::toString)
        val static = found.static
        if (static) {
            // Where Redex inlined the interface's method: the store's static write, handed the reel, the state's key, the store and a boxed yes or no.
            assertEquals("$name: the pinch's static write takes the reel, a key, a store, a value and a yes or no", 5, takes.size)
            assertEquals("$name: the reel first", itemType, takes[0])
            assertEquals("$name: then an object and a yes or no", listOf(anyType, "Z"), takes.drop(3))
            assertEquals("$name: the hook for a static write", PINCH_CLEAR_MODE_WRITE, found.hook)
        } else {
            assertEquals("$name: the pinch's write takes the reel and a yes or no", listOf(itemType, "Z"), takes)
            assertEquals("$name: the hook for an interface's write", PINCH_CLEAR_MODE_TURN, found.hook)
        }
        assertEquals("$name: two writes, on and off", 2, found.writes.size)
        assertTrue("$name: both after the flag's read", found.writes.all { it > load.resultAt + 1 })
        val calls = if (static) setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) else setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
        assertTrue("$name: both the same kind of call", found.writes.all { stock[it].opcode in calls })
        assertTrue("$name: the write's class is public", AccessFlags.PUBLIC.isSet(context.classDefBy(write.definingClass).accessFlags))
        val casts = if (static) takes.take(3) else listOf(write.definingClass, itemType)
        for (type in casts) assertTrue("$name: $type is public", AccessFlags.PUBLIC.isSet(context.classDefBy(type).accessFlags))
        if (static) {
            val writer = context.classDefBy(write.definingClass).methods.single { it.name == write.name && it.parameterTypes.map(Any::toString) == takes }
            assertTrue("$name: the static write is public", AccessFlags.PUBLIC.isSet(writer.accessFlags) && AccessFlags.STATIC.isSet(writer.accessFlags))
        }
        val renderMethod = {
            context.mutableClassDefBy(button.definingClass).methods.single {
                it.name == button.name && it.returnType == button.returnType &&
                    it.parameterTypes.map(Any::toString) == button.parameterTypes.map(Any::toString)
            }
        }
        val render = { renderMethod().code() }
        val before = render()
        // Keep clear mode on went into this render first and moved its places, so they're read again
        // from the hooked render, the way the patch reads them.
        val parts = renderMethod().exitButtonParts()
        assertTrue("$name: the exit button kept its registers through Keep clear mode on", parts.sameShape(first))
        assertEquals("$name: the icon's call", EXIT_BUTTON_DRAWABLE, before[parts.icon].methodRef()?.parameterTypes?.first()?.toString())
        assertEquals("$name: the tap's answer is what the icon gets", parts.tapAnswer, before[parts.icon].registers()[1])
        assertEquals("$name: the tap's move-result", parts.tapAnswer, (before[parts.tap + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: the circle's move-result", parts.circleAnswer, (before[parts.circle + 1] as OneRegisterInstruction).registerA)

        context.pinchClearMode(found)

        val read = context.mutableClassDefBy(load.type).methods.single { it.sameAs(load) }.code()
        assertEquals("$name: two instructions go in after the read", stock.size + 2, read.size)
        assertCall("$name: the pinch hook", read[load.resultAt + 1], PINCH_CLEAR_MODE, listOf(load.register))
        assertEquals("$name: the pinch hook's answer", Opcode.MOVE_RESULT, read[load.resultAt + 2].opcode)
        assertEquals("$name: into the read's register", load.register, (read[load.resultAt + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: then the branch it decides", stock[load.resultAt + 1].opcode, read[load.resultAt + 3].opcode)
        assertTrue("$name: Instagram's read stays", read[load.callAt].methodRef()?.returnType == "Z")
        for (at in found.writes) {
            // The flag's hook went in above the writes, two instructions.
            assertCall("$name: the write's hook", read[at + 2], found.hook, stock[at].registers())
        }
        assertEquals(
            "$name: nothing else in the reader changes",
            stock.filterIndexed { at, _ -> at !in found.writes }.map { it.opcode },
            read.filterIndexed { at, _ -> at !in (load.resultAt + 1..load.resultAt + 2) && at - 2 !in found.writes }.map { it.opcode },
        )
        val state = context.method(REEL_PINCH_CLEAR_MODE, if (static) PINCH_CLEAR_MODE_PUT_STUB else PINCH_CLEAR_MODE_STATE_STUB)
        val original = if (static) Opcode.INVOKE_STATIC else Opcode.INVOKE_INTERFACE
        assertEquals(
            "$name: the write stub",
            List(casts.size) { Opcode.CHECK_CAST } + listOf(original, Opcode.RETURN_VOID),
            state.take(casts.size + 2).map { it.opcode },
        )
        assertEquals("$name: the write stub casts", casts, state.take(casts.size).map { it.type() })
        assertEquals("$name: the write stub makes the pinch's write", "$write", state[casts.size].referenceText())
        assertEquals("$name: with everything the write takes", takes.indices.map { it } + if (static) emptyList() else listOf(takes.size), state[casts.size].registers())
        // The other shape's stub stays empty.
        val other = context.method(REEL_PINCH_CLEAR_MODE, if (static) PINCH_CLEAR_MODE_STATE_STUB else PINCH_CLEAR_MODE_PUT_STUB)
        assertEquals("$name: the other write stub only returns", listOf(Opcode.RETURN_VOID), other.map { it.opcode })

        val after = render()
        assertEquals("$name: eight instructions go into the render", before.size + 8, after.size)
        val icons = after.indices.filter { after[it].referenceText() == CLEAR_MODE_EXIT_ICON }
        assertEquals("$name: one icon hook", 1, icons.size)
        val icon = icons.single()
        assertEquals("$name: the icon hook takes the icon's drawable", listOf(parts.iconDrawable), after[icon].registers())
        assertEquals("$name: and answers into it", parts.iconDrawable, (after[icon + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: right before the icon's call", "${before[parts.icon].methodRef()}", "${after[icon + 2].methodRef()}")
        val kept = after.indices.filter { after[it].referenceText() == CLEAR_MODE_EXIT_PART }
        assertEquals("$name: the circle and the tap", 2, kept.size)
        val (circle, tap) = kept
        assertEquals("$name: the circle's hook right after its move-result", "${before[parts.circle].methodRef()}", "${after[circle - 2].methodRef()}")
        assertCall("$name: the circle's hook", after[circle], CLEAR_MODE_EXIT_PART, listOf(parts.circleBefore, parts.circleAnswer))
        assertEquals("$name: the tap's hook right after its move-result", "${before[parts.tap].methodRef()}", "${after[tap - 2].methodRef()}")
        assertCall("$name: the tap's hook", after[tap], CLEAR_MODE_EXIT_PART, listOf(parts.tapBefore, parts.tapAnswer))
        assertEquals("$name: the tap's hook goes before the icon's", icon - 3, tap)
        for ((at, answer) in listOf(circle to parts.circleAnswer, tap to parts.tapAnswer)) {
            assertEquals("$name: the part's answer", Opcode.MOVE_RESULT_OBJECT, after[at + 1].opcode)
            assertEquals("$name: into the part's register", answer, (after[at + 1] as OneRegisterInstruction).registerA)
            assertEquals("$name: cast back", Opcode.CHECK_CAST, after[at + 2].opcode)
            assertEquals("$name: to the modifier", parts.modifier, after[at + 2].type())
        }
        val unhooked = after.filterIndexed { at, _ -> at !in (icon..icon + 1) && at !in (circle..circle + 2) && at !in (tap..tap + 2) }
        assertEquals("$name: nothing else in the render changes", before.map { it.opcode }, unhooked.map { it.opcode })
    }

    private fun Method.sameAs(load: FlagLoad) =
        name == load.name && returnType == load.returnType && parameterTypes.map(CharSequence::toString) == load.parameters

    /** Hide the exit button in clear mode (#744): every hook it and Pinch for clear mode write is in the extension, public and static. */
    @Test
    fun thePinchHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_PINCH_CLEAR_MODE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val stubs = listOf(
            "$REEL_PINCH_CLEAR_MODE->$PINCH_CLEAR_MODE_STATE_STUB($anyType${anyType}Z)V",
            "$REEL_PINCH_CLEAR_MODE->$PINCH_CLEAR_MODE_PUT_STUB($anyType$anyType$anyType${anyType}Z)V",
        )
        for (hook in listOf(PINCH_CLEAR_MODE, PINCH_CLEAR_MODE_TURN, PINCH_CLEAR_MODE_WRITE, CLEAR_MODE_EXIT_ICON, CLEAR_MODE_EXIT_PART) + stubs) {
            assertTrue("$hook is not in the extension: $declared", hook in declared)
        }
    }

    /** A render shaped like 456's exit button, with [tapAnswer] and the instructions in [between] the tap and the icon. */
    private fun exitRender(tapAnswer: Int = 4, between: String = "", icons: Int = 1, jumpToIcon: Boolean = false): Method {
        val shapes = "Lfixture/Shapes;"
        val modifier = "Lfixture/Modifier;"
        val drawable = EXIT_BUTTON_DRAWABLE
        return method(button, "render", listOf(modifier), "Lfixture/Node;", 9, """
            const/4 v0, 0x0
            const/4 v1, 0x0
            const/4 v2, 0x0
            ${if (jumpToIcon) "if-eqz v0, :icon" else "nop"}
            invoke-static { v2, v8 }, $shapes->circle($drawable$modifier)$modifier
            move-result-object v3
            invoke-static { v3, v0 }, $shapes->tap(${modifier}Lkotlin/jvm/functions/Function1;)$modifier
            move-result-object v$tapAnswer
            $between
            :icon
            ${"invoke-static { v1, v$tapAnswer }, $shapes->icon($drawable$modifier)Lfixture/Node;\n".repeat(icons)}
            move-result-object v5
            return-object v5
        """)
    }

    /** The exit button's parts are read where 456 has them, and a render shaped any other way is refused. */
    @Test
    fun theExitButtonsPartsAreFoundOnlyWhereTheyFit() {
        val parts = exitRender().exitButtonParts()
        assertEquals("the icon", 8, parts.icon)
        assertEquals("its drawable", 1, parts.iconDrawable)
        assertEquals("the circle", 4, parts.circle)
        assertEquals("its answer", 3, parts.circleAnswer)
        assertEquals("the modifier handed to it", 8, parts.circleBefore)
        assertEquals("the tap", 6, parts.tap)
        assertEquals("its answer", 4, parts.tapAnswer)
        assertEquals("the modifier handed to it", 3, parts.tapBefore)
        assertEquals("Lfixture/Modifier;", parts.modifier)
        for ((render, why) in listOf(
            exitRender(tapAnswer = 3) to "writes a modifier over the one it was handed",
            exitRender(between = "move-object v4, v3") to "doesn't get its modifier straight from the call adding its tap",
            exitRender(icons = 2) to "expected one call building the exit button's icon, found 2",
            exitRender(jumpToIcon = true) to "jumps into a place a hook goes",
        )) {
            val refused = assertThrows(PatchException::class.java) { render.exitButtonParts() }
            assertTrue("${refused.message} isn't why: $why", refused.message!!.contains(why))
        }
    }

    /** The methods both ways of finding clear mode hook the same way, and the turn-off's runs where it's inlined. */
    private class Sites(
        val dismiss: Method?,
        val enabled: Method,
        val paged: Method,
        val render: Method,
        val chrome: Method,
        val scrolled: Method,
        val setup: Method,
        val inlined: InlinedTurnOff? = null,
    )

    /**
     * The hooks every build gets, by markers or by what 456 keeps, where each goes: after the action's
     * call in the handler's turn-off, in front of the viewer's own test of the pager's reel, first
     * thing in the page report and the exit button's render, with the speaker in clear mode in the
     * render's row, right after the chrome setter reads the alpha it holds, right after the scroll
     * state change reads the pager, and right after the setup gives the handler its [action].
     */
    private fun sharedHooks(
        context: BytecodePatchContext,
        name: String,
        declared: Boolean,
        sites: Sites,
        handlerType: String,
        itemType: String,
        action: String,
    ) {
        val after = { method: Method -> context.now(method) }
        val dismiss = sites.dismiss
        val enabled = sites.enabled
        val paged = sites.paged
        val render = sites.render
        val chrome = sites.chrome
        val scrolled = sites.scrolled
        val setup = sites.setup

        // The turn-off: this, the reel and the reason, right after the action's call, and the branch
        // around a missing action lands past the hook.
        if (dismiss == null) {
            inlinedTurnOff(context, name, sites.inlined, handlerType)
        } else {
            val newDismiss = after(dismiss)
            assertEquals("$name: the turn-off grew", dismiss.code().size + 1, newDismiss.size)
            val left = newDismiss.indexOfFirst { it.referenceText() == CLEAR_MODE_LEFT }
            val dismissThis = dismiss.implementation!!.registerCount - 3
            assertEquals(
                "$name: the turn-off hands over this, the reel and the reason",
                listOf(dismissThis, dismissThis + 1, dismissThis + 2),
                newDismiss[left].registers(),
            )
            assertEquals("$name: the turn-off's action comes just before", "invoke", newDismiss[left - 1].methodRef()?.name)
            assertEquals("$name: a missing action skips the turn-off's hook", left + 1, newDismiss.target(newDismiss.indexOfFirst { it.opcode == Opcode.IF_EQZ }))
            // The extension takes a reel out of clear mode for a pinch through that same turn-off (#744).
            val reasonType = dismiss.parameterTypes[1].toString()
            val leave = context.method(CLEAR_MODE, CLEAR_MODE_LEAVE_STUB)
            assertEquals(
                "$name: the leave stub",
                listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
                leave.take(5).map { it.opcode },
            )
            assertEquals("$name: the leave stub casts", listOf(handlerType, itemType, reasonType), leave.take(3).map { it.type() })
            assertEquals("$name: the leave stub runs the turn-off", "$handlerType->${dismiss.name}($itemType$reasonType)V", leave[3].referenceText())
            assertEquals("$name: with the handler, the reel and the reason", listOf(0, 1, 2), leave[3].registers())
        }

        // The viewer's turn-on: the reel read off the pager goes through the extension and comes back
        // in the same register, cast to the reel, in front of Instagram's own test, and no reel only returns.
        val newEnabled = after(enabled)
        assertEquals("$name: the viewer's turn-on grew", enabled.code().size + 3, newEnabled.size)
        val asked = newEnabled.indexOfFirst { it.referenceText() == CLEAR_MODE_CURRENT }
        assertEquals("$name: the pager's reel is read just before", itemType, newEnabled[asked - 2].methodRef()?.returnType)
        assertEquals("$name: the read is kept", Opcode.MOVE_RESULT_OBJECT, newEnabled[asked - 1].opcode)
        val reel = (newEnabled[asked - 1] as OneRegisterInstruction).registerA
        assertEquals("$name: the reel handed over is the pager's", listOf(reel), newEnabled[asked].registers())
        assertEquals("$name: the answer is kept", Opcode.MOVE_RESULT_OBJECT, newEnabled[asked + 1].opcode)
        assertEquals("$name: the answer goes where the reel was", reel, (newEnabled[asked + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: the answer is cast back", Opcode.CHECK_CAST, newEnabled[asked + 2].opcode)
        assertEquals("$name: the answer is cast to the reel", itemType, newEnabled[asked + 2].type())
        assertEquals("$name: the cast is on the reel's register", reel, (newEnabled[asked + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: Instagram's own test follows", Opcode.IF_EQZ, newEnabled[asked + 3].opcode)
        assertEquals("$name: the test is of the answer", reel, (newEnabled[asked + 3] as OneRegisterInstruction).registerA)
        val withoutReel = newEnabled.drop(newEnabled.target(asked + 3)).takeWhile { it.opcode !in setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE) }
        assertTrue("$name: no reel does more than return", withoutReel.none { it.methodRef() != null || it is OffsetInstruction })

        val newPaged = after(paged)
        assertEquals("$name: the page report grew", paged.code().size + 1, newPaged.size)
        assertEquals("$name: the page report's first instruction", CLEAR_MODE_PAGED, newPaged[0].referenceText())

        // The exit button: its own reel, read off this into the first local, first thing.
        val newRender = after(render)
        assertEquals("$name: the exit button's render grew", render.code().size + 6, newRender.size)
        assertEquals("$name: the render's first instruction", Opcode.MOVE_OBJECT_FROM16, newRender[0].opcode)
        assertEquals("$name: this is what the reel is read from", render.implementation!!.registerCount - 2, (newRender[0] as TwoRegisterInstruction).registerB)
        val reelField = (newRender[1] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the reel is the exit button's own", render.definingClass, reelField.definingClass)
        assertEquals("$name: the field holds a reel", itemType, reelField.type)
        assertEquals("$name: the exit button's hook", CLEAR_MODE_BUILT, newRender[2].referenceText())
        assertEquals("$name: the reel handed over is the one read", listOf(0), newRender[2].registers())
        // Its buzz (#480): asked about once, into a local, and a yes skips the one call to the haptics helper.
        val quiet = newRender.indexOfFirst { it.referenceText() == CLEAR_MODE_QUIET }
        assertEquals("$name: the render asks about its buzz once", 1, newRender.count { it.referenceText() == CLEAR_MODE_QUIET })
        val quietLocal = (newRender[quiet + 1] as OneRegisterInstruction).registerA
        assertEquals("$name: the answer is kept", Opcode.MOVE_RESULT, newRender[quiet + 1].opcode)
        assertTrue("$name: the answer is in a local", quietLocal < render.implementation!!.registerCount - 2)
        assertEquals("$name: the answer is tested", Opcode.IF_NEZ, newRender[quiet + 2].opcode)
        assertEquals("$name: the test is of the answer", quietLocal, (newRender[quiet + 2] as OneRegisterInstruction).registerA)
        val buzz = newRender[quiet + 3].methodRef()
        assertEquals("$name: the buzz answers nothing", "V", buzz?.returnType)
        assertTrue("$name: the buzz is the haptics helper's", context.classDefBy(buzz!!.definingClass).fields.any { it.type == VIBRATOR })
        assertEquals("$name: a yes skips the buzz alone", quiet + 4, newRender.target(quiet + 2))

        // The speaker in clear mode (#608): with the list the render hands its row, right after its
        // last add to it and right before the row is built. Another build may be left without it.
        val rowAdd = runCatching { context.findClearModeRowAdd() }
        if (declared || rowAdd.isSuccess) {
            val (_, rowAt, rowList) = rowAdd.getOrThrow()
            context.addMuteButtonToClearMode()
            val spoken = after(render)
            assertEquals("$name: one call more in the render", newRender.count { it.opcode != Opcode.NOP } + 1, spoken.count { it.opcode != Opcode.NOP })
            assertEquals("$name: the speaker's hook", MUTE_CLEAR_MODE, spoken[rowAt].referenceText())
            assertEquals("$name: it's handed the list", listOf(rowList), spoken[rowAt].registers())
            val addAt = if (spoken[rowAt - 1].opcode == Opcode.MOVE_RESULT) rowAt - 2 else rowAt - 1
            assertEquals("$name: an add to the list comes just before", "add", spoken[addAt].methodRef()?.name)
            assertEquals("$name: the add is to that list", rowList, spoken[addAt].registers().first())
            assertEquals("$name: the row is built right after", Opcode.NEW_INSTANCE, spoken[rowAt + 1].opcode)
            val listMade = spoken.single { it.opcode == Opcode.NEW_INSTANCE && it.type() == "Ljava/util/ArrayList;" }
            assertEquals("$name: the list is the one the render builds", rowList, (listMade as OneRegisterInstruction).registerA)
        } else {
            assertTrue("$name: a render the speaker can't go in is refused", rowAdd.exceptionOrNull() is PatchException)
        }

        // The chrome setter: the alpha asked for and the one held, right after reading the held one,
        // and the answer goes back where the asked one was.
        val newChrome = after(chrome)
        assertEquals("$name: the chrome setter grew", chrome.code().size + 2, newChrome.size)
        val holds = newChrome.indexOfFirst { it.referenceText() == CLEAR_MODE_CHROME_ALPHA }
        assertEquals("$name: the chrome setter asks once", 1, newChrome.count { it.referenceText() == CLEAR_MODE_CHROME_ALPHA })
        assertEquals("$name: the held alpha is read just before", floatValue, newChrome[holds - 2].referenceText())
        assertEquals("$name: the held alpha is kept", Opcode.MOVE_RESULT, newChrome[holds - 1].opcode)
        val heldAlpha = (newChrome[holds - 1] as OneRegisterInstruction).registerA
        val askedAlpha = chrome.implementation!!.registerCount - 1
        assertEquals(
            "$name: the hook hands over the holder, the asked alpha and the held one",
            listOf(askedAlpha - 1, askedAlpha, heldAlpha), newChrome[holds].registers(),
        )
        assertEquals("$name: the answer is kept", Opcode.MOVE_RESULT, newChrome[holds + 1].opcode)
        assertEquals("$name: the answer goes where the asked alpha was", askedAlpha, (newChrome[holds + 1] as OneRegisterInstruction).registerA)
        // The extension reaches that same setter to give a holder its buttons back (#740).
        val setChrome = context.method(CLEAR_MODE, CLEAR_MODE_CHROME_STUB)
        assertEquals("$name: the chrome stub casts to the holder", chrome.definingClass, setChrome[0].type())
        assertEquals(
            "$name: the chrome stub calls the holder's setter",
            "${chrome.definingClass}->${chrome.name}(F)V", setChrome[1].referenceText(),
        )

        // The scroll state change: the pager it just read and the state, right after the read.
        val newScrolled = after(scrolled)
        assertEquals("$name: the scroll state change grew", scrolled.code().size + 1, newScrolled.size)
        val settles = newScrolled.indexOfFirst { it.referenceText() == CLEAR_MODE_SETTLED }
        assertEquals("$name: the scroll state change hands over once", 1, newScrolled.count { it.referenceText() == CLEAR_MODE_SETTLED })
        assertEquals("$name: the pager is read just before", Opcode.IGET_OBJECT, newScrolled[settles - 1].opcode)
        val pagerField = (newScrolled[settles - 1] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the read is of the pager", VIEW_PAGER, pagerField.type)
        val pagerRegister = (newScrolled[settles - 1] as TwoRegisterInstruction).registerA
        assertEquals(
            "$name: the hook hands over the pager and the state",
            listOf(pagerRegister, scrolled.implementation!!.registerCount - 1),
            newScrolled[settles].registers(),
        )

        // The viewer's setup: the handler, by range, right after it's given the action its turn-on reads.
        val newSetup = after(setup)
        val unpadded = { code: List<Instruction> -> code.filter { it.opcode != Opcode.NOP } }
        assertEquals("$name: the viewer's setup grew", unpadded(setup.code()).size + 1, unpadded(newSetup).size)
        val ready = newSetup.indexOfFirst { it.referenceText() == CLEAR_MODE_READY }
        assertEquals("$name: the setup hands over once", 1, newSetup.count { it.referenceText() == CLEAR_MODE_READY })
        assertEquals("$name: the turn-on action is set just before", Opcode.IPUT_OBJECT, newSetup[ready - 1].opcode)
        val set = (newSetup[ready - 1] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the action set is the handler's", handlerType, set.definingClass)
        assertEquals("$name: the action set is the one the turn-on reads", action, set.name)
        assertEquals("$name: the hook is a range call", Opcode.INVOKE_STATIC_RANGE, newSetup[ready].opcode)
        assertEquals(
            "$name: the hook hands over the handler the action went to",
            listOf((newSetup[ready - 1] as TwoRegisterInstruction).registerB),
            newSetup[ready].registers(),
        )
    }

    /**
     * 457's armeabi-v7a (386314340), which inlines the handler's turn-off into its callers: right after
     * each caller's run of the turn-off action, the hook with the handler the action was read off, the
     * reel and the reason the action got, a missing action's branch landing elsewhere. Where the
     * action's read writes over the handler, the handler is copied into another local right before
     * it. The leave stub runs the action the same way, then tells the extension, for a pinch (#744).
     */
    private fun inlinedTurnOff(context: BytecodePatchContext, name: String, inlined: InlinedTurnOff?, handlerType: String) {
        assertTrue("$name: no turn-off and no runs of its action", inlined != null && inlined.runs.isNotEmpty())
        for (run in inlined!!.runs) {
            val where = "$name: ${run.method.definingClass}->${run.method.name}"
            val now = context.now(run.method)
            assertEquals(
                "$where: grew",
                run.method.code().count { it.opcode != Opcode.NOP } + if (run.copy == null) 1 else 2,
                now.count { it.opcode != Opcode.NOP },
            )
            assertEquals("$where: hooked once", 1, now.count { it.referenceText() == CLEAR_MODE_LEFT })
            val left = now.indexOfFirst { it.referenceText() == CLEAR_MODE_LEFT }
            assertEquals("$where: the action's call comes just before", inlined.call, now[left - 1].referenceText())
            val handed = now[left - 1].registers()
            assertEquals("$where: a missing action is tested", Opcode.IF_EQZ, now[left - 2].opcode)
            assertTrue("$where: a missing action skips the hook", now.target(left - 2) != left)
            val read = now[left - 3] as TwoRegisterInstruction
            assertEquals("$where: the action is read off the handler", inlined.action, now[left - 3].referenceText())
            assertEquals("$where: the action read is the one run", handed[0], read.registerA)
            val (reason, reel) = if (inlined.reasonFirst) handed[1] to handed[2] else handed[2] to handed[1]
            val handler = if (run.copy == null) {
                read.registerB
            } else {
                val copy = now[left - 4] as TwoRegisterInstruction
                assertTrue("$where: the handler is copied", now[left - 4].opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16))
                assertEquals("$where: from the register the action is read off", read.registerB, copy.registerB)
                assertTrue("$where: into a register nothing here uses", copy.registerA != read.registerB && copy.registerA !in handed)
                copy.registerA
            }
            assertEquals("$where: the hook hands over the handler, the reel and the reason", listOf(handler, reel, reason), now[left].registers())
        }
        val leave = context.method(CLEAR_MODE, CLEAR_MODE_LEAVE_STUB)
        assertEquals(
            "$name: the leave stub",
            listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID),
            leave.take(6).map { it.opcode },
        )
        assertEquals("$name: the leave stub casts to the handler", handlerType, leave[0].type())
        assertEquals("$name: the leave stub reads the turn-off action", inlined.action, leave[1].referenceText())
        assertEquals("$name: and runs it", inlined.call, leave[3].referenceText())
        assertEquals("$name: with the reason and the reel", if (inlined.reasonFirst) listOf(0, 3, 2) else listOf(0, 2, 3), leave[3].registers())
        assertEquals("$name: then tells the extension", CLEAR_MODE_LEFT, leave[4].referenceText())
        assertEquals("$name: with the handler, the reel and the reason", listOf(1, 2, 3), leave[4].registers())
        assertEquals("$name: no action skips both", 5, leave.target(2))
    }

    /** [method]'s code as the context has it now. */
    private fun BytecodePatchContext.now(method: Method): List<Instruction> =
        mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
        }.code()

    /** Every class [wanted] picks, copied out, in the dex files naming one of [types] (a class can't use a type its dex doesn't name). */
    private fun walk(bundle: File, types: Set<String>, wanted: (ClassDef) -> Boolean): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it in types }) return@forEach
            for (classDef in dex.classes) if (wanted(classDef)) found += ImmutableClassDef.of(classDef)
        }
        return found
    }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /** What each stand-in looks like; the defaults are 450's shape, and each case changes one thing. */
    private data class Shape(
        val enables: Int = 1,
        val enablePublic: Boolean = true,
        val reasonInApp: Boolean = true,
        val reasonIsEnum: Boolean = true,
        val reasonNamesSwipe: Boolean = true,
        val enableActions: Int = 1,
        val enableHandsReason: Boolean = true,
        val enableEndsAtCall: Boolean = false,
        val enableKeepsResult: Boolean = false,
        val reasonOverwritten: Boolean = false,
        val dismissElsewhere: Boolean = false,
        val dismissTakesReason: Boolean = true,
        val itemInApp: Boolean = true,
        val itemPublic: Boolean = true,
        val itemHasId: Boolean = true,
        val handlerOverwritten: Boolean = false,
        val enabledInDismiss: Boolean = false,
        val pagerReads: Int = 1,
        val pagerFolded: Boolean = false,
        val reelTested: Boolean = true,
        val jumpToTest: Boolean = false,
        val noReelCalls: Boolean = false,
        val noReelReturns: Boolean = true,
        val pageTakesInts: Boolean = true,
        val buttonTakesScope: Boolean = true,
        val buttonReels: Int = 1,
        val buttonLocals: Boolean = true,
        val buzzes: Int = 1,
        val jumpToBuzz: Boolean = false,
        val buzzBusy: Boolean = false,
        val buzzDeep: Boolean = false,
        val rows: Int = 1,
        val rowTakesScope: Boolean = true,
        val flagNames: Int = 1,
        val flagFromCall: Boolean = false,
        val flagJump: Boolean = false,
        val flagPrivate: Boolean = false,
        val rowReelStates: Int = 1,
        val reelStatePrivate: Boolean = false,
        val farState: Boolean = false,
        val flagOtherClass: Boolean = false,
        val nameThenOther: Boolean = false,
        val flagAppended: Boolean = true,
        val flagUnfilled: Boolean = false,
        val flagFieldMissing: Boolean = false,
        val childTakesState: Boolean = true,
        val scrubberUnboxed: Boolean = true,
        val scrubberTests: Int = 1,
        val jumpToScrubber: Boolean = false,
        val scrubberHigh: Boolean = false,
        val rowThisOverwritten: Boolean = false,
        val rowLocalsFree: Boolean = true,
        val chromes: Int = 1,
        val chromeTakesAlpha: Boolean = true,
        val chromeStatic: Boolean = false,
        val chromeReturns: String = "V",
        val chromePublic: Boolean = true,
        val holderPublic: Boolean = true,
        val holderOverwritten: Boolean = false,
        val chromeEndsAtRead: Boolean = false,
        val alphaReads: Int = 1,
        val alphaKept: Boolean = true,
        val jumpToHold: Boolean = false,
        val alphaHigh: Boolean = false,
        val askedOverwritten: Boolean = false,
        val alphaStored: Boolean = true,
        val scrollStates: Int = 1,
        val scrollTakesState: Boolean = true,
        val stateInApp: Boolean = true,
        val stateIsEnum: Boolean = true,
        val stateNamesIdle: Boolean = true,
        val viewPagerReads: Int = 1,
        val settleEndsAtRead: Boolean = false,
        val jumpToSettle: Boolean = false,
        val settleHigh: Boolean = false,
        val stateOverwritten: Boolean = false,
        val stubs: Boolean = true,
        val enableReadsPager: Boolean = false,
        val setups: Int = 1,
        val setupSets: Int = 1,
        val setupEndsAtSet: Boolean = false,
        val jumpToReady: Boolean = false,
    )

    /**
     * The fullscreen view's action handler, whose turn-on and turn-off each run the action the viewer
     * set; the reason enum and the reel with its getId; the viewer's turn-on action, which reads the
     * reel the pager is on and does nothing without one; the pager's listener; the exit button a
     * reel in clear mode draws, which holds its reel; and the seek bar row, which holds its state,
     * whose reel state names its clear mode flag in its toString, and which builds the scrubber from
     * the reel and that state past a test of an unboxed yes or no; and the reel holder, whose chrome
     * setter reads the alpha its cell holds, keeps the asked one from rising past it while locked,
     * and stores the asked one; and the scroll state enum, which the pager listener's scroll state
     * change tests for rest before it reads the pager.
     */
    private fun classes(shape: Shape = Shape()): List<ClassDef> {
        // The far shape moves the reel state to another package, where the row can't see a class that isn't public.
        val reelState = if (shape.farState) "Lfixture/far/ReelState;" else this.reelState
        val otherState = "Lfixture/OtherState;"
        val handed = if (shape.enableHandsReason) "p1" else "p0"
        val enableCalls = "invoke-interface { v0, $handed }, $function1->invoke($anyType)$anyType\n".repeat(shape.enableActions)
        val enableBody = if (shape.enableEndsAtCall) {
            """
                const-string v0, "${marker(CLEAR_MODE_ENABLE)}"
                invoke-static { v0 }, $purge
                iget-object v0, p0, $handler->onEnable:$function1
                if-nez v0, :call
                return-void
                :call
                invoke-interface { v0, p1 }, $function1->invoke($anyType)$anyType
            """
        } else {
            """
                const-string v0, "${marker(CLEAR_MODE_ENABLE)}"
                invoke-static { v0 }, $purge
                ${if (shape.enableReadsPager) "iget-object v0, p0, $handler->pager:$pager" else ""}
                iget-object v0, p0, $handler->onEnable:$function1
                if-eqz v0, :done
                ${if (shape.reasonOverwritten) "const/4 p1, 0x0" else ""}
                $enableCalls
                ${if (shape.enableKeepsResult) "move-result-object v0" else ""}
                :done
                return-void
            """
        }
        val enables = (0 until shape.enables).map { copy ->
            method(
                handler, if (copy == 0) "enable" else "enableAgain", listOf(reason), "V", 3, enableBody,
                (if (shape.enablePublic) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or AccessFlags.FINAL.value,
            )
        }

        val dismissOwner = if (shape.dismissElsewhere) "Lfixture/Other;" else handler
        val dismissParameters = listOf(item, if (shape.dismissTakesReason) reason else stringType)
        val dismiss = if (shape.enabledInDismiss) {
            method(dismissOwner, "dismiss", dismissParameters, "V", 5, """
                invoke-static { p1 }, Lfixture/Checks;->notNull($anyType)V
                const-string v0, "${marker(CLEAR_MODE_DISMISS)}"
                invoke-static { v0 }, $purge
                const-string v0, "${marker(CLEAR_MODE_ENABLED)}"
                iget-object v0, p0, $handler->pager:$pager
                invoke-virtual { v0 }, $pager->current()$item
                move-result-object v1
                if-eqz v1, :done
                iget-object v0, p0, $handler->onDismiss:$function2
                if-eqz v0, :done
                invoke-interface { v0, p2, p1 }, $function2->invoke($anyType$anyType)$anyType
                :done
                return-void
            """)
        } else {
            method(dismissOwner, "dismiss", dismissParameters, "V", 4, """
                invoke-static { p1 }, Lfixture/Checks;->notNull($anyType)V
                const-string v0, "${marker(CLEAR_MODE_DISMISS)}"
                invoke-static { v0 }, $purge
                iget-object v0, p0, $handler->onDismiss:$function2
                if-eqz v0, :done
                ${if (shape.handlerOverwritten) "const/4 p0, 0x0" else ""}
                invoke-interface { v0, p2, p1 }, $function2->invoke($anyType$anyType)$anyType
                :done
                return-void
            """)
        }
        val handlerClass = classDef(
            handler, enables + (if (shape.dismissElsewhere) emptyList() else listOf(dismiss)),
            fields = listOf(field(handler, "onEnable", function1), field(handler, "onDismiss", function2), field(handler, "pager", pager)),
        )

        val names = listOfNotNull("LONG_PRESS", "EXIT_BUTTON", if (shape.reasonNamesSwipe) CLEAR_MODE_SWIPED_AWAY else null, "OVERFLOW_MENU")
        val reasonClass = ImmutableClassDef(
            reason, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value,
            if (shape.reasonIsEnum) "Ljava/lang/Enum;" else "Ljava/lang/Object;", null, null, null, emptyList(),
            listOf(
                method(
                    reason, "<clinit>", emptyList(), "V", 1,
                    names.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void",
                    AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value,
                ),
            ),
        )
        val itemClass = ImmutableClassDef(
            item, (if (shape.itemPublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, listOf(field(item, "id", stringType)),
            if (!shape.itemHasId) emptyList() else listOf(
                method(item, "getId", emptyList(), stringType, 2, """
                    iget-object v0, p0, $item->id:$stringType
                    return-object v0
                """),
            ),
        )

        val read = (
            if (shape.pagerFolded) "invoke-static { v0 }, $pagers->current($pager)$item\nmove-result-object v2\n"
            else "invoke-virtual { v0 }, $pager->current()$item\nmove-result-object v2\n"
            ).repeat(shape.pagerReads)
        val answer = "sget-object v0, $unit\nreturn-object v0"
        val enabledMarker = if (shape.enabledInDismiss) "ClipsViewerNavigatorProvider_handleLongPress" else CLEAR_MODE_ENABLED
        val enabled = method(actions, "enabled", listOf(anyType, anyType), anyType, 6, """
            check-cast p0, $viewer
            check-cast p1, $reason
            const-string v0, "${marker(enabledMarker)}"
            invoke-static { v0 }, $purge
            iget-object v0, p0, $viewer->pager:$pager
            $read
            ${if (shape.reelTested) "" else "const/4 v1, 0x0"}
            ${if (shape.jumpToTest) ":test" else ""}
            if-eqz v2, :none
            invoke-static { v2, p1 }, $viewer->show($item$reason)V
            ${if (shape.jumpToTest) "if-nez v2, :test" else ""}
            ${if (shape.noReelReturns) "" else answer}
            :none
            ${if (shape.noReelCalls) "invoke-static { }, Lfixture/Log;->none()V" else ""}
            ${if (shape.noReelReturns) answer else "const/4 v0, 0x0"}
        """, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)

        // The viewer's setup (453's 03VS.A08) gives the handler the action its turn-on reads.
        val setAction = "iput-object p1, p0, $handler->onEnable:$function1\n".repeat(shape.setupSets)
        val setupBody = when {
            shape.setupEndsAtSet -> "const-string v0, \"${marker(CLEAR_MODE_SETUP)}\"\ninvoke-static { v0 }, $purge\n$setAction"
            shape.jumpToReady -> """
                const-string v0, "${marker(CLEAR_MODE_SETUP)}"
                invoke-static { v0 }, $purge
                if-eqz p1, :ready
                $setAction
                :ready
                return-void
            """
            else -> """
                const-string v0, "${marker(CLEAR_MODE_SETUP)}"
                invoke-static { v0 }, $purge
                $setAction
                return-void
            """
        }
        val setups = (0 until shape.setups).map { copy ->
            method(
                actions, if (copy == 0) "setup" else "setupAgain", listOf(handler, function1), "V", 3, setupBody,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            )
        }

        val paged = method(listener, "onPageSelected", if (shape.pageTakesInts) listOf("I", "I") else listOf("I"), "V", 5, """
            const/4 v0, 0x0
            const-string v1, "${marker(REEL_PAGE_SELECTED, "q2")}"
            invoke-static { v1 }, $purge
            return-void
        """)

        // 452's scroll state change (07Hr.G9K) reads the pager into v1 on the way to rest only and
        // tests it for null; the high shape keeps the state past v15 and tests a copy of it.
        val scrollHigh = shape.settleHigh
        val scrollSelf = if (scrollHigh) "v3" else "p0"
        val scrollTested = if (scrollHigh) "v2" else "p1"
        val scrollMarker = if (shape.scrollStates == 0) "ClipsViewerViewPagerListener_onPageScrolled" else REEL_SCROLL_STATE
        val pagerRead = (
            if (shape.viewPagerReads == 0) "iget-object v1, v0, $pagerHost->view:$anyType\n"
            else "iget-object v1, v0, $pagerHost->pager:$VIEW_PAGER\n".repeat(shape.viewPagerReads)
            )
        val scrollBody = if (shape.settleEndsAtRead) {
            """
                const-string v0, "${marker(scrollMarker, "q2")}"
                invoke-static { v0 }, $purge
                iget-object v0, p0, $listener->host:$pagerHost
                $pagerRead
            """
        } else {
            """
                ${if (scrollHigh) "move-object/from16 v3, p0\nmove-object/from16 v2, p1" else ""}
                const-string v0, "${marker(scrollMarker, "q2")}"
                invoke-static { v0 }, $purge
                sget-object v0, $scrollState->IDLE:$scrollState
                if-ne $scrollTested, v0, :done
                ${if (shape.stateOverwritten) "const/4 p1, 0x0" else ""}
                iget-object v0, $scrollSelf, $listener->host:$pagerHost
                ${if (shape.jumpToSettle) "if-eqz v0, :settle" else ""}
                $pagerRead
                ${if (shape.jumpToSettle) ":settle" else ""}
                if-eqz v1, :done
                invoke-static { v1 }, Lfixture/Log;->rest($anyType)V
                :done
                return-void
            """
        }
        val scrollParameters = if (shape.scrollTakesState) listOf(scrollState) else listOf(scrollState, "I")
        val scrolls = (0 until maxOf(shape.scrollStates, 1)).map { copy ->
            method(
                listener, if (copy == 0) "onScrollStateChanged" else "onScrollStateChangedAgain", scrollParameters, "V",
                (if (scrollHigh) 17 else 3) + scrollParameters.size, scrollBody,
            )
        }
        val stateNames = listOfNotNull(if (shape.stateNamesIdle) REEL_AT_REST else null, "DRAGGING", "SETTLING")
        val stateClass = ImmutableClassDef(
            scrollState, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value,
            if (shape.stateIsEnum) "Ljava/lang/Enum;" else "Ljava/lang/Object;", null, null, null, emptyList(),
            listOf(
                method(
                    scrollState, "<clinit>", emptyList(), "V", 1,
                    stateNames.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void",
                    AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value,
                ),
            ),
        )

        // 453's render (09Xm.A0o) reads the haptics helper into v0 and buzzes through it; the busy
        // shape keeps v1 for after the buzz, so no local is free there.
        val local = if (shape.buttonLocals) "v0" else "p1"
        val renderParameters = if (shape.buttonTakesScope) listOf(scope) else listOf(scope, "I")
        val tap = "invoke-virtual { $local }, $haptics->${if (shape.buzzDeep) "tapDeep" else "tap"}()V\n"
        val render = method(button, "render", renderParameters, component, if (shape.buttonLocals) 3 + renderParameters.size else 2, """
            const-string $local, "${marker(CLEAR_MODE_BUTTON)}"
            invoke-static { $local }, $purge
            ${if (shape.buzzBusy) "const/4 v1, 0x1" else ""}
            invoke-static { }, $haptics->get()$haptics
            move-result-object $local
            ${if (shape.jumpToBuzz) "if-eqz $local, :buzz\n:buzz" else ""}
            ${tap.repeat(shape.buzzes)}
            ${if (shape.buzzBusy) "invoke-static { v1 }, Lfixture/Log;->kept(I)V" else ""}
            const/4 $local, 0x0
            return-object $local
        """)
        // The helper buzzes for 30 ms through a method of its own; tapDeep goes through one more.
        val hapticsClass = classDef(
            haptics,
            listOf(
                method(haptics, "get", emptyList(), haptics, 1, "const/4 v0, 0x0\nreturn-object v0",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value),
                method(haptics, "tap", emptyList(), "V", 4, """
                    const-wide/16 v0, 0x1e
                    const/4 v2, 0x0
                    invoke-virtual { p0, v0, v1, v2 }, $haptics->buzz(JZ)V
                    return-void
                """),
                method(haptics, "tapDeep", emptyList(), "V", 1, "invoke-virtual { p0 }, $haptics->tap()V\nreturn-void"),
                method(haptics, "buzz", listOf("J", "Z"), "V", 5, """
                    iget-object v0, p0, $haptics->vibrator:$VIBRATOR
                    invoke-virtual { v0, p1, p2 }, $VIBRATOR->vibrate(J)V
                    return-void
                """),
            ),
            fields = listOf(field(haptics, "vibrator", VIBRATOR)),
        )
        val buttonFields = (0 until shape.buttonReels).map { field(button, if (it == 0) "reel" else "other", item) } +
            listOf(field(button, "state", "Lfixture/State;"), field(button, "exited", "Z"))

        // 452's row keeps Instagram's answer and the scrubber's makings in low locals; the high shape
        // keeps the answer past v15 and reads through a copy of this, since p0 is past v15 too.
        val self = if (shape.scrubberHigh) "v5" else "p0"
        val shown = if (shape.scrubberHigh) "v16" else "v1"
        val unbox = if (shape.scrubberHigh) "invoke-virtual/range { v16 .. v16 }, $boxed->booleanValue()Z" else "invoke-virtual { v1 }, $boxed->booleanValue()Z"
        val visibleTest = """
            iget-object v0, $self, $row->visible:$stateHook
            ${if (shape.rowThisOverwritten) "const/4 p0, 0x0" else ""}
            invoke-virtual { v0 }, $stateHook->getValue()$anyType
            move-result-object $shown
            check-cast $shown, $boxed
            $unbox
            move-result $shown
            ${if (shape.jumpToScrubber) ":test" else ""}
            if-eqz $shown, :done
        """
        val rowMarker = if (shape.rows == 0) "ClipsScrubberRowComponent_measure" else CLEAR_MODE_SCRUBBER_ROW
        val rowParameters = if (shape.rowTakesScope) listOf(scope) else listOf(scope, "I")
        val rowRender = method(row, "render", rowParameters, component, (if (shape.scrubberHigh) 19 else 7) + rowParameters.size - 1, """
            ${if (shape.scrubberHigh) "move-object/from16 v5, p0" else ""}
            const-string v0, "${marker(rowMarker)}"
            invoke-static { v0 }, $purge
            iget-object v4, $self, $row->state:$rowState
            iget-object v2, v4, $rowState->item:$item
            iget-object v3, v4, $rowState->reel:$reelState
            iget-object v0, $self, $row->thumb:$stateHook
            invoke-virtual { v0 }, $stateHook->getValue()$anyType
            move-result-object v1
            check-cast v1, $boxed
            invoke-virtual { v1 }, $boxed->booleanValue()Z
            move-result v1
            if-eqz v1, :thumbless
            invoke-static { }, Lfixture/Log;->thumb()V
            ${if (shape.jumpToScrubber) "if-nez v1, :test" else ""}
            :thumbless
            ${if (shape.scrubberUnboxed) visibleTest else "iget-boolean v1, $self, $row->shown:Z\nif-eqz v1, :done"}
            ${if (shape.scrubberTests == 2) visibleTest.replace(":test", "") else ""}
            new-instance v0, $scrubberChild
            invoke-direct { v0, v2, v3 }, $scrubberChild-><init>($item${if (shape.childTakesState) reelState else "I"})V
            invoke-static { v0 }, Lfixture/Children;->add($anyType)V
            :done
            ${if (shape.rowLocalsFree) "" else "invoke-static { v0, v4 }, Lfixture/Log;->both($anyType$anyType)V"}
            const/4 v0, 0x0
            return-object v0
        """)
        val rowClass = classDef(
            row, listOf(rowRender),
            fields = listOf(
                field(row, "state", rowState), field(row, "visible", stateHook), field(row, "thumb", stateHook), field(row, "shown", "Z"),
            ),
        )
        val reelField = if (shape.reelStatePrivate) {
            ImmutableField(rowState, "reel", reelState, AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null, null)
        } else {
            field(rowState, "reel", reelState)
        }
        val rowStateClass = classDef(
            rowState, emptyList(),
            fields = listOf(field(rowState, "item", item), reelField, field(rowState, "count", "I")) +
                (if (shape.rowReelStates == 2) listOf(field(rowState, "previous", reelState)) else emptyList()),
        )
        val flagName = if (shape.flagNames == 0) ", isOtherViewActive=" else CLEAR_MODE_FLAG_NAME
        val flagRead = when {
            shape.flagFromCall -> "invoke-virtual { p0 }, $reelState->isFullscreen()Z\nmove-result v0"
            shape.flagOtherClass -> "iget-object v1, p0, $reelState->other:$otherState\niget-boolean v0, v1, $otherState->fullscreen:Z"
            else -> "iget-boolean v0, p0, $reelState->fullscreen:Z"
        }
        // v3 is p0: a parameter, so no instruction fills it.
        val flagAppend = when {
            !shape.flagAppended -> ""
            shape.flagUnfilled -> "invoke-virtual { v1, v3 }, $builder->append(Z)$builder"
            else -> "move v0, v2\ninvoke-virtual { v1, v0 }, $builder->append(Z)$builder"
        }
        val toString = method(reelState, "toString", emptyList(), stringType, 4, """
            $flagRead
            move v2, v0
            new-instance v1, $builder
            invoke-direct { v1 }, $builder-><init>()V
            const-string v0, "ReelState(isPlaying="
            invoke-virtual { v1, v0 }, $builder->append($stringType)$builder
            iget-boolean v0, p0, $reelState->playing:Z
            ${if (shape.flagJump) "if-eqz v0, :flag" else ""}
            invoke-virtual { v1, v0 }, $builder->append(Z)$builder
            const-string v0, "$flagName"
            invoke-virtual { v1, v0 }, $builder->append($stringType)$builder
            ${if (shape.nameThenOther) "const-string v0, \", isMuted=\"\ninvoke-virtual { v1, v0 }, $builder->append($stringType)$builder" else ""}
            ${if (shape.flagJump) ":flag" else ""}
            $flagAppend
            ${if (shape.flagNames == 2) "const-string v0, \"$flagName\"\ninvoke-virtual { v1, v0 }, $builder->append($stringType)$builder" else ""}
            invoke-virtual { v1 }, $builder->toString()$stringType
            move-result-object v0
            return-object v0
        """)
        val reelStateFields = listOf(
            field(reelState, "playing", "Z"),
            when {
                shape.flagPrivate -> ImmutableField(reelState, "fullscreen", "Z", AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null, null)
                shape.flagFieldMissing -> field(reelState, "fullscreen", "I")
                else -> field(reelState, "fullscreen", "Z")
            },
        )
        val reelStateClass = if (shape.farState) {
            ImmutableClassDef(reelState, AccessFlags.FINAL.value, anyType, null, null, null, reelStateFields, listOf(toString))
        } else {
            classDef(reelState, listOf(toString), fields = reelStateFields)
        }
        val childClass = classDef(
            scrubberChild,
            listOf(
                method(
                    scrubberChild, "<init>", listOf(item, if (shape.childTakesState) reelState else "I"), "V", 3, "return-void",
                    AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                ),
            ),
        )

        // 452's holder (07RN.A0M) reads the alpha its cell holds into v1 and is asked for one in p1;
        // the high shape keeps the asked one past v15 and reads through a copy of this.
        val high = shape.alphaHigh
        val holderSelf = if (high) "v3" else "p0"
        val alphaRead = "iget-object v0, v2, $cell->value:$anyType\ncheck-cast v0, Ljava/lang/Number;\n" +
            "invoke-virtual { v0 }, $floatValue\n" + (if (shape.alphaKept) "move-result v1\n" else "const/4 v1, 0x0\n")
        val chromeMarker = if (shape.chromes == 0) "LithoClipsItemDefinition_Holder_bind" else CLEAR_MODE_CHROME
        val chromeMarked = "const-string v0, \"${marker(chromeMarker, "q2")}\"\ninvoke-static { v0 }, $purge\n"
        val fullChrome = """
            ${if (high) "move-object/from16 v3, p0" else ""}
            const-string v0, "${marker(chromeMarker, "q2")}"
            invoke-static { v0 }, $purge
            ${if (shape.askedOverwritten) "const/4 p1, 0x0" else ""}
            iget-object v2, $holderSelf, $holder->alpha:$cell
            ${if (shape.holderOverwritten) "move-object p0, v2" else ""}
            ${if (shape.jumpToHold) "if-eqz v2, :hold" else ""}
            ${alphaRead.repeat(shape.alphaReads)}
            ${if (shape.alphaReads == 0) "iget v1, v2, $cell->raw:F" else ""}
            ${if (shape.jumpToHold) ":hold" else ""}
            iget-object v0, $holderSelf, $holder->locked:$function0
            invoke-interface { v0 }, $function0->invoke()$anyType
            move-result-object v0
            check-cast v0, $boxed
            invoke-virtual { v0 }, $boxed->booleanValue()Z
            move-result v0
            if-eqz v0, :store
            cmpl-float v0, p1, v1
            if-lez v0, :store
            ${if (high) "move/from16 p1, v1" else "move p1, v1"}
            :store
            ${when {
                !shape.alphaStored -> "invoke-static { v1 }, Ljava/lang/Float;->valueOf(F)Ljava/lang/Float;"
                high -> "invoke-static/range { p1 .. p1 }, Ljava/lang/Float;->valueOf(F)Ljava/lang/Float;"
                else -> "invoke-static { p1 }, Ljava/lang/Float;->valueOf(F)Ljava/lang/Float;"
            }}
            move-result-object v0
            invoke-virtual { v2, v0 }, $cell->set($anyType)V
            ${if (shape.chromeReturns == "V") "return-void" else "const/4 v0, 0x0\nreturn v0"}
        """
        val chromeBody = when {
            // A static setter can't name p1, so it holds the marker alone.
            shape.chromeStatic -> chromeMarked + "return-void"
            shape.chromeEndsAtRead -> chromeMarked + "iget-object v2, p0, $holder->alpha:$cell\n" + alphaRead
            else -> fullChrome
        }
        val chromeFlags = (if (shape.chromePublic) AccessFlags.PUBLIC.value else 0) or
            (if (shape.chromeStatic) AccessFlags.STATIC.value else AccessFlags.FINAL.value)
        val chromes = (0 until maxOf(shape.chromes, 1)).map { copy ->
            method(
                holder, if (copy == 0) "updateChrome" else "updateChromeAgain",
                if (shape.chromeTakesAlpha) listOf("F") else listOf("I"), shape.chromeReturns, if (high) 19 else 5, chromeBody,
                chromeFlags,
            )
        }
        val holderFields = listOf(field(holder, "alpha", cell), field(holder, "locked", function0))
        val holderClass = if (shape.holderPublic) {
            classDef(holder, chromes, fields = holderFields)
        } else {
            ImmutableClassDef(holder, AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, holderFields, chromes)
        }

        val extension = if (shape.stubs) {
            ExtensionDex.classDef(CLEAR_MODE)
        } else {
            classDef(
                CLEAR_MODE,
                listOf(method(CLEAR_MODE, CLEAR_MODE_ENTER_STUB, listOf(anyType, anyType), "V", 2, "return-void", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)),
            )
        }

        return listOfNotNull(
            handlerClass,
            if (shape.dismissElsewhere) classDef("Lfixture/Other;", listOf(dismiss)) else null,
            if (shape.reasonInApp) reasonClass else null,
            if (shape.itemInApp) itemClass else null,
            classDef(actions, listOf(enabled) + setups),
            classDef(listener, listOf(paged) + scrolls, fields = listOf(field(listener, "host", pagerHost))),
            if (shape.stateInApp) stateClass else null,
            classDef(button, listOf(render), fields = buttonFields),
            hapticsClass,
            rowClass,
            rowStateClass,
            reelStateClass,
            childClass,
            holderClass,
            extension,
        )
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun field(owner: String, name: String, type: String) = ImmutableField(
        owner, name, type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null,
    )

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    private fun BytecodePatchContext.method(owner: String, name: String): List<Instruction> =
        mutableClassDefBy(owner).methods.single { it.name == name }.code()

    /** The names of the methods of [classes] that call one of the hooks, as the context has them now. */
    private fun BytecodePatchContext.written(classes: List<ClassDef>): List<String> =
        classes.map { it.type }.distinct().flatMap { type -> classDefByOrNull(type)?.methods?.toList().orEmpty() }
            .filter { method -> method.code().any { it.referenceText() in hooks } }.map { it.name }

    private fun assertCall(what: String, instruction: Instruction, hook: String, registers: List<Int>) {
        assertEquals("$what: the hook", hook, instruction.referenceText())
        assertEquals("$what: the registers", registers, instruction.registers())
    }

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        var address = 0
        val addresses = map { instruction -> address.also { address += instruction.codeUnits } }
        val landing = addresses[index] + (this[index] as OffsetInstruction).codeOffset
        return addresses.indexOf(landing)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) "${it.definingClass}->${it.name}:${it.type}" else it.toString()
    }

    private fun Instruction.type(): String? = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

    private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}
