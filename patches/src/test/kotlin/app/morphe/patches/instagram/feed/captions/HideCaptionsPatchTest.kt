/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.captions

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.feed.ViewIdFixture
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide captions changes no Instagram code: it switches the status method on, and the
 * extension finds the caption hosts by the ids Instagram's layout gives them.
 */
class HideCaptionsPatchTest {
    private val views = listOf("media_caption_component", "clips_caption_component")

    /** The extension the bundle ships has the install the application hook calls. */
    @Test
    fun theExtensionHasTheInstall() {
        val install = ExtensionDex.classDef("$EXTENSION_PACKAGE/feed/HideCaptions;").methods.single { it.name == "install" }
        assertTrue(AccessFlags.PUBLIC.isSet(install.accessFlags) && AccessFlags.STATIC.isSet(install.accessFlags))
    }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        hideCaptionsPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, HIDE_CAPTIONS_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideComments").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** On each declared 451 build the patch applies, and the build declares the ids the extension looks for. */
    @Test
    fun eachDeclaredBuildHasTheCaptionHosts() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                declares(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHasTheCaptionHostsToo() {
        for (base in Fixtures.otherBuilds()) declares(base, base.parentFile.name)
    }

    private fun declares(bundle: File, label: String) {
        assertEquals("$label: ids the build doesn't declare", emptyList<String>(),
            ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), views))
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        hideCaptionsPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, HIDE_CAPTIONS_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
}
