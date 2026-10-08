/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.taptoplay

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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

class TapToPlayHookTest {
    private val player = "Lfixture/VideoPlayer;"
    private val groot = "Lfixture/GrootPlayer;"
    private val core = "Lfixture/CorePlayer;"
    private val checker = "Lfixture/AutoplayChecker;"
    private val string = "Ljava/lang/String;"
    private val objectType = "Ljava/lang/Object;"
    private val navigator = "Lfixture/PauseAndMuteNavigator;"
    private val controller = "Lfixture/ClipsVideoPlayerController;"
    private val lookup = "Lfixture/ClipsPlayers;"
    private val holder = "Lfixture/ClipsViewHolder;"
    private val reelPlayer = "Lfixture/ClipsVideoPlayer;"
    private val state = "Lfixture/PlayerState;"
    private val function0 = "Lkotlin/jvm/functions/Function0;"
    private val binder = "Lfixture/VideoPlayButtonBinder;"
    private val buttonState = "Lfixture/PlayButtonState;"
    private val button = "Lfixture/LithoPlayButton;"
    private val lambdas = "Lfixture/Lambdas;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val storyPlayerInterface = "Lfixture/StoryVideoPlayer;"
    private val storyPlayer = "Lfixture/StoryPlayer;"
    private val storyPlayerOther = "Lfixture/StoryPlayerOverVideoPlayer;"
    private val scrollerType = "Lfixture/ClipsAutoScroller;"
    private val scrollerBase = "Lfixture/ScrollerBase;"
    private val pager = "Lfixture/ClipsViewPager;"
    private val upNext = "Lfixture/UpNextNavigator;"
    private val dataSaver = "Lfixture/DataSaverDialog;->A00(Landroid/content/Context;Lfixture/Module;$session$function0)V"

    /** The players' video logger, the Reels viewer's, one the feed makes, and a Reels helper that's no logger. */
    private val logger = "Lfixture/VideoLogger;"
    private val reelsLogger = "Lfixture/ClipsVideoLogger;"
    private val feedLogger = "Lfixture/FeedVideoLogger;"
    private val clipsHelper = "Lfixture/ClipsViewerHelper;"

    /** What playInternal's hook reads before it asks: the IgGrootPlayer, then the logger it checks. */
    private val playInternalPrefix = listOf(Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INSTANCE_OF)

    /** What the Reels pager keeps, and the marker of its smooth scroll to the next item, which the fixture check holds the move to. */
    private val viewPager2 = "Landroidx/viewpager2/widget/ViewPager2;"
    private val smoothScrollToNextItem = "ClipsViewPagerImpl_smoothScrollToNextItem"

    /** What the controller's pause logs, which the fixture check finds it by. */
    private val pauseCurrentPlayerLog = "ClipsVideoPlayerController.pauseCurrentPlayer pauseReason="

