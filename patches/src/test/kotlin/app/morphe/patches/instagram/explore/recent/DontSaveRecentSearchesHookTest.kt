/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore.recent

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Don't save recent searches asks first in the recent searches cache's add, and nowhere else in the cache. */
class DontSaveRecentSearchesHookTest {
    private val cacheType = "Lfixture/RecentCache;"
    private val listAdd = ImmutableMethodReference("Ljava/util/List;", "add", listOf("I", "Ljava/lang/Object;"), "V")
    private val listRemove = ImmutableMethodReference("Ljava/util/List;", "remove", listOf("Ljava/lang/Object;"), "Z")

    private fun method(name: String, registers: Int, vararg code: Instruction, parameters: List<String> = listOf("Ljava/lang/Object;")) =
        ImmutableMethod(
            cacheType, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(registers, code.toList(), null, null),
        )

    private fun cache(vararg adds: Method) = ImmutableClassDef(
        cacheType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf<Method>(
            method("save", 2,
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(RECENT_CACHE)),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(RECENT_CACHE_FAILED)),
                ImmutableInstruction10x(Opcode.RETURN_VOID), parameters = emptyList()),
            method("remove", 3,
                ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 0, 2, 0, 0, 0, listRemove),
                ImmutableInstruction10x(Opcode.RETURN_VOID)),
        ) + adds,
    )

    /** v2 `this`, v3 the entry, v0 written before it's read and v1 never used, so both are free at the start. */
    private fun add(name: String = "add") = method(name, 4,
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, 2, 0, 3, 0, 0, listAdd),
        ImmutableInstruction10x(Opcode.RETURN_VOID))

    @Test
    fun theCachesAddAsksFirst() {
        val context = PatchContexts.of(listOf(cache(add())))
        val found = context.findRecentAdd()
        assertEquals("add", found.name)

        found.askFirst()

        assertAsksFirst(context.mutableClassDefBy(cacheType).methods.single { it.name == "add" })
        val remove = context.mutableClassDefBy(cacheType).methods.single { it.name == "remove" }.code()
        assertEquals("the remove stays", Opcode.INVOKE_INTERFACE, remove[0].opcode)
    }

    @Test
    fun aCacheWithoutOneAddFails() {
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(cache())).findRecentAdd() }
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(cache(add("a"), add("b")))).findRecentAdd() }
    }

    @Test
    fun theHookIsInTheExtension() {
        val keep = ExtensionDex.classDef(RECENT_SEARCHES).methods.single { it.name == "keep" && it.parameterTypes.isEmpty() }
        assertEquals("Z", keep.returnType)
        assertTrue(AccessFlags.PUBLIC.isSet(keep.accessFlags) && AccessFlags.STATIC.isSet(keep.accessFlags))
    }

    /**
     * On the declared build the cache and the call to the servers are each found once, and after
     * the hooks go in each starts by asking.
     */
    @Test
    fun eachDeclaredBuildAsksFirstInBoth() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.stringSection.none { it == RECENT_CACHE || it == REGISTER_RECENT }) return@forEach
                    for (classDef in dex.classes) {
                        val holds = classDef.methods.any { method ->
                            method.code().any { (it as? ReferenceInstruction)?.reference.let { ref ->
                                ref is StringReference && (ref.string == RECENT_CACHE || ref.string == REGISTER_RECENT)
                            } }
                        }
                        if (holds) classes += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(classes)
                val add = context.findRecentAdd()
                val register = context.mutableClassDefBy(
                    classes.single { c -> c.methods.any { m -> m.code().any { (it as? ReferenceInstruction)?.reference.let { ref -> ref is StringReference && ref.string == REGISTER_RECENT } } } }.type,
                ).methods.single { m -> m.code().any { (it as? ReferenceInstruction)?.reference.let { ref -> ref is StringReference && ref.string == REGISTER_RECENT } } }
                add.askFirst()
                register.askFirst()
                assertAsksFirst(add)
                assertAsksFirst(register)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertAsksFirst(method: Method) {
        val code = method.code()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals(KEEP, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_NEZ, code[2].opcode)
        assertEquals((code[1] as OneRegisterInstruction).registerA, (code[2] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_VOID, code[3].opcode)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
}
