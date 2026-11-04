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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep stories playing while zooming"
internal const val STORY_ZOOM_PLAY = "$EXTENSION_PACKAGE/stories/StoryZoomPlay;"
internal const val KEEP_PLAYING = "$STORY_ZOOM_PLAY->keepPlaying()Z"
internal const val ZOOM_PAUSED = "$STORY_ZOOM_PLAY->paused(Ljava/lang/String;)V"
internal const val ZOOM_RESUMED = "$STORY_ZOOM_PLAY->resumed()V"
internal const val PINCH_RESUMED = "$STORY_ZOOM_PLAY->pinchResumed()V"
internal const val DRAGGING = "$STORY_ZOOM_PLAY->dragging(I)I"

/** The viewer's gesture controller, whose drag check the viewer's resume asks. Redex keeps the name. */
internal const val GESTURES = "mGestureController"

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

/**
 * The story viewer's pause and resume, and the pinch's pause: where each hook goes. [viewerField] is
 * the field the pinch's pause reads the viewer from, as a smali field reference. [dragAnswer] is the
 * index in the resume of the `move-result` taking its drag check's answer.
 */
internal class StoryZoomPause(
    val pinch: Located,
    val pause: Located,
    val resume: Located,
    val viewerField: String,
    val dragAnswer: Int,
)

/** A method named by its class, its name and its parameter and return types. */
internal class Located(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val isStatic: Boolean = true,
) {
    val descriptor get() = "$type->$name(${parameters.joinToString("")})$returnType"

    /** The invoke that calls it with the viewer first, as `this` when it's an instance method. */
    val invoke get() = if (isStatic) "invoke-static" else "invoke-virtual"
}

private fun Method.located() =
    Located(definingClass, name, parameterTypes.map(CharSequence::toString), returnType, AccessFlags.STATIC.isSet(accessFlags))

/** The story viewer's own pause and resume, which See who a story mentions holds a story with too. */
internal class ViewerPlayback(val pause: Method, val resume: Method)

/** The invoke that calls one of the viewer's own methods with the viewer first, as `this` when it's an instance method. */
internal fun Method.viewerInvoke() = if (AccessFlags.STATIC.isSet(accessFlags)) "invoke-static" else "invoke-virtual"

/**
 * The story viewer's pause, its one static `(viewer, String)V` loading [PAUSE_MARK], and its
 * resume, the one static `(viewer, String, boolean)V` loading [RESUME_MARK]. 454 made the resume an
 * instance `(String, boolean)V`, so either form counts, the viewer in `p0` both ways. Fails naming
 * [patch] before anything changes when either is missing or found more than once.
 */
internal fun BytecodePatchContext.findViewerPlayback(patch: String): ViewerPlayback {
    fun stop(detail: String): Nothing = throw PatchException("$patch: $detail")
    fun inViewer(mark: String, parameters: List<String>, what: String): Method {
        val found = classesLoadingString(mark).filter { it.type == STORY_VIEWER }.flatMap { classDef ->
            classDef.methods.filter { method ->
                val static = AccessFlags.STATIC.isSet(method.accessFlags)
                method.returnType == "V" && loadsString(method, mark) &&
                    method.parameterTypes.map(CharSequence::toString) == if (static) listOf(STORY_VIEWER) + parameters else parameters
            }
        }
        return found.singleOrNull() ?: stop("expected one $what in $STORY_VIEWER loading \"$mark\", found ${found.size}")
    }
    if (classesLoadingString(PAUSE_MARK).none { it.type == STORY_VIEWER }) stop("no $STORY_VIEWER loads \"$PAUSE_MARK\"")
    return ViewerPlayback(
        inViewer(PAUSE_MARK, listOf(STRING), "pause"),
        inViewer(RESUME_MARK, listOf(STRING, "Z"), "resume"),
    )
}

/**
 * Finds the three methods the hooks go in. The pinch's pause is the one `run()V` loading
 * [PINCH_REASON] (the reason enum's own initializer loads it too, but isn't a `run`). The viewer's
 * pause and resume are [findViewerPlayback]'s. The resume's drag check is the first `()Z` call
 * within a few instructions of its one read of [GESTURES]. Fails before anything changes when any
 * of the three is missing or found more than once, when the pinch's pause doesn't read the viewer
 * from one field, or when the drag check isn't where it's looked for.
 */
