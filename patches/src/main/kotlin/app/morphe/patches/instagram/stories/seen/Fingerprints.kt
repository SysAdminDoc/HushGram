/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * The strings of the method that builds Instagram's request telling it which stories you've seen, a
 * method of the batch it sends: the media/seen/ path and the name it sends the ids of stories it
 * counts as seen under. 451's 385713073 asks a string pool for the name, 450 held it, so
 * [findStorySeen] takes either.
 */
internal const val SEEN_PATH = "media/seen/?reel=%s&live_vod=0"
internal const val FORCE_SEEN_KEY = "force_seen_story_ids"

/**
 * The strings of PendingReelSeenStateStore's method that reads the batches a session saved to disk.
 * Its class is the store that sends each batch.
 */
internal const val PENDING_PREFIX = "pending_reel_seen_states_"
internal const val PENDING_READ = "PendingReelSeenStateStore.deserializeFromDisk"

/**
 * The trace section of the story item binder's method the Mark as seen button binds from. 450 named
 * it bindHeaderViews. 451 has no header binder: the item binder's media bind and item bind (the two
 * callers 450's header binder had) now call only its toolbar binder, which takes the same account,
 * story and view holder on every bind, so the button binds from there. It still goes in the
 * header's row of buttons, found by name under the holder's item view.
 */
internal const val STORY_BINDER_TRACE = "ReelViewerItemBinder.bindToolbarViews"

/**
 * The story item binder's method the button binds from, the one method holding [STORY_BINDER_TRACE].
 * It's handed the account signed in, the story on screen and the story's view holder.
 */
internal object StoryHeaderBinderFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string(STORY_BINDER_TRACE)),
)

/**
 * RecyclerView's ViewHolder constructor, the one method holding the message it throws for a null
 * item view. Its class keeps the item view every holder hands it, the story viewer's included.
 */
internal object ViewHolderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(string("itemView may not be null")),
)
