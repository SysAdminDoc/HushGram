/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridges every switch keyed on a chat's Reels viewer reads it through (#804): where the viewer
 * was opened, and the id of the chat its config holds, which the chat data's toString appends right
 * after ", threadId=" (457's ClipsViewerDirectData->A04).
 */
class ViewerChatPatchTest {
    private val text = "Ljava/lang/String;"
    private val append = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"

    @Test
    fun theBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(VIEWER_CHAT).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (bridge in listOf("viewerSource(Ljava/lang/Object;)Ljava/lang/Object;", "chatThread(Ljava/lang/Object;)Ljava/lang/String;")) {
            assertTrue("$bridge is not in the extension: $declared", bridge in declared)
        }
    }

    @Test
    fun theChatsIdIsWhatToStringAppendsAfterItsLabel() {
        for ((copies, pooled) in listOf(false to false, true to false, false to true)) {
            val context = context(copies = copies, pooled = pooled)
            val found = context.findViewerChat()
            assertEquals("$VIEWER_CONFIG->source:$VIEWER_SOURCE", found.source)
            assertEquals("$VIEWER_CONFIG->direct:$DIRECT_DATA", found.direct)
            assertEquals("copied: $copies, pooled: $pooled", "$DIRECT_DATA->thread:$text", found.thread)

            context.readViewerChats(found)
            val bridges = context.mutableClassDefBy(VIEWER_CHAT).methods.groupBy { it.name }
            val source = bridges.getValue("viewerSource").single().code()
            assertEquals(listOf(VIEWER_CONFIG, found.source, null), source.take(3).map { it.text() })
            assertEquals(Opcode.RETURN_OBJECT, source[2].opcode)
            val thread = bridges.getValue("chatThread").single().code()
            assertEquals(listOf(VIEWER_CONFIG, found.direct, null, null, null, found.thread, null), thread.take(7).map { it.text() })
            assertEquals(
                "a config holding no chat answers null",
                listOf(Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN_OBJECT), thread.subList(2, 5).map { it.opcode },
            )
            val address = IntArray(thread.size + 1)
            thread.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
            assertEquals("the chat goes on to its id", 5, address.indexOf(address[2] + (thread[2] as OffsetInstruction).codeOffset))
        }
    }

    @Test
    fun aBuildItCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            context(sources = 2) to "$VIEWER_CONFIG holds 2 $VIEWER_SOURCE fields, not one",
            context(directs = 0) to "$VIEWER_CONFIG holds 0 $DIRECT_DATA fields, not one",
            context(labels = 0) to "names \"$THREAD_ID_LABEL\" 0 times, not once",
            context(labels = 2) to "names \"$THREAD_ID_LABEL\" 2 times, not once",
            context(labels = 2, pooled = true) to "names \"$THREAD_ID_LABEL\" 2 times, not once",
            context(appendsField = false) to "isn't one of its own fields",
            context(branches = true) to "branches between reading the chat's id and appending it",
            context(foreign = true) to "not a text field of its own",
        )
        for ((context, expected) in cases) {
            val refusal = assertThrows(PatchException::class.java) { context.findViewerChat() }
            assertTrue("${refusal.message} should name $expected", refusal.message!!.contains(expected))
            assertTrue(refusal.message!!.startsWith("Reels viewers from chats: "))
            val stubs = context.mutableClassDefBy(VIEWER_CHAT).methods.single { it.name == "chatThread" }.code()
            assertEquals("nothing changed", ExtensionDex.classDef(VIEWER_CHAT).methods.single { it.name == "chatThread" }
                .implementation!!.instructions.count(), stubs.size)
        }
    }

    /**
     * Shaped like 457's ClipsViewerDirectData.toString: each field appended after its label. The
     * config holds [sources] viewer sources and [directs] chat data fields, the label shows [labels]
     * times, and the chat's id is appended from its own field ([appendsField]), through a copy
     * ([copies]), with a jump onto the append ([branches]) or from another class's field ([foreign]).
     * A [pooled] label is asked of a Redex string pool by number, as 386314286 has it.
     */
    private fun context(
        sources: Int = 1,
        directs: Int = 1,
        labels: Int = 1,
        appendsField: Boolean = true,
        copies: Boolean = false,
        branches: Boolean = false,
        foreign: Boolean = false,
        pooled: Boolean = false,
    ): BytecodePatchContext {
        val owner = if (foreign) VIEWER_CONFIG else DIRECT_DATA
        val read = when {
            !appendsField -> "const-string v1, \"none\""
            copies -> "iget-object v2, p0, $owner->thread:$text\nmove-object v1, v2"
            else -> "iget-object v1, p0, $owner->thread:$text"
        }
        val jump = if (branches) "if-eqz v0, :append\nnop\n:append" else ""
        val load = if (pooled) "const/4 v1, 0x1\ninvoke-static {v1}, $POOL->A00(I)$text\nmove-result-object v1" else "const-string v1, \"$THREAD_ID_LABEL\""
        val label = List(labels) { "$load\ninvoke-virtual {v0, v1}, $append" }
        val body = """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "ClipsViewerDirectData(otherId="
            invoke-virtual {v0, v1}, $append
            iget-object v1, p0, $DIRECT_DATA->other:$text
            invoke-virtual {v0, v1}, $append
            ${label.firstOrNull() ?: ""}
            $read
            $jump
            invoke-virtual {v0, v1}, $append
            ${label.drop(1).joinToString("\n")}
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val toString = method(DIRECT_DATA, "toString", text, 3, flags, body)
        val poolFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val ask = method(POOL, "A00", text, 1, poolFlags, POOL_CASES, listOf("I"))
        val configFields = List(sources) { ImmutableField(VIEWER_CONFIG, if (it == 0) "source" else "source$it", VIEWER_SOURCE, flags, null, null, null) } +
            List(directs) { ImmutableField(VIEWER_CONFIG, if (it == 0) "direct" else "direct$it", DIRECT_DATA, flags, null, null, null) } +
            ImmutableField(VIEWER_CONFIG, "thread", text, flags, null, null, null) +
            ImmutableField(VIEWER_CONFIG, "NONE", VIEWER_CONFIG, flags or AccessFlags.STATIC.value, null, null, null)
        val dataFields = listOf("thread", "other").map { ImmutableField(DIRECT_DATA, it, text, flags, null, null, null) }
        val classes: List<ClassDef> = listOf(
            classDef(VIEWER_CONFIG, emptyList(), configFields),
            classDef(DIRECT_DATA, listOf(toString), dataFields),
            classDef(POOL, listOf(ask), emptyList()),
            ImmutableClassDef.of(ExtensionDex.classDef(VIEWER_CHAT)),
        )
        return PatchContexts.of(classes)
    }

    private fun method(
        owner: String,
        name: String,
        returns: String,
        registers: Int,
        flags: Int,
        body: String,
        parameters: List<String> = emptyList(),
    ): Method {
        val count = registers + parameters.size + if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(count, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", emptyList(), null, null, fields, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.text(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val POOL = "Lfixture/Pool;"

        /** A Redex pool's switch: 0 the first label, 1 the chat id's. */
        val POOL_CASES = """
            packed-switch p0, :cases
            const/4 v0, 0x0
            return-object v0
            :other
            const-string v0, "ClipsViewerDirectData(otherId="
            return-object v0
            :thread
            const-string v0, "$THREAD_ID_LABEL"
            return-object v0
            :cases
            .packed-switch 0x0
                :other
                :thread
            .end packed-switch
        """
    }
}
