/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.instagram.stories.zoom.ViewerPlayback
import app.morphe.patches.instagram.stories.zoom.findViewerPlayback
import app.morphe.patches.instagram.stories.zoom.viewerInvoke
import com.android.tools.smali.dexlib2.AccessFlags

internal const val STORIES_PIP = "$EXTENSION_PACKAGE/media/StoriesPip;"

/** Asked with the viewer and the reason before each pause of a story; yes keeps the story in the window playing (#572). */
internal const val STORY_PAUSING = "$STORIES_PIP->pausing(Ljava/lang/Object;Ljava/lang/String;)Z"

/** Told the viewer at each resume of a story, so the extension knows which one plays. */
internal const val STORY_RESUMED = "$STORIES_PIP->resumed(Ljava/lang/Object;)V"

/**
 * The story viewer's pause and resume ([findViewerPlayback]), which Picture in picture for stories
 * hooks. The extension pauses the story through the pause when the window closes, so the viewer and
 * its pause must be public. Fails naming [patch] before anything changes when they aren't.
 */
internal fun BytecodePatchContext.findStoriesPip(patch: String): ViewerPlayback {
    val playback = findViewerPlayback(patch)
    val viewer = classDefBy(STORY_VIEWER)
    val closed = listOfNotNull(
        STORY_VIEWER.takeUnless { AccessFlags.PUBLIC.isSet(viewer.accessFlags) },
        playback.pause.key().takeUnless { AccessFlags.PUBLIC.isSet(playback.pause.accessFlags) },
    )
    if (closed.isNotEmpty()) throw PatchException("$patch: the extension can't reach ${closed.joinToString()}, not public")
    return playback
}

/**
 * Picture in picture for stories (#572). Instagram has no window for stories, so the extension asks
 * for one as you leave and holds the story viewer's pause the fragment makes as the screen pauses.
 * The pause first asks [STORY_PAUSING] and returns at once on a yes, and the resume tells
 * [STORY_RESUMED] which viewer plays, each after the hooks Keep stories playing while zooming and
 * See who a story mentions put first there. The pause's guard takes one of its locals. Then fills
 * the extension's pause stub, which pauses a story through the viewer's own pause.
 */
internal fun BytecodePatchContext.keepStoriesInPip(patch: String, playback: ViewerPlayback) {
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
}
