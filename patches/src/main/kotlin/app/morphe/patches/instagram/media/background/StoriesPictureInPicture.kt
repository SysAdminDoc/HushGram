/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.instagram.stories.zoom.ViewerPlayback
import app.morphe.patches.instagram.stories.zoom.findViewerPlayback
import app.morphe.patches.instagram.stories.zoom.viewerInvoke
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val STORIES_PIP = "$EXTENSION_PACKAGE/media/StoriesPip;"

/** Asked with the viewer and the reason before each pause of a story; yes keeps the story in the window playing (#572). */
internal const val STORY_PAUSING = "$STORIES_PIP->pausing(Ljava/lang/Object;Ljava/lang/String;)Z"

/** Told the viewer at each resume of a story, so the extension knows which one plays. */
internal const val STORY_RESUMED = "$STORIES_PIP->resumed(Ljava/lang/Object;)V"

/** Answers the viewer's isResumed() check in its video start, yes for the viewer in the window too. */
internal const val STORY_STARTS_VIDEO = "$STORIES_PIP->startsVideo(Ljava/lang/Object;)Z"

/** The log line of the story viewer's video start, as it unbinds a player still bound. */
internal const val VIDEO_START_MARK = "Force unbinding player"

/**
 * Where Picture in picture for stories goes: the viewer's pause and resume, and its video start with
 * the index of the start's one isResumed() call, which returns before the player binds while the
 * screen isn't resumed.
 */
internal class StoriesPipSites(val playback: ViewerPlayback, val videoStart: Method, val resumedCheck: Int)

/**
 * The story viewer's pause and resume ([findViewerPlayback]) and its video start, the one viewer
 * method loading [VIDEO_START_MARK]. The extension pauses the story through the pause when the window
 * closes, so the viewer and its pause must be public. Fails naming [patch] before anything changes
 * when they aren't, or when the video start isn't found once with one isResumed() call whose answer
 * is read.
 */
internal fun BytecodePatchContext.findStoriesPip(patch: String): StoriesPipSites {
    val playback = findViewerPlayback(patch)
    val viewer = classDefBy(STORY_VIEWER)
    val closed = listOfNotNull(
        STORY_VIEWER.takeUnless { AccessFlags.PUBLIC.isSet(viewer.accessFlags) },
        playback.pause.key().takeUnless { AccessFlags.PUBLIC.isSet(playback.pause.accessFlags) },
    )
    if (closed.isNotEmpty()) throw PatchException("$patch: the extension can't reach ${closed.joinToString()}, not public")

    val starts = classesLoadingString(VIDEO_START_MARK).filter { it.type == STORY_VIEWER }.flatMap { classDef ->
        classDef.methods.filter { it.returnType == "V" && loadsString(it, VIDEO_START_MARK) }
    }
    val start = starts.singleOrNull()
        ?: throw PatchException("$patch: expected one video start in $STORY_VIEWER loading \"$VIDEO_START_MARK\", found ${starts.size}")
    val code = start.implementation?.instructions?.toList().orEmpty()
    val checks = code.indices.filter { code[it].callsIsResumed() }
    val check = checks.singleOrNull()
        ?: throw PatchException("$patch: expected one isResumed() call in the video start, found ${checks.size}")
    if (code.getOrNull(check + 1)?.opcode != Opcode.MOVE_RESULT) throw PatchException("$patch: the video start doesn't read its isResumed()")
    return StoriesPipSites(playback, start, check)
}

private fun Instruction.callsIsResumed(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.name == "isResumed" && call.parameterTypes.isEmpty() && call.returnType == "Z"
}

/** The register an instance call's receiver is in. */
private fun Instruction.receiver(): Int = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> throw PatchException("not a call: $opcode")
}

/**
 * Picture in picture for stories (#572). Instagram has no window for stories, so the extension asks
 * for one as you leave and holds the story viewer's pause the fragment makes as the screen pauses.
 * The pause first asks [STORY_PAUSING] and returns at once on a yes, and the resume tells
 * [STORY_RESUMED] which viewer plays, each after the hooks Keep stories playing while zooming and
 * See who a story mentions put first there. The pause's guard takes one of its locals. The video
 * start asks [STORY_STARTS_VIDEO] in place of its isResumed() call, so the next video story starts
 * in the window too. Then fills the extension's pause stub, which pauses a story through the
 * viewer's own pause, and its isResumed stub, which makes the call the start made.
 */
internal fun BytecodePatchContext.keepStoriesInPip(patch: String, sites: StoriesPipSites) {
    val playback = sites.playback
    val check = sites.videoStart.implementation!!.instructions.elementAt(sites.resumedCheck)
    val isResumed = "${((check as ReferenceInstruction).reference as MethodReference).definingClass}->isResumed()Z"
    val receiver = check.receiver()
    mutable(sites.videoStart).replaceInstruction(sites.resumedCheck, "invoke-static/range { v$receiver .. v$receiver }, $STORY_STARTS_VIDEO")
    mutable(playback.pause).apply {
        requireLocals(patch, 1)
        addAfterLeadingHooks(
            """
                invoke-static/range { p0 .. p1 }, $STORY_PAUSING
                move-result v0
                if-eqz v0, :start
                return-void
            """,
            guard = true,
        )
    }
    mutable(playback.resume).addAfterLeadingHooks("invoke-static/range { p0 .. p0 }, $STORY_RESUMED")
    fillStub(
        STORIES_PIP, "pause", "V", 2,
        """
            check-cast p0, $STORY_VIEWER
            ${playback.pause.viewerInvoke()} { p0, p1 }, ${playback.pause.key()}
            return-void
        """,
        parameters = listOf("Ljava/lang/Object;", "Ljava/lang/String;"),
    )
    fillStub(
        STORIES_PIP, "isResumed", "Z", 1,
        """
            check-cast p0, $STORY_VIEWER
            invoke-virtual { p0 }, $isResumed
            move-result p0
            return p0
        """,
    )
}
