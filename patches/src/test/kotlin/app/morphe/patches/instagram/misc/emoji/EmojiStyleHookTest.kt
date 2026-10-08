/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.emoji

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emoji style: first thing in EmojiCompat's process, the strategy it was handed goes through
 * EmojiStyle.replaceStrategy and the answer replaces it, and a process the patch can't tell the
 * strategy of fails the patch before a change.
 */
class EmojiStyleHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(EMOJI_STYLE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$REPLACE_STRATEGY is not in the extension: $declared", REPLACE_STRATEGY in declared)
    }

    @Test
    fun processAsksForTheStrategyFirst() {
        val context = PatchContexts.of(listOf(process(), extension()))

        val found = context.findEmojiProcess()
        assertEquals(PROCESS, found.method.definingClass)
        assertEquals("p4", found.strategy)
        context.askReplaceStrategy()

        assertAsks("stand-in", context.mutableClassDefBy(PROCESS).methods.single(), 18)
    }

    @Test
    fun aProcessItCantTellTheStrategyOfFailsThePatch() {
        for ((case, classes) in listOf(
            "two methods holding the checks" to listOf(process(), process(type = "Lfixture/OtherCompat;"), extension()),
            "a static process" to listOf(process(static = true), extension()),
            "a most emoji count still taken" to listOf(process(counted = true), extension()),
            "the start not checked for being negative" to listOf(process(startChecked = false), extension()),
            "the strategy never compared" to listOf(process(compared = false), extension()),
            "a jump onto the start" to listOf(process(loop = true), extension()),
            "no hook in the extension" to listOf(process()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findEmojiProcess() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Emoji style:"))
        }
    }

    /** A refusal comes before any change: process keeps every instruction it had. */
    @Test
    fun aRefusalChangesNothing() {
        val stub = process(compared = false)
        val context = PatchContexts.of(listOf(stub, extension()))
        val before = stub.methods.single().instructions().map { it.opcode }
        assertThrows(PatchException::class.java) { context.askReplaceStrategy() }
        assertEquals(before, context.classDefBy(PROCESS).methods.single().instructions().map { it.opcode })
    }

    /** In each declared build EmojiCompat's process is found, its strategy is its last number, and it asks first. */
    @Test
    fun eachDeclaredBuildAsksFirst() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, RANGE_CHECK)
                val context = PatchContexts.of(holders.distinctBy { it.type } + extension())

                val found = context.findEmojiProcess()
                assertEquals("${bundle.name}: the strategy", "p4", found.strategy)
                val type = found.method.definingClass
                val name = found.method.name
                val register = found.method.implementation!!.registerCount - 1
                val first = found.method.instructions().first().opcode
                context.askReplaceStrategy()

                val method = context.mutableClassDefBy(type).methods.single { it.name == name && it.holds(RANGE_CHECK) }
                assertAsks("${bundle.name} $type->$name", method, register)
                assertEquals("${bundle.name}: process's own start follows", first, method.instructions()[2].opcode)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The hook first, on [register] alone, and its answer written back to [register]. */
    private fun assertAsks(what: String, method: Method, register: Int) {
        val code = method.instructions()
        val hooks = code.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == REPLACE_STRATEGY }
        assertEquals("$what: hooks", listOf(0), hooks.map { it.index })
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        val asked = code[0] as RegisterRangeInstruction
        assertEquals("$what: the strategy asked about", register, asked.startRegister)
        assertEquals("$what: the strategy alone", 1, asked.registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer replaces the strategy", register, (code[1] as OneRegisterInstruction).registerA)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.holds(value: String) = instructions().any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    }

    private companion object {
        const val PROCESS = "Lfixture/EmojiCompat;"

        fun extension(): ClassDef = ExtensionDex.classDef(EMOJI_STYLE)

        /**
         * An instance (CharSequence, int, int, int)CharSequence shaped like 450's 00Zx.A04: it checks
         * the start and the end for being negative and the range against the text, copies the
         * strategy and compares it with replace-all. Nineteen registers put the strategy, p4, in v18.
         * Each flag breaks one part of that.
         */
        fun process(
            type: String = PROCESS,
            static: Boolean = false,
            counted: Boolean = false,
            startChecked: Boolean = true,
            compared: Boolean = true,
            loop: Boolean = false,
        ): ClassDef {
            val parameters = listOf("Ljava/lang/CharSequence;", "I", "I") + (if (counted) listOf("I") else emptyList()) + "I"
            // A static process has no this, so its parameters start at p0.
            fun p(index: Int) = "p${index + if (static) 0 else 1}"
            val body = """
                :start
                const-string v0, "start cannot be negative"
                ${if (startChecked) "if-ltz ${p(1)}, :bad" else ""}
                const-string v0, "end cannot be negative"
                if-ltz ${p(2)}, :bad
                ${if (counted) "if-ltz ${p(3)}, :bad" else ""}
                const-string v0, "$RANGE_CHECK"
                const-string v0, "$LENGTH_CHECK"
                move/from16 v1, ${p(parameters.size - 1)}
                const/4 v0, 0x1
                ${if (compared) "if-eq v1, v0, :all" else "if-lez v1, :all"}
                :all
                ${if (loop) "if-nez v1, :start" else ""}
                return-object ${p(0)}
                :bad
                new-instance v0, Ljava/lang/IllegalArgumentException;
                invoke-direct { v0 }, Ljava/lang/IllegalArgumentException;-><init>()V
                throw v0
            """
            val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            val registers = 14 + (if (static) 0 else 1) + parameters.size
            val method = ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, "A04", parameters.map { ImmutableMethodParameter(it, null, null) }, "Ljava/lang/CharSequence;",
                        flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent()) },
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
                null, null, null, null, listOf(method))
        }
    }
}
