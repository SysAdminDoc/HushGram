/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureCache
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The New Messages divider's cursor on each declared build's own dex: the one reader of the account's
 * own cursor in a chat is found by its shape, the hook goes in front of the first read of its
 * boundary, and the cursor bridges are written from the reader's own fields and calls.
 */
class NativeLocalCursorTest {
    @Test fun declaredBuildsHookTheOneCursorReaderBeforeItsBoundaryIsRead() = fixtures { bundle -> hookTheReaderOn(bundle) }

    @Test fun otherBuildsHookTheOneCursorReaderBeforeItsBoundaryIsReadToo() = otherBuilds { bundle -> hookTheReaderOn(bundle) }

    private fun hookTheReaderOn(bundle: File) {
        val classes = NativeMarkReadTest.markReadClasses(bundle) + cursorClasses(bundle)
        val context = PatchContexts.of(classes.values)
        val messages = context.findMarkRead(context.findThreadSeen()).messageClass
        val found = context.findLocalCursor(messages)
        assertNotNull("no cursor reader in the slice", found)
        found!!
        val before = found.read.visualCode().filter { it.opcode != Opcode.NOP }.map { it.opcode }
        assertEquals("the cursor, its session, the chat's items, the cursor's id and the move", 5, found.bridges.size)
        assertTrue("the hook goes in front of an IGET_OBJECT of the boundary", found.read.visualCode()[found.at].opcode == Opcode.IGET_OBJECT)
        keepCursorLocal(found)
        val after = found.read.visualCode().filter { it.opcode != Opcode.NOP }
        assertEquals("one call added and nothing else", before.size + 1, after.size)
        val call = after[found.at - found.read.visualCode().take(found.at).count { it.opcode == Opcode.NOP }]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(LOCAL_CURSOR, call.visualReference().toString())
        assertEquals(before, after.map { it.opcode }.filterIndexed { index, _ ->
            index != found.at - found.read.visualCode().take(found.at).count { it.opcode == Opcode.NOP }
        })
        for (bridge in found.bridges) {
            assertTrue("${bridge.stub.name} was written", bridge.stub.visualCode().size > 2)
        }
        val chats = context.mutableClassDefBy(INSTAGRAM_CHATS).methods
        for (name in CURSOR_BRIDGES) {
            assertTrue("$name is written", chats.single { it.name == name }.visualCode().any { it.opcode != Opcode.RETURN_VOID && it.opcode != Opcode.CONST_4 })
        }
    }

    companion object {
        /** The cursor bridges, written only where the reader is in the slice. */
        internal val CURSOR_BRIDGES = setOf("cursorOf", "cursorSession", "cursorItems", "cursorItem", "moveCursor")

        private val cached = FixtureCache<Map<String, ClassDef>>()

        /** The reader, the classes it names and the classes those extend, plus the extension's hooks and bridges. */
        private fun cursorClasses(bundle: File): Map<String, ClassDef> = cached.get(bundle) {
            val readers = FixtureDex.methodsWhere(bundle, { true }) { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/util/List;" &&
                    method.parameterTypes.size == 2 && method.parameterTypes[1].toString() == THREAD_KEY &&
                    method.visualCode().any { it.opcode == Opcode.IGET_OBJECT && (it.visualReference() as? FieldReference)?.type == "Ljava/lang/Long;" }
            }
            val classes = mutableMapOf<String, ClassDef>()
            classes += FixtureDex.classes(bundle, readers.map { it.definingClass }.toSet())
            val named = mutableSetOf<String>()
            for (classDef in classes.values) for (method in classDef.methods) for (instruction in method.visualCode()) {
                when (val reference = instruction.visualReference()) {
                    is MethodReference -> named += reference.parameterTypes.map(Any::toString) + reference.definingClass + reference.returnType
                    is FieldReference -> named += listOf(reference.definingClass, reference.type)
                    is TypeReference -> named += reference.type
                }
            }
            classes += FixtureDex.classes(bundle, named.filter { it.startsWith("L") && it !in classes }.toSet())
            // Every class those extend, where an item's id and time are declared.
            while (true) {
                val supers = classes.values.mapNotNull { it.superclass }.filter { it !in classes }.toSet()
                val loaded = FixtureDex.classes(bundle, supers)
                if (loaded.isEmpty()) break
                classes += loaded
            }
            classes[THREAD_SEEN] = ImmutableClassDef.of(ExtensionDex.classDef(THREAD_SEEN))
            classes[INSTAGRAM_CHATS] = ImmutableClassDef.of(ExtensionDex.classDef(INSTAGRAM_CHATS))
            classes
        }

        @Suppress("unused")
        private fun Method.reads() = visualCode()
    }
}
