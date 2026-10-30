/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.history

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.keep.findMessageParser
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Chat history tools, on each build's own message parser and chat screen. */
class NativeChatHistoryTest {
    @Test fun declaredBuildsResolveTheMessageFields() = fixtures { check(it) }

    @Test fun eachOtherBuildResolvesTheMessageFields() = otherBuilds { check(it) }

    private fun check(bundle: File) {
        val name = bundle.parentFile.name
        val base = (
            FixtureDex.classesLoading(bundle, "message_item_dict") +
                FixtureDex.classesLoading(bundle, "DirectThreadController.onResume") +
                FixtureDex.classesLoading(bundle, "DirectThreadController.onPause")
            ).distinctBy { it.type }
        val classes = FixtureDex.withStringPools(bundle, base).associateBy { it.type }.toMutableMap()
        for (type in listOf(CHAT_MESSAGES, CHAT_PAGER)) classes[type] = ImmutableClassDef.of(ExtensionDex.classDef(type))

        // The classes the parser's stores name join the slice, as the patcher has the whole APK.
        val parse = PatchContexts.of(classes.values).findMessageParser()
        val named = mutableSetOf<String>()
        for (instruction in parse.visualCode()) {
            val field = instruction.visualReference() as? FieldReference ?: continue
            if (instruction.opcode == Opcode.IPUT_OBJECT || instruction.opcode == Opcode.IPUT_BOOLEAN) {
                named += field.definingClass
                named += field.type
            }
        }
        var more = FixtureDex.classes(bundle, named - classes.keys)
        classes += more
        // Their ancestors, so a type's superclass can be read.
        more = FixtureDex.classes(bundle, more.values.mapNotNull { it.superclass }.toSet() - classes.keys)
        classes += more

        val context = PatchContexts.of(classes.values)
        val found = try {
            context.findChatHistory()
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        val fields = found.fields
        assertTrue("$name: the message class is public", AccessFlags.PUBLIC.isSet(classes.getValue(fields.owner).accessFlags))
        assertEquals("$name: item_id", "Ljava/lang/String;", fields.id.type)
        assertEquals("$name: is_sent_by_viewer", "Z", fields.sent.type)
        assertNotNull("$name: the message type is an enum", fields.kind)

        val resumeBefore = found.resume.visualCode().count { it.opcode != Opcode.NOP }
        val pauseBefore = found.pause.visualCode().count { it.opcode != Opcode.NOP }
        writeChatHistory(found)
        assertEquals("$name: onResume gets one call", resumeBefore + 1, found.resume.visualCode().count { it.opcode != Opcode.NOP })
        assertEquals("$name: onPause gets one call", pauseBefore + 1, found.pause.visualCode().count { it.opcode != Opcode.NOP })
        assertEquals((found.resume.visualCode().first().visualReference() as MethodReference).toString(), PAGER_OPENED)
        assertEquals((found.pause.visualCode().first().visualReference() as MethodReference).toString(), PAGER_CLOSED)

        val bridges = context.mutableClassDefBy(CHAT_MESSAGES).methods.associateBy { it.name }
        assertTrue(bridges.getValue("isMessage").visualCode().any { it.opcode == Opcode.INSTANCE_OF })
        assertTrue(bridges.getValue("sentByViewer").visualCode().any { it.opcode == Opcode.IGET_BOOLEAN })
        assertTrue(bridges.getValue("kind").visualCode().any { (it.visualReference() as? MethodReference)?.name == "name" })
        for (read in listOf("id", "sender", "text", "time")) {
            assertTrue("$name: $read reads a field", bridges.getValue(read).visualCode().any { it.opcode == Opcode.IGET_OBJECT })
        }
    }

    /** A parser with no "text" key fails at patch time, naming the key, before any code changes. */
    @Test fun aParserWithoutTheKeyFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(emptyList<ClassDef>())
        val failure = assertThrows(PatchException::class.java) { context.findChatHistory() }
        assertTrue(failure.message, failure.message!!.startsWith(CHAT_HISTORY_PATCH))
    }
}
