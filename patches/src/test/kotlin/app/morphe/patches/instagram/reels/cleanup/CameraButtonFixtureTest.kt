/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reel opened from Saved, a profile or a post on 454 gets the screen's own top bar, and Instagram
 * adds Create (+) to it in one method (#620). These hold that place on the real build: the method
 * stores the button in its class's View field, the class reaches the viewer's source through one
 * field, and the hook asks with that source first and returns before the button is built on a no.
 */
class CameraButtonFixtureTest {
    @Test
    fun theExtensionTakesTheSourceAndAnswersWithABoolean() {
        val method = ExtensionDex.classDef(TOP_BAR).methods.single { it.name == "keepsCameraButton" }
        assertEquals(listOf("Ljava/lang/Object;"), method.parameterTypes.map(Any::toString))
        assertEquals("Z", method.returnType)
        assertTrue("public", AccessFlags.PUBLIC.isSet(method.accessFlags))
        assertTrue("static", AccessFlags.STATIC.isSet(method.accessFlags))
        assertEquals(CAMERA_BUTTON_HOOK, "$TOP_BAR->keepsCameraButton(Ljava/lang/Object;)Z")
    }

    @Test
    fun eachDeclaredBuildAsksBeforeTheButtonIsBuilt() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                asksFirst(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksBeforeTheButtonIsBuiltToo() {
        val builds = Fixtures.otherBuilds()
        for (apk in builds) asksFirst(apk, apk.parentFile.name)
        assertTrue("no other build was read", builds.isNotEmpty())
    }

    private fun asksFirst(bundle: File, label: String) {
        val classes = barClasses(bundle)
        val context = PatchContexts.of(classes)
        val site = context.findCameraButton()
        val before = site.method.code()

        // Stock: the method keeps a built view in its own class, and the source is a field of the setup.
        val owner = site.method.definingClass
        assertTrue("$label: stores the button on its class", before.any {
            it.opcode == Opcode.IPUT_OBJECT && (it.field().definingClass == owner && it.field().type == "Landroid/view/View;")
        })
        assertTrue("$label: the setup is a field of the bar", site.holderField.startsWith("$owner->"))
        val holderType = site.holderField.substringAfterLast(':')
        assertTrue("$label: the source is the setup's", site.sourceField.startsWith("$holderType->"))
        assertTrue("$label: of the source's type", site.sourceField.endsWith(":$CLIPS_VIEWER_SOURCE"))

        context.hideCameraButton(site)
        val patched = context.mutableClassDefBy(owner).methods.single {
            it.name == site.method.name && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        val after = patched.code()

        assertEquals("$label: eight instructions added", before.size + 8, after.size)
        assertEquals("$label: this first", Opcode.MOVE_OBJECT_FROM16, after[0].opcode)
        val self = after[0] as TwoRegisterInstruction
        assertEquals("$label: into the first local", 0, self.registerA)
        assertEquals("$label: from this", patched.implementation!!.registerCount - 1, self.registerB)
        assertEquals("$label: reads the setup", Opcode.IGET_OBJECT, after[1].opcode)
        assertEquals("$label: the setup's field", site.holderField, after[1].field().toString())
        assertEquals("$label: a missing setup", Opcode.IF_EQZ, after[2].opcode)
        assertEquals("$label: reads the source", Opcode.IGET_OBJECT, after[3].opcode)
        assertEquals("$label: the source's field", site.sourceField, after[3].field().toString())
        assertEquals("$label: the call", Opcode.INVOKE_STATIC, after[4].opcode)
        assertEquals("$label: the hook", CAMERA_BUTTON_HOOK, (after[4] as ReferenceInstruction).reference.toString())
        val call = after[4] as FiveRegisterInstruction
        assertEquals("$label: with the source alone", 1, call.registerCount)
        assertEquals("$label: in the first local", 0, call.registerC)
        assertEquals("$label: takes the answer", Opcode.MOVE_RESULT, after[5].opcode)
        assertEquals("$label: into the first local", 0, (after[5] as OneRegisterInstruction).registerA)
        assertEquals("$label: a yes goes on", Opcode.IF_NEZ, after[6].opcode)
        assertEquals("$label: a no returns", Opcode.RETURN_VOID, after[7].opcode)
        for (jump in listOf(2, 6)) {
            assertEquals("$label: $jump lands on Instagram's first instruction", 8, (after[jump] as BuilderOffsetInstruction).target.location.index)
        }

        assertEquals("$label: the rest as it was", before.map(::kind), after.drop(8).map(::kind))

        // The same classes without the bar's: nowhere to ask, so the patch fails.
        val without = PatchContexts.of(classes.filter { it.type != owner })
        val failure = assertThrows(PatchException::class.java) { without.findCameraButton() }
        assertTrue("$label: ${failure.message}", failure.message!!.contains("found 0"))
    }

    /** The patcher may widen a jump the insert pushes past its reach, which changes nothing. */
    private fun kind(instruction: Instruction): Opcode = when (instruction.opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> instruction.opcode
    }

    /** The classes asking for the button, and the classes of their fields, where the setup is. */
    private fun barClasses(bundle: File): List<ClassDef> {
        val loading = FixtureDex.classesLoading(bundle, SHOW_CAMERA_BUTTON)
        val fieldTypes = loading.flatMap { it.fields }.map { it.type }.filter { it.startsWith("L") }.toSet()
        return (loading + FixtureDex.classes(bundle, fieldTypes).values).associateBy { it.type }.values.toList()
    }

    private fun Instruction.field(): FieldReference = (this as ReferenceInstruction).reference as FieldReference

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val TOP_BAR = "Lapp/hushgram/extension/instagram/reels/ReelsTopBar;"
    }
}
