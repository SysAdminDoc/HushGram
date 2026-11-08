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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
 * Accent color hands the like button's line heart to LikedHeart.outline in place of drawing it, so a
 * liked heart in the accent color has no light edge around it (#519).
 */
class LikedHeartTest {
    @Test
    fun theHeartsLineHeartGoesToTheExtensionWithItsLikedProgress() {
        val context = PatchContexts.of(listOf(heart(HEART), heart(FADE, readsProgress = false), extension()))

        context.dropLikedHeartOutline()

        val draw = context.mutableClassDefBy(HEART).methods.single { it.name == "draw" }
        val body = draw.code()
        val at = body.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == LIKED_HEART }
        assertTrue("the hook is in the heart's draw", at > 0)
        val read = body[at - 1]
        assertEquals(Opcode.IGET, read.opcode)
        assertEquals("the liked progress", "$HEART->progress:F", ((read as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" })
        val hook = body[at] as FiveRegisterInstruction
        val outline = body[at - 2] as TwoRegisterInstruction
        assertEquals("the line heart, the canvas and the progress", listOf(outline.registerA, 6, (read as TwoRegisterInstruction).registerA), listOf(hook.registerC, hook.registerD, hook.registerE))
        assertEquals("only the filled heart is still drawn by Instagram", 1, body.count { it.callsDraw() })
        assertEquals("the outline's own read stays where the jump lands", Opcode.IGET_OBJECT, body[at - 2].opcode)

        val fade = context.mutableClassDefBy(FADE).methods.single { it.name == "draw" }.code()
        assertTrue("a drawable that reads no progress after its draw is left alone", fade.none { (it as? ReferenceInstruction)?.reference?.toString() == LIKED_HEART })
        assertEquals(2, fade.count { it.callsDraw() })
    }

    @Test
    fun twoHeartsOrNoneLeaveEveryHeartAlone() {
        val two = PatchContexts.of(listOf(heart(HEART), heart(FADE), extension()))
        two.dropLikedHeartOutline()
        for (type in listOf(HEART, FADE)) {
            val body = two.mutableClassDefBy(type).methods.single { it.name == "draw" }.code()
            assertEquals(type, 2, body.count { it.callsDraw() })
        }
        PatchContexts.of(listOf(extension())).dropLikedHeartOutline()
    }

    @Test
    fun anExtensionWithoutTheHookFailsThePatch() {
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(heart(HEART), classOf(LIKED_HEART_CLASS))).dropLikedHeartOutline()
        }
        assertTrue(failure.message!!, failure.message!!.contains(LIKED_HEART))
    }

    /** On every build the like heart, the one made from a line heart and a filled one, takes the hook once. */
    @Test
    fun everyBuildHandsTheLikeHeartsLineHeartToTheExtension() {
        val checked = builds()
        assertTrue("no fixture builds", checked.isNotEmpty())
        for ((code, bundle) in checked) {
            val drawables = FixtureDex.subclasses(bundle, setOf(DRAWABLE))
            val context = PatchContexts.of(drawables + extension())
            context.dropLikedHeartOutline()
            val hooked = drawables.map { it.type }.filter { type ->
                context.mutableClassDefBy(type).methods.any { method -> method.code().any { (it as? ReferenceInstruction)?.reference?.toString() == LIKED_HEART } }
            }
            assertEquals("$code: one heart", 1, hooked.size)
            val heart = context.mutableClassDefBy(hooked.single())
            assertTrue("$code: built from two drawables and a flag", heart.methods.any {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(DRAWABLE, DRAWABLE, "Z")
            })
            val body = heart.methods.single { it.name == "draw" }.code()
            val at = body.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == LIKED_HEART }
            assertEquals("$code: the progress read for the hook", Opcode.IGET, body[at - 1].opcode)
            assertEquals("$code: and read again right after", body[at - 1].read(), body[at + 1].read())
            assertEquals("$code: the line heart read for the hook", Opcode.IGET_OBJECT, body[at - 2].opcode)
            val line = (body[at - 2] as ReferenceInstruction).reference.toString()
            assertEquals("$code: and nowhere else", 1, body.count { (it as? ReferenceInstruction)?.reference?.toString() == line })
            assertTrue("$code: Instagram still draws the filled heart", body.any { it.callsDraw() })
        }
    }

    private fun builds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        return (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** An iget as its opcode, registers and field, for comparing reads. */
    private fun Instruction.read(): String {
        val field = (this as ReferenceInstruction).reference as FieldReference
        val registers = this as TwoRegisterInstruction
        return "$opcode v${registers.registerA}, v${registers.registerB}, ${field.definingClass}->${field.name}:${field.type}"
    }

    private fun Instruction.callsDraw(): Boolean =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.definingClass == DRAWABLE && it.name == "draw"
        } == true

    private companion object {
        const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
        const val CANVAS = "Landroid/graphics/Canvas;"
        const val HEART = "LX/heart;"
        const val FADE = "LX/fade;"

        fun extension(): ClassDef = classOf(
            LIKED_HEART_CLASS,
            method(LIKED_HEART_CLASS, "outline", listOf(DRAWABLE, CANVAS, "F"), 0, "return-void", static = true),
        )

        /**
         * A heart like Instagram's: its draw draws the line heart, then reads its liked progress and
         * lays the filled heart over it. A jump lands on the line heart's read, as on 454. Without
         * [readsProgress] the draw goes on to the filled heart without reading a float first.
         */
        fun heart(type: String, readsProgress: Boolean = true): ClassDef {
            val progress = if (readsProgress) "iget v1, p0, $type->progress:F" else "const/high16 v1, 0x3f800000"
            val draw = method(
                type, "draw", listOf(CANVAS), 5,
                """
                    iget v0, p0, $type->alpha:I
                    if-eqz v0, :done
                    const/16 v4, 0xff
                    const/4 v3, -0x1
                    if-ge v0, v4, :outline
                    const/4 v2, 0x1
                    :outline
                    iget-object v0, p0, $type->base:$DRAWABLE
                    invoke-virtual { v0, p1 }, $DRAWABLE->draw($CANVAS)V
                    $progress
                    const/high16 v0, 0x437f0000
                    mul-float/2addr v1, v0
                    float-to-int v1, v1
                    if-lez v1, :done
                    iget-object v0, p0, $type->filled:$DRAWABLE
                    invoke-virtual { v0, v1 }, $DRAWABLE->setAlpha(I)V
                    invoke-virtual { v0, p1 }, $DRAWABLE->draw($CANVAS)V
                    :done
                    return-void
                """,
                static = false,
            )
            val layer = method(
                type, "layer", listOf(CANVAS), 6,
                """
                    move-object v0, p1
                    const/4 v1, 0x0
                    const/4 v2, 0x0
                    const/4 v3, 0x0
                    const/4 v4, 0x0
                    const/16 v5, 0x80
                    invoke-virtual/range { v0 .. v5 }, $CANVAS->saveLayerAlpha(FFFFI)I
                    move-result v0
                    return v0
                """,
                static = false, returns = "I",
            )
            return classOf(type, draw, layer, superclass = DRAWABLE)
        }

        fun method(type: String, name: String, parameters: List<String>, locals: Int, body: String, static: Boolean, returns: String = "V"): Method {
            val registers = locals + parameters.size + if (static) 0 else 1
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
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
