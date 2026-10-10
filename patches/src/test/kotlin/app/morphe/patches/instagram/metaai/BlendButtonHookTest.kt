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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Meta AI's Blend switch, on each declared build and on every other build of the same
 * Instagram version: one static method decides the Blend invite button and names itself to
 * Instagram's logging, and the check goes in ahead of its first instruction.
 */
class BlendButtonHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(BLEND_BUTTON.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$BLEND_BUTTON is not in the extension: $declared", BLEND_BUTTON.substringAfter("->") in declared)
    }

    @Test
    fun eachDeclaredBuildHasOneBlendCheck() {
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
    fun eachOtherBuildHasOneBlendCheckToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    @Test
    fun aBuildWithoutTheCheckFailsThePatch() {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findBlendVisibilityCheck() }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("0 methods decide the Blend invite button"))
    }

    @Test
    fun aBuildWithoutTheAnswerClassFailsThePatch() {
        val bundle = Fixtures.otherBuilds().first()
        val holders = FixtureDex.classesHolding(bundle, BLEND_VISIBILITY).distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))
        val refusal = assertThrows(PatchException::class.java) { context.findBlendVisibilityCheck() }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("is missing"))
    }

    private fun builds(bundle: File, label: String) {
        val holders = FixtureDex.classesHolding(bundle, BLEND_VISIBILITY).distinctBy { it.type }
        val seed = FixtureDex.withStringPools(bundle, holders)
        val answers = holders.flatMap { holder -> holder.methods.map { it.returnType } }.toSet()
        val patch = PatchContexts.of(seed + FixtureDex.classes(bundle, answers).values.filter { found -> seed.none { it.type == found.type } })
        val found = patch.findBlendVisibilityCheck()
        assertEquals("$label: static (reel, session, owner, three flags)", 6, found.parameters.size)
        val before = code(holders, found)

        patch.holdBlendButton(found)

        val method = patch.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        val after = method.implementation!!.instructions.toList()
        assertEquals("$label: the ask comes first", BLEND_BUTTON, after[0].referenceText())
        assertEquals("$label: answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$label: test", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$label: a new answer", Opcode.NEW_INSTANCE, after[3].opcode)
        assertEquals("$label: of the method's own answer type", found.returnType, after[3].referenceText())
        assertEquals("$label: with both flags false", Opcode.CONST_4, after[4].opcode)
        assertEquals("$label: built", Opcode.INVOKE_DIRECT, after[5].opcode)
        assertEquals("$label: with the two boolean constructor", "${found.returnType}-><init>(ZZ)V", after[5].referenceText())
        assertEquals("$label: returned", Opcode.RETURN_OBJECT, after[6].opcode)
        assertEquals("$label: one ask", 1, after.count { it.referenceText() == BLEND_BUTTON })
        assertEquals("$label: the rest is as it was", before.map { it.opcode }, after.drop(7).map { it.opcode })
    }

    private fun code(holders: List<ClassDef>, found: BlendVisibilityCheck): List<Instruction> =
        holders.single { it.type == found.type }.methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.code()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
