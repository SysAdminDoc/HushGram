/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.direct.seen.visualString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock single chats, on each declared build's own dex and on every other build of the same Instagram
 * version: the chat screen's onResume and onPause tell the extension which chat is in front, the push
 * display hands the extension the notifications and the ids its push carries before it posts them,
 * and the extension's thread id bridge is written from Instagram's own controller and chat key.
 */
class ChatLockHookTest {
    @Test
    fun theHooksAndTheBridgeAreInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_LOCKS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(CHAT_OPENED, CHAT_CLOSED, CHAT_TRACK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        assertTrue("no thread id bridge: $declared", "threadId(Ljava/lang/Object;)Ljava/lang/String;" in declared)
    }

    @Test
    fun eachDeclaredBuildTellsTheExtensionWhichChatIsInFront() = fixtures { bundle -> check(bundle.name, chatClasses(bundle)) }

    @Test
    fun eachOtherBuildDoesToo() {
        for (bundle in Fixtures.otherBuilds()) check(bundle.parentFile.name, chatClasses(bundle))
    }

    @Test
    fun dexBackedInstructionReReadsResolveTheSameTargets() = fixtures { bundle ->
        val types = chatClasses(bundle).keys - CHAT_LOCKS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(CHAT_LOCKS))
        val found = context.findChatLockTargets()
        hookChatLocks(found)
        assertEquals(Opcode.MOVE_OBJECT_FROM16, found.display.visualCode().first().opcode)
        assertEquals(CHAT_OPENED, (found.resume.visualCode().first() as ReferenceInstruction).reference.toString())
        assertEquals(CHAT_CLOSED, (found.pause.visualCode().first() as ReferenceInstruction).reference.toString())
    }

    @Test
    fun aBuildWithoutTheChatKeyFailsThePatch() = refuses("$THREAD_KEY is missing") { it.type != THREAD_KEY }

    @Test
    fun aBuildWithoutThePushDisplayFailsThePatch() = refuses("push notification display") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == PUSH_ALERT_ONCE } }
    }

    @Test
    fun aBuildWithoutTheChatScreenFailsThePatch() = refuses("chat screen onResume") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == CHAT_RESUME } }
    }

    @Test
    fun aBuildWithoutThePushLabelsFailsThePatch() = refuses("push notification labels") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == PUSH_ACTION } }
    }

    /** The patch refuses for the reason given on the first declared build with these classes left out, before anything changes. */
    private fun refuses(reason: String, keep: (ClassDef) -> Boolean) = fixtures { bundle ->
        val classes = chatClasses(bundle).values.filter(keep)
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.findChatLockTargets() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun check(name: String, classes: Map<String, ClassDef>) {
        val context = PatchContexts.of(classes.values)
        val found = context.findChatLockTargets()
        val screen = found.resume.definingClass
        assertEquals("$name: onResume and onPause", screen, found.pause.definingClass)
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val hooked = setOf(found.resume, found.pause, found.display, found.bridge).map { "${it.definingClass}->${it.name}" }

        hookChatLocks(found)

        for ((method, hook) in listOf(found.resume to CHAT_OPENED, found.pause to CHAT_CLOSED)) {
            val first = method.visualCode().first()
            assertEquals("$name: ${method.name} calls $hook", hook, (first as ReferenceInstruction).reference.toString())
            assertEquals("$name: ${method.name} hands over this", method.implementation!!.registerCount - 1,
                (first as RegisterRangeInstruction).startRegister)
            assertEquals("$name: ${method.name} hands over one register", 1, first.registerCount)
        }
        val display = found.display.visualCode()
        assertEquals("$name: the push display saves its result", Opcode.MOVE_OBJECT_FROM16, display[0].opcode)
        val track = display.indices.single { (display[it] as? ReferenceInstruction)?.reference?.toString() == CHAT_TRACK }
        assertEquals("$name: the six reads are handed over in order", 0, (display[track] as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the six reads", 6, (display[track] as RegisterRangeInstruction).registerCount)
        val skip = found.display.implementation!!.instructions.toList().filterIsInstance<BuilderOffsetInstruction>()
            .first { it.opcode == Opcode.IF_EQZ }
        assertEquals("$name: a push with no payload goes on to the original code", track + 1, skip.target.location.index)

        val bridge = found.bridge.visualCode()
        assertEquals("$name: the bridge casts to the chat screen", Opcode.CHECK_CAST, bridge[0].opcode)
        assertEquals(screen, (bridge[0].visualReference() as com.android.tools.smali.dexlib2.iface.reference.TypeReference).type)
        assertTrue("$name: the bridge reads the key's thread id",
            bridge.any { (it.visualReference() as? FieldReference)?.let { field -> field.definingClass == THREAD_KEY && field.type == "Ljava/lang/String;" } == true })
        assertEquals("$name: the bridge answers", Opcode.RETURN_OBJECT, bridge.last { it.opcode == Opcode.RETURN_OBJECT }.opcode)

        for ((type, original) in before) {
            if (type == CHAT_LOCKS) continue
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

        /**
         * The chat screen, the push display and the push's labels, the chat key, every type the screen
         * and the display name, and the extension's chat lock class.
         */
        private fun chatClasses(bundle: File): Map<String, ClassDef> = cached.getOrPut(bundle.absolutePath) {
            val classes = mutableMapOf<String, ClassDef>()
            for (anchor in listOf(CHAT_RESUME, CHAT_PAUSE, PUSH_ALERT_ONCE, PUSH_ACTION)) {
                FixtureDex.classesHolding(bundle, anchor).forEach { classes[it.type] = it }
            }
            val named = mutableSetOf(THREAD_KEY)
            for (classDef in classes.values.toList()) {
                named += classDef.fields.map { it.type }
                for (method in classDef.methods) {
                    if (method.visualCode().any { it.visualString() == PUSH_ALERT_ONCE }) named += method.parameterTypes.map(Any::toString)
                }
            }
            classes += FixtureDex.classes(bundle, named.filter { it.startsWith("L") && it !in classes }.toSet())
            classes[CHAT_LOCKS] = ExtensionDex.classDef(CHAT_LOCKS)
            classes
        }
    }
}