    /** Every hook the patch writes is in the extension the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(ALLOW_START, ALLOW_DIRECT_START, PAUSED, REBOUND, AUTOPLAY_ALLOWED, TOUCH, RESUME_ON_TAP, PLAY_BUTTON_TAPPED, RESUME_HELD_STORY, AUTO_SCROLLED)) {
            val type = hook.substringBefore("->")
            val declared = ExtensionDex.classDef(type).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        val stubs = ExtensionDex.classDef(REEL_STATE_READER).methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for ((name, count) in REEL_STUBS) {
            val stub = "$name(${objectType.repeat(count)})$objectType"
            assertTrue("the stub $stub is not in the extension: $stubs", stub in stubs)
        }
        val grootOf = ExtensionDex.classDef(STORY_PLAYER_READER).methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("the stub $GROOT_OF is not in the extension: $grootOf", "$GROOT_OF($objectType)$objectType" in grootOf)
    }

    /**
     * playInternal hands over the IgGrootPlayer it would play and returns on a no, IgGrootPlayer's
     * play does the same, its pause and prepare report first thing, every return of the autoplay
     * check goes through the filter, and the touch dispatch feeds the tap clock.
     */
    @Test
    fun eachHookGoesFirst() {
        val context = PatchContexts.of(classes())

        val warnings = PatchLogCapture.warnings { context.holdStartsWithoutATap() }

        assertEquals("nothing went without", emptyList<String>(), warnings)
        assertGateFirst("playInternal", context.method(player, "A0J").code(), ALLOW_START, prefix = playInternalPrefix)
        val internal = context.method(player, "A0J").code()
        assertEquals("the IgGrootPlayer comes from the player's field", "$player->groot:$groot", (internal[0] as ReferenceInstruction).reference.toString())
        assertEquals("read from playInternal's player", 2, (internal[0] as TwoRegisterInstruction).registerB)
        assertEquals("the logger comes from the player's field", "$player->logger:$logger", internal[1].referenceText())
        assertEquals("read from playInternal's player too", 2, (internal[1] as TwoRegisterInstruction).registerB)
        assertEquals("checked against the Reels viewer's logger", reelsLogger, internal[2].referenceText())
        assertEquals("the check reads the logger it read", (internal[1] as TwoRegisterInstruction).registerA, (internal[2] as TwoRegisterInstruction).registerB)
        val ask = internal[3] as FiveRegisterInstruction
        assertEquals(
            "the IgGrootPlayer, the reason and the check",
            listOf((internal[0] as TwoRegisterInstruction).registerA, 3, (internal[2] as TwoRegisterInstruction).registerA),
            listOf(ask.registerC, ask.registerD, ask.registerE),
        )

        val play = context.method(groot, "A0X").code()
        assertGateFirst("play", play, ALLOW_DIRECT_START)
        assertEquals("this and the reason", listOf(1, 2), (play[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })

        val pause = context.method(groot, "A0V").code()
        assertEquals(PAUSED, pause[0].referenceText())
        assertEquals("this and the reason", listOf(1, 2), (pause[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertTrue("the other String method", context.method(groot, "A0W").code().none { it.referenceText() == PAUSED })

        val prepare = context.method(groot, "A0S").code()
        assertEquals(REBOUND, prepare[0].referenceText())
        assertEquals("this, past v15", listOf(21, 1), (prepare[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })

        val check = context.method(checker, "A01").code()
        val returns = check.indices.filter { check[it].opcode == Opcode.RETURN }
        assertEquals(2, returns.size)
        for (at in returns) {
            assertEquals(AUTOPLAY_ALLOWED, check[at - 2].referenceText())
            assertEquals(Opcode.MOVE_RESULT, check[at - 1].opcode)
        }
        val branch = check.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals("the branch to the second return passes through the filter", returns[1] - 2, check.target(branch))

        val touch = context.method(FRAGMENT_ACTIVITY, "dispatchTouchEvent").code()
        assertEquals(TOUCH, touch[0].referenceText())
        assertEquals("the activity and the event", listOf(1, 2), (touch[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })

        assertReelTapHooked(context.method(navigator, "A01").code(), decision = 1, navigatorRegister = 5)
        assertPlayButtonHooked(context.method(lambdas, "invoke").code(), eventRegister = 6)
        // Each stub, before its first return, reaches one step toward the state of the reel on screen.
        assertEquals(listOf(navigator, "$navigator->A03:$function0", "$function0->invoke()$objectType"), filled(context, "controllerOf"))
        assertEquals(listOf(controller, "$controller->A0f()$holder"), filled(context, "holderOf"))
        assertEquals(listOf(controller, "$controller->A0R:$lookup"), filled(context, "playersOf"))
        assertEquals(listOf(lookup, holder, "$lookup->A01($holder)$reelPlayer"), filled(context, "playerFor"))
        assertEquals(listOf(reelPlayer, "$reelPlayer->Cyy()$state"), filled(context, "stateOf"))
        assertStoryReleaseHooked(context.method(storyPlayer, "Gk2").code(), "$storyPlayer->A0W:Z", flag = 0, playerRegister = 7)
        assertEquals(listOf(storyPlayer, "$storyPlayer->A0E:$groot"), filled(context, GROOT_OF, STORY_PLAYER_READER))
        assertTrue("the player over IgVideoPlayerImpl", context.method(storyPlayerOther, "Gk2").code().none { it.referenceText() == RESUME_HELD_STORY })
        val scroll = context.method(scrollerType, "A03").code()
        assertAutoScrollHooked(scroll, "the auto scroll")
        val pagerRead = scroll[scroll.indexOfFirst { it.referenceText() == AUTO_SCROLLED } - 2] as TwoRegisterInstruction
        assertEquals("the second read of the pager, off the scroller in v8", listOf("$scrollerBase->A02:$pager", 8), listOf((pagerRead as ReferenceInstruction).reference.toString(), pagerRead.registerB))
        assertTrue("Up next's own move to the next reel", context.method(upNext, "A00").code().none { it.referenceText() == AUTO_SCROLLED })
    }

    /**
     * The extension hears of the auto scroller's move right in front of the pager's call, which comes
     * straight after its null check of the pager it just read, and nothing jumps to the hook or the call.
     */
    private fun assertAutoScrollHooked(code: List<Instruction>, what: String) {
        val hook = code.indices.single { code[it].referenceText() == AUTO_SCROLLED }
        assertEquals("$what: the hook names no register", 0, (code[hook] as FiveRegisterInstruction).registerCount)
        val move = code[hook + 1]
        val moved = move.referenceText()!!
        assertEquals("$what: right in front of a virtual call", Opcode.INVOKE_VIRTUAL, move.opcode)
        assertTrue("$what: $moved takes nothing and returns nothing", moved.endsWith("()V"))
        val pagerRegister = (move as FiveRegisterInstruction).registerC
        assertEquals(
            "$what: right after the pager's null check", listOf(Opcode.IF_EQZ, pagerRegister),
            listOf(code[hook - 1].opcode, (code[hook - 1] as OneRegisterInstruction).registerA),
        )
        assertEquals("$what: whose no lands past the move", hook + 2, code.target(hook - 1))
        val read = code[hook - 2]
        assertEquals("$what: and the pager's read", listOf(Opcode.IGET_OBJECT, pagerRegister), listOf(read.opcode, (read as TwoRegisterInstruction).registerA))
        assertEquals("$what: of the field the move calls on", moved.substringBefore("->"), ((read as ReferenceInstruction).reference as FieldReference).type)
        code.indices.filter { code[it] is OffsetInstruction && code[it].opcode !in setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA) }
            .forEach { assertTrue("$what: the branch at $it lands on the hook or the move", code.target(it) !in setOf(hook, hook + 1)) }
    }

    /** Each auto scroller the patch can't hook safely fails at patch time with what it found, before anything is written. */
    @Test
    fun aReelsAutoScrollerThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Scroller(secondMove = true) to "expected one method marked $SCROLL_TO_NEXT_REEL, found 2",
            Scroller(instance = true) to "isn't static void ($scrollerType, boolean)",
            Scroller(extendsObject = true) to "extends no class of Instagram's",
            Scroller(secondPagerField = true) to "its pager, found 2",
            Scroller(moveTakesAnInt = true) to "the move, found 0",
            Scroller(secondMoveCall = true) to "the move, found 2",
            Scroller(moveOnOther = true) to "doesn't move the pager it reads",
            Scroller(pagerOffAnother = true) to "doesn't move the pager it reads",
            Scroller(jumpToMove = true) to "jumps straight to the move",
            Scroller(jumpBetween = true) to "between the pager's read",
            Scroller(scrollerWrittenOver = true) to "writes over parameter 0 (v8)",
        )
        for ((scroller, expected) in cases) {
            val context = PatchContexts.of(classes(scroller = scroller))
            val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
            assertTrue("$scroller: ${failure.message}", failure.message!!.startsWith("$PATCH: the Reels auto scroller: "))
            assertTrue("$scroller: ${failure.message}", failure.message!!.contains(expected))
            assertUntouched(context)
        }
    }

    /**
     * playInternal's hook tells Reels apart by the player's logger, so a build where the Reels
     * viewer's logger isn't the one class made with its config, or where the player keeps more
     * than one logger of that type, stops the patch before anything changes.
     */
    @Test
    fun aReelsLoggerThePatchCantTellFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(reelsLoggers = 0) to "found 0",
            classes(reelsLoggers = 2) to "found 2",
            classes(twoLoggerFields = true) to "more than one final field of $logger",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("$PATCH: ") && failure.message!!.contains(expected))
            assertUntouched(context)
        }
        assertEquals("the Reels viewer's logger", reelsLogger, PatchContexts.of(classes()).findReelsLogger(player).type)
    }

    /**
     * A build with no auto scroller marker, or with it renamed, still gets every other hook, and the
     * patch log says auto scroll won't start the reel it moves to. Nothing goes into the method that
     * lost its marker.
     */
    @Test
    fun withoutTheAutoScrollerMarkerTheRestIsHookedAndTheLogSaysSo() {
        for (marker in listOf(null, "ClipsAutoScrollerImpl_scrollToNextReel")) {
            val context = PatchContexts.of(classes(scroller = Scroller(marker = marker)))
            val warnings = PatchLogCapture.warnings { context.holdStartsWithoutATap() }
            assertEquals("$marker: one warning: $warnings", 1, warnings.size)
            assertTrue("$marker: ${warnings.single()}", warnings.single().startsWith("$PATCH: no method carries") && SCROLL_TO_NEXT_REEL in warnings.single())
            assertTrue("$marker: the move", context.method(scrollerType, "A03").code().none { it.referenceText() == AUTO_SCROLLED })
            assertGateFirst("$marker: playInternal", context.method(player, "A0J").code(), ALLOW_START, prefix = playInternalPrefix)
            assertReelTapHooked(context.method(navigator, "A01").code(), decision = 1, navigatorRegister = 5)
            assertStoryReleaseHooked(context.method(storyPlayer, "Gk2").code(), "$storyPlayer->A0W:Z", flag = 0, playerRegister = 7)
        }
    }

