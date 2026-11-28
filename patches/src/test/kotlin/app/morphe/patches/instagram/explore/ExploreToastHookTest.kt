/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toast Explore shows for the grid that isn't personalized (#311). Instagram counts it in a user
 * preference and shows it while the count is under three, so the hook hands the count it read to the
 * extension, and the check after it sees the extension's answer.
 */
class ExploreToastHookTest {
    /** In each declared build both places that read the count are found, and each read goes past the extension. */
    @Test
    fun eachDeclaredBuildHandsTheToastCountOver() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                handsTheCountOver(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other builds of each declared version, which Redex compiled on their own. */
    @Test
    fun eachOtherBuildHandsTheToastCountOver() {
        val builds = Fixtures.otherBuilds()
        for (apk in builds) handsTheCountOver(apk, apk.parentFile.name)
        assertEquals("other builds of ${Fixtures.VERSION}", Fixtures.OTHER_BUILDS, builds.size)
    }

    @Test
    fun noPreferenceKeyFailsThePatch() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findExploreToastCounts() }
        assertTrue(failure.message!!, failure.message!!.contains("expected one preference key for $TOAST_COUNT, found 0"))
    }

    /** With the key but none of the places that compare the count, nothing is written. */
    @Test
    fun noCountCheckFailsThePatch() {
        val bundle = Fixtures.files { it.extension == "apks" && it.name.contains("-${Fixtures.VERSION}-") }.first()
        val holders = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, TOAST_COUNT))
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(holders).findExploreToastCounts() }
        assertTrue(failure.message!!, failure.message!!.contains("nothing compares the count in"))
    }

    private fun handsTheCountOver(bundle: File, label: String) {
        val context = PatchContexts.of(slice(bundle))
        val sites = context.findExploreToastCounts()
        assertEquals("$label: the places that read the toast's count", 2, sites.size)
        val stock = sites.associateWith { site ->
            context.classDefBy(site.holder).methods.single { it.name == site.method && it.parameterTypes.map(CharSequence::toString) == site.parameters }
                .implementation!!.instructions.toList()
        }

        context.skipExploreToasts(sites)

        for (site in sites) {
            val what = "$label ${site.holder}->${site.method}"
            val before = stock.getValue(site)
            val code = context.mutableClassDefBy(site.holder).methods.single {
                it.name == site.method && it.parameterTypes.map(CharSequence::toString) == site.parameters
            }.implementation!!.instructions.toList()
            assertEquals("$what: two instructions more", before.size + 2, code.size)
            assertEquals("$what: the count is taken", Opcode.MOVE_RESULT, code[site.readAt].opcode)
            assertEquals("$what: into its register", site.register, (code[site.readAt] as OneRegisterInstruction).registerA)
            val hand = code[site.readAt + 1]
            assertEquals("$what: then handed over", TOASTS_SHOWN, (hand as ReferenceInstruction).reference.toString())
            assertEquals("$what: in the range form", Opcode.INVOKE_STATIC_RANGE, hand.opcode)
            assertEquals("$what: the count's register", site.register, (hand as RegisterRangeInstruction).startRegister)
            assertEquals("$what: alone", 1, hand.registerCount)
            assertEquals("$what: the answer comes back", Opcode.MOVE_RESULT, code[site.readAt + 2].opcode)
            assertEquals("$what: into the same register", site.register, (code[site.readAt + 2] as OneRegisterInstruction).registerA)
            assertEquals("$what: Instagram's own code goes on", before[site.readAt + 1].opcode, code[site.readAt + 3].opcode)
            assertEquals("$what: one hook", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == TOASTS_SHOWN })
            assertComparesWithThree(what, code, site.readAt + 3, site.register)
        }
    }

    /** Within the next few instructions the count is compared with three, the times Instagram shows the toast. */
    private fun assertComparesWithThree(what: String, code: List<Instruction>, from: Int, count: Int) {
        val window = code.subList(from, minOf(from + 3, code.size))
        val check = window.firstOrNull { it.opcode == Opcode.IF_GE } as? TwoRegisterInstruction
            ?: throw AssertionError("$what: no if-ge after the count: ${window.map { it.opcode }}")
        assertEquals("$what: the check reads the count", count, check.registerA)
        val limit = window.firstOrNull { it.opcode == Opcode.CONST_4 && (it as OneRegisterInstruction).registerA == check.registerB }
            ?: throw AssertionError("$what: the limit isn't loaded right before the check")
        assertEquals("$what: Instagram shows the toast three times", 3, (limit as NarrowLiteralInstruction).narrowLiteral)
    }

    /** The class that names the preference, and every class reading the key field it writes, with their string pools. */
    private fun slice(bundle: File): List<ClassDef> {
        val holders = FixtureDex.classesLoading(bundle, TOAST_COUNT)
        val keys = holders.flatMap { it.methods }.filter { it.name == "<clinit>" }
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .filter { it.opcode == Opcode.SPUT_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }
            .map { "${it.definingClass}->${it.name}" }
            .toSet()
        val readers = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.implementation?.instructions?.any { instruction ->
                instruction.opcode == Opcode.SGET_OBJECT &&
                    ((instruction as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}" in keys }
            } == true
        }.map { it.definingClass }.toSet() - holders.map { it.type }.toSet()
        return FixtureDex.withStringPools(bundle, holders + FixtureDex.classes(bundle, readers).values).distinctBy { it.type }
    }
}
