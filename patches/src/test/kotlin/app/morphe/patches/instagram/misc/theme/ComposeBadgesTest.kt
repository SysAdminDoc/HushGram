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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Badges in the accent color reaches the Messages folder chip's unread dot, which Compose paints from a palette (#671). */
class ComposeBadgesTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(ACCENT_THEME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SWAP_BADGE is not in the extension: $declared", SWAP_BADGE in declared)
    }

    @Test
    fun everyDeclaredBundlePaintsTheDotThroughTheExtension() {
        var checked = 0
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                paints(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun everyOtherBuildPaintsTheDotThroughTheExtension() {
        for (base in Fixtures.otherBuilds()) paints(base, base.parentFile.name)
    }

    /** The chip found but no hook in the extension refuses under the patch's name, and no chip at all changes nothing. */
    @Test
    fun withoutTheHookItRefuses() {
        val bundle = Fixtures.otherBuilds().first()
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(FixtureDex.classesLoading(bundle, FOLDER_CHIP_CONTENT)).tintChipDot() }
        assertTrue(failure.message, failure.message!!.startsWith("Accent color"))
        PatchContexts.of(listOf(ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME)))).tintChipDot()
    }

    /**
     * The chip's content has one background painting a palette's color, the dot, and right before it
     * the color goes through the extension and comes back in the same pair. The chip's own background
     * reads the content's field and stays, and the rest of the code is Instagram's.
     */
    private fun paints(bundle: File, label: String) {
        val context = PatchContexts.of(FixtureDex.classesLoading(bundle, FOLDER_CHIP_CONTENT) + ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME)))
        val dot = context.findChipDot()
        assertNotNull("$label: no folder chip dot", dot)
        val stock = dot!!.method.code()
        val painted = (stock[dot.call] as ReferenceInstruction).reference.toString()
        context.tintChipDot()
        val code = context.mutableClassDefBy(dot.method.definingClass).methods.single {
            it.name == dot.method.name && it.parameterTypes.map(Any::toString) == dot.method.parameterTypes.map(Any::toString)
        }.code()
        val hook = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == SWAP_BADGE }
        assertTrue("$label: no hook", hook >= 0)
        assertEquals("$label: one hook", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == SWAP_BADGE })
        assertEquals("$label: handed as a range", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$label: the dot's color handed over", listOf(dot.color, 2), (code[hook] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("$label: and written back", Opcode.MOVE_RESULT_WIDE, code[hook + 1].opcode)
        assertEquals("$label: into the same pair", dot.color, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: the dot painted right after", painted, (code[hook + 2] as ReferenceInstruction).reference.toString())
        assertEquals(
            "$label: Instagram's own code around it",
            stock.kinds(),
            (code.subList(0, hook) + code.subList(hook + 2, code.size)).kinds(),
        )
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun List<Instruction>.kinds(): List<Opcode> = filter { it.opcode != Opcode.NOP }.map {
        if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode
    }
}
