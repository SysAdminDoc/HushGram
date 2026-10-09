/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.comments

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.FeedStateWrite
import app.morphe.patches.instagram.feed.feedRowState
import app.morphe.patches.instagram.feed.flagWrites
import app.morphe.patches.instagram.feed.isToString
import app.morphe.patches.instagram.feed.prepareFlagWrites
import app.morphe.patches.instagram.feed.printedFlag
import app.morphe.patches.instagram.feed.sameAs
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Hide comments"
internal const val COMMENTS_FEED_STATE = "$EXTENSION_PACKAGE/feed/CommentsButton;->feedState(I)Z"

/**
 * The labels Feed's action-row state prints its comment flags under in its toString: whether the
 * row shows the Comment button, and whether it shows the comment count.
 */
internal const val COMMENTS_ENABLED_LABEL = ", isCommentsEnabled="
internal const val COMMENT_COUNT_LABEL = ", shouldShowCommentCountInUfi="

/**
 * Takes the Comment button and the comment count off the action row of posts in Feed. In simple
 * mode with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val hideCommentsPatch = bytecodePatch(
    name = "Hide comments",
    description = "Takes the Comment button and the comment count off the posts in your feed. Off until you turn it " +
        "on. Switch: HushGram settings > Comments.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("hideComments")
        val state = findFeedCommentState()
        prepareFeedComments(state)()
        enableStatus("hideComments")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Feed's action-row state, its two comment flags, and every constructor write of them. */
internal class FeedCommentState(val type: String, val flags: List<FieldReference>, val writes: List<FeedStateWrite>)

/**
 * Feed draws each post's action row from an immutable state whose toString prints whether the row
 * shows the Comment button after [COMMENTS_ENABLED_LABEL], and whether it shows the comment count
 * after [COMMENT_COUNT_LABEL]. Each is a final boolean field of the state's own, set only by its
 * constructors, and on 450 the action row's binders skip the button and the count when it's false.
 * Its writes are read as the constructor is now, so one Hide the Repost button has already hooked
 * is found where it sits.
 *
 * Fails before any change when no class prints the label or more than one does, when either label
 * isn't followed by such a flag, when both name the same one, when a method other than a
 * constructor sets one or none sets it, or when a write has no register for the answer.
 */
internal fun BytecodePatchContext.findFeedCommentState(): FeedCommentState {
    val state = feedRowState(PATCH, COMMENTS_ENABLED_LABEL)
    val toString = state.methods.single { it.isToString() }
    val flags = listOf(COMMENTS_ENABLED_LABEL, COMMENT_COUNT_LABEL).map { toString.printedFlag(PATCH, state.type, it) }
    if (flags[0].sameAs(flags[1])) refuse("${state.type}->toString prints one flag for both the button and the count")
    val stored = mutableClassDefBy(state.type)
    val writes = flags.zip(listOf(COMMENTS_ENABLED_LABEL, COMMENT_COUNT_LABEL)).flatMap { (flag, label) ->
        stored.flagWrites(PATCH, flag).ifEmpty {
            refuse("${state.type}'s constructor never sets what it prints as \"$label\"")
        }
    }
    return FeedCommentState(state.type, flags, writes)
}

/**
 * Checks every write and answers the change, made only when called: right before each constructor
 * write of a comment flag, the value goes through [COMMENTS_FEED_STATE], so a state built while the
 * switch is on keeps the button and its count off however often Feed draws the row from it.
 */
internal fun BytecodePatchContext.prepareFeedComments(found: FeedCommentState) =
    prepareFlagWrites(PATCH, found.type, "comment", found.writes, COMMENTS_FEED_STATE)
