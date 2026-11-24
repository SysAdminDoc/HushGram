/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.hold

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.feed.tap.FEED_TAP_HANDLER_STRING
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep 2x after you let go (#552): what the lock writes into the feed hold's press entry and release. */
class HoldFeedVideoLockHookTest {
    private val handler = "Lfixture/FeedTapHandler;"
    private val controller = "Lfixture/SpeedController;"
    private val view = "Landroid/view/View;"
    private val media = "Lcom/instagram/feed/media/Media;"
    private val point = "Lfixture/Point;"
    private val holder = "Lfixture/Holder;"
    private val state = "Lfixture/State;"
    private val atomic = "Ljava/util/concurrent/atomic/AtomicBoolean;"
    private val item = "Lfixture/Item;"
    private val mode = "Lfixture/Mode;"
    private val reset = "$controller->reset($controller" + "Z)Z"
    private val fast = "$controller->fast:$atomic"
    private val setter = "Lfixture/Setter;"
    private val look = FeedLook("$controller->item:$item", "$mode->REST:$mode", "$item->show($mode)V")
    private val inlinedLook = FeedLook("$controller->item:$item", "$mode->REST:$mode", "$setter->set($item" + "Ljava/lang/Object;)V", "$item->setter:$setter")
    private val held = "$mode->ACTIVE:$mode"

    /** The lock hooks are in the FeedHoldSpeed the bundle ships, public and static. */
    @Test
    fun theLockHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_HOLD).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        listOf(LOCK_PRESSED, LOCK_LIFTED, LOOK_KEPT, KEPT_SHOWN).forEach {
            assertTrue("$it is not in the extension: $declared", it.substringAfter("->") in declared)
        }
    }

    /**
     * A handler shaped like 454's 02g8 with its controller like 07kE: the press entry tells the flag before its reset,
     * and the release asks before its own, a yes skipping the reset with the answer set to yes. Nothing stock goes.
     * 457's x86_64 386314286 inlines the item's setter into a call on an object the item holds, which works the same.
     */
    @Test
    fun thePressTellsAndTheReleaseAsksBeforeTheirResets() {
        for (inlined in listOf(false, true)) for (high in listOf(false, true)) {
            val what = (if (high) "registers above v15" else "low registers") + if (inlined) ", the setter inlined" else ""
            val context = PatchContexts.of(classes(high = high, inlinedSetter = inlined))
            val press = context.feedPressEntry()
            val found = context.findFeedLock(press)
            assertEquals("$what: the press entry", "press", found.press.name)
            assertEquals("$what: the release", "release", found.release.name)
            assertEquals("$what: the reset", reset, found.reset)
            assertEquals("$what: the fast flag", fast, found.fast)
            assertEquals("$what: how the reset puts the look to rest", if (inlined) inlinedLook else look, found.look)
            assertEquals("$what: the hold state the press hands the post", held, found.held)
            val stockPress = FeedLockWrites.code(context, found.press)
            val stockRelease = FeedLockWrites.code(context, found.release)

            context.lockFast(found)

            FeedLockWrites.assertWritten(what, context, found)
            val release = FeedLockWrites.code(context, found.release)
            val yes = release[release.indexOfFirst { it.text() == reset } - 2]
            assertEquals("$what: the answer's width", if (high) Opcode.CONST_16 else Opcode.CONST_4, yes.opcode)
            assertEquals("$what: the answer's register", if (high) 18 else 0, (yes as OneRegisterInstruction).registerA)
            assertTrue("$what: the press's stock kept in order", isKept(stockPress, FeedLockWrites.code(context, found.press)))
            assertTrue("$what: the release's stock kept in order", isKept(stockRelease, release))
            assertTrue("$what: the tap and the reset stay as they were", listOf("tap", "reset").all { name ->
                (context.mutableClassDefBy(handler).methods + context.mutableClassDefBy(controller).methods)
                    .single { it.name == name }.implementation!!.instructions.none { it.text()?.startsWith(FEED_HOLD) == true }
            })
        }
    }

    /**
     * #552: a reset that doesn't put a look to rest the way 456's does, or puts two, leaves a kept lift only
     * skipping the reset, as before.
     */
    @Test
    fun aResetWithNoOneLookToRestOnlySkips() {
        for (looks in listOf(0, 2)) {
            val context = PatchContexts.of(classes(looks = looks))
            val found = context.findFeedLock(context.feedPressEntry())
            assertEquals("$looks looks", null, found.look)
            assertEquals("$looks looks: no hold state to swap", null, found.held)
            context.lockFast(found)
            FeedLockWrites.assertWritten("$looks looks", context, found)
            val release = FeedLockWrites.code(context, found.release)
            assertTrue("$looks looks: nothing put to rest", release.none { it.text() == look.show })
            val press = FeedLockWrites.code(context, found.press)
            assertTrue("$looks looks: the press isn't asked about its look", press.none { it.text() == LOOK_KEPT })
        }
    }

    /**
     * #552: a press that hands the post no hold state, two of them, or one something jumps straight onto keeps its look
     * as Instagram has it, while the rest of the lock goes in.
     */
    @Test
    fun aPressWithNoOneHoldStateKeepsItsLook() {
        val cases = listOf(
            "no hold state" to classes(holds = 0),
            "two hold states" to classes(holds = 2),
            "a jump onto the hold state's call" to classes(jumpsOntoHold = true),
            "a jump onto the inlined setter's call" to classes(jumpsOntoHold = true, inlinedSetter = true),
        )
        for ((what, classes) in cases) {
            val context = PatchContexts.of(classes)
            val found = context.findFeedLock(context.feedPressEntry())
            assertEquals("$what: the look the reset puts to rest", if ("inlined" in what) inlinedLook else look, found.look)
            assertEquals(what, null, found.held)
            val stockPress = FeedLockWrites.code(context, found.press)
            context.lockFast(found)
            FeedLockWrites.assertWritten(what, context, found)
            val press = FeedLockWrites.code(context, found.press)
            assertTrue("$what: the press isn't asked about its look", press.none { it.text() == LOOK_KEPT })
            assertTrue("$what: the press's stock kept in order", isKept(stockPress, press))
        }
    }

    /** A build the lock can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildTheLockCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(tagged = false) to "expected $handler->press to call one static speed reset loading \"$SPEED_RESET_TAG\", found 0",
            classes(twoFlags = true) to "expected one $atomic field in $controller, found 2",
            classes(comparesAndSets = false) to "$handler->press doesn't set $fast with compareAndSet",
            classes(release = false) to "expected one press release ($media, $point, $state, Z) in $handler, found 0",
            classes(dropsAnswer = true) to "$handler->release calls $reset and drops the answer",
            classes(resetsTwice = true) to "expected $handler->release to call $reset once, found 2",
            classes(jumpsOntoReset = true) to "$handler->release jumps straight to its call to $reset",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.lockFast(context.findFeedLock(context.feedPressEntry()))
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.implementation?.instructions?.any { it.text()?.startsWith(FEED_HOLD) == true } == true }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** Whether every stock instruction is still there in order, a goto of any width read as one. */
    private fun isKept(stock: List<Instruction>, patched: List<Instruction>): Boolean {
        val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
        fun Instruction.kind() = if (opcode in gotos) Opcode.GOTO else opcode
        val wanted = stock.filter { it.opcode != Opcode.NOP }
        var at = 0
        for (instruction in patched.filter { it.opcode != Opcode.NOP }) {
            if (at < wanted.size && instruction.kind() == wanted[at].kind() && instruction.text() == wanted[at].text()) at++
        }
        return at == wanted.size
    }

    /**
     * The tap handler with its tap, press entry and release, and the feed's speed controller with its fast flag, the
     * feed item it holds and static reset. [high] puts the reset's registers above v15, the way a long method has
     * them. The reset puts the item's look to rest [looks] times, the way 456's does once, and the press hands the
     * item a hold state [holds] times after its 2x, the way 456's does once ([jumpsOntoHold] branching onto that call).
     * [inlinedSetter] calls the setter the way 457's x86_64 does, through an object the item holds.
     */
    private fun classes(
        high: Boolean = false,
        tagged: Boolean = true,
        twoFlags: Boolean = false,
        comparesAndSets: Boolean = true,
        release: Boolean = true,
        dropsAnswer: Boolean = false,
        resetsTwice: Boolean = false,
        jumpsOntoReset: Boolean = false,
        looks: Int = 1,
        holds: Int = 1,
        jumpsOntoHold: Boolean = false,
        inlinedSetter: Boolean = false,
    ): List<ClassDef> {
        val instance = AccessFlags.PUBLIC.value
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        fun show(on: Int, given: Int, spare: Int, label: String = "") =
            if (inlinedSetter) {
                "iget-object v$spare, v$on, ${inlinedLook.holder}\n$label\ninvoke-virtual {v$spare, v$on, v$given}, ${inlinedLook.show}"
            } else {
                "$label\ninvoke-virtual {v$on, v$given}, ${look.show}"
            }
        val hands = (1..holds).joinToString("\n") { n ->
            val state = "$mode->${if (n == 1) "ACTIVE" else "LOCKED"}:$mode"
            """
            iget-object v1, v0, $controller->item:$item
            sget-object v2, $state
            ${if (jumpsOntoHold) "if-eqz v1, :hold$n\nsget-object v2, $state" else ""}
            ${show(1, 2, 4, if (jumpsOntoHold) ":hold$n" else "")}
            """
        }
        val setsFast = (if (comparesAndSets) "invoke-virtual {v1, v2, v3}, $atomic->compareAndSet(ZZ)Z" else "invoke-virtual {v1, v3}, $atomic->set(Z)V") +
            "\n" + hands
        val pressBody = if (high) """
            move-object/from16 v0, p0
            iget-object v0, v0, $handler->speed:$controller
            move-object/from16 v16, v0
            const/4 v1, 0x1
            move/from16 v17, v1
            invoke-static/range {v16 .. v17}, $reset
            move-object/from16 v0, v16
            iget-object v1, v0, $fast
            const/4 v2, 0x0
            const/4 v3, 0x1
            $setsFast
            return-void
        """ else """
            iget-object v0, p0, $handler->speed:$controller
            const/4 v1, 0x1
            invoke-static {v0, v1}, $reset
            iget-object v1, v0, $fast
            const/4 v2, 0x0
            const/4 v3, 0x1
            $setsFast
            return-void
        """
        val releaseBody = if (high) """
            move-object/from16 v0, p0
            iget-object v0, v0, $handler->speed:$controller
            move-object/from16 v16, v0
            const/4 v1, 0x0
            move/from16 v17, v1
            invoke-static/range {v16 .. v17}, $reset
            move-result v18
            if-nez v18, :fast
            invoke-static {}, Lfixture/Reels;->open()V
            :fast
            return-void
        """ else """
            iget-object v0, p0, $handler->speed:$controller
            ${if (resetsTwice) "const/4 v1, 0x0\ninvoke-static {v0, v1}, $reset" else ""}
            const/4 v1, 0x0
            ${if (jumpsOntoReset) "if-eqz p4, :call\nnop\n:call" else ""}
            invoke-static {v0, v1}, $reset
            ${if (dropsAnswer) "" else "move-result v0\nif-nez v0, :fast"}
            invoke-static {}, Lfixture/Reels;->open()V
            :fast
            return-void
        """
        val rest = (1..looks).joinToString("\n") { n ->
            """
            iget-object v1, p0, $controller->item:$item
            if-eqz v1, :rested$n
            sget-object v2, $mode->${if (n == 1) "REST" else "ACTIVE"}:$mode
            ${show(1, 2, 3)}
            :rested$n
            """
        }
        val resetBody = """
            iget-object v0, p0, $fast
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $atomic->getAndSet(Z)Z
            move-result v0
            $rest
            const-string v1, "${if (tagged) SPEED_RESET_TAG else "FeedVideoSpeed"}"
            return v0
        """
        val tapBody = """
            const-string v0, "$FEED_TAP_HANDLER_STRING"
            return-void
        """
        val handlerMethods = listOfNotNull(
            method(handler, "tap", listOf(view, media, point, holder, state, "Ljava/lang/Object;", "I", "Z"), "V", 2, instance, tapBody),
            method(handler, "press", listOf(view, media, point, holder, state), "V", if (high) 20 else 6, instance, pressBody),
            // A kept lift borrows a third local for the setter's object where the setter is inlined.
            if (release) method(handler, "release", listOf(media, point, state, "Z"), "V", if (high) 20 else if (inlinedSetter) 5 else 4, instance, releaseBody) else null,
        )
        val flags = listOfNotNull("fast", if (twoFlags) "slow" else null)
            .map { ImmutableField(controller, it, atomic, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null) } +
            ImmutableField(controller, "item", item, AccessFlags.PUBLIC.value, null, null, null)
        return listOf(
            ImmutableClassDef(handler, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", emptyList(), null, null,
                listOf(ImmutableField(handler, "speed", controller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
                handlerMethods),
            ImmutableClassDef(controller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", emptyList(), null, null,
                flags, listOf(method(controller, "reset", listOf(controller, "Z"), "Z", if (inlinedSetter) 4 else 3, static, resetBody))),
        )
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

    private fun Instruction.text(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}

/** What the lock writes, read back from a patched context; the real builds' test checks it too. */
internal object FeedLockWrites {
    private val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

    fun code(context: BytecodePatchContext, method: Method): List<Instruction> =
        context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
        }.implementation!!.instructions.toList()

    /**
     * The press entry reads the fast flag into a register right before its reset and hands it to [LOCK_PRESSED]. The
     * release reads it the same way for [LOCK_LIFTED], whose no goes on to the reset and whose yes puts the post's
     * look to rest the way the reset does ([FeedLock.look]), sets the reset's answer register to 1 and jumps past the
     * reset's move-result.
     */
    fun assertWritten(what: String, context: BytecodePatchContext, lock: FeedLock) {
        val press = code(context, lock.press)
        val pressed = press.indices.filter { press[it].text() == LOCK_PRESSED }
        assertEquals("$what: calls telling the press", 1, pressed.size)
        val told = pressed.single()
        assertEquals("$what: the press's reset comes right after", lock.reset, press[told + 1].text())
        assertFlagHanded("$what: the press", press, told, lock)
        val asks = press.indices.filter { press[it].text() == LOOK_KEPT }
        val held = lock.held
        if (held == null) {
            assertTrue("$what: no hold state, so the press isn't asked about its look", asks.isEmpty())
        } else {
            assertLookKept(what, press, asks, lock.look!!, held)
        }

        val release = code(context, lock.release)
        val lifted = release.indices.filter { release[it].text() == LOCK_LIFTED }
        assertEquals("$what: calls asking at the release", 1, lifted.size)
        val asked = lifted.single()
        val flag = assertFlagHanded("$what: the release", release, asked, lock)
        assertEquals("$what: the lift's answer", Opcode.MOVE_RESULT, release[asked + 1].opcode)
        assertEquals("$what: the lift's answer register", flag, (release[asked + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: a no", Opcode.IF_EQZ, release[asked + 2].opcode)
        assertEquals("$what: a no's register", flag, (release[asked + 2] as OneRegisterInstruction).registerA)
        val reset = (asked + 3 until release.size).first { release[it].text() == lock.reset }
        assertEquals("$what: a no goes on to the reset", reset, (release[asked + 2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: the reset's answer", Opcode.MOVE_RESULT, release[reset + 1].opcode)
        val answer = (release[reset + 1] as OneRegisterInstruction).registerA
        val yesAt = reset - 2
        val yes = release[yesAt]
        assertTrue("$what: a yes is a constant, not ${yes.opcode}", yes.opcode == Opcode.CONST_4 || yes.opcode == Opcode.CONST_16)
        assertEquals("$what: a yes goes to the reset's answer", answer, (yes as OneRegisterInstruction).registerA)
        assertEquals("$what: a yes says it was fast", 1, (yes as NarrowLiteralInstruction).narrowLiteral)
        assertTrue("$what: a yes jumps, not ${release[reset - 1].opcode}", release[reset - 1].opcode in gotos)
        assertEquals("$what: a yes jumps past the reset's answer", reset + 2,
            (release[reset - 1] as BuilderOffsetInstruction).target.location.index)
        val rests = release.subList(asked + 3, yesAt)
        val look = lock.look
        if (look == null) {
            assertTrue("$what: a yes with no look to rest goes straight to its answer: ${rests.map { it.opcode }}", rests.isEmpty())
            return
        }
        assertRested(what, rests, asked + 3, yesAt, look, lock.held)
    }

    /**
     * A kept lift's look put to rest: the feed item read off the controller (through the borrowed local when the
     * controller is above v15), skipped when there's none, and handed the resting state, all in locals up to v15.
     * Where the press hands the post a hold state ([held]), Show 2x after you let go (#860) asks [KEPT_SHOWN] into the
     * state register first: a yes loads the hold state and jumps over the resting one, a no loads the resting one, and
     * both land on the setter.
     */
    private fun assertRested(what: String, rests: List<Instruction>, from: Int, yesAt: Int, look: FeedLook, held: String?) {
        // Where the item's setter is inlined (457's x86_64), the call goes to the object the item holds, read off it first.
        val extra = if (look.holder == null) 0 else 1
        val asks = if (held == null) 0 else 5
        assertTrue("$what: the steps putting the look to rest: ${rests.map { it.opcode }}",
            rests.size == 4 + asks + extra || rests.size == 5 + asks + extra)
        assertEquals("$what: calls asking about the kept look", if (held == null) 0 else 1, rests.count { it.text() == KEPT_SHOWN })
        val show = rests.last()
        assertEquals("$what: the look's setter", Opcode.INVOKE_VIRTUAL, show.opcode)
        assertEquals("$what: the look's setter", look.show, show.text())
        val call = show as FiveRegisterInstruction
        assertEquals("$what: the setter's registers", 2 + extra, call.registerCount)
        val handed = listOf(call.registerC, call.registerD, call.registerE).take(call.registerCount)
        assertTrue("$what: locals up to v15: $handed", handed.all { it <= 15 } && handed.toSet().size == handed.size)
        val item = handed[extra]
        val state = handed[extra + 1]
        if (look.holder != null) {
            val setter = rests[rests.size - 2]
            assertEquals("$what: the setter's object read", Opcode.IGET_OBJECT, setter.opcode)
            assertEquals("$what: the setter's object read", look.holder, setter.text())
            assertEquals("$what: the setter's object read into its register", call.registerC, (setter as TwoRegisterInstruction).registerA)
            assertEquals("$what: the setter's object read off the item", item, setter.registerB)
        }
        // Where the setter's steps start: the holder's read where it is inlined, otherwise the call itself.
        val lookAt = rests.size - 1 - extra
        val rest = rests[lookAt - 1]
        assertEquals("$what: the resting state", Opcode.SGET_OBJECT, rest.opcode)
        assertEquals("$what: the resting state", look.rest, rest.text())
        assertEquals("$what: the resting state's register", state, (rest as OneRegisterInstruction).registerA)
        if (held != null) {
            val jump = rests[lookAt - 2]
            assertTrue("$what: the hold state jumps over the resting one, not ${jump.opcode}", jump.opcode in gotos)
            assertEquals("$what: the hold state goes to the setter", from + lookAt, (jump as BuilderOffsetInstruction).target.location.index)
            val shown = rests[lookAt - 3]
            assertEquals("$what: a yes loads the hold state", Opcode.SGET_OBJECT, shown.opcode)
            assertEquals("$what: a yes loads the hold state", held, shown.text())
            assertEquals("$what: the hold state's register", state, (shown as OneRegisterInstruction).registerA)
            val no = rests[lookAt - 4]
            assertEquals("$what: a no", Opcode.IF_EQZ, no.opcode)
            assertEquals("$what: a no's register", state, (no as OneRegisterInstruction).registerA)
            assertEquals("$what: a no loads the resting state", from + lookAt - 1, (no as BuilderOffsetInstruction).target.location.index)
            val answer = rests[lookAt - 5]
            assertEquals("$what: the ask's answer", Opcode.MOVE_RESULT, answer.opcode)
            assertEquals("$what: the ask's answer goes to the state register", state, (answer as OneRegisterInstruction).registerA)
            val ask = rests[lookAt - 6]
            assertEquals("$what: the ask", Opcode.INVOKE_STATIC, ask.opcode)
            assertEquals("$what: the ask", KEPT_SHOWN, ask.text())
            assertEquals("$what: the ask takes nothing", 0, (ask as FiveRegisterInstruction).registerCount)
        }
        val none = rests[lookAt - 2 - asks]
        assertEquals("$what: no item skips", Opcode.IF_EQZ, none.opcode)
        assertEquals("$what: no item's register", item, (none as OneRegisterInstruction).registerA)
        assertEquals("$what: no item goes to the yes", yesAt, (none as BuilderOffsetInstruction).target.location.index)
        val read = rests[lookAt - 3 - asks]
        assertEquals("$what: the item's read", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$what: the item's read", look.item, read.text())
        assertEquals("$what: the item's register", item, (read as TwoRegisterInstruction).registerA)
        if (rests.size == 5 + asks + extra) {
            assertEquals("$what: the controller moved down at $from", Opcode.MOVE_OBJECT_FROM16, rests[0].opcode)
            assertEquals("$what: into the item's register", item, (rests[0] as TwoRegisterInstruction).registerA)
            assertEquals("$what: the item read through it", item, read.registerB)
        }
    }

    /**
     * Keep the name while you hold: right after Instagram loads the hold state, the press asks [LOOK_KEPT] into the
     * setter's state register. A no loads the hold state again, a yes the resting one and jumps over that, and both
     * land on the setter's own call.
     */
    private fun assertLookKept(what: String, press: List<Instruction>, asks: List<Int>, look: FeedLook, held: String) {
        assertEquals("$what: calls asking about the look", 1, asks.size)
        val ask = asks.single()
        // An inlined setter (457's x86_64) reads its object off the item between the state's load and the ask.
        val extra = if (look.holder == null) 0 else 1
        if (look.holder != null) assertEquals("$what: the setter's object is read right before the ask", look.holder, press[ask - 1].text())
        assertEquals("$what: Instagram's hold state loads right before the ask", held, press[ask - 1 - extra].text())
        val answer = press[ask + 1]
        assertEquals("$what: the ask's answer", Opcode.MOVE_RESULT, answer.opcode)
        val state = (answer as OneRegisterInstruction).registerA
        val no = press[ask + 2]
        assertEquals("$what: a no", Opcode.IF_EQZ, no.opcode)
        assertEquals("$what: a no's register", state, (no as OneRegisterInstruction).registerA)
        val rest = press[ask + 3]
        assertEquals("$what: a yes loads the resting state", Opcode.SGET_OBJECT, rest.opcode)
        assertEquals("$what: a yes loads the resting state", look.rest, rest.text())
        assertEquals("$what: into the state register", state, (rest as OneRegisterInstruction).registerA)
        val jump = press[ask + 4]
        assertTrue("$what: a yes jumps, not ${jump.opcode}", jump.opcode in gotos)
        val heldAt = (no as BuilderOffsetInstruction).target.location.index
        assertEquals("$what: a no loads the hold state next", ask + 5, heldAt)
        assertEquals("$what: a no loads the hold state", Opcode.SGET_OBJECT, press[heldAt].opcode)
        assertEquals("$what: a no loads the hold state", held, press[heldAt].text())
        assertEquals("$what: into the state register", state, (press[heldAt] as OneRegisterInstruction).registerA)
        val show = press[heldAt + 1]
        assertEquals("$what: then the look's setter", look.show, show.text())
        assertEquals("$what: a yes jumps to the setter", heldAt + 1, (jump as BuilderOffsetInstruction).target.location.index)
        val call = show as FiveRegisterInstruction
        assertEquals("$what: the setter takes the state register", state, if (extra == 0) call.registerD else call.registerE)
    }

    /** The one register handed to the hook at [at], read from the controller's fast flag right before. */
    private fun assertFlagHanded(what: String, code: List<Instruction>, at: Int, lock: FeedLock): Int {
        val call = code[at] as FiveRegisterInstruction
        assertEquals("$what: the hook's call", Opcode.INVOKE_STATIC, code[at].opcode)
        assertEquals("$what: registers handed to the hook", 1, call.registerCount)
        assertEquals("$what: the flag's read", Opcode.IGET_OBJECT, code[at - 1].opcode)
        assertEquals("$what: the flag read", lock.fast, code[at - 1].text())
        assertEquals("$what: the hook is handed the flag", (code[at - 1] as TwoRegisterInstruction).registerA, call.registerC)
        return call.registerC
    }

    private fun Instruction.text(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
