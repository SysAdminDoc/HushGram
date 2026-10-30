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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accent color's Compose half (#8): 451's bio links and the Activity tab's Follow buttons take their
 * blues from Compose palettes written into the code, so each accent blue a palette stores goes
 * through AccentColor.composeColor as the palette is set up.
 */
class ComposePalettesTest {
    /** Both ways a palette makes a Compose color are hooked right before the store; anything else is left alone. */
    @Test
    fun eachBlueAPaletteStoresIsHookedBeforeTheStore() {
        val context = PatchContexts.of(listOf(palette(), extension()))
        val stores = context.findPaletteStores()
        assertEquals(listOf("LINK", "FILL_30", "FILL"), stores.map { it.field() })
        assertEquals(listOf(0xff85a1ff.toInt(), 0x4d4a5df9, 0xff4a5df9.toInt()), stores.map { it.argb })

        context.swapComposePalettes(stores)
        val code = context.mutableClassDefBy(PALETTE).methods.single { it.name == "<clinit>" }.code()
        val hooks = code.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == SWAP_COMPOSE }
        assertEquals("a hook per blue", 3, hooks.size)
        for ((at, hook) in hooks) {
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
            assertEquals("the pair the color is in", 0, (hook as RegisterRangeInstruction).startRegister)
            assertEquals(2, hook.registerCount)
            assertEquals(Opcode.MOVE_RESULT_WIDE, code[at + 1].opcode)
            assertEquals(0, (code[at + 1] as OneRegisterInstruction).registerA)
            assertEquals("the store follows", Opcode.SPUT_WIDE, code[at + 2].opcode)
        }
        val stored = code.filter { it.opcode == Opcode.SPUT_WIDE }.map { ((it as ReferenceInstruction).reference as FieldReference).name }
        val hooked = hooks.map { ((code[it.index + 2] as ReferenceInstruction).reference as FieldReference).name }
        assertEquals(listOf("LINK", "FILL_30", "FILL"), hooked)
        assertTrue("the gray, the odd shift and the other class's field stay", stored.containsAll(listOf("GRAY", "HALF", "ELSEWHERE")))
    }

    /** Without the link or the fill among the stores, or without the extension's hook, nothing changes. */
    @Test
    fun withoutTheBluesOrTheHookItRefuses() {
        for ((case, classes) in listOf(
            "no palette" to listOf(extension()),
            "a shift that isn't 32" to listOf(palette(shift = 16), extension()),
            "no hook in the extension" to listOf(palette()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findPaletteStores() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Accent color"))
        }
    }

    /**
     * Every build of 452: BasePrismColors and BasePrismColorsV2 store the dark link and Prism's
     * indigo fill, every blue found is hooked right before its store, and the extension's hook is
     * the one called.
     */
    @Test
    fun everyBuildHooksItsPalettes() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val builds = (versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
        assertEquals("every build of Instagram ${Fixtures.VERSION} has a fixture", Fixtures.BUILDS, builds.size)
        val wanted = NEEDED_PALETTE_BLUES.map { 0xff000000L or it.toLong() }.toSet()
        for ((code, bundle) in builds) {
            val setUps = FixtureDex.methodsWhere(bundle, { true }) { method ->
                method.name == "<clinit>" && method.code().any { it is WideLiteralInstruction && it.opcode != Opcode.CONST && it.wideLiteral in wanted }
            }
            val classes = FixtureDex.classes(bundle, setUps.map { it.definingClass }.toSet()).values.toList()
            val context = PatchContexts.of(classes + extension())
            val stores = context.findPaletteStores()
            val palettes = stores.map { it.method.definingClass }.toSet()
            assertTrue("$code: $palettes", palettes.containsAll(listOf(BASE_PRISM_COLORS, BASE_PRISM_COLORS_V2)))
            for (needed in NEEDED_PALETTE_BLUES) {
                assertTrue("$code: #%06x".format(needed), stores.any { it.argb and 0xffffff == needed })
            }
            context.swapComposePalettes(stores)
            val hooked = palettes.sumOf { type ->
                context.mutableClassDefBy(type).methods.single { it.name == "<clinit>" }.code().count {
                    (it as? ReferenceInstruction)?.reference?.toString() == SWAP_COMPOSE
                }
            }
            assertEquals("$code: a hook per blue", stores.size, hooked)
        }
    }

    private fun PaletteStore.field(): String =
        ((method.code()[store] as ReferenceInstruction).reference as FieldReference).name

    private companion object {
        const val PALETTE = "Lfixture/Palette;"
        const val BASE_PRISM_COLORS = "Lcom/instagram/compose/core/theme/BasePrismColors;"
        const val BASE_PRISM_COLORS_V2 = "Lcom/instagram/compose/core/theme/BasePrismColorsV2;"

        /**
         * A palette's set-up storing the dark link and Prism's fill both ways a palette makes a
         * Compose color, a see-through fill, a gray, a blue shifted by a register holding something
         * other than 32, and a blue stored into another class.
         */
        fun palette(shift: Int = 32): ClassDef {
            val body = """
                const/16 v4, 0x${shift.toString(16)}
                const/16 v3, 0x10
                const-wide v0, 0xff85a1ffL
                shl-long/2addr v0, v4
                sput-wide v0, $PALETTE->LINK:J
                const-wide v0, 0xff737373L
                shl-long/2addr v0, v4
                sput-wide v0, $PALETTE->GRAY:J
                const-wide/32 v0, 0x4d4a5df9
                shl-long/2addr v0, v4
                sput-wide v0, $PALETTE->FILL_30:J
                const-wide v0, 0xff3143e3L
                shl-long/2addr v0, v3
                sput-wide v0, $PALETTE->HALF:J
                const-wide v0, 0xff4a5df9L
                invoke-static { v0, v1 }, Lfixture/Color;->of(J)J
                move-result-wide v0
                sput-wide v0, $PALETTE->FILL:J
                const-wide v0, 0xff3849daL
                shl-long/2addr v0, v4
                sput-wide v0, Lfixture/Other;->ELSEWHERE:J
                return-void
            """
            return classOf(PALETTE, method(PALETTE, "<clinit>", emptyList(), "V", 5, body, AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value))
        }

        fun extension(): ClassDef = classOf(
            ACCENT_COLOR,
            method(ACCENT_COLOR, "composeColor", listOf("J"), "J", 0, "return-wide p0", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value),
        )

        fun method(type: String, name: String, parameters: List<String>, returns: String, locals: Int, body: String, flags: Int): Method {
            val registers = locals + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent().trim()) },
            )
        }

        fun classOf(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList(),
        )

        fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    }
}
