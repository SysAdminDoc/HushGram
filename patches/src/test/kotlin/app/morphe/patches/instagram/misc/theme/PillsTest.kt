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

/** Saved's filters hand each pill and the background it was just given to the extension (#187). */
class PillsTest {
    /**
     * The pill strip as 457's `LX/0WI9;->A0g` has it: the outlined pill, swapped for the selected one
     * when the filter is the one you're on, given with a marker through a static (View, int, int)
     * call. The hook goes right after that call with the pill's and the background's registers, and
     * every instruction of Instagram's stays where it was.
     */
    @Test
    fun aPillsBackgroundGoesThroughTheExtension() {
        val context = PatchContexts.of(listOf(strip(), extension()))
        context.tintPills()
        val code = context.mutableClassDefBy(STRIP).methods.single { it.name == "A0g" }.code()
        val call = code.indexOfFirst { it.callsMethod(MARKER, "A0U", VIEW, "I", "I") }
        val hook = code[call + 1]
        assertTrue("right after the call", hook.calls(PILLS))
        assertEquals("the pill's register", 9, (hook as FiveRegisterInstruction).registerC)
        assertEquals("the background's register", 2, hook.registerD)
        assertEquals("one hook", 1, code.count { it.calls(PILLS) })
        assertEquals(Opcode.INVOKE_VIRTUAL, code[call + 2].opcode)
    }

    /**
     * A background call loading another id and one loading a pill id into another register get no
     * hook, and with a pill call but no hook in the extension the patch refuses.
     */
    @Test
    fun onlyAPillsOwnBackgroundIsHanded() {
        val other = PatchContexts.of(listOf(strip(id = 0x7f080001), extension()))
        other.tintPills()
        assertTrue("another background", other.mutableClassDefBy(STRIP).methods.none { method -> method.code().any { it.calls(PILLS) } })
        val elsewhere = PatchContexts.of(listOf(strip(loadInto = 3), extension()))
        elsewhere.tintPills()
        assertTrue("the id in another register", elsewhere.mutableClassDefBy(STRIP).methods.none { method -> method.code().any { it.calls(PILLS) } })
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(strip(), extension(pills = false))).tintPills() }
        assertTrue(failure.message, failure.message!!.contains("no public static $PILLS"))
    }

    /**
     * Every build: the strip gives each pill one of the two backgrounds, and each such call is followed
     * by the hook with the call's own pill and background registers, Instagram's code otherwise as it was.
     */
    @Test
    fun everyBuildHandsItsPillsToTheExtension() {
        for ((code, bundle) in builds()) {
            val strips = stripsIn(bundle)
            assertTrue("$code: something loads the selected pill", strips.isNotEmpty())
            val context = PatchContexts.of(strips + extension())
            val stock = strips.associate { strip -> strip.type to strip.methods.associate { it.name + it.parameterTypes.joinToString("") to it.code() } }
            context.tintPills()
            var hooks = 0
            for (strip in strips) {
                for (method in context.mutableClassDefBy(strip.type).methods) {
                    val after = method.code()
                    val before = stock.getValue(strip.type).getValue(method.name + method.parameterTypes.joinToString(""))
                    val added = after.indices.filter { after[it].calls(PILLS) }
                    // Payload alignment nops come and go as code moves, so they aren't counted.
                    assertEquals(
                        "$code: ${method.name} only the hooks added",
                        before.count { it.opcode != Opcode.NOP } + added.size,
                        after.count { it.opcode != Opcode.NOP },
                    )
                    for (at in added) {
                        val call = after[at - 1] as FiveRegisterInstruction
                        assertTrue("$code: ${method.name}@$at after a background call", (call as Instruction).isBackgroundCall())
                        val hook = after[at] as FiveRegisterInstruction
                        assertEquals("$code: the pill", call.registerC, hook.registerC)
                        assertEquals("$code: the background", call.registerD, hook.registerD)
                    }
                    hooks += added.size
                }
            }
            assertTrue("$code: Saved's pills handed over, $hooks", hooks >= 1)
        }
    }

    /** Every class of [bundle] with a method loading [PILL_SELECTED], as the patch's index finds them. */
    private fun stripsIn(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { method ->
                    method.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == PILL_SELECTED }
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
        const val STRIP = "Lfixture/PillStrip;"
        const val MARKER = "Lfixture/Views;"

        /**
         * The strip: [id] in [loadInto], the selected pill over it when the filter is picked, and the
         * background call on the pill in [view] with that and a marker.
         */
        fun strip(id: Int = PILL_OUTLINED, loadInto: Int = 2): ClassDef {
            val body = """
                move-object v9, p1
                const v$loadInto, $id
                if-eqz p2, :plain
                const v$loadInto, ${if (id == PILL_OUTLINED) PILL_SELECTED else id}
                :plain
                const v1, 0x59e7aff7
                invoke-static { v9, v2, v1 }, $MARKER->A0U(${VIEW}II)V
                invoke-virtual { p1 }, $VIEW->requestLayout()V
                return-void
            """
            return classOf(STRIP, method(STRIP, "A0g", listOf(VIEW, "I"), 10, body, static = false))
        }

        fun extension(pills: Boolean = true): ClassDef = classOf(
            ACCENT_THEME,
            *listOfNotNull(
                method(ACCENT_THEME, "apply", listOf("Landroid/content/Context;"), 0, "return-void", static = true),
                if (pills) method(ACCENT_THEME, "pills", listOf(VIEW, "I"), 0, "return-void", static = true) else null,
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
    it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Landroid/view/View;", "I", "I")
} == true
