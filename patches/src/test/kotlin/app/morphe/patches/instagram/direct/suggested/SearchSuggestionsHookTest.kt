/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * More suggestions in Messages search (#826): on every declared build the one method sorting those
 * accounts hands them to the extension first thing and goes on with its answer, and nothing else
 * changes. A build without it keeps the inbox's half of the patch.
 */
class SearchSuggestionsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SEARCH_SUGGESTIONS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SEARCH_SUGGESTIONS is not in the extension: $declared", SEARCH_SUGGESTIONS.substringAfter("->") in declared)
    }

    @Test
    fun everyBuildAsksForTheSearchSuggestions() {
        val checked = mutableListOf<String>()
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) checked += asks(bundle, bundle.name)
        }
        for (base in Fixtures.otherBuilds()) checked += asks(base, base.parentFile.name)
        assertTrue("no fixture was checked", checked.isNotEmpty())
    }

    @Test
    fun aBuildWithoutTheSearchKeepsTheInbox() {
        val context = PatchContexts.of(emptyList())
        val refusal = assertThrows(PatchException::class.java) { context.findSearchSuggestions() }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("found 0"))
        assertNull(context.findSearchSuggestionsOrWarn())
    }

    @Test
    fun theSectionsHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SEARCH_SECTIONS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SEARCH_SECTIONS is not in the extension: $declared", SEARCH_SECTIONS.substringAfter("->") in declared)
    }

    /** The header went with the switch on (#826): every build hands the search's section names over before building any. */
    @Test
    fun everyBuildHandsOverTheSearchSections() {
        val checked = mutableListOf<String>()
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) checked += sections(bundle, bundle.name)
        }
        for (base in Fixtures.otherBuilds()) checked += sections(base, base.parentFile.name)
        assertTrue("no fixture was checked", checked.isNotEmpty())
    }

    @Test
    fun aBuildWithoutTheSectionsKeepsTheEmptyList() {
        val context = PatchContexts.of(emptyList())
        val sort = UnitsSite("LX/0NJo;", "A00", listOf("Lcom/instagram/common/session/UserSession;", "Ljava/util/List;"))
        val refusal = assertThrows(PatchException::class.java) { context.findSearchSections(sort) }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("found 0"))
        assertNull(context.findSearchSectionsOrWarn(sort))
    }

    private fun sections(bundle: File, label: String): String {
        val loading = (FixtureDex.classesLoading(bundle, REMOVED_SUGGESTIONS) + FixtureDex.classesLoading(bundle, RECENT_QUERIES)).distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading))
        val site = runCatching { context.findSearchSections(context.findSearchSuggestions()) }.getOrElse { throw AssertionError("$label: ${it.message}", it) }
        val where = "$label: ${site.type}->${site.name}"
        val stock = context.sectionsMethod(site)
        val stockCode = stock.code()
        val walk = (site.at until stockCode.size).first { stockCode[it].opcode !in COUNTERS }
        // Read off the stock code: past the counters, the walk asks the size of the list the hook gets, and takes names out of it.
        assertEquals("$where: the walk starts by asking the list's size", "size()I", stockCode[walk].call())
        assertEquals("$where: of the list handed over", site.list, stockCode[walk].receiver())
        assertTrue("$where: and takes the names out of it", stockCode.any { it.call() == "get(I)Ljava/lang/Object;" && it.receiver() == site.list })
        val reached = site.at in stock.jumpTargets()
        val original = NeutralNativePath(stock)

        context.hookSearchSections(site)

        val built = context.sectionsMethod(site)
        val code = built.code()
        val ask = code[site.at] as RegisterRangeInstruction
        assertEquals("$where: hands the sections over", SEARCH_SECTIONS, code[site.at].reference())
        assertEquals("$where: the list of names", listOf(Opcode.INVOKE_STATIC_RANGE, site.list, 1), listOf(ask.opcode, ask.startRegister, ask.registerCount))
        assertEquals("$where: a jump to the counters reaches the hook first", reached, site.at in built.jumpTargets())
        assertEquals("$where: one call", 1, code.count { it.reference() == SEARCH_SECTIONS })
        original.assertPreserved(where, built, setOf(site.at))
        val elsewhere = loading.flatMap { context.mutableClassDefBy(it.type).methods }
            .filter { method -> method.code().any { it.reference() == SEARCH_SECTIONS } }
        assertEquals("$label: methods handing sections over", 1, elsewhere.size)
        return label
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.sectionsMethod(site: SectionsSite) =
        mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)
        ?.let { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }

    private fun Instruction.receiver(): Int? = when (this) {
        is FiveRegisterInstruction -> registerC
        is RegisterRangeInstruction -> startRegister
        else -> null
    }

    private fun asks(bundle: File, label: String): String {
        val loading = FixtureDex.classesLoading(bundle, REMOVED_SUGGESTIONS)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading))
        val site = runCatching { context.findSearchSuggestions() }.getOrElse { throw AssertionError("$label: ${it.message}", it) }
        val stock = context.method(site)
        assertTrue("$label: static", AccessFlags.STATIC.isSet(stock.accessFlags))
        assertEquals("$label: sorts accounts into a list", "Ljava/util/List;", stock.returnType)
        val original = NeutralNativePath(stock)

        context.hookSearchSuggestions(site)

        val sorted = context.method(site)
        val code = sorted.code()
        val accounts = sorted.implementation!!.registerCount - 1
        val where = "$label: ${site.type}->${site.name}"
        val ask = code[0] as RegisterRangeInstruction
        assertEquals("$where: asks first", SEARCH_SUGGESTIONS, code[0].reference())
        assertEquals("$where: with the accounts", listOf(Opcode.INVOKE_STATIC_RANGE, accounts, 1), listOf(ask.opcode, ask.startRegister, ask.registerCount))
        assertEquals("$where: and sorts the answer", listOf(Opcode.MOVE_RESULT_OBJECT, accounts), listOf(code[1].opcode, (code[1] as OneRegisterInstruction).registerA))
        assertEquals("$where: one ask", 1, code.count { it.reference() == SEARCH_SUGGESTIONS })
        original.assertPreserved(where, sorted, setOf(0, 1))
        val elsewhere = loading.flatMap { context.mutableClassDefBy(it.type).methods }
            .filter { method -> method.code().any { it.reference() == SEARCH_SUGGESTIONS } }
        assertEquals("$label: methods asking", 1, elsewhere.size)
        return label
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.method(site: UnitsSite) =
        mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        val COUNTERS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)
    }
}
