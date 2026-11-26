/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Buttons for the reels picture in picture window (#873): in every fixture build, ClipsViewerPipUtil's
 * settings builder asks the extension at its top with its activity and its own answer for the
 * buttons, uses the extension's answer instead, and hands the extension its list right before it's
 * set. The rest of the builder is as it came.
 */
class ReelsPipButtonsTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(REELS_PIP_BUTTONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$REELS_PIP_BUTTONS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(PIP_WITH_ACTIONS, PIP_ACTIONS)) {
            assertTrue("ReelsPipButtons declares no $hook: $declared", hook in declared)
        }
    }

    @Test
    fun eachBuildHandsTheExtensionTheWindowsButtons() {
        var checked = 0
        for (bundle in bundles() + Fixtures.otherBuilds()) {
            val classes = FixtureDex.classesLoading(bundle, PIP_UTIL_TAG)
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes) + ExtensionDex.classDef(REELS_PIP_BUTTONS))
            val sites = context.findReelsPipButtons("test")
            val stock = sites.builder.implementation!!.instructions.toList()
            val stockSet = stock[sites.setActions] as FiveRegisterInstruction
            val list = stockSet.registerD
            val activity = sites.builder.parameterRegisterNumber(0)
            val wanted = sites.builder.parameterRegisterNumber(sites.builder.parameterTypes.lastIndex)

            context.addReelsPipButtons("test", sites)

            val label = bundle.name
            val code = context.mutable(sites.builder).implementation!!.instructions.toList()
            assertEquals("$label: the activity goes to a local", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
            assertEquals("$label: v0", listOf(0, activity), code[0].registers())
            assertEquals("$label: the builder's own answer goes to a local", Opcode.MOVE_FROM16, code[1].opcode)
            assertEquals("$label: v1", listOf(1, wanted), code[1].registers())
            assertEquals("$label: the extension is asked", PIP_WITH_ACTIONS, code[2].reference())
            assertEquals("$label: with both", listOf(0, 1), (code[2] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
            assertEquals("$label: and only them", 2, (code[2] as FiveRegisterInstruction).registerCount)
            assertEquals("$label: its answer replaces the builder's", Opcode.MOVE_RESULT, code[3].opcode)
            assertEquals("$label: in the parameter's own register", wanted, (code[3] as OneRegisterInstruction).registerA)

            val hook = sites.setActions + 4
            assertEquals("$label: the list goes to the extension", PIP_ACTIONS, code[hook].reference())
            assertEquals("$label: as a range of one", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
            assertEquals("$label: the list's register", list, (code[hook] as RegisterRangeInstruction).startRegister)
            assertEquals("$label: alone", 1, (code[hook] as RegisterRangeInstruction).registerCount)
            assertEquals("$label: its answer is the list", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
            assertEquals("$label: in the same register", list, (code[hook + 1] as OneRegisterInstruction).registerA)
            assertEquals("$label: and setActions follows", stockSet.reference(), code[hook + 2].reference())
            assertEquals("$label: with that register", list, (code[hook + 2] as FiveRegisterInstruction).registerD)

            val rest = code.subList(4, hook) + code.drop(hook + 2)
            assertEquals("$label: the rest is stock", stock.kinds(), rest.kinds())
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
            .also { assertTrue("no declared build has a fixture", it.isNotEmpty()) }
    }

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.registers(): List<Int> = (this as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) }

    /** The opcodes without the alignment nops, with a widened goto read as a goto. */
    private fun List<Instruction>.kinds(): List<Opcode> = filter { it.opcode != Opcode.NOP }.map {
        if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode
    }
}
