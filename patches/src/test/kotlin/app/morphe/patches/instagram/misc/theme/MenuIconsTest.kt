/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

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
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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
 * The icons of Instagram's menu rows, a reel's three-dot menu among them, go through the extension,
 * which gives them the theme's icon color in place of Instagram's text color with Icons in the accent
 * color on (#187): each ImageView.setColorFilter(int) in the method laying the rows out becomes the
 * extension's, with the same registers.
 */
class MenuIconsTest {
    /**
     * Both forms of the rows' binder's ImageView.setColorFilter(int) become the extension's with the
     * same registers, the branch onto the second row's code still landing there, and a setColorFilter
     * of another class and another method of the adapter are left alone. Two binders coloring icons
     * both hand them on, a binder coloring none beside one that does is left as it is, no binder or
     * none coloring an icon leaves everything, and an extension without the hook refuses.
     */
    @Test
    fun theMenuRowsHandTheirIconsColorsOn() {
        val context = PatchContexts.of(listOf(adapter(), ExtensionDex.classDef(ACCENT_THEME)))
        context.tintMenuIcons()
        val methods = context.mutableClassDefBy(ADAPTER).methods.associateBy { it.name }

        val code = methods.getValue("A0g").code()
        val hooks = code.indices.filter { code[it].calls(MENU_ICON) }
        assertEquals("both calls", 2, hooks.size)
        assertEquals("the plain form", Opcode.INVOKE_STATIC, code[hooks[0]].opcode)
        assertEquals("the icon", 1, (code[hooks[0]] as FiveRegisterInstruction).registerC)
        assertEquals("the color", 2, (code[hooks[0]] as FiveRegisterInstruction).registerD)
        assertEquals("the range form", Opcode.INVOKE_STATIC_RANGE, code[hooks[1]].opcode)
        assertEquals("from the icon", 1, (code[hooks[1]] as RegisterRangeInstruction).startRegister)
        assertEquals("to the color", 2, (code[hooks[1]] as RegisterRangeInstruction).registerCount)
        assertTrue("no ImageView call left", code.none { it.callsMethod(IMAGE, "setColorFilter", "I") })
        assertTrue("another class's kept", code.any { it.callsMethod(OTHER, "setColorFilter", "I") })
        val skip = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch still lands on the second row", MENU_SELECTOR_ROW, code[skip.target.location.index].string())
        assertKept("A0g", adapter().methods.single { it.name == "A0g" }.code(), code, hooks)
        assertTrue("the other method kept", methods.getValue("A0c").code().any { it.callsMethod(IMAGE, "setColorFilter", "I") })

        for ((case, icons) in listOf("two binders" to listOf(true, true), "one coloring no icon" to listOf(true, false))) {
            val stock = adapter(icons)
            val both = PatchContexts.of(listOf(stock, ExtensionDex.classDef(ACCENT_THEME)))
            assertEquals("$case: found", icons.count { it }, both.findMenuRowIcons().size)
            both.tintMenuIcons()
            val patched = both.mutableClassDefBy(ADAPTER).methods.associateBy { it.name }
            for ((at, colors) in icons.withIndex()) {
                val name = if (at == 0) "A0g" else "A1$at"
                val after = patched.getValue(name).code()
                assertEquals("$case: $name's calls", if (colors) 2 else 0, after.count { it.calls(MENU_ICON) })
                if (!colors) assertEquals("$case: $name kept", stock.methods.single { it.name == name }.code().map { it.kind() }, after.map { it.kind() })
            }
        }
        for ((case, classes) in listOf(
            "no binder" to emptyList<ClassDef>(),
            "no icon" to listOf(adapter(listOf(false))),
        )) {
            val untouched = PatchContexts.of(classes + ExtensionDex.classDef(ACCENT_THEME))
            assertTrue("$case: not found", untouched.findMenuRowIcons().isEmpty())
            untouched.tintMenuIcons()
        }
        refuses { PatchContexts.of(listOf(adapter(), noHooks())).tintMenuIcons() }
    }

    /**
     * Every build: the methods laying out the menu rows, each an instance (holder, int)V loading both
     * row messages, hand each icon they color to the extension in place of ImageView.setColorFilter(int),
     * with the same registers, and keep Instagram's code otherwise. 386314286 has a second such
     * adapter coloring no icon that way, which is left alone.
     */
    @Test
    fun everyBuildHandsItsMenuIconsToTheExtension() {
        for ((build, bundle) in builds()) {
            val stock = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, MENU_SWITCH_ROW))
            val byType = stock.associateBy { it.type }
            val context = PatchContexts.of(stock + ExtensionDex.classDef(ACCENT_THEME))

            context.tintMenuIcons()

            val hooked = stock.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods.filter { method -> method.code().any { it.calls(MENU_ICON) } } }
            assertTrue("$build: a binder hooked", hooked.isNotEmpty())
            for (binder in hooked) {
                val where = "$build: ${binder.definingClass}->${binder.name}"
                assertEquals("$where: an instance (holder, int)V", Triple(false, "I", "V"),
                    Triple(AccessFlags.STATIC.isSet(binder.accessFlags), binder.parameterTypes.map(Any::toString).last(), binder.returnType))
                val original = byType.getValue(binder.definingClass).methods.single {
                    it.name == binder.name && it.parameterTypes.map(Any::toString) == binder.parameterTypes.map(Any::toString)
                }
                assertTrue("$where: it loads both row messages", FixtureDex.loads(original, MENU_SWITCH_ROW, byType) && FixtureDex.loads(original, MENU_SELECTOR_ROW, byType))
                val before = original.code()
                val after = binder.code()
                val calls = before.indices.filter { before[it].callsMethod(IMAGE, "setColorFilter", "I") }
                assertTrue("$where: icons colored", calls.isNotEmpty())
                assertEquals("$where: each call the extension's", calls, after.indices.filter { after[it].calls(MENU_ICON) })
                for (at in calls) assertEquals("$where: the same registers at $at", before[at].registers(), after[at].registers())
                assertKept(where, before, after, calls)
            }
        }
    }

    /** [patched] is [stock] but at [hooks], where a virtual call became a static one of the same form. */
    private fun assertKept(what: String, stock: List<Instruction>, patched: List<Instruction>, hooks: List<Int>) {
        assertEquals("$what: Instagram's code kept", stock.map { it.kind() }, patched.map { it.kind() }.mapIndexed { at, kind ->
            if (at !in hooks) kind else if (kind == Opcode.INVOKE_STATIC_RANGE) Opcode.INVOKE_VIRTUAL_RANGE else Opcode.INVOKE_VIRTUAL
        })
    }

    private fun refuses(run: () -> Unit) {
        val failure = assertThrows(PatchException::class.java) { run() }
        assertTrue("no hook in the extension: ${failure.message}", failure.message!!.startsWith("Accent color"))
    }

    private fun builds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        assertTrue("no fixture of a declared build", declared.isNotEmpty())
        return (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
    }

    private companion object {
        const val IMAGE = "Landroid/widget/ImageView;"
        const val OTHER = "Lfixture/Tinted;"
        const val ADAPTER = "Lfixture/MenuAdapter;"
        const val HOLDER = "Lfixture/MenuHolder;"

        /**
         * The menu adapter: a method for each of [icons] laying out the rows as 386314286's
         * `LX/0TsX;->A0g` does (A0g, then A11 and on), each loading both row messages and, where its
         * entry is true, coloring a row's icon with each form of ImageView.setColorFilter(int), and A0c,
         * which loads one message and colors an icon too.
         */
        fun adapter(icons: List<Boolean> = listOf(true)): ClassDef = classOf(
            ADAPTER,
            *(List(icons.size) { at ->
                method(ADAPTER, if (at == 0) "A0g" else "A1$at", listOf(HOLDER, "I"), 3, """
                    const-string v0, "$MENU_SWITCH_ROW"
                    iget-object v1, p1, $HOLDER->A02:$IMAGE
                    const v2, -0x1
                    if-eqz p2, :selector
                    ${if (icons[at]) "invoke-virtual { v1, v2 }, $IMAGE->setColorFilter(I)V" else "nop"}
                    :selector
                    const-string v0, "$MENU_SELECTOR_ROW"
                    ${if (icons[at]) "invoke-virtual/range { v1 .. v2 }, $IMAGE->setColorFilter(I)V" else "nop"}
                    invoke-virtual { v0, v2 }, $OTHER->setColorFilter(I)V
                    return-void
                """, static = false)
            } + method(ADAPTER, "A0c", listOf(HOLDER, "I"), 2, """
                    const-string v0, "$MENU_SWITCH_ROW"
                    iget-object v1, p1, $HOLDER->A02:$IMAGE
                    invoke-virtual { v1, p2 }, $IMAGE->setColorFilter(I)V
                    return-void
                """, static = false)).toTypedArray(),
        )

        /** AccentTheme without the hook for menu icons. */
        fun noHooks(): ClassDef = classOf(ACCENT_THEME, method(ACCENT_THEME, "apply", listOf("Landroid/content/Context;"), 0, "return-void", static = true))

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

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.calls(hook: String): Boolean = called()?.let {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
} == true

private fun Instruction.callsMethod(owner: String, name: String, vararg parameters: String): Boolean = called()?.let {
    it.definingClass == owner && it.name == name && it.parameterTypes.map(Any::toString) == parameters.toList()
} == true

private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Instruction.kind(): Opcode = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
