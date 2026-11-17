/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.reelsort

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sort reels on profiles (#679): on every build of the declared version, each unboxed read of the
 * Reels tab provider's sort gate, in the tab coming up and in a tap on its title, goes through the
 * extension right after its move-result, and nothing else in either method changes. Some builds
 * have Redex move the read into a small static method of the provider; there each call of it is
 * hooked the same way.
 */
class SortProfileReelsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_SORT).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$REEL_SORT->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SORTABLE_HOOK is not in the extension: $declared", SORTABLE_HOOK in declared)
    }

    @Test
    fun aBuildWithoutTheTabFailsBeforeAnythingChanges() {
        val refused = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(ExtensionDex.classDef(REEL_SORT))).findReelSort() }
        assertTrue(refused.message, refused.message!!.contains(TAB_SELECTED))
    }

    @Test
    fun anExtensionWithoutTheHookFailsBeforeAnythingChanges() {
        val refused = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findReelSort() }
        assertTrue(refused.message, refused.message!!.contains(REEL_SORT))
    }

    @Test
    fun eachDeclaredBuildHooksEveryReadOfTheSortGate() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksThemToo() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    private fun hooks(bundle: File, label: String) {
        val context = PatchContexts.of(load(bundle) + ExtensionDex.classDef(REEL_SORT))
        val site = runCatching { context.findReelSort() }.getOrElse { throw AssertionError("$label: ${it.message}", it) }

        val gate = site.gate
        assertEquals("$label: the gate is the provider's own field", site.provider, gate.definingClass)
        val methods = site.reads.map { it.method }.distinctBy { it.name }
        assertEquals("$label: both the tab coming up and a tap on its title read it", 2, methods.size)

        val of = { method: Method ->
            context.mutableClassDefBy(site.provider).methods.single {
                it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
            }
        }
        val before = methods.associate { it.name to NeutralNativePath(of(it)) }
        // Read off the stock code apart from the finder: every call on the gate field's type, each
        // unboxed, and every call of a static method of the provider whose body calls on that type
        // (where Redex moved the read out), each answer taken. Each entry is the answer's move-result.
        val readsGate = { call: MethodReference ->
            context.mutableClassDefBy(site.provider).methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }.any { helper ->
                helper.name == call.name && helper.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
                    helper.code().any { it.methodCalled()?.definingClass == gate.type }
            }
        }
        val stockResults = methods.associate { method ->
            val code = of(method).code()
            method.name to code.indices.mapNotNull { at ->
                val call = code[at].methodCalled() ?: return@mapNotNull null
                when {
                    call.definingClass == gate.type && call.parameterTypes.isEmpty() -> {
                        assertEquals("$label: ${method.name} unboxes each read at once", "Ljava/lang/Boolean;->booleanValue()Z", code[at + 3].methodCalled()?.toString())
                        at + 4
                    }
                    code[at].opcode == Opcode.INVOKE_STATIC && call.definingClass == site.provider && call.returnType == "Z" && readsGate(call) -> at + 1
                    else -> null
                }
            }.onEach { assertEquals("$label: ${method.name} takes each answer", Opcode.MOVE_RESULT, code[it].opcode) }
        }
        assertTrue("$label: the gate is read where the tab comes up", stockResults.values.all { it.isNotEmpty() })
        val registers = methods.associate { method ->
            val code = of(method).code()
            method.name to stockResults.getValue(method.name).map { (code[it] as OneRegisterInstruction).registerA }
        }

        context.applyReelSort(site)

        for (method in methods) {
            val code = of(method).code()
            val hooks = code.indices.filter { code[it].methodCalled()?.toString() == SORTABLE_HOOK }
            // Each earlier hook adds two instructions in front of the next read.
            val expected = stockResults.getValue(method.name).mapIndexed { n, result -> result + 1 + 2 * n }
            assertEquals("$label: ${method.name} hooks each read right after its move-result", expected, hooks)
            hooks.zip(registers.getValue(method.name)).forEach { (at, register) ->
                val call = code[at] as RegisterRangeInstruction
                assertEquals("$label: ${method.name} hands the hook the answer's register", register to 1, call.startRegister to call.registerCount)
                assertEquals("$label: and takes it back", Opcode.MOVE_RESULT, code[at + 1].opcode)
                assertEquals("$label: into the same register", register, (code[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$label: right after Instagram's own", Opcode.MOVE_RESULT, code[at - 1].opcode)
            }
            val added = hooks.flatMap { listOf(it, it + 1) }.toSet()
            before.getValue(method.name).assertPreserved("$label: ${method.name}", of(method), added)
        }
        val elsewhere = context.mutableClassDefBy(site.provider).methods.filter { method -> method.name !in methods.map { it.name } }
            .count { method -> method.code().any { it.methodCalled()?.toString() == SORTABLE_HOOK } }
        assertEquals("$label: no other method of the provider is touched", 0, elsewhere)
    }

    /** The classes loading the provider's two strings, directly or through a pool, and the pools. */
    private fun load(bundle: File): List<ClassDef> {
        val classes = (FixtureDex.classesLoading(bundle, TAB_SELECTED) + FixtureDex.classesLoading(bundle, TAB_CLICKED))
            .distinctBy { it.type }
        assertTrue("${bundle.name}: a class loads the Reels tab's strings", classes.isNotEmpty())
        return classes
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.methodCalled(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
}
