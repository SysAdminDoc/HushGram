/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.profilesaved

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.feed.ViewIdFixture
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Saved on your profile writes no hook of its own: it switches its status method on, and the
 * extension finds your profile's tabs, its create button and the action bar by the ids Instagram's
 * layout gives them. So each build has to declare all three, or the bookmark quietly never shows.
 */
class ProfileSavedHookTest {
    private val saved = "$EXTENSION_PACKAGE/misc/ProfileSaved;"
    private val ids = listOf("TAB_LAYOUT", "CREATE_BUTTON", "ACTION_BUTTONS").map { ExtensionDex.stringConstant(saved, it) }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        savedOnProfilePatch.execute(context)
        assertEquals(1, context.status("savedOnProfile"))
        assertEquals("another patch's status is still off", 0, context.status("glassTabBar"))
    }

    /** An extension without the status method fails at patch time, naming it, instead of going in and doing nothing. */
    @Test
    fun anExtensionWithoutTheStatusMethodRefuses() {
        val status = ExtensionDex.classDef(SETTINGS_STATUS)
        val without = ImmutableClassDef(
            status.type, status.accessFlags, status.superclass, status.interfaces, status.sourceFile, status.annotations,
            status.fields, status.methods.filterNot { it.name == "savedOnProfile" },
        )
        val failure = assertThrows(PatchException::class.java) { savedOnProfilePatch.execute(PatchContexts.of(listOf(without))) }
        assertTrue(failure.message, failure.message!!.contains("savedOnProfile()"))
    }

    @Test
    fun eachDeclaredBuildDeclaresTheIds() {
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
    fun eachOtherBuildDeclaresTheIdsToo() {
        for (base in Fixtures.otherBuilds()) declares(base, base.parentFile.name)
    }

    /** The search tells a real id from one the build doesn't declare. */
    @Test
    fun anIdTheBuildDoesntDeclareIsMissing() {
        val wrong = ids.first() + "_hushgram"
        val table = ViewIdFixture.resourceTable(Fixtures.otherBuilds().first())
        assertEquals(listOf(wrong), ViewIdFixture.missing(table, listOf(ids.first(), wrong)))
    }

    private fun declares(bundle: File, label: String) {
        assertEquals("$label: ids the build doesn't declare", emptyList<String>(), ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), ids))
    }

    private fun BytecodePatchContext.status(name: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.first { it.name == name }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral
}
