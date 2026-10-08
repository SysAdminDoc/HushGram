/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.typing

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.typing.HideTypingHookTest.Companion.assertTypingGuard
import app.morphe.patches.instagram.direct.typing.HideTypingHookTest.Companion.code
import app.morphe.patches.instagram.direct.typing.HideTypingHookTest.Companion.reference
import app.morphe.patches.instagram.direct.typing.HideTypingHookTest.Companion.snapshot
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The typing hook on each declared build's own dex: the service it lands in and the sender it skips. */
class NativeTypingTest {
    @Test fun declaredBuildsHoldTypingInTheServiceAndLeaveEverythingElse() = fixtures { bundle ->
        val classes = typingClasses(bundle)
        val context = PatchContexts.of(classes.values)
        val found = context.findTyping()
        assertTrue(found.service.code().map { it.text() }.containsAll(listOf(TYPING_SERVICE, TYPING_ENABLED)))
        assertTrue(found.sender.code().any { it.text() == TYPING_COMMAND })
        val first = found.service.code().first()
        val hooked = "${found.service.definingClass}->${found.service.name}("
        val before = classes.mapValues { snapshot(it.value.methods) }
        context.holdBackTyping()
        assertTypingGuard(found.service, first)
        for ((type, original) in before) {
            val now = snapshot(context.mutableClassDefBy(type).methods)
            assertEquals("native $type changed", original.filterNot { it.first.startsWith(hooked) }, now.filterNot { it.first.startsWith(hooked) })
        }
        val senderKey = found.sender.toString()
        assertEquals("only the service sends the typing indicator", 1,
            classes.values.flatMap { it.methods }.sumOf { method -> method.code().count { it.reference() == senderKey } })
    }

    @Test fun dexBackedInstructionReReadsResolveTheSameService() = fixtures { bundle ->
        val types = typingClasses(bundle).keys - TYPING_STATUS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(TYPING_STATUS))
        val found = context.findTyping()
        val first = found.service.code().first()
        context.holdBackTyping()
        assertTypingGuard(found.service, first)
    }

    companion object {
        private val cached = mutableMapOf<String, Map<String, ClassDef>>()
        private val anchors = setOf(TYPING_SERVICE, TYPING_COMMAND)

        /** The classes holding the service's and the sender's strings, every caller of the sender, and the extension. */
        private fun typingClasses(bundle: File): Map<String, ClassDef> = cached.getOrPut(bundle.absolutePath) {
            val found = mutableMapOf<String, ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.stringSection.any { it in anchors }) for (candidate in dex.classes) {
                    if (candidate.methods.any { method -> method.code().any { it.text() in anchors } }) {
                        found[candidate.type] = ImmutableClassDef.of(candidate)
                    }
                }
            }
            val sender = found.values.flatMap { it.methods }.single { method -> method.code().any { it.text() == TYPING_COMMAND } }.toString()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.methodSection.any { it.toString() == sender }) for (candidate in dex.classes) {
                    if (candidate.methods.any { method -> method.code().any { (it as? ReferenceInstruction)?.reference is MethodReference && it.reference() == sender } }) {
                        found[candidate.type] = ImmutableClassDef.of(candidate)
                    }
                }
            }
            found[TYPING_STATUS] = ImmutableClassDef.of(ExtensionDex.classDef(TYPING_STATUS))
            found
        }

        private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.text() =
            ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
    }
}
