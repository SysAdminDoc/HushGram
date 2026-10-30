/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.autoadvance

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.stories.loop.findStoryLoop
import app.morphe.patches.instagram.stories.loop.STORY_LOOP_FLAG
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoryAdvanceHookTest {
    private val reelItem = "Lcom/instagram/model/reels/ReelItem;"
    private val check = "$STORY_VIEWER->plays($reelItem)Z"

    /** The hooks the patch writes are in the StoryAdvance the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(HOLD, HOLD_UNLESS_IT_LOOPS)) {
            val type = hook.substringBefore("->")
            val declared = ExtensionDex.classDef(type).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The finished-item bridge asks first; the other bridge, holding "sponsored", stays as it was. */
    @Test
    fun theFinishedItemHandlerAsksBeforeMovingOn() {
        val context = PatchContexts.of(listOf(viewer()))

        context.holdFinishedStories()

        val patched = context.mutableClassDefBy(STORY_VIEWER)
        assertGuardFirst("finished", patched.methods.single { it.name == "FrS" }, check)
        assertEquals("the sponsored bridge was touched", 3, patched.methods.single { it.name == "FrU" }.instructions().size)
    }

    /**
     * Each patch alone stays as it was: Stop alone holds every finished story at the top of the
     * handler without asking anything more, and with Stop off or paused every story goes on into
     * the handler, where Instagram loops it or moves on, without the guard asking the loop check.
     */
    @Test
    fun eachPatchAloneStaysAsItWas() {
        val handler = patchedHandler()

        val stopAlone = trace(handler, hold = true, unlessItLoops = false, loops = false)
        assertTrue("Stop alone holds", stopAlone.held)
        assertEquals("Stop alone asks only hold()", listOf(HOLD), stopAlone.asked)
        assertTrue("Stop alone holds a story that could loop", trace(handler, hold = true, unlessItLoops = false, loops = true).held)

        for (loops in listOf(true, false)) {
            val off = trace(handler, hold = false, unlessItLoops = false, loops = loops)
            assertFalse("Stop off, loops $loops: goes on into the handler", off.held)
            assertEquals("Stop off, loops $loops: the loop check isn't asked", listOf(HOLD, HOLD_UNLESS_IT_LOOPS), off.asked)
        }
    }

    /**
     * With both on, a story the viewer's loop check says yes to goes on into the handler, where
     * Instagram's own ask of the same check starts it over; Stop doesn't hold it.
     */
    @Test
    fun bothOnAStoryThatCanLoopLoops() {
        val run = trace(patchedHandler(), hold = false, unlessItLoops = true, loops = true)
        assertFalse("a story that can loop was held", run.held)
        assertEquals("what the guard asked", listOf(HOLD, HOLD_UNLESS_IT_LOOPS, check), run.asked)
    }

    /** With both on, a story the loop check turns down, an ad or a special kind, is still held. */
    @Test
    fun bothOnAStoryThatCantLoopIsStillHeld() {
        val run = trace(patchedHandler(), hold = false, unlessItLoops = true, loops = false)
        assertTrue("a story that can't loop moved on", run.held)
        assertEquals("what the guard asked", listOf(HOLD, HOLD_UNLESS_IT_LOOPS, check), run.asked)
    }

    @Test
    fun aHandlerThatDoesNotCastToAStoryItemFailsThePatch() {
        val context = PatchContexts.of(listOf(viewer(firstCast = "Ljava/lang/String;")))
        val failure = assertThrows(PatchException::class.java) { context.holdFinishedStories() }
        assertTrue(failure.message!!, failure.message!!.contains(reelItem))
    }

    @Test
    fun withoutTheViewerThePatchFails() {
        val context = PatchContexts.of(listOf(viewer(type = "Lfixture/SomeOtherFragment;")))
        assertThrows(PatchException::class.java) { context.holdFinishedStories() }
    }

    /** A handler without exactly one private yes-or-no check of the item fails before anything is written. */
    @Test
    fun aHandlerWithoutOneLoopCheckFailsBeforeAnythingChanges() {
        val handler = "$STORY_VIEWER->FrS"
        val notPrivate = "$handler asks $check, which isn't a private instance method of the viewer"
        val cases = listOf(
            viewer(asks = 0) to "expected $handler to ask one private check of the story item, found 0",
            viewer(asks = 2) to "expected $handler to ask one private check of the story item, found 2",
            viewer(checkFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) to notPrivate,
            viewer(checkFlags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value) to notPrivate,
            viewer(checkDeclared = false) to notPrivate,
        )
        for ((viewer, expected) in cases) {
            val context = PatchContexts.of(listOf(viewer))
            val failure = assertThrows(expected, PatchException::class.java) { context.holdFinishedStories() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = context.mutableClassDefBy(STORY_VIEWER).methods.filter { m -> m.instructions().any { it.reference() == HOLD } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** A private item predicate is not a loop check unless it reads Loop a story's actual flag. */
    @Test
    fun aDifferentPrivatePredicateIsRefusedBeforeTheGuardIsWritten() {
        val original = viewer(loopFlag = STORY_LOOP_FLAG + 1)
        val context = PatchContexts.of(listOf(original))
        assertThrows(PatchException::class.java) { context.holdFinishedStories() }
        for (method in original.methods) {
            val after = context.mutableClassDefBy(STORY_VIEWER).methods.single { it.name == method.name }
            assertEquals(method.implementation!!.registerCount, after.implementation!!.registerCount)
            assertEquals(method.instructions().map { it.opcode to it.reference() }, after.instructions().map { it.opcode to it.reference() })
        }
    }

    /**
     * In each declared build the handler is the viewer's one bridge that fits, and it gets the
     * guard. The loop check the guard asks is the method holding the read of Instagram's story loop
     * flag, the one Loop a story answers, found after the guard went in.
     */
    @Test
    fun eachDeclaredBuildHoldsTheFinishedStory() {
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
    fun eachOtherBuildHoldsTheFinishedStoryToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val viewer = FixtureDex.classes(bundle, setOf(STORY_VIEWER))[STORY_VIEWER]
            ?: error("${label}: no $STORY_VIEWER")
        val context = PatchContexts.of(listOf(viewer))

        context.holdFinishedStories()

        val bridges = viewer.methods.filter {
            AccessFlags.BRIDGE.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
        }
        assertEquals("${label}: the viewer's one-Object bridges", 2, bridges.size)
        // On 452 each bridge casts the item and forwards it to a viewer method taking the ReelItem,
        // which holds the body; the guard goes there.
        val bodies = bridges.map { bridge -> bridge.forwardedTo(viewer) ?: bridge }
        val handler = bodies.single { method -> method.instructions().any { it.string() == "userSession" } }
        val after = context.mutableClassDefBy(STORY_VIEWER).methods.single {
            it.name == handler.name && it.parameterTypes.map(Any::toString) == handler.parameterTypes.map(Any::toString)
        }
        assertEquals("${label}: handler size", handler.instructions().size + GUARD, after.instructions().size)
        val read = context.findStoryLoop()
        val loopCheck = "$STORY_VIEWER->${read.name}($reelItem)Z"
        assertGuardFirst(label, after, loopCheck, original = handler.instructions().first())
        assertEquals(
            "${label}: Instagram's own asks of the loop check",
            1, handler.instructions().count { it.reference() == loopCheck },
        )
        assertTrue("${label}: both on, a story that can loop", !trace(after, false, true, true).held)
        assertTrue("${label}: both on, a story that can't loop", trace(after, false, true, false).held)
        val other = bodies.single { it !== handler }
        assertTrue(
            "${label}: the other bridge no longer holds \"sponsored\"",
            other.instructions().any { it.string() == "sponsored" },
        )
    }

    /**
     * The guard is the handler's first [GUARD] instructions and the handler's own [original] first
     * instruction follows it: the item's cast in a bridge handler, whatever 452's forwarded handler
     * starts with there.
     */
    private fun assertGuardFirst(what: String, handler: Method, loopCheck: String, original: Instruction? = null) {
        val code = handler.instructions()
        assertEquals(
            "$what: the guard's opcodes",
            listOf(
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                Opcode.CHECK_CAST, Opcode.INVOKE_DIRECT_RANGE, Opcode.MOVE_RESULT, Opcode.IF_NEZ,
                Opcode.RETURN_VOID, original?.opcode ?: Opcode.CHECK_CAST,
            ),
            code.take(GUARD + 1).map { it.opcode },
        )
        assertEquals("$what: the hook called first", HOLD, code[0].reference())
        assertEquals("$what: the hook called next", HOLD_UNLESS_IT_LOOPS, code[3].reference())
        assertEquals("$what: the cast", reelItem, code[6].reference())
        assertEquals("$what: the loop check", loopCheck, code[7].reference())
        val range = code[7] as RegisterRangeInstruction
        val implementation = handler.implementation!!
        assertEquals("$what: the check is handed this and the item", implementation.registerCount - 2, range.startRegister)
        assertEquals("$what: two registers", 2, range.registerCount)
        if (original == null || original.opcode == Opcode.CHECK_CAST) {
            assertEquals("$what: the original cast", reelItem, code[GUARD].reference())
        } else {
            assertEquals("$what: the handler takes the item", listOf(reelItem), handler.parameterTypes.map(Any::toString))
            assertEquals("$what: the handler's own start", original.reference(), code[GUARD].reference())
        }
    }

    /**
     * The viewer method [this] bridge hands its item to when it's nothing but a cast to a story item,
     * a call and a return, as on 452; null for a bridge with a body of its own.
     */
    private fun Method.forwardedTo(viewer: ClassDef): Method? {
        val code = instructions()
        if (code.size != 3 || code[0].opcode != Opcode.CHECK_CAST || code[0].reference() != reelItem) return null
        val called = (code[1] as? ReferenceInstruction)?.reference as? MethodReference ?: return null
        if (called.definingClass != STORY_VIEWER || called.parameterTypes.map(Any::toString) != listOf(reelItem)) return null
        return viewer.methods.single { it.name == called.name && it.parameterTypes.map(Any::toString) == listOf(reelItem) }
    }

    /** What happened when one finished story ran through the guard. */
    private data class Run(val held: Boolean, val asked: List<String>)

    /**
     * Follows the guard [holdFinishedStories] put at the top of [handler], with hold() answering
     * [hold], holdUnlessItLoops() [unlessItLoops] and the viewer's loop check [loops]. The run ends
     * held at the guard's return-void, or not held when it reaches the handler's own first
     * instruction. Anything else in the guard fails the test.
     */
    private fun trace(handler: Method, hold: Boolean, unlessItLoops: Boolean, loops: Boolean): Run {
        val code = handler.instructions()
        val guardEnd = code.indexOfFirst { it.opcode == Opcode.RETURN_VOID } + 1
        val offsets = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        val asked = mutableListOf<String>()
        var pending: Boolean? = null
        var v0: Boolean? = null
        var pc = 0
        while (pc < guardEnd) {
            val instruction = code[pc]
            when (instruction.opcode) {
                Opcode.INVOKE_STATIC -> {
                    val hook = instruction.reference()!!
                    asked += hook
                    pending = when (hook) {
                        HOLD -> hold
                        HOLD_UNLESS_IT_LOOPS -> unlessItLoops
                        else -> error("the guard calls $hook")
                    }
                }
                Opcode.INVOKE_DIRECT_RANGE -> {
                    asked += instruction.reference()!!
                    pending = loops
                }
                Opcode.MOVE_RESULT -> v0 = pending ?: error("a move-result with nothing to move")
                Opcode.CHECK_CAST -> Unit
                Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                    val value = v0 ?: error("a branch on an unset register")
                    val jump = if (instruction.opcode == Opcode.IF_EQZ) !value else value
                    if (jump) {
                        val target = offsets[pc] + (instruction as OffsetInstruction).codeOffset
                        pc = offsets.indexOf(target)
                        check(pc >= 0) { "a branch to the middle of an instruction" }
                        continue
                    }
                }
                Opcode.RETURN_VOID -> return Run(held = true, asked = asked)
                else -> error("the guard runs ${instruction.opcode}")
            }
            pc++
        }
        assertEquals("the run left the guard somewhere other than the handler's own start", guardEnd, pc)
        return Run(held = false, asked = asked)
    }

    private fun patchedHandler(): Method {
        val context = PatchContexts.of(listOf(viewer()))
        context.holdFinishedStories()
        return context.mutableClassDefBy(STORY_VIEWER).methods.single { it.name == "FrS" }
    }

    /**
     * A story viewer shaped like Instagram 449's: two public bridges taking one Object, each casting
     * it to a ReelItem first, one holding "userSession" and asking the viewer's private loop check
     * about the item, the other holding "sponsored".
     */
    private fun viewer(
        type: String = STORY_VIEWER,
        firstCast: String = reelItem,
        asks: Int = 1,
        checkFlags: Int = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
        checkDeclared: Boolean = true,
        loopFlag: Long = STORY_LOOP_FLAG,
    ): ClassDef {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.BRIDGE.value or AccessFlags.SYNTHETIC.value
        fun bridge(name: String, string: String, cast: String, asks: Int) = ImmutableMethod(
            type, name, listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "V", flags, null, null,
            ImmutableMethodImplementation(
                4,
                listOf(
                    ImmutableInstruction21c(Opcode.CHECK_CAST, 3, ImmutableTypeReference(cast)),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(string)),
                ) + List(asks) {
                    listOf(
                        ImmutableInstruction35c(
                            Opcode.INVOKE_DIRECT, 2, 2, 3, 0, 0, 0,
                            ImmutableMethodReference(type, "plays", listOf(reelItem), "Z"),
                        ),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    )
                }.flatten() + ImmutableInstruction10x(Opcode.RETURN_VOID),
                null, null,
            ),
        )
        val plays = ImmutableMethod(
            type, "plays", listOf(ImmutableMethodParameter(reelItem, null, null)), "Z", checkFlags, null, null,
            ImmutableMethodImplementation(
                3,
                listOf(
                    ImmutableInstruction51l(Opcode.CONST_WIDE, 0, loopFlag),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0,
                        ImmutableMethodReference("Lfixture/Configs;", "read", listOf("J"), "Z")),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
                null, null,
            ),
        )
        val methods = listOf(bridge("FrS", "userSession", firstCast, asks), bridge("FrU", "sponsored", reelItem, 0)) +
            if (checkDeclared) listOf(plays) else emptyList()
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Landroidx/fragment/app/Fragment;",
            null, null, null, null, methods,
        )
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private companion object {
        /** The guard's instructions. */
        const val GUARD = 11
    }
}
