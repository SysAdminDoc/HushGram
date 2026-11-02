/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mix in Following's hooks (#438) in Home's fetcher, its reply handler and its delivery. */
class FollowingMixHookTest {
    private val extensionType = MIX_CURSOR.substringBefore("->")
    private val hooks = listOf(MIX_CURSOR, MIX_SOURCE_SET, MIX_SOURCE, MIX_NEXT_CURSOR, MIX_MORE, MIX_REWOUND)

    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(extensionType).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in hooks) assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
    }

    /** A build without Home's fetcher as the patch knows it warns and keeps the other switches. */
    @Test
    fun aBuildWithoutHomesFetcherOnlyWarns() {
        val none = PatchContexts.of(listOf(ExtensionDex.classDef(extensionType)))
        val failure = assertThrows(PatchException::class.java) { none.findFollowingMix() }
        assertTrue(failure.message, failure.message!!.contains("no method loads $HOME_FETCH"))
        assertFalse(none.mixInFollowingOrWarn())
    }

    /**
     * In every build the fetcher's cursor, the request's flag and paging source, the reply's cursor
     * and "more" writes and the delivery's rewind are found and hooked, each hook's answer goes back
     * into the register Instagram goes on to use, the jumps onto the cursor land on its hook, and
     * the methods' own code is otherwise untouched.
     */
    @Test
    fun everyBuildMixesFollowingIn() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the others", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val name = bundle.parentFile?.name ?: bundle.name
            val classes = LinkedHashMap<String, ClassDef>()
            for (anchor in listOf(HOME_FETCH, HOME_REPLY, HOME_NOTHING_NEW)) {
                FixtureDex.classesLoading(bundle, anchor).forEach { classes.putIfAbsent(it.type, it) }
            }
            classes[extensionType] = ExtensionDex.classDef(extensionType)
            val context = PatchContexts.of(classes.values.toList())

            val targets = context.findFollowingMix()
            val fetch = targets.fetch
            val fetchBefore = fetch.method.code()
            val replyBefore = targets.reply.method.code()
            val rewindBefore = targets.rewind.method.code()
            val locals = fetch.method.localRegisterCount()
            val onto = fetchBefore.indices.filter { at -> fetchBefore[at] is OffsetInstruction && fetchBefore.targetOf(at) == fetch.merge }
            assertTrue("$name: something jumps onto the cursor", onto.isNotEmpty())
            context.mixInFollowing(targets)

            val fetcher = context.mutated(fetch.method).code()
            assertEquals("$name: the feed read", Opcode.IGET_OBJECT, fetcher[fetch.merge].opcode)
            assertEquals("$name: the feed read's field", fetch.feedField.toString(), fetcher[fetch.merge].referenceText())
            val feed = (fetcher[fetch.merge] as TwoRegisterInstruction).registerA
            assertTrue("$name: the feed lives in a local", feed < locals)
            assertTrue(
                "$name: the feed register isn't one the hook reads",
                feed !in listOf(fetch.cursorRegister, fetch.headRegister, fetch.fetcherRegister),
            )
            val call = fetcher[fetch.merge + 1] as FiveRegisterInstruction
            assertEquals("$name: the cursor hook", MIX_CURSOR, fetcher[fetch.merge + 1].referenceText())
            assertEquals(
                "$name: the cursor hook is handed the cursor, the head flag and the feed",
                listOf(3, fetch.cursorRegister, fetch.headRegister, feed),
                listOf(call.registerCount, call.registerC, call.registerD, call.registerE),
            )
            assertAnswered(name, fetcher, fetch.merge + 2, Opcode.MOVE_RESULT_OBJECT, fetch.cursorRegister)
            for (at in onto) {
                val moved = if (at > fetch.merge) at + 3 else at
                assertEquals("$name: the jump at $at lands on the cursor hook", fetch.merge, fetcher.targetOf(moved))
            }
            assertHooked(name, fetcher, MIX_SOURCE_SET, Opcode.MOVE_RESULT, Opcode.IPUT_BOOLEAN, fetch.setRegister)
            assertHooked(name, fetcher, MIX_SOURCE, Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT, fetch.sourceRegister)
            assertEquals(
                "$name: the fetcher's own code",
                fetchBefore.described(),
                fetcher.filterIndexed { at, _ -> at != fetch.merge }.withoutHooks().described(),
            )

            val reply = context.mutated(targets.reply.method).code()
            assertHooked(name, reply, MIX_NEXT_CURSOR, Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT, targets.reply.cursorRegister)
            assertHooked(name, reply, MIX_MORE, Opcode.MOVE_RESULT, Opcode.IPUT_BOOLEAN, targets.reply.moreRegister)
            assertEquals("$name: the reply handler's own code", replyBefore.described(), reply.withoutHooks().described())

            val rewind = context.mutated(targets.rewind.method).code()
            assertHooked(name, rewind, MIX_REWOUND, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, targets.rewind.register)
            assertEquals("$name: the delivery's own code", rewindBefore.described(), rewind.withoutHooks().described())
        }
    }

    /** One `invoke-static/range` of [hook] on [register] in [code], its answer back into it, then Instagram's [next] reading it. */
    private fun assertHooked(name: String, code: List<Instruction>, hook: String, result: Opcode, next: Opcode, register: Int) {
        val calls = code.indices.filter { code[it].referenceText() == hook }
        assertEquals("$name: one call of $hook", 1, calls.size)
        val at = calls.single()
        val range = code[at] as RegisterRangeInstruction
        assertEquals(
            "$name: $hook's operand",
            listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
            listOf(code[at].opcode, range.startRegister, range.registerCount),
        )
        assertAnswered(name, code, at + 1, result, register)
        assertEquals("$name: Instagram reads $hook's answer next", next, code[at + 2].opcode)
        val read = when (val instruction = code[at + 2]) {
            is TwoRegisterInstruction -> instruction.registerA
            is OneRegisterInstruction -> instruction.registerA
            else -> -1
        }
        assertEquals("$name: from the same register", register, read)
    }

    private fun assertAnswered(name: String, code: List<Instruction>, at: Int, result: Opcode, register: Int) {
        assertEquals(
            "$name: the answer at $at",
            listOf(result, register),
            listOf(code[at].opcode, (code[at] as OneRegisterInstruction).registerA),
        )
    }

    /** [this] without each hook call and the move-result after it. */
    private fun List<Instruction>.withoutHooks(): List<Instruction> {
        val calls = indices.filter { this[it].referenceText() in hooks }.flatMap { listOf(it, it + 1) }.toSet()
        return filterIndexed { at, _ -> at !in calls }
    }

    private fun List<Instruction>.described(): List<String> = filter { it.opcode != Opcode.NOP }.map { it.describe() }

    private fun BytecodePatchContext.mutated(method: Method): Method =
        mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
                it.returnType == method.returnType
        }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    /** The index of the instruction the branch at [at] jumps to. */
    private fun List<Instruction>.targetOf(at: Int): Int {
        val addresses = runningFold(0) { address, instruction -> address + instruction.codeUnits }
        return addresses.indexOf(addresses[at] + (this[at] as OffsetInstruction).codeOffset)
    }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.describe(): String = buildString {
        append(opcode.toString())
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        if (this@describe is RegisterRangeInstruction) append(" {v$startRegister..${startRegister + registerCount}}")
        referenceText()?.let { append(" $it") }
    }
}
