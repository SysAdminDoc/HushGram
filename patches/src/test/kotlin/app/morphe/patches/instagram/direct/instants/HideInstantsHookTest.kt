/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide Instants (#59): Instagram's one Instants check answers no from its first instruction while
 * the switch is on, and anything the patch can't tell apart fails it before an instruction changes.
 */
class HideInstantsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HIDE_INSTANTS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDE_INSTANTS is not in the extension: $declared", HIDE_INSTANTS.substringAfter("->") in declared)
    }

    @Test
    fun theCheckAsksTheExtensionFirst() {
        val context = PatchContexts.of(listOf(gate()))

        val found = context.findInstantsGate()
        context.holdInstantsGate(found)

        assertEquals(GATE, found.type)
        assertAnswersNoFirst(GATE, context.mutableClassDefBy(GATE).methods.single { it.name == "A0O" })
    }

    /** A method holding one of the two flags is some other check. */
    @Test
    fun aCheckWithOneFlagFailsThePatch() {
        val context = PatchContexts.of(listOf(gate(flags = listOf(INSTANTS_FLAG))))
        refuses("0 Instants checks") { context.findInstantsGate() }
    }

    @Test
    fun twoChecksFailThePatch() {
        val context = PatchContexts.of(listOf(gate(), gate("Lfixture/OtherGate;")))
        refuses("2 Instants checks") { context.findInstantsGate() }
    }

    /** The flags in an instance method, or one taking other arguments, are no Instants check. */
    @Test
    fun aCheckOfAnotherShapeFailsThePatch() {
        refuses("0 Instants checks") { PatchContexts.of(listOf(gate(static = false))).findInstantsGate() }
        refuses("0 Instants checks") { PatchContexts.of(listOf(gate(parameters = listOf(GATE_PARAMETERS[0])))).findInstantsGate() }
    }

    /** With no local register to spare, the early answer has nowhere to go. */
    @Test
    fun aCheckWithoutALocalFailsThePatch() {
        val context = PatchContexts.of(listOf(gate(registers = 2)))
        val gate = context.findInstantsGate()
        refuses("needs 1") { context.holdInstantsGate(gate) }
    }

    /** In each declared build: the one Instants check, asking the extension first. */
    @Test
    fun eachDeclaredBuildHidesInstants() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val kept = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        val loads = classDef.methods.flatMap { it.instructions() }
                            .any { (it as? WideLiteralInstruction)?.wideLiteral == INSTANTS_FLAG }
                        if (loads) kept += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(kept)

                val gate = context.findInstantsGate()
                context.holdInstantsGate(gate)

                val method = context.mutableClassDefBy(gate.type).methods.single {
                    it.name == gate.name && it.parameterTypes.map(CharSequence::toString) == GATE_PARAMETERS
                }
                assertAnswersNoFirst("${bundle.name} ${gate.type}->${gate.name}", method)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /** The hook, its answer in v0, a branch past the early return to the check's own first instruction, and that return of no. */
    private fun assertAnswersNoFirst(what: String, method: Method) {
        val code = method.instructions()
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$what: the hook", HIDE_INSTANTS, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer's register", 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the branch's register", 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch's target", 5, (method.implementation!!.instructions.toList()[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: no", Opcode.CONST_4, code[3].opcode)
        assertEquals("$what: no's value", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: the early return", Opcode.RETURN, code[4].opcode)
        assertEquals("$what: the register returned", 0, (code[4] as OneRegisterInstruction).registerA)
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == HIDE_INSTANTS })
    }

    private companion object {
        const val GATE = "Lfixture/InstantsGate;"

        /** Shaped like 450's: static (UserSession, boolean) answering a boolean, loading both flags. */
        fun gate(
            type: String = GATE,
            flags: List<Long> = listOf(INSTANTS_FLAG, INSTANTS_SECOND_FLAG),
            static: Boolean = true,
            parameters: List<String> = GATE_PARAMETERS,
            registers: Int = 8,
        ): ClassDef {
            val code = flags.map { ImmutableInstruction51l(Opcode.CONST_WIDE, 2, it) } +
                listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0))
            val access = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A0O", parameters.map { ImmutableMethodParameter(it, null, null) }, "Z", access, null, null,
                        ImmutableMethodImplementation(registers, code, null, null),
                    ),
                ),
            )
        }
    }
}
