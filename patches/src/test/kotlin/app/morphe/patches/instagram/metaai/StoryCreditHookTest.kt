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
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Meta AI restyle credits on stories, on each declared build and on every other build of the same
 * Instagram version: one instance method decides whether a story's header shows a line of a given
 * kind, and the ask goes in ahead of its first instruction, handed that kind.
 */
class StoryCreditHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_CREDIT.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$STORY_CREDIT is not in the extension: $declared", STORY_CREDIT.substringAfter("->") in declared)
    }

    @Test
    fun eachDeclaredBuildHasOneCheck() {
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
    fun eachOtherBuildHasOneCheckToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    @Test
    fun aBuildWithoutTheCheckFailsThePatch() {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findStoryCreditCheck() }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("0 methods decide a story header's lines"))
    }

    @Test
    fun twoChecksFailThePatch() {
        val bundle = Fixtures.otherBuilds().first()
        val holders = shaped(bundle)
        val check = holders.single { holder -> holder.methods.any { it.isStoryCreditCheck() } }
        val copy = ImmutableClassDef(
            "Lfixture/SecondStoryCreditCheck;", check.accessFlags, check.superclass, check.interfaces, check.sourceFile,
            check.annotations, check.fields,
            check.methods.map { ImmutableMethod("Lfixture/SecondStoryCreditCheck;", it.name, it.parameters, it.returnType, it.accessFlags, it.annotations, it.hiddenApiRestrictions, it.implementation) },
        )
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(holders + copy).findStoryCreditCheck() }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("2 methods decide a story header's lines"))
    }

    @Test
    fun aStaticCheckIsntTheOne() {
        val bundle = Fixtures.otherBuilds().first()
        val holders = shaped(bundle)
        val check = holders.single { holder -> holder.methods.any { it.isStoryCreditCheck() } }
        val found = check.methods.single { it.isStoryCreditCheck() }
        val static = ImmutableMethod(
            found.definingClass, found.name, found.parameters, found.returnType, found.accessFlags or AccessFlags.STATIC.value,
            found.annotations, found.hiddenApiRestrictions, found.implementation,
        )
        assertTrue("a static method of the shape isn't the check", !static.isStoryCreditCheck())
    }

    private fun builds(bundle: File, label: String) {
        val holders = shaped(bundle)
        val patch = PatchContexts.of(holders)
        val found = patch.findStoryCreditCheck()
        assertEquals("$label: (Context, session, kind, story, ...)", STORY_CREDIT_PARAMETERS, found.parameters.take(STORY_CREDIT_PARAMETERS.size))
        val stock = holders.single { it.type == found.type }.methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        val kind = stock.parameterRegisterNumber(STORY_CREDIT_PARAMETERS.indexOf(ATTRIBUTION_TYPE))
        val before = stock.code()

        patch.holdStoryCredit(found)

        val method = patch.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        val after = method.implementation!!.instructions.toList()
        assertEquals("$label: the ask comes first", STORY_CREDIT, after[0].referenceText())
        assertEquals("$label: as a range call", Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
        assertEquals("$label: handed the line's kind", kind, (after[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, (after[0] as RegisterRangeInstruction).registerCount)
        assertEquals("$label: answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$label: test", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$label: a no goes on to Instagram's own check", 5, (after[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: false", Opcode.CONST_4, after[3].opcode)
        assertEquals("$label: answered", Opcode.RETURN, after[4].opcode)
        assertEquals("$label: one ask", 1, after.count { it.referenceText() == STORY_CREDIT })
        assertEquals("$label: the rest is as it was", before.kinds(), after.drop(5).kinds())
    }

    /** The opcodes, payload alignment NOPs left out and every goto read as one, since an insert can widen a goto. */
    private fun List<Instruction>.kinds(): List<Opcode> = map {
        when (it.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> it.opcode
        }
    }.filter { it != Opcode.NOP }

    /** The classes with a method taking (Context, session, the line's kind, story, ...), static or not. */
    private fun shaped(bundle: File): List<ClassDef> {
        val types = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == ATTRIBUTION_TYPE } }) { method ->
            method.parameterTypes.map(CharSequence::toString).take(STORY_CREDIT_PARAMETERS.size) == STORY_CREDIT_PARAMETERS
        }.map { it.definingClass }.toSet()
        return FixtureDex.classes(bundle, types).values.toList()
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
