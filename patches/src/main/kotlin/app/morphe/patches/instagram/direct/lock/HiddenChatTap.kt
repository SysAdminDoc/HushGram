/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Double-tap Messages for hidden chats (#358): handed every touch, true keeps it from Instagram. */
internal const val HIDDEN_CHATS_TAP = "$EXTENSION_PACKAGE/direct/HiddenChatsTap;->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)Z"

private const val MOTION_EVENT = "Landroid/view/MotionEvent;"

/**
 * IgFragmentActivity's own dispatchTouchEvent, which every touch on an Instagram screen passes
 * through before any of its views, the Messages buttons on the tab bar and at the top of Home
 * included. The hook's answer goes in the method's first local, which is written before it's read.
 */
internal fun BytecodePatchContext.findHiddenChatsTouch(): MutableMethod {
    val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: refuseTap("this build has no $FRAGMENT_ACTIVITY")
    val touches = activity.methods.filter {
        it.name == "dispatchTouchEvent" && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) &&
            it.implementation != null
    }
    val touch = touches.singleOrNull() ?: refuseTap("expected $FRAGMENT_ACTIVITY to have one dispatchTouchEvent of its own, found ${touches.size}")
    if (touch.localRegisterCount() < 1) refuseTap("$FRAGMENT_ACTIVITY->dispatchTouchEvent has no local register for the hook's answer")
    return mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single {
        it.name == touch.name && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT)
    }
}

/**
 * First thing in [touch], hands the activity and the event to [HIDDEN_CHATS_TAP]. When it answers
 * true the touch was the second tap of a double tap on Messages and is answered as handled.
 * Otherwise the code after it runs as it was. Tap to play reads every touch here too and is held
 * first, so when its call is already in, this one goes right after it.
 */
internal fun hookHiddenChatsTouch(touch: MutableMethod) {
    val first = (touch.getInstruction(0) as? ReferenceInstruction)?.reference as? MethodReference
    val at = if (first != null && "${first.definingClass}->${first.name}(${first.parameterTypes.joinToString("")})${first.returnType}" == TAP_CLOCK) 1 else 0
    touch.addInstructionsWithLabels(
        at,
        """
            invoke-static/range { p0 .. p1 }, $HIDDEN_CHATS_TAP
            move-result v0
            if-eqz v0, :instagram
            return v0
        """,
        ExternalLabel("instagram", touch.getInstruction(at)),
    )
}

private fun refuseTap(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")
