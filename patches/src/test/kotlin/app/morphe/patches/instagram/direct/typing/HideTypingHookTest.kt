/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.typing

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideTypingHookTest {
    // Its switch starts off, so simple mode picks it (DefaultSelectionPolicyTest).
    @Test fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(hideTypingPatch.default)
    }

    @Test fun aStartIsHeldBeforeAnythingIsSentAndNothingElseChanges() {
        val input = TypingFixture.classes()
        val context = PatchContexts.of(input)
        val found = context.findTyping()
        assertEquals(TypingFixture.SENDER, found.sender.definingClass)
        val before = input.associate { it.type to snapshot(it.methods) }
        val first = TypingFixture.service().methods.single { it.name == "report" }.code().first()
        context.holdBackTyping()
        val patched = context.mutableClassDefBy(TypingFixture.SERVICE).methods.single { it.name == "report" }
        assertTypingGuard(patched, first)
        for (candidate in input.filter { it.type != TypingFixture.SERVICE }) {
            assertEquals("${candidate.type} changed", before[candidate.type], snapshot(context.mutableClassDefBy(candidate.type).methods))
        }
    }

    /** A build that tests the flag the other way round, typing on the jump, is the same shape. */
    @Test fun aFlagTestedTheOtherWayRoundIsAccepted() {
        val input = TypingFixture.classes().map {
            if (it.type == TypingFixture.SERVICE) TypingFixture.service(nonZeroJumps = true) else it
        }
        val context = PatchContexts.of(input)
        context.holdBackTyping()
        assertTypingGuard(context.mutableClassDefBy(TypingFixture.SERVICE).methods.single { it.name == "report" },
            TypingFixture.text(0, TYPING_SERVICE))
    }

    @Test fun aMissingServiceIsRefused() =
        refuses("one typing status service", TypingFixture.classes().filter { it.type != TypingFixture.SERVICE })
    @Test fun duplicateServicesAreRefused() =
        refuses("one typing status service", TypingFixture.classes() + TypingFixture.service("Lfixture/SecondTypingService;"))
    @Test fun aMissingExtensionIsRefused() =
        refuses("no public static hold(I)Z", TypingFixture.classes().filter { it.type != TYPING_STATUS })
    @Test fun anExtensionThatTakesABooleanIsRefused() = refuses("no public static hold(I)Z", TypingFixture.classes().map {
        if (it.type == TYPING_STATUS) TypingFixture.extension("Z") else it
    })
    @Test fun aServiceThatReadsItsScratchRegisterFirstIsRefused() = changed("still reads v0", TypingFixture.SERVICE, "report") {
        it.add(0, TypingFixture.call(listOf(0), "Lfixture/Timer;", "note", listOf("Ljava/lang/String;")))
    }
    @Test fun aFlagChangedBeforeItsCheckIsRefused() = changed("writes over parameter 0", TypingFixture.SERVICE, "report") {
        it.add(2, ImmutableInstruction11n(Opcode.CONST_4, 3, 1))
    }
    @Test fun aFlagCheckedTwiceIsRefused() = refuses("one typing flag check, found 2", TypingFixture.classes().map {
        if (it.type == TypingFixture.SERVICE) TypingFixture.service(checkTwice = true) else it
    })
    @Test fun aSenderOnTheStopSideIsRefused() = refuses("sent when you stop typing", TypingFixture.classes().map {
        if (it.type == TypingFixture.SERVICE) TypingFixture.service(sendOnStop = true) else it
    })
    @Test fun aSenderCalledFromElsewhereIsRefused() = refuses("one call of the typing indicator sender, found 2",
        TypingFixture.classes() + TypingFixture.clazz("Lfixture/Elsewhere;", listOf(
            TypingFixture.method("Lfixture/Elsewhere;", "ping", emptyList(), "V", 2, listOf(
                TypingFixture.text(0, "thread"), TypingFixture.call(listOf(0), TypingFixture.SENDER, "send", listOf("Ljava/lang/String;")),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )),
        )))
    @Test fun aSecondTypingSenderIsRefused() =
        refuses("one typing indicator sender, found 2", TypingFixture.classes() + TypingFixture.sender("Lfixture/SecondSender;"))

    private fun changed(reason: String, type: String, name: String, change: (MutableList<Instruction>) -> Unit) =
        refuses(reason, TypingFixture.classes().map { candidate ->
            if (candidate.type != type) candidate else TypingFixture.clazz(type, candidate.methods.map { old ->
                if (old.name != name) old else old.code().toMutableList().let { code ->
                    change(code)
                    ImmutableMethod(old.definingClass, old.name, old.parameters, old.returnType, old.accessFlags, null, null,
                        ImmutableMethodImplementation(old.implementation!!.registerCount, code, null, null))
                }
            })
        })

    /** Refused for [reason], with every class as it was. */
    private fun refuses(reason: String, input: List<ClassDef>) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val refusal = assertThrows(PatchException::class.java) { context.holdBackTyping() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$TYPING_PATCH: ") && reason in refusal.message!!)
        input.forEach { assertEquals("${it.type} was edited before refusal", before[it.type], snapshot(context.mutableClassDefBy(it.type).methods)) }
    }

    companion object {
        /** The guard sits first: a held start returns, anything else jumps to Instagram's [original] first instruction. */
        internal fun assertTypingGuard(method: Method, original: Instruction) {
            val code = method.code()
            assertEquals(1, code.count { it.reference() == HOLD_TYPING })
            assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.take(4).map { it.opcode })
            assertEquals(HOLD_TYPING, code[0].reference())
            assertEquals("the hook reads Instagram's typing flag", listOf(method.parameterRegisterNumber(0)), code[0].namedRegisters())
            assertEquals(listOf(0), code[1].namedRegisters())
            assertEquals(0, (code[2] as OneRegisterInstruction).registerA)
            val flow = ControlFlow.of(method)
            assertEquals("held falls through to the return, anything else jumps to Instagram", setOf(3, 4), flow.normal[2].toSet())
            assertTrue("the return ends the method", flow.normal[3].isEmpty())
            assertEquals(original.opcode, code[4].opcode)
            assertEquals(original.namedRegisters(), code[4].namedRegisters())
            assertEquals(original.reference(), code[4].reference())
        }

        internal fun snapshot(methods: Iterable<Method>) = methods.map { method ->
            method.toString() to method.code().map { Triple(it.opcode, it.reference(), it.namedRegisters()) }
        }

        internal fun Method.code() = implementation?.instructions?.toList().orEmpty()
        internal fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
    }
}

