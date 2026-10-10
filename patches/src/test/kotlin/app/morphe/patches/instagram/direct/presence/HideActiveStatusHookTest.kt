/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideActiveStatusHookTest {
    // Its switch starts off, so simple mode picks it (DefaultSelectionPolicyTest).
    @Test fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(hideActiveStatusPatch.default)
    }

    @Test fun theStatusGoesThroughTheExtensionBeforeItIsStoredAndNothingElseChanges() {
        val input = PresenceFixture.classes()
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val original = PresenceFixture.constructor().code()
        context.sendIdleForActive()
        val methods = context.mutableClassDefBy(PRESENCE_WRITE_REQUEST).methods
        assertStatusHook(methods.single { it.parameterTypes.size == 6 }, original)
        assertEquals("Kotlin's serializer constructor stays as it was",
            snapshot(PresenceFixture.request().methods.filter { it.parameterTypes.size == 7 }),
            snapshot(methods.filter { it.parameterTypes.size == 7 }))
        for (candidate in input.filter { it.type != PRESENCE_WRITE_REQUEST }) {
            assertEquals("${candidate.type} changed", before[candidate.type], snapshot(context.mutableClassDefBy(candidate.type).methods))
        }
    }

    @Test fun aMissingWriteRequestIsRefused() = refuses("one presence write request constructor",
        PresenceFixture.classes().filter { it.type != PRESENCE_WRITE_REQUEST })
    @Test fun aMissingExtensionIsRefused() = refuses("no public static status(Ljava/lang/Object;)Ljava/lang/Object;",
        PresenceFixture.classes().filter { it.type != ACTIVE_STATUS })
    @Test fun anExtensionAnsweringTheEnumIsRefused() = refuses("no public static status(Ljava/lang/Object;)Ljava/lang/Object;",
        PresenceFixture.classes().map { if (it.type == ACTIVE_STATUS) PresenceFixture.extension(PRESENCE_STATUS) else it })
    @Test fun anEnumWithoutIdleIsRefused() = refuses("doesn't name ACTIVE and IDLE",
        PresenceFixture.classes().map { if (it.type == PRESENCE_STATUS) PresenceFixture.statusEnum(listOf("OFFLINE", "ACTIVE")) else it })
    @Test fun aMissingEnumIsRefused() = refuses("no presence status enum", PresenceFixture.classes().filter { it.type != PRESENCE_STATUS })
    @Test fun aStatusStoredFromAnotherRegisterIsRefused() = refuses("isn't stored from the status parameter",
        PresenceFixture.classes().map { if (it.type == PRESENCE_WRITE_REQUEST) PresenceFixture.request(statusFrom = 4) else it })
    @Test fun aStatusOverwrittenBeforeItsStoreIsRefused() = refuses("writes over parameter 2",
        PresenceFixture.classes().map { if (it.type == PRESENCE_WRITE_REQUEST) PresenceFixture.request(overwrite = true) else it })
    @Test fun aStatusStoredTwiceIsRefused() = refuses("one store of the status field, found 2",
        PresenceFixture.classes().map { if (it.type == PRESENCE_WRITE_REQUEST) PresenceFixture.request(storeTwice = true) else it })

    /** Refused for [reason], with every class as it was. */
    private fun refuses(reason: String, input: List<ClassDef>) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val refusal = assertThrows(PatchException::class.java) { context.sendIdleForActive() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$ACTIVE_STATUS_PATCH: ") && reason in refusal.message!!)
        input.forEach { assertEquals("${it.type} was edited before refusal", before[it.type], snapshot(context.mutableClassDefBy(it.type).methods)) }
    }

    companion object {
        /**
         * The status parameter goes through the extension and is cast back to the enum in its own
         * register, first thing, and then Instagram's [original] constructor runs as it was.
         */
        internal fun assertStatusHook(method: Method, original: List<Instruction>) {
            val code = method.code()
            val status = method.parameterRegisterNumber(STATUS_PARAMETER)
            assertEquals(1, code.count { it.reference() == SEND_STATUS })
            assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST), code.take(3).map { it.opcode })
            assertEquals(SEND_STATUS, code[0].reference())
            assertEquals("the hook reads the status parameter", listOf(status), code[0].namedRegisters())
            assertEquals("the answer goes back in the status parameter", listOf(status), code[1].namedRegisters())
            assertEquals(listOf(status), code[2].namedRegisters())
            assertEquals("cast back to the enum for the store", PRESENCE_STATUS, code[2].reference())
            assertEquals("three instructions come in front, nothing else", original.size + 3, code.size)
            assertEquals(original.map { Triple(it.opcode, it.reference(), it.namedRegisters()) },
                code.drop(3).map { Triple(it.opcode, it.reference(), it.namedRegisters()) })
        }

        internal fun snapshot(methods: Iterable<Method>) = methods.map { method ->
            method.toString() to method.code().map { Triple(it.opcode, it.reference(), it.namedRegisters()) }
        }

        internal fun Method.code() = implementation?.instructions?.toList().orEmpty()
        internal fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
    }
}

