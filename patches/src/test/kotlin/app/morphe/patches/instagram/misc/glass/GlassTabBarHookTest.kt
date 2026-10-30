/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.glass

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
 * Glass tab bar writes no hook of its own: it switches its status method on, and the extension finds
 * the tab bar, the screens under it, the Reels viewer and the sheets by the ids Instagram's layout
 * gives them. So each build has to declare every one of those ids, or the pill quietly never shows.
 */
class GlassTabBarHookTest {
    private val glass = "$EXTENSION_PACKAGE/misc/GlassTabBar;"
    private val ids = listOf(
        "TAB_BAR", "TAB_BAR_SHADOW", "CONTENT", "SWIPE_PAGER", "REELS_ROOT", "TOUCH_STRIP", "SCRUBBER", "REEL_INFO",
        "REEL_MEDIA", "REEL_FOOTERS", "SEARCH_TAB", "REELS_TAB", "SHEET_HOLDER", "SHEET_DIMMER", "STORY_ROOT",
    ).map { ExtensionDex.stringConstant(glass, it) }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        glassTabBarPatch.execute(context)
        assertEquals(1, context.status("glassTabBar"))
        assertEquals("another patch's status is still off", 0, context.status("savedOnProfile"))
    }

    /** An extension without the status method fails at patch time, naming it, instead of going in and doing nothing. */
    @Test
    fun anExtensionWithoutTheStatusMethodRefuses() {
        val status = ExtensionDex.classDef(SETTINGS_STATUS)
        val without = ImmutableClassDef(
            status.type, status.accessFlags, status.superclass, status.interfaces, status.sourceFile, status.annotations,
            status.fields, status.methods.filterNot { it.name == "glassTabBar" },
        )
        val failure = assertThrows(PatchException::class.java) { glassTabBarPatch.execute(PatchContexts.of(listOf(without))) }
        assertTrue(failure.message, failure.message!!.contains("glassTabBar()"))
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

    /** Only a whole id counts: the start of tab_bar_shadow is no id, though every byte of it is in the table. */
    @Test
    fun aPieceOfALongerIdIsMissing() {
        val shadow = ExtensionDex.stringConstant(glass, "TAB_BAR_SHADOW")
        val piece = shadow.dropLast(1)
        val table = ViewIdFixture.resourceTable(Fixtures.otherBuilds().first())
        assertEquals(listOf(piece), ViewIdFixture.missing(table, listOf(shadow, piece)))
    }

    private fun declares(bundle: File, label: String) {
        assertEquals("$label: ids the build doesn't declare", emptyList<String>(), ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), ids))
    }

    private fun BytecodePatchContext.status(name: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.first { it.name == name }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral
}
