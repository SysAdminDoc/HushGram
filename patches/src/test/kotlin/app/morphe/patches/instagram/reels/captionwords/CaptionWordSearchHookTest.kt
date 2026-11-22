/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.captionwords

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.explore.HIDE_GRID
import app.morphe.patches.instagram.explore.PAGING_TOKEN
import app.morphe.patches.instagram.explore.emptyExplorePages
import app.morphe.patches.instagram.explore.findExploreParser
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Also in search (#807): the caption word patch hands the Explore page's and the search results
 * grid's section lists to the extension, each right before its parser hands the page back, beside
 * Hide the Explore grid's own hook in either patch order.
 */
class CaptionWordSearchHookTest {
    /** The hook is in the CaptionWordSearch the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CAPTION_WORD_SEARCH).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SEARCH_SECTIONS is not in the extension: $declared", SEARCH_SECTIONS.substringAfter("->") in declared)
    }

    @Test
    fun everyDeclaredBundleFiltersExploreAndSearchResults() {
        var checked = 0
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun everyOtherBuildFiltersExploreAndSearchResults() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    /** Without the hook in the extension nothing is looked for, and without the parsers each one is left out. */
    @Test
    fun aMissingHookOrParserLeavesItOut() {
        assertEquals(emptyList<SectionPage>(), PatchContexts.of(emptyList()).findSearchSectionPages())
        val bare = PatchContexts.of(listOf(ImmutableClassDef.of(ExtensionDex.classDef(CAPTION_WORD_SEARCH))))
        assertEquals(emptyList<SectionPage>(), bare.findSearchSectionPages())
        val failure = assertThrows(PatchException::class.java) { bare.findSearchResultsPage() }
        assertTrue(failure.message, failure.message!!.startsWith("Hide reels by caption words: expected one search results parser"))
    }

    private fun hooks(bundle: File, label: String) {
        val classes = sectionClasses(bundle)
        for (exploreFirst in listOf(true, false)) {
            val name = "$label, ${if (exploreFirst) "Explore grid" else "caption words"} first"
            val context = PatchContexts.of(classes)
            if (exploreFirst) context.emptyExplorePages(context.findExploreParser())
            val pages = context.findSearchSectionPages()
            assertEquals(name, listOf("Explore", "search results"), pages.map { it.name })
            pages.forEach { context.filterSearchSections(it) }
            if (!exploreFirst) context.emptyExplorePages(context.findExploreParser())

            for (page in pages) {
                val where = "$name, ${page.name}"
                val method = context.mutableClassDefBy(page.type).methods.single {
                    it.name == "unsafeParseFromJson" && it.parameterTypes.map(Any::toString) == page.parameters
                }
                val code = method.implementation!!.instructions.toList()
                assertEquals(where, 1, code.count { it.referenceText() == SEARCH_SECTIONS })
                val call = code.indexOfFirst { it.referenceText() == SEARCH_SECTIONS }
                val read = code[call - 1]
                assertEquals(where, Opcode.IGET_OBJECT, read.opcode)
                assertEquals(where, page.sections.toString(), ((read as ReferenceInstruction).reference as FieldReference).toString())
                assertEquals("$where: the page's own sections", page.register, (read as TwoRegisterInstruction).registerB)
                assertEquals(where, read.registerA, (code[call] as FiveRegisterInstruction).registerC)

                val returns = code.indices.filter {
                    code[it].opcode == Opcode.RETURN_OBJECT && (code[it] as OneRegisterInstruction).registerA == page.register
                }
                assertEquals(where, 1, returns.size)
                assertTrue("$where: the hook comes before the return", call < returns.single())
                assertTrue("$where: nothing jumps past the hook to the return", returns.single() !in method.jumpTargets())
                if (page.name == "Explore") {
                    assertEquals("$where: Hide the Explore grid's hook stays", 1, code.count { it.referenceText() == HIDE_GRID })
                    val hide = code.indexOfFirst { it.referenceText() == HIDE_GRID }
                    assertTrue("$where: the later patch's hook runs last", if (exploreFirst) hide < call else call < hide)
                }
            }
        }
    }

    /** The two parsers, the string pools they ask, and the extension's CaptionWordSearch. */
    private fun sectionClasses(bundle: File): List<ClassDef> {
        val pool = LinkedHashMap<String, ClassDef>()
        (FixtureDex.classesLoading(bundle, PAGING_TOKEN) + FixtureDex.classesLoading(bundle, RESULT_MORE_REELS))
            .forEach { pool[it.type] = it }
        return FixtureDex.withStringPools(bundle, pool.values) + ImmutableClassDef.of(ExtensionDex.classDef(CAPTION_WORD_SEARCH))
    }

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
}
