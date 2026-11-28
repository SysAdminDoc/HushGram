/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.swipereply

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/** What a refusal from the side tap half of the patch is labelled with. */
private const val SIDE_TAPS = "Stop story side taps"
internal const val STORY_TAPS = "$EXTENSION_PACKAGE/stories/StoryTaps;"
internal const val KEEPS_TAP = "$STORY_TAPS->keepsTap()Z"

/** What the viewer's tap forward handler logs a tap as, and the tap back handler. */
internal const val TAP_FORWARD = "tap_forward"
internal const val TAP_BACK = "tap_back"

/** Both handlers take the tap's x and y, boxed, and Instagram's own seek path hands them nulls. */
internal val TAP_PARAMETERS = listOf("Ljava/lang/Float;", "Ljava/lang/Float;")

/*
 * Stop story side taps (#887), the second switch of Stop swipe up to reply. It rides on that patch
 * because both change a story gesture and the Stories group is full.
 *
 * On 457 the story viewer's item delegate (ReelViewerItemDelegateImpl) takes a confirmed single
 * tap, gives the story's stickers, links and buttons their turn, and hands what's left to the
 * viewer: a tap on the left side to its tap back handler, any other to its tap forward handler
 * (ReelViewerFragment.GY8 and GYF on 386314286). They're the viewer's two instance (Float,
 * Float)V methods holding "tap_back" and "tap_forward", and only the delegate's tap calls them.
 * They log the tap and call the viewer's moves, which swipes, the story ending, and Instagram's
 * own next and previous buttons call directly. The patch asks [KEEPS_TAP] first thing in both
 * handlers and returns on a no, so only taps stay put.
 */

/** The viewer's tap forward and tap back handlers, by name, as found before anything changes. */
internal data class TapHandlers(val forward: String, val back: String)

/**
 * Finds the viewer's two tap handlers: for each of [TAP_FORWARD] and [TAP_BACK], the one instance
 * method of the viewer taking [TAP_PARAMETERS], returning void and loading the string. Throws
 * before anything changes when either isn't exactly one method, when they're the same one, or when
 * a branch lands on a handler's first instruction, where the guard goes.
 */
internal fun BytecodePatchContext.findTapHandlers(): TapHandlers {
    val viewer = classDefByOrNull(STORY_VIEWER) ?: throw PatchException("$SIDE_TAPS: no $STORY_VIEWER")
    fun handler(mark: String): Method {
        val found = viewer.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
                method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == TAP_PARAMETERS &&
                loadsString(method, mark)
        }
        val one = found.singleOrNull()
            ?: throw PatchException("$SIDE_TAPS: expected one (Float, Float)V method of $STORY_VIEWER loading \"$mark\", found ${found.size}")
        if (0 in one.jumpTargets()) throw PatchException("$SIDE_TAPS: a branch in $STORY_VIEWER->${one.name} lands on its first instruction")
        return one
    }
    val forward = handler(TAP_FORWARD)
    val back = handler(TAP_BACK)
    if (forward.name == back.name) throw PatchException("$SIDE_TAPS: \"$TAP_FORWARD\" and \"$TAP_BACK\" share $STORY_VIEWER->${forward.name}")
    return TapHandlers(forward.name, back.name)
}

/**
 * Puts the guard first in both [handlers]: a no from [KEEPS_TAP] returns before the handler logs
 * or moves anything, a yes goes on into Instagram's code as it was. Each borrows one local, free at
 * the top of the method.
 */
internal fun BytecodePatchContext.stopSideTaps(handlers: TapHandlers) {
    val viewer = mutableClassDefBy(STORY_VIEWER)
    for (name in listOf(handlers.forward, handlers.back)) {
        viewer.methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == TAP_PARAMETERS }.apply {
            requireLocals(SIDE_TAPS, 1)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $KEEPS_TAP
                    move-result v0
                    if-nez v0, :tap
                    return-void
                """,
                ExternalLabel("tap", getInstruction(0)),
            )
        }
    }
}