/** Stand-ins for Meta's presence write request, its status enum and the extension, in 450's shapes. */
internal object PresenceFixture {
    private const val OBJECT = "Ljava/lang/Object;"

    fun classes(): List<ClassDef> = listOf(request(), statusEnum(listOf("OFFLINE", "ACTIVE", "IDLE", "DISABLED")), extension(OBJECT))

    /** 450's six-parameter constructor: p0 in v0, the status in v3, each parameter stored to its field. */
    fun constructor(statusFrom: Int = 3, overwrite: Boolean = false, storeTwice: Boolean = false): Method {
        val fields = listOf("A00" to 1, "A01" to 2, "A02" to statusFrom, "A03" to 4, "A04" to 5, "A05" to 6)
        val code = mutableListOf<Instruction>(
            ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(OBJECT, "<init>", emptyList(), "V")),
        )
        if (overwrite) code += ImmutableInstruction12x(Opcode.MOVE_OBJECT, 3, 4)
        for ((name, register) in fields) code += store(register, name, PRESENCE_WRITE_PARAMETERS[fields.indexOfFirst { it.first == name }])
        if (storeTwice) code += store(3, "A02", PRESENCE_STATUS)
        code += ImmutableInstruction10x(Opcode.RETURN_VOID)
        return method(PRESENCE_WRITE_REQUEST, "<init>", PRESENCE_WRITE_PARAMETERS, "V", 7, code, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)
    }

    fun request(statusFrom: Int = 3, overwrite: Boolean = false, storeTwice: Boolean = false): ClassDef {
        // Kotlin's serializer constructor: the same six and a bit mask. It stores the status too, but isn't the hook's.
        val serializer = method(PRESENCE_WRITE_REQUEST, "<init>", PRESENCE_WRITE_PARAMETERS + "I", "V", 9, listOf(
            ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(OBJECT, "<init>", emptyList(), "V")),
            store(4, "A02", PRESENCE_STATUS),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ), AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value or AccessFlags.SYNTHETIC.value)
        return clazz(PRESENCE_WRITE_REQUEST, listOf(constructor(statusFrom, overwrite, storeTwice), serializer))
    }

    /** The status enum's static initializer, naming each constant. */
    fun statusEnum(names: List<String>): ClassDef = clazz(PRESENCE_STATUS, listOf(method(PRESENCE_STATUS, "<clinit>", emptyList(), "V", 3,
        names.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) as Instruction } +
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value)))

    fun extension(returns: String): ClassDef = clazz(ACTIVE_STATUS, listOf(method(ACTIVE_STATUS, "status", listOf(OBJECT), returns, 1,
        listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)))

    private fun store(register: Int, name: String, type: String): Instruction =
        ImmutableInstruction22c(Opcode.IPUT_OBJECT, register, 0, ImmutableFieldReference(PRESENCE_WRITE_REQUEST, name, type))

    fun clazz(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null, methods)

    fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, code: List<Instruction>, flags: Int): Method =
        ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, code, null, null))
}
