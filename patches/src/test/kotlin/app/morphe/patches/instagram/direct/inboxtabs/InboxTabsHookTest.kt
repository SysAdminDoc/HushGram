/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.inboxtabs

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import app.morphe.util.reachingWrites
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.BitSet

class InboxTabsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(INBOX_TABS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$INBOX_TABS is not in the extension: $declared", INBOX_TABS.substringAfter("->") in declared)
    }

    /**
     * On every build the flag is loaded ten times and each load feeds one read, one of them the gate
     * your messages ask. Every one of those reads has its answer go through the hook right after its
     * move-result. The one read that takes an id loaded earlier stays unhooked, and can't run once
     * the hooked reads all say yes.
     */
    @Test
    fun eachBuildAnswersEveryRead() {
        for (build in builds()) {
            val label = "${build.parentFile.name}/${build.name}"
            val holders = holders(build)
            val loads = holders.sumOf { classDef -> classDef.methods.sumOf { method -> method.code().count { it.isFlag() } } }
            assertEquals("$label: loads of the flag", 10, loads)
            val context = PatchContexts.of(holders)
            val reads = context.findInboxTabs()
            assertEquals("$label: one read for each load", loads, reads.size)
            context.inboxTabs()

            val calls = context.flagCalls(holders)
            val answered = calls.filter { it.hooked }
            assertEquals("$label: reads answered", 10, answered.size)
            assertEquals("$label: each answered read is one the finder found", reads.map { "${it.type}->${it.name}" }.sorted(),
                answered.map { "${it.type}->${it.method.name}" }.sorted())
            val again = calls.filterNot { it.hooked }
            assertEquals("$label: reads of an id loaded earlier ${again.map { "${it.type}->${it.method.name}@${it.at}" }}", 1, again.size)
            val reread = again.single()
            assertTrue("$label: the second read sits in a static (UserSession)Z",
                AccessFlags.STATIC.isSet(reread.method.accessFlags) && reread.method.returnType == "Z" &&
                    reread.method.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION))
            val code = reread.method.code()
            val hooked = answered.filter { it.method === reread.method }.map { it.at }.toSet()
            assertTrue("$label: the method reads the flag through the hook first", hooked.isNotEmpty())
            assertTrue("$label: the second read is on a path from the start", reread.at in reachable(reread.method, code, emptySet()))
            assertFalse("$label: the second read runs although every hooked read says yes",
                reread.at in reachable(reread.method, code, hooked))
        }
    }

    @Test
    fun aBuildWithoutTheGateFailsThePatch() {
        val build = builds().first()
        val holders = holders(build)
        val gate = holders.single { classDef ->
            classDef.methods.any { method ->
                AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION) && method.returnsTheFlag()
            }
        }
        val thrown = assertThrows(PatchException::class.java) { PatchContexts.of(holders - gate).findInboxTabs() }
        assertTrue(thrown.message.orEmpty(), "expected one static (UserSession)Z" in thrown.message.orEmpty())
    }

    @Test
    fun noReadFailsThePatch() {
        assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findInboxTabs() }
    }

    /** A boolean read of the flag in a patched method: where its call is and whether the hook follows it. */
    private class FlagCall(val type: String, val method: Method, val at: Int, val hooked: Boolean)

    /** Every call that reads the flag as a boolean, found by what reaches its id's register rather than by where the load sits. */
    private fun BytecodePatchContext.flagCalls(holders: List<ClassDef>): List<FlagCall> {
        val calls = mutableListOf<FlagCall>()
        for (holder in holders) {
            val patched = mutableClassDefBy(holder.type)
            for (method in patched.methods) {
                val code = method.code()
                if (code.none { it.isFlag() }) continue
                code.forEachIndexed { at, instruction ->
                    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                    if (call.returnType != "Z" || call.parameterTypes.lastOrNull()?.toString() != "J") return@forEachIndexed
                    val arguments = instruction.namedRegisters()
                    if (!holdsTheFlag(method, code, arguments[arguments.size - 2], at, 0)) return@forEachIndexed
                    val result = code.getOrNull(at + 1)
                    val hook = code.getOrNull(at + 2)
                    val back = code.getOrNull(at + 3)
                    val hooked = result?.opcode == Opcode.MOVE_RESULT &&
                        (hook as? ReferenceInstruction)?.reference?.toString() == INBOX_TABS
                    if (hooked) {
                        val register = (result as OneRegisterInstruction).registerA
                        assertEquals("${holder.type}->${method.name}: the hook reads the answer", register,
                            (hook as RegisterRangeInstruction).startRegister)
                        assertEquals("${holder.type}->${method.name}: the hook's answer goes back", Opcode.MOVE_RESULT, back?.opcode)
                        assertEquals("${holder.type}->${method.name}: into the same register", register, (back as OneRegisterInstruction).registerA)
                    }
                    calls += FlagCall(holder.type, method, at, hooked)
                }
            }
        }
        return calls
    }

    /** Whether every write of [register] reaching [at] puts the flag's id there, directly or through moves. */
    private fun holdsTheFlag(method: Method, code: List<Instruction>, register: Int, at: Int, depth: Int): Boolean {
        if (depth > 4) return false
        val writes = reachingWrites(method, code, register, at)
        return writes.isNotEmpty() && writes.all { write ->
            val instruction = code[write]
            when {
                instruction.isFlag() -> true
                instruction.opcode in MOVE_WIDES -> holdsTheFlag(method, code, (instruction as TwoRegisterInstruction).registerB, write, depth + 1)
                else -> false
            }
        }
    }

    /**
     * The instructions a run of [method] can reach from its start, taking only the yes way out of a
     * test of each hooked read in [yes] (the read's call index): the way it goes with the switch on.
     */
    private fun reachable(method: Method, code: List<Instruction>, yes: Set<Int>): Set<Int> {
        val flow = ControlFlow.of(method)
        val onlyYes = HashMap<Int, Int>()
        for (call in yes) {
            val test = call + 4
            val register = (code[call + 3] as OneRegisterInstruction).registerA
            val branch = code.getOrNull(test) as? OneRegisterInstruction ?: continue
            if (branch.registerA != register) continue
            val taken = flow.normal[test].filter { it != test + 1 }
            when (code[test].opcode) {
                Opcode.IF_NEZ -> onlyYes[test] = taken.single()
                Opcode.IF_EQZ -> onlyYes[test] = test + 1
                else -> {}
            }
        }
        val seen = BitSet()
        val pending = ArrayDeque(listOf(0))
        seen.set(0)
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            val next = onlyYes[at]?.let { listOf(it) } ?: flow.normal[at]
            for (to in next + flow.exceptional[at]) if (!seen[to]) { seen.set(to); pending += to }
        }
        return seen.stream().toArray().toSet()
    }

    private fun builds(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val builds = Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } } + Fixtures.otherBuilds()
        assertTrue("no fixture builds", builds.isNotEmpty())
        return builds
    }

    private fun holders(build: File): List<ClassDef> {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(build) { dex ->
            for (classDef in dex.classes) if (classDef.methods.any { method -> method.code().any { it.isFlag() } }) holders += ImmutableClassDef.of(classDef)
        }
        return holders
    }

    /** Whether the method's one load of the flag feeds a read whose answer it returns as it is. */
    private fun Method.returnsTheFlag(): Boolean {
        val code = code()
        val load = code.indexOfFirst { it.isFlag() }
        if (load < 0) return false
        val result = (load until code.size).firstOrNull { code[it].opcode == Opcode.MOVE_RESULT } ?: return false
        val back = code.getOrNull(result + 1) ?: return false
        return back.opcode == Opcode.RETURN && (back as OneRegisterInstruction).registerA == (code[result] as OneRegisterInstruction).registerA
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.isFlag() = opcode == Opcode.CONST_WIDE && (this as WideLiteralInstruction).wideLiteral == INBOX_TABS_FLAG

    private companion object {
        const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
        val MOVE_WIDES = setOf(Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16)
    }
}
