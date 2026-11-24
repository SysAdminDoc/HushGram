/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.viewers

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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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

/**
 * Search viewers in the viewer list (#833): the bind of each row of a story's viewer list hands the
 * row's view to the extension first, which puts the Search viewers bar on the row's list.
 */
class ViewerSearchBarTest {
    /**
     * The row maker as 457's `LX/0YXO` has it: the bind's view is p2, above v15 in its 35 registers,
     * so the hook takes it with a range call before anything else, and Instagram's code stays as it was.
     */
    @Test
    fun aRowsViewGoesToTheExtensionFirstInItsBind() {
        val context = PatchContexts.of(listOf(rows(), extension()))
        context.searchViewerLists()
        val bind = context.mutableClassDefBy(ROWS).methods.single { it.name == "bindView" }
        val code = bind.code()
        assertTrue("first", code[0].calls(STORY_VIEWER_ROW))
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        val range = code[0] as RegisterRangeInstruction
        assertEquals("the row's view, p2", 32, range.startRegister)
        assertEquals(1, range.registerCount)
        assertEquals("one hook", 1, code.count { it.calls(STORY_VIEWER_ROW) })
        assertEquals(Opcode.MOVE_OBJECT_FROM16, code[1].opcode)
        assertTrue("the maker is left alone", context.mutableClassDefBy(ROWS).methods.single { it.name == "createView" }
            .code().none { it.calls(STORY_VIEWER_ROW) })
    }

    /**
     * A class loading another layout gets no hook, a maker with two binds or an extension without the
     * hook fails the patch, and a bind something jumps back to the start of keeps no hook.
     */
    @Test
    fun onlyAViewerRowsBindIsHooked() {
        val other = PatchContexts.of(listOf(rows(layout = 0x7f0e0001), extension()))
        other.searchViewerLists()
        assertTrue("another layout", other.mutableClassDefBy(ROWS).methods.none { method -> method.code().any { it.calls(STORY_VIEWER_ROW) } })

        val twice = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(rows(binds = 2), extension())).searchViewerLists() }
        assertTrue(twice.message, twice.message!!.contains("expected one bind method on $ROWS, found 2"))
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(rows(), extension(row = false))).searchViewerLists() }
        assertTrue(missing.message, missing.message!!.contains("no public static $STORY_VIEWER_ROW"))

        val loop = PatchContexts.of(listOf(rows(loops = true), extension()))
        loop.searchViewerLists()
        assertTrue("a jump back to the start", loop.mutableClassDefBy(ROWS).methods.none { method -> method.code().any { it.calls(STORY_VIEWER_ROW) } })
    }

    /**
     * Every build: each class making the viewer row has its bind hooked first with that bind's own
     * view parameter, its code otherwise as it was, and the extension's real class has the hook.
     */
    @Test
    fun everyBuildHandsItsViewerRowsToTheExtension() {
        val real = ExtensionDex.classDef(STORY_VIEWER_SEARCH)
        assertTrue("the extension's hook", real.methods.any {
            it.name == "row" && it.parameterTypes.map(Any::toString) == listOf(VIEW) && AccessFlags.STATIC.isSet(it.accessFlags)
        })
        val builds = builds()
        assertTrue("no fixture", builds.isNotEmpty())
        for ((code, bundle) in builds) {
            val makers = makersIn(bundle)
            assertTrue("$code: something makes the viewer row", makers.isNotEmpty())
            val context = PatchContexts.of(makers + real)
            val stock = makers.associate { maker -> maker.type to maker.methods.associate { it.name + it.parameterTypes.joinToString("") to it.code() } }
            context.searchViewerLists()
            var hooks = 0
            for (maker in makers) {
                for (method in context.mutableClassDefBy(maker.type).methods) {
                    val after = method.code()
                    val before = stock.getValue(maker.type).getValue(method.name + method.parameterTypes.joinToString(""))
                    val added = after.count { it.calls(STORY_VIEWER_ROW) }
                    assertEquals(
                        "$code: ${method.name} only the hook added",
                        before.count { it.opcode != Opcode.NOP } + added,
                        after.count { it.opcode != Opcode.NOP },
                    )
                    if (added == 0) continue
                    assertEquals("$code: ${method.name} binds a row", listOf("I", VIEW, ANY, ANY), method.parameterTypes.map(Any::toString))
                    assertTrue("$code: first", after[0].calls(STORY_VIEWER_ROW))
                    val view = method.implementation!!.registerCount - 5 + 2
                    assertEquals("$code: the bind's own view", view, (after[0] as RegisterRangeInstruction).startRegister)
                    hooks += added
                }
            }
            assertTrue("$code: a viewer row bind hooked, $hooks", hooks >= 1)
        }
    }

    /** Every class of [bundle] loading [VIEWER_ROW_LAYOUT], as the patch's index finds them. */
    private fun makersIn(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { method ->
                    method.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == VIEWER_ROW_LAYOUT }
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
        const val VIEW_GROUP = "Landroid/view/ViewGroup;"
        const val ANY = "Ljava/lang/Object;"
        const val ROWS = "Lfixture/ViewerRows;"

        /**
         * The row maker: [layout] made from an int and a holder, and [binds] binds of an int, a view
         * and two objects in 35 registers, the first one jumping back to its start when [loops].
         */
        fun rows(layout: Int = VIEWER_ROW_LAYOUT, binds: Int = 1, loops: Boolean = false): ClassDef {
            val make = method(ROWS, "createView", listOf("I", VIEW_GROUP), 2, """
                const v0, $layout
                const/4 v1, 0x0
                return-object v1
            """, static = false, returns = VIEW)
            val bind = (1..binds).map { n ->
                val body = if (loops && n == 1) {
                    """
                    :top
                    move-object/from16 v0, p2
                    invoke-virtual { v0 }, $VIEW->requestLayout()V
                    if-eqz p1, :top
                    return-void
                    """
                } else {
                    """
                    move-object/from16 v0, p2
                    invoke-virtual { v0 }, $VIEW->requestLayout()V
                    return-void
                    """
                }
                method(ROWS, if (n == 1) "bindView" else "bindView$n", listOf("I", VIEW, ANY, ANY), 30, body, static = false)
            }
            return classOf(ROWS, *(listOf(make) + bind).toTypedArray())
        }

        fun extension(row: Boolean = true): ClassDef = classOf(
            STORY_VIEWER_SEARCH,
            *listOfNotNull(
                method(STORY_VIEWER_SEARCH, "on", emptyList(), 1, "const/4 v0, 0x0\nreturn v0", static = true, returns = "Z"),
                if (row) method(STORY_VIEWER_SEARCH, "row", listOf(VIEW), 0, "return-void", static = true) else null,
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

private fun Instruction.calls(hook: String): Boolean = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
} == true
