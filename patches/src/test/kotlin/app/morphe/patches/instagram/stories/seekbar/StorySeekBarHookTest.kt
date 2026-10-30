/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seekbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.media.taptoplay.REEL_VIEWER_FRAGMENT
import app.morphe.patches.instagram.media.taptoplay.VIDEO_PLAYER_FIELD
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorySeekBarHookTest {
    /** The calls the patch writes into the viewer are in the StorySeekBar the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_SEEK_BAR).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        listOf(SEEK_RESUMED, SEEK_PAUSED).forEach { assertTrue("$it is not in the extension: $declared", it.substringAfter("->") in declared) }
    }

    /**
     * On 452.1 the patch finds the player's seek to (Gww, not the seek by Gwj), its position (Bvv)
     * and length (C4s, the one Instagram's scrubber clamps to, not the live-only Bvx), the viewer's
     * pause (A0o) and resume (F8S), fills every stub and tells the extension right after onResume
     * and onPause hand on to the superclass.
     */
    @Test
    fun eachDeclaredBuildFindsTheSeekAndHooksTheViewer() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val sites = builds(bundle, bundle.name)
                if (version == "452.1.0.53.64") {
                    assertEquals("${bundle.name}: the seek to", "Gww", sites.seekTo)
                    assertEquals("${bundle.name}: the position", "Bvv", sites.position)
                    assertEquals("${bundle.name}: the length", "C4s", sites.duration)
                    assertEquals("${bundle.name}: the pause", "$REEL_VIEWER_FRAGMENT->A0o($REEL_VIEWER_FRAGMENT$STRING)V", sites.pause)
                    assertEquals("${bundle.name}: the resume", "F8S", sites.resume)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildFindsTheSeekToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** A second run finds its own calls and refuses instead of telling the extension twice. */
    @Test
    fun aSecondRunRefuses() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle))
        context.hookStorySeek(context.findStorySeek())
        val failure = assertThrows(PatchException::class.java) { context.findStorySeek() }
        assertTrue(failure.message, failure.message!!.contains("already calls $STORY_SEEK_BAR"))
    }

    /** A build whose player nothing implements, or without the extension's stubs, fails before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val all = classes(bundle)
        val playerType = all.single { it.type == REEL_VIEWER_FRAGMENT }.fields.single { it.name == VIDEO_PLAYER_FIELD }.type
        val cases = listOf(
            all.filter { playerType !in it.interfaces } to "nothing implements $playerType",
            all.filter { it.type != STORY_SEEK_READER } to "the extension has no $STORY_SEEK_READER",
            all.filter { classDef -> classDef.methods.none { it.holds(SCRUBBER) } } to "no method holds $SCRUBBER",
            all.filter { it.type != REEL_VIEWER_FRAGMENT } to "this build has no $REEL_VIEWER_FRAGMENT",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findStorySeek() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            if (classes.any { it.type == REEL_VIEWER_FRAGMENT }) {
                val viewer = context.mutableClassDefBy(REEL_VIEWER_FRAGMENT).methods
                assertTrue("$expected: something was written", viewer.none { m -> m.code().any { it.method()?.definingClass == STORY_SEEK_BAR } })
            }
        }
    }

    private fun builds(bundle: File, label: String): StorySeekSites {
        val classes = classes(bundle)
        val context = PatchContexts.of(classes)
        val sites = context.findStorySeek()
        context.hookStorySeek(sites)

        val viewer = classes.single { it.type == REEL_VIEWER_FRAGMENT }
        for ((name, hook) in listOf("onResume" to SEEK_RESUMED, "onPause" to SEEK_PAUSED)) {
            val stock = viewer.lifecycle(name).code()
            val hooked = context.mutableClassDefBy(REEL_VIEWER_FRAGMENT).lifecycle(name).code()
            assertEquals("$label: $name grows by the one call", stock.size + 1, hooked.size)
            val at = hooked.indexOfFirst { it.method()?.toString() == hook }
            assertTrue("$label: $name calls $hook", at > 0)
            assertEquals("$label: $name calls $hook once", 1, hooked.count { it.method()?.toString() == hook })
            val before = hooked[at - 1]
            assertTrue("$label: $name calls $hook right after its superclass's", before.opcode == Opcode.INVOKE_SUPER || before.opcode == Opcode.INVOKE_SUPER_RANGE)
            assertEquals("$label: $name's superclass call", name, before.method()?.name)
        }
        val reader = context.mutableClassDefBy(STORY_SEEK_READER)
        for (stub in listOf("view", "position", "duration", "seekTo", "pause", "resume")) {
            val code = reader.methods.single { it.name == stub }.code()
            assertEquals("$label: $stub is filled", Opcode.CHECK_CAST, code.first().opcode)
        }
        val seek = reader.methods.single { it.name == "seekTo" }.code().mapNotNull { it.method() }.single()
        assertEquals("$label: the seek goes to the player interface", "${sites.playerInterface}->${sites.seekTo}(I)V", seek.toString())
        return sites
    }

    /** The viewer, its player interface, what implements it, Instagram's story scrubber and the extension's stubs. */
    private fun classes(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        var playerType: String? = null
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.firstOrNull { it.type == REEL_VIEWER_FRAGMENT }?.let { viewer ->
                found += ImmutableClassDef.of(viewer)
                playerType = viewer.fields.single { it.name == VIDEO_PLAYER_FIELD }.type
            }
        }
        val player = playerType ?: error("${bundle.name} has no $REEL_VIEWER_FRAGMENT")
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type == player || player in classDef.interfaces || classDef.methods.any { it.holds(SCRUBBER) }) {
                    if (classDef.type != REEL_VIEWER_FRAGMENT) found += ImmutableClassDef.of(classDef)
                }
            }
        }
        return FixtureDex.withStringPools(bundle, found) + ExtensionDex.classDef(STORY_SEEK_READER)
    }

    private fun ClassDef.lifecycle(name: String): Method =
        methods.single { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.holds(string: String): Boolean = code().any {
        (it as? ReferenceInstruction)?.reference.let { reference -> reference is StringReference && reference.string == string }
    }

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private companion object {
        const val STRING = "Ljava/lang/String;"
    }
}
