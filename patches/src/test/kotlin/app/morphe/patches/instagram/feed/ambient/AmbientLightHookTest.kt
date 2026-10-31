/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.ambient

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
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

/**
 * Ambient light writes no hook of its own: it switches its status method on, and the extension finds a
 * post's media frame by its class name and a reel's frame by its view id. So each build has to have both,
 * or the glow quietly never shows.
 */
class AmbientLightHookTest {
    private val light = "$EXTENSION_PACKAGE/feed/AmbientLight;"
    private val reelFrame = ExtensionDex.stringConstant(light, "REEL_FRAME")
    private val storyFrame = ExtensionDex.stringConstant(light, "STORY_FRAME")
    private val mediaFrame = "L" + ExtensionDex.stringConstant(light, "MEDIA_FRAME").replace('.', '/') + ";"

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        ambientLightPatch.execute(context)
        assertEquals(1, context.status("ambientLight"))
        assertEquals("another patch's status is still off", 0, context.status("carouselArrows"))
    }

    /** An extension without the status method fails at patch time, naming it, instead of going in and doing nothing. */
    @Test
    fun anExtensionWithoutTheStatusMethodRefuses() {
        val status = ExtensionDex.classDef(SETTINGS_STATUS)
        val without = ImmutableClassDef(
            status.type, status.accessFlags, status.superclass, status.interfaces, status.sourceFile, status.annotations,
            status.fields, status.methods.filterNot { it.name == "ambientLight" },
        )
        val failure = assertThrows(PatchException::class.java) { ambientLightPatch.execute(PatchContexts.of(listOf(without))) }
        assertTrue(failure.message, failure.message!!.contains("ambientLight()"))
    }

    /** Every build of the version declares the reel frame's id and has the media frame class the extension looks for by name. */
    @Test
    fun eachBuildHasTheReelFrameAndTheMediaFrame() {
        assertEquals("Lcom/instagram/ui/widget/framelayout/MediaFrameLayout;", mediaFrame)
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            assertEquals("$name: ids the build doesn't declare", emptyList<String>(), ViewIdFixture.missing(ViewIdFixture.resourceTable(apk), listOf(reelFrame, storyFrame)))
            assertEquals("$name: the media frame", setOf(mediaFrame), FixtureDex.classes(apk, setOf(mediaFrame)).keys)
        }
    }

    /** The search tells the real id from one the build doesn't declare. */
    @Test
    fun anIdTheBuildDoesntDeclareIsMissing() {
        val wrong = reelFrame + "_hushgram"
        val table = ViewIdFixture.resourceTable(Fixtures.otherBuilds().first())
        assertEquals(listOf(wrong), ViewIdFixture.missing(table, listOf(reelFrame, wrong)))
    }

    private fun BytecodePatchContext.status(name: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.first { it.name == name }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral
}
