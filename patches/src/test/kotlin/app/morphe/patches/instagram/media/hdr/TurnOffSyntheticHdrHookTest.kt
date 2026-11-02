/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.hdr

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The AV1 decoder settings' constructor hands the enhancement mode it writes to HdrBoost first, so
 * the synthetic HDR modes read as none while the switch is on (#85).
 */
class TurnOffSyntheticHdrHookTest {
    private val mode = ImmutableFieldReference(DAV1D_SETTING, "enhancementMode", "I")

    /**
     * The settings as Instagram has them, cut down: a constructor taking the mode in v2 that copies
     * it to v0, branches onto the write past a const and writes v0 into the field on this (v1), and a
     * getter reading the field. Another class's enhancementMode write sits in [other].
     */
    private fun settings(type: String = DAV1D_SETTING, field: ImmutableFieldReference = mode): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "<init>", listOf(ImmutableMethodParameter("I", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(
                    3,
                    listOf(
                        ImmutableInstruction12x(Opcode.MOVE, 0, 2),
                        ImmutableInstruction21t(Opcode.IF_EQZ, 2, 3),
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        ImmutableInstruction22c(Opcode.IPUT, 0, 1, field),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            ),
            ImmutableMethod(
                type, "getEnhancementMode", emptyList(), "I", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(
                    2,
                    listOf(ImmutableInstruction22c(Opcode.IGET, 0, 1, field), ImmutableInstruction11x(Opcode.RETURN, 0)),
                    null, null,
                ),
            ),
        ),
    )

    private val other = "Lfixture/OtherSetting;"

    @Test
    fun theConstructorsWriteAsksHdrBoostFirst() {
        val context = PatchContexts.of(listOf(settings(), settings(other, ImmutableFieldReference(other, "enhancementMode", "I"))))

        assertEquals(1, context.holdSyntheticHdrOff())

        val code = context.mutableClassDefBy(DAV1D_SETTING).methods.single { it.name == "<init>" }.instructions()
        assertEquals(
            listOf(Opcode.MOVE, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IPUT, Opcode.RETURN_VOID),
            code.map { it.opcode },
        )
        assertEquals(ENHANCEMENT_MODE, (code[3] as ReferenceInstruction).reference.toString())
        assertEquals("the register written goes in", listOf(0), code[3].registers())
        assertEquals("and comes back to it", 0, (code[4] as OneRegisterInstruction).registerA)
        assertEquals("the write keeps its register", 0, (code[5] as TwoRegisterInstruction).registerA)
        assertEquals("the branch onto the write lands on the hook", 3, code.landing(1))

        val getter = context.mutableClassDefBy(DAV1D_SETTING).methods.single { it.name == "getEnhancementMode" }.instructions()
        assertEquals("a read stays", listOf(Opcode.IGET, Opcode.RETURN), getter.map { it.opcode })
        val elsewhere = context.mutableClassDefBy(other).methods.single { it.name == "<init>" }.instructions()
        assertEquals("another class's write stays", 5, elsewhere.size)
    }

    /** Without the settings class the rest of the patch still works, so the hold steps aside. */
    @Test
    fun withoutTheSettingsNothingChanges() {
        val context = PatchContexts.of(listOf(settings(other, ImmutableFieldReference(other, "enhancementMode", "I"))))
        assertEquals(0, context.holdSyntheticHdrOff())
        assertEquals(5, context.mutableClassDefBy(other).methods.single { it.name == "<init>" }.instructions().size)
    }

    /** The method the hook calls is a public static method of the HdrBoost the bundle ships. */
    @Test
    fun theHookHasItsMethod() {
        val declared = ExtensionDex.classDef(HDR_BOOST).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$HDR_BOOST->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("HdrBoost declares no $ENHANCEMENT_MODE: $declared", ENHANCEMENT_MODE in declared)
    }

    /**
     * On each declared build, and every other build of the version, the settings class is there, its
     * one write of the mode is the constructor's, and the hook goes in right before it on its register.
     */
    @Test
    fun theDeclaredBuildHoldsTheMode() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                build(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) build(base, base.parentFile.name)
    }

    private fun build(bundle: File, label: String) {
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.firstOrNull { it.type == DAV1D_SETTING }?.let { kept += ImmutableClassDef.of(it) }
        }
        assertEquals("$label: the settings class", 1, kept.size)
        val writers = kept.single().methods.filter { method -> method.instructions().any { it.writesEnhancementMode() } }
        assertEquals("$label: the writers of the mode", listOf("<init>"), writers.map { it.name })

        val context = PatchContexts.of(kept)
        assertEquals("$label: writes sent", 1, context.holdSyntheticHdrOff())
        val shape = writers.single().parameterTypes.map { it.toString() }
        val code = context.mutableClassDefBy(DAV1D_SETTING).methods
            .single { it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } == shape }.instructions()
        val write = code.indexOfFirst { it.writesEnhancementMode() }
        val register = (code[write] as TwoRegisterInstruction).registerA
        assertEquals("$label: the hook", ENHANCEMENT_MODE, (code[write - 2] as ReferenceInstruction).reference.toString())
        assertEquals("$label: on the register written", listOf(register), code[write - 2].registers())
        assertEquals("$label: and back", register, (code[write - 1] as OneRegisterInstruction).registerA)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.registers(): List<Int> = (this as FiveRegisterInstruction).let {
        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
    }

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.landing(index: Int): Int {
        var address = 0
        val addresses = map { instruction -> address.also { address += instruction.codeUnits } }
        return addresses.indexOf(addresses[index] + (this[index] as OffsetInstruction).codeOffset)
    }
}
