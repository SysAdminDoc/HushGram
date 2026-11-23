/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The extension's hook the reel's touch hands each event to first (#744). */
internal const val PINCH_CLEAR_MODE_TOUCH = "$REEL_PINCH_CLEAR_MODE->touch(Landroid/view/View;Landroid/view/MotionEvent;)V"

/** What the pinch listener's scale end loads to log a pinch, on every 456 build. */
internal const val PINCH_TO_ZOOM = "pinch_to_zoom"

internal const val SIMPLE_GESTURE_LISTENER = "Landroid/view/GestureDetector\$SimpleOnGestureListener;"
internal const val SCALE_LISTENER = "Landroid/view/ScaleGestureDetector\$OnScaleGestureListener;"
internal const val SCALE_TOUCH = "Landroid/view/ScaleGestureDetector;->onTouchEvent(Landroid/view/MotionEvent;)Z"
private val TOUCH_PARAMETERS = listOf("Landroid/view/View;", "Landroid/view/MotionEvent;")

/** The reel's touch method in [owner]: [name], with the view and the event in the registers from [first] on. */
internal class PinchTouch(val owner: String, val name: String, val first: Int)

/**
 * The reel's touch, where Pinch for clear mode hands each event to [PINCH_CLEAR_MODE_TOUCH] first, or
 * null after the patch log says why, so the rest of the pinch goes in without it.
 *
 * Instagram's Reels viewer feeds a reel's touches to a pinch listener of its own, a
 * SimpleOnGestureListener that is also the ScaleGestureDetector's listener, whose scale end loads
 * [PINCH_TO_ZOOM] (`LX/0DCL` on 386214574). The method taking the reel's view and the event and
 * answering whether it took them is the one handing the event to the detector, and it's the one that
 * hears a second finger land. Two fingers spreading out from close together only start the pinch once
 * they're as far apart as the phone's least pinch width, about an inch, and by then the list around
 * the reel has read them as a scroll or a pull to refresh and taken the gesture, so only two fingers
 * closing in ever turned clear mode on or off (#744). The hook lets the extension keep the list out
 * of a two-finger gesture from the moment the second finger lands.
 */
internal fun BytecodePatchContext.findPinchTouch(): PinchTouch? {
    fun skip(why: String): PinchTouch? {
        patchLog.warning("$PINCH_CLEAR_MODE_PART: $why. Pinch for clear mode goes in without keeping the list out of a spread.")
        return null
    }
    val listeners = classesLoadingString(PINCH_TO_ZOOM).filter { owner ->
        owner.superclass == SIMPLE_GESTURE_LISTENER && SCALE_LISTENER in owner.interfaces.map(CharSequence::toString) &&
            owner.methods.any { it.name == "onScaleEnd" && loadsString(it, PINCH_TO_ZOOM) }
    }
    val listener = listeners.singleOrNull() ?: return skip("expected one pinch listener logging $PINCH_TO_ZOOM, found ${listeners.size}")
    val touches = listener.methods.filter { it.isReelTouch() }
    val touch = touches.singleOrNull()
        ?: return skip("expected one method of ${listener.type} handing a reel's touch to its scale detector, found ${touches.size}")
    if (0 in touch.jumpTargets()) return skip("${listener.type}->${touch.name} jumps back to its start")
    val first = touch.parameterRegisterNumber(0)
    return PinchTouch(listener.type, touch.name, first)
}

/** An instance method taking a view and a motion event, answering a yes or no, that hands the event to a scale detector. */
private fun Method.isReelTouch(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "Z") return false
    if (parameterTypes.map(CharSequence::toString) != TOUCH_PARAMETERS) return false
    return implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SCALE_TOUCH } == true
}

/** Only called with what [findPinchTouch] found: the touch hands its view and event to [PINCH_CLEAR_MODE_TOUCH] first thing. */
internal fun BytecodePatchContext.pinchTouch(found: PinchTouch) {
    val method = mutableClassDefBy(found.owner).methods.single {
        it.name == found.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == TOUCH_PARAMETERS
    }
    if (!method.isReelTouch() || method.parameterRegisterNumber(0) != found.first) {
        throw PatchException("$PINCH_CLEAR_MODE_PART: ${found.owner}->${found.name} changed after the reel's touch was found")
    }
    method.addInstructions(0, "invoke-static/range { v${found.first} .. v${found.first + 1} }, $PINCH_CLEAR_MODE_TOUCH")
}
