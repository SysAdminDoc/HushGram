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
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
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
    private val hooks = listOf(
        CLEAR_MODE_ENTERED, CLEAR_MODE_LEFT, CLEAR_MODE_CURRENT, CLEAR_MODE_PAGED, CLEAR_MODE_BUILT, CLEAR_MODE_SCRUBBER, CLEAR_MODE_CHROME_ALPHA,
        CLEAR_MODE_SETTLED,
    )

    private fun marker(name: String, quarter: String = "q3") = "android_purge_26_${quarter}_$name"

    /** Every hook the patch writes is in the ReelClearMode the bundle ships, public and static, and so is every stub. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(CLEAR_MODE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val stubs = listOf("$CLEAR_MODE_ENTER_STUB($anyType$anyType)V", "$CLEAR_MODE_ID_STUB($anyType)$stringType")
        for (signature in hooks.map { it.substringAfter("->") } + stubs) {
            assertTrue("$signature is not in the extension: $declared", signature in declared)
        }
    }

    /**
     * The handler's turn-on and turn-off each tell the extension after their action's call, where the
     * branch around a missing action still lands past the hook. The viewer's turn-on passes the reel
     * it read off the pager through the extension in front of its own test. The pager's listener and
     * the exit button's render each ask first thing. The reel holder's chrome setter passes the
     * alpha it was asked for through the extension right after reading the one it holds. The pager
     * listener's scroll state change hands over the pager right after reading it, with the state.
     * The stubs call the handler's turn-on and the reel's getId.
     */
    @Test
    fun everyHookGoesInAndBothStubsAreFilled() {
        val context = PatchContexts.of(classes())

        context.keepClearMode()

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

        // The chrome setter: the alpha asked for and the one it holds, right after reading that one,
        // and the answer goes where the asked one was, ahead of Instagram's own compare and store.
        val chrome = context.method(holder, "updateChrome")
        val holds = chrome.indexOfFirst { it.referenceText() == CLEAR_MODE_CHROME_ALPHA }
        assertEquals("the held alpha isn't read just before", floatValue, chrome[holds - 2].referenceText())
        assertEquals(Opcode.MOVE_RESULT, chrome[holds - 1].opcode)
        // Three locals, then this and the alpha asked for.
        assertCall("the chrome setter's hook", chrome[holds], CLEAR_MODE_CHROME_ALPHA, listOf(4, 1))
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
            val stubs = context.classDefBy(CLEAR_MODE).methods.filter { it.name == CLEAR_MODE_ENTER_STUB || it.name == CLEAR_MODE_ID_STUB }
            assertTrue("$expected: a stub was filled", stubs.none { stub -> stub.code().any { it.opcode == Opcode.CHECK_CAST } })
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
            listener to "onScrollStateChanged",
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
     * setter reads the alpha it holds, and right after the pager listener's scroll state change
     * reads the pager.
     */
    @Test
    fun eachDeclaredBuildGetsEveryHook() {
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
    fun eachOtherBuildGetsEveryHookToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
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
        val methods = holders.flatMap { it.methods }
        if (methods.count { CLEAR_MODE_ENABLE in it.markers() } > 1) {
            // The x86 builds inline the turn-on into its callers: Clean up Reels goes in without Keep clear mode on there.
            assertTrue("$label: only the x86 builds inline the turn-on", label.endsWith("-385914518") || label.endsWith("-385914558"))
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
        val handlerType = enable.definingClass
        val reasonType = enable.parameterTypes.single().toString()
        val itemType = dismiss.parameterTypes.first().toString()
        val stateType = scrolled.parameterTypes.single().toString()
        // The row's state and what it holds, what the row builds, and the reel state whose toString names the flag.
        val rowFields = holders.single { it.type == scrubberRow.definingClass }.instanceFields.map { it.type }.filter { it.startsWith("L") }.toSet()
        val heldFields = FixtureDex.classes(bundle, rowFields).values.flatMap { held -> held.instanceFields.map { it.type } }.filter { it.startsWith("L") }
        val built = scrubberRow.code().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { it.type() }
        val extra = FixtureDex.classes(bundle, setOf(reasonType, itemType, stateType) + rowFields + heldFields + built).values +
            FixtureDex.classesHolding(bundle, CLEAR_MODE_FLAG_NAME)
        val context = PatchContexts.of((holders + extra + ExtensionDex.classDef(CLEAR_MODE)).distinctBy { it.type })

        context.keepClearMode()

        val name = label
        val after = { method: Method ->
            context.mutableClassDefBy(method.definingClass).methods.single {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
            }.code()
        }

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

        // The turn-off: this, the reel and the reason, placed the same way.
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
        assertEquals("$name: the exit button's render grew", render.code().size + 3, newRender.size)
        assertEquals("$name: the render's first instruction", Opcode.MOVE_OBJECT_FROM16, newRender[0].opcode)
        assertEquals("$name: this is what the reel is read from", render.implementation!!.registerCount - 2, (newRender[0] as TwoRegisterInstruction).registerB)
        val reelField = (newRender[1] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the reel is the exit button's own", render.definingClass, reelField.definingClass)
        assertEquals("$name: the field holds a reel", itemType, reelField.type)
        assertEquals("$name: the exit button's hook", CLEAR_MODE_BUILT, newRender[2].referenceText())
        assertEquals("$name: the reel handed over is the one read", listOf(0), newRender[2].registers())

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
        assertEquals("$name: the hook hands over the asked alpha and the held one", listOf(askedAlpha, heldAlpha), newChrome[holds].registers())
        assertEquals("$name: the answer is kept", Opcode.MOVE_RESULT, newChrome[holds + 1].opcode)
        assertEquals("$name: the answer goes where the asked alpha was", askedAlpha, (newChrome[holds + 1] as OneRegisterInstruction).registerA)

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

        val enter = context.method(CLEAR_MODE, CLEAR_MODE_ENTER_STUB)
        assertEquals("$name: the stub calls the handler's turn-on", "$handlerType->${enable.name}($reasonType)V", enter[2].referenceText())
        val reelId = context.method(CLEAR_MODE, CLEAR_MODE_ID_STUB)
        assertEquals("$name: the stub reads the reel's id", "$itemType->getId()$stringType", reelId[1].referenceText())
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

        val local = if (shape.buttonLocals) "v0" else "p1"
        val renderParameters = if (shape.buttonTakesScope) listOf(scope) else listOf(scope, "I")
        val render = method(button, "render", renderParameters, component, if (shape.buttonLocals) 3 + renderParameters.size else 2, """
            const-string $local, "${marker(CLEAR_MODE_BUTTON)}"
            invoke-static { $local }, $purge
            const/4 $local, 0x0
            return-object $local
        """)
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
        val chromeFlags = AccessFlags.PUBLIC.value or (if (shape.chromeStatic) AccessFlags.STATIC.value else AccessFlags.FINAL.value)
        val chromes = (0 until maxOf(shape.chromes, 1)).map { copy ->
            method(
                holder, if (copy == 0) "updateChrome" else "updateChromeAgain",
                if (shape.chromeTakesAlpha) listOf("F") else listOf("I"), shape.chromeReturns, if (high) 19 else 5, chromeBody,
                chromeFlags,
            )
        }
        val holderClass = classDef(holder, chromes, fields = listOf(field(holder, "alpha", cell), field(holder, "locked", function0)))

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
            classDef(actions, listOf(enabled)),
            classDef(listener, listOf(paged) + scrolls, fields = listOf(field(listener, "host", pagerHost))),
            if (shape.stateInApp) stateClass else null,
            classDef(button, listOf(render), fields = buttonFields),
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
