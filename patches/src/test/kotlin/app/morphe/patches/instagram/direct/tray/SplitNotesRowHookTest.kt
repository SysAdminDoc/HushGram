/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.tray

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplitNotesRowHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SPLIT_NOTES_ROW.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SPLIT_NOTES_ROW is not in the extension: $declared", SPLIT_NOTES_ROW.substringAfter("->") in declared)
    }

    /**
     * On every build, the flag is read four times, once in the onCreate that opens your messages,
     * the Stories stack's flag twice, and each read's answer goes through its hook right after its
     * move-result.
     */
    @Test
    fun eachBuildAnswersEveryRead() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val builds: List<File> =
            Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } } + Fixtures.otherBuilds()
        assertTrue("no fixture builds", builds.isNotEmpty())
        for (build in builds) {
            val label = "${build.parentFile.name}/${build.name}"
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(build) { dex ->
                for (classDef in dex.classes) if (classDef.methods.any { it.loadsAFlag() }) holders += ImmutableClassDef.of(classDef)
            }
            val context = PatchContexts.of(holders)
            val reads = context.findSplitNotesRow()
            assertEquals("$label: reads of the flag", 4, reads.size)
            val storiesReads = context.findSplitNotesStories()
            assertEquals("$label: reads of the Stories stack's flag", 2, storiesReads.size)
            context.splitNotesRow()
            assertEquals("$label: reads answered", 4, context.answered(label, reads, SPLIT_NOTES_ROW_FLAG, SPLIT_NOTES_ROW))
            assertEquals("$label: Stories reads answered", 2, context.answered(label, storiesReads, SPLIT_NOTES_STORIES_OFF_FLAG, SPLIT_NOTES_STORIES))
        }
    }

    @Test
    fun noReadFailsThePatch() {
        assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findSplitNotesRow() }
    }

    /** How many loads of [flag] in [reads]' methods have their answer go through [expected] right after the move-result. */
    private fun BytecodePatchContext.answered(
        label: String,
        reads: List<FlagLoad>,
        flag: Long,
        expected: String,
    ): Int {
        var answered = 0
        for (read in reads.distinctBy { "${it.type}->${it.name}" }) {
            val method = mutableClassDefBy(read.type).methods.single {
                it.name == read.name && it.parameterTypes.map(CharSequence::toString) == read.parameters
            }
            val code = method.implementation!!.instructions.toList()
            code.withIndex().filter { it.value.isFlag(flag) }.forEach { (load, _) ->
                val result = (load until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
                val register = (code[result] as OneRegisterInstruction).registerA
                val hook = code[result + 1]
                assertEquals("$label/${read.type}->${read.name}: the hook after the read", expected, (hook as? ReferenceInstruction)?.reference?.toString())
                assertEquals("$label: the hook reads the answer", register, (hook as RegisterRangeInstruction).startRegister)
                val back = code[result + 2]
                assertEquals("$label: the hook's answer goes back", Opcode.MOVE_RESULT, back.opcode)
                assertEquals("$label: into the same register", register, (back as OneRegisterInstruction).registerA)
                answered++
            }
        }
        return answered
    }

    private fun Method.loadsAFlag() =
        implementation?.instructions?.any { it.isFlag(SPLIT_NOTES_ROW_FLAG) || it.isFlag(SPLIT_NOTES_STORIES_OFF_FLAG) } == true

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isFlag(flag: Long) =
        opcode == Opcode.CONST_WIDE && (this as WideLiteralInstruction).wideLiteral == flag
}
