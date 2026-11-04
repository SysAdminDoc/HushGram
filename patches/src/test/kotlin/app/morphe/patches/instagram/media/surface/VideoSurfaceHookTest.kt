/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.surface

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Instagram's choice of a SurfaceView asks the extension first and answers no when it says so, on
 * every build, and its own code carries on unchanged after that.
 */
class VideoSurfaceHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(VIDEO_SURFACE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$VIDEO_SURFACE->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("VideoSurface declares no $TEXTURE_ONLY: $declared", TEXTURE_ONLY in declared)
    }

    /** 454's 386014758 drops the phone model test and its marker, and LX/04h7;->A00 still reads the model. */
    @Test
    fun aChoiceWithoutTheMarkerIsTheOneReadingThePhoneModel() {
        val context = PatchContexts.of(listOf(choice("Lfixture/Choice;"), choice("Lfixture/ModelOnly;", sdk = false)))
        assertEquals("Lfixture/Choice;", context.findSurfaceChoice()?.definingClass)
        val hooked = context.keepVideoOnTexture()
        assertEquals(TEXTURE_ONLY, (hooked!!.code()[0] as ReferenceInstruction).reference.toString())
    }

    @Test
    fun theMarkerStillPicksTheChoiceFirst() {
        val context = PatchContexts.of(listOf(choice("Lfixture/Marked;", marker = true, model = false), choice("Lfixture/Choice;")))
        assertEquals("Lfixture/Marked;", context.findSurfaceChoice()?.definingClass)
    }

    @Test
    fun twoChoicesReadingThePhoneModelAreNone() {
        val context = PatchContexts.of(listOf(choice("Lfixture/Choice;"), choice("Lfixture/Other;")))
        assertNull(context.findSurfaceChoice())
        assertNull(context.keepVideoOnTexture())
    }

    @Test
    fun anInstanceMethodReadingThePhoneModelIsNoChoice() {
        assertNull(PatchContexts.of(listOf(choice("Lfixture/Choice;", static = false))).findSurfaceChoice())
    }

    @Test
    fun eachDeclaredBuildHooksTheChoice() {
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
    fun eachOtherBuildHooksTheChoiceToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = (FixtureDex.classesLoading(bundle, SURFACE_CHOICE_MARKER) + modelReaders(bundle)).distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes))

        val choice = context.findSurfaceChoice()
        assertNotNull("$label: no one choice, by the marker or by the phone model", choice)
        val before = choice!!.code()
        val hooked = context.keepVideoOnTexture()
        assertNotNull("$label: the choice can't take the hook", hooked)

        val code = hooked!!.code()
        assertEquals("$label: asks first", TEXTURE_ONLY, (code[0] as? ReferenceInstruction)?.reference?.toString())
        assertEquals("$label: the call", Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$label: the answer is tested", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$label: a TextureView is no", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: and returns", Opcode.RETURN, code[4].opcode)
        assertEquals("$label: Instagram's own choice follows", before.map { it.opcode }, code.drop(5).map { it.opcode })
    }

    /** The classes with a static (UserSession, String)Z reading the phone model, the choice where the marker is gone. */
    private fun modelReaders(bundle: File): List<ClassDef> {
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val reads = classDef.methods.any { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
                        method.parameterTypes.map { it.toString() } == listOf(USER_SESSION, "Ljava/lang/String;") &&
                        method.code().any { (it as? ReferenceInstruction)?.reference?.toString() == "Landroid/os/Build;->MODEL:Ljava/lang/String;" }
                }
                if (reads) readers += ImmutableClassDef.of(classDef)
            }
        }
        return readers
    }

    /** A stand-in choice, a (UserSession, String)Z reading what's asked of it and answering no. */
    private fun choice(type: String, marker: Boolean = false, model: Boolean = true, sdk: Boolean = true, static: Boolean = true): ClassDef {
        val flags = AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0
        val method = MutableMethod(
            ImmutableMethod(
                type, "A00", listOf(USER_SESSION, "Ljava/lang/String;").map { ImmutableMethodParameter(it, null, null) }, "Z", flags, null, null,
                ImmutableMethodImplementation(if (static) 4 else 5, emptyList(), null, null),
            ),
        )
        method.addInstructions(
            0,
            listOfNotNull(
                if (sdk) "sget v1, Landroid/os/Build\$VERSION;->SDK_INT:I" else null,
                if (model) "sget-object v1, Landroid/os/Build;->MODEL:Ljava/lang/String;" else null,
                if (marker) "const-string v1, \"$SURFACE_CHOICE_MARKER\"" else null,
                "const/4 v0, 0x0",
                "return v0",
            ).joinToString("\n"),
        )
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(method)))
    }

    private companion object {
        const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
    }

    /** The code without the alignment nops dexlib2 adds or drops around a payload. */
    private fun Method.code(): List<Instruction> = implementation?.instructions?.filter { it.opcode != Opcode.NOP } ?: emptyList()
}
