/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.playservices

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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
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
import java.io.File

/**
 * #767: Firebase Instance ID's token request builds the PendingIntent naming the app through the
 * extension, which marks it immutable, on every declared build, and nothing else in it changes.
 */
class WithoutPlayServicesHookTest {
    private val extensionClass = "Lapp/hushgram/extension/instagram/misc/FirebaseRpc;"

    @Test
    fun theBroadcastIsInTheExtension() {
        val declared = ExtensionDex.classDef(extensionClass).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FIREBASE_BROADCAST is not in the extension: $declared", FIREBASE_BROADCAST in declared)
    }

    @Test
    fun eachDeclaredBundleAsksTheExtension() {
        var checked = 0
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                routes(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildAsksTheExtension() {
        for (base in Fixtures.otherBuilds()) routes(base, base.parentFile.name)
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.strings(): Set<String> =
        code().mapNotNullTo(HashSet()) { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }

    private fun routes(bundle: File, label: String) {
        val kept = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, PLACEHOLDER_PACKAGE))
            .map { ImmutableClassDef.of(it) }
        val context = PatchContexts.of(kept.distinctBy { it.type })
        val found = context.firebaseRequestSender()
        val sender = context.mutableClassDefBy(found.definingClass).methods.single { it.toString() == found.toString() }
        val what = "$label ${sender.definingClass}->${sender.name}"

        // The sender it found is Firebase's token request: it names the Play services package and
        // the registration action next to the placeholder.
        assertTrue("$what: names Play services", "com.google.android.gms" in sender.strings())
        assertTrue("$what: names the register action", "com.google.android.c2dm.intent.REGISTER" in sender.strings())
        val stock = sender.code()
        val at = sender.getBroadcastCalls().single()
        val registers = (stock[at] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE, it.registerF) }

        sender.buildRequestIdentityThroughExtension()
        val patched = sender.code()
        assertEquals("$what: nothing added or taken away", stock.size, patched.size)
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, patched[at].opcode)
        assertEquals("$what: the extension", FIREBASE_BROADCAST, (patched[at] as ReferenceInstruction).reference.toString())
        val call = patched[at] as FiveRegisterInstruction
        assertEquals("$what: same registers", registers, listOf(call.registerC, call.registerD, call.registerE, call.registerF))
        assertEquals("$what: the PendingIntent is read as before", Opcode.MOVE_RESULT_OBJECT, patched[at + 1].opcode)
        assertTrue("$what: no stock PendingIntent left", sender.getBroadcastCalls().isEmpty())
        for (index in stock.indices) {
            if (index != at) assertEquals("$what: instruction $index", stock[index].opcode, patched[index].opcode)
        }
    }

    // -- Shapes a later build could give the sender ---------------------------------------------

    private fun sender(registers: Int, body: String): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Rpc;", "send", listOf(ImmutableMethodParameter("Landroid/os/Bundle;", null, null), ImmutableMethodParameter("Lfixture/Rpc;", null, null)),
            "Landroid/os/Bundle;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body) }

    @Test
    fun `a call over a register range keeps its range`() {
        val method = sender(24, """
            invoke-static/range { v17 .. v20 }, $GET_BROADCAST
            move-result-object v0
            return-object v0
        """)
        method.buildRequestIdentityThroughExtension()
        val call = method.code()[0] as RegisterRangeInstruction
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(17, call.startRegister)
        assertEquals(4, call.registerCount)
        assertEquals(FIREBASE_BROADCAST, (call as ReferenceInstruction).reference.toString())
    }

    @Test
    fun `no PendingIntent or a second one stops the patch`() {
        assertThrows(PatchException::class.java) {
            sender(4, "const/4 v0, 0x0\nreturn-object v0").buildRequestIdentityThroughExtension()
        }
        assertThrows(PatchException::class.java) {
            sender(6, """
                invoke-static { v0, v1, v2, v1 }, $GET_BROADCAST
                invoke-static { v0, v1, v2, v1 }, $GET_BROADCAST
                const/4 v0, 0x0
                return-object v0
            """).buildRequestIdentityThroughExtension()
        }
    }
}