/** Stand-ins for the typing status service, its sender and the extension, in 450's shapes. */
internal object TypingFixture {
    const val SERVICE = "Lfixture/TypingService;"
    const val SENDER = "Lfixture/TypingSender;"
    const val LISTENER = "Lfixture/ComposerListener;"
    private const val STRING = "Ljava/lang/String;"
    private const val CALLBACK = "Lcom/instagram/realtimeclient/RealtimeClientManager\$MessageDeliveryCallback;"

    fun classes(): List<ClassDef> = listOf(service(), sender(), listener(), extension("I"))

    /**
     * report(Z)V with p0 in v2 and the flag in v3. The flag's typing side calls the sender, and its
     * stop side cancels a timer. [nonZeroJumps] lays it out the other way round.
     */
    fun service(type: String = SERVICE, nonZeroJumps: Boolean = false, checkTwice: Boolean = false, sendOnStop: Boolean = false): ClassDef {
        val send = call(listOf(1), SENDER, "send", listOf(STRING))
        val cancel = call(emptyList(), "Lfixture/Timer;", "cancel", emptyList())
        val (near, far) = if (nonZeroJumps != sendOnStop) cancel to send else send to cancel
        val code = mutableListOf(
            text(0, TYPING_SERVICE), text(1, TYPING_ENABLED),
            ImmutableInstruction21t(if (nonZeroJumps) Opcode.IF_NEZ else Opcode.IF_EQZ, 3, 0),
            near, ImmutableInstruction10x(Opcode.RETURN_VOID),
            far, ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        code[2] = ImmutableInstruction21t(code[2].opcode, 3, offset(code, 2, 5))
        if (checkTwice) {
            code.add(5, ImmutableInstruction21t(Opcode.IF_EQZ, 3, 0))
            code[5] = ImmutableInstruction21t(Opcode.IF_EQZ, 3, offset(code, 5, 7))
            code[2] = ImmutableInstruction21t(code[2].opcode, 3, offset(code, 2, 5))
        }
        return clazz(type, listOf(method(type, "report", listOf("Z"), "V", 4, code)))
    }

    /** Sends the typing command over the realtime connection, as 450's sender does. */
    fun sender(type: String = SENDER): ClassDef = clazz(type, listOf(method(type, "send", listOf(STRING), "V", 3, listOf(
        text(0, TYPING_COMMAND),
        ImmutableInstruction21c(Opcode.SGET_OBJECT, 1, ImmutableFieldReference("Lfixture/Realtime;", "client", REALTIME_CLIENT)),
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 1, 2, 0, 1, 0,
            ImmutableMethodReference(REALTIME_CLIENT, "sendCommand", listOf(STRING, STRING, CALLBACK), "V")),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ), static = true)))

    /** The composer's listener hands the service its flag. */
    fun listener(): ClassDef = clazz(LISTENER, listOf(method(LISTENER, "changed", listOf(SERVICE), "V", 3, listOf(
        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 0, 0, 0, 0, ImmutableMethodReference(SERVICE, "report", listOf("Z"), "V")),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))))

    fun extension(parameter: String): ClassDef = clazz(TYPING_STATUS, listOf(method(TYPING_STATUS, "hold", listOf(parameter), "Z", 2,
        listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)), static = true)))

    fun text(register: Int, value: String): Instruction = ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))
    fun call(registers: List<Int>, owner: String, name: String, parameters: List<String>): Instruction {
        val r = registers + List(5 - registers.size) { 0 }
        return ImmutableInstruction35c(Opcode.INVOKE_STATIC, registers.size, r[0], r[1], r[2], r[3], r[4],
            ImmutableMethodReference(owner, name, parameters, "V"))
    }
    fun offset(code: List<Instruction>, from: Int, to: Int): Int = code.take(to).sumOf { it.codeUnits } - code.take(from).sumOf { it.codeUnits }
    fun clazz(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
    fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, code: List<Instruction>, static: Boolean = false): Method =
        ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, null, null, ImmutableMethodImplementation(registers, code, null, null))
}