internal fun BytecodePatchContext.findStoryZoomPause(): StoryZoomPause {
    val runs = classesLoadingString(PINCH_REASON).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == "run" && method.returnType == "V" && method.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(method.accessFlags) && loadsString(method, PINCH_REASON)
        }
    }
    val pinch = runs.singleOrNull() ?: refuse("expected one run() loading \"$PINCH_REASON\", found ${runs.size}")
    val playback = findViewerPlayback(PATCH)
    val pause = playback.pause
    val resume = playback.resume
    // The pinch's pause calls the resume from its own class, so the resume must be reachable from there.
    if (!reaches(pinch.definingClass, resume)) {
        refuse("$STORY_VIEWER->${resume.name} can't be called from ${pinch.definingClass}, where the pinch's pause plays the story")
    }
    val fields = pinch.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .filter { it.type == STORY_VIEWER }
        .map { "${it.definingClass}->${it.name}:${it.type}" }
        .distinct()
    val viewerField = fields.singleOrNull()
        ?: refuse("expected the pinch pause to read one $STORY_VIEWER field, found ${fields.size}")
    return StoryZoomPause(pinch.located(), pause.located(), resume.located(), viewerField, dragAnswer(resume))
}

/** Whether code in [caller] may call [method]: public, its own class, or a package mate unless private. */
private fun reaches(caller: String, method: Method): Boolean = when {
    AccessFlags.PUBLIC.isSet(method.accessFlags) || caller == method.definingClass -> true
    AccessFlags.PRIVATE.isSet(method.accessFlags) -> false
    else -> caller.substringBeforeLast('/') == method.definingClass.substringBeforeLast('/')
}

private fun dragAnswer(resume: Method): Int {
    val code = resume.implementation?.instructions?.toList().orEmpty()
    val reads = code.indices.filter { index ->
        val field = (code[index] as? ReferenceInstruction)?.reference as? FieldReference
        code[index].opcode == Opcode.IGET_OBJECT && field?.definingClass == STORY_VIEWER && field.name == GESTURES
    }
    val read = reads.singleOrNull() ?: refuse("expected the resume to read $GESTURES once, found ${reads.size}")
    val check = (read + 1..minOf(read + 4, code.size - 2)).firstOrNull { index ->
        val called = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        code[index].opcode == Opcode.INVOKE_VIRTUAL && called?.returnType == "Z" && called.parameterTypes.isEmpty()
    } ?: refuse("no ()Z drag check follows the resume's read of $GESTURES")
    if (code[check + 1].opcode != Opcode.MOVE_RESULT) refuse("the resume's drag check answer isn't kept")
    return check + 1
}

private fun BytecodePatchContext.mutable(at: Located) = mutableClassDefBy(at.type).methods.single {
    it.name == at.name && it.returnType == at.returnType && it.parameterTypes.map(CharSequence::toString) == at.parameters
}

/**
 * Reports each pause and resume of the viewer to the extension. When the extension says to keep
 * playing, the pinch's pause plays the story again and returns before pausing it: the first finger
 * down already paused the story ("tapped"), and that pause would hold until the fingers lift. The
 * resume goes through the viewer's own, called the way lifting a finger calls it ("resume", true).
 * `p0` takes the flag since nothing reads it after; the method returns right away. The pinch's
 * moving fingers count as a drag to that resume, which won't play a story being dragged, so the
 * extension also answers the resume's drag check, and hears when the pinch's call is done. The drag
 * check is counted again on the resume as it is now, since See who a story mentions may have put
 * its hold in front of it after [StoryZoomPause.dragAnswer] was counted, and its hook goes in
 * before the resume's first hook so that count still holds.
 */
internal fun BytecodePatchContext.keepPlayingOnZoom(found: StoryZoomPause) {
    mutable(found.pause).addInstructions(0, "invoke-static/range { p1 .. p1 }, $ZOOM_PAUSED")
    val resume = mutable(found.resume)
    val dragAnswer = dragAnswer(resume)
    val answer = (resume.getInstruction(dragAnswer) as OneRegisterInstruction).registerA
    resume.addInstructions(
        dragAnswer + 1,
        """
            invoke-static/range { v$answer .. v$answer }, $DRAGGING
            move-result v$answer
        """,
    )
    resume.addInstructions(0, "invoke-static { }, $ZOOM_RESUMED")
    val pinch = mutable(found.pinch)
    pinch.requireLocals(PATCH, 2)
    pinch.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEP_PLAYING
            move-result v0
            if-eqz v0, :pause
            iget-object v0, p0, ${found.viewerField}
            const-string v1, "resume"
            const/4 p0, 0x1
            ${found.resume.invoke} { v0, v1, p0 }, ${found.resume.descriptor}
            invoke-static { }, $PINCH_RESUMED
            return-void
        """,
        ExternalLabel("pause", pinch.getInstruction(0)),
    )
}
