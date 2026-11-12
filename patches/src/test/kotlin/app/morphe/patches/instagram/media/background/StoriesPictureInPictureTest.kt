/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.stories.mentions.HOLDS_STORY
import app.morphe.patches.instagram.stories.mentions.STORY_MENTIONS
import app.morphe.patches.instagram.stories.mentions.findStoryHold
import app.morphe.patches.instagram.stories.mentions.hookStoryHold
import app.morphe.patches.instagram.stories.mentions.storyHoldStubs
import app.morphe.patches.instagram.stories.zoom.PAUSE_MARK
import app.morphe.patches.instagram.stories.zoom.PINCH_REASON
import app.morphe.patches.instagram.stories.zoom.RESUME_MARK
import app.morphe.patches.instagram.stories.zoom.ZOOM_PAUSED
import app.morphe.patches.instagram.stories.zoom.ZOOM_RESUMED
import app.morphe.patches.instagram.stories.zoom.findStoryZoomPause
import app.morphe.patches.instagram.stories.zoom.keepPlayingOnZoom
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Picture in picture for stories (#572): the hooks are in the extension, and in every build the
 * story viewer's pause asks the extension first and returns on a yes, its resume reports the viewer,
 * the extension's pause stub calls the viewer's pause, and the hooks sit after the ones Keep stories
 * playing while zooming and See who a story mentions put first, whichever patch goes in first.
 */
class StoriesPictureInPictureTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(STORIES_PIP).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$STORIES_PIP->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val hooks = listOf(
            STORY_PAUSING, STORY_RESUMED, STORY_STARTS_VIDEO,
            "$STORIES_PIP->pause(Ljava/lang/Object;Ljava/lang/String;)V", "$STORIES_PIP->isResumed(Ljava/lang/Object;)Z",
        )
        for (hook in hooks) {
            assertTrue("StoriesPip declares no $hook: $declared", hook in declared)
        }
    }

    @Test
    fun eachBuildHooksTheViewer() {
        var checked = 0
        for (bundle in bundles() + Fixtures.otherBuilds()) {
            val classes = viewerClasses(bundle)
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes) + ExtensionDex.classDef(STORIES_PIP))
            val sites = context.findStoriesPip("test")
            val playback = sites.playback
            val stockPause = playback.pause.code()
            val stockResume = playback.resume.code()
            val stockStart = sites.videoStart.implementation!!.instructions.toList()
            val stockCheck = stockStart[sites.resumedCheck]
            val viewer = (stockCheck as? RegisterRangeInstruction)?.startRegister ?: (stockCheck as FiveRegisterInstruction).registerC

            context.keepStoriesInPip("test", sites)

            val label = bundle.name
            val start = context.raw(sites.videoStart)
            val asks = start[sites.resumedCheck]
            assertEquals("$label: the video start asks the extension", STORY_STARTS_VIDEO, reference(asks))
            assertEquals("$label: in place of isResumed()", Opcode.INVOKE_STATIC_RANGE, asks.opcode)
            assertEquals("$label: about the same viewer", viewer, (asks as RegisterRangeInstruction).startRegister)
            assertEquals("$label: alone", 1, asks.registerCount)
            assertEquals("$label: and reads the answer as before", Opcode.MOVE_RESULT, start[sites.resumedCheck + 1].opcode)
            assertEquals("$label: the rest of the start is stock", stockStart.without(stockCheck), start.without(asks))
            val pause = context.code(playback.pause)
            assertEquals("$label: the pause asks first", STORY_PAUSING, reference(pause[0]))
            assertEquals("$label: with the viewer and the reason", parameterStart(context, playback.pause),
                (pause[0] as RegisterRangeInstruction).startRegister)
            assertEquals("$label: the answer", Opcode.MOVE_RESULT, pause[1].opcode)
            assertEquals("$label: is tested", Opcode.IF_EQZ, pause[2].opcode)
            assertEquals("$label: and a yes returns", Opcode.RETURN_VOID, pause[3].opcode)
            assertEquals("$label: the stock pause follows", stockPause.map { it.kind() }, pause.drop(4).map { it.kind() })

            val resume = context.code(playback.resume)
            assertEquals("$label: the resume reports first", STORY_RESUMED, reference(resume[0]))
            assertEquals("$label: with the viewer", parameterStart(context, playback.resume),
                (resume[0] as RegisterRangeInstruction).startRegister)
            assertEquals("$label: the stock resume follows", stockResume.map { it.kind() }, resume.drop(1).map { it.kind() })

            val stub = context.mutableClassDefBy(STORIES_PIP).methods.single { it.name == "pause" }.code()
            assertEquals("$label: the stub casts the viewer", Opcode.CHECK_CAST, stub[0].opcode)
            assertEquals("$label: and calls the viewer's pause", playback.pause.key(), reference(stub[1]))

            val resumed = context.mutableClassDefBy(STORIES_PIP).methods.single { it.name == "isResumed" }.code()
            assertEquals("$label: the isResumed stub casts the viewer", Opcode.CHECK_CAST, resumed[0].opcode)
            assertEquals("$label: and makes the start's own call", reference(stockCheck), reference(resumed[1]))
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    /** Whichever goes in first, the other patches' hooks come first and every path still reaches ours. */
    @Test
    fun theOtherStoryHooksStayFirst() {
        for (bundle in bundles()) for (oursFirst in listOf(true, false)) {
            val label = "${bundle.name}, ${if (oursFirst) "picture in picture" else "the zoom and the hold"} first"
            val classes = (viewerClasses(bundle) + FixtureDex.classesLoading(bundle, PINCH_REASON)).distinctBy { it.type }
            val context = PatchContexts.of(
                FixtureDex.withStringPools(bundle, classes) + ExtensionDex.classDef(STORIES_PIP) + ExtensionDex.classDef(STORY_MENTIONS),
            )
            val sites = context.findStoriesPip("test")
            val playback = sites.playback
            val zoom = context.findStoryZoomPause()
            val hold = context.findStoryHold()
            if (oursFirst) context.keepStoriesInPip("test", sites)
            context.keepPlayingOnZoom(zoom)
            context.hookStoryHold(hold, context.storyHoldStubs())
            if (!oursFirst) context.keepStoriesInPip("test", sites)

            val pause = context.raw(playback.pause)
            assertEquals("$label: one zoom report", 1, pause.count { reference(it) == ZOOM_PAUSED })
            assertEquals("$label: one guard", 1, pause.count { reference(it) == STORY_PAUSING })
            val guard = pause.indexOfFirst { reference(it) == STORY_PAUSING }
            assertTrue("$label: the zoom reports before the guard", pause.indexOfFirst { reference(it) == ZOOM_PAUSED } < guard)
            assertEquals("$label: a no goes on to Instagram's own pause", guard + 4, (pause[guard + 2] as BuilderInstruction21t).target.location.index)

            val resume = context.raw(playback.resume)
            assertEquals("$label: one report", 1, resume.count { reference(it) == STORY_RESUMED })
            assertEquals("$label: one zoom report", 1, resume.count { reference(it) == ZOOM_RESUMED })
            val held = resume.indexOfFirst { reference(it) == HOLDS_STORY }
            val ours = resume.indexOfFirst { reference(it) == STORY_RESUMED }
            assertTrue("$label: the hold is asked before the report", held in 0 until ours)
            val playOn = (resume[held + 2] as BuilderInstruction21t).target.location.index
            assertTrue("$label: a story not held reaches the report", playOn <= ours)
        }
    }

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
            .also { assertTrue("no declared build has a fixture", it.isNotEmpty()) }
    }

    private fun viewerClasses(bundle: File) =
        listOf(PAUSE_MARK, RESUME_MARK, VIDEO_START_MARK).flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }

    /** The code's opcodes without [left] out and the alignment nops, which can move as a body is rewritten. */
    private fun List<Instruction>.without(left: Instruction): List<Opcode> =
        filter { it !== left && it.opcode != Opcode.NOP }.map { it.kind() }

    /** The first parameter register: the viewer, `this` in an instance method. */
    private fun parameterStart(context: BytecodePatchContext, method: Method): Int {
        val now = context.mutable(method)
        val words = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } +
            if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        return now.implementation!!.registerCount - words
    }

    private fun BytecodePatchContext.code(method: Method) = mutable(method).code()

    private fun BytecodePatchContext.raw(method: Method): List<Instruction> = mutable(method).implementation!!.instructions.toList()

    private fun reference(instruction: Instruction): String? = (instruction as? ReferenceInstruction)?.reference?.toString()

    /** An insert can push a branch past a plain goto's reach, and dexlib2 widens it. */
    private fun Instruction.kind(): Opcode =
        if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    /** The code without the alignment nops dexlib2 adds or drops around a payload. */
    private fun Method.code(): List<Instruction> = implementation?.instructions?.filter { it.opcode != Opcode.NOP } ?: emptyList()
}
