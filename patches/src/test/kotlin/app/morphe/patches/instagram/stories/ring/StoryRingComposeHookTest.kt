/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.ring

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.COMPOSE_RING
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.COMPOSE_RING_TAKER
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.HIGH_RESULT
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.RING_HOLDER
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.composeRing
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.composeRingTaker
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.ringMaker
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ring color on Compose screens (#528): the default ring's colors, turned into an array where the
 * method loading the ring's name reads them, go through the hook before the screen draws with them.
 */
class StoryRingComposeHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(RING_DEFAULT_COLORS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$RING_DEFAULT_COLORS is not in the extension: $declared", RING_DEFAULT_COLORS.substringAfter("->") in declared)
    }

    /** The hook goes right after the array lands, takes it, and answers into the same register. */
    @Test
    fun theDefaultRingsColorsGoThroughTheHook() {
        val context = PatchContexts.of(listOf(composeRing()))

        val ring = context.findComposeRing()
        assertEquals(COMPOSE_RING, ring.type)
        assertEquals("A00", ring.name)
        assertEquals(emptyList<String>(), ring.parameters)
        assertEquals(4, ring.at)
        assertEquals(6, ring.register)
        val original = NeutralNativePath(context.method(ring))
        context.colorComposeRing(ring, RING_DEFAULT_COLORS)

        val method = context.method(ring)
        assertHooked("stand-in", method, RING_DEFAULT_COLORS, ring.at, ring.register)
        original.assertPreserved("stand-in", method, setOf(ring.at, ring.at + 1))
        assertEquals("the screen reads the answer", Opcode.ARRAY_LENGTH, method.implementation!!.instructions.toList()[ring.at + 2].opcode)
    }

    /** A call that lists its register as a range, and an array landing past v15, are found and hooked too. */
    @Test
    fun aRangeCallIntoAHighRegisterIsHookedToo() {
        val context = PatchContexts.of(listOf(composeRing(range = true)))

        val ring = context.findComposeRing()
        assertEquals(HIGH_RESULT, ring.register)
        context.colorComposeRing(ring, RING_DEFAULT_COLORS)
        assertHooked("range", context.method(ring), RING_DEFAULT_COLORS, ring.at, HIGH_RESULT)
    }

    /**
     * 455's shape: the method loading the ring's name makes the ring's own class, and a static
     * taking only that class turns its colors into an array. That static is found and hooked.
     */
    @Test
    fun aStaticTakingTheDefaultRingIsFoundAndHooked() {
        val context = PatchContexts.of(listOf(ringMaker(), composeRingTaker()))

        val ring = context.findComposeRing()
        assertEquals(COMPOSE_RING_TAKER, ring.type)
        assertEquals("A00", ring.name)
        assertEquals(listOf(RING_HOLDER), ring.parameters)
        assertEquals(3, ring.at)
        assertEquals(6, ring.register)
        val original = NeutralNativePath(context.method(ring))
        context.colorComposeRing(ring, RING_DEFAULT_COLORS)

        val method = context.method(ring)
        assertHooked("taker", method, RING_DEFAULT_COLORS, ring.at, ring.register)
        original.assertPreserved("taker", method, setOf(ring.at, ring.at + 1))
        assertEquals("the screen reads the answer", Opcode.ARRAY_LENGTH, method.implementation!!.instructions.toList()[ring.at + 2].opcode)
    }

    /** A static taking another class, or a class the name's method makes from one list, isn't the ring's. */
    @Test
    fun aStaticTakingAnotherClassIsNotTheRing() {
        refuses("found 0", listOf(ringMaker(), composeRingTaker(takes = "Lfixture/Other;")))
        refuses("found 0", listOf(ringMaker(lists = 1), composeRingTaker()))
    }

    /** 454's shape and 455's in one build leave two places, and the patch can't tell which is the ring's. */
    @Test
    fun bothShapesAtOnceFailThePatch() {
        refuses("found 2", listOf(composeRing(), ringMaker(), composeRingTaker()))
    }

    /** 454's other method loading the ring's name makes no array from a list, and is left alone. */
    @Test
    fun aMethodLoadingTheNameWithoutTheCallIsLeftAlone() {
        val context = PatchContexts.of(listOf(composeRing(), composeRing(type = "Lfixture/Sibling;", calls = 0)))
        assertEquals(COMPOSE_RING, context.findComposeRing().type)
    }

    /** The same call in a method of the class that doesn't load the ring's name isn't the ring's. */
    @Test
    fun theCallInAMethodNotLoadingTheNameIsLeftAlone() {
        val context = PatchContexts.of(listOf(composeRing(unnamedCaller = true)))
        assertEquals("A00", context.findComposeRing().name)
    }

    @Test
    fun noCallOrMoreThanOneFailsThePatch() {
        refuses("found 0", listOf(composeRing(calls = 0)))
        refuses("found 2: $COMPOSE_RING->A00 at 2, $COMPOSE_RING->A00 at 5", listOf(composeRing(calls = 2)))
        refuses("found 2", listOf(composeRing(), composeRing(type = "Lfixture/Sibling;")))
    }

    /** A call answering anything but an int array, or whose answer isn't moved right after, isn't the colors. */
    @Test
    fun aCallAnsweringSomethingElseIsNotTheRing() {
        refuses("found 0", listOf(composeRing(answers = "[J")))
        refuses("found 0", listOf(composeRing(moved = false)))
    }

    /** A jump onto where the hook goes would keep its label there and skip the hook. */
    @Test
    fun aJumpOntoWhereTheHookGoesFailsThePatch() {
        refuses("jumps onto the instruction after the default ring's colors land in $COMPOSE_RING->A00", listOf(composeRing(jumpsAfter = true)))
    }

    @Test
    fun aRefusalNamesThePatchAsking() {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(composeRing(calls = 0))).findComposeRing("Accent color") }
        assertTrue(refusal.message, refusal.message.orEmpty().startsWith("Accent color: expected one place"))
    }

    /** In each declared build one method loading the ring's name makes its colors an array, and gets the hook. */
    @Test
    fun eachDeclaredBuildHandsItsDefaultRingToTheHook() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildHandsItsDefaultRingToTheHookToo() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    private fun hooks(bundle: File, label: String) {
        val context = PatchContexts.of(StoryRingFixtures.composeRingClasses(bundle))
        val ring = context.findComposeRing()
        val original = NeutralNativePath(context.method(ring))
        context.colorComposeRing(ring, RING_DEFAULT_COLORS)
        val method = context.method(ring)
        assertHooked("$label ${ring.type}->${ring.name}", method, RING_DEFAULT_COLORS, ring.at, ring.register)
        original.assertPreserved("$label ${ring.type}->${ring.name}", method, setOf(ring.at, ring.at + 1))
    }

    private fun refuses(why: String, classes: List<ClassDef>) {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findComposeRing() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(why))
    }

    internal companion object {
        fun BytecodePatchContext.method(ring: ComposeRing): Method =
            mutableClassDefBy(ring.type).methods.single {
                it.name == ring.name && it.parameterTypes.map(CharSequence::toString) == ring.parameters
            }

        /**
         * One hook, at [at]: a range call passing [register] alone, its answer moved back into
         * [register], right after the array landed there, and nothing jumping onto either.
         */
        fun assertHooked(what: String, method: Method, hook: String, at: Int, register: Int) {
            val code = method.implementation!!.instructions.toList()
            val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == hook }
            assertEquals("$what: hooks", listOf(at), hooks)
            assertEquals("$what: the hook's call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
            val call = code[at] as RegisterRangeInstruction
            assertEquals("$what: what the hook is passed", register to 1, call.startRegister to call.registerCount)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
            assertEquals("$what: back where the colors were", register, (code[at + 1] as OneRegisterInstruction).registerA)
            assertTrue("$what: something jumps onto the hook", method.jumpTargets().none { it == at || it == at + 1 })
        }
    }
}
