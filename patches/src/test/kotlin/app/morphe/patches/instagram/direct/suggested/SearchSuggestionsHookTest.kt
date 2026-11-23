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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
}
