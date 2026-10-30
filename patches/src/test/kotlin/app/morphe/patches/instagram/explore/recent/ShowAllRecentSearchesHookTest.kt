/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore.recent

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
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
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Show all recent searches hands the count of recent searches to the extension and changes nothing else. */
class ShowAllRecentSearchesHookTest {
    private val hook = SHOWN_HOOK
    private val min = "Ljava/lang/Math;->min(II)I"

    @Test
    fun theHookIsInTheExtension() {
        val shown = ExtensionDex.classDef(RECENT_SEARCHES).methods.single { it.name == "shown" && it.parameterTypes.size == 1 }
        assertEquals("I", shown.returnType)
        assertEquals(listOf("I"), shown.parameterTypes.map(Any::toString))
        assertTrue(AccessFlags.PUBLIC.isSet(shown.accessFlags) && AccessFlags.STATIC.isSet(shown.accessFlags))
    }

    /** The hook goes right after the last count before the group is named, and nothing else moves. */
    @Test
    fun theCountGoesThroughTheHookRightAfterItIsWorkedOut() {
        val context = PatchContexts.of(listOf(builder("Lfixture/Newer;"), builder("Lfixture/Older;")))
        val sites = context.findRecentLimits()
        assertEquals(2, sites.size)
        val before = context.mutableClassDefBy("Lfixture/Newer;").methods.single { it.name == "build" }.code().map { it.shape() }

        context.showAllRecents(sites)

        val after = context.mutableClassDefBy("Lfixture/Newer;").methods.single { it.name == "build" }.code()
        assertEquals(before.size + 2, after.size)
        val at = after.indexOfFirst { it.reference() == hook }
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[at].opcode)
        assertEquals(Opcode.MOVE_RESULT, after[at + 1].opcode)
        assertEquals(min, after[at - 2].reference())
        assertEquals("everything else stays as it was", before, after.filterIndexed { i, _ -> i != at && i != at + 1 }.map { it.shape() })
        assertEquals(1, after.count { it.reference() == hook })
    }

    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            emptyList<ClassDef>() to "found 0",
            listOf(builder("Lfixture/A;"), builder("Lfixture/B;"), builder("Lfixture/C;")) to "found 3",
            listOf(builder("Lfixture/A;", withMin = false)) to "has no $min",
            listOf(builder("Lfixture/A;", jumpAfterMin = true)) to "has a jump to just after",
        )
        for ((classes, expected) in cases) {
            val failure = assertThrows(expected, PatchException::class.java) { PatchContexts.of(classes).findRecentLimits() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** On each declared build the group is found and the hook lands right after each count. */
    @Test
    fun everyDeclaredBuildTakesTheHookAfterTheCount() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                takesTheHook(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun takesTheHook(bundle: java.io.File) {
        val holders = FixtureDex.classesLoading(bundle, SHOW_MORE)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders) + ExtensionDex.classDef(RECENT_SEARCHES))
        val sites = context.findRecentLimits()
        assertTrue("${bundle.name}: ${sites.size} sites", sites.size in 1..2)
        val before = sites.map { site ->
            context.mutableClassDefBy(site.type).methods.single { it.matches(site) }.code().map { it.shape() }
        }

        context.showAllRecents(sites)

        sites.forEachIndexed { i, site ->
            val after = context.mutableClassDefBy(site.type).methods.single { it.matches(site) }.code()
            val at = after.indexOfFirst { it.reference() == hook }
            assertEquals("${bundle.name}: the hook follows the count", min, after[at - 2].reference())
            assertEquals("${bundle.name}: the rest is untouched", before[i], after.filterIndexed { n, _ -> n != at && n != at + 1 }.map { it.shape() })
        }
    }

    private fun MutableMethod.matches(site: RecentLimitSite) =
        name == site.name && parameterTypes.map(Any::toString) == site.parameters

    private fun builder(type: String, withMin: Boolean = true, jumpAfterMin: Boolean = false): ClassDef {
        val body = buildString {
            appendLine("const/4 v0, 0x3")
            appendLine("const/4 v1, 0x5")
            if (jumpAfterMin) appendLine("if-eqz v0, :after")
            if (withMin) {
                appendLine("invoke-static { v0, v1 }, $min")
                appendLine("move-result v2")
            }
            if (jumpAfterMin) appendLine(":after")
            appendLine("const-string v3, \"$SHOW_MORE\"")
            appendLine("const-string v3, \"$NULL_STATE_RECENT\"")
            appendLine("return-void")
        }
        val mutable = MutableMethod(
            ImmutableMethod(type, "build", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(4, emptyList(), null, null)),
        )
        mutable.addInstructionsWithLabels(0, body)
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(mutable)))
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.shape() = opcode to reference()
}
