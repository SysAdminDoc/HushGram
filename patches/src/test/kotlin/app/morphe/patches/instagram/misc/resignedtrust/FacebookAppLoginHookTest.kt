/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.resignedtrust

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
 * #760: the Facebook SDK's app login handler asks the extension, not PackageManager, whether a
 * Facebook app is there to log in with, on every declared build, and nothing else in it changes.
 */
class FacebookAppLoginHookTest {
    private val extensionClass = "Lapp/hushgram/extension/instagram/misc/FacebookAppLogin;"

    @Test
    fun theLookupIsInTheExtension() {
        val declared = ExtensionDex.classDef(extensionClass).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FACEBOOK_APP_LOOKUP is not in the extension: $declared", FACEBOOK_APP_LOOKUP in declared)
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
        val kept = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, FACEBOOK_PROXY_AUTH))
            .map { ImmutableClassDef.of(it) }
        val context = PatchContexts.of(kept.distinctBy { it.type })
        val found = context.facebookAppLoginHandler()
        val handler = context.mutableClassDefBy(found.definingClass).methods.single { it.toString() == found.toString() }
        val what = "$label ${handler.definingClass}->${handler.name}"

        // The handler it found is the app login: besides the Facebook app's login screen it names
        // the Facebook beta, the first app it tries.
        assertTrue("$what: names the beta", "com.facebook.wakizashi" in handler.strings())
        val stock = handler.code()
        val at = handler.resolveActivityCalls().single()
        val registers = (stock[at] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE) }

        handler.lookUpFacebookAppThroughExtension()
        val patched = handler.code()
        assertEquals("$what: nothing added or taken away", stock.size, patched.size)
        assertEquals("$what: the lookup", Opcode.INVOKE_STATIC, patched[at].opcode)
        assertEquals("$what: the extension", FACEBOOK_APP_LOOKUP, (patched[at] as ReferenceInstruction).reference.toString())
        val call = patched[at] as FiveRegisterInstruction
        assertEquals("$what: same registers, PackageManager first", registers, listOf(call.registerC, call.registerD, call.registerE))
        assertEquals("$what: the answer is read as before", Opcode.MOVE_RESULT_OBJECT, patched[at + 1].opcode)
        assertTrue("$what: no PackageManager lookup left", handler.resolveActivityCalls().isEmpty())
        for (index in stock.indices) {
            if (index != at) assertEquals("$what: instruction $index", stock[index].opcode, patched[index].opcode)
        }
    }

    // -- Shapes a later build could give the handler ---------------------------------------------

    private fun handler(registers: Int, body: String): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/KatanaProxy;", "login", listOf(ImmutableMethodParameter(FACEBOOK_LOGIN_REQUEST, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body) }

    @Test
    fun `a lookup over a register range keeps its range`() {
        val method = handler(24, """
            invoke-virtual/range { v18 .. v20 }, $RESOLVE_ACTIVITY
            move-result-object v0
            return-void
        """)
        method.lookUpFacebookAppThroughExtension()
        val call = method.code()[0] as RegisterRangeInstruction
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(18, call.startRegister)
        assertEquals(3, call.registerCount)
        assertEquals(FACEBOOK_APP_LOOKUP, (call as ReferenceInstruction).reference.toString())
    }

    @Test
    fun `no lookup or a second one stops the patch`() {
        assertThrows(PatchException::class.java) {
            handler(4, "return-void").lookUpFacebookAppThroughExtension()
        }
        assertThrows(PatchException::class.java) {
            handler(4, """
                invoke-virtual { v0, v1, v2 }, $RESOLVE_ACTIVITY
                invoke-virtual { v0, v1, v2 }, $RESOLVE_ACTIVITY
                return-void
            """).lookUpFacebookAppThroughExtension()
        }
    }
}
