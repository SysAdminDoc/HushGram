/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.calls

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Call blocker (#578): the ring asks the extension first with the call object, on every declared build. */
class CallBlockerHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CALL_BLOCKER).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$DROP_CALL is not in the extension: $declared", DROP_CALL in declared)
    }

    @Test
    fun eachDeclaredBundleAsksFirst() {
        var checked = 0
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildAsksFirst() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val kept = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, INCOMING_CALL_ACTION))
            .map { ImmutableClassDef.of(it) }
        val context = PatchContexts.of(kept.distinctBy { it.type })
        val ring = context.findCallRing()
        val entityAt = ring.parameterTypes.map(Any::toString).indexOf(CALL_ENTITY)
        val stock = ring.implementation!!.instructions.count()
        stopRing(ring)
        assertAsksFirst("$label ${ring.definingClass}->${ring.name}", ring, entityAt)
        assertEquals("$label: only the hook was added", stock + 4, ring.implementation!!.instructions.count())
    }

    private fun assertAsksFirst(what: String, method: Method, entityAt: Int) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$what: the hook", DROP_CALL, (code[0] as ReferenceInstruction).reference.toString())
        val range = code[0] as RegisterRangeInstruction
        val parameters = method.parameterTypes.map(Any::toString)
        val size = parameters.sumOf { if (it == "J" || it == "D") 2 as Int else 1 } + 1
        val slot = parameters.take(entityAt).sumOf { if (it == "J" || it == "D") 2 as Int else 1 } + 1
        assertEquals("$what: the call object", method.implementation!!.registerCount - size + slot, range.startRegister)
        assertEquals("$what: one register", 1, range.registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the early return", Opcode.RETURN_VOID, code[3].opcode)
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == DROP_CALL })
    }
}
