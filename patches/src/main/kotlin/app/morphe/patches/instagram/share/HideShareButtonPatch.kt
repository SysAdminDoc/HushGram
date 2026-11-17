/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.feed.FeedStateWrite
import app.morphe.patches.instagram.feed.feedRowState
import app.morphe.patches.instagram.feed.flagWrites
import app.morphe.patches.instagram.feed.isToString
import app.morphe.patches.instagram.feed.prepareFlagWrites
import app.morphe.patches.instagram.feed.printedFlag
import app.morphe.patches.instagram.feed.sameAs
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Hide the Share button"
private const val SHARE_BUTTON = "$EXTENSION_PACKAGE/share/ShareButton;"
internal const val SHARE_FEED_STATE = "$SHARE_BUTTON->feedState(I)Z"
internal const val HIDE_SHARE_IN_REELS = "$SHARE_BUTTON->hideInReels()Z"

/**
 * The labels Feed's action-row state prints its Share flags under in its toString: whether the
 * row shows the Share button, and whether it shows the share count.
 */
internal const val SHARE_ENABLED_LABEL = ", isShareEnabled="
internal const val SHARE_COUNT_LABEL = ", shouldShowShareCountInUfi="

/**
 * The id of the reel's own flag the check that decides whether a reel's action column has a Share
 * button reads to leave the button out (`DTq(0xe24407c8)` on 386214574), a narrow constant. Both of
 * the column's readers on 450 ask that check, the one that builds the button with its count and the
 * one that only needs a yes or no, and a no draws the column without the button. Instagram 456
 * dropped the check's UfiUseCase_shouldShowShareButton purge marker; it is the one method taking
 * the Reels viewer's config that loads this id.
 */
internal const val REELS_SHARE_OFF = 0xe24407c8.toInt()
private const val REELS_VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"

/**
 * Takes the Share button (the paper plane) and its count off posts in Feed and off reels. In
 * simple mode with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val hideShareButtonPatch = bytecodePatch(
    name = "Hide the Share button",
    description = "Takes the Share button and its count off posts in your feed and off reels. Starts off. Turn it " +
        "on in HushGram settings > Sharing.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("hideShareButton")
        val state = findFeedShareState()
        val reels = findReelsShareCheck()
        prepareFeedShare(state)()
        guardReelsShareCheck(reels)
        enableStatus("hideShareButton")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Feed's action-row state, its two Share flags, and every constructor write of them. */
internal class FeedShareState(val type: String, val flags: List<FieldReference>, val writes: List<FeedStateWrite>)

/**
 * Feed draws each post's action row from an immutable state whose toString prints whether the row
 * shows the Share button after [SHARE_ENABLED_LABEL], and whether it shows the share count after
 * [SHARE_COUNT_LABEL]. Each is a final boolean field of the state's own, set only by its
 * constructors, and on 450 the action row's binder hides the button, and drops its listeners, when
 * the first is false, and leaves the count out when the second is. Its writes are read as the
 * constructor is now, so one Hide the Repost button or Hide comments has already hooked is found
 * where it sits.
 *
 * Fails before any change when no class prints the label or more than one does, when either label
 * isn't followed by such a flag, when both name the same one, when a method other than a
 * constructor sets one or none sets it, or when a write has no register for the answer.
 */
internal fun BytecodePatchContext.findFeedShareState(): FeedShareState {
    val state = feedRowState(PATCH, SHARE_ENABLED_LABEL)
    val toString = state.methods.single { it.isToString() }
    val labels = listOf(SHARE_ENABLED_LABEL, SHARE_COUNT_LABEL)
    val flags = labels.map { toString.printedFlag(PATCH, state.type, it) }
    if (flags[0].sameAs(flags[1])) refuse("${state.type}->toString prints one flag for both the button and the count")
    val stored = mutableClassDefBy(state.type)
    val writes = flags.zip(labels).flatMap { (flag, label) ->
        flagWrites(PATCH, stored, flag).ifEmpty {
            refuse("${state.type}'s constructor never sets what it prints as \"$label\"")
        }
    }
    return FeedShareState(state.type, flags, writes)
}

/**
 * Checks every write and answers the change, made only when called: right before each constructor
 * write of a Share flag, the value goes through [SHARE_FEED_STATE], so a state built while the
 * switch is on keeps the button and its count off however often Feed draws the row from it.
 */
internal fun BytecodePatchContext.prepareFeedShare(found: FeedShareState) =
    prepareFlagWrites(PATCH, found.type, "Share", found.writes, SHARE_FEED_STATE)

/**
 * The one method taking the Reels viewer's config that loads [REELS_SHARE_OFF]: a static check
 * answering a boolean (`LX/07Xo;->A01` on 386214574), with a local register for the guard to
 * borrow. Fails when none loads the id, more than one does, or the one that does has another shape.
 */
internal fun BytecodePatchContext.findReelsShareCheck(): Method {
    val holders = classesLoading(REELS_SHARE_OFF.toLong()).sortedBy { it.type }.flatMap { it.methods }.filter { method ->
        method.parameterTypes.any { it.toString() == REELS_VIEWER_CONFIG } &&
            method.implementation?.instructions?.any { it.loadsShareOff() } == true
    }
    val check = holders.singleOrNull() ?: refuse(
        "expected one method taking the Reels viewer's config and loading the reel's Share flag id, found " +
            if (holders.isEmpty()) "none" else holders.joinToString { "${it.definingClass}->${it.name}" },
    )
    val where = "${check.definingClass}->${check.name}"
    if (!AccessFlags.STATIC.isSet(check.accessFlags) || check.returnType != "Z" || check.implementation == null) {
        refuse("$where, loading the reel's Share flag id, isn't a static check answering a boolean")
    }
    if (check.localRegisterCount() < 1) refuse("$where has no local register for the guard")
    return check
}

/** A narrow constant load of [REELS_SHARE_OFF]. */
private fun Instruction.loadsShareOff(): Boolean =
    !opcode.setsWideRegister() && (this as? NarrowLiteralInstruction)?.narrowLiteral == REELS_SHARE_OFF

/**
 * Asks [HIDE_SHARE_IN_REELS] first thing in the Reels Share check, and answers no when it says to
 * hide, the answer Instagram itself gives for a reel that can't be shared.
 */
internal fun BytecodePatchContext.guardReelsShareCheck(found: Method) {
    val method = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString) &&
            it.returnType == found.returnType
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_SHARE_IN_REELS
            move-result v0
            if-eqz v0, :decide
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("decide", method.getInstruction(0)),
    )
}
