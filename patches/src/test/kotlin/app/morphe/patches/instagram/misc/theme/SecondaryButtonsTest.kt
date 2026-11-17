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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Edit profile and Share profile take the accent backgrounds' top shade through every load of their fill (#187). */
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

    /** The fills found but no hook in the extension refuses under the patch's name, and no fill at all changes nothing. */
    @Test
    fun withoutTheHookItRefuses() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(styles())).fillSecondaryButtons() }
        assertTrue(failure.message, failure.message!!.startsWith("Accent color"))
        val none = PatchContexts.of(listOf(ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        assertEquals(emptyList<SecondaryFill>(), none.findSecondaryFills())
        none.fillSecondaryButtons()
    }

    /**
     * Each load of either fill is handed on with its method's context, the static method's too, and a
     * method taking no context first is left alone. A jump onto the instruction after a load lands past
     * the hook, so only the loaded fill goes through it.
     */
    @Test
    fun onlyTheLoadedFillGoesThroughTheHook() {
        val context = PatchContexts.of(listOf(styles(), ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        val fills = context.findSecondaryFills().associateBy { it.method.name }
        assertEquals("the instance fill and the static light one, not the one without a context", setOf("fill", "light"), fills.keys)
        assertEquals("the instance method's context, past this", listOf(2, 1), listOf(fills.getValue("fill").context, fills.getValue("light").context))
        assertEquals("the loads", listOf(listOf(1), listOf(0)), listOf(fills.getValue("fill").loads, fills.getValue("light").loads))

        context.fillSecondaryButtons()

        val methods = context.mutableClassDefBy(STYLES).methods.associateBy { it.name }
        val code = methods.getValue("fill").code()
        val hook = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == SECONDARY_BUTTON_FILL }
        assertEquals("the hook follows the filled color", 2, hook)
        val jump = code.indexOfFirst { kind(it.opcode) == Opcode.GOTO }
        assertEquals("the goto lands on the return past the hook", hook + 2, target(code, jump))
        assertEquals("the branch still skips the filled color", 5, target(code, 0))
        val light = methods.getValue("light").code()
        assertEquals("the static method hands on its own context", listOf(1, 0), (light[1] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertTrue("no context, no hook", methods.getValue("odd").code().none { it.opcode == Opcode.INVOKE_STATIC })
    }

    /**
     * The one class loading both fills of a pair hands the context and each loaded fill to the extension
     * right after the load, in every method loading one, keeps the answer in the fill's register, and
     * keeps Instagram's code around it.
     */
    private fun handsOn(bundle: File, label: String) {
        val classes = fillClasses(bundle)
        val context = PatchContexts.of(classes + ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME)))
        val fills = context.findSecondaryFills()
        val styles = fills.map { it.method.definingClass }.toSet()
        assertEquals("$label: one class answering the fill", 1, styles.size)
        val loading = classes.single { it.type == styles.single() }.methods.filter { method -> method.code().any { it.loadsFill() } }
        assertEquals("$label: every method loading a fill", loading.map { it.name }.sorted(), fills.map { it.method.name }.sorted())
        val loaded = fills.flatMap { fill -> fill.loads.map { (fill.method.code()[it] as NarrowLiteralInstruction).narrowLiteral.toLong() and 0xffffffffL } }.toSet()
        assertTrue("$label: both fills of a pair, $loaded", SECONDARY_FILL_PAIRS.any { (filled, light) -> loaded == setOf(filled, light) })
        val before = fills.associate { it.method.name to it.method.code() }

        context.fillSecondaryButtons()

        for (fill in fills) {
            val where = "$label: ${fill.method.name}"
            val code = context.mutableClassDefBy(fill.method.definingClass).methods.single {
                it.name == fill.method.name && it.parameterTypes.map(Any::toString) == fill.method.parameterTypes.map(Any::toString)
            }.code()
            val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == SECONDARY_BUTTON_FILL }
            assertEquals("$where: one hook per load", fill.loads.size, hooks.size)
            for (at in hooks) {
                assertTrue("$where: the hook follows a load", code[at - 1].loadsFill())
                val register = (code[at - 1] as OneRegisterInstruction).registerA
                val call = code[at] as FiveRegisterInstruction
                assertEquals("$where: the call", Opcode.INVOKE_STATIC, code[at].opcode)
                assertEquals("$where: the context and the fill", listOf(2, fill.context, register), listOf(call.registerCount, call.registerC, call.registerD))
                assertEquals("$where: the answer kept in its place", listOf(Opcode.MOVE_RESULT, register), listOf(code[at + 1].opcode, (code[at + 1] as OneRegisterInstruction).registerA))
            }
            val kept = code.filterIndexed { index, _ -> hooks.none { index == it || index == it + 1 } }
            assertEquals("$where: Instagram's own code around it", before.getValue(fill.method.name).map { kind(it.opcode) }, kept.map { kind(it.opcode) })
        }
    }

    /** The bundle's classes loading one of [SECONDARY_FILL_IDS]. */
    private fun fillClasses(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.code().any { it.loadsFill() } }) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    private fun Instruction.loadsFill(): Boolean =
        this is NarrowLiteralInstruction && this is OneRegisterInstruction && (narrowLiteral.toLong() and 0xffffffffL) in SECONDARY_FILL_IDS

    /**
     * A button styles class: an instance fill that branches past the filled color for any other style, calls
     * another class for it, then jumps back onto the filled color's return,
     * `if-nez p2; const v0, fill; return v0; invoke-static {p1, p2}; move-result v0; goto return`; a static
     * method answering the light fill for a context; and one loading the fill with no context at all.
     */
    private fun styles(): ClassDef {
        val (filled, light) = SECONDARY_FILL_PAIRS.first()
        val other = ImmutableMethodReference("Lfixture/Other;", "fill", listOf(CONTEXT, STYLE), "I")
        val fill = listOf(
            ImmutableInstruction21t(Opcode.IF_NEZ, 3, 6),
            ImmutableInstruction31i(Opcode.CONST, 0, filled.toInt()),
            ImmutableInstruction11x(Opcode.RETURN, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 2, 3, 0, 0, 0, other),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction10t(Opcode.GOTO, -5),
        )
        val methods = listOf(
            method("fill", listOf(CONTEXT, STYLE), AccessFlags.PUBLIC.value, 4, fill),
            method("light", listOf(CONTEXT), AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 2, loadAndReturn(light)),
            method("odd", listOf("I"), AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 2, loadAndReturn(filled)),
        )
        return ImmutableClassDef(STYLES, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
    }

    private fun loadAndReturn(id: Long): List<Instruction> =
        listOf(ImmutableInstruction31i(Opcode.CONST, 0, id.toInt()), ImmutableInstruction11x(Opcode.RETURN, 0))

    private fun method(name: String, parameters: List<String>, flags: Int, registers: Int, code: List<Instruction>) = ImmutableMethod(
        STYLES, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "I", flags, null, null,
        ImmutableMethodImplementation(registers, code, null, null),
    )

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
