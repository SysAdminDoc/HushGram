/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Log out in Home's menu (#850): the bridge makes Instagram's own Log out controller from the main
 * screen and asks it for the logout Instagram's own settings row asks for, through the start that
 * hands over to the controller's flow, never the call that signs out at once. Nothing of Instagram's
 * own code changes.
 */
class HomeLogoutHookTest {
    @Test
    fun theBridgeIsInTheExtension() {
        val declared = ExtensionDex.classDef(HOME_LOGOUT).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { it.signature() }
        assertTrue("$LOGOUT_WIRED is not in the extension: $declared", LOGOUT_WIRED in declared)
        assertTrue("$LOGOUT_START is not in the extension: $declared", LOGOUT_START in declared)
    }

    @Test
    fun anExtensionWithoutTheBridgeFailsThePatch() {
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findHomeLogout() }
        assertTrue(missing.message, missing.message!!.contains("the extension has no $HOME_LOGOUT"))
    }

    @Test
    fun aBuildWithoutTheControllerLeavesTheRestOfThePatchIn() {
        assertNull(PatchContexts.of(listOf(ExtensionDex.classDef(HOME_LOGOUT))).findHomeLogout())
    }

    @Test
    fun eachDeclaredBuildStartsInstagramsOwnLogOut() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                starts(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildStartsItToo() {
        for (base in Fixtures.otherBuilds()) starts(base, base.parentFile.name)
    }

    private fun starts(bundle: File, label: String) {
        val controller = FixtureDex.classesHolding(bundle, LOGOUT_QUESTION).singleOrNull { holder ->
            holder.methods.any { it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == FRAGMENT }
        } ?: throw AssertionError("$label: no one Log out controller logs $LOGOUT_QUESTION")
        val makers = FixtureDex.classesCreating(bundle, setOf(controller.type))
        val kinds = controller.methods.mapNotNull { it.parameterTypes.singleOrNull()?.toString() }.filter { it.startsWith("L") }.toSet()
        val screens = linkedMapOf<String, ClassDef>()
        var wanted = setOf(MAIN_ACTIVITY)
        while (wanted.isNotEmpty()) {
            val found = FixtureDex.classes(bundle, wanted)
            screens += found
            wanted = found.values.mapNotNull { it.superclass }.filter { it !in screens && it.startsWith("L") && !it.startsWith("Landroid/") }.toSet()
        }
        val classes = FixtureDex.withStringPools(bundle, listOf(controller) + makers + screens.values +
            FixtureDex.classes(bundle, kinds).values).distinctBy { it.type }
        val byType = classes.associateBy { it.type }
        assertTrue("$label: the main screen is a $FRAGMENT_ACTIVITY", FRAGMENT_ACTIVITY in screens)

        val context = PatchContexts.of(classes + ExtensionDex.classDef(HOME_LOGOUT))
        val hook = context.findHomeLogout() ?: throw AssertionError("$label: the patch found no Log out")
        assertEquals(label, controller.type, hook.controller)
        context.writeHomeLogout(hook)

        val written = context.mutableClassDefBy(HOME_LOGOUT).methods
        val wired = written.single { it.signature() == LOGOUT_WIRED }.code()
        assertEquals("$label: wired answers true", 1, (wired.first() as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, wired.last().opcode)

        val body = written.single { it.signature() == LOGOUT_START }.code()
        assertEquals("$label: the body was written", Opcode.CHECK_CAST, body.first().opcode)
        assertEquals("$label: the body works on the main screen", MAIN_ACTIVITY, (body.first().reference() as TypeReference).type)
        val made = body.single { it.opcode == Opcode.NEW_INSTANCE }
        assertEquals("$label: makes Instagram's controller", controller.type, (made.reference() as TypeReference).type)
        val constructor = body.single { it.opcode == Opcode.INVOKE_DIRECT_RANGE }.reference() as MethodReference
        val kind = body.single { it.opcode == Opcode.SGET_OBJECT }.reference() as FieldReference
        val asked = body.single { it.opcode == Opcode.INVOKE_VIRTUAL && (it.reference() as MethodReference).definingClass == controller.type }
            .reference() as MethodReference
        assertEquals("$label: answers true once started", Opcode.RETURN, body.last().opcode)

        // One of Instagram's own rows makes the controller with that constructor and asks that start for that kind.
        val rows = makers.flatMap { it.methods }.filter { row ->
            val code = row.code().map { it.reference()?.toString() }
            constructor.toString() in code && kind.toString() in code && asked.toString() in code
        }
        assertTrue("$label: no row of Instagram's own starts Log out as the bridge does", rows.isNotEmpty())

        // The start the bridge asks hands over to the flow; the controller's call that signs out at once is another.
        val start = controller.methods.single { it.signature() == asked.toString() }
        assertFalse("$label: the start signs out right away", FixtureDex.loads(start, SIGNS_OUT_NOW, byType))
        assertTrue("$label: the controller's sign-out call is still where it was",
            controller.methods.any { it !== start && FixtureDex.loads(it, SIGNS_OUT_NOW, byType) })
    }

    private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}
