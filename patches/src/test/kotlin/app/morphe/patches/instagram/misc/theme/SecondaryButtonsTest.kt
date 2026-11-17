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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Edit profile and Share profile take the accent backgrounds' top shade through their button style's fill (#187). */
class SecondaryButtonsTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(ACCENT_THEME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SECONDARY_BUTTON_FILL is not in the extension: $declared", SECONDARY_BUTTON_FILL in declared)
    }

    @Test
    fun everyDeclaredBundleHandsItsFillOn() {
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
    fun everyOtherBuildHandsItsFillOn() {
        for (base in Fixtures.otherBuilds()) handsOn(base, base.parentFile.name)
    }

    /** The fill found but no hook in the extension refuses under the patch's name, and no fill at all changes nothing. */
    @Test
    fun withoutTheHookItRefuses() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(styles())).fillSecondaryButtons() }
        assertTrue(failure.message, failure.message!!.startsWith("Accent color"))
        val none = PatchContexts.of(listOf(ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        assertNull(none.findSecondaryFill())
        none.fillSecondaryButtons()
    }

    /** A branch onto the filled color's return lands on the hook, so the fill it returns goes through it too. */
    @Test
    fun aJumpOntoTheReturnGoesThroughTheHook() {
        val context = PatchContexts.of(listOf(styles(), ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        val fill = context.findSecondaryFill()
        assertNotNull(fill)
        assertEquals("the context, past this", 2, fill!!.context)
        assertEquals("only the filled color's return, the other call isn't its class's", listOf(2), fill.returns)

        context.fillSecondaryButtons()

        val code = context.mutableClassDefBy(STYLES).methods.single().code()
        val hook = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == SECONDARY_BUTTON_FILL }
        assertEquals("the hook follows the filled color", 2, hook)
        val jump = code.indexOfFirst { kind(it.opcode) == Opcode.GOTO }
        assertEquals("the goto lands on the hook", hook, target(code, jump))
        assertEquals("the branch still skips the filled color", 5, target(code, 0))
    }

    /**
     * The one method answering the fill hands the context and the fill to the extension right before
     * each of its two returns of it and returns the answer, with Instagram's code kept around them.
     */
    private fun handsOn(bundle: File, label: String) {
        val context = PatchContexts.of(fillClasses(bundle) + ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME)))
        val fill = context.findSecondaryFill()
        assertNotNull("$label: no secondary button fill", fill)
        assertEquals("$label: the filled color's return and the dark one's", 2, fill!!.returns.size)
        val before = fill.method.code()

        context.fillSecondaryButtons()

        val code = context.mutableClassDefBy(fill.method.definingClass).methods.single {
            it.name == fill.method.name && it.parameterTypes.map(Any::toString) == fill.method.parameterTypes.map(Any::toString)
        }.code()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == SECONDARY_BUTTON_FILL }
        assertEquals("$label: one hook per return", 2, hooks.size)
        for (at in hooks) {
            val call = code[at] as FiveRegisterInstruction
            val register = (code[at + 1] as OneRegisterInstruction).registerA
            assertEquals("$label: the call", Opcode.INVOKE_STATIC, code[at].opcode)
            assertEquals("$label: the context and the fill", listOf(2, fill.context, register), listOf(call.registerCount, call.registerC, call.registerD))
            assertEquals("$label: the answer kept", Opcode.MOVE_RESULT, code[at + 1].opcode)
            assertEquals("$label: and returned", listOf(Opcode.RETURN, register), listOf(code[at + 2].opcode, (code[at + 2] as OneRegisterInstruction).registerA))
        }
        assertEquals(
            "$label: one after the filled color, one after the dark call",
            listOf(true, true),
            listOf(
                hooks.any { (code[it - 1] as? NarrowLiteralInstruction)?.narrowLiteral?.toLong() in SECONDARY_FILL_IDS },
                hooks.any { code[it - 1].opcode == Opcode.MOVE_RESULT && code[it - 2].opcode == Opcode.INVOKE_STATIC },
            ),
        )
        val kept = code.filterIndexed { index, _ -> hooks.none { index == it || index == it + 1 } }
        assertEquals("$label: Instagram's own code around them", before.map { kind(it.opcode) }, kept.map { kind(it.opcode) })
    }

    /** The bundle's classes loading one of [SECONDARY_FILL_IDS]. */
    private fun fillClasses(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral?.toLong() in SECONDARY_FILL_IDS } == true
                }
                if (loads) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    /**
     * A button styles class whose fill method branches past the filled color for any other style, calls
     * another class for it, then jumps back onto the filled color's return:
     * `if-nez p2; const v0, fill; return v0; invoke-static {p1, p2}; move-result v0; goto return`.
     */
    private fun styles(): ClassDef {
        val other = ImmutableMethodReference("Lfixture/Other;", "fill", listOf(CONTEXT, STYLE), "I")
        val code = listOf(
            ImmutableInstruction21t(Opcode.IF_NEZ, 3, 6),
            ImmutableInstruction31i(Opcode.CONST, 0, SECONDARY_FILL_IDS.first().toInt()),
            ImmutableInstruction11x(Opcode.RETURN, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 2, 3, 0, 0, 0, other),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction10t(Opcode.GOTO, -5),
        )
        val method = ImmutableMethod(
            STYLES, "fill", listOf(CONTEXT, STYLE).map { ImmutableMethodParameter(it, null, null) }, "I", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(4, code, null, null),
        )
        return ImmutableClassDef(STYLES, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method))
    }

    /** The index of the instruction the branch at [at] goes to. */
    private fun target(code: List<Instruction>, at: Int): Int {
        val offsets = code.runningFold(0) { offset, instruction -> offset + instruction.codeUnits }
        return offsets.indexOf(offsets[at] + (code[at] as OffsetInstruction).codeOffset)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** An insert can push a branch past a plain goto's reach, and dexlib2 widens it. */
    private fun kind(opcode: Opcode) = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    private companion object {
        const val STYLES = "Lfixture/ButtonStyles;"
        const val STYLE = "Lfixture/ButtonStyle;"
        const val CONTEXT = "Landroid/content/Context;"
    }
}
