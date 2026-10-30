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
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.FloatEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
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
        val gradient = context.findRingGradient()
        assertEquals("build", gradient.name)
        assertEquals(12, gradient.colors)
        assertEquals(9, gradient.stops)
        val original = NeutralNativePath(context.builder(gradient))
        context.colorRingGradient(gradient)

        val builder = context.builder(gradient)
        assertColored("stand-in", builder)
        original.assertPreserved("stand-in", builder, ADDED)
        val code = builder.implementation!!.instructions.toList()
        assertEquals("the gradient's copy of the colors comes after the hook", Opcode.MOVE_OBJECT_FROM16, code[3].opcode)
        assertEquals(6, (code[3] as OneRegisterInstruction).registerA)
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
        val context = PatchContexts.of(listOf(spinner(), defaultRing(stops = emptyList())))
        assertThrows(PatchException::class.java) { context.findRingGradient() }
        val code = context.mutableClassDefBy(GRADIENT_SPINNER).methods.single { it.name == "build" }
            .implementation!!.instructions.toList()
        assertTrue(code.none { (it as? ReferenceInstruction)?.reference?.toString() == RING_COLORS })
    }

    /**
     * In each declared build the ring view has its one builder, the hook lands first in it, and one
     * method puts the default ring together with the four stops.
     */
    @Test
    fun eachDeclaredBuildColorsTheRing() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildColorsTheRingToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
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
        val gradient = context.findRingGradient()
        val original = NeutralNativePath(context.builder(gradient))
        context.colorRingGradient(gradient)
        val builder = context.builder(gradient)
        assertColored(label, builder)
        original.assertPreserved(label, builder, ADDED)
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
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findRingGradient() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(why))
    }

    private fun BytecodePatchContext.builder(gradient: RingGradient): Method =
        mutableClassDefBy(GRADIENT_SPINNER).methods.single {
            it.name == gradient.name && it.parameterTypes.map(CharSequence::toString) == GRADIENT_PARAMETERS
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
        const val RING_SPEC = "Lfixture/DefaultRing;"
        const val TILE_MODE = "Landroid/graphics/Shader\$TileMode;"

        /** Where the hook's two instructions sit in a patched builder. */
        val ADDED = setOf(0, 1)

        /**
         * The ring view with a builder shaped like Instagram's: fifteen registers, the last seven
         * its parameters, so the stops arrive in v9 and the colors in v12.
         *
         *     a0  move-object/from16 v7, v9      the stops
         *     a2  move-object/from16 v6, v12     the colors
         *     a4  if-eqz v9, :fallback
         *     a6  const/4 v2, 0
         *     a7  sget-object v8, Shader.TileMode.CLAMP
         *     a9  new-instance v1, LinearGradient
         *     a11 invoke-direct/range {v1 .. v8}, LinearGradient.<init>
         *     a14 return-object v1
         *     a15 :fallback const/4 v1, 0
         *     a16 return-object v1
         *     a17 goto a0                        only with [loops]
         */
        fun spinner(builders: Int = 1, fromArrays: Boolean = true, loops: Boolean = false, registers: Int = 15): ClassDef {
            val first = registers - GRADIENT_PARAMETERS.size
            val stops = first + STOPS_PARAMETER
            val colors = first + COLORS_PARAMETER
            val made = ImmutableMethodReference(
                LINEAR_GRADIENT, "<init>",
                if (fromArrays) listOf("F", "F", "F", "F", "[I", "[F", TILE_MODE) else listOf("F", "F", "F", "F", "I", "I", TILE_MODE),
                "V",
            )
            val code = mutableListOf<Instruction>(
                ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 7, stops),
                ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 6, colors),
                ImmutableInstruction21t(Opcode.IF_EQZ, stops, 11),
                ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
                ImmutableInstruction21c(Opcode.SGET_OBJECT, first, ImmutableFieldReference(TILE_MODE, "CLAMP", TILE_MODE)),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(LINEAR_GRADIENT)),
                ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 1, 8, made),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
            )
            if (loops) code += ImmutableInstruction10t(Opcode.GOTO, -17)
            val methods = listOf("build", "buildAgain").take(builders).map { name ->
                ImmutableMethod(
                    GRADIENT_SPINNER, name, GRADIENT_PARAMETERS.map { ImmutableMethodParameter(it, null, null) }, LINEAR_GRADIENT,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(registers, code, null, null),
                )
            }
            return ImmutableClassDef(
                GRADIENT_SPINNER, AccessFlags.PUBLIC.value, "Landroid/view/View;",
                null, null, null, null, methods,
            )
        }

        /**
         * The class that puts the default ring together: one method loading the ring's name and its
         * stops as doubles. With [named] false it loads another ring's name.
         */
        fun defaultRing(type: String = RING_SPEC, named: Boolean = true, stops: List<Long> = DEFAULT_RING_STOPS): ClassDef {
            val code = mutableListOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(if (named) DEFAULT_RING else "rainbow")),
            )
            stops.forEach { code += ImmutableInstruction51l(Opcode.CONST_WIDE, 1, it) }
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
            val method = ImmutableMethod(
                type, "invoke", emptyList(), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(4, code, null, null),
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
                null, null, null, null, listOf(method),
            )
        }
    }
}
