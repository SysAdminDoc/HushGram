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
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.RING_SPEC
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.defaultRing
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures.spinner
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.FloatEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoryRingColorHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(RING_COLORS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$RING_COLORS is not in the extension: $declared", RING_COLORS.substringAfter("->") in declared)
    }

    /** The stops as Instagram's code loads them, so a slip in one of the four numbers shows here. */
    @Test
    fun theStopsAreTheDoublesInstagramLoads() {
        assertEquals(
            listOf(0x3fc25e353f7ced91L, 0x3fd82f837b4a233aL, 0x3fe36d5cfaacd9e8L, 0x3fea978d4fdf3b64L),
            DEFAULT_RING_STOPS,
        )
    }

    /** The extension tells a story's gradient by the same four stops the patch checks the app for. */
    @Test
    fun theExtensionKnowsTheStopsThePatchChecks() {
        val fields = ExtensionDex.classDef(RING_COLORS.substringBefore("->")).staticFields.associateBy { it.name }
        val known = listOf("FIRST_STOP", "SECOND_STOP", "THIRD_STOP", "FOURTH_STOP").map { name ->
            val field = fields[name] ?: throw AssertionError("the extension's StoryRing has no $name")
            (field.initialValue as? FloatEncodedValue)?.value
                ?: throw AssertionError("$name starts with no float: ${field.initialValue}")
        }
        assertEquals(DEFAULT_RING_STOPS.map { Double.fromBits(it).toFloat() }, known)
    }

    /**
     * The hook goes first in the builder, takes the colors and the stops, and puts its answer where
     * the colors were, ahead of the copy the gradient is made from. The builder's own code and its
     * branch to the fallback are as they were.
     */
    @Test
    fun theBuilderAsksForItsColorsFirst() {
        val context = PatchContexts.of(listOf(spinner(), defaultRing()))

        assertEquals(listOf("$RING_SPEC->invoke"), context.findDefaultRingStops())
        val gradients = context.findRingGradients()
        val gradient = gradients.single()
        assertEquals("build", gradient.name)
        assertEquals(12, gradient.colors)
        assertEquals(9, gradient.stops)
        val original = NeutralNativePath(context.builder(gradient))
        context.colorRingGradients(gradients)

        val builder = context.builder(gradient)
        assertColored("stand-in", builder)
        original.assertPreserved("stand-in", builder, ADDED)
        val code = builder.implementation!!.instructions.toList()
        assertEquals("the gradient's copy of the colors comes after the hook", Opcode.MOVE_OBJECT_FROM16, code[3].opcode)
        assertEquals(6, (code[3] as OneRegisterInstruction).registerA)
    }

    /**
     * 454's ring view has a private setter with its own copy of the builder's code, and the stories
     * row's rings go through it (#518). It asks too, with its colors and stops in the same registers
     * as the builder's, since its `this` sits where the builder takes the ring.
     */
    @Test
    fun theSetterThatMakesItsOwnGradientAsksToo() {
        val context = PatchContexts.of(listOf(spinner(setters = 1), defaultRing()))

        val gradients = context.findRingGradients()
        assertEquals(listOf("build", "keep"), gradients.map { it.name })
        val setter = gradients.single { it.name == "keep" }
        assertEquals(SETTER_PARAMETERS, setter.parameters)
        assertEquals(12, setter.colors)
        assertEquals(9, setter.stops)
        val originals = gradients.associate { it.name to NeutralNativePath(context.builder(it)) }
        context.colorRingGradients(gradients)

        for (gradient in gradients) {
            val method = context.builder(gradient)
            assertColored(gradient.name, method)
            originals.getValue(gradient.name).assertPreserved(gradient.name, method, ADDED)
        }
    }

    /** A setter that hands its arrays to the builder, as on 386014704, is left alone: the builder asks for it. */
    @Test
    fun aSetterThatCallsTheBuilderIsLeftAlone() {
        val context = PatchContexts.of(listOf(spinner(setters = 1, setterMakesItsOwn = false), defaultRing()))

        val gradients = context.findRingGradients()
        assertEquals(listOf("build"), gradients.map { it.name })
        context.colorRingGradients(gradients)
        val setter = context.mutableClassDefBy(GRADIENT_SPINNER).methods.single { it.name == "keep" }
        assertTrue(setter.implementation!!.instructions.none { (it as? ReferenceInstruction)?.reference?.toString() == RING_COLORS })
    }

    @Test
    fun aBuildWithoutTheRingViewFailsThePatch() {
        refuses("has no $GRADIENT_SPINNER", listOf(defaultRing()))
    }

    @Test
    fun noBuilderOrTwoFailsThePatch() {
        refuses("found 0", listOf(spinner(builders = 0), defaultRing()))
        refuses("found 2", listOf(spinner(builders = 2), defaultRing()))
    }

    @Test
    fun twoSettersMakingTheirOwnGradientFailThePatch() {
        refuses("at most one setter in $GRADIENT_SPINNER making its own gradient from stops and colors, found 2", listOf(spinner(setters = 2), defaultRing()))
    }

    /** Any other method of the ring making a gradient from arrays would draw colors the patch never sees. */
    @Test
    fun aGradientMadeAnywhereElseInTheRingFailsThePatch() {
        refuses("$GRADIENT_SPINNER->drawn makes a gradient from colors and stops the patch can't find", listOf(spinner(elsewhere = true), defaultRing()))
    }

    @Test
    fun aSetterJumpingBackToItsStartFailsThePatch() {
        refuses("first instruction of $GRADIENT_SPINNER->keep", listOf(spinner(setters = 1, setterLoops = true), defaultRing()))
    }

    /** A builder that makes its gradient some other way wouldn't draw with the colors the hook answers. */
    @Test
    fun aBuilderThatNoLongerTakesArraysFailsThePatch() {
        refuses("no longer makes its gradient", listOf(spinner(fromArrays = false), defaultRing()))
    }

    /** A jump back to the first instruction would keep its label there and skip the hook put in front. */
    @Test
    fun aJumpBackToTheStartFailsThePatch() {
        refuses("jumps back to the first instruction", listOf(spinner(loops = true), defaultRing()))
    }

    @Test
    fun colorsPastTheRegistersACallCanListFailThePatch() {
        refuses("past the registers a call can list", listOf(spinner(registers = 24), defaultRing()))
    }

    /**
     * The extension knows a story's gradient by its stops. A build whose default ring has others, or
     * loads them somewhere that doesn't name the ring, would leave the choice doing nothing, so the
     * patch fails instead.
     */
    @Test
    fun aDefaultRingWithOtherStopsFailsThePatch() {
        val moved = DEFAULT_RING_STOPS.dropLast(1) + 0.9.toRawBits()
        refuses("four stops", listOf(spinner(), defaultRing(stops = moved)))
        refuses("four stops", listOf(spinner(), defaultRing(stops = DEFAULT_RING_STOPS.take(3))))
        refuses(
            "four stops",
            listOf(spinner(), defaultRing(named = false), defaultRing(type = "Lfixture/NamedElsewhere;", stops = emptyList())),
        )
    }

    /** A refusal leaves the builder as it was: everything is checked before the hook goes in. */
    @Test
    fun aRefusalLeavesTheBuilderAlone() {
        val context = PatchContexts.of(listOf(spinner(setters = 1), defaultRing(stops = emptyList())))
        assertThrows(PatchException::class.java) { context.findRingGradients() }
        for (name in listOf("build", "keep")) {
            val code = context.mutableClassDefBy(GRADIENT_SPINNER).methods.single { it.name == name }
                .implementation!!.instructions.toList()
            assertTrue(name, code.none { (it as? ReferenceInstruction)?.reference?.toString() == RING_COLORS })
        }
    }

    /**
     * In each declared build every place the ring view makes a gradient from colors and stops gets
     * the hook first, and one method puts the default ring together with the four stops. The
     * declared arm64 build makes it in two places, the builder and the setter the stories row uses.
     */
    @Test
    fun eachDeclaredBuildColorsTheRing() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                assertEquals("${bundle.name}: places the ring makes its gradient", 2, builds(bundle, bundle.name))
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildColorsTheRingToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** Patches one build's ring view and answers how many places got the hook. */
    private fun builds(bundle: File, label: String): Int {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type == GRADIENT_SPINNER || classDef.methods.any { it.namesOrStopsTheDefaultRing() }) {
                    holders += ImmutableClassDef.of(classDef)
                }
            }
        }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders.distinctBy { it.type }).distinctBy { it.type })

        assertEquals("$label: methods putting the default ring together", 1, context.findDefaultRingStops().size)
        val makers = context.classDefBy(GRADIENT_SPINNER).methods
            .filter { method -> method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == GRADIENT_FROM_ARRAYS } == true }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})" }.sorted()
        val gradients = context.findRingGradients()
        assertEquals("$label: every place the ring makes a gradient from colors and stops", makers,
            gradients.map { "${it.name}(${it.parameters.joinToString("")})" }.sorted())
        val originals = gradients.map { NeutralNativePath(context.builder(it)) }
        context.colorRingGradients(gradients)
        gradients.zip(originals).forEach { (gradient, original) ->
            val method = context.builder(gradient)
            assertColored("$label ${gradient.name}", method)
            original.assertPreserved("$label ${gradient.name}", method, ADDED)
        }
        return gradients.size
    }

    /**
     * One hook, first in the builder: a static call passing the colors' register then the stops',
     * its answer moved back into the colors' register, and no branch landing on either instruction.
     */
    private fun assertColored(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val first = method.implementation!!.registerCount - GRADIENT_PARAMETERS.size
        val colors = first + COLORS_PARAMETER
        val stops = first + STOPS_PARAMETER
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == RING_COLORS }
        assertEquals("$what: hooks", listOf(0), hooks)
        assertEquals("$what: the hook's call", Opcode.INVOKE_STATIC, code[0].opcode)
        val hook = code[0] as FiveRegisterInstruction
        assertEquals("$what: what the hook is passed", 2, hook.registerCount)
        assertEquals("$what: the colors go first", colors, hook.registerC)
        assertEquals("$what: the stops go second", stops, hook.registerD)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: back into the colors", colors, (code[1] as OneRegisterInstruction).registerA)
        assertTrue(
            "$what: the builder still makes its gradient from the arrays",
            code.drop(2).any { (it as? ReferenceInstruction)?.reference?.toString() == GRADIENT_FROM_ARRAYS },
        )

        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val hookAddresses = setOf(addresses[0], addresses[1])
        for ((at, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction) continue
            assertTrue("$what: the branch at $at lands on the hook", addresses[at] + instruction.codeOffset !in hookAddresses)
        }
    }

    private fun refuses(why: String, classes: List<ClassDef>) {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findRingGradients() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(why))
    }

    private fun BytecodePatchContext.builder(gradient: RingGradient): Method =
        mutableClassDefBy(GRADIENT_SPINNER).methods.single {
            it.name == gradient.name && it.parameterTypes.map(CharSequence::toString) == gradient.parameters
        }

    /** Whether the method loads the default ring's name or every one of its stops, read in one pass. */
    private fun Method.namesOrStopsTheDefaultRing(): Boolean {
        val code = implementation?.instructions ?: return false
        val stops = HashSet<Long>()
        for (instruction in code) {
            if (((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == DEFAULT_RING) return true
            val literal = (instruction as? WideLiteralInstruction)?.wideLiteral ?: continue
            if (literal in DEFAULT_RING_STOPS) stops += literal
        }
        return stops.size == DEFAULT_RING_STOPS.size
    }

    private companion object {
        /** Where the hook's two instructions sit in a patched builder. */
        val ADDED = setOf(0, 1)
    }
}
