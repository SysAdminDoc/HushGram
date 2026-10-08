/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.scrolling

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stop after 20 reels hears of every page the Reels pager lands on, from each store of its current page. */
class ReelPagesHookTest {
    private val current = ImmutableFieldReference(VIEW_PAGER, "A00", "I")
    private val other = ImmutableFieldReference(VIEW_PAGER, "A01", "I")

    private fun method(type: String, name: String, registers: Int, vararg code: Instruction, returns: String = "V") = ImmutableMethod(
        type, name, emptyList(), returns, AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(registers, code.toList(), null, null),
    )

    private fun pager() = ImmutableClassDef(
        VIEW_PAGER, AccessFlags.PUBLIC.value, "Landroid/view/ViewGroup;", null, null, null,
        listOf(ImmutableField(VIEW_PAGER, "A00", "I", AccessFlags.PUBLIC.value, null, null, null)),
        listOf<Method>(
            method(VIEW_PAGER, CURRENT_ITEM, 2,
                ImmutableInstruction22c(Opcode.IGET, 0, 1, current), ImmutableInstruction11x(Opcode.RETURN, 0), returns = "I"),
            method(VIEW_PAGER, "setAdapter", 3,
                ImmutableInstruction22c(Opcode.IPUT, 0, 2, other),
                ImmutableInstruction22c(Opcode.IPUT, 1, 2, current),
                ImmutableInstruction10x(Opcode.RETURN_VOID)),
        ),
    )

    /** The page callback outside the pager: v3 the pager, v4 the page, stored twice. */
    private val callback = ImmutableClassDef(
        "Lfixture/PageCallback;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            method("Lfixture/PageCallback;", "selected", 5,
                ImmutableInstruction22c(Opcode.IPUT, 4, 3, current),
                ImmutableInstruction22c(Opcode.IPUT, 0, 3, current),
                ImmutableInstruction10x(Opcode.RETURN_VOID)),
        ),
    )

    @Test
    fun eachStoreOfTheCurrentPageReportsIt() {
        val context = PatchContexts.of(listOf(pager(), callback))
        val stores = context.findPageStores()
        assertEquals(3, stores.size)

        context.reportPages(stores)

        val adapter = context.mutableClassDefBy(VIEW_PAGER).methods.single { it.name == "setAdapter" }.code()
        assertEquals("the other field's store has no hook", Opcode.IPUT, adapter[1].opcode)
        assertHook(adapter[2], pager = 2, position = 1)
        val selected = context.mutableClassDefBy("Lfixture/PageCallback;").methods.single().code()
        assertEquals(Opcode.IPUT, selected[0].opcode)
        assertHook(selected[1], pager = 3, position = 4)
        assertEquals(Opcode.IPUT, selected[2].opcode)
        assertHook(selected[3], pager = 3, position = 0)
        assertEquals(Opcode.RETURN_VOID, selected[4].opcode)
    }

    @Test
    fun aBuildWhereOnlyThePagerStoresItsPageFails() {
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(pager())).findPageStores() }
    }

    @Test
    fun aGetterReadingSomethingElseFails() {
        val odd = pager().let { stock ->
            ImmutableClassDef(
                VIEW_PAGER, stock.accessFlags, stock.superclass, null, null, null, stock.fields,
                stock.methods.filter { it.name != CURRENT_ITEM } + method(VIEW_PAGER, CURRENT_ITEM, 1,
                    ImmutableInstruction11x(Opcode.RETURN, 0), returns = "I"),
            )
        }
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(odd, callback)).findPageStores() }
    }

    /**
     * On the declared build, the pager's current page is stored inside the pager and in its page
     * callback outside it, and after the hooks go in each store is followed by the report, on the
     * store's own registers.
     */
    @Test
    fun eachDeclaredBuildReportsEveryStore() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.fieldSection.none { it.definingClass == VIEW_PAGER }) return@forEach
                    for (classDef in dex.classes) {
                        val writesPager = classDef.methods.any { method ->
                            method.code().any { it.opcode == Opcode.IPUT && it.field()?.definingClass == VIEW_PAGER }
                        }
                        if (classDef.type == VIEW_PAGER || writesPager) classes += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(classes)
                val stores = context.findPageStores()
                assertTrue("${bundle.name}: a store outside the pager", stores.any { it.type != VIEW_PAGER })
                assertTrue("${bundle.name}: a store in the pager", stores.any { it.type == VIEW_PAGER })

                context.reportPages(stores)

                stores.groupBy { it.type to it.name }.forEach { (where, inMethod) ->
                    val code = context.mutableClassDefBy(where.first).methods.filter { it.name == where.second }
                        .single { method -> method.code().any { it.referenceText() == PAGE_HOOK } }.code()
                    val hooks = code.withIndex().filter { it.value.referenceText() == PAGE_HOOK }
                    assertEquals("${bundle.name}: ${where.first}->${where.second} hooks", inMethod.size, hooks.size)
                    hooks.forEach { (index, hook) ->
                        val store = code[index - 1] as TwoRegisterInstruction
                        assertEquals("${bundle.name}: ${where.first}->${where.second} at $index", Opcode.IPUT, code[index - 1].opcode)
                        assertHook(hook, store.registerB, store.registerA)
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertHook(instruction: Instruction, pager: Int, position: Int) {
        assertEquals(Opcode.INVOKE_STATIC, instruction.opcode)
        assertEquals(PAGE_HOOK, instruction.referenceText())
        val call = instruction as FiveRegisterInstruction
        assertEquals(listOf(pager, position), listOf(call.registerC, call.registerD).take(call.registerCount))
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
