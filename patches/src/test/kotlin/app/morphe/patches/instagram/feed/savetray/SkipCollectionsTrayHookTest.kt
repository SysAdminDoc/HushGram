/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.savetray

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Skip the collections tray, on each declared build and on every other build of the same Instagram
 * version: one method decides whether a save opens the tray, and the skip ask goes in ahead of its
 * first instruction with a jump that still lands on that instruction.
 */
class SkipCollectionsTrayHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SKIP_TRAY.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SKIP_TRAY is not in the extension: $declared", SKIP_TRAY.substringAfter("->") in declared)
    }

    @Test
    fun eachDeclaredBuildHasOneTrayCheck() {
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
    fun eachOtherBuildHasOneTrayCheckToo() {
        var checked = 0
        for (base in Fixtures.otherBuilds()) {
            builds(base, base.parentFile.name)
            checked++
        }
        assertTrue("other builds beside the declared one: $checked", checked >= 1)
    }

    @Test
    fun aBuildWithoutTheCheckFailsThePatch() {
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(emptyList()).findTrayCheck()
        }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("expected exactly one $TRAY_CHECK"))
    }

    private fun builds(bundle: File, label: String) {
        // classesLoading carries the classes asking a string pool for the name, as 385914510's check does.
        val holders = FixtureDex.classesLoading(bundle, TRAY_COUNT).distinctBy { it.type }
        val patch = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))
        val found = try {
            patch.findTrayCheck()
        } catch (refusal: PatchException) {
            throw AssertionError("$label: ${refusal.message}", refusal)
        }
        assertTrue("$label: an instance method", !AccessFlags.STATIC.isSet(found.accessFlags))
        assertEquals("$label: (session)", listOf("Lcom/instagram/common/session/UserSession;"), found.parameterTypes.map { it.toString() })
        val before = found.implementation!!.instructions.toList().map { it.opcode }.filter { it != Opcode.NOP }
        assertTrue("$label: a local register for the answer", found.implementation!!.registerCount - found.parameterTypes.size - 1 >= 1)

        found.skipTray()

        val after = found.implementation!!.instructions.toList()
        assertEquals("$label: the ask comes first", SKIP_TRAY, after[0].referenceText())
        assertEquals("$label: answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$label: test", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$label: skipped, so no", Opcode.CONST_4, after[3].opcode)
        assertEquals("$label: no is zero", 0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: and leave", Opcode.RETURN, after[4].opcode)
        // invoke-static (3 units) + move-result (1) puts if-eqz at offset 4; the original first instruction
        // follows the 2-unit if-eqz, the 1-unit const/4 and the 1-unit return, at offset 8, four units on.
        assertEquals("$label: the jump lands on the original first instruction", 4, (after[2] as OffsetInstruction).codeOffset)
        assertEquals("$label: one ask", 1, after.count { it.referenceText() == SKIP_TRAY })
        assertEquals("$label: the rest is as it was", before, after.drop(5).map { it.opcode }.filter { it != Opcode.NOP })
    }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
