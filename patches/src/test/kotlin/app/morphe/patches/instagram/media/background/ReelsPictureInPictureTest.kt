/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picture in picture for reels (#572): the hook is in the extension, and in every build of 454 both
 * of Instagram's reels picture in picture checks ask it first, answer yes on a yes, and otherwise run
 * as they came.
 */
class ReelsPictureInPictureTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REELS_PIP.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$REELS_PIP is not in the extension: $declared", REELS_PIP.substringAfter("->") in declared)
    }

    @Test
    fun eachBuildAsksTheSwitchFirstInBothChecks() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val classes = (FixtureDex.classesLoading(bundle, PIP_ALLOWED) + FixtureDex.classesLoading(bundle, PIP_SWITCHED_ON))
                .distinctBy { it.type }
            val context = PatchContexts.of(classes)
            val checks = context.findReelsPipChecks("test")
            val stock = listOf(checks.allowed, checks.switchedOn).map { it.code() }

            context.allowReelsPip("test", checks)

            for ((at, check) in listOf(checks.allowed, checks.switchedOn).withIndex()) {
                val method = context.mutableClassDefBy(check.definingClass).methods.single {
                    it.name == check.name && it.parameterTypes.map(Any::toString) == check.parameterTypes.map(Any::toString)
                }
                val code = method.code()
                val where = "${bundle.name}: ${method.definingClass}->${method.name}"
                assertEquals("$where asks the switch first", REELS_PIP, (code[0] as ReferenceInstruction).reference.toString())
                assertEquals("$where reads the answer", Opcode.MOVE_RESULT, code[1].opcode)
                assertEquals("$where branches on it", Opcode.IF_EQZ, code[2].opcode)
                assertEquals("$where answers yes", 1, (code[3] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$where returns that", Opcode.RETURN, code[4].opcode)
                assertEquals("$where returns the register it set", (code[3] as OneRegisterInstruction).registerA,
                    (code[4] as OneRegisterInstruction).registerA)
                assertEquals("$where keeps the stock body after the hook",
                    stock[at].map { it.kind() }, code.drop(5).map { it.kind() })
            }
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    /** An insert can push a branch past a plain goto's reach, and dexlib2 widens it. */
    private fun Instruction.kind(): Opcode =
        if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    private fun Method.code(): List<Instruction> =
        implementation?.instructions?.toList().orEmpty().filter { it.opcode != Opcode.NOP }
}
