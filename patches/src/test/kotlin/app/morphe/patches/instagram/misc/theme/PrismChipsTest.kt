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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
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
 * Instagram's prism chips, the filters under a search among them, go through the extension, which
 * gives them Saved's filters' look in the picked color with Filters in the accent color on (#187): the
 * fill and outline color their background reads from the theme, and the chip itself once it's built
 * or selected, for its labels.
 */
class PrismChipsTest {
    /**
     * The chip's background hands the fill and the outline color its constructor reads from the theme
     * to the extension right after each read, with the Context it read them from, and keeps the answers.
     * No chip, a chip making two drawables, a background reading two fills or one jumping to right after
     * a read is left as it is, and an extension without the hooks refuses.
     */
    @Test
    fun theChipsBackgroundHandsItsFillAndOutlineOn() {
        val context = PatchContexts.of(listOf(prismChip(), background(), ExtensionDex.classDef(ACCENT_THEME)))
        context.tintPrismBackgrounds()
        val code = context.mutableClassDefBy(BACKGROUND).methods.single { it.name == "<init>" }.code()

        val fill = code.indices.single { code[it].calls(PRISM_FILL) }
        assertTrue("right after the fill's read", code[fill - 2].callsMethod(COLORS_OF, "A00", CONTEXT, "I"))
        assertEquals("and its answer", Opcode.MOVE_RESULT_OBJECT, code[fill - 1].opcode)
        assertEquals("the Context", 3, (code[fill] as FiveRegisterInstruction).registerC)
        assertEquals("the fill", 1, (code[fill] as FiveRegisterInstruction).registerD)
        assertEquals("the answer taken", Opcode.MOVE_RESULT_OBJECT, code[fill + 1].opcode)
        assertEquals("into the fill", 1, (code[fill + 1] as OneRegisterInstruction).registerA)
        assertEquals("then kept", Opcode.IPUT_OBJECT, code[fill + 2].opcode)

        val stroke = code.indices.single { code[it].calls(PRISM_STROKE) }
        assertTrue("right after the outline's read", code[stroke - 2].callsMethod(COLORS_OF, "A0S", CONTEXT, "I"))
        assertEquals("and its answer", Opcode.MOVE_RESULT, code[stroke - 1].opcode)
        assertEquals("the Context", 3, (code[stroke] as FiveRegisterInstruction).registerC)
        assertEquals("the color", 1, (code[stroke] as FiveRegisterInstruction).registerD)
        assertEquals("the answer taken", Opcode.MOVE_RESULT, code[stroke + 1].opcode)
        assertEquals("into the color", 1, (code[stroke + 1] as OneRegisterInstruction).registerA)
        assertEquals("then kept", Opcode.IPUT, code[stroke + 2].opcode)
        assertKept("the background", background().methods.single().code(),
            code.filterIndexed { at, _ -> at !in setOf(fill, fill + 1, stroke, stroke + 1) })

        for ((case, classes) in listOf(
            "no chip" to listOf(background()),
            "two drawables" to listOf(prismChip(BACKGROUND, OTHER_BACKGROUND), background(), background(OTHER_BACKGROUND)),
            "two fills" to listOf(prismChip(), background(fills = 2)),
            "a jump past the read" to listOf(prismChip(), background(jumps = true)),
        )) {
            val untouched = PatchContexts.of(classes + ExtensionDex.classDef(ACCENT_THEME))
            assertNull("$case: not found", untouched.findPrismBackground())
            untouched.tintPrismBackgrounds()
            assertTrue("$case: no hook", classes.map { it.type }.filter { it != IGDS_PRISM_CHIP }.all { type ->
                untouched.mutableClassDefBy(type).methods.none { method -> method.code().any { it.calls(PRISM_FILL) || it.calls(PRISM_STROKE) } }
            })
        }
        refuses { PatchContexts.of(listOf(prismChip(), background(), noHooks())).tintPrismBackgrounds() }
    }

    /**
     * The chip hands itself to the extension before each return of the constructor building it and of
     * setSelected, the branch skipping the background landing on the hook too, while the constructor
     * calling the other is left alone. A chip without setSelected or no chip is left as it is, and an
     * extension without the hook refuses.
     */
    @Test
    fun theChipHandsItselfOnAsItsBuiltAndSelected() {
        val context = PatchContexts.of(listOf(prismChip(), background(), ExtensionDex.classDef(ACCENT_THEME)))
        context.tintPrismChipLabels()
        val methods = context.mutableClassDefBy(IGDS_PRISM_CHIP).methods

        val builder = methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(CONTEXT, STYLE) }.code()
        val built = builder.indices.single { builder[it].calls(PRISM_CHIP) }
        assertEquals("the range form", Opcode.INVOKE_STATIC_RANGE, builder[built].opcode)
        assertEquals("the chip", 2, (builder[built] as RegisterRangeInstruction).startRegister)
        assertEquals("the chip alone", 1, (builder[built] as RegisterRangeInstruction).registerCount)
        assertEquals("right before the return", Opcode.RETURN_VOID, builder[built + 1].opcode)
        val skip = builder.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the skip lands on the hook", built, skip.target.location.index)
        assertKept("the builder", prismChip().methods.single { it.name == "<init>" && it.parameterTypes.size == 2 }.code(),
            builder.filterIndexed { at, _ -> at != built })

        val chained = methods.single { it.name == "<init>" && it.parameterTypes.size == 1 }
        assertTrue("the constructor calling the other kept", chained.code().none { it.calls(PRISM_CHIP) })

        val setter = methods.single { it.name == "setSelected" }.code()
        val selected = setter.indices.single { setter[it].calls(PRISM_CHIP) }
        assertEquals("the chip", 0, (setter[selected] as RegisterRangeInstruction).startRegister)
        assertEquals("right before the return", Opcode.RETURN_VOID, setter[selected + 1].opcode)
        assertKept("setSelected", prismChip().methods.single { it.name == "setSelected" }.code(), setter.filterIndexed { at, _ -> at != selected })

        for ((case, classes) in listOf("no setSelected" to listOf(prismChip(selectable = false)), "no chip" to listOf(background()))) {
            val untouched = PatchContexts.of(classes + ExtensionDex.classDef(ACCENT_THEME))
            assertTrue("$case: none", untouched.findPrismChipLabels().isEmpty())
        }
        refuses { PatchContexts.of(listOf(prismChip(), noHooks())).tintPrismChipLabels() }
    }

    /**
     * Every build: the one drawable of its own the chip makes hands on the one fill and the one outline
     * color its Context constructor reads from the theme and keeps, and the chip hands itself on before
     * each return of the constructors building it and of setSelected, no branch left aiming past a hook
     * to its return. All with the real extension and Instagram's code otherwise kept.
     */
    @Test
    fun everyBuildHandsItsPrismChipsToTheExtension() {
        for ((build, bundle) in builds()) {
            val named = FixtureDex.classes(bundle, setOf(IGDS_PRISM_CHIP))
            assertEquals("$build: the chip", setOf(IGDS_PRISM_CHIP), named.keys)
            val chip = named.getValue(IGDS_PRISM_CHIP)
            val made = chip.methods.flatMap { it.code() }.filter { it.opcode == Opcode.NEW_INSTANCE }
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }.toSet()
            val drawables = FixtureDex.classes(bundle, made).values.filter { it.superclass == DRAWABLE }
            assertEquals("$build: the chip's drawable, ${drawables.map { it.type }}", 1, drawables.size)
            val drawable = drawables.single()
            val context = PatchContexts.of(listOf(chip, drawable, ExtensionDex.classDef(ACCENT_THEME)))

            context.tintPrismChips()

            val constructor = context.mutableClassDefBy(drawable.type).methods.single { it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == CONTEXT }
            val code = constructor.code()
            val hooks = mutableListOf<Int>()
            for ((hook, move, store) in listOf(
                Triple(PRISM_FILL, Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT),
                Triple(PRISM_STROKE, Opcode.MOVE_RESULT, Opcode.IPUT),
            )) {
                val at = code.indices.single { code[it].calls(hook) }
                val read = code[at - 2]
                assertEquals("$build: $hook right after a static read", Opcode.INVOKE_STATIC, read.opcode)
                assertEquals("$build: of a Context and an int", listOf(CONTEXT, "I"), read.called()!!.parameterTypes.map(Any::toString))
                assertEquals("$build: and its answer", move, code[at - 1].opcode)
                val color = (code[at - 1] as OneRegisterInstruction).registerA
                assertEquals("$build: the Context it was read from", (read as FiveRegisterInstruction).registerC, (code[at] as FiveRegisterInstruction).registerC)
                assertEquals("$build: the color", color, (code[at] as FiveRegisterInstruction).registerD)
                assertEquals("$build: the answer taken", move, code[at + 1].opcode)
                assertEquals("$build: into the color", color, (code[at + 1] as OneRegisterInstruction).registerA)
                val kept = code[at + 2]
                assertEquals("$build: then kept", store, kept.opcode)
                assertEquals("$build: in the drawable", drawable.type, ((kept as ReferenceInstruction).reference as FieldReference).definingClass)
                assertEquals("$build: the answer", color, (kept as TwoRegisterInstruction).registerA)
                hooks += listOf(at, at + 1)
            }
            assertKept("$build: ${drawable.type} constructor", drawable.methods.single { it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == CONTEXT }.code(),
                code.filterIndexed { at, _ -> at !in hooks })

            val hooked = context.mutableClassDefBy(IGDS_PRISM_CHIP).methods.filter { method -> method.code().any { it.calls(PRISM_CHIP) } }
            assertTrue("$build: a constructor, ${hooked.map { it.name }}", hooked.any { it.name == "<init>" })
            assertTrue("$build: setSelected, ${hooked.map { it.name }}", hooked.any { it.name == "setSelected" })
            for (method in hooked) {
                val body = method.code()
                val calls = body.indices.filter { body[it].calls(PRISM_CHIP) }
                val where = "$build: ${method.name}(${method.parameterTypes.joinToString("")})"
                assertEquals("$where: one for each return", body.count { it.opcode == Opcode.RETURN_VOID }, calls.size)
                val self = method.implementation!!.registerCount - method.parameterTypes.size - 1
                for (at in calls) {
                    assertEquals("$where: the chip", self, (body[at] as RegisterRangeInstruction).startRegister)
                    assertEquals("$where: right before a return", Opcode.RETURN_VOID, body[at + 1].opcode)
                }
                val aims = body.mapNotNull { (it as? BuilderOffsetInstruction)?.target?.location?.index }
                assertTrue("$where: no branch past a hook to its return", aims.none { body[it].opcode == Opcode.RETURN_VOID })
                val stock = chip.methods.single { it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) }
                assertKept(where, stock.code(), body.filterIndexed { at, _ -> at !in calls })
            }
        }
    }

    /**
     * The icon chip before Explore's chips hands its outlined view to the extension right after the
     * chip list finds it, in the register it's taken into. Two such finds, one something jumps to right
     * after, or an id handed to a call answering something else is left as it is, and an extension
     * without the hook refuses.
     */
    @Test
    fun exploresIconChipHandsItsOutlineOn() {
        val context = PatchContexts.of(listOf(chipList(), ExtensionDex.classDef(ACCENT_THEME)))
        context.tintExplorePill()
        val code = context.mutableClassDefBy(CHIP_LIST).methods.single().code()
        val hook = code.indices.single { code[it].calls(EXPLORE_PILL) }
        assertEquals("the range form", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("the view", 0, (code[hook] as RegisterRangeInstruction).startRegister)
        assertEquals("the view alone", 1, (code[hook] as RegisterRangeInstruction).registerCount)
        assertEquals("right after it's taken", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        assertTrue("from the find", code[hook - 2].callsMethod(VIEWS, "A05", VIEW, "I"))
        assertEquals("then kept", Opcode.IPUT_OBJECT, code[hook + 1].opcode)
        assertKept("the chip list", chipList().methods.single().code(), code.filterIndexed { at, _ -> at != hook })

        for ((case, list) in listOf(
            "two finds" to chipList(finds = 2),
            "a jump to right after" to chipList(jumps = true),
            "another answer" to chipList(answers = "Ljava/lang/Object;"),
        )) {
            val untouched = PatchContexts.of(listOf(list, ExtensionDex.classDef(ACCENT_THEME)))
            assertNull("$case: not found", untouched.findExplorePill())
            untouched.tintExplorePill()
            assertTrue("$case: no hook", untouched.mutableClassDefBy(CHIP_LIST).methods.none { method -> method.code().any { it.calls(EXPLORE_PILL) } })
        }
        refuses { PatchContexts.of(listOf(chipList(), noHooks())).tintExplorePill() }
    }

    /**
     * Every build: the one place loading the icon chip's id hands its outlined view on right after it's
     * found and taken, with the real extension and Instagram's code otherwise kept.
     */
    @Test
    fun everyBuildHandsExploresIconChipToTheExtension() {
        for ((build, bundle) in builds()) {
            val lists = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { method -> method.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == EXPLORE_SETTINGS_PILL } }) {
                        lists += ImmutableClassDef.of(classDef)
                    }
                }
            }
            assertTrue("$build: something loads the icon chip's id", lists.isNotEmpty())
            val context = PatchContexts.of(lists + ExtensionDex.classDef(ACCENT_THEME))

            context.tintExplorePill()

            val hooked = lists.flatMap { owner -> context.mutableClassDefBy(owner.type).methods.filter { method -> method.code().any { it.calls(EXPLORE_PILL) } } }
            assertEquals("$build: one place hands the outline on, ${hooked.map { "${it.definingClass}->${it.name}" }}", 1, hooked.size)
            val code = hooked.single().code()
            val hook = code.indices.single { code[it].calls(EXPLORE_PILL) }
            val taken = code[hook - 1]
            assertEquals("$build: right after it's taken", Opcode.MOVE_RESULT_OBJECT, taken.opcode)
            assertEquals("$build: the view", (taken as OneRegisterInstruction).registerA, (code[hook] as RegisterRangeInstruction).startRegister)
            assertEquals("$build: from a find answering a View", VIEW, code[hook - 2].called()!!.returnType)
            assertEquals("$build: handed the id", EXPLORE_SETTINGS_PILL, (code[hook - 3] as NarrowLiteralInstruction).narrowLiteral)
            val stock = lists.single { it.type == hooked.single().definingClass }.methods
                .single { it.name == hooked.single().name && it.parameterTypes.map(Any::toString) == hooked.single().parameterTypes.map(Any::toString) }
            assertKept("$build: ${hooked.single().name}", stock.code().filter { it.opcode != Opcode.NOP },
                code.filterIndexed { at, instruction -> at != hook && instruction.opcode != Opcode.NOP })
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
        const val COLORS = "Landroid/content/res/ColorStateList;"
        const val LINEAR = "Landroid/widget/LinearLayout;"
        const val BACKGROUND = "Lfixture/PrismBackground;"
        const val OTHER_BACKGROUND = "Lfixture/OtherBackground;"
        const val COLORS_OF = "Lfixture/Colors;"
        const val STYLE = "Lfixture/ChipStyle;"
        const val VIEW = "Landroid/view/View;"
        const val CHIP_LIST = "Lfixture/ChipList;"
        const val VIEWS = "Lfixture/Views;"

        /**
         * The chip: a constructor building it, making each of [backgrounds] unless it has no style, one
         * calling that one, and setSelected(boolean) unless not [selectable].
         */
        fun prismChip(vararg backgrounds: String = arrayOf(BACKGROUND), selectable: Boolean = true): ClassDef = classOf(
            IGDS_PRISM_CHIP,
            *(listOf(
                method(IGDS_PRISM_CHIP, "<init>", listOf(CONTEXT, STYLE), 2, (listOf(
                    "invoke-direct { p0, p1 }, $LINEAR-><init>($CONTEXT)V",
                    "if-eqz p2, :plain",
                    "const/4 v1, 0x0",
                ) + backgrounds.map { type ->
                    """
                    new-instance v0, $type
                    invoke-direct { v0, p1, p2, v1 }, $type-><init>($CONTEXT$STYLE$STYLE)V
                    invoke-virtual { p0, v0 }, $LINEAR->setBackground($DRAWABLE)V
                    """.trimIndent()
                } + listOf(":plain", "return-void")).joinToString("\n"), static = false),
                method(IGDS_PRISM_CHIP, "<init>", listOf(CONTEXT), 1, """
                    const/4 v0, 0x0
                    invoke-direct { p0, p1, v0 }, $IGDS_PRISM_CHIP-><init>($CONTEXT$STYLE)V
                    return-void
                """, static = false),
            ) + if (selectable) listOf(method(IGDS_PRISM_CHIP, "setSelected", listOf("Z"), 0, """
                    invoke-super { p0, p1 }, $LINEAR->setSelected(Z)V
                    return-void
                """, static = false)) else emptyList()).toTypedArray(),
            superclass = LINEAR,
        )

        /**
         * The chip's background as 386314286's `LX/0BOO` has it: its fill read [fills] times and its
         * outline color once, each from the Context and kept, with a branch past the outline's read to
         * right after it when [jumps].
         */
        fun background(type: String = BACKGROUND, fills: Int = 1, jumps: Boolean = false): ClassDef = classOf(
            type,
            method(type, "<init>", listOf(CONTEXT, STYLE, STYLE), 2, (listOf("invoke-direct { p0 }, $DRAWABLE-><init>()V") + List(fills) {
                """
                const v0, 0x7f040001
                invoke-static { p1, v0 }, $COLORS_OF->A00(${CONTEXT}I)$COLORS
                move-result-object v1
                iput-object v1, p0, $type->A0C:$COLORS
                """.trimIndent()
            } + """
                const v0, 0x7f040002
                ${if (jumps) "if-eqz p2, :kept" else "nop"}
                invoke-static { p1, v0 }, $COLORS_OF->A0S(${CONTEXT}I)I
                move-result v1
                :kept
                iput v1, p0, $type->A03:I
                return-void
            """.trimIndent()).joinToString("\n"), static = false),
            superclass = DRAWABLE,
        )

        /**
         * Explore's chip list as 386314286's `LX/0CUr;->A0c` has it: the icon chip's id handed to a
         * static find answering [answers], its answer taken and kept, [finds] times, with a branch to
         * right after the first take when [jumps].
         */
        fun chipList(finds: Int = 1, jumps: Boolean = false, answers: String = VIEW): ClassDef = classOf(
            CHIP_LIST,
            method(CHIP_LIST, "A0c", listOf("Landroid/view/ViewGroup;", "I"), 2, (listOf(if (jumps) "if-eqz p2, :kept" else "nop") + List(finds) {
                """
                const v0, $EXPLORE_SETTINGS_PILL
                invoke-static { p1, v0 }, $VIEWS->A05(${VIEW}I)$answers
                move-result-object v0
                ${if (it == 0) ":kept" else "nop"}
                iput-object v0, p0, $CHIP_LIST->A00:$answers
                """.trimIndent()
            } + listOf("return-object v0")).joinToString("\n"), static = false, returns = "Ljava/lang/Object;"),
        )

        /** AccentTheme without the hooks for prism chips. */
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

private fun Instruction.kind(): Opcode = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
