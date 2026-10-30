/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.util

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * setExtensionIsPatchIncluded makes the one isPatchIncluded it finds return true, and stops when
 * an "ends with" class type finds two rather than flipping whichever comes first.
 */
class ExtensionIsPatchIncludedTest {
    @Test
    fun theOneMatchReturnsTrue() {
        val context = PatchContexts.of(listOf(extension("Lcom/example/a/Patch;")))
        with(context) { setExtensionIsPatchIncluded("Lcom/example/a/Patch;") }

        val first = context.mutableClassDefBy("Lcom/example/a/Patch;").methods.single().implementation!!.instructions.first()
        assertEquals(Opcode.CONST_4, first.opcode)
        assertEquals("isPatchIncluded answers true", 1, (first as NarrowLiteralInstruction).narrowLiteral)
    }

    @Test
    fun twoMatchesStopThePatch() {
        val types = listOf("Lcom/example/a/Patch;", "Lcom/example/b/Patch;")
        val context = PatchContexts.of(types.map(::extension))
        val refusal = assertThrows(PatchException::class.java) {
            with(context) { setExtensionIsPatchIncluded("/Patch;") }
        }
        val message = refusal.message.orEmpty()
        assertTrue(message, message.contains("exactly one") && message.contains("found 2"))
        for (type in types) {
            val first = context.mutableClassDefBy(type).methods.single().implementation!!.instructions.first()
            assertEquals("$type still answers false", 0, (first as NarrowLiteralInstruction).narrowLiteral)
        }
    }

    private fun extension(type: String): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "isPatchIncluded", emptyList(), "Z", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null, null,
                ImmutableMethodImplementation(
                    1,
                    listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)),
                    null,
                    null,
                ),
            ),
        ),
    )
}
