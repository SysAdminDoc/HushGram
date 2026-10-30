/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.ExtensionDex
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeThreadSeenTest.Companion.threadClasses
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mark a chat seen when you reply, on each build's own composer and receipt classes. */
class NativeReplySeenTest {
    @Test fun declaredBuildsHookTheComposerSend() = fixtures { check(it) }

    @Test fun eachOtherBuildHooksTheComposerSend() = otherBuilds { check(it) }

    private fun check(bundle: File) {
        val classes = threadClasses(bundle).toMutableMap()
        val composers = mutableMapOf<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                if (candidate.methods.any { it.isComposerSend() }) composers[candidate.type] = ImmutableClassDef.of(candidate)
            }
        }
        assertEquals("one class has the composer's send", 1, composers.size)
        classes += composers
        val send = composers.values.single().methods.single { it.isComposerSend() }
        // The static call reading the thread id, its class and the chat handle's.
        val needs = mutableSetOf<String>()
        for (instruction in send.visualCode()) {
            val call = instruction.visualReference() as? MethodReference ?: continue
            if (instruction.opcode == Opcode.INVOKE_STATIC && call.parameterTypes.size == 1 && call.returnType == "Ljava/lang/String;" &&
                call.parameterTypes[0].startsWith("LX/")
            ) {
                needs += call.definingClass
                needs += call.parameterTypes[0].toString()
            }
        }
        classes += FixtureDex.classes(bundle, needs - classes.keys)
        classes[INSTAGRAM_CHATS] = ImmutableClassDef.of(ExtensionDex.classDef(INSTAGRAM_CHATS))

        // The receipt's own class holds the sender field, so it joins the slice once the finder names it.
        val first = PatchContexts.of(classes.values).findThreadSeen()
        classes += FixtureDex.classes(bundle, setOf(first.mutation) - classes.keys)
        val context = PatchContexts.of(classes.values)
        val seen = context.findThreadSeen()
        val found = context.findReplySeen(seen)
        assertNotNull("${bundle.parentFile.name}: the composer's send resolves", found)
        found!!
        assertEquals(send.definingClass, found.send.definingClass)
        assertEquals(Opcode.RETURN, found.send.getInstruction(found.returnAt).opcode)
        val before = found.send.visualCode().size
        writeReplySeen(found)
        val after = found.send.visualCode()
        val hooked = after.indexOfFirst { (it.visualReference() as? MethodReference)?.toString() == REPLIED }
        assertTrue("the hook calls the extension before the return", hooked in 0 until after.size - 1)
        assertEquals("the original return follows the hook", Opcode.RETURN, after[hooked + 1].opcode)
        assertTrue("only the hook's five instructions were added", after.count { it.opcode != Opcode.NOP } - before >= 5)
        val bridges = context.mutableClassDefBy(INSTAGRAM_CHATS).methods.associateBy { it.name }
        assertTrue(bridges.getValue("composerThread").visualCode().any { it.opcode == Opcode.INVOKE_STATIC })
        assertTrue(bridges.getValue("receiptSender").visualCode().any { it.opcode == Opcode.IGET_OBJECT })
    }

    private companion object {
        fun com.android.tools.smali.dexlib2.iface.Method.isComposerSend() =
            returnType == "Z" && parameterTypes.map(Any::toString) == listOf("Landroid/text/Editable;", "Ljava/lang/Long;", "Ljava/lang/String;") &&
                visualCode().any { it.visualString() == "direct_composer_send_text" }
    }
}
