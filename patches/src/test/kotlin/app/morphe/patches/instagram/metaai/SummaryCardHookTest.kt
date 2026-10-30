/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Meta AI's summary card (#152): the search results page's two row makers for Meta AI's card
 * pass the row they inflate through the extension before the row's holder takes it, and nothing
 * else about them changes.
 */
class SummaryCardHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SUMMARY_CARD.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SUMMARY_CARD is not in the extension: $declared", SUMMARY_CARD.substringAfter("->") in declared)
    }

    @Test
    fun eachRowMakerPassesItsRowThroughTheExtension() {
        val context = PatchContexts.of(classes())
        val rows = context.findSummaryRows()
        assertEquals(listOf(CARD, PLUGIN), rows.map { it.type })
        val before = rows.map { context.code(it) }
        context.foldSummaryRows(rows)
        rows.forEachIndexed { index, row -> assertFolds(row.type, context, row, before[index]) }
    }

    @Test
    fun noRowMakerFailsThePatch() = refused("0 search results row makers inflate layout $PLUGIN_LAYOUT", classes(plugin = null))

    @Test
    fun twoRowMakersFailThePatch() = refused("2 search results row makers inflate layout $CARD_LAYOUT", classes(second = true))

    @Test
    fun aMakerLoadingItsLayoutTwiceFailsThePatch() = refused("loads layout $CARD_LAYOUT 2 times", classes(card = "twice"))

    @Test
    fun aMakerThatInflatesSomethingElseFailsThePatch() = refused("doesn't inflate layout $CARD_LAYOUT", classes(card = "notaview"))

    @Test
    fun aMakerThatDropsItsRowFailsThePatch() = refused("drops the row it inflates", classes(card = "dropped"))

    @Test
    fun aRowThatNeverReachesAHolderFailsThePatch() = refused("doesn't hand the row it inflates", classes(card = "noholder"))

    @Test
    fun aRowSwappedBeforeItsHolderFailsThePatch() = refused("doesn't hand the row it inflates", classes(card = "swapped"))

    @Test
    fun aJumpPastTheHookFailsThePatch() = refused("jumps past the row it inflates", classes(plugin = "jump"))

    /** Hook test over each declared build: two makers, Meta AI's card's first, each hooked once. */
    @Test
    fun eachDeclaredBuildFoldsTheSummaryRows() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                foldsTheSummaryRowsIn(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The same in the two other builds of 452, which Redex names differently. */
    @Test
    fun eachOtherBuildFoldsTheSummaryRows() {
        val builds = Fixtures.otherBuilds()
        for (base in builds) foldsTheSummaryRowsIn(base, base.parentFile.name)
        assertEquals("the builds besides the declared one", Fixtures.OTHER_BUILDS, builds.size)
    }

    /**
     * Slices the fixture the way the finder reads it: every class with a method loading either
     * layout, with the string pools they call, so a second maker anywhere in the app would be there.
     */
    private fun foldsTheSummaryRowsIn(bundle: File, label: String) {
        val types = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral in SUMMARY_LAYOUTS } == true
        }.map { it.definingClass }.toSet()
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, FixtureDex.classes(bundle, types).values))
        val rows = context.findSummaryRows()
        assertEquals("$label: one maker per layout", SUMMARY_LAYOUTS.size, rows.size)
        val card = context.code(rows[0])
        assertTrue(
            "$label: the first is Meta AI's card's maker",
            card.indices.any { context.stringLoadedAt(card, it) == "MetaAIHCMDefinitionCreationCount" },
        )
        val before = rows.map { context.code(it) }
        context.foldSummaryRows(rows)
        rows.forEachIndexed { index, row -> assertFolds("$label ${row.type}->${row.name}", context, row, before[index]) }
    }

    /** Right after the inflate's move-result: the row through the extension and back, and the rest as it was. */
    private fun assertFolds(what: String, context: BytecodePatchContext, row: HookSite, before: List<Instruction>) {
        val after = context.code(row)
        val calls = after.indices.filter { after[it].reference() == SUMMARY_CARD }
        assertEquals("$what: one call, right after the row is inflated", listOf(row.moveResult + 1), calls)
        val call = after[row.moveResult + 1] as RegisterRangeInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC_RANGE, after[row.moveResult + 1].opcode)
        assertEquals("$what: with the row", row.register to 1, call.startRegister to call.registerCount)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT_OBJECT, after[row.moveResult + 2].opcode)
        assertEquals("$what: back in the row's register", row.register, (after[row.moveResult + 2] as OneRegisterInstruction).registerA)
        assertEquals(
            "$what: the rest is as it was",
            before.map { it.opcode },
            (after.take(row.moveResult + 1) + after.drop(row.moveResult + 3)).map { it.opcode },
        )
    }

    private fun BytecodePatchContext.code(row: HookSite): List<Instruction> = mutableClassDefBy(row.type).methods.single {
        it.name == row.name && it.parameterTypes.map(CharSequence::toString) == row.parameters
    }.implementation!!.instructions.toList()

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findSummaryRows() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private companion object {
        const val CARD = "Lfixture/CardDefinition;"
        const val PLUGIN = "Lfixture/PluginDefinition;"

        /** How the refusals name the two layouts, which Instagram renumbers each version. */
        val CARD_LAYOUT = "0x${SUMMARY_LAYOUTS[0].toString(16)}"
        val PLUGIN_LAYOUT = "0x${SUMMARY_LAYOUTS[1].toString(16)}"
        const val HOLDER = "Lfixture/Holder;"
        const val INFLATE = "Lfixture/Inflate;->A06(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;IZ)Landroid/view/View;"

        /**
         * Shaped like 450's row makers: load the layout, inflate it into the page's list without
         * adding it, and hand the row to a new holder. [variant] breaks one thing at a time.
         */
        fun smali(layout: Int, variant: String): String {
            val inflate = if (variant == "notaview") INFLATE.replace(")Landroid/view/View;", ")Ljava/lang/Object;") else INFLATE
            val result = if (variant == "dropped") "nop" else "move-result-object v1"
            val holder = when (variant) {
                "noholder" -> "invoke-direct {v0}, $HOLDER-><init>()V"
                else -> "invoke-direct {v0, v1}, $HOLDER-><init>(Landroid/view/View;)V"
            }
            return """
                const/4 v1, 0x0
                ${if (variant == "jump") "if-eqz p1, :made" else "nop"}
                const v0, 0x${layout.toString(16)}
                invoke-static {p1, p2, v0, v1}, $inflate
                $result
                :made
                ${if (variant == "swapped") "const/4 v1, 0x0" else "nop"}
                new-instance v0, $HOLDER
                $holder
                ${if (variant == "twice") "const v2, 0x${layout.toString(16)}" else "nop"}
                return-object v0
            """.trimIndent()
        }

        fun maker(owner: String, layout: Int, variant: String): Method {
            val method = MutableMethod(
                ImmutableMethod(
                    owner, "A0R",
                    listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;").map { ImmutableMethodParameter(it, null, null) },
                    HOLDER, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(6, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali(layout, variant))
            return ImmutableMethod.of(method)
        }

        /** Both makers, or [card] and [plugin] as variants, null for none, plus a method of another shape loading each layout. */
        fun classes(card: String? = "ok", plugin: String? = "ok", second: Boolean = false): List<ClassDef> {
            fun definition(type: String, methods: List<Method>) = ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods,
            )
            val (cardLayout, pluginLayout) = SUMMARY_LAYOUTS
            val makers = listOfNotNull(
                card?.let { definition(CARD, listOf(maker(CARD, cardLayout, it))) },
                plugin?.let { definition(PLUGIN, listOf(maker(PLUGIN, pluginLayout, it))) },
                if (second) definition("Lfixture/OtherDefinition;", listOf(maker("Lfixture/OtherDefinition;", cardLayout, "ok"))) else null,
            )
            // Instagram's lazy card body loads a layout too, in a method of another shape.
            val body = MutableMethod(
                ImmutableMethod(
                    "Lfixture/CardBody;", "invoke", emptyList(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null,
                    ImmutableMethodImplementation(2, emptyList(), null, null),
                ),
            )
            body.addInstructionsWithLabels(0, "const v0, 0x${cardLayout.toString(16)}\nconst v0, 0x${pluginLayout.toString(16)}\nreturn-object p0")
            return makers + definition("Lfixture/CardBody;", listOf(ImmutableMethod.of(body)))
        }
    }
}
