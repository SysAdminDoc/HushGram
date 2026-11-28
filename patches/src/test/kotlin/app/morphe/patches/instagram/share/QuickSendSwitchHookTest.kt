/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The switch that turns on the row a long press on Share opens (#889): every read of Instagram's test answered through the hook. */
class QuickSendSwitchHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(QUICK_SEND_ON.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$QUICK_SEND_ON is not in the extension: $declared", QUICK_SEND_ON.substringAfter("->") in declared)
    }

    /**
     * On every build the test's id is loaded six times, each load feeding one boolean read, one of
     * them in the long press on a post's Share button. Each read's answer goes through the hook right
     * after its move-result and comes back into the same register, and nothing else reads the id.
     */
    @Test
    fun eachBuildAnswersEveryRead() {
        for (build in builds()) {
            val label = "${build.parentFile.name}/${build.name}"
            val holders = holders(build)
            val loads = holders.sumOf { classDef -> classDef.methods.sumOf { method -> method.code().count { it.isTest() } } }
            assertEquals("$label: loads of the test", 6, loads)
            val context = PatchContexts.of(holders)
            val reads = context.findQuickSendTest()
            assertEquals("$label: one read for each load", loads, reads.size)
            assertEquals("$label: reads in the long press", 1, reads.count { it.parameters == QUICK_SEND_PRESS && it.returnType == "V" })
            context.answerFlagReads(reads, QUICK_SEND_ON)

            var answered = 0
            for (holder in holders) {
                for (method in context.mutableClassDefBy(holder.type).methods) {
                    val code = method.code()
                    if (code.none { it.isTest() }) continue
                    code.forEachIndexed { at, instruction ->
                        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                        if (call.toString() != QUICK_SEND_ON) return@forEachIndexed
                        val result = code[at - 1] as OneRegisterInstruction
                        assertEquals("$label ${holder.type}->${method.name}: the read's answer goes in", Opcode.MOVE_RESULT, code[at - 1].opcode)
                        assertEquals("$label ${holder.type}->${method.name}: the hook takes it", result.registerA,
                            (instruction as RegisterRangeInstruction).startRegister)
                        assertEquals("$label ${holder.type}->${method.name}: one register", 1, instruction.registerCount)
                        val read = (code[at - 2] as? ReferenceInstruction)?.reference as? MethodReference
                        assertTrue("$label ${holder.type}->${method.name}: right after a boolean read",
                            read != null && read.returnType == "Z" && read.parameterTypes.last().toString() == "J")
                        val back = code[at + 1]
                        assertEquals("$label ${holder.type}->${method.name}: the hook's answer comes back", Opcode.MOVE_RESULT, back.opcode)
                        assertEquals("$label ${holder.type}->${method.name}: into the same register", result.registerA,
                            (back as OneRegisterInstruction).registerA)
                        answered++
                    }
                }
            }
            assertEquals("$label: reads answered", 6, answered)
        }
    }

    @Test
    fun aBuildWithoutTheLongPressLeavesTheSwitchOut() {
        val holders = holders(builds().first())
        val press = holders.single { classDef ->
            classDef.methods.any { method ->
                method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == QUICK_SEND_PRESS &&
                    method.code().any { it.isTest() }
            }
        }
        val context = PatchContexts.of(holders - press)
        val thrown = assertThrows(PatchException::class.java) { context.findQuickSendTest() }
        assertTrue(thrown.message.orEmpty(), "long press" in thrown.message.orEmpty())
        assertEquals(null, context.findQuickSendTestOrWarn())
    }

    @Test
    fun noReadLeavesTheSwitchOut() {
        assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findQuickSendTest() }
        assertEquals(null, PatchContexts.of(emptyList()).findQuickSendTestOrWarn())
    }

    private fun builds(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val builds = Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } } + Fixtures.otherBuilds()
        assertTrue("no fixture builds", builds.isNotEmpty())
        return builds
    }

    private fun holders(build: File): List<ClassDef> {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(build) { dex ->
            for (classDef in dex.classes) if (classDef.methods.any { method -> method.code().any { it.isTest() } }) holders += ImmutableClassDef.of(classDef)
        }
        return holders
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.isTest() = opcode == Opcode.CONST_WIDE && (this as WideLiteralInstruction).wideLiteral == QUICK_SEND_FLAG
}
