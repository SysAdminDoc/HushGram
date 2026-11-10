/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reminder

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the Community Standards reminder (#588): the chat with one person and the public chat each
 * ask the extension before they read Instagram's answer, on every build, and anything the patch
 * can't pick out fails it before an instruction changes.
 */
class HideKindnessReminderHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(MAY_SHOW, TIMES_SHOWN)) {
            val name = hook.substringAfter("->").substringBefore("(")
            val method = ExtensionDex.classDef(KINDNESS_REMINDER).methods.single { it.name == name }
            assertEquals("$name takes an int", listOf("I"), method.parameterTypes.map(CharSequence::toString))
            assertEquals("$name answers an int", "I", method.returnType)
            assertTrue(name, AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }

    @Test
    fun theChatWithOnePersonAsksFirst() {
        val method = PatchContexts.of(listOf(onePerson())).findOnePersonReminder()
        val stock = method.code().map(::text)
        keepOnePersonReminderOut(method)
        assertAsks("stand-in", method.code(), 0, MAY_SHOW, FLAG)
        assertEquals("the rest stays", stock, method.code().drop(2).map(::text))
    }

    @Test
    fun aMissingOrDoubledOnePersonCallbackFailsThePatch() {
        refuses("found 0", listOf(onePerson(event = false))) { findOnePersonReminder() }
        refuses("found 2", listOf(onePerson(), onePerson("Lfixture/Other;"))) { findOnePersonReminder() }
    }

    @Test
    fun aOnePersonCallbackOfAnotherShapeFailsThePatch() {
        refuses("doesn't take one boolean", listOf(onePerson(parameters = listOf("I")))) { findOnePersonReminder() }
        refuses("is static", listOf(onePerson(static = true))) { findOnePersonReminder() }
        refuses("doesn't start by testing", listOf(onePerson(tests = false))) { findOnePersonReminder() }
    }

    @Test
    fun thePublicChatAsksBeforeTheCompare() {
        val count = PatchContexts.of(listOf(publicChat())).findPublicChatCount()
        assertEquals("the count's register", COUNT, count.register)
        val stock = count.method.code().map(::text)
        keepPublicChatReminderOut(count)
        val code = count.method.code()
        assertAsks("stand-in", code, count.index, TIMES_SHOWN, COUNT)
        assertEquals("the rest stays", stock, code.take(count.index).map(::text) + code.drop(count.index + 2).map(::text))
    }

    @Test
    fun aMissingOrDoubledPublicChatTaskFailsThePatch() {
        refuses("found 0", listOf(publicChat(marker = false))) { findPublicChatCount() }
        refuses("found 2", listOf(publicChat(), publicChat("Lfixture/OtherTask;"))) { findPublicChatCount() }
    }

    @Test
    fun aPublicChatTaskOfAnotherShapeFailsThePatch() {
        refuses("doesn't read the count", listOf(publicChat(reads = false))) { findPublicChatCount() }
        refuses("doesn't compare the count", listOf(publicChat(compares = false))) { findPublicChatCount() }
    }

    /** In each declared build: both places ask the extension, with Instagram's own code left around them. */
    @Test
    fun eachDeclaredBuildAsksFirst() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildAsksFirstToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val reminder = PatchContexts.of(FixtureDex.classesLoading(bundle, ONE_PERSON_EVENT)).findOnePersonReminder()
        val flag = reminder.implementation!!.registerCount - 1
        val stock = reminder.code().filter { it.opcode != Opcode.NOP }.map(::text)
        keepOnePersonReminderOut(reminder)
        assertAsks(label, reminder.code(), 0, MAY_SHOW, flag)
        assertEquals("$label: the rest stays", stock, reminder.code().drop(2).filter { it.opcode != Opcode.NOP }.map(::text))

        val count = PatchContexts.of(FixtureDex.classesLoading(bundle, PUBLIC_CHATS_SHOWN)).findPublicChatCount()
        val before = count.method.code().filter { it.opcode != Opcode.NOP }.map(::text)
        val index = count.index
        keepPublicChatReminderOut(count)
        val code = count.method.code()
        assertAsks(label, code, index, TIMES_SHOWN, count.register)
        val rest = (code.take(index) + code.drop(index + 2)).filter { it.opcode != Opcode.NOP }.map(::text)
        assertEquals("$label: the task's code stays", before, rest)
    }

    private fun assertAsks(label: String, code: List<Instruction>, at: Int, hook: String, register: Int) {
        val ask = code[at]
        assertEquals("$label: the call", Opcode.INVOKE_STATIC_RANGE, ask.opcode)
        assertEquals("$label: the hook", hook, (ask as ReferenceInstruction).reference.toString())
        assertEquals("$label: what it's handed", register, (ask as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, ask.registerCount)
        val answer = code[at + 1]
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, answer.opcode)
        assertEquals("$label: replaces what it asked about", register, (answer as OneRegisterInstruction).registerA)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>, patch: BytecodePatchContext.() -> Unit) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.patch() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    private fun onePerson(
        type: String = "Lfixture/Reminder;",
        event: Boolean = true,
        parameters: List<String> = listOf("Z"),
        static: Boolean = false,
        tests: Boolean = true,
    ): ClassDef {
        val code = mutableListOf<ImmutableInstruction>()
        code += if (tests) ImmutableInstruction21t(Opcode.IF_EQZ, FLAG, 4) else ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("first"))
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(if (event) ONE_PERSON_EVENT else "another"))
        code += ImmutableInstruction10x(Opcode.RETURN_VOID)
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        return classOf(type, method(type, "show", parameters, flags, 3, code))
    }

    private fun publicChat(
        type: String = "Lfixture/ChatTask;",
        marker: Boolean = true,
        reads: Boolean = true,
        compares: Boolean = true,
    ): ClassDef {
        val read = ImmutableMethodReference("Lfixture/Prefs;", "getInt", if (reads) listOf("Ljava/lang/String;", "I") else listOf("Ljava/lang/String;"), "I")
        val code = listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11n(Opcode.CONST_4, ONE, 1),
            ImmutableInstruction21c(Opcode.CONST_STRING, COUNT, ImmutableStringReference(if (marker) PUBLIC_CHATS_SHOWN else "another")),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, 4, COUNT, 0, 0, 0, read),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, COUNT),
            if (compares) ImmutableInstruction22t(Opcode.IF_GE, COUNT, ONE, 3) else ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction10x(Opcode.NOP),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return classOf(type, method(type, "run", emptyList(), AccessFlags.PUBLIC.value, 5, code))
    }

    private fun method(type: String, name: String, parameters: List<String>, flags: Int, registers: Int, code: List<ImmutableInstruction>) =
        ImmutableMethod(
            type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
            ImmutableMethodImplementation(registers, code, null, null),
        )

    private fun classOf(type: String, vararg methods: ImmutableMethod): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList(),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun text(instruction: Instruction) =
        "${kind(instruction.opcode)} ${(instruction as? ReferenceInstruction)?.reference ?: ""}"

    /** An insert can push a branch past a plain goto's reach, and dexlib2 widens it. */
    private fun kind(opcode: Opcode) = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    private companion object {
        /** The one person callback's boolean: its last of three registers. */
        const val FLAG = 2

        /** The public chat task's count and the 1 it's compared with. */
        const val COUNT = 1
        const val ONE = 3
    }
}
