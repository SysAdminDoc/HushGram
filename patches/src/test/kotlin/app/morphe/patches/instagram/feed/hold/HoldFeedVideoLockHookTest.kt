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
    private val reset = "$controller->reset($controller" + "Z)Z"
    private val fast = "$controller->fast:$atomic"

    /** Both lock hooks are in the FeedHoldSpeed the bundle ships, public and static. */
    @Test
    fun theLockHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_HOLD).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        listOf(LOCK_PRESSED, LOCK_LIFTED).forEach { assertTrue("$it is not in the extension: $declared", it.substringAfter("->") in declared) }
    }

    /**
     * A handler shaped like 454's 02g8 with its controller like 07kE: the press entry tells the flag before its reset,
     * and the release asks before its own, a yes skipping the reset with the answer set to yes. Nothing stock goes.
     */
    @Test
    fun thePressTellsAndTheReleaseAsksBeforeTheirResets() {
        for (high in listOf(false, true)) {
            val what = if (high) "registers above v15" else "low registers"
            val context = PatchContexts.of(classes(high = high))
            val press = context.feedPressEntry()
            val found = context.findFeedLock(press)
            assertEquals("$what: the press entry", "press", found.press.name)
            assertEquals("$what: the release", "release", found.release.name)
            assertEquals("$what: the reset", reset, found.reset)
            assertEquals("$what: the fast flag", fast, found.fast)
            val stockPress = FeedLockWrites.code(context, found.press)
            val stockRelease = FeedLockWrites.code(context, found.release)

            context.lockFast(found)

            FeedLockWrites.assertWritten(what, context, found)
            val release = FeedLockWrites.code(context, found.release)
            val yes = release[release.indexOfFirst { it.text() == LOCK_LIFTED } + 3]
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
     * The tap handler with its tap, press entry and release, and the feed's speed controller with its fast flag and
     * static reset. [high] puts the reset's registers above v15, the way a long method has them.
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
    ): List<ClassDef> {
        val instance = AccessFlags.PUBLIC.value
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val setsFast = if (comparesAndSets) "invoke-virtual {v1, v2, v3}, $atomic->compareAndSet(ZZ)Z" else "invoke-virtual {v1, v3}, $atomic->set(Z)V"
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
        val resetBody = """
            iget-object v0, p0, $fast
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $atomic->getAndSet(Z)Z
            move-result v0
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
            if (release) method(handler, "release", listOf(media, point, state, "Z"), "V", if (high) 20 else 3, instance, releaseBody) else null,
        )
        val flags = listOfNotNull("fast", if (twoFlags) "slow" else null)
            .map { ImmutableField(controller, it, atomic, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null) }
        return listOf(
            ImmutableClassDef(handler, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", emptyList(), null, null,
                listOf(ImmutableField(handler, "speed", controller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
                handlerMethods),
            ImmutableClassDef(controller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", emptyList(), null, null,
                flags, listOf(method(controller, "reset", listOf(controller, "Z"), "Z", 2, static, resetBody))),
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
     * release reads it the same way for [LOCK_LIFTED], whose no goes on to the reset and whose yes sets the reset's
     * answer register to 1 and jumps past the reset's move-result.
     */
    fun assertWritten(what: String, context: BytecodePatchContext, lock: FeedLock) {
        val press = code(context, lock.press)
        val pressed = press.indices.filter { press[it].text() == LOCK_PRESSED }
        assertEquals("$what: calls telling the press", 1, pressed.size)
        val told = pressed.single()
        assertEquals("$what: the press's reset comes right after", lock.reset, press[told + 1].text())
        assertFlagHanded("$what: the press", press, told, lock)

        val release = code(context, lock.release)
        val lifted = release.indices.filter { release[it].text() == LOCK_LIFTED }
        assertEquals("$what: calls asking at the release", 1, lifted.size)
        val asked = lifted.single()
        val flag = assertFlagHanded("$what: the release", release, asked, lock)
        assertEquals("$what: the lift's answer", Opcode.MOVE_RESULT, release[asked + 1].opcode)
        assertEquals("$what: the lift's answer register", flag, (release[asked + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: a no", Opcode.IF_EQZ, release[asked + 2].opcode)
        assertEquals("$what: a no's register", flag, (release[asked + 2] as OneRegisterInstruction).registerA)
        val reset = asked + 5
        assertEquals("$what: the release's reset", lock.reset, release[reset].text())
        assertEquals("$what: a no goes on to the reset", reset, (release[asked + 2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: the reset's answer", Opcode.MOVE_RESULT, release[reset + 1].opcode)
        val answer = (release[reset + 1] as OneRegisterInstruction).registerA
        val yes = release[asked + 3]
        assertTrue("$what: a yes is a constant, not ${yes.opcode}", yes.opcode == Opcode.CONST_4 || yes.opcode == Opcode.CONST_16)
        assertEquals("$what: a yes goes to the reset's answer", answer, (yes as OneRegisterInstruction).registerA)
        assertEquals("$what: a yes says it was fast", 1, (yes as NarrowLiteralInstruction).narrowLiteral)
        assertTrue("$what: a yes jumps, not ${release[asked + 4].opcode}", release[asked + 4].opcode in gotos)
        assertEquals("$what: a yes jumps past the reset's answer", reset + 2,
            (release[asked + 4] as BuilderOffsetInstruction).target.location.index)
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
