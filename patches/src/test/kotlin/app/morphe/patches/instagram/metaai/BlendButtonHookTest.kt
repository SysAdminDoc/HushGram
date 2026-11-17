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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Meta AI's Blend switch, on each declared build and on every other build of the same
 * Instagram version: one static method decides the Blend invite button and asks the reel's share
 * eligibility for the Blend surface, and the check goes in ahead of its first instruction.
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
        val context = PatchContexts.of(shaped(bundle))
        val refusal = assertThrows(PatchException::class.java) { context.findBlendVisibilityCheck() }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("is missing"))
    }

    @Test
    fun twoChecksFailThePatch() {
        val bundle = Fixtures.otherBuilds().first()
        val holders = shaped(bundle)
        val check = holders.single { holder -> holder.methods.any { it.isBlendVisibilityCheck() } }
        val copy = ImmutableClassDef(
            "Lfixture/SecondBlendCheck;", check.accessFlags, check.superclass, check.interfaces, check.sourceFile,
            check.annotations, check.fields,
            check.methods.map { ImmutableMethod("Lfixture/SecondBlendCheck;", it.name, it.parameters, it.returnType, it.accessFlags, it.annotations, it.hiddenApiRestrictions, it.implementation) },
        )
        val answers = holders.flatMap { holder -> holder.methods.map { it.returnType } }.toSet()
        val context = PatchContexts.of(holders + copy + FixtureDex.classes(bundle, answers).values.filter { found -> holders.none { it.type == found.type } })
        val refusal = assertThrows(PatchException::class.java) { context.findBlendVisibilityCheck() }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("2 methods decide the Blend invite button"))
    }

    private fun builds(bundle: File, label: String) {
        val holders = shaped(bundle)
        assertTrue("$label: an instance method of the same parameters is in the slice, and isn't the check", holders.any { holder ->
            holder.methods.any { !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.size == 6 && it.parameterTypes[1].toString() == SESSION }
        })
        val answers = holders.flatMap { holder -> holder.methods.map { it.returnType } }.toSet()
        val patch = PatchContexts.of(holders + FixtureDex.classes(bundle, answers).values.filter { found -> holders.none { it.type == found.type } })
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

    /** The classes with a method taking (object, session, string, three flags), static or not. */
    private fun shaped(bundle: File): List<ClassDef> {
        val types = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == SESSION } }) { method ->
            method.parameterTypes.map(CharSequence::toString).let { parameters ->
                parameters.size == 6 && parameters[1] == SESSION && parameters[2] == "Ljava/lang/String;" && parameters.drop(3).all { it == "Z" }
            }
        }.map { it.definingClass }.toSet()
        return FixtureDex.classes(bundle, types).values.toList()
    }

    private fun code(holders: List<ClassDef>, found: BlendVisibilityCheck): List<Instruction> =
        holders.single { it.type == found.type }.methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.code()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
    }
}
