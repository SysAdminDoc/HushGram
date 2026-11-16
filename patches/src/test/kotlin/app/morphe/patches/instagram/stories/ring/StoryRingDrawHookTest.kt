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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hide rings outside the stories row (#719): the ring view's draw asks the extension first. */
class StoryRingDrawHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(RING_HIDES.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$RING_HIDES is not in the extension: $declared", RING_HIDES.substringAfter("->") in declared)
    }

    /** The draw asks first and returns on a yes. Its own code, branch included, is as it was. */
    @Test
    fun theDrawAsksFirst() {
        val context = PatchContexts.of(listOf(spinner()))
        val draw = context.findRingDraw()
        val original = NeutralNativePath(context.draw())
        context.hideRingsOutsideTheRow(draw)

        val patched = context.draw()
        assertHooked("stand-in", patched)
        original.assertPreserved("stand-in", patched, ADDED)
    }

    @Test
    fun aBuildWithoutTheRingViewFailsThePatch() {
        refuses("has no $GRADIENT_SPINNER", listOf(spinner().renamed("Lfixture/Other;")))
    }

    @Test
    fun aRingWithoutItsOwnDrawFailsThePatch() {
        refuses("expected one onDraw(Canvas)", listOf(spinner(draws = false)))
    }

    @Test
    fun aDrawWithNoLocalFailsThePatch() {
        refuses("no local register", listOf(spinner(registers = 2)))
    }

    @Test
    fun aJumpBackToTheStartFailsThePatch() {
        refuses("jumps back to the first instruction", listOf(spinner(loops = true)))
    }

    /** A refusal comes before anything changes, so the draw is the app's own. */
    @Test
    fun aRefusalLeavesTheDrawAlone() {
        val context = PatchContexts.of(listOf(spinner(loops = true)))
        val before = context.draw().implementation!!.instructions.map { it.opcode }
        assertThrows(PatchException::class.java) { context.findRingDraw() }
        assertEquals(before, context.draw().implementation!!.instructions.map { it.opcode })
    }

    /** Every declared build's ring view takes the hook, with `this` in whatever register that build gives it. */
    @Test
    fun eachDeclaredBuildHidesTheRing() {
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
    fun eachOtherBuildHidesTheRingToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filter { it.type == GRADIENT_SPINNER }.forEach { holders += ImmutableClassDef.of(it) }
        }
        assertEquals("$label: ring views", 1, holders.distinctBy { it.type }.size)
        val context = PatchContexts.of(holders.distinctBy { it.type })
        val draw = context.findRingDraw()
        val original = NeutralNativePath(context.draw())
        context.hideRingsOutsideTheRow(draw)
        val patched = context.draw()
        assertHooked(label, patched)
        original.assertPreserved(label, patched, ADDED)
    }

    /**
     * Four added instructions at the top: the call with `this` alone in a range, its answer in v0, a
     * branch on no to what was the first instruction, and the early return. Nothing else lands on
     * the added ones.
     */
    private fun assertHooked(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val self = method.implementation!!.registerCount - 2
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == RING_HIDES }
        assertEquals("$what: hooks", listOf(0), hooks)
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        val call = code[0] as RegisterRangeInstruction
        assertEquals("$what: the call takes the ring alone", 1, call.registerCount)
        assertEquals("$what: the ring is this", self, call.startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer's register", 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch on no", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the branch reads the answer", 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the early return", Opcode.RETURN_VOID, code[3].opcode)

        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals("$what: no goes on to the draw's own first instruction",
            addresses[4], addresses[2] + (code[2] as OffsetInstruction).codeOffset)
        val added = ADDED.map { addresses[it] }.toSet()
        for ((at, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction) continue
            assertTrue("$what: the branch at $at lands on the hook", addresses[at] + instruction.codeOffset !in added)
        }
    }

    private fun refuses(why: String, classes: List<ClassDef>) {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findRingDraw() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(why))
    }

    private fun BytecodePatchContext.draw(): Method =
        mutableClassDefBy(GRADIENT_SPINNER).methods.single {
            it.name == RING_DRAW && it.parameterTypes.map(CharSequence::toString) == RING_DRAW_PARAMETERS
        }

    private fun ClassDef.renamed(type: String): ClassDef = ImmutableClassDef(
        type, accessFlags, superclass, null, null, null, null,
        methods.map { ImmutableMethod(type, it.name, it.parameters, it.returnType, it.accessFlags, null, null, it.implementation) },
    )

    private companion object {
        /** Where the hook's four instructions sit in a patched draw. */
        val ADDED = setOf(0, 1, 2, 3)

        const val LINEAR_GRADIENT_FIELD = "Landroid/graphics/LinearGradient;"

        /**
         * The ring view with a draw shaped like 454's start: [registers] registers, the last two
         * `this` and the canvas.
         *
         *     a0 const/4 v0, 0
         *     a1 iget-object v0, this, gradient
         *     a3 if-nez v0, :draw
         *     a5 return-void
         *     a6 :draw return-void
         *     a7 goto a0            only with [loops]
         *
         * With [draws] false the view has no draw of its own.
         */
        fun spinner(registers: Int = 6, loops: Boolean = false, draws: Boolean = true): ClassDef {
            val self = registers - 2
            val local = if (registers > 2) 0 else self
            val code = mutableListOf<Instruction>(
                ImmutableInstruction11n(Opcode.CONST_4, local, 0),
                ImmutableInstruction22c(
                    Opcode.IGET_OBJECT, local, self,
                    ImmutableFieldReference(GRADIENT_SPINNER, "gradient", LINEAR_GRADIENT_FIELD),
                ),
                ImmutableInstruction21t(Opcode.IF_NEZ, local, 3),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            if (loops) code += ImmutableInstruction10t(Opcode.GOTO, -7)
            val methods = listOfNotNull(
                ImmutableMethod(
                    GRADIENT_SPINNER, RING_DRAW, RING_DRAW_PARAMETERS.map { ImmutableMethodParameter(it, null, null) }, "V",
                    AccessFlags.PUBLIC.value, null, null,
                    ImmutableMethodImplementation(registers, code, null, null),
                ).takeIf { draws },
                ImmutableMethod(
                    GRADIENT_SPINNER, "onMeasure", listOf(ImmutableMethodParameter("I", null, null), ImmutableMethodParameter("I", null, null)), "V",
                    AccessFlags.PUBLIC.value, null, null,
                    ImmutableMethodImplementation(3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
                ),
            )
            return ImmutableClassDef(
                GRADIENT_SPINNER, AccessFlags.PUBLIC.value, "Landroid/view/View;",
                null, null, null, null, methods,
            )
        }
    }
}
