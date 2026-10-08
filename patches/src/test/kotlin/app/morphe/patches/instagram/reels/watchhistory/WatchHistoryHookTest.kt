/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.watchhistory

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryHookTest {
    private val batchType = "Lfixture/PendingClipsSeenState;"

    /** The hook the patch writes is in the ReelWatchHistory the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val type = HOLD_BACK.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOLD_BACK is not in the extension: $declared", HOLD_BACK.substringAfter("->") in declared)
    }

    /** Both records ask first, and go on to their own first instruction when told not to hold back. */
    @Test
    fun bothRecordsAskBeforeTheReelGoesIn() {
        val context = PatchContexts.of(listOf(batch()))

        context.holdBackWatchedReels()

        val patched = context.mutableClassDefBy(batchType)
        for ((parameters, what) in RECORDS) {
            val record = patched.methods.single { it.recordParameters(batchType) == parameters }
            assertGuardFirst(what, record)
        }
        val decoy = patched.methods.single { it.isStatic() && it.recordParameters(batchType) == null }
        assertEquals("the static method of the same shape was touched", 1, decoy.instructions().size)
        val request = patched.methods.single { it.name == "request" }
        assertEquals("the request builder was touched", 3, request.instructions().size)
    }

    @Test
    fun withoutTheRequestThePatchFails() {
        val context = PatchContexts.of(listOf(batch(request = false)))
        assertThrows(PatchException::class.java) { context.holdBackWatchedReels() }
    }

    @Test
    fun aBatchMissingARecordFailsThePatch() {
        for ((parameters, _) in RECORDS) {
            val context = PatchContexts.of(listOf(batch(leaveOut = parameters)))
            val failure = assertThrows(PatchException::class.java) { context.holdBackWatchedReels() }
            assertTrue(failure.message!!, failure.message!!.contains(parameters))
        }
    }

    @Test
    fun aSecondRecordOfTheSameShapeFailsThePatch() {
        val context = PatchContexts.of(listOf(batch(twice = RECORDS.first().first)))
        assertThrows(PatchException::class.java) { context.holdBackWatchedReels() }
    }

    /**
     * In each declared build the request is found in one class, and both of its record methods get
     * the guard first thing, falling through to the method's own first instruction.
     */
    @Test
    fun eachDeclaredBuildHoldsBackBothRecords() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.stringSection.none { it == "clips/write_seen_state/" }) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.instructions().any { it.string() == "clips/write_seen_state/" } }) {
                            holders += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(holders)

                context.holdBackWatchedReels()

                val batch = holders.single { holder ->
                    holder.methods.any { method -> method.instructions().any { it.string() == WATCHED_REELS_ERROR } }
                }
                val patched = context.mutableClassDefBy(batch.type)
                for ((parameters, what) in RECORDS) {
                    val before = batch.methods.single { it.recordParameters(batch.type) == parameters }
                    val after = patched.methods.single { it.name == before.name && it.recordParameters(batch.type) == parameters }
                    assertEquals("${bundle.name}: $what size", before.instructions().size + 4, after.instructions().size)
                    assertGuardFirst("${bundle.name}: $what", after)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertGuardFirst(what: String, record: Method) {
        val code = record.instructions()
        assertEquals(
            "$what: the guard's opcodes",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
            code.take(4).map { it.opcode },
        )
        assertEquals("$what: the hook called", HOLD_BACK, (code[0] as ReferenceInstruction).reference.toString())
        // The branch lands on the record's own first instruction, right after the return.
        val jump = code[2] as OffsetInstruction
        val codeUnitsTo = code.take(2).sumOf { it.codeUnits }
        assertEquals("$what: the branch target", code.take(4).sumOf { it.codeUnits } - codeUnitsTo, jump.codeOffset)
    }

    /**
     * A batch class shaped like Instagram 450's: the request builder holding both strings, the
     * watched reel record, the static progress record taking the batch first, and a static method
     * of the first record's shape without the batch that has to stay as it is.
     */
    private fun batch(request: Boolean = true, leaveOut: String? = null, twice: String? = null): ClassDef {
        val methods = mutableListOf<ImmutableMethod>()
        if (request) {
            methods += method(
                "request", emptyList(), "Ljava/lang/Object;", static = false,
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("clips/write_seen_state/")),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(WATCHED_REELS_ERROR)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
        }
        RECORDS.forEachIndexed { index, (parameters, _) ->
            if (parameters == leaveOut) return@forEachIndexed
            val progress = parameters.endsWith("J")
            val types = if (progress) listOf(batchType, "Ljava/lang/String;", "J", "J") else listOf("Ljava/lang/String;", "Ljava/lang/String;")
            val copies = if (parameters == twice) 2 else 1
            repeat(copies) { copy ->
                methods += method(
                    "A0${index}$copy", types, "V", static = progress,
                    ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Ljava/lang/Object;")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                )
            }
        }
        methods += method(
            "A09", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V", static = true,
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return ImmutableClassDef(
            batchType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods,
        )
    }

    private fun method(name: String, parameters: List<String>, returns: String, static: Boolean, vararg code: Instruction) =
        ImmutableMethod(
            batchType, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
            null, null,
            ImmutableMethodImplementation(parameters.sumOf { if (it == "J") 2L else 1L }.toInt() + 3, code.toList(), null, null),
        )

    private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private companion object {
        const val WATCHED_REELS_ERROR = "PendingClipsSeenState#progressImpressionsEncodeFailure"
    }
}
