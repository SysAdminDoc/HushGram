/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.direct.lock.CHAT_CLOSED
import app.morphe.patches.instagram.direct.lock.CHAT_OPENED
import app.morphe.patches.instagram.direct.lock.ChatPauseFingerprint
import app.morphe.patches.instagram.direct.lock.ChatResumeFingerprint
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val MARK_READ_BUTTON = "$EXTENSION_PACKAGE/direct/MarkReadButton;"
internal const val BUTTON_OPENED = "$MARK_READ_BUTTON->opened(Ljava/lang/Object;)V"
internal const val BUTTON_CLOSED = "$MARK_READ_BUTTON->closed(Ljava/lang/Object;)V"

/** The chat screen's onResume and onPause, where the header's check mark comes and goes (#458). */
internal class MarkReadButtonTargets(val resume: MutableMethod, val pause: MutableMethod)

/**
 * The chat screen's two lifecycle methods, the pair the chat lock and the chat history hook. Null when
 * either isn't there, so the receipt is still held and only the check mark is left out. Refuses when
 * both are there but don't belong together or something jumps back to where the hook goes.
 */
internal fun BytecodePatchContext.findMarkReadButton(): MarkReadButtonTargets? {
    val resumes = ChatResumeFingerprint.matchAllOrNull().orEmpty()
    val pauses = ChatPauseFingerprint.matchAllOrNull().orEmpty()
    if (resumes.isEmpty() || pauses.isEmpty()) return null
    if (resumes.size != 1 || pauses.size != 1) {
        throw PatchException("$THREAD_SEEN_PATCH: expected one chat screen onResume and onPause, found ${resumes.size} and ${pauses.size}")
    }
    val resume = resumes.single().method
    val pause = pauses.single().method
    if (resume.definingClass != pause.definingClass) {
        throw PatchException("$THREAD_SEEN_PATCH: the chat screen's onResume and onPause are in different classes")
    }
    for (method in listOf(resume, pause)) {
        if (0 in method.jumpTargets()) throw PatchException("$THREAD_SEEN_PATCH: something jumps back to the chat screen's ${method.name} first instruction")
    }
    return MarkReadButtonTargets(resume, pause)
}

/**
 * Tells the extension each time a chat comes to the front and leaves it, before Instagram's own code.
 * Patches apply in name order, so the chat lock has already put its own call first: this one goes right
 * after it, which keeps the lock's call first and lets the check mark read the chat the lock just read.
 */
internal fun hookMarkReadButton(found: MarkReadButtonTargets) {
    found.resume.hookAfterChatLock(CHAT_OPENED, BUTTON_OPENED)
    found.pause.hookAfterChatLock(CHAT_CLOSED, BUTTON_CLOSED)
}

private fun MutableMethod.hookAfterChatLock(lock: String, call: String) {
    val first = implementation?.instructions?.firstOrNull() as? ReferenceInstruction
    val at = if ((first?.reference as? MethodReference)?.toString() == lock) 1 else 0
    if (at in jumpTargets()) throw PatchException("$THREAD_SEEN_PATCH: something jumps to where the chat screen's $name hook goes")
    addInstructions(at, "invoke-static/range { p0 .. p0 }, $call")
}
