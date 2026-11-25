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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Icons Instagram tints with its text and icon color rather than the icon color the icon style points
 * go through the extension, which answers the theme's icon color with Icons in the accent color on: the
 * tab icon view's tint, the badged icons on a profile's top bar and the line under a selected tab (#187).
 */
class TextIconsTest {
    /**
     * The tab icon view hands itself to the extension before the return of the method laying its tint,
     * the branch skipping the tint landing on the hook too. Its other methods keep Instagram's code. No
     * such method, or two, is left as it is, and an extension without the hook refuses.
     */
    @Test
    fun theTabIconViewHandsItselfOnAsItLaysItsTint() {
        val context = PatchContexts.of(listOf(tabIconView(), ExtensionDex.classDef(ACCENT_THEME)))
        context.tintColorFilterIcons()
        val methods = context.mutableClassDefBy(TAB_ICON_VIEW).methods.associateBy { it.name }

        val code = methods.getValue("A01").code()
        val hook = code.indices.single { code[it].calls(TEXT_ICONS) }
        assertEquals("the range form", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("the view", 2, (code[hook] as RegisterRangeInstruction).startRegister)
        assertEquals("the view alone", 1, (code[hook] as RegisterRangeInstruction).registerCount)
        assertEquals("right before the return", Opcode.RETURN_VOID, code[hook + 1].opcode)
        val skip = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the skip lands on the hook", hook, skip.target.location.index)
        assertEquals("Instagram's code kept", tabIconView().methods.single { it.name == "A01" }.code().map { it.opcode },
            code.filterIndexed { at, _ -> at != hook }.map { it.opcode })
        assertTrue("the color setter kept", methods.getValue("setNormalColor").code().none { it.calls(TEXT_ICONS) })

        for ((case, view) in listOf("no setter" to tabIconView(setters = 0), "two setters" to tabIconView(setters = 2))) {
            val untouched = PatchContexts.of(listOf(view, ExtensionDex.classDef(ACCENT_THEME)))
            assertNull("$case: not found", untouched.findColorFilterTint())
            untouched.tintColorFilterIcons()
            assertTrue("$case: no hook", untouched.mutableClassDefBy(TAB_ICON_VIEW).methods.none { method -> method.code().any { it.calls(TEXT_ICONS) } })
        }
        refuses { PatchContexts.of(listOf(tabIconView(), noHooks())).tintColorFilterIcons() }
    }

    /**
     * The drawable the top bar makes for a button's badged icon hands the color its (Context, int)
     * constructor reads to the extension right after the read, with that Context, and takes the answer.
     * A bar making no drawable or two, a constructor reading two colors or one jumping to right after
     * its read is left as it is, and an extension without the hook refuses.
     */
    @Test
    fun theTopBarsBadgedIconsHandTheirColorOn() {
        val context = PatchContexts.of(listOf(actionBar(), badged(), ExtensionDex.classDef(ACCENT_THEME)))
        context.tintBadgedIcons()
        val code = context.mutableClassDefBy(BADGED).methods.single { it.name == "<init>" }.code()
        val hook = code.indices.single { code[it].calls(TEXT_ICON) }
        assertEquals("right after the read", Opcode.MOVE_RESULT, code[hook - 1].opcode)
        assertTrue("of Context.getColor", code[hook - 2].callsMethod(CONTEXT, "getColor", "I"))
        val call = code[hook] as FiveRegisterInstruction
        assertEquals("the Context", 2, call.registerC)
        assertEquals("the color", 0, call.registerD)
        assertEquals("the answer taken", Opcode.MOVE_RESULT, code[hook + 1].opcode)
        assertEquals("into the color", 0, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("Instagram's code kept", badged().methods.single().code().map { it.opcode },
            code.filterIndexed { at, _ -> at != hook && at != hook + 1 }.map { it.opcode })

        for ((case, classes) in listOf(
            "no bar" to listOf(badged()),
            "two drawables" to listOf(actionBar(BADGED, OTHER_BADGED), badged(), badged(OTHER_BADGED)),
            "two colors" to listOf(actionBar(), badged(reads = 2)),
            "a jump past the read" to listOf(actionBar(), badged(jumps = true)),
        )) {
            val untouched = PatchContexts.of(classes + ExtensionDex.classDef(ACCENT_THEME))
            assertNull("$case: not found", untouched.findBadgedIconColor())
        }
        refuses { PatchContexts.of(listOf(actionBar(), badged(), noHooks())).tintBadgedIcons() }
    }

    /**
     * The strip's draw hands the color it reads for the line under the selected tab to the extension
     * right after the read, with the TabLayout it read it from, before checking it and tinting the line.
     * A draw reading it without a tint, another int of the layout and a draw jumping to right after the
     * read are left alone, and an extension without the hook refuses.
     */
    @Test
    fun theSelectedTabsLineHandsItsColorOn() {
        val context = PatchContexts.of(listOf(tabLayout(), strip(), reader(), ExtensionDex.classDef(ACCENT_THEME)))
        context.tintTabIndicator()
        val code = context.mutableClassDefBy(STRIP).methods.single { it.name == "draw" }.code()
        val hook = code.indices.single { code[it].calls(TAB_INDICATOR) }
        val read = code[hook - 1]
        assertEquals("right after the read", Opcode.IGET, read.opcode)
        assertEquals("of the indicator color", "A0C", ((read as ReferenceInstruction).reference as FieldReference).name)
        val call = code[hook] as FiveRegisterInstruction
        assertEquals("the layout", 0, call.registerC)
        assertEquals("the color", 2, call.registerD)
        assertEquals("the answer taken", 2, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("then the check for none", Opcode.IF_EQZ, code[hook + 2].opcode)
        assertTrue("then the tint", code[hook + 3].callsMethod(DRAWABLE, "setTint", "I"))
        assertEquals("Instagram's code kept", strip().methods.single().code().map { it.opcode },
            code.filterIndexed { at, _ -> at != hook && at != hook + 1 }.map { it.opcode })
        assertTrue("a draw without the tint kept", context.mutableClassDefBy(READER).methods.none { method -> method.code().any { it.calls(TAB_INDICATOR) } })

        assertNull("another int stored", PatchContexts.of(listOf(tabLayout(field = "A0D"), strip(), ExtensionDex.classDef(ACCENT_THEME))).findTabIndicatorRead())
        assertNull("no tinted read", PatchContexts.of(listOf(tabLayout(), reader(), ExtensionDex.classDef(ACCENT_THEME))).findTabIndicatorRead())
        assertNull("a jump past the read", PatchContexts.of(listOf(tabLayout(), strip(jumps = true), ExtensionDex.classDef(ACCENT_THEME))).findTabIndicatorRead())
        refuses { PatchContexts.of(listOf(tabLayout(), strip(), noHooks())).tintTabIndicator() }
    }

    /**
     * Every build: the tab icon view hands itself on before each return of the one method laying its
     * tint, no branch left aiming past the hook; the top bar's one badged drawable hands on the color its
     * (Context, int) constructor reads from the theme; and one strip's draw hands on the indicator color
     * TabLayout.setSelectedTabIndicatorColor keeps, right before tinting the line with it. All with the
     * real extension and Instagram's code otherwise kept.
     */
    @Test
    fun everyBuildHandsItsTextIconsToTheExtension() {
        for ((build, bundle) in builds()) {
            val named = FixtureDex.classes(bundle, setOf(TAB_ICON_VIEW, IGDS_ACTION_BAR, TAB_LAYOUT))
            assertEquals("$build: the named classes", setOf(TAB_ICON_VIEW, IGDS_ACTION_BAR, TAB_LAYOUT), named.keys)
            val made = named.getValue(IGDS_ACTION_BAR).methods.flatMap { it.code() }.filter { it.opcode == Opcode.NEW_INSTANCE }
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }.toSet()
            val drawables = FixtureDex.classes(bundle, made).values.filter { it.superclass == DRAWABLE }
            assertEquals("$build: the bar's badged drawable, ${drawables.map { it.type }}", 1, drawables.size)
            val draws = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == TAB_LAYOUT } }) { method ->
                method.name == "draw" && method.parameterTypes.map(Any::toString) == listOf(CANVAS) && method.code().any { it.readsLayoutInt() }
            }
            val strips = FixtureDex.classes(bundle, draws.map { it.definingClass }.toSet() - named.keys)
            val stock = (named.values + drawables + strips.values).associateBy { it.type }
            val context = PatchContexts.of(stock.values + ExtensionDex.classDef(ACCENT_THEME))

            context.tintTextIcons()

            val setters = context.mutableClassDefBy(TAB_ICON_VIEW).methods.filter { method -> method.code().any { it.calls(TEXT_ICONS) } }
            assertEquals("$build: one tint setter, ${setters.map { it.name }}", 1, setters.size)
            val setter = setters.single()
            assertTrue("$build: it lays the tint", setter.code().any { it.callsMethod(IMAGE, "setImageTintList", COLORS) })
            assertEquals("$build: an instance ()V", Triple(false, emptyList<String>(), "V"),
                Triple(AccessFlags.STATIC.isSet(setter.accessFlags), setter.parameterTypes.map(Any::toString), setter.returnType))
            val tint = setter.code()
            val hooks = tint.indices.filter { tint[it].calls(TEXT_ICONS) }
            assertEquals("$build: one for each return", tint.count { it.opcode == Opcode.RETURN_VOID }, hooks.size)
            for (at in hooks) {
                assertEquals("$build: the view", setter.implementation!!.registerCount - 1, (tint[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$build: right before a return", Opcode.RETURN_VOID, tint[at + 1].opcode)
            }
            val aims = tint.mapNotNull { (it as? BuilderOffsetInstruction)?.target?.location?.index }
            assertTrue("$build: no branch past a hook to its return", aims.none { tint[it].opcode == Opcode.RETURN_VOID })
            assertKept("$build: ${setter.name}", stock.getValue(TAB_ICON_VIEW).methods.single { it.name == setter.name && it.parameterTypes.isEmpty() }.code(),
                tint.filterIndexed { at, _ -> at !in hooks })

            val icons = drawables.single()
            val constructor = context.mutableClassDefBy(icons.type).methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(CONTEXT, "I") }
            val icon = constructor.code()
            val read = icon.indices.single { icon[it].calls(TEXT_ICON) }
            assertTrue("$build: right after Context.getColor", icon[read - 2].callsMethod(CONTEXT, "getColor", "I"))
            assertEquals("$build: and its answer", Opcode.MOVE_RESULT, icon[read - 1].opcode)
            val color = (icon[read - 1] as OneRegisterInstruction).registerA
            assertEquals("$build: the Context it was read from", (icon[read - 2] as FiveRegisterInstruction).registerC, (icon[read] as FiveRegisterInstruction).registerC)
            assertEquals("$build: the color", color, (icon[read] as FiveRegisterInstruction).registerD)
            assertEquals("$build: the answer taken", color, (icon[read + 1] as OneRegisterInstruction).registerA)
            assertEquals("$build: as a move-result", Opcode.MOVE_RESULT, icon[read + 1].opcode)
            assertKept("$build: ${icons.type} constructor", icons.methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(CONTEXT, "I") }.code(),
                icon.filterIndexed { at, _ -> at != read && at != read + 1 })

            val indicator = named.getValue(TAB_LAYOUT).methods.single { it.name == "setSelectedTabIndicatorColor" }.code()
                .filter { it.opcode == Opcode.IPUT }.map { ((it as ReferenceInstruction).reference as FieldReference).name }.distinct().single()
            val hooked = strips.keys.flatMap { type -> context.mutableClassDefBy(type).methods.filter { method -> method.code().any { it.calls(TAB_INDICATOR) } } }
            assertEquals("$build: one strip's draw, ${hooked.map { it.definingClass }}", 1, hooked.size)
            val draw = hooked.single().code()
            val line = draw.indices.single { draw[it].calls(TAB_INDICATOR) }
            val field = draw[line - 1]
            assertEquals("$build: right after the read", Opcode.IGET, field.opcode)
            assertEquals("$build: of the indicator color", "$TAB_LAYOUT->$indicator", ((field as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}" })
            val call = draw[line] as FiveRegisterInstruction
            assertEquals("$build: the layout", (field as TwoRegisterInstruction).registerB, call.registerC)
            assertEquals("$build: the color", field.registerA, call.registerD)
            assertEquals("$build: the answer taken", field.registerA, (draw[line + 1] as OneRegisterInstruction).registerA)
            val tinted = (line + 2..minOf(line + 4, draw.lastIndex)).any { draw[it].callsMethod(DRAWABLE, "setTint", "I") && (draw[it] as FiveRegisterInstruction).registerD == field.registerA }
            assertTrue("$build: then the line tinted with it", tinted)
            assertKept("$build: ${hooked.single().definingClass} draw", stock.getValue(hooked.single().definingClass).methods.single { it.name == "draw" && it.parameterTypes.map(Any::toString) == listOf(CANVAS) }.code(),
                draw.filterIndexed { at, _ -> at != line && at != line + 1 })
        }
    }

    private fun assertKept(what: String, stock: List<Instruction>, patched: List<Instruction>) {
        assertEquals("$what: Instagram's code kept", stock.map { it.kind() }, patched.map { it.kind() })
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
        const val CONTEXT = "Landroid/content/Context;"
        const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
        const val CANVAS = "Landroid/graphics/Canvas;"
        const val IMAGE = "Landroid/widget/ImageView;"
        const val COLORS = "Landroid/content/res/ColorStateList;"
        const val BADGED = "Lfixture/BadgedIcon;"
        const val OTHER_BADGED = "Lfixture/OtherIcon;"
        const val STRIP = "Lfixture/TabStrip;"
        const val READER = "Lfixture/TabReader;"

        /**
         * The tab icon view as 457's has it: A01 laying the tint it keeps on itself unless it has none,
         * and setNormalColor keeping a color and calling it. [setters] copies of A01 under other names.
         */
        fun tabIconView(setters: Int = 1): ClassDef = classOf(
            TAB_ICON_VIEW,
            *(List(setters) { at -> method(TAB_ICON_VIEW, if (at == 0) "A01" else "A1$at", emptyList(), 2, """
                iget-object v0, p0, $TAB_ICON_VIEW->A05:$COLORS
                if-eqz v0, :done
                invoke-virtual { p0, v0 }, $IMAGE->setImageTintList($COLORS)V
                :done
                return-void
            """, static = false) } + method(TAB_ICON_VIEW, "setNormalColor", listOf("I"), 0, """
                iput p1, p0, $TAB_ICON_VIEW->A01:I
                return-void
            """, static = false)).toTypedArray(),
            superclass = IMAGE,
        )

        /** The top bar making each of [icons] for a button, and a plain image view. */
        fun actionBar(vararg icons: String = arrayOf(BADGED)): ClassDef = classOf(
            IGDS_ACTION_BAR,
            method(IGDS_ACTION_BAR, "A00", listOf(CONTEXT), 2, (icons.map { icon ->
                """
                new-instance v0, $icon
                const v1, 0x7f080001
                invoke-direct { v0, p1, v1 }, $icon-><init>(${CONTEXT}I)V
                """.trimIndent()
            } + """
                new-instance v0, $IMAGE
                invoke-direct { v0, p1 }, $IMAGE-><init>($CONTEXT)V
                return-void
            """.trimIndent()).joinToString("\n"), static = false),
        )

        /**
         * The badged icon as 457's `LX/00c8` has it, reading the theme's text color [reads] times, with a
         * branch past the read to right after it when [jumps].
         */
        fun badged(type: String = BADGED, reads: Int = 1, jumps: Boolean = false): ClassDef = classOf(
            type,
            method(type, "<init>", listOf(CONTEXT, "I"), 1, "invoke-direct { p0 }, $DRAWABLE-><init>()V\n" + List(reads) {
                """
                const v0, 0x7f0408ce
                ${if (jumps) "if-eqz p2, :after" else "nop"}
                invoke-virtual { p1, v0 }, $CONTEXT->getColor(I)I
                move-result v0
                """.trimIndent()
            }.joinToString("\n") + "\n:after\nreturn-void", static = false),
            superclass = DRAWABLE,
        )

        /** TabLayout keeping the color it's told for the line under its selected tab in [field]. */
        fun tabLayout(field: String = "A0C"): ClassDef = classOf(
            TAB_LAYOUT,
            method(TAB_LAYOUT, "setSelectedTabIndicatorColor", listOf("I"), 0, """
                iput p1, p0, $TAB_LAYOUT->$field:I
                return-void
            """, static = false),
            superclass = "Landroid/widget/HorizontalScrollView;",
        )

        /**
         * The strip drawing the line, as 457's `LX/0Ed0;->draw` does: the layout's color read, then the
         * line tinted with it unless it's 0. With [jumps], a branch past the read lands right after it.
         */
        fun strip(jumps: Boolean = false): ClassDef = classOf(
            STRIP,
            method(STRIP, "draw", listOf(CANVAS), 3, """
                iget-object v0, p0, $STRIP->A01:$TAB_LAYOUT
                iget-object v1, v0, $TAB_LAYOUT->A0K:$DRAWABLE
                const/4 v2, 0x0
                ${if (jumps) "if-eqz v1, :check" else "nop"}
                iget v2, v0, $TAB_LAYOUT->A0C:I
                :check
                if-eqz v2, :plain
                invoke-virtual { v1, v2 }, $DRAWABLE->setTint(I)V
                :plain
                invoke-virtual { v1, p1 }, $DRAWABLE->draw($CANVAS)V
                return-void
            """, static = false),
            superclass = "Landroid/widget/LinearLayout;",
        )

        /** A draw reading the color without tinting anything with it. */
        fun reader(): ClassDef = classOf(
            READER,
            method(READER, "draw", listOf(CANVAS), 2, """
                iget-object v0, p0, $READER->A00:$TAB_LAYOUT
                iget v1, v0, $TAB_LAYOUT->A0C:I
                invoke-virtual { p1, v1 }, $CANVAS->drawColor(I)V
                return-void
            """, static = false),
            superclass = "Landroid/view/View;",
        )

        /** AccentTheme without the hooks for icons in the text color. */
        fun noHooks(): ClassDef = classOf(ACCENT_THEME, method(ACCENT_THEME, "apply", listOf(CONTEXT), 0, "return-void", static = true))

        fun method(type: String, name: String, parameters: List<String>, locals: Int, body: String, static: Boolean, returns: String = "V"): Method {
            val registers = locals + parameters.size + if (static) 0 else 1
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0) or
                            (if (name == "<init>") AccessFlags.CONSTRUCTOR.value else 0), null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent().trim()) },
            )
        }

        fun classOf(type: String, vararg methods: Method, superclass: String = "Ljava/lang/Object;"): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.toList(),
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

private fun Instruction.readsLayoutInt(): Boolean = opcode == Opcode.IGET &&
    ((this as ReferenceInstruction).reference as FieldReference).let { it.definingClass == TAB_LAYOUT && it.type == "I" }

private fun Instruction.kind(): Opcode = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
