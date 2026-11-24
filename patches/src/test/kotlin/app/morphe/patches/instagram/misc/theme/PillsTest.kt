/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Saved's filters hand each tab's background to the extension and take back what it answers (#187). */
class PillsTest {
    /**
     * Saved's tab maker as 457's `LX/0LXK;->A01` has it: the background loaded by its id, then given to
     * the tab with a marker through a static (Drawable, View, int) call. The hook goes between the
     * load's answer and the marker with the drawable's, the tab's and the id's registers, its answer
     * goes back in the drawable's register, and every instruction of Instagram's stays as it was.
     */
    @Test
    fun aTabsBackgroundGoesThroughTheExtension() {
        val context = PatchContexts.of(listOf(tabs(), extension()))
        context.tintSavedFilters()
        val code = context.mutableClassDefBy(TABS).methods.single { it.name == "A01" }.code()
        val load = code.indexOfFirst { it.callsMethod("Landroid/content/Context;", "getDrawable", "I") }
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[load + 1].opcode)
        val hook = code[load + 2]
        assertTrue("right after the load's answer", hook.calls(SAVED_FILTER))
        hook as FiveRegisterInstruction
        assertEquals("the drawable", 1, hook.registerC)
        assertEquals("the tab", 8, hook.registerD)
        assertEquals("the id", 0, hook.registerE)
        assertEquals("its answer replaces the drawable", 1, (code[load + 3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[load + 3].opcode)
        assertEquals("then the marker", 0x6d169e13, (code[load + 4] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("one hook", 1, code.count { it.calls(SAVED_FILTER) })
    }

    /**
     * A drawable that goes anywhere but a tab's background, a tab maker that isn't Saved's (no
     * fixed_tabbar_button) and a background call on another drawable get no hook, and with a tab's
     * background but no hook in the extension the patch refuses.
     */
    @Test
    fun onlyATabsOwnBackgroundIsHanded() {
        for (other in listOf(tabs(given = false), tabs(layout = 0x7f0e0001), tabs(otherDrawable = true))) {
            val context = PatchContexts.of(listOf(other, extension()))
            context.tintSavedFilters()
            assertTrue(context.mutableClassDefBy(TABS).methods.none { method -> method.code().any { it.calls(SAVED_FILTER) } })
        }
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(tabs(), extension(filter = false))).tintSavedFilters() }
        assertTrue(failure.message, failure.message!!.contains("no public static $SAVED_FILTER"))
    }

    /**
     * Every build: Saved's tab maker hands its background over, each hook sitting between a
     * getDrawable's answer and the background call giving that drawable to the tab, with the call's
     * own registers, Instagram's code otherwise as it was.
     */
    @Test
    fun everyBuildHandsItsFiltersToTheExtension() {
        for ((code, bundle) in builds()) {
            val makers = makersIn(bundle)
            assertTrue("$code: something loads fixed_tabbar_button", makers.isNotEmpty())
            val context = PatchContexts.of(makers + extension())
            val stock = makers.associate { maker -> maker.type to maker.methods.associate { it.name + it.parameterTypes.joinToString("") to it.code() } }
            context.tintSavedFilters()
            var hooks = 0
            for (maker in makers) {
                for (method in context.mutableClassDefBy(maker.type).methods) {
                    val after = method.code()
                    val before = stock.getValue(maker.type).getValue(method.name + method.parameterTypes.joinToString(""))
                    val added = after.indices.filter { after[it].calls(SAVED_FILTER) }
                    // Payload alignment nops come and go as code moves, so they aren't counted.
                    assertEquals(
                        "$code: ${method.name} only the hooks added",
                        before.count { it.opcode != Opcode.NOP } + 2 * added.size,
                        after.count { it.opcode != Opcode.NOP },
                    )
                    for (at in added) {
                        val load = after[at - 2] as FiveRegisterInstruction
                        assertTrue("$code: ${method.name}@$at after a getDrawable", (load as Instruction).callsMethod("Landroid/content/Context;", "getDrawable", "I"))
                        val drawable = (after[at - 1] as OneRegisterInstruction).registerA
                        val hook = after[at] as FiveRegisterInstruction
                        assertEquals("$code: the drawable", drawable, hook.registerC)
                        assertEquals("$code: the id", load.registerD, hook.registerE)
                        assertEquals("$code: the answer back", drawable, (after[at + 1] as OneRegisterInstruction).registerA)
                        val given = (at + 2 until at + 6).first { after[it].isBackgroundCall() }
                        val call = after[given] as FiveRegisterInstruction
                        assertEquals("$code: given to the tab", drawable, call.registerC)
                        assertEquals("$code: the tab", call.registerD, hook.registerD)
                    }
                    hooks += added.size
                }
            }
            assertEquals("$code: Saved's tabs handed over", 1, hooks)
        }
    }

    /** Every class of [bundle] with a method loading [SAVED_TAB_BUTTON], as the patch's index finds them. */
    private fun makersIn(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { method ->
                    method.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == SAVED_TAB_BUTTON }
                }
                if (loads) found += ImmutableClassDef.of(classDef)
            }
        }
        return found.distinctBy { it.type }
    }

    private fun builds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        return (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
    }

    private companion object {
        const val VIEW = "Landroid/view/View;"
        const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
        const val TABS = "Lfixture/SavedTabs;"
        const val MARKER = "Lfixture/Views;"

        /**
         * The tab maker: inflates [layout], loads the background by its id and, when [given], gives
         * it to the tab in v8 with a marker ([otherDrawable] gives another drawable instead).
         */
        fun tabs(layout: Int = SAVED_TAB_BUTTON, given: Boolean = true, otherDrawable: Boolean = false): ClassDef {
            val give = if (given) {
                "invoke-static { ${if (otherDrawable) "v2" else "v1"}, v8, v0 }, $MARKER->A03(${DRAWABLE}${VIEW}I)V"
            } else {
                "invoke-virtual { v8, v1 }, $VIEW->setForeground($DRAWABLE)V"
            }
            val body = """
                const v0, $layout
                move-object v8, p1
                move-object v12, p2
                const/4 v2, 0x0
                const v0, 0x7f081a36
                invoke-virtual { v12, v0 }, Landroid/content/Context;->getDrawable(I)$DRAWABLE
                move-result-object v1
                const v0, 0x6d169e13
                $give
                invoke-virtual { v8 }, $VIEW->requestLayout()V
                return-void
            """
            return classOf(TABS, method(TABS, "A01", listOf(VIEW, "Landroid/content/Context;"), 13, body, static = false))
        }

        fun extension(filter: Boolean = true): ClassDef = classOf(
            ACCENT_THEME,
            *listOfNotNull(
                method(ACCENT_THEME, "apply", listOf("Landroid/content/Context;"), 0, "return-void", static = true),
                if (filter) method(ACCENT_THEME, "savedFilter", listOf(DRAWABLE, VIEW, "I"), 0, "return-object p0", static = true, returns = DRAWABLE) else null,
            ).toTypedArray(),
        )

        fun method(type: String, name: String, parameters: List<String>, locals: Int, body: String, static: Boolean, returns: String = "V"): Method {
            val registers = locals + parameters.size + if (static) 0 else 1
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent().trim()) },
            )
        }

        fun classOf(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList(),
        )
    }
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.calls(hook: String): Boolean = called()?.let {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
} == true

private fun Instruction.callsMethod(owner: String, name: String, vararg parameters: String): Boolean = called()?.let {
    it.definingClass == owner && it.name == name && it.parameterTypes.map(Any::toString) == parameters.toList()
} == true

private fun Instruction.isBackgroundCall(): Boolean = opcode == Opcode.INVOKE_STATIC && called()?.let {
    it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Landroid/graphics/drawable/Drawable;", "Landroid/view/View;", "I")
} == true
