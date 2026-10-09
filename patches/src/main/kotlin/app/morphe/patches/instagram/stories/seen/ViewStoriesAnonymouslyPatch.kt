/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val PATCH = "View stories anonymously"

/**
 * Keeps the stories you watch from being reported, which is what puts you on their viewer lists.
 * Only the viewing report stops: replies and reactions go out through their own requests.
 *
 * In the default selection with its switch off, since it changes what other people see. A second
 * switch, off to start, adds a Mark as seen button to the story viewer's header: a
 * story you tap it on is reported, alone, and the rest stay held back.
 *
 * Instagram also writes down on the phone that you watched a story, which greys its ring and sends
 * it to the end of the tray. While its view is held back that write is skipped too, so the ring
 * stays new (#92); a story you mark as seen is written down as it goes out ([keepStoriesNew]).
 */
@Suppress("unused")
val viewStoriesAnonymouslyPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "View stories anonymously",
    description = "Keeps you off the viewer list of the stories you watch. Replying or reacting still shows you. " +
        "An optional Mark as seen button lets you choose. Ghost mode turns it on too. Starts off. Turn it on in " +
        "HushGram settings > Stories.",
) {
    category("Ghost mode")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        // Found first, so a build without it changes nothing.
        val rings = findStoryRings()
        holdBackStoryViews()
        keepStoriesNew(rings)
        enableStatus("storySeen")
    }
}

/**
 * Instagram gathers the stories you've seen into a batch and posts it to media/seen/ from one
 * method of PendingReelSeenStateStore, the send: it builds the request from the batch and schedules
 * it, and returns without either when the batch is empty. Each caller (the viewer stopping or
 * closing, the tray loading) hands it a copy of its batch and clears its own. The hook goes first in
 * the send and answers the batch that goes out in its place: Instagram's own, one of the
 * extension's holding only the stories you marked, or none, and then the send returns, so a batch
 * held back is dropped, not kept for later. The store also retries batches, read back from what a
 * session before this one saved to disk; a second hook goes right before the retry builds its
 * request and, while views are held back, skips its native claim and request. The pending item is
 * checked again on every retry, without changing either owned map or making an empty batch. The only other call of
 * the request on 449, the Reset NUX developer option, sends a batch it
 * makes right there with nothing but a NUX in it, and the patch refuses any build with another
 * route to the request ([findStorySeen]).
 *
 * The Mark as seen button goes in from the story header binder, which every story on screen runs
 * through with its account, the story and its view holder.
 *
 * Everything is found and checked first ([findStorySeen]); nothing is written unless all of it is
 * there.
 */
internal fun BytecodePatchContext.holdBackStoryViews() {
    val found = findStorySeen()
    hookStorySend(found)
    hookStoryRetryQueue(found)
    hookStoryHeader(found)
    found.fillStubs()
}
