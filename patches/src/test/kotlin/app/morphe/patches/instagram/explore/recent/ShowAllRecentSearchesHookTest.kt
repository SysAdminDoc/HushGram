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
import app.morphe.patches.instagram.misc.extension.loadsString
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

    /**
     * #375: a builder that makes the Show more row without naming its string is found too, and a
     * method naming the Recent group with neither, like the group's own factory, is left alone.
     */
    @Test
    fun aBuilderThatOnlyMakesTheShowMoreRowIsFoundToo() {
        val context = PatchContexts.of(
            listOf(builder("Lfixture/Older;"), builder("Lfixture/Newer;", showMore = ShowMore.MADE), builder("Lfixture/Group;", showMore = ShowMore.NONE), showMoreRow()),
        )
        val sites = context.findRecentLimits()
        assertEquals(listOf("Lfixture/Older;", "Lfixture/Newer;"), sites.map { it.type }.sortedDescending())

        context.showAllRecents(sites)

        for (type in listOf("Lfixture/Older;", "Lfixture/Newer;")) {
            val after = context.mutableClassDefBy(type).methods.single { it.name == "build" }.code()
            assertEquals(type, 1, after.count { it.reference() == hook })
        }
        assertEquals(0, context.mutableClassDefBy("Lfixture/Group;").methods.single().code().count { it.reference() == hook })
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
        val holders = (FixtureDex.classesLoading(bundle, SHOW_MORE) + FixtureDex.classesLoading(bundle, NULL_STATE_RECENT)).distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders) + ExtensionDex.classDef(RECENT_SEARCHES))
        val sites = context.findRecentLimits()
        assertTrue("${bundle.name}: ${sites.size} sites", sites.size in 1..2)
        assertTrue(
            "${bundle.name}: #375, the builder that only makes the Show more row is hooked too",
            sites.any { site -> context.mutableClassDefBy(site.type).methods.single { it.matches(site) }.let { !context.loadsString(it, SHOW_MORE) } },
        )
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

    /** How a builder reaches the Show more row: names its string, makes the row, or neither. */
    private enum class ShowMore { NAMED, MADE, NONE }

    private val row = "Lfixture/ShowMoreRow;"

    /** The Show more row, whose constructor names [SHOW_MORE]. */
    private fun showMoreRow(): ClassDef {
        val init = MutableMethod(
            ImmutableMethod(row, "<init>", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null, ImmutableMethodImplementation(2, emptyList(), null, null)),
        )
        init.addInstructionsWithLabels(0, "const-string v0, \"$SHOW_MORE\"\nreturn-void")
        return ImmutableClassDef(row, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(init)))
    }

    private fun builder(type: String, withMin: Boolean = true, jumpAfterMin: Boolean = false, showMore: ShowMore = ShowMore.NAMED): ClassDef {
        val body = buildString {
            appendLine("const/4 v0, 0x3")
            appendLine("const/4 v1, 0x5")
            if (jumpAfterMin) appendLine("if-eqz v0, :after")
            if (withMin) {
                appendLine("invoke-static { v0, v1 }, $min")
                appendLine("move-result v2")
            }
            if (jumpAfterMin) appendLine(":after")
            when (showMore) {
                ShowMore.NAMED -> appendLine("const-string v3, \"$SHOW_MORE\"")
                ShowMore.MADE -> appendLine("new-instance v3, $row")
                ShowMore.NONE -> Unit
            }
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
