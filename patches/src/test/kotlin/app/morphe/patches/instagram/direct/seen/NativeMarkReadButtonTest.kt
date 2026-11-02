/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.lock.CHAT_CLOSED
import app.morphe.patches.instagram.direct.lock.CHAT_OPENED
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** The header's check mark (#458) on each build's own chat screen, alone and after the chat lock. */
class NativeMarkReadButtonTest {
    @Test fun declaredBuildsTellTheCheckMarkWhenAChatComesAndGoes() = fixtures { check(it) }

    @Test fun eachOtherBuildTellsTheCheckMarkWhenAChatComesAndGoes() = otherBuilds { check(it) }

    private fun slice(bundle: File) = PatchContexts.of(
        FixtureDex.withStringPools(
            bundle,
            (
                FixtureDex.classesLoading(bundle, "DirectThreadController.onResume") +
                    FixtureDex.classesLoading(bundle, "DirectThreadController.onPause")
                ).distinctBy { it.type },
        ),
    )

    private fun check(bundle: File) {
        val name = bundle.parentFile.name
        val alone = slice(bundle).findMarkReadButton()
        assertNotNull("$name: the chat screen's onResume and onPause", alone)
        val resumeBefore = alone!!.resume.calls().size
        val pauseBefore = alone.pause.calls().size
        hookMarkReadButton(alone)
        assertEquals("$name: onResume gets one call", resumeBefore + 1, alone.resume.calls().size)
        assertEquals("$name: onPause gets one call", pauseBefore + 1, alone.pause.calls().size)
        assertEquals("$name: first in onResume", BUTTON_OPENED, alone.resume.called(0))
        assertEquals("$name: first in onPause", BUTTON_CLOSED, alone.pause.called(0))

        // Patches apply in name order, so the chat lock's own calls are already first.
        val locked = slice(bundle).findMarkReadButton()!!
        locked.resume.addInstructions(0, "invoke-static/range { p0 .. p0 }, $CHAT_OPENED")
        locked.pause.addInstructions(0, "invoke-static/range { p0 .. p0 }, $CHAT_CLOSED")
        hookMarkReadButton(locked)
        assertEquals("$name: the lock keeps onResume's first call", CHAT_OPENED, locked.resume.called(0))
        assertEquals("$name: the check mark comes right after it", BUTTON_OPENED, locked.resume.called(1))
        assertEquals("$name: the lock keeps onPause's first call", CHAT_CLOSED, locked.pause.called(0))
        assertEquals(BUTTON_CLOSED, locked.pause.called(1))
    }

    private fun Method.calls() = visualCode().filter { it.opcode != Opcode.NOP }

    private fun Method.called(at: Int) = (calls()[at].visualReference() as MethodReference).toString()

    /** A build without the chat screen still holds receipts: the check mark is just left out. */
    @Test fun withoutTheChatScreenThereIsNoCheckMark() {
        assertNull(PatchContexts.of(emptyList<ClassDef>()).findMarkReadButton())
    }
}
