/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.back.enumConstantName
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Meta AI restyle credits on reels, on each declared build and on every other build of the
 * same Instagram version: the byline's Edits credit gate gets the ask first, handed the credit's
 * name, and the pill's restyle check gets its own ask first.
 */
class RestyleCreditHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(RESTYLE_BYLINE.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(RESTYLE_BYLINE, RESTYLE_PILL)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun eachDeclaredBuildHasOneGateAndOneCheck() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val declared = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.map { version to it }
        }
        for ((version, bundle) in declared) {
            builds(bundle, bundle.name)
            checked += version
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHasThemToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    @Test
    fun aBuildWithoutThemFailsThePatch() {
        val gate = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findRestyleBylineGate() }
        assertTrue(gate.message.orEmpty(), gate.message.orEmpty().contains("0 gates are asked with $RESTYLE_GATE"))
        val pill = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findRestylePillCheck() }
        assertTrue(pill.message.orEmpty(), pill.message.orEmpty().contains("no enum has a constant built as $RESTYLE_TOOL"))
    }

    /**
     * The gate's class holds a second gate of the same shape that another screen asks, so the
     * gates alone, without the byline asking one with the restyle credit's name, aren't enough.
     */
    @Test
    fun theGateIsTheOneTheBylineAsks() {
        val bundle = Fixtures.otherBuilds().first()
        val gates = FixtureDex.classesLoading(bundle, EDITS_GATE_PREFIX)
        val shaped = gates.flatMap { it.methods }.filter { it.isRestyleBylineGate() }
        assertTrue("two gates of the shape: ${shaped.map { it.name }}", shaped.size >= 2)
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(gates).findRestyleBylineGate() }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("0 gates are asked"))
    }

    @Test
    fun anInstanceCheckIsntThePill() {
        val bundle = Fixtures.otherBuilds().first()
        val (restyle, holders) = pillSlice(bundle)
        val found = holders.flatMap { it.methods }.single { it.isRestylePillCheck(restyle) }
        val instance = ImmutableMethod(
            found.definingClass, found.name, found.parameters, found.returnType, found.accessFlags and AccessFlags.STATIC.value.inv(),
            found.annotations, found.hiddenApiRestrictions, found.implementation,
        )
        assertTrue("an instance method of the shape isn't the check", !instance.isRestylePillCheck(restyle))
    }

    private fun builds(bundle: File, label: String) {
        gateBuilds(bundle, label)
        pillBuilds(bundle, label)
    }

    private fun gateBuilds(bundle: File, label: String) {
        val holders = (FixtureDex.classesLoading(bundle, EDITS_GATE_PREFIX) + FixtureDex.classesLoading(bundle, RESTYLE_GATE))
            .distinctBy { it.type }
        val patch = PatchContexts.of(holders)
        val found = patch.findRestyleBylineGate()
        assertEquals("$label: (session, name)", RESTYLE_GATE_PARAMETERS, found.parameters)
        val stock = holders.single { it.type == found.type }.methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        val name = stock.parameterRegisterNumber(RESTYLE_GATE_PARAMETERS.indexOf("Ljava/lang/String;"))
        val before = stock.code()

        patch.holdRestyleByline(found)

        val after = patch.mutable(found).implementation!!.instructions.toList()
        assertEquals("$label: the ask comes first", RESTYLE_BYLINE, after[0].referenceText())
        assertEquals("$label: as a range call", Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
        assertEquals("$label: handed the credit's name", name, (after[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, (after[0] as RegisterRangeInstruction).registerCount)
        assertEquals("$label: answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$label: test", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$label: a no goes on to Instagram's own gate", 5, (after[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: true", Opcode.CONST_4, after[3].opcode)
        assertEquals("$label: leave it out", 1, (after[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: answered", Opcode.RETURN, after[4].opcode)
        assertEquals("$label: one ask", 1, after.count { it.referenceText() == RESTYLE_BYLINE })
        assertEquals("$label: the rest is as it was", before.kinds(), after.drop(5).kinds())
    }

    private fun pillBuilds(bundle: File, label: String) {
        val (restyle, holders) = pillSlice(bundle)
        val patch = PatchContexts.of(holders)
        val found = patch.findRestylePillCheck()
        assertEquals("$label: the session second", RESTYLE_GATE_PARAMETERS[0], found.parameters[1])
        val stock = holders.single { it.type == found.type }.methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        assertTrue("$label: static", AccessFlags.STATIC.isSet(stock.accessFlags))
        val tools = stock.code().mapNotNull { (it.reference() as? FieldReference)?.takeIf { field -> field.definingClass in restyle } }
        assertEquals("$label: it reads the restyle tool once: $tools", 1, tools.size)
        val before = stock.code()

        patch.holdRestylePill(found)

        val after = patch.mutable(found).implementation!!.instructions.toList()
        assertEquals("$label: the ask comes first", RESTYLE_PILL, after[0].referenceText())
        assertEquals("$label: as a plain call", Opcode.INVOKE_STATIC, after[0].opcode)
        assertEquals("$label: handed nothing", 0, (after[0] as FiveRegisterInstruction).registerCount)
        assertEquals("$label: answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$label: test", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$label: a no goes on to Instagram's own check", 5, (after[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: false", Opcode.CONST_4, after[3].opcode)
        assertEquals("$label: no pill", 0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: answered", Opcode.RETURN, after[4].opcode)
        assertEquals("$label: one ask", 1, after.count { it.referenceText() == RESTYLE_PILL })
        assertEquals("$label: the rest is as it was", before.kinds(), after.drop(5).kinds())
    }

    /**
     * The enums naming the restyle tool (to the field built as it, where one is), with every class
     * holding a static (something, session) method that reads one of them, and the string pools.
     */
    private fun pillSlice(bundle: File): Pair<Map<String, String>, List<ClassDef>> {
        val enums = FixtureDex.classesLoading(bundle, RESTYLE_TOOL)
        val types = enums.filter { AccessFlags.ENUM.isSet(it.accessFlags) }.mapTo(HashSet()) { it.type }
        assertTrue("${bundle.name}: an enum names $RESTYLE_TOOL", types.isNotEmpty())
        val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in types } }) { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.size == 2 &&
                method.code().any { (it.reference() as? FieldReference)?.definingClass in types }
        }.map { it.definingClass }.toSet()
        val holders = (enums + FixtureDex.classes(bundle, readers - enums.map { it.type }.toSet()).values).distinctBy { it.type }
        val restyle = enums.filter { it.type in types }.mapNotNull { enum ->
            enum.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == enum.type }
                .singleOrNull { enumConstantName(enum, it) == RESTYLE_TOOL }
                ?.let { enum.type to it.name }
        }.toMap()
        return restyle to holders
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.mutable(check: RestyleCreditCheck) =
        mutableClassDefBy(check.type).methods.single {
            it.name == check.name && it.parameterTypes.map(CharSequence::toString) == check.parameters
        }

    /** The opcodes, payload alignment NOPs left out and every goto read as one, since an insert can widen a goto. */
    private fun List<Instruction>.kinds(): List<Opcode> = map {
        when (it.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> it.opcode
        }
    }.filter { it != Opcode.NOP }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.reference(): Any? = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()
}