    /**
     * The flag the story player's resume reads goes past the extension, with the player, between
     * the read and the branch testing it, and the extension's answer replaces it.
     */
    private fun assertStoryReleaseHooked(code: List<Instruction>, flagField: String, flag: Int, playerRegister: Int, what: String = "the story release") {
        val hook = code.indices.single { code[it].referenceText() == RESUME_HELD_STORY }
        assertEquals("$what: right after the flag's read", listOf(Opcode.IGET_BOOLEAN, flagField), listOf(code[hook - 1].opcode, code[hook - 1].referenceText()))
        assertEquals("$what: the flag and the player", listOf(flag, playerRegister), (code[hook] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals(
            "$what: the answer replaces the flag, and the branch tests it", listOf(Opcode.MOVE_RESULT, flag, Opcode.IF_EQZ, flag),
            listOf(code[hook + 1].opcode, (code[hook + 1] as OneRegisterInstruction).registerA, code[hook + 2].opcode, (code[hook + 2] as OneRegisterInstruction).registerA),
        )
    }

    /** Each story player the patch can't hook safely fails at patch time with what it found, before anything is written. */
    @Test
    fun aStoryPlayerThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Story(fragment = false) to "this build has no $REEL_VIEWER_FRAGMENT",
            Story(secondGrootPlayer = true) to "expected one $storyPlayerInterface holding one $groot, found 2",
            Story(pauseSetsFlag = false) to "expected one flag $storyPlayer->Gk2 reads",
            Story(resumeClearsFlag = false) to "expected one flag $storyPlayer->Gk2 reads",
            Story(thisWrittenOver = true) to "writes over this (v7)",
            Story(privateGroot = true) to "can't reach $storyPlayer->A0E",
        )
        for ((story, expected) in cases) {
            val context = PatchContexts.of(classes(story = story))
            val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
            assertTrue("$story: ${failure.message}", failure.message!!.contains(expected))
            assertUntouched(context)
        }
    }

    /**
     * The Litho play button's click tells the extension right after it hands its start to
     * Instagram, with the click event, and a branch from the click's other cases to the instruction
     * after that call still skips the hook.
     */
    private fun assertPlayButtonHooked(code: List<Instruction>, eventRegister: Int, what: String = "the play button's click") {
        val call = code.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC && it.referenceText()?.contains("(Landroid/content/Context;") == true }
        assertTrue("$what: the start's call", call >= 0)
        val hook = code[call + 1]
        assertEquals("$what: the hook right after the call", PLAY_BUTTON_TAPPED, hook.referenceText())
        assertEquals("$what: the click event", listOf(eventRegister, 1), (hook as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("$what: one hook", 1, code.count { it.referenceText() == PLAY_BUTTON_TAPPED })
        code.indices.filter { code[it] is OffsetInstruction && code[it].opcode != Opcode.PACKED_SWITCH && code[it].opcode != Opcode.SPARSE_SWITCH }
            .forEach { assertTrue("$what: the branch at $it lands on the hook", code.target(it) != call + 1) }
    }

    /**
     * A play button the patch can't pick out, or a click that writes over its event before the
     * call, fails the patch before anything changes.
     */
    @Test
    fun aPlayButtonThePatchCantHookFailsBeforeAnythingChanges() {
        for ((case, classes) in listOf(
            "no button" to classes(buttons = 0),
            "two buttons" to classes(buttons = 2),
            "two starts" to classes(twoStarts = true),
            "event written over" to classes(clobberedEvent = true),
            "start read twice" to classes(start = Start.READ_TWICE),
            "start written over before the call" to classes(start = Start.WRITTEN_OVER),
            "another Function0 handed over" to classes(start = Start.OTHER),
            "two calls" to classes(start = Start.TWO_CALLS),
            "no binder" to classes(binder = false),
            "a state that isn't an enum" to classes(enumState = false),
        )) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(case, PatchException::class.java) { context.holdStartsWithoutATap() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith(PATCH))
            assertUntouched(context)
        }
    }

    /** What the filled stub [name] names before its first return. Its own body stays behind it, unreached. */
    private fun filled(context: BytecodePatchContext, name: String, reader: String = REEL_STATE_READER): List<String> =
        context.method(reader, name).code().takeWhile { it.opcode != Opcode.RETURN_OBJECT }.mapNotNull { it.referenceText() }

