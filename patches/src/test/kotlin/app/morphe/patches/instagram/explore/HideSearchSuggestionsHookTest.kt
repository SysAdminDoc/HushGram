/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide suggested accounts in search, a switch of Hide the Explore grid: both builders of the search screen, found and hooked. */
class HideSearchSuggestionsHookTest {
    private val oldSearch = "Lfixture/OldSearch;"
    private val newSearch = "Lfixture/NewSearch;"
    private val item = "Lfixture/SearchItem;"
    private val kind = "Lfixture/SearchKind;"
    private val config = "Lfixture/SearchConfig;"
    private val builderParameters = listOf("Lfixture/Repository;", newSearch, "Ljava/util/List;", "Z")
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

    /** Both hooks the patch writes are in the SearchSuggestions the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(SEARCH_SUGGESTIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HIDE_SECTION, NULL_STATE_ITEMS)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The older section returns first thing when told to, and the newer builder starts by filtering its list. */
    @Test
    fun bothBuildersAreHooked() {
        val context = PatchContexts.of(classes())
        val sectionBefore = classes().single { it.type == oldSearch }.methods.single().code()
        val builderBefore = classes().single { it.type == newSearch }.methods.single().code()

        val sites = context.findSearchSuggestions()
        context.hideSearchSuggestions(sites)

        assertEquals("the items", item, sites.itemType)
        val section = context.mutableClassDefBy(oldSearch).methods.single()
        assertSectionGuarded("stand-in", section)
        val sectionCode = section.code()
        val guard = sectionCode.indexOfFirst { it.referenceText() == HIDE_SECTION }
        assertEquals(
            "Instagram's section around the guard", sectionBefore.map { it.opcode },
            (sectionCode.take(guard) + sectionCode.drop(guard + 3)).map { it.opcode },
        )
        val builder = context.mutableClassDefBy(newSearch).methods.single()
        assertItemsFiltered("stand-in", builder, builder.implementation!!.registerCount - 2)
        assertEquals("Instagram's builder after the filter", builderBefore.map { it.opcode }, builder.code().drop(2).map { it.opcode })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(sections = 0) to "expected one method adding the search screen's $NULL_STATE_SUGGESTED section, found 0",
            classes(sections = 2) to "expected one method adding the search screen's $NULL_STATE_SUGGESTED section, found 2",
            classes(builderHoldsRecents = false) to "expected one builder of the search screen taking a list and holding $SHOW_MORE_RECENTS, found 0",
            classes(loopsToStart = true) to "something in $newSearch->A00 jumps back to its start",
            classes(kindNames = listOf("RECENTS", "NULLSTATE_SUGGESTIONS")) to
                "expected the search screen's builder to cast to one class with a kind naming SUGGESTED_USERS, found []",
            classes(kindNames = listOf("SUGGESTED_USERS")) to "expected one enum field of $item whose type names all of $SEARCH_ITEM_KINDS",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideSearchSuggestions(context.findSearchSuggestions())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == HIDE_SECTION || it.referenceText() == NULL_STATE_ITEMS } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each of the three 452 builds both builders (the older section and the newer builder) are
     * there once and hooked.
     */
    @Test
    fun eachDeclaredBuildHooksBothBuilders() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            val casts = mutableSetOf<String>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val holding = classDef.methods.filter { m -> m.code().any { it.string() == NULL_STATE_SUGGESTED } }
                    if (holding.isNotEmpty() || classDef.superclass == "Ljava/lang/Enum;") holders += ImmutableClassDef.of(classDef)
                    holding.forEach { m -> m.code().filter { it.opcode == Opcode.CHECK_CAST }.forEach { casts += (it.reference() as TypeReference).type } }
                }
            }
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.type in casts && holders.none { it.type == classDef.type }) holders += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders)

            val sites = context.findSearchSuggestions()
            context.hideSearchSuggestions(sites)

            val section = context.mutableClassDefBy(sites.sectionType).methods.single { it.name == sites.sectionName && it.parameterTypes.map(CharSequence::toString) == sites.sectionParameters }
            assertSectionGuarded(bundle.name, section)
            val builder = context.mutableClassDefBy(sites.builderType).methods.single { it.name == sites.builderName && it.parameterTypes.map(CharSequence::toString) == sites.builderParameters }
            assertEquals("${bundle.name}: the builder's list", "Ljava/util/List;", sites.builderParameters[2])
            assertItemsFiltered(bundle.name, builder, builder.implementation!!.registerCount - 2)
            for (hook in listOf(HIDE_SECTION, NULL_STATE_ITEMS)) {
                assertEquals(
                    "${bundle.name}: methods calling $hook", 1,
                    holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == hook } } },
                )
            }
        }
    }

    /**
     * The recents are built, the extension is asked, and the suggested block is skipped when it says
     * so: the hook sits after the recents and before the suggested names, and branches past them.
     */
    private fun assertSectionGuarded(what: String, method: Method) {
        val code = method.code()
        val call = code.indexOfFirst { it.referenceText() == HIDE_SECTION }
        assertTrue("$what: the hook is missing", call >= 0)
        assertEquals("$what: one hook", 1, code.count { it.referenceText() == HIDE_SECTION })
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[call + 1].opcode)
        val answer = (code[call + 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the branch", Opcode.IF_NEZ, code[call + 2].opcode)
        assertEquals("$what: the branch tests the answer", answer, (code[call + 2] as OneRegisterInstruction).registerA)
        val recents = code.indexOfFirst { it.string() == SHOW_MORE_RECENTS }
        val suggested = code.indexOfFirst { it.string() == NULL_STATE_SUGGESTED }
        // Some 453 builds put the recents outside the section, which then holds no Show more row.
        assertTrue("$what: recents built before the hook", recents < call)
        assertTrue("$what: suggested names after the hook", suggested > call + 2)
    }

    /** First thing: the list's register handed to the extension and its answer put back in it. */
    private fun assertItemsFiltered(what: String, method: Method, list: Int) {
        val code = method.code()
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$what: the hook", NULL_STATE_ITEMS, code[0].referenceText())
        assertEquals("$what: the list", list, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: back in the list's register", list, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == NULL_STATE_ITEMS })
    }

    /**
     * Shaped like 450's: the older screen's static section method and the newer screen's static
     * builder, both loading the two names, the builder also Show more and casting to its config and
     * to its items, whose kind is an enum naming RECENTS and SUGGESTED_USERS.
     */
    private fun classes(
        sections: Int = 1,
        sectionStatic: Boolean = true,
        builderHoldsRecents: Boolean = true,
        loopsToStart: Boolean = false,
        kindNames: List<String> = listOf("RECENTS", "SUGGESTED_USERS", "NULLSTATE_SUGGESTIONS"),
    ): List<ClassDef> {
        val names = """
            const-string v0, "$NULL_STATE_SUGGESTED"
            const-string v0, "$FOLLOW_CHAINING_UNIT"
        """
        // Shaped like 452's older section: the recents row first, then Instagram's own checks guard
        // the suggested block, skipping to a common point past it when there's nothing to suggest.
        val section = { name: String ->
            method(
                oldSearch, name, listOf("Lfixture/OldState;", oldSearch), "V", 1, if (sectionStatic) static else AccessFlags.PUBLIC.value,
                """
                    const-string v0, "$SHOW_MORE_RECENTS"
                    if-eqz p0, :hidden
                    if-eqz p1, :hidden
                    $names
                    :hidden
                    return-void
                """,
            )
        }
        val builder = method(
            newSearch, "A00", builderParameters, "Lfixture/Screen;", 2, static,
            listOfNotNull(
                if (loopsToStart) ":start" else null,
                names,
                if (builderHoldsRecents) "const-string v0, \"$SHOW_MORE_RECENTS\"" else null,
                "invoke-interface {p2}, Ljava/util/List;->iterator()Ljava/util/Iterator;",
                "move-result-object v0",
                "invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;",
                "move-result-object v1",
                "check-cast v1, $item",
                "check-cast v0, $config",
                if (loopsToStart) "if-eqz p3, :start" else null,
                "const/4 v0, 0x0",
                "return-object v0",
            ).joinToString("\n"),
        )
        val setup = method(
            kind, "<clinit>", emptyList(), "V", 1, static or AccessFlags.CONSTRUCTOR.value,
            (kindNames.map { "const-string v0, \"$it\"" } + "return-void").joinToString("\n"),
        )
        val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        return listOf(
            classDef(oldSearch, "Ljava/lang/Object;", List(sections) { section(if (it == 0) "A02" else "A03") }),
            classDef(newSearch, "Ljava/lang/Object;", listOf(builder)),
            classDef(item, "Ljava/lang/Object;", emptyList(), listOf(ImmutableField(item, "A03", kind, instance, null, null, null))),
            classDef(config, "Ljava/lang/Object;", emptyList(), listOf(ImmutableField(config, "A08", "Z", instance, null, null, null))),
            classDef(kind, "Ljava/lang/Enum;", listOf(setup)),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt() + if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, superclass: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, fields, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = (reference() as? MethodReference)?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string
}
