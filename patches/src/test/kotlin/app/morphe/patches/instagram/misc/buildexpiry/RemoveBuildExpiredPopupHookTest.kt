/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.buildexpiry

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Remove build expired popup, on each declared build and on every other build of the same Instagram
 * version: one method shows the lockout over the main activity, and the suppress ask goes in ahead
 * of its first instruction with a jump that still lands on that instruction.
 */
class RemoveBuildExpiredPopupHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SUPPRESS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SUPPRESS is not in the extension: $declared", SUPPRESS.substringAfter("->") in declared)
    }

    @Test
    fun eachDeclaredBuildHasOneLockout() {
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
    fun eachOtherBuildHasOneLockoutToo() {
        var checked = 0
        for (base in Fixtures.otherBuilds()) {
            builds(base, base.parentFile.name)
            checked++
        }
        assertTrue("other builds beside the declared one: $checked", checked >= 1)
    }

    @Test
    fun aBuildWithoutTheLockoutFailsThePatch() {
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(emptyList()).uniqueMethod(PATCH_NAME, LOCKOUT, BuildExpiredLockoutFingerprint)
        }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("expected exactly one $LOCKOUT"))
    }

    private fun builds(bundle: File, label: String) {
        val holders = FixtureDex.classesHolding(bundle, "lockout_active").distinctBy { it.type }
        val patch = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))
        val found = patch.uniqueMethod(PATCH_NAME, LOCKOUT, BuildExpiredLockoutFingerprint)
        assertEquals("$label: (activity, lockout config)", 2, found.parameterTypes.size)
        assertEquals("$label: the activity first", "Landroidx/fragment/app/FragmentActivity;", found.parameterTypes[0].toString())
        val before = found.implementation!!.instructions.toList().map { it.opcode }
        assertTrue("$label: a local register for the answer", found.implementation!!.registerCount - found.parameterTypes.size - 1 >= 1)

        found.suppressLockout()

        val after = found.implementation!!.instructions.toList()
        assertEquals("$label: the ask comes first", SUPPRESS, after[0].referenceText())
        assertEquals("$label: answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$label: test", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$label: suppressed, so leave", Opcode.RETURN_VOID, after[3].opcode)
        // invoke-static (3 units) + move-result (1) puts if-eqz at offset 4; the original first instruction
        // follows the 2-unit if-eqz and the 1-unit return-void, at offset 7, three units on from the jump.
        assertEquals("$label: the jump lands on the original first instruction", 3, (after[2] as OffsetInstruction).codeOffset)
        assertEquals("$label: one ask", 1, after.count { it.referenceText() == SUPPRESS })
        assertEquals("$label: the rest is as it was", before, after.drop(4).map { it.opcode })
    }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
