/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.profile.threads.PROFILE_ACTION_BAR
import app.morphe.patches.instagram.profile.threads.USER_DETAIL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Profile extras button (#234): the extension gets the bar and its right row first thing in the
 * bar's bind, and the bar with the profile screen right after each call that binds it.
 */
class ProfileExtrasHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(PROFILE_EXTRAS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$PROFILE_EXTRAS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(EXTRAS_BOUND, EXTRAS_SCREEN)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val noBar = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(ExtensionDex.classDef(PROFILE_EXTRAS))).findProfileExtras() }
        assertTrue(noBar.message, noBar.message!!.contains("this Instagram build has no $PROFILE_ACTION_BAR"))
    }

    @Test
    fun eachDeclaredBuildHooksTheBindAndItsCallers() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksTheBindAndItsCallersToo() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    private fun hooks(bundle: File, label: String) {
        val base = FixtureDex.classes(bundle, setOf(PROFILE_ACTION_BAR, USER_DETAIL, USER_DETAIL_MODEL)).values
        val callers = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.definingClass != PROFILE_ACTION_BAR &&
                method.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == PROFILE_ACTION_BAR } == true
        }.map { it.definingClass }.toSet()
        val callerClasses = FixtureDex.classes(bundle, callers).values
        val context = PatchContexts.of((base + callerClasses).distinctBy { it.type } + ExtensionDex.classDef(PROFILE_EXTRAS))
        val site = context.findProfileExtras()
        assertTrue("$label: a caller binds the bar", site.ties.isNotEmpty())

        val binderBefore = NeutralNativePath(context.method(PROFILE_ACTION_BAR, site.name, site.parameters))
        val tiesBefore = site.ties.associateWith { NeutralNativePath(context.method(it.owner, it.method, it.parameters)) }

        context.applyProfileExtras(site)

        val binder = context.method(PROFILE_ACTION_BAR, site.name, site.parameters)
        val code = binder.code()
        assertEquals("$label: the bind hands over first", listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC),
            code.take(3).map { it.opcode })
        assertEquals("$label: bound", EXTRAS_BOUND, code[2].referenceText())
        assertEquals("$label: the right row", site.right, code[1].referenceText())
        assertEquals("$label: bound once", 1, code.count { it.referenceText() == EXTRAS_BOUND })
        binderBefore.assertPreserved(label, binder, setOf(0, 1, 2))

        for (tie in site.ties) {
            val method = context.method(tie.owner, tie.method, tie.parameters)
            val steps = method.code()
            val where = "$label: ${tie.owner}->${tie.method}"
            val hooked = steps.indices.filter { steps[it].referenceText() == EXTRAS_SCREEN }
            assertEquals("$where: hooks", listOf(tie.call + 1), hooked)
            assertEquals("$where: hands the bar and the screen", listOf(tie.bar, tie.screen), steps[tie.call + 1].arguments())
            tiesBefore.getValue(tie).assertPreserved(where, method, setOf(tie.call + 1))
        }
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}
