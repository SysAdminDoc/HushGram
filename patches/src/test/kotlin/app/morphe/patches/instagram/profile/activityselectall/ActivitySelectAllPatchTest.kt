/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.activityselectall

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
 * Select all in Your activity (#762): the status method goes on, and every build declares the Bloks
 * container and top bar ids the extension looks for.
 */
class ActivitySelectAllPatchTest {
    private val views = listOf("bloks_container", "action_bar")

    /** The extension the bundle ships has the install the application hook calls. */
    @Test
    fun theExtensionHasTheInstall() {
        val install = ExtensionDex.classDef("$EXTENSION_PACKAGE/profile/ActivitySelectAll;").methods.single { it.name == "install" }
        assertTrue(AccessFlags.PUBLIC.isSet(install.accessFlags) && AccessFlags.STATIC.isSet(install.accessFlags))
    }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        activitySelectAllPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, ACTIVITY_SELECT_ALL_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "profileFollowCounts").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** On each declared build the patch applies, and the build declares the ids the extension looks for. */
    @Test
    fun eachDeclaredBuildHasTheIds() {
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
    fun eachOtherBuildHasTheIdsToo() {
        for (base in Fixtures.otherBuilds()) declares(base, base.parentFile.name)
    }

    private fun declares(bundle: File, label: String) {
        assertEquals("$label: ids the build doesn't declare", emptyList<String>(),
            ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), views))
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        activitySelectAllPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, ACTIVITY_SELECT_ALL_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
}
