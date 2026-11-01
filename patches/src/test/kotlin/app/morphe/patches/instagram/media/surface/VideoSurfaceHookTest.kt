/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.surface

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val classes = FixtureDex.classesLoading(bundle, SURFACE_CHOICE_MARKER)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes))

        val choice = context.findSurfaceChoice()
        assertNotNull("$label: no one method loads the marker", choice)
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

    /** The code without the alignment nops dexlib2 adds or drops around a payload. */
    private fun Method.code(): List<Instruction> = implementation?.instructions?.filter { it.opcode != Opcode.NOP } ?: emptyList()
}
