/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Meta's own menus, the account switcher among them, take the accent through their design system colors (#187). */
class DspColorsTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(ACCENT_THEME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SWAP_DSP_LIGHT is not in the extension: $declared", SWAP_DSP_LIGHT in declared)
        assertTrue("$SWAP_DSP_DARK is not in the extension: $declared", SWAP_DSP_DARK in declared)
    }

    @Test
    fun everyDeclaredBundleHandsItsColorsOn() {
        var checked = 0
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                handsOn(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun everyOtherBuildHandsItsColorsOn() {
        for (base in Fixtures.otherBuilds()) handsOn(base, base.parentFile.name)
    }

    /** The color found but no hook in the extension refuses under the patch's name, and no color class at all changes nothing. */
    @Test
    fun withoutTheHooksItRefuses() {
        val bundle = Fixtures.otherBuilds().first()
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(FixtureDex.classes(bundle, setOf(DSP_COLOR)).values.toList()).swapDspColors()
        }
        assertTrue(failure.message, failure.message!!.startsWith("Accent color"))
        val none = PatchContexts.of(listOf(ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        assertNull(none.findDspColor())
        none.swapDspColors()
    }

    /**
     * The one constructor hands its light color, then its dark one, to the extension at its start and
     * keeps each answer in the parameter's own register, with Instagram's code after.
     */
    private fun handsOn(bundle: File, label: String) {
        val stock = FixtureDex.classes(bundle, setOf(DSP_COLOR)).getValue(DSP_COLOR)
        val before = stock.methods.single { it.name == "<init>" }.code()
        val context = PatchContexts.of(listOf(stock, ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        assertNotNull("$label: no design system color", context.findDspColor())

        context.swapDspColors()

        val constructor = context.mutableClassDefBy(DSP_COLOR).methods.single { it.name == "<init>" }
        val code = constructor.code()
        val light = constructor.implementation!!.registerCount - 2
        val dark = light + 1
        assertEquals("$label: the hooks", listOf(SWAP_DSP_LIGHT, SWAP_DSP_DARK), code.take(4).mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() })
        assertEquals(
            "$label: the shape",
            listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT),
            code.take(4).map { it.opcode },
        )
        assertEquals(
            "$label: each color handed over and written back",
            listOf(light, light, dark, dark),
            listOf(
                (code[0] as RegisterRangeInstruction).startRegister, (code[1] as OneRegisterInstruction).registerA,
                (code[2] as RegisterRangeInstruction).startRegister, (code[3] as OneRegisterInstruction).registerA,
            ),
        )
        assertEquals("$label: one register each", listOf(1, 1), listOf((code[0] as RegisterRangeInstruction).registerCount, (code[2] as RegisterRangeInstruction).registerCount))
        assertEquals("$label: Instagram's own code after", before.map { it.opcode }, code.drop(4).map { it.opcode })
        assertEquals("$label: the light color is what goes to the light field", "A01", before.lightField(light))
    }

    /** The name of the int field [register] is written to, as the stock constructor writes it. */
    private fun List<Instruction>.lightField(register: Int): String? = firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.IPUT || (instruction as TwoRegisterInstruction).registerA != register) return@firstNotNullOfOrNull null
        ((instruction as ReferenceInstruction).reference as FieldReference).name
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
}
