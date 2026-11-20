/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.bubbles

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureCache
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.lock.CHAT_LOCKS
import app.morphe.patches.instagram.direct.lock.CHAT_PAUSE
import app.morphe.patches.instagram.direct.lock.CHAT_RESUME
import app.morphe.patches.instagram.direct.lock.CHAT_TRACK
import app.morphe.patches.instagram.direct.lock.PUSH_ACTION
import app.morphe.patches.instagram.direct.lock.PUSH_ALERT_ONCE
import app.morphe.patches.instagram.direct.lock.findChatLockTargets
import app.morphe.patches.instagram.direct.lock.hookChatLocks
import app.morphe.patches.instagram.direct.lock.hookFirst
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat bubbles (#747), on each declared build's own dex and on every other build of the same
 * Instagram version: the push display hands the extension the push's notifications and ids first,
 * goes on to Instagram's own code whatever the extension does, and keeps the lock's hook working
 * whichever of the two goes in first.
 */
class ChatBubblesHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_BUBBLES).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CHAT_BUBBLE is not in the extension: $declared", CHAT_BUBBLE.substringAfter("->") in declared)
    }

    @Test
    fun eachDeclaredBuildHandsThePushToTheExtensionFirst() = fixtures { bundle -> check(bundle.name, pushClasses(bundle)) }

    @Test
    fun eachOtherBuildDoesToo() {
        for (bundle in Fixtures.otherBuilds()) check(bundle.parentFile.name, pushClasses(bundle))
    }

    @Test
    fun theLockStillWorksWhenItGoesInFirst() = fixtures { bundle ->
        val context = PatchContexts.of(pushClasses(bundle).values)
        val before = stock(context.findChatBubbles().method)
        hookChatLocks(context.findChatLockTargets())
        val bubbles = context.findChatBubbles()
        bubbles.hookFirst(bubbles.hookCalling(CHAT_BUBBLE, answers = false))
        bothHooksKept(bundle.name, bubbles.method, before)
    }

    @Test
    fun theLockStillWorksWhenItGoesInSecond() = fixtures { bundle ->
        val context = PatchContexts.of(pushClasses(bundle).values)
        val bubbles = context.findChatBubbles()
        val before = stock(bubbles.method)
        bubbles.hookFirst(bubbles.hookCalling(CHAT_BUBBLE, answers = false))
        val lock = context.findChatLockTargets()
        hookChatLocks(lock)
        assertEquals("${bundle.name}: both patches hook one method", bubbles.method, lock.display)
        bothHooksKept(bundle.name, bubbles.method, before)
    }

    @Test
    fun aBuildWithoutThePushDisplayFailsThePatch() = refuses("push notification display") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == PUSH_ALERT_ONCE } }
    }

    @Test
    fun aBuildWithoutThePushLabelsFailsThePatch() = refuses("push notification labels") { classDef ->
        classDef.methods.none { method -> method.visualCode().any { it.visualString() == PUSH_ACTION } }
    }

    @Test
    fun anExtensionWithoutTheHookFailsThePatch() = refuses("the extension has no $CHAT_BUBBLES") { it.type != CHAT_BUBBLES }

    /** The patch refuses in its own name for the reason given on each declared build with these classes left out. */
    private fun refuses(reason: String, keep: (ClassDef) -> Boolean) = fixtures { bundle ->
        val context = PatchContexts.of(pushClasses(bundle).values.filter(keep))
        val refusal = assertThrows(PatchException::class.java) { context.findChatBubbles() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        assertTrue("refused in another patch's name: ${refusal.message}", refusal.message.orEmpty().startsWith("$BUBBLES_PATCH: "))
    }

    private fun check(name: String, classes: Map<String, ClassDef>) {
        val context = PatchContexts.of(classes.values)
        val found = context.findChatBubbles()
        val display = found.method
        val before = classes.mapValues { (_, classDef) -> classDef.methods.map { method -> method.visualCode().map(::text) } }
        val stock = stock(display)

        found.hookFirst(found.hookCalling(CHAT_BUBBLE, answers = false))

        val code = display.visualCode()
        assertEquals("$name: the push display saves its result", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        val offer = code.indices.single { code[it].calls(CHAT_BUBBLE) }
        assertEquals("$name: the seven reads are handed over in order", 0, (code[offer] as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the seven reads", 7, (code[offer] as RegisterRangeInstruction).registerCount)
        val recipient = code[offer - 1]
        assertEquals("$name: the last read is the account the push was sent to", Opcode.IGET_OBJECT, recipient.opcode)
        assertEquals(6, (recipient as TwoRegisterInstruction).registerA)
        assertEquals("Ljava/lang/String;", (recipient.visualReference() as FieldReference).type)
        assertFalse("$name: the hook never ends the push", code.take(offer + 1).any { it.opcode == Opcode.RETURN_VOID })
        val skips = skips(display).filter { it.location.index < offer }
        assertEquals("$name: one check for a push with no payload", 1, skips.size)
        assertEquals("$name: a push with no payload goes on to the original code", offer + 1, skips.single().target.location.index)
        assertEquals("$name: Instagram's own code follows the hook untouched", stock, kept(code.drop(offer + 1)))

        val hooked = "${display.definingClass}->${display.name}"
        for ((type, original) in before) {
            if (type == CHAT_BUBBLES || type == CHAT_LOCKS) continue
            val methods = context.mutableClassDefBy(type).methods.toList()
            assertEquals("$name: $type lost or gained a method", original.size, methods.size)
            methods.forEachIndexed { index, method ->
                if ("${method.definingClass}->${method.name}" != hooked) {
                    assertEquals("$name: native $type changed", original[index], method.visualCode().map(::text))
                }
            }
        }
    }

    /**
     * Both hooks are in once each, every check in either one goes on to what follows that hook, the
     * lock's locked-chat return is still there, and Instagram's code follows both untouched.
     */
    private fun bothHooksKept(name: String, display: MutableMethod, stock: List<String>) {
        val code = display.visualCode()
        val offer = code.indices.single { code[it].calls(CHAT_BUBBLE) }
        val track = code.indices.single { code[it].calls(CHAT_TRACK) }
        assertEquals("$name: the lock reads its answer", Opcode.MOVE_RESULT, code[track + 1].opcode)
        assertEquals("$name: a locked chat's push still returns early", Opcode.RETURN_VOID, code[track + 3].opcode)
        val ends = mapOf(offer to offer + 1, track to track + 4)
        // A check belongs to the hook it sits in, the first one that ends after it. The lock's
        // answer check comes after its own call, so the call's index alone can't say which.
        for (skip in skips(display).filter { it.location.index < ends.values.max() }) {
            val at = skip.location.index
            val end = ends.values.filter { it > at }.min()
            assertEquals("$name: the check at $at goes on past its own hook", end, skip.target.location.index)
        }
        val first = minOf(offer, track)
        val second = maxOf(offer, track)
        assertEquals("$name: the second hook starts where the first goes on to", Opcode.MOVE_OBJECT_FROM16, code[ends.getValue(first)].opcode)
        assertEquals("$name: Instagram's own code follows both hooks untouched", stock, kept(code.drop(ends.getValue(second))))
    }

    private fun skips(method: MutableMethod) = method.implementation!!.instructions.toList()
        .filterIsInstance<BuilderOffsetInstruction>().filter { it.opcode == Opcode.IF_EQZ }

    private fun Instruction.calls(hook: String) = (this as? ReferenceInstruction)?.reference?.toString() == hook

    private fun stock(method: MutableMethod) = kept(method.visualCode())

    /** The code as text with alignment nops left out and every goto read as one, so a widened branch still matches. */
    private fun kept(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }.map { instruction ->
        if (instruction.opcode in setOf(Opcode.GOTO_16, Opcode.GOTO_32)) "${Opcode.GOTO}" else text(instruction)
    }

    private fun text(instruction: Instruction): String = when (val reference = instruction.visualReference()) {
        null -> "${instruction.opcode}"
        is MethodReference, is FieldReference -> "${instruction.opcode} $reference"
        else -> "${instruction.opcode} " + (instruction.visualString() ?: reference.toString())
    }

    companion object {
        private val cached = FixtureCache<Map<String, ClassDef>>()

        /**
         * The push display and the push's labels, every type the display names, and, so the lock can go
         * in beside the bubbles, the chat screen, the chat key and both extension classes.
         */
        private fun pushClasses(bundle: File): Map<String, ClassDef> = cached.get(bundle) {
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
            classes[CHAT_BUBBLES] = ExtensionDex.classDef(CHAT_BUBBLES)
            classes
        }
    }
}
