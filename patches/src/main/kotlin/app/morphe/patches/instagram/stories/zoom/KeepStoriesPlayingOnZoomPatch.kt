/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.zoom

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

private const val PATCH = "Keep stories playing while zooming"
internal const val STORY_ZOOM_PLAY = "$EXTENSION_PACKAGE/stories/StoryZoomPlay;"
internal const val KEEP_PLAYING = "$STORY_ZOOM_PLAY->keepPlaying()Z"
internal const val ZOOM_PAUSED = "$STORY_ZOOM_PLAY->paused(Ljava/lang/String;)V"
internal const val ZOOM_RESUMED = "$STORY_ZOOM_PLAY->resumed()V"

/** The reason the story viewer is given when a pinch pauses a story. Redex keeps the text. */
internal const val PINCH_REASON = "paused_for_pinch_to_zoom"

/** What the viewer's pause logs to its flow when it pauses a story. */
internal const val PAUSE_MARK = "pause_story"

/** What the viewer's resume logs when it declines to play a story again. */
internal const val RESUME_MARK = "resume_story_request_skipped"

private const val STRING = "Ljava/lang/String;"

/**
 * Stops a pinch on a story from pausing it. Off to start: Instagram pauses the story for the zoom
 * and plays it again when the fingers lift.
 */
@Suppress("unused")
val keepStoriesPlayingOnZoomPatch = bytecodePatch(
    name = "Keep stories playing while zooming",
    description = "Keeps a story playing while you pinch to zoom it, instead of pausing it. A story you hold still " +
        "first stays paused. Starts off. Turn it on in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyZoomPlay")
        keepPlayingOnZoom(findStoryZoomPause())
        enableStatus("storyZoomPlay")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The story viewer's pause and resume, and the pinch's pause: where each hook goes. */
internal class StoryZoomPause(val pinch: Located, val pause: Located, val resume: Located)

/** A method named by its class, its name and its parameter and return types. */
internal class Located(val type: String, val name: String, val parameters: List<String>, val returnType: String)

private fun Method.located() =
    Located(definingClass, name, parameterTypes.map(CharSequence::toString), returnType)

/**
 * Finds the three methods the hooks go in. The pinch's pause is the one `run()V` loading
 * [PINCH_REASON] (the reason enum's own initializer loads it too, but isn't a `run`). The viewer's
 * pause is its one static `(viewer, String)V` loading [PAUSE_MARK], and its resume the one static
 * `(viewer, String, boolean)V` loading [RESUME_MARK]. Fails before anything changes when any of the
 * three is missing or found more than once.
 */
internal fun BytecodePatchContext.findStoryZoomPause(): StoryZoomPause {
    val runs = classesLoadingString(PINCH_REASON).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == "run" && method.returnType == "V" && method.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(method.accessFlags) && loadsString(method, PINCH_REASON)
        }
    }
    val pinch = runs.singleOrNull() ?: refuse("expected one run() loading \"$PINCH_REASON\", found ${runs.size}")
    val viewer = classesLoadingString(PAUSE_MARK).filter { it.type == STORY_VIEWER }
    fun inViewer(mark: String, parameters: List<String>, what: String): Method {
        val found = classesLoadingString(mark).filter { it.type == STORY_VIEWER }.flatMap { classDef ->
            classDef.methods.filter { method ->
                AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                    method.parameterTypes.map(CharSequence::toString) == parameters && loadsString(method, mark)
            }
        }
        return found.singleOrNull() ?: refuse("expected one $what in $STORY_VIEWER loading \"$mark\", found ${found.size}")
    }
    if (viewer.isEmpty()) refuse("no $STORY_VIEWER loads \"$PAUSE_MARK\"")
    val pause = inViewer(PAUSE_MARK, listOf(STORY_VIEWER, STRING), "pause")
    val resume = inViewer(RESUME_MARK, listOf(STORY_VIEWER, STRING, "Z"), "resume")
    return StoryZoomPause(pinch.located(), pause.located(), resume.located())
}

private fun BytecodePatchContext.mutable(at: Located) = mutableClassDefBy(at.type).methods.single {
    it.name == at.name && it.returnType == at.returnType && it.parameterTypes.map(CharSequence::toString) == at.parameters
}

/**
 * Reports each pause and resume of the viewer to the extension, and returns from the pinch's pause
 * before it does anything when the extension says to keep playing.
 */
internal fun BytecodePatchContext.keepPlayingOnZoom(found: StoryZoomPause) {
    mutable(found.pause).addInstructions(0, "invoke-static/range { p1 .. p1 }, $ZOOM_PAUSED")
    mutable(found.resume).addInstructions(0, "invoke-static { }, $ZOOM_RESUMED")
    val pinch = mutable(found.pinch)
    pinch.requireLocals(PATCH, 1)
    pinch.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEP_PLAYING
            move-result v0
            if-eqz v0, :pause
            return-void
        """,
        ExternalLabel("pause", pinch.getInstruction(0)),
    )
}
