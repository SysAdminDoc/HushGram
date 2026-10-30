/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.refreshrate

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Use the highest refresh rate writes no hook of its own: it switches its status method on, and the
 * extension's watcher asks each resumed window for the display's fastest mode.
 */
class RefreshRateHookTest {
    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        refreshRatePatch.execute(context)
        assertEquals(1, context.status("refreshRate"))
        assertEquals("another patch's status is still off", 0, context.status("glassTabBar"))
    }

    /** An extension without the status method fails at patch time, naming it. */
    @Test
    fun anExtensionWithoutTheStatusMethodRefuses() {
        val status = ExtensionDex.classDef(SETTINGS_STATUS)
        val without = ImmutableClassDef(
            status.type, status.accessFlags, status.superclass, status.interfaces, status.sourceFile, status.annotations,
            status.fields, status.methods.filterNot { it.name == "refreshRate" },
        )
        val failure = assertThrows(PatchException::class.java) { refreshRatePatch.execute(PatchContexts.of(listOf(without))) }
        assertTrue(failure.message, failure.message!!.contains("refreshRate()"))
    }

    private fun BytecodePatchContext.status(name: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.first { it.name == name }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral
}
