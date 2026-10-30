/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.translatedstart

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranslatedStartTest {
    private val tasks = "Lfixture/IdleTasks;"
    private val later = "Lfixture/LaterInit;"
    private val bare = "Lfixture/Bare;"
    private val selfOnly = "Lfixture/SelfOnly;"
    private val reading = "Lfixture/Reading;"
    private val extension = "Lapp/hushgram/extension/instagram/Mine;"

    /**
     * Each call to the code protection step asks protectCode() first and is jumped over on a 0: the
     * idle task's, reached from its type check's fall-through, the later init's, first in its method,
     * and one a branch lands on, which now lands on the question.
     */
    @Test
    fun everyCallAsksFirstAndIsSkippedOnANo() {
        val context = PatchContexts.of(classes())

        assertEquals(3, context.guardCodeProtection())

        for ((type, name) in listOf(tasks to "run", later to "start", later to "branched")) {
            val code = context.code(type, name)
            val call = code.indices.single { code[it].reference() == MPROTECT_EXEC_CODE }
            assertGuarded("$type->$name", code, call)
        }
        val branched = context.code(later, "branched")
        val jump = branched.indices.first { branched[it].opcode == Opcode.IF_NEZ }
        assertEquals("the branch to the call", PROTECT_CODE, branched[target(branched, jump)].reference())
        assertTrue("the extension's own call", context.code(extension, "call").none { it.reference() == PROTECT_CODE })
    }

    /** A call with no local to hold the answer is named in the log and left as it was, and the rest are guarded. */
    @Test
    fun aCallItCantGuardIsLeftAndTheRestAreGuarded() {
        val context = PatchContexts.of(classes() + bareCaller())

        assertEquals(3, context.guardCodeProtection())

        assertTrue(context.code(bare, "protect").none { it.reference() == PROTECT_CODE })
    }

    /**
     * A caller whose one register is `this` (Instagram 451's 0HUM.A02) keeps the answer there, since
     * nothing reads it after the call. One that still reads its parameter after the call is left alone.
     */
    @Test
    fun aCallerWithNoLocalBorrowsAParameterNothingReadsAgain() {
        val context = PatchContexts.of(classes() + selfOnlyCaller() + readingCaller())

        assertEquals(4, context.guardCodeProtection())

        val code = context.code(selfOnly, "protect")
        val call = code.indices.single { code[it].reference() == MPROTECT_EXEC_CODE }
        assertGuarded("$selfOnly->protect", code, call)
        assertEquals("the answer goes in this", 0, (code[call - 2] as OneRegisterInstruction).registerA)
        assertTrue(context.code(reading, "protect").none { it.reference() == PROTECT_CODE })
    }

    @Test
    fun aBuildThatNeverCallsTheStepStopsThePatch() {
        val context = PatchContexts.of(listOf(bareCaller()).map { it.renamedCall() })

        val failure = assertThrows(PatchException::class.java) { context.guardCodeProtection() }

        assertTrue(failure.message, failure.message!!.contains("never calls"))
    }

    /** In each declared build, every call Instagram makes to the code protection step asks first. */
    @Test
    fun eachDeclaredBuildGuardsEveryCall() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildStartsTranslatedToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.instructionsOrEmpty().any { it.reference() == MPROTECT_EXEC_CODE } }) {
                    classes += ImmutableClassDef.of(classDef)
                }
            }
        }
        val calls = classes.sumOf { classDef -> classDef.methods.sumOf { method -> method.instructionsOrEmpty().count { it.reference() == MPROTECT_EXEC_CODE } } }
        val context = PatchContexts.of(classes)

        assertEquals(label, calls, context.guardCodeProtection())

        assertTrue("${label}: no call to guard", calls >= 1)
        for (classDef in classes) {
            for (method in context.classDefBy(classDef.type).methods) {
                val code = method.instructionsOrEmpty()
                code.indices.filter { code[it].reference() == MPROTECT_EXEC_CODE }.forEach { call ->
                    assertGuarded("${label}: ${method.definingClass}->${method.name}", code, call)
                }
            }
        }
    }

    /** The three instructions in front of [call]: protectCode(), its answer, and a branch on it to just past the call. */
    private fun assertGuarded(where: String, code: List<Instruction>, call: Int) {
        assertEquals("$where: the question", PROTECT_CODE, code[call - 3].reference())
        assertEquals("$where: the answer", Opcode.MOVE_RESULT, code[call - 2].opcode)
        val answer = (code[call - 2] as OneRegisterInstruction).registerA
        assertEquals("$where: the branch", Opcode.IF_EQZ, code[call - 1].opcode)
        assertEquals("$where: the branch tests the answer", answer, (code[call - 1] as OneRegisterInstruction).registerA)
        assertEquals("$where: a no skips just the call", call + 1, target(code, call - 1))
    }

    /** The index the jump at [index] lands on. */
    private fun target(code: List<Instruction>, index: Int): Int {
        val address = IntArray(code.size + 1)
        code.forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (code[index] as OffsetInstruction).codeOffset)
    }

    private fun BytecodePatchContext.code(type: String, name: String): List<Instruction> =
        classDefBy(type).methods.single { it.name == name }.instructionsOrEmpty()

    private fun Method.instructionsOrEmpty(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /**
     * The idle task runner, whose "mprotect" task's arm makes the call and returns; the later init,
     * which makes it first; a method a branch takes to the call; and the extension, which also calls
     * the step and is left alone.
     */
    private fun classes(): List<ClassDef> = listOf(
        classOf(tasks, method(tasks, "run", listOf("Ljava/lang/Object;"), 3, """
            instance-of v0, p0, Lfixture/MprotectTask;
            if-eqz v0, :other
            invoke-static { }, $MPROTECT_EXEC_CODE
            return-void
            :other
            const/4 v1, 0x1
            return-void
        """)),
        classOf(later,
            method(later, "start", emptyList(), 1, """
                invoke-static { }, $MPROTECT_EXEC_CODE
                const/4 v0, 0x0
                return-void
            """),
            method(later, "branched", listOf("I"), 2, """
                if-nez p0, :protect
                goto :done
                :protect
                invoke-static { }, $MPROTECT_EXEC_CODE
                :done
                return-void
            """),
        ),
        classOf(extension, method(extension, "call", emptyList(), 0, """
            invoke-static { }, $MPROTECT_EXEC_CODE
            return-void
        """)),
    )

    /** A caller with no locals, so nowhere to keep protectCode()'s answer. */
    private fun bareCaller(): ClassDef = classOf(bare, method(bare, "protect", emptyList(), 0, """
        invoke-static { }, $MPROTECT_EXEC_CODE
        return-void
    """))

    /** An instance caller with no locals: its one register is `this`, which nothing reads again. */
    private fun selfOnlyCaller(): ClassDef = classOf(selfOnly, method(selfOnly, "protect", emptyList(), 1, """
        invoke-static { }, $MPROTECT_EXEC_CODE
        return-void
    """, static = false))

    /** A caller with no locals whose parameter is still read after the call. */
    private fun readingCaller(): ClassDef = classOf(reading, method(reading, "protect", listOf("I"), 1, """
        invoke-static { }, $MPROTECT_EXEC_CODE
        if-eqz p0, :done
        :done
        return-void
    """))

    /** This class with its call pointed at some other native method instead. */
    private fun ClassDef.renamedCall(): ClassDef = classOf(type, method(type, "protect", emptyList(), 0, """
        invoke-static { }, Lcom/facebook/common/dextricks/RuntimeInternals;->somethingElse()V
        return-void
    """))

    private fun classOf(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    private fun method(owner: String, name: String, parameters: List<String>, registers: Int, body: String, static: Boolean = true): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }
}
