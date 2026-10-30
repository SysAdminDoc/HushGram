/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.watchhistory

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patches.instagram.misc.extension.loadsConstString

/**
 * The method that builds Instagram's request posting the reels you watched to
 * clips/write_seen_state/. It's the one method holding both that path and the name of the error it
 * logs when the watch progress can't be encoded, and its class holds the pending batch.
 */
internal object WatchedReelsRequestFingerprint : Fingerprint(
    filters = listOf(string("clips/write_seen_state/")),
    custom = { method, _ -> method.loadsConstString("PendingClipsSeenState#progressImpressionsEncodeFailure") },
)
