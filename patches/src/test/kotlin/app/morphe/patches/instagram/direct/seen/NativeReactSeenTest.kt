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
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mark a chat seen when you react, on each build's own reaction sender: the hook goes in right
 * after the sender looks the message up, and hands the extension the registers the stock code
 * holds the account, the chat key, the message, its id and the added flag in.
 */
class NativeReactSeenTest {
    @Test fun declaredBuildsHookTheReactionSender() = fixtures { check(it) }

    @Test fun eachOtherBuildHooksTheReactionSender() = otherBuilds { check(it) }

    private fun check(bundle: File) {
        val classes = NativeMarkReadTest.markReadClasses(bundle) + reactClasses(bundle)
        val context = PatchContexts.of(classes.values)
        val marks = context.findMarkRead(context.findThreadSeen())
        val found = context.findReactSeen(marks)
        assertNotNull("${bundle.parentFile.name}: the reaction sender resolves", found)
        found!!
        val stock = found.send.visualCode()

        // The registers come from the stock instructions around the lookup.
        val lookup = stock[found.at - 2]
        assertEquals(Opcode.INVOKE_INTERFACE, lookup.opcode)
        assertEquals(listOf(THREAD_KEY, "Ljava/lang/String;"), (lookup.visualReference() as MethodReference).parameterTypes.map(Any::toString))
        assertEquals(lookup.namedRegisters()[1], found.key)
        assertEquals(lookup.namedRegisters()[2], found.item)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, stock[found.at - 1].opcode)
        assertEquals((stock[found.at - 1] as OneRegisterInstruction).registerA, found.message)
        val sessionCall = stock[found.at - 4]
        assertEquals(Opcode.INVOKE_STATIC, sessionCall.opcode)
        assertEquals(listOf(USER_SESSION), (sessionCall.visualReference() as MethodReference).parameterTypes.map(Any::toString))
        assertEquals(sessionCall.namedRegisters().single(), found.session)
        assertEquals("the added flag is the last parameter", found.send.implementation!!.registerCount - 1, found.created)

        val next = stock[found.at].opcode
        writeReactSeen(found)
        val after = found.send.visualCode()
        val hooked = found.at + found.scratch.size
        assertEquals("the hook calls the extension after its copies", REACTED, after[hooked].visualReference()?.toString())
        assertEquals("the extension is called once", 1, after.count { it.visualReference()?.toString() == REACTED })
        assertEquals("the stock instruction after the lookup follows the hook", next, after[hooked + 1].opcode)
        // Each hook register is the stock one, or a copy of it made just before the call.
        val copies = after.subList(found.at, hooked)
        assertTrue("only copies go in front of the call", copies.all { it.opcode in COPIES })
        val source = copies.associate { (it as TwoRegisterInstruction).registerA to it.registerB }
        val passed = after[hooked].namedRegisters().map { source[it] ?: it }
        assertEquals(listOf(found.session, found.key, found.message, found.item, found.created), passed)
        assertTrue("every passed register fits the call", after[hooked].namedRegisters().all { it <= 15 })
        assertEquals("Instagram's own code is kept", kinds(stock), kinds(after.take(found.at) + after.drop(hooked + 1)))
    }

    private companion object {
        private val cached = FixtureCache<Map<String, ClassDef>>()
        private val COPIES = setOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16, Opcode.MOVE_FROM16, Opcode.MOVE_16)

        /** Opcodes without padding, a widened goto read as the goto it was. */
        private fun kinds(code: List<Instruction>) = code.map {
            if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode
        }.filter { it != Opcode.NOP }

        /** Shaped like a reaction sender: an instance method ending in the added flag, with the chat key's message lookup. */
        private fun Method.sendsReaction() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
            parameterTypes.map(Any::toString).let { it.count { type -> type == THREAD_KEY } == 1 && it.lastOrNull() == "Z" } &&
            visualCode().any { instruction ->
                instruction.opcode == Opcode.INVOKE_INTERFACE &&
                    (instruction.visualReference() as? MethodReference)?.parameterTypes?.map(Any::toString) == listOf(THREAD_KEY, "Ljava/lang/String;")
            }

        /**
         * The reaction sender's class, the pools it asks for its strings, the class its message
         * lookup answers with and every class that one extends, and the extension's hooks.
         */
        fun reactClasses(bundle: File): Map<String, ClassDef> = cached.get(bundle) {
            val loading = FixtureDex.classesLoading(bundle, "created")
            val senders = loading.filter { candidate -> candidate.methods.any { it.sendsReaction() } }
            assertTrue("${bundle.parentFile.name}: a class shaped like the reaction sender", senders.isNotEmpty())
            val classes = senders.associateBy { it.type }.toMutableMap()
            val needs = mutableSetOf<String>()
            for (method in senders.flatMap { it.methods }.filter { it.sendsReaction() }) {
                for (instruction in method.visualCode()) {
                    val call = instruction.visualReference() as? MethodReference ?: continue
                    // A pool asked for a string by number, and what the message lookup answers.
                    if (instruction.opcode == Opcode.INVOKE_STATIC && call.parameterTypes.map(Any::toString) == listOf("I") &&
                        call.returnType == "Ljava/lang/String;"
                    ) needs += call.definingClass
                    if (instruction.opcode == Opcode.INVOKE_INTERFACE &&
                        call.parameterTypes.map(Any::toString) == listOf(THREAD_KEY, "Ljava/lang/String;")
                    ) needs += call.returnType
                }
            }
            var wanted: Set<String> = needs
            while (true) {
                val loaded = FixtureDex.classes(bundle, wanted.filter { it.startsWith("L") && it !in classes }.toSet())
                if (loaded.isEmpty()) break
                classes += loaded
                wanted = loaded.values.mapNotNull { it.superclass }.toSet()
            }
            classes[THREAD_SEEN] = ImmutableClassDef.of(ExtensionDex.classDef(THREAD_SEEN))
            classes
        }
    }
}
