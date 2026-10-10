/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.direct.seen.visualString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hidden chats, on each declared build's own dex and on every other build of the same Instagram
 * version: the thread store's two readers of the sorted thread summaries hand their list to the
 * extension on the way out, and the extension's bridge from a summary to its chat's thread id is
 * written from Instagram's own summary type and chat key.
 */
class HiddenChatHookTest {
    @Test
    fun theFilterAndTheBridgeAreInTheExtension() {
        val declared = ExtensionDex.classDef(HIDDEN_CHATS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDDEN_FILTER is not in the extension: $declared", HIDDEN_FILTER.substringAfter("->") in declared)
        assertTrue("no thread summary bridge: $declared", "threadId(Ljava/lang/Object;)Ljava/lang/String;" in declared)
    }

    @Test
    fun eachDeclaredBuildFiltersItsInbox() = fixtures { bundle -> check(bundle.name, summaryClasses(bundle)) }

    @Test
    fun eachOtherBuildDoesToo() {
        for (bundle in Fixtures.otherBuilds()) check(bundle.parentFile.name, summaryClasses(bundle))
    }

    @Test
    fun dexBackedInstructionReReadsResolveTheSameTargets() = fixtures { bundle ->
        val types = summaryClasses(bundle).keys - HIDDEN_CHATS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(HIDDEN_CHATS))
        val found = context.findHiddenChatTargets()
        hideChatsFromInbox(found)
        for (list in found.lists) {
            assertEquals(Opcode.INVOKE_STATIC_RANGE, list.method.visualCode()[list.returnAt].opcode)
        }
    }

    @Test
    fun aBuildWithoutTheChatKeyFailsThePatch() = refuses("$THREAD_KEY is missing") { it.type != THREAD_KEY }

    @Test
    fun aBuildWithoutTheThreadStoreFailsThePatch() = refuses("expected two readers") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == THREAD_SUMMARIES } }
    }

    /** The patch refuses for the reason given on the first declared build with these classes left out, before anything changes. */
    private fun refuses(reason: String, keep: (ClassDef) -> Boolean) = fixtures { bundle ->
        val classes = summaryClasses(bundle).values.filter(keep)
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.findHiddenChatTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun check(name: String, classes: Map<String, ClassDef>) {
        val context = PatchContexts.of(classes.values)
        val found = context.findHiddenChatTargets()
        assertEquals("$name: both readers", 2, found.lists.size)
        assertEquals("$name: one store", 1, found.lists.map { it.method.definingClass }.distinct().size)
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val hooked = (found.lists.map { it.method } + found.bridge).map { "${it.definingClass}->${it.name}" }
        val originals = found.lists.map { list -> list.method.visualCode().map(::text) }

        hideChatsFromInbox(found)

        found.lists.forEachIndexed { index, list ->
            val code = list.method.visualCode()
            val register = list.register
            val call = code[list.returnAt]
            assertEquals("$name: the return is replaced by the filter", HIDDEN_FILTER, (call as ReferenceInstruction).reference.toString())
            assertEquals("$name: the filter is handed the list", register,
                (call as com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction).startRegister)
            assertEquals("$name: the answer goes back in the same register", Opcode.MOVE_RESULT_OBJECT, code[list.returnAt + 1].opcode)
            assertEquals(register, (code[list.returnAt + 1] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA)
            assertEquals("$name: the list is still returned", Opcode.RETURN_OBJECT, code[list.returnAt + 2].opcode)
            assertEquals(register, (code[list.returnAt + 2] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA)
            assertEquals("$name: nothing else in the reader moved", originals[index].size + 2, code.size)
            assertEquals(originals[index].take(list.returnAt), code.take(list.returnAt).map(::text))
        }

        val bridge = found.bridge.visualCode()
        assertEquals("$name: the bridge casts to the summary", Opcode.CHECK_CAST, bridge[0].opcode)
        assertTrue("$name: the bridge reads the key's thread id",
            bridge.any { (it.visualReference() as? FieldReference)?.let { field -> field.definingClass == THREAD_KEY && field.type == "Ljava/lang/String;" } == true })
        assertTrue("$name: the bridge asks the summary for its key",
            bridge.any { (it.visualReference() as? MethodReference)?.returnType == THREAD_KEY })
        assertEquals("$name: the bridge answers", Opcode.RETURN_OBJECT, bridge.last { it.opcode == Opcode.RETURN_OBJECT }.opcode)

        for ((type, original) in before) {
            if (type == HIDDEN_CHATS) continue
            val methods = context.mutableClassDefBy(type).methods.toList()
            assertEquals("$name: $type lost or gained a method", original.size, methods.size)
            methods.forEachIndexed { index, method ->
                if (hooked.none { it == "${method.definingClass}->${method.name}" }) {
                    assertEquals("$name: native $type changed", original[index], method.visualCode().map(::text))
                }
            }
        }
    }

    private fun text(instruction: Instruction): String = when (val reference = instruction.visualReference()) {
        null -> instruction.opcode.name
        is MethodReference, is FieldReference -> "${instruction.opcode.name} $reference"
        else -> instruction.opcode.name + " " + (instruction.visualString() ?: reference.toString())
    }

    companion object {
        private val cached = mutableMapOf<String, Map<String, ClassDef>>()

        /** The thread store, the chat key, every type the store's readers load fields of, and the extension's class. */
        private fun summaryClasses(bundle: File): Map<String, ClassDef> = cached.getOrPut(bundle.absolutePath) {
            val classes = mutableMapOf<String, ClassDef>()
            FixtureDex.classesHolding(bundle, THREAD_SUMMARIES).forEach { classes[it.type] = it }
            val named = mutableSetOf(THREAD_KEY)
            for (classDef in classes.values.toList()) {
                for (method in classDef.methods) {
                    if (method.visualCode().none { it.visualString() == THREAD_SUMMARIES }) continue
                    method.visualCode().mapNotNullTo(named) { (it.visualReference() as? FieldReference)?.type }
                }
            }
            classes += FixtureDex.classes(bundle, named.filter { it.startsWith("L") && it !in classes }.toSet())
            classes[HIDDEN_CHATS] = ExtensionDex.classDef(HIDDEN_CHATS)
            classes
        }
    }
}
