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
import app.morphe.patches.instagram.stories.speed.SPEED_PLAYING
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
        listOf(SEEK_RESUMED, SEEK_PAUSED, SEEK_PLAYING).forEach { assertTrue("$it is not in the extension: $declared", it.substringAfter("->") in declared) }
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
                if (version == "457.0.0.55.73") {
                    assertEquals("${bundle.name}: the callback for a story's video starting", "Gi1", sites.started)
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

    /**
     * Speed button on stories hooks the same callback for a story's video starting. With its call
     * already in, this patch still finds the callback and puts its own call in front of each return
     * too, and every branch that went to a return runs both.
     */
    @Test
    fun itStacksWithTheSpeedButton() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle))
        val found = context.findStorySeek()
        val callback = context.mutableClassDefBy(REEL_VIEWER_FRAGMENT).methods
            .single { it.name == found.started && it.parameters() == found.startedParameters }
        // Speed button on stories' own call, the way its hookStorySpeed writes it.
        val stock = callback.code()
        for (at in stock.indices.filter { stock[it].opcode == Opcode.RETURN_VOID }.sortedDescending()) {
            callback.addInstructionsAtControlFlowLabel(at, "invoke-static/range { p0 .. p2 }, $SPEED_PLAYING")
        }
        context.hookStorySeek(context.findStorySeek())

        val code = callback.code().filter { it.opcode != Opcode.NOP }
        val ends = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        assertTrue(ends.isNotEmpty())
        for (end in ends) {
            assertEquals("the seek bar's call right before the return", SEEK_PLAYING, code[end - 1].method()?.toString())
            assertEquals("the speed's call before it", SPEED_PLAYING, code[end - 2].method()?.toString())
        }
        val all = callback.implementation!!.instructions.toList()
        for (instruction in all.filterIsInstance<BuilderOffsetInstruction>()) {
            val target = all[instruction.target.location.index]
            assertTrue("a branch lands past the speed's call", target.opcode != Opcode.RETURN_VOID && target.method()?.toString() != SEEK_PLAYING)
        }
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
        // #618: the callback for a story's video starting tells the extension right before each return.
        val stockStart = viewer.methods.single { it.name == sites.started && it.parameters() == sites.startedParameters }
            .code().filter { it.opcode != Opcode.NOP }
        val startMethod = context.mutableClassDefBy(REEL_VIEWER_FRAGMENT).methods
            .single { it.name == sites.started && it.parameters() == sites.startedParameters }
        val started = startMethod.code().filter { it.opcode != Opcode.NOP }
        val returns = stockStart.count { it.opcode == Opcode.RETURN_VOID }
        assertTrue("$label: ${sites.started} returns", returns > 0)
        assertEquals("$label: ${sites.started} grows by one call per return", stockStart.size + returns, started.size)
        val calls = started.indices.filter { started[it].method()?.toString() == SEEK_PLAYING }
        assertEquals("$label: one call per return", returns, calls.size)
        val self = startMethod.implementation!!.registerCount - 1 - sites.startedParameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        for (at in calls) {
            assertEquals("$label: the call is right before a return", Opcode.RETURN_VOID, started[at + 1].opcode)
            val call = started[at] as RegisterRangeInstruction
            assertEquals("$label: the call hands on this", self, call.startRegister)
            assertEquals("$label: one register", 1, call.registerCount)
        }
        val all = startMethod.implementation!!.instructions.toList()
        for (instruction in all.filterIsInstance<BuilderOffsetInstruction>()) {
            assertTrue("$label: a branch skips the call to land on a return",
                all[instruction.target.location.index].opcode != Opcode.RETURN_VOID)
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

    private fun Method.parameters(): List<String> = parameterTypes.map(Any::toString)

    private fun Method.holds(string: String): Boolean = code().any {
        (it as? ReferenceInstruction)?.reference.let { reference -> reference is StringReference && reference.string == string }
    }

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private companion object {
        const val STRING = "Ljava/lang/String;"
    }
}
