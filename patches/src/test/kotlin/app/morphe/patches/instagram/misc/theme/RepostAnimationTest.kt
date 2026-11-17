/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
 * Accent color hands the Keyframes loader's raw id and the drawable it makes to the extension, and
 * wraps the drawable's draw in two calls to it, so the repost button's animation, black or white in
 * its file, takes the accent icon color (#724).
 */
class RepostAnimationTest {
    @Test
    fun theLoaderAndTheDrawableTakeTheHooks() {
        val context = PatchContexts.of(listOf(keyframes(KEYFRAMES), loader(LOADER, KEYFRAMES), parser(), elsewhere(), extension()))

        context.tintRepostAnimation()

        val load = context.loaderOf(LOADER)
        assertHooked("the loader", load, KEYFRAMES)
        val elsewhere = context.mutableClassDefBy(ELSEWHERE).methods.single().code()
        assertTrue("a Context and int method making something else is left alone", elsewhere.none { it.callsAny(REPOST_LOAD, REPOST_LOADED) })
        assertTrue("the parser is no drawable", context.mutableClassDefBy(PARSER).methods.all { method -> method.code().none { it.callsAny(REPOST_DRAW, REPOST_DRAWN) } })
        assertDrawWrapped("the drawable", context.drawOf(KEYFRAMES))
    }

    @Test
    fun twoDrawablesOrNoLoaderLeaveKeyframesAlone() {
        val two = PatchContexts.of(listOf(keyframes(KEYFRAMES), keyframes(OTHER), loader(LOADER, KEYFRAMES), extension()))
        two.tintRepostAnimation()
        for (type in listOf(KEYFRAMES, OTHER)) assertTrue(type, two.drawOf(type).code().none { it.callsAny(REPOST_DRAW, REPOST_DRAWN) })
        assertTrue(two.loaderOf(LOADER).code().none { it.callsAny(REPOST_LOAD, REPOST_LOADED) })

        val none = PatchContexts.of(listOf(keyframes(KEYFRAMES), extension()))
        none.tintRepostAnimation()
        assertTrue(none.drawOf(KEYFRAMES).code().none { it.callsAny(REPOST_DRAW, REPOST_DRAWN) })
        PatchContexts.of(listOf(extension())).tintRepostAnimation()
    }

    @Test
    fun anExtensionWithoutTheHooksFailsThePatch() {
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(keyframes(KEYFRAMES), loader(LOADER, KEYFRAMES), extension(skip = REPOST_DRAWN))).tintRepostAnimation()
        }
        assertTrue(failure.message!!, failure.message!!.contains(REPOST_DRAWN))
    }

    /** On every build there's one Keyframes drawable and one loader making it, and both take their hooks. */
    @Test
    fun everyBuildHooksItsKeyframesLoaderAndDrawable() {
        val checked = builds()
        assertTrue("no fixture builds", checked.isNotEmpty())
        for ((code, bundle) in checked) {
            val loading = FixtureDex.classesLoading(bundle, KEYFRAMES_CAPABILITY)
            val drawables = loading.filter { it.superclass == DRAWABLE }.map { it.type }.toSet()
            assertTrue("$code: a Drawable loads the capability line", drawables.isNotEmpty())
            val makers = FixtureDex.classesCreating(bundle, drawables)
            val context = PatchContexts.of((loading + makers).distinctBy { it.type } + extension())
            context.tintRepostAnimation()

            val hooked = drawables.filter { type -> context.drawOf(type).code().any { it.callsAny(REPOST_DRAW) } }
            assertEquals("$code: one Keyframes drawable", 1, hooked.size)
            assertDrawWrapped("$code: the drawable", context.drawOf(hooked.single()))
            val loaders = makers.flatMap { maker ->
                context.mutableClassDefBy(maker.type).methods.filter { method -> method.code().any { it.callsAny(REPOST_LOAD) } }
            }
            assertEquals("$code: one loader", 1, loaders.size)
            assertHooked("$code: the loader", loaders.single(), hooked.single())
        }
    }

    /**
     * [method] calls [REPOST_LOAD] first with its Context and raw id, and [REPOST_LOADED] once, right
     * after [drawable]'s constructor, with the drawable it built, which it then returns.
     */
    private fun assertHooked(what: String, method: Method, drawable: String) {
        val code = method.code()
        assertEquals("$what: makes $drawable", drawable, method.returnType)
        assertEquals("$what: first the raw id", REPOST_LOAD, code[0].target())
        val locals = method.implementation!!.registerCount - 2
        assertEquals("$what: the Context and the raw id", listOf(locals, locals + 1), code[0].range())
        val made = code.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT && it.target()!!.startsWith("$drawable-><init>(") }
        assertTrue("$what: builds the drawable", made > 0)
        val built = (code[made] as FiveRegisterInstruction).registerC
        assertEquals("$what: hands over what it built", REPOST_LOADED, code[made + 1].target())
        assertEquals("$what: the drawable", listOf(built, built), code[made + 1].range())
        assertEquals("$what: and returns it", Opcode.RETURN_OBJECT, code[made + 2].opcode)
        assertEquals("$what: the same drawable", built, (code[made + 2] as OneRegisterInstruction).registerA)
        assertTrue("$what: nothing jumps past the hook", made + 1 !in method.jumpTargets())
        assertEquals("$what: hands over once", 1, code.count { it.callsAny(REPOST_LOADED) })
    }

    /** [draw] calls [REPOST_DRAW] first and [REPOST_DRAWN] before each return, with this and the canvas. */
    private fun assertDrawWrapped(what: String, draw: Method) {
        val code = draw.code()
        val self = draw.implementation!!.registerCount - 2
        assertEquals("$what: opens first", REPOST_DRAW, code[0].target())
        assertEquals("$what: this and the canvas", listOf(self, self + 1), code[0].range())
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        assertTrue("$what: returns", returns.isNotEmpty())
        val targets = draw.jumpTargets()
        for (at in returns) {
            assertEquals("$what: closes before $at", REPOST_DRAWN, code[at - 1].target())
            assertEquals("$what: this and the canvas at $at", listOf(self, self + 1), code[at - 1].range())
            assertTrue("$what: nothing jumps past the close to $at", at !in targets)
        }
        assertEquals("$what: opens once", 1, code.count { it.callsAny(REPOST_DRAW) })
    }

    private fun BytecodePatchContext.drawOf(type: String): Method =
        mutableClassDefBy(type).methods.single { it.name == "draw" && it.parameterTypes.map(CharSequence::toString) == listOf(CANVAS) }

    private fun BytecodePatchContext.loaderOf(type: String): Method =
        mutableClassDefBy(type).methods.single { it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, "I") }

    private fun builds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        return (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.target(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.callsAny(vararg hooks: String): Boolean = target() in hooks

    /** The registers a /range call passes, first and last. */
    private fun Instruction.range(): List<Int> {
        val call = this as RegisterRangeInstruction
        return listOf(call.startRegister, call.startRegister + call.registerCount - 1)
    }

    private companion object {
        const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
        const val CANVAS = "Landroid/graphics/Canvas;"
        const val CONTEXT = "Landroid/content/Context;"
        const val MODEL = "LX/model;"
        const val KEYFRAMES = "LX/keyframes;"
        const val OTHER = "LX/other;"
        const val LOADER = "LX/loader;"
        const val PARSER = "LX/parser;"
        const val ELSEWHERE = "LX/elsewhere;"

        fun extension(skip: String? = null): ClassDef {
            val hooks = listOf(
                Triple("repostAnimation", listOf(CONTEXT, "I"), REPOST_LOAD),
                Triple("repostAnimation", listOf(DRAWABLE), REPOST_LOADED),
                Triple("repostDraw", listOf(DRAWABLE, CANVAS), REPOST_DRAW),
                Triple("repostDrawn", listOf(DRAWABLE, CANVAS), REPOST_DRAWN),
            ).filter { it.third != skip }
            return classOf(ACCENT_THEME, *hooks.map { (name, parameters, _) -> method(ACCENT_THEME, name, parameters, 0, "return-void", static = true) }.toTypedArray())
        }

        /**
         * A Keyframes drawable like 456's: its constructor names a capability a file lacks, and its
         * draw jumps to its one return when there's nothing to draw, and again from its last step.
         */
        fun keyframes(type: String): ClassDef {
            val init = method(
                type, "<init>", listOf(MODEL), 2,
                """
                    invoke-direct { p0 }, $DRAWABLE-><init>()V
                    iput-object p1, p0, $type->model:$MODEL
                    const-string v0, "$KEYFRAMES_CAPABILITY"
                    const-string v1, " is unsupported"
                    return-void
                """,
                static = false,
            )
            val draw = method(
                type, "draw", listOf(CANVAS), 3,
                """
                    iget-object v0, p0, $type->model:$MODEL
                    if-eqz v0, :done
                    invoke-virtual { p1 }, $CANVAS->save()I
                    move-result v1
                    iget v2, p0, $type->progress:F
                    invoke-virtual { v0, p1, v2 }, $MODEL->A00(${CANVAS}F)V
                    if-lez v1, :done
                    invoke-virtual { p1, v1 }, $CANVAS->restoreToCount(I)V
                    :done
                    return-void
                """,
                static = false,
            )
            return classOf(type, init, draw, superclass = DRAWABLE)
        }

        /** A loader like 456's 04q1.A02: null when the file doesn't load, else a new [drawable] around the model. */
        fun loader(type: String, drawable: String): ClassDef = classOf(
            type,
            method(
                type, "A02", listOf(CONTEXT, "I"), 2,
                """
                    invoke-static { p0, p1 }, $MODEL->A00(${CONTEXT}I)$MODEL
                    move-result-object v1
                    const/4 v0, 0x0
                    if-nez v1, :made
                    return-object v0
                    :made
                    new-instance v0, $drawable
                    invoke-direct { v0, v1 }, $drawable-><init>($MODEL)V
                    return-object v0
                """,
                static = true, returns = drawable,
            ),
        )

        /** A class that also names a lacking capability, but draws nothing. */
        fun parser(): ClassDef = classOf(
            PARSER,
            method(PARSER, "A00", listOf("Ljava/lang/String;"), 1, "const-string v0, \"$KEYFRAMES_CAPABILITY\"\nreturn-void", static = true),
        )

        /** A static method taking a Context and an int that makes something other than a Keyframes drawable. */
        fun elsewhere(): ClassDef = classOf(
            ELSEWHERE,
            method(
                ELSEWHERE, "A00", listOf(CONTEXT, "I"), 1,
                """
                    new-instance v0, $MODEL
                    invoke-direct { v0 }, $MODEL-><init>()V
                    return-object v0
                """,
                static = true, returns = MODEL,
            ),
        )

        fun method(type: String, name: String, parameters: List<String>, locals: Int, body: String, static: Boolean, returns: String = "V"): Method {
            val registers = locals + parameters.size + if (static) 0 else 1
            val flags = if (name == "<init>") AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value
            else AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                        flags, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trim()) },
            )
        }

        fun classOf(type: String, vararg methods: Method, superclass: String = "Ljava/lang/Object;"): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.toList(),
        )
    }
}
