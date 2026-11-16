/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction20t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ask for VP9 instead of AV1 (#546): both hardware AV1 answers in the device status map go
 * through the extension right before their put, and nothing else in the method changes. Anything
 * the patch can't tell apart fails it before an instruction changes.
 */
class AskForVp9HookTest {
    @Test
    fun theHookIsInTheExtension() {
        val hook = ExtensionDex.classDef(VP9_VIDEO).methods.single {
            it.name == "hardwareAv1" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        }
        assertEquals("Ljava/lang/Object;", hook.returnType)
        assertTrue(AccessFlags.PUBLIC.isSet(hook.accessFlags) && AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    @Test
    fun bothAv1AnswersAskTheExtension() {
        val context = PatchContexts.of(listOf(status()))
        val found = context.findDeviceStatus()
        assertEquals("the keys", AV1_KEYS, found.puts.map { it.key })
        assertEquals("the answers' register", listOf(1, 1), found.puts.map { it.value })
        val before = found.method.code().filter { it.opcode != Opcode.NOP }.map(::text)
        askForVp9(found)
        assertHooked(context, "stand-in", found.method, before)
    }

    /** Redex pools some keys: the 10-bit one comes from a pool call and its move-result. */
    @Test
    fun aPooledKeyIsFoundToo() {
        val context = PatchContexts.of(listOf(status(pooled = true), pool()))
        val found = context.findDeviceStatus()
        assertEquals("the keys", AV1_KEYS, found.puts.map { it.key })
        val before = found.method.code().filter { it.opcode != Opcode.NOP }.map(::text)
        askForVp9(found)
        assertHooked(context, "pooled stand-in", found.method, before)
    }

    @Test
    fun aMissingOrDoubledStatusFailsThePatch() {
        refuses("found 0", listOf(status(keys = listOf(HW_AV1, "hw_vp9_dec"))))
        refuses("found 0", listOf(status(keys = listOf("hw_vp9_dec"))))
        refuses("found 2", listOf(status(), status("Lfixture/OtherStatus;")))
    }

    @Test
    fun aStatusOfAnotherShapeFailsThePatch() {
        refuses("not a map", listOf(status(returns = "Ljava/lang/String;")))
        refuses("2 times, not once", listOf(status(twice = true)))
        refuses("right after loading it", listOf(status(gap = true)))
        refuses("right after loading it", listOf(status(adder = OTHER_ADD)))
        refuses("jumps onto the put", listOf(status(loop = true)))
    }

    /** In each declared build: the one status builder, both AV1 answers asking first. */
    @Test
    fun eachDeclaredBuildAsks() {
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
    fun eachOtherBuildAsksToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val context = PatchContexts.of(FixtureDex.classesLoading(bundle, HW_AV1))
        val found = context.findDeviceStatus()
        assertEquals("$label: the keys", AV1_KEYS, found.puts.map { it.key })
        val before = found.method.code().filter { it.opcode != Opcode.NOP }.map(::text)
        askForVp9(found)
        assertHooked(context, label, found.method, before)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>, patch: BytecodePatchContext.() -> Unit = { askForVp9(findDeviceStatus()) }) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.patch() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /**
     * Two hooks in [method], each handing a register to [HARDWARE_AV1], keeping the answer in it,
     * then putting it under an AV1 key; the stock code ([before]) is what's left without them.
     */
    private fun assertHooked(context: BytecodePatchContext, what: String, method: Method, before: List<String>) {
        val code = method.code()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == HARDWARE_AV1 }
        assertEquals("$what: two calls", 2, hooks.size)
        val keys = hooks.map { at ->
            val call = code[at] as RegisterRangeInstruction
            assertEquals("$what: the call hands over one register", 1, call.registerCount)
            val value = call.startRegister
            assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
            assertEquals("$what: the answer goes back", value, (code[at + 1] as OneRegisterInstruction).registerA)
            val put = code[at + 2]
            assertEquals("$what: then the put", "put", ((put as ReferenceInstruction).reference as MethodReference).name)
            val registers = put.callRegisters()
            assertEquals("$what: the put takes the answer", value, registers[2])
            context.keyBefore(code, at, registers[1])
        }
        assertEquals("$what: under the AV1 keys", AV1_KEYS.toSet(), keys.toSet())
        val stock = code.filterIndexed { at, _ -> at !in hooks && at - 1 !in hooks }
        assertEquals("$what: the rest stays", before, stock.filter { it.opcode != Opcode.NOP }.map(::text))
    }

    /** The key loaded into [register] right before the hook at [at]: a const-string's, or a pool call's. */
    private fun BytecodePatchContext.keyBefore(code: List<Instruction>, at: Int, register: Int): String? {
        val load = code[at - 1]
        assertEquals("the key register", register, (load as OneRegisterInstruction).registerA)
        return if (load.opcode == Opcode.MOVE_RESULT_OBJECT) stringLoadedAt(code, at - 2) else stringLoadedAt(code, at - 1)
    }

    private fun Instruction.callRegisters(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name // opcode-name-ok
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference" // opcode-name-ok
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private companion object {
        const val STATUS = "Lfixture/DeviceStatus;"
        const val MAP_TYPE = "Lfixture/StatusMap;"
        const val LAZY = "Lfixture/Lazy;"
        const val DECODERS = "Lfixture/Decoders;"
        const val POOL = "Lfixture/Strings;"
        const val POOLED = 7
        val PUT = ImmutableMethodReference(MAP_TYPE, "put", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;")
        val OTHER_ADD = ImmutableMethodReference(MAP_TYPE, "add", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;")
        val INIT = ImmutableMethodReference(MAP_TYPE, "<init>", emptyList(), "V")
        val GET_VALUE = ImmutableMethodReference(LAZY, "getValue", emptyList(), "Ljava/lang/Object;")
        val POOL_CALL = ImmutableMethodReference(POOL, "A00", listOf("I"), "Ljava/lang/String;")

        /**
         * Shaped like 454's device status builder: static (LX/06dJ;)Ljava/util/Map;, a map in v4,
         * each decoder's lazy answer read into v1, its key loaded into v0 and both put in the map.
         * With [pooled], the 10-bit AV1 key comes from [pool] as Redex has some.
         */
        fun status(
            type: String = STATUS,
            keys: List<String> = listOf(HW_AV1, "hw_vp9_dec", "hw_avc_dec", HW_AV1_10BIT),
            returns: String = "Ljava/util/Map;",
            pooled: Boolean = false,
            twice: Boolean = false,
            gap: Boolean = false,
            adder: ImmutableMethodReference = PUT,
            loop: Boolean = false,
        ): ClassDef {
            val code = mutableListOf<ImmutableInstruction>(
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 4, ImmutableTypeReference(MAP_TYPE)),
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 4, 0, 0, 0, 0, INIT),
            )
            var firstPut = -1
            val entries = if (twice) keys + HW_AV1 else keys
            for ((n, key) in entries.withIndex()) {
                code += ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(DECODERS, "A0$n", LAZY))
                code += ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0, GET_VALUE)
                code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1)
                if (pooled && key == HW_AV1_10BIT) {
                    code += ImmutableInstruction21s(Opcode.CONST_16, 0, POOLED)
                    code += ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, POOL_CALL)
                    code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
                } else {
                    code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key))
                }
                if (gap && key == HW_AV1) code += ImmutableInstruction11n(Opcode.CONST_4, 2, 0)
                if (firstPut < 0) firstPut = code.size
                code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 4, 0, 1, 0, 0, if (key == HW_AV1) adder else PUT)
            }
            if (loop) {
                val here = code.sumOf { it.codeUnits }
                val target = code.take(firstPut).sumOf { it.codeUnits }
                code += ImmutableInstruction20t(Opcode.GOTO_16, target - here)
            }
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 4)
            val build = ImmutableMethod(
                type, "A00", listOf(ImmutableMethodParameter("Lfixture/Session;", null, null)), returns,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(6, code, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(build))
        }

        /**
         * A Redex string pool: a packed switch from [POOLED] to the 10-bit AV1 key, anything else null.
         *   0 packed-switch p0, +8   3 const/4 v0   4 return-object   5 const-string   7 return-object   8 payload
         */
        fun pool(): ClassDef = ImmutableClassDef(
            POOL, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    POOL, "A00", listOf(ImmutableMethodParameter("I", null, null)), "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(2, listOf(
                        ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 8),
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(HW_AV1_10BIT)),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(POOLED, 5))),
                    ), null, null),
                ),
            ),
        )
    }
}
