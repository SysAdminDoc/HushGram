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
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backgrounds in the accent color (#577): the Compose palettes' dark grays are repainted as their set-up ends. */
class ComposeSurfacesTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(ACCENT_THEME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SWAP_SURFACE is not in the extension: $declared", SWAP_SURFACE in declared)
    }

    /** A branch landing on the return still runs the repaint, and each field is read, swapped and stored back. */
    @Test
    fun aBranchToTheReturnStillRepaints() {
        val context = PatchContexts.of(PALETTE_SURFACES.map { (type, fields) -> palette(type, fields) })
        context.swapComposeSurfaces()
        for ((type, fields) in PALETTE_SURFACES) {
            val code = context.mutableClassDefBy(type).methods.single { it.name == "<clinit>" }.code()
            assertRepaintedLast(type, fields, code)
            val jump = code.first { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
            val target = code.first { (it as BuilderInstruction).location === jump.target.location }
            assertEquals("$type: the branch lands on the first read", Opcode.SGET_WIDE, target.opcode)
            assertEquals("$type: the branch lands on the first field", fields.first(), target.fieldName())
        }
    }

    @Test
    fun aMissingFieldOrHookRefuses() {
        val whole = PALETTE_SURFACES.map { (type, fields) -> palette(type, fields) }
        val noField = PALETTE_SURFACES.map { (type, fields) -> palette(type, fields.drop(1)) }
        val hook = ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))
        for ((case, classes) in listOf(
            "no hook in the extension" to whole,
            "a field gone" to noField + hook,
            "a palette gone" to whole.drop(1) + hook,
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).requirePaletteSurfaces() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Accent color"))
        }
        PatchContexts.of(whole + hook).requirePaletteSurfaces()
    }

    @Test
    fun everyDeclaredBundleEndsWithTheRepaint() {
        var checked = 0
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun everyOtherBuildEndsWithTheRepaint() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: java.io.File, label: String) {
        val found = FixtureDex.classes(bundle, PALETTE_SURFACES.keys).values.map { ImmutableClassDef.of(it) }
        val hook = ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))
        val context = PatchContexts.of(found + hook)
        context.requirePaletteSurfaces()
        context.swapComposeSurfaces()
        for ((type, fields) in PALETTE_SURFACES) {
            assertRepaintedLast("$label $type", fields, context.mutableClassDefBy(type).methods.single { it.name == "<clinit>" }.code())
        }
    }

    private fun assertRepaintedLast(what: String, fields: List<String>, code: List<Instruction>) {
        val real = code.filter { it.opcode != Opcode.NOP }
        val tail = real.takeLast(fields.size * 4 + 1)
        assertEquals("$what: ends in a return", Opcode.RETURN_VOID, tail.last().opcode)
        for ((step, field) in fields.withIndex()) {
            val at = step * 4
            assertEquals("$what: read", Opcode.SGET_WIDE, tail[at].opcode)
            assertEquals("$what: read field", field, tail[at].fieldName())
            assertEquals("$what: hook", Opcode.INVOKE_STATIC_RANGE, tail[at + 1].opcode)
            assertEquals("$what: hook name", SWAP_SURFACE, (tail[at + 1] as ReferenceInstruction).reference.toString())
            assertEquals("$what: result", Opcode.MOVE_RESULT_WIDE, tail[at + 2].opcode)
            assertEquals("$what: store", Opcode.SPUT_WIDE, tail[at + 3].opcode)
            assertEquals("$what: store field", field, tail[at + 3].fieldName())
        }
    }

    private fun Instruction.fieldName(): String = ((this as ReferenceInstruction).reference as FieldReference).name

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun palette(type: String, fields: List<String>): ClassDef {
        val body = """
            const/4 v0, 0x0
            if-eqz v0, :end
            const-wide/16 v0, 0x1
            sput-wide v0, $type->${fields.first()}:J
            :end
            return-void
        """.trimIndent()
        val setUp = ImmutableMethod.of(
            MutableMethod(
                ImmutableMethod(
                    type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                    ImmutableMethodImplementation(5, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body) },
        )
        val held = fields.map { ImmutableField(type, it, "J", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null) }
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, held, listOf(setUp))
    }
}