    /** The decision goes past the extension, with the navigator, just before the branch to the pause path. */
    private fun assertReelTapHooked(code: List<Instruction>, decision: Int, navigatorRegister: Int, what: String = "the Reels tap") {
        val pausePath = code.indexOfFirst { it.referenceText() == CLIPS_PAUSE }
        val branch = code.indices.single { code[it].opcode == Opcode.IF_EQZ && code.target(it) == pausePath }
        assertEquals("$what: the hook", RESUME_ON_TAP, code[branch - 2].referenceText())
        assertEquals(
            "$what: the decision and the navigator", listOf(decision, navigatorRegister),
            (code[branch - 2] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) },
        )
        assertEquals(
            "$what: the answer replaces the decision", listOf(Opcode.MOVE_RESULT, decision),
            listOf(code[branch - 1].opcode, (code[branch - 1] as OneRegisterInstruction).registerA),
        )
        assertEquals("$what: the branch tests it", decision, (code[branch] as OneRegisterInstruction).registerA)
    }

    /** Each Reels tap the patch can't hook safely fails at patch time with what it found, before anything is written. */
    @Test
    fun aReelsTapThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Reel(secondPauseBranch = true) to "one branch to the pause path",
            Reel(decision = "p3") to "in a parameter's register",
            Reel(decision = "v16") to "past v15",
            Reel(objectDecision = true) to "v1 holds something other than a boolean",
            Reel(pauseCall = "A0d") to "never pauses through $controller",
            Reel(privateSupplier = true) to "can't reach $navigator->A03",
            Reel(privateHolder = true) to "can't reach $holder",
            Reel(secondHolderAccessor = true) to "one holder accessor on $controller",
            Reel(playerIsInterface = false) to "$reelPlayer isn't an interface",
            Reel(stateNames = listOf("IDLE", "PLAYING")) to "one state accessor on $reelPlayer",
        )
        for ((reel, expected) in cases) {
            val context = PatchContexts.of(classes(reel = reel))
            val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
            assertTrue("$reel: ${failure.message}", failure.message!!.contains(expected))
            assertUntouched(context)
        }
    }

    /** A playInternal that reads its IgGrootPlayer from anything but its player can't be gated first thing. */
    @Test
    fun aGrootReadFromElsewhereFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(grootFromElsewhere = true))
        val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
        assertTrue(failure.message!!, failure.message!!.contains("other than its player"))
        assertUntouched(context)
    }

    @Test
    fun twoPausesFailBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(secondPause = true))
        val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
        assertTrue(failure.message!!, failure.message!!.contains("pause"))
        assertUntouched(context)
    }

    @Test
    fun withoutTheActivityNothingChanges() {
        val context = PatchContexts.of(classes(activity = false))
        val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
        assertTrue(failure.message!!, failure.message!!.contains(FRAGMENT_ACTIVITY))
        assertUntouched(context)
    }

    /**
     * In each declared build, playInternal, IgGrootPlayer's play, pause and prepare, the autoplay
     * check and the touch dispatch are each found once, and each gets its hook first.
     */
    @Test
    fun eachDeclaredBuildGatesItsPlayers() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                // The Reels tap's navigator and controller, then the classes the controller's pause
                // names, then the classes their methods return, which reaches the player interface
                // and its state enum.
                val reel = FixtureDex.classesHolding(bundle, CLIPS_PAUSE) + FixtureDex.classesHolding(bundle, pauseCurrentPlayerLog)
                val pauses = reel.flatMap { it.methods }.filter { pauseCurrentPlayerLog in it.strings() }
                val near = FixtureDex.classes(bundle, pauses.flatMap { it.namedTypes() }.toSet()).values.toList()
                val returned = FixtureDex.classes(bundle, near.flatMap { it.methods }.map { it.returnType }.toSet()).values.toList()
                // The story viewer, and every class implementing its video player's interface.
                val viewer = FixtureDex.classes(bundle, setOf(REEL_VIEWER_FRAGMENT)).values.toList()
                val storyInterface = viewer.single().fields.single { it.name == VIDEO_PLAYER_FIELD }.type
                val storyPlayers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(storyPlayers) { storyInterface in it.interfaces } }
                // Every class made with a ClipsViewerConfig first, the Reels viewer's video logger among them.
                val madeWithConfig = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    dex.classes.filterTo(madeWithConfig) { classDef ->
                        classDef.methods.any { it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == CLIPS_VIEWER_CONFIG }
                    }
                }
                val classes = (
                    FixtureDex.classesHolding(bundle, PLAY_INTERNAL) + FixtureDex.classesHolding(bundle, GROOT_PREPARE) +
                        FixtureDex.classesHolding(bundle, AUTOPLAY_CHECKER.last()) +
                        listOfNotNull(FixtureDex.classes(bundle, setOf(FRAGMENT_ACTIVITY))[FRAGMENT_ACTIVITY]) +
                        reel + near + returned + playButtonClasses(bundle) + viewer + storyPlayers + scrollToNextReelClasses(bundle) + madeWithConfig +
                        ExtensionDex.classDef(TAP_TO_PLAY) + ExtensionDex.classDef(REEL_STATE_READER) + ExtensionDex.classDef(STORY_PLAYER_READER)
                    ).distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val hooks = context.findPlayerHooks()
                val reelTap = context.findReelTap()
                val storyRelease = context.findStoryRelease(hooks.prepare.definingClass)
                val flagField = storyRelease.resume.code()[storyRelease.read].referenceText()!!
                val autoScroll = context.findAutoScroll()
                    ?: throw AssertionError("${bundle.name}: no method carries $SCROLL_TO_NEXT_REEL")
                // The call the patch found by its shape is the Reels pager's smooth scroll to the next item.
                val moveCall = (autoScroll.move.code()[autoScroll.call] as ReferenceInstruction).reference as MethodReference
                val pagerClass = FixtureDex.classes(bundle, setOf(moveCall.definingClass)).values.single()
                val target = pagerClass.methods.single { it.name == moveCall.name && it.parameterTypes.isEmpty() && it.returnType == "V" }
                assertTrue("${bundle.name}: ${moveCall.definingClass} holds a ViewPager2", pagerClass.fields.any { it.type == viewPager2 })
                assertEquals("${bundle.name}: $moveCall", listOf(smoothScrollToNextItem), target.markers())
                assertEquals("${bundle.name}: the player's IgGrootPlayer field", hooks.prepare.definingClass, hooks.grootField.type)
                assertEquals("${bundle.name}: play and prepare are one class's", hooks.prepare.definingClass, hooks.play.definingClass)
                assertEquals("${bundle.name}: pause is theirs too", hooks.prepare.definingClass, hooks.pause.definingClass)
                // The logger is the player's own, kept from its making, and the Reels viewer's keeps its config.
                assertEquals("${bundle.name}: the logger field", hooks.playInternal.definingClass, hooks.reelsLogger.field.definingClass)
                val loggerClass = madeWithConfig.single { it.type == hooks.reelsLogger.type }
                assertEquals("${bundle.name}: the Reels viewer's logger extends the field's type", hooks.reelsLogger.field.type, loggerClass.superclass)
                assertTrue("${bundle.name}: and keeps its config", loggerClass.fields.any { it.type == CLIPS_VIEWER_CONFIG })

                context.holdStartsWithoutATap()

                fun after(method: Method) = context.method(method.definingClass, method.name, method.parameterTypes.map(Any::toString)).code()
                val internal = after(hooks.playInternal)
                assertEquals("${bundle.name}: playInternal", hooks.grootField.toString(), internal[0].referenceText())
                assertGateFirst("${bundle.name}: playInternal", internal, ALLOW_START, prefix = playInternalPrefix)
                assertEquals("${bundle.name}: the logger read", hooks.reelsLogger.field.toString(), internal[1].referenceText())
                assertEquals("${bundle.name}: the logger check", hooks.reelsLogger.type, internal[2].referenceText())
                assertGateFirst("${bundle.name}: play", after(hooks.play), ALLOW_DIRECT_START)
                assertEquals("${bundle.name}: pause", PAUSED, after(hooks.pause)[0].referenceText())
                assertEquals("${bundle.name}: prepare", REBOUND, after(hooks.prepare)[0].referenceText())
                val check = after(hooks.checker)
                val returns = check.indices.filter { check[it].opcode == Opcode.RETURN }
                assertTrue("${bundle.name}: the check returns", returns.isNotEmpty())
                returns.forEach { assertEquals("${bundle.name}: the check's return at $it", AUTOPLAY_ALLOWED, check[it - 2].referenceText()) }
                assertEquals("${bundle.name}: touch", TOUCH, after(hooks.touch)[0].referenceText())
                // `this` is the first register past the locals: the count less the three declared parameters and itself.
                assertReelTapHooked(after(reelTap.tap), reelTap.decision, reelTap.tap.implementation!!.registerCount - 4, "${bundle.name}: the Reels tap")
                assertEquals("${bundle.name}: the state stub", reelTap.state.toString(), filled(context, "stateOf").last())
                assertEquals("${bundle.name}: the controller stub", "$function0->invoke()$objectType", filled(context, "controllerOf").last())
                // `this` is the first register past the locals: the count less the two declared parameters and itself.
                assertStoryReleaseHooked(
                    after(storyRelease.resume), flagField, storyRelease.flag, storyRelease.resume.implementation!!.registerCount - 3,
                    "${bundle.name}: the story release",
                )
                assertEquals(
                    "${bundle.name}: the story player stub", listOf(storyRelease.resume.definingClass, storyRelease.groot.toString()),
                    filled(context, GROOT_OF, STORY_PLAYER_READER),
                )
                val click = hooks.playButton.method
                assertPlayButtonHooked(after(click), click.implementation!!.registerCount - 1, "${bundle.name}: the play button's click")
                val scroll = after(autoScroll.move)
                assertAutoScrollHooked(scroll, "${bundle.name}: the auto scroll")
                assertEquals("${bundle.name}: the hook is in front of the call found", moveCall.toString(), scroll[scroll.indexOfFirst { it.referenceText() == AUTO_SCROLLED } + 1].referenceText())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertGateFirst(what: String, code: List<Instruction>, hook: String, prefix: List<Opcode> = emptyList()) {
        val gate = listOf(
            if (prefix.isEmpty()) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC,
            Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.RETURN_VOID,
        )
        assertEquals("$what: the gate's opcodes", prefix + gate, code.take(prefix.size + gate.size).map { it.opcode })
        assertEquals("$what: the hook called", hook, code[prefix.size].referenceText())
        val branch = prefix.size + 2
        assertEquals("$what: a yes lands on the original first instruction", prefix.size + gate.size, code.target(branch))
    }

    private fun assertUntouched(context: BytecodePatchContext) {
        val hooks = setOf(ALLOW_START, ALLOW_DIRECT_START, PAUSED, REBOUND, AUTOPLAY_ALLOWED, TOUCH, RESUME_ON_TAP, PLAY_BUTTON_TAPPED, RESUME_HELD_STORY, AUTO_SCROLLED)
        for ((type, name) in listOf(player to "A0J", groot to "A0X", groot to "A0V", groot to "A0S", checker to "A01", navigator to "A01", lambdas to "invoke")) {
            assertTrue("$type->$name changed", context.method(type, name).code().none { it.referenceText() in hooks })
        }
        for ((name, _) in REEL_STUBS) {
            assertEquals("the stub $name was filled", Opcode.SGET_OBJECT, context.method(REEL_STATE_READER, name).code().first().opcode)
        }
        assertEquals("the stub $GROOT_OF was filled", Opcode.SGET_OBJECT, context.method(STORY_PLAYER_READER, GROOT_OF).code().first().opcode)
        context.classDefByOrNull(storyPlayer)?.let { player ->
            assertTrue("$storyPlayer->Gk2 changed", player.methods.first { it.name == "Gk2" }.code().none { it.referenceText() in hooks })
        }
        for (type in listOf(scrollerType, "Lfixture/OtherScroller;", upNext)) {
            context.classDefByOrNull(type)?.methods?.forEach { method ->
                assertTrue("$type->${method.name} changed", method.code().none { it.referenceText() in hooks })
            }
        }
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.first { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.strings(): Set<String> =
        code().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()

    /** The classes [this] names: its return, and the fields' and calls' types and owners. */
    private fun Method.namedTypes(): Set<String> = (
        listOf(returnType) + code().mapNotNull { (it as? ReferenceInstruction)?.reference }.flatMap { reference ->
            when (reference) {
                is FieldReference -> listOf(reference.definingClass, reference.type)
                is MethodReference -> listOf(reference.definingClass, reference.returnType)
                else -> emptyList()
            }
        }
        ).filter { it.startsWith("L") }.toSet()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** The index [branch] at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    /**
     * Every class in [bundle] with a method carrying a purge marker that ends in "_scrollToNextReel":
     * the auto scroller, and on 449 Up next's move as well, which the patch has to tell apart.
     */
    private fun scrollToNextReelClasses(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it.startsWith("android_purge_") && it.endsWith("_scrollToNextReel") }) return@forEach
            dex.classes.filterTo(found) { classDef -> classDef.methods.any { method -> method.markers().any { it.endsWith("_scrollToNextReel") } } }
        }
        return found.map { ImmutableClassDef.of(it) }
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The play button's binders, its state, the Litho button and its click in [bundle]: the classes
     * holding the binders' string, the enum they all take, then every class declaring a field of
     * that state and a Function0, then every class reading one of those classes' Function0 fields.
     */
    private fun playButtonClasses(bundle: File): List<ClassDef> {
        val binders = FixtureDex.classesHolding(bundle, PLAY_BUTTON_BINDER)
        val shared = binders.flatMap { it.methods }.filter { PLAY_BUTTON_BINDER in it.strings() }
            .map { it.parameterTypes.map(Any::toString).toSet() }.reduce { all, next -> all intersect next }
        val stateClass = FixtureDex.classes(bundle, shared).values.single { it.superclass == "Ljava/lang/Enum;" }
        val state = stateClass.type
        val buttons = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(buttons) { classDef ->
                classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }.let { state in it && function0 in it }
            }
        }
        val starts = buttons.flatMap { classDef -> classDef.fields.filter { it.type == function0 }.map { "${classDef.type}->${it.name}:$function0" } }.toSet()
        val clicks = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.fieldSection.none { it.toString() in starts }) return@forEach
            dex.classes.filterTo(clicks) { classDef ->
                classDef.methods.any { method -> method.code().any { it.opcode == Opcode.IGET_OBJECT && it.referenceText() in starts } }
            }
        }
        return binders + stateClass + buttons + clicks
    }

    private fun classes(
        grootFromElsewhere: Boolean = false,
        secondPause: Boolean = false,
        activity: Boolean = true,
        reel: Reel = Reel(),
        buttons: Int = 1,
        twoStarts: Boolean = false,
        clobberedEvent: Boolean = false,
        binder: Boolean = true,
        enumState: Boolean = true,
        start: Start = Start.HANDED_OVER,
        story: Story = Story(),
        scroller: Scroller = Scroller(),
        reelsLoggers: Int = 1,
        twoLoggerFields: Boolean = false,
    ): List<ClassDef> {
        val videoPlayer = classDef(
            player,
            listOf(
                // playInternal: static, (player, reason, playAfterSeek, fromPrepare), two locals.
                method(player, "A0J", listOf(player, string, "Z", "Z"), "V", 6, static = true, body = """
                    const-string v0, "$PLAY_INTERNAL"
                    ${if (grootFromElsewhere) "sget-object v1, $player->shared:$groot" else "iget-object v1, p0, $player->groot:$groot"}
                    if-eqz v1, :end
                    invoke-virtual { v1, p1, p2 }, $groot->A0X(Ljava/lang/String;Z)V
                    ${if (grootFromElsewhere) "iget-object v1, v0, $player->groot:$groot" else ""}
                    :end
                    return-void
                """),
            ),
            listOfNotNull(
                ImmutableField(player, "groot", groot, AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(player, "shared", groot, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null),
                ImmutableField(player, "logger", logger, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
                // A logger the player isn't made with: not final, so not the one it keeps.
                ImmutableField(player, "lastLogger", logger, AccessFlags.PUBLIC.value, null, null, null),
                if (twoLoggerFields) ImmutableField(player, "otherLogger", logger, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null) else null,
            ),
        )
        // The Reels viewer's logger keeps the ClipsViewerConfig it's made with; the feed's is made
        // without one, and the helper keeps one but is no logger.
        val madeWithConfig = { type: String, superclass: String ->
            classDef(
                type,
                listOf(
                    method(type, "<init>", listOf(CLIPS_VIEWER_CONFIG, session), "V", 3, static = false, body = """
                        invoke-direct { p0 }, $superclass-><init>()V
                        iput-object p1, p0, $type->config:$CLIPS_VIEWER_CONFIG
                        return-void
                    """),
                ),
                listOf(ImmutableField(type, "config", CLIPS_VIEWER_CONFIG, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
                superclass,
            )
        }
        val loggers = listOf(
            classDef(logger, emptyList()),
            classDef(
                feedLogger,
                listOf(
                    method(feedLogger, "<init>", listOf(session), "V", 2, static = false, body = """
                        invoke-direct { p0 }, $logger-><init>()V
                        return-void
                    """),
                ),
                superclass = logger,
            ),
            madeWithConfig(clipsHelper, objectType),
        ) + (0 until reelsLoggers).map { madeWithConfig(if (it == 0) reelsLogger else "Lfixture/ClipsVideoLogger$it;", logger) }
        val pause = { name: String ->
            method(groot, name, listOf(string), "V", 3, static = false, body = """
                iget-object v0, p0, $groot->core:$core
                invoke-virtual { v0, p1 }, $core->A0b(Ljava/lang/String;)V
                return-void
            """)
        }
        val grootPlayer = classDef(
            groot,
            listOfNotNull(
                method(groot, "A0S", listOf("Landroid/view/ViewGroup;", "Ljava/lang/Object;", "Ljava/lang/Integer;"), "V", 25, static = false, body = """
                    const-string v0, "$GROOT_PREPARE"
                    return-void
                """),
                method(groot, "A0X", listOf(string, "Z"), "V", 4, static = false, body = """
                    const-string v0, "retry"
                    const-string v0, "play_after_recovery"
                    return-void
                """),
                pause("A0V"),
                if (secondPause) pause("A0U") else null,
                method(groot, "A0W", listOf(string), "V", 3, static = false, body = """
                    const-string v0, "current_watching_module"
                    return-void
                """),
            ),
            listOf(ImmutableField(groot, "core", core, AccessFlags.PUBLIC.value, null, null, null)),
        )
        val autoplayChecker = classDef(
            checker,
            listOf(
                method(checker, "A01", emptyList(), "Z", 3, static = false, body = """
                    const-string v0, "VideoAutoplayChecker"
                    const-string v0, "zero_rating_or_data_saver"
                    const/4 v1, 0x1
                    if-eqz v1, :done
                    const/4 v1, 0x0
                    return v1
                    :done
                    return v1
                """),
            ),
        )
        val fragmentActivity = classDef(
            FRAGMENT_ACTIVITY,
            listOf(
                method(FRAGMENT_ACTIVITY, "dispatchTouchEvent", listOf("Landroid/view/MotionEvent;"), "Z", 3, static = false, body = """
                    const/4 v0, 0x0
                    return v0
                """),
            ),
        )
        return listOfNotNull(videoPlayer, grootPlayer, autoplayChecker, if (activity) fragmentActivity else null) + loggers +
            reelClasses(reel) + playButtonClasses(buttons, twoStarts, clobberedEvent, binder, enumState, start) + storyClasses(story) +
            scrollerClasses(scroller) +
            ExtensionDex.classDef(TAP_TO_PLAY) + ExtensionDex.classDef(REEL_STATE_READER) + ExtensionDex.classDef(STORY_PLAYER_READER)
    }

    /**
     * The view-based button's two binders, holding its string and both taking the button state, an
     * enum; the Litho button, holding that state and its start; and a lambda class whose invoke is the
     * button's click on one arm, handing the start to the data saver check, and another click on
     * the other, which branches to the instruction after that call. The event is p1, v6.
     */
    private fun playButtonClasses(
        buttons: Int,
        twoStarts: Boolean,
        clobberedEvent: Boolean,
        binder: Boolean,
        enumState: Boolean,
        start: Start,
    ): List<ClassDef> {
        val binderClass = classDef(
            this.binder,
            listOf(
                method(this.binder, "A00", listOf("Landroid/content/Context;", "Lfixture/Module;", session, "Lfixture/Listener;", buttonState, "Lfixture/ButtonHolder;", "Z"), "V", 9, static = true, body = """
                    const-string v0, "$PLAY_BUTTON_BINDER"
                    return-void
                """),
                method(this.binder, "A01", listOf("Lfixture/Module;", session, "Lfixture/Listener;", buttonState, "Lfixture/ButtonHolder;", "Z"), "V", 8, static = true, body = """
                    const-string v0, "$PLAY_BUTTON_BINDER"
                    return-void
                """),
            ),
        )
        val stateClass = ImmutableClassDef(
            buttonState, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (enumState) AccessFlags.ENUM.value else 0),
            if (enumState) "Ljava/lang/Enum;" else "Ljava/lang/Object;", null, null, null, emptyList(), emptyList(),
        )
        val holderClass = classDef("Lfixture/ButtonHolder;", emptyList())
        val field = { owner: String, name: String, type: String -> ImmutableField(owner, name, type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null) }
        val buttonClasses = (0 until buttons).map { i ->
            val type = if (i == 0) button else "Lfixture/OtherPlayButton$i;"
            classDef(
                type,
                emptyList(),
                listOfNotNull(
                    field(type, "A00", "Lfixture/Module;"), field(type, "A01", session), field(type, "A02", function0),
                    field(type, "A04", buttonState), if (twoStarts) field(type, "A05", function0) else null,
                ),
            )
        }
        val click = method(lambdas, "invoke", listOf(objectType), objectType, 7, static = false, body = """
            iget v0, p0, $lambdas->kind:I
            if-eqz v0, :other
            ${if (clobberedEvent) "const/4 p1, 0x0" else ""}
            iget-object v0, p0, $lambdas->A01:$objectType
            check-cast v0, $button
            iget-object v1, v0, $button->A01:$session
            iget-object v2, v0, $button->A00:Lfixture/Module;
            iget-object v3, v0, $button->A02:$function0
            ${if (start == Start.WRITTEN_OVER) "const/4 v3, 0x0" else ""}
            const/4 v0, 0x0
            invoke-static { v0, v2, v1, ${if (start == Start.OTHER) "v4" else "v3"} }, $dataSaver
            ${if (start == Start.TWO_CALLS) "invoke-static { v0, v2, v1, v3 }, $dataSaver" else ""}
            :done
            const/4 v0, 0x0
            return-object v0
            :other
            move-object v5, p1
            ${if (start == Start.READ_TWICE) "iget-object v4, v0, $button->A02:$function0" else ""}
            goto :done
        """)
        val lambdaClass = classDef(lambdas, listOf(click), listOf(field(lambdas, "kind", "I"), field(lambdas, "A01", objectType)))
        return listOfNotNull(if (binder) binderClass else null) + stateClass + holderClass + buttonClasses + lambdaClass
    }

    /** How the play button's click hands its start to the data saver check. */
    private enum class Start {
        /** As 449 does: read once, straight into the call's last argument. */
        HANDED_OVER,
        READ_TWICE,
        WRITTEN_OVER,
        OTHER,
        TWO_CALLS,
    }

    /** How a test's Reels tap stand-ins differ from 449's. */
    private data class Reel(
        val secondPauseBranch: Boolean = false,
        /** The register the tap decides in, v1 in 449. */
        val decision: String = "v1",
        /** The decision's register holds an object before the branch. */
        val objectDecision: Boolean = false,
        /** The controller method the pause path calls, A0c in 449. */
        val pauseCall: String = "A0c",
        val privateSupplier: Boolean = false,
        val privateHolder: Boolean = false,
        val secondHolderAccessor: Boolean = false,
        val playerIsInterface: Boolean = true,
        val stateNames: List<String> = PLAYER_STATES,
    )

    /**
     * PauseAndMuteNavigator's tap, shaped like 449's: nine registers, the decision in v1, `this` in
     * v5, the resume path first and the pause path after the branch. Then the
     * ClipsVideoPlayerController it reaches through a Function0 field, whose pause looks up the
     * player for the holder on screen, the holder, the player interface, and its state enum.
     */
    private fun reelClasses(reel: Reel): List<ClassDef> {
        val d = reel.decision
        val wide = d == "v16"
        // Past v15, `this` is too, so the stand-in reads its field through a copy in v5.
        val self = if (wide) "v5" else "p0"
        val setDecision = { value: Int ->
            when {
                reel.objectDecision -> "iget-object $d, $self, $navigator->A03:$function0"
                wide -> "const/16 $d, 0x$value"
                else -> "const/4 $d, 0x$value"
            }
        }
        val tap = classDef(
            navigator,
            listOf(
                method(navigator, "A01", listOf("Landroid/view/View;", objectType, objectType), "V", if (wide) 24 else 9, static = false, body = """
                    ${if (wide) "move-object/from16 v5, p0" else ""}
                    const-string v0, "android_purge_26_q3_$TOGGLE_PAUSE"
                    ${if (d == "p3") "" else setDecision(0)}
                    if-eqz p3, :decided
                    ${if (d == "p3") "" else setDecision(1)}
                    :decided
                    ${if (reel.secondPauseBranch) "if-eqz p2, :pause" else ""}
                    if-eqz $d, :pause
                    const-wide/16 v0, 0x0
                    return-void
                    :pause
                    const-string v3, "$CLIPS_PAUSE"
                    iget-object v0, $self, $navigator->A03:$function0
                    invoke-interface { v0 }, $function0->invoke()$objectType
                    move-result-object v2
                    check-cast v2, $controller
                    const/4 v0, 0x1
                    invoke-virtual { v2, v3, v0, v0 }, $controller->${reel.pauseCall}(Ljava/lang/String;ZZ)I
                    return-void
                """),
            ),
            listOf(ImmutableField(navigator, "A03", function0, if (reel.privateSupplier) AccessFlags.PRIVATE.value else AccessFlags.PUBLIC.value, null, null, null)),
        )
        val clipsController = classDef(
            controller,
            listOf(
                method(controller, "A0c", listOf(string, "Z", "Z"), "I", 7, static = false, body = """
                    const-string v0, "android_purge_26_q3_$PAUSE_CURRENT_PLAYER"
                    invoke-virtual { p0 }, $controller->A0f()$holder
                    move-result-object v2
                    if-eqz v2, :none
                    iget-object v0, p0, $controller->A0R:$lookup
                    invoke-virtual { v0, v2 }, $lookup->A01($holder)$reelPlayer
                    move-result-object v3
                    if-eqz v3, :none
                    invoke-interface { v3, p1 }, $reelPlayer->GS8(Ljava/lang/String;)I
                    move-result v0
                    return v0
                    :none
                    const/4 v0, 0x0
                    return v0
                """),
                method(controller, "A0f", emptyList(), holder, 2, static = false, body = """
                    const/4 v0, 0x0
                    return-object v0
                """),
            ) + if (reel.secondHolderAccessor) listOf(method(controller, "A0g", emptyList(), holder, 2, static = false, body = """
                    const/4 v0, 0x0
                    return-object v0
                """)) else emptyList(),
            listOf(ImmutableField(controller, "A0R", lookup, AccessFlags.PUBLIC.value, null, null, null)),
        )
        val players = classDef(
            lookup,
            listOf(
                method(lookup, "A01", listOf(holder), reelPlayer, 3, static = false, body = """
                    const/4 v0, 0x0
                    return-object v0
                """),
            ),
        )
        val holderClass = ImmutableClassDef(
            holder, if (reel.privateHolder) 0 else AccessFlags.PUBLIC.value, objectType, null, null, null, emptyList(), emptyList(),
        )
        val playerInterface = ImmutableClassDef(
            reelPlayer, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value or (if (reel.playerIsInterface) AccessFlags.INTERFACE.value else 0),
            objectType, null, null, null,
            emptyList(),
            listOf(abstractMethod(reelPlayer, "GS8", listOf(string), "I"), abstractMethod(reelPlayer, "Cyy", emptyList(), state)),
        )
        val stateEnum = ImmutableClassDef(
            state, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value, "Ljava/lang/Enum;", null, null, null,
            emptyList(),
            listOf(
                method(state, "<clinit>", emptyList(), "V", 1, static = true, body = reel.stateNames.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void"),
            ),
        )
        return listOf(tap, clipsController, players, holderClass, playerInterface, stateEnum)
    }

    /** How a test's story player stand-ins differ from 449's. */
    private data class Story(
        val fragment: Boolean = true,
        /** A second player over IgGrootPlayer, beside the one 449 has. */
        val secondGrootPlayer: Boolean = false,
        val pauseSetsFlag: Boolean = true,
        val resumeClearsFlag: Boolean = true,
        /** The resume writes over `this` before it reads the flag. */
        val thisWrittenOver: Boolean = false,
        val privateGroot: Boolean = false,
    )

    /**
     * The story viewer, shaped like 449's: its video player field, its pause and resume logging
     * their strings and calling the player, the player interface, the player over IgGrootPlayer with
     * its bound flag, its resume flag and a preparing flag, and the other player over
     * IgVideoPlayerImpl, which the patch leaves alone.
     */
    private fun storyClasses(story: Story): List<ClassDef> {
        val flagField = { owner: String, name: String -> ImmutableField(owner, name, "Z", AccessFlags.PUBLIC.value, null, null, null) }
        val viewer = classDef(
            REEL_VIEWER_FRAGMENT,
            listOf(
                method(REEL_VIEWER_FRAGMENT, "A10", listOf(REEL_VIEWER_FRAGMENT, string), "V", 3, static = true, body = """
                    iget-object v0, p0, $REEL_VIEWER_FRAGMENT->$VIDEO_PLAYER_FIELD:$storyPlayerInterface
                    invoke-interface { v0, p1 }, $storyPlayerInterface->GS9($string)V
                    const-string v1, "$PAUSE_STORY"
                    return-void
                """),
                method(REEL_VIEWER_FRAGMENT, "A11", listOf(REEL_VIEWER_FRAGMENT, string, "Z"), "V", 5, static = true, body = """
                    const-string v1, "${RESUME_STORY[0]}"
                    iget-object v0, p0, $REEL_VIEWER_FRAGMENT->$VIDEO_PLAYER_FIELD:$storyPlayerInterface
                    invoke-interface { v0, p1, p2 }, $storyPlayerInterface->Gk2(${string}Z)V
                    const-string v1, "${RESUME_STORY[1]}"
                    return-void
                """),
            ),
            listOf(ImmutableField(REEL_VIEWER_FRAGMENT, VIDEO_PLAYER_FIELD, storyPlayerInterface, AccessFlags.PUBLIC.value, null, null, null)),
        )
        val playerInterface = ImmutableClassDef(
            storyPlayerInterface, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value or AccessFlags.INTERFACE.value,
            objectType, null, null, null, emptyList(),
            listOf(abstractMethod(storyPlayerInterface, "GS9", listOf(string), "V"), abstractMethod(storyPlayerInterface, "Gk2", listOf(string, "Z"), "V")),
        )
        val self = "p0"
        val implementer = { type: String, heldType: String ->
            ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, objectType, listOf(storyPlayerInterface), null, null,
                listOf(
                    ImmutableField(type, "A0E", heldType, if (story.privateGroot) AccessFlags.PRIVATE.value else AccessFlags.PUBLIC.value, null, null, null),
                    flagField(type, "A0T"), flagField(type, "A0W"), flagField(type, "A0i"),
                ),
                listOf(
                    method(type, "GS9", listOf(string), "V", 6, static = false, body = """
                        const/4 v2, 0x1
                        ${if (story.pauseSetsFlag) "iput-boolean v2, $self, $type->A0W:Z" else ""}
                        return-void
                    """),
                    method(type, "Gk2", listOf(string, "Z"), "V", 10, static = false, body = """
                        ${if (story.thisWrittenOver) "move-object/from16 p0, p0" else ""}
                        const/4 v4, 0x0
                        iget-boolean v0, $self, $type->A0i:Z
                        if-eqz v0, :end
                        iget-boolean v0, $self, $type->A0W:Z
                        if-eqz v0, :end
                        ${if (story.resumeClearsFlag) "iput-boolean v4, $self, $type->A0W:Z" else ""}
                        iget-boolean v0, $self, $type->A0T:Z
                        if-nez v0, :end
                        return-void
                        :end
                        return-void
                    """),
                ),
            )
        }
        return listOfNotNull(
            if (story.fragment) viewer else null,
            playerInterface,
            implementer(storyPlayer, groot),
            implementer(storyPlayerOther, if (story.secondGrootPlayer) groot else player),
        )
    }

    /** How a test's auto scroller stand-ins differ from 449's. */
    private data class Scroller(
        /** The marker after its release, null for none at all. */
        val marker: String? = SCROLL_TO_NEXT_REEL,
        /** A second method elsewhere carrying the same marker. */
        val secondMove: Boolean = false,
        val instance: Boolean = false,
        val extendsObject: Boolean = false,
        /** The move reads a second field the scroller inherits. */
        val secondPagerField: Boolean = false,
        /** The pager's move takes the position. */
        val moveTakesAnInt: Boolean = false,
        /** A second call on the pager taking nothing and returning nothing. */
        val secondMoveCall: Boolean = false,
        /** The move is called on a pager read from the scroller's own field. */
        val moveOnOther: Boolean = false,
        /** The move is called on the pager field read off another scroller the scroller holds. */
        val pagerOffAnother: Boolean = false,
        /** The no-pager path jumps straight to the move. */
        val jumpToMove: Boolean = false,
        /** The no-pager path jumps to the null check between the pager's read and the move. */
        val jumpBetween: Boolean = false,
        /** The move writes over the scroller before it reads the pager the second time. */
        val scrollerWrittenOver: Boolean = false,
    )

    /**
     * The auto scroller's move, shaped like 449's 07SD.A03: static, (scroller, flag), ten registers,
     * the scroller in v8. It reads the pager from the field its superclass keeps first thing, and when
     * there's none it goes back with no item; then it reads the pager again, checks it, moves it on,
     * and writes over the scroller once it's done with it. Beside it, Up next's own move, whose marker
     * ends the same way.
     */
    private fun scrollerClasses(s: Scroller): List<ClassDef> {
        val item = "Lfixture/ClipsItem;"
        val pagerRead = "iget-object v0, p0, $scrollerBase->A02:$pager"
        val marker = s.marker?.let { "android_purge_26_q2_$it" } ?: "ClipsAutoScroller"
        val move = method(scrollerType, "A03", listOf(scrollerType, "Z"), "V", 10, static = !s.instance, body = """
            const-string v0, "$marker"
            $pagerRead
            if-eqz v0, :none
            invoke-virtual { v0 }, $pager->A0N()$item
            move-result-object v2
            :got
            ${if (s.secondPagerField) "iget-object v5, p0, $scrollerBase->A01:$pager" else ""}
            iget v3, p0, $scrollerType->A00:I
            const/4 v4, 0x1
            iput-boolean v4, p0, $scrollerType->A07:Z
            ${if (s.scrollerWrittenOver) "iget-object p0, p0, $scrollerType->A0E:$session" else ""}
            ${if (s.pagerOffAnother) "iget-object v5, p0, $scrollerType->A0T:$scrollerBase" else ""}
            ${if (s.moveOnOther) "iget-object v0, p0, $scrollerType->A0S:$pager" else if (s.pagerOffAnother) "iget-object v0, v5, $scrollerBase->A02:$pager" else pagerRead}
            :check
            if-eqz v0, :moved
            :move
            ${if (s.moveTakesAnInt) "invoke-virtual { v0, v3 }, $pager->A0X(I)V" else "invoke-virtual { v0 }, $pager->A0X()V"}
            ${if (s.secondMoveCall) "invoke-virtual { v0 }, $pager->A0Y()V" else ""}
            :moved
            iget-object p0, p0, $scrollerType->A0E:$session
            const-string v0, "instagram_clips_viewer_autoplay_scroll"
            return-void
            :none
            const/4 v2, 0x0
            goto ${if (s.jumpToMove) ":move" else if (s.jumpBetween) ":check" else ":got"}
        """)
        val field = { owner: String, name: String, type: String -> ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null) }
        val base = ImmutableClassDef(
            scrollerBase, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, objectType, null, null, null,
            listOf(field(scrollerBase, "A01", pager), field(scrollerBase, "A02", pager)), emptyList(),
        )
        val scrollerClass = ImmutableClassDef(
            scrollerType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, if (s.extendsObject) objectType else scrollerBase, null, null, null,
            listOf(field(scrollerType, "A00", "I"), field(scrollerType, "A07", "Z"), field(scrollerType, "A0E", session), field(scrollerType, "A0S", pager), field(scrollerType, "A0T", scrollerBase)),
            listOf(move),
        )
        val upNextClass = classDef(upNext, listOf(method(upNext, "A00", listOf(upNext, "Z"), "V", 3, static = true, body = """
            const-string v0, "android_purge_26_q3_UpNextNavigator_scrollToNextReel"
            iget-object v0, p0, $scrollerBase->A02:$pager
            invoke-virtual { v0 }, $pager->A0X()V
            return-void
        """)))
        val other = if (s.secondMove) classDef("Lfixture/OtherScroller;", listOf(method("Lfixture/OtherScroller;", "A00", emptyList(), "V", 1, static = true, body = """
            const-string v0, "android_purge_26_q2_$SCROLL_TO_NEXT_REEL"
            return-void
        """))) else null
        return listOfNotNull(base, scrollerClass, upNextClass, other)
    }

    private fun abstractMethod(owner: String, name: String, parameters: List<String>, returns: String): Method = ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
    )

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, static: Boolean, body: String): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList(), superclass: String = objectType): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, fields, methods)
}
