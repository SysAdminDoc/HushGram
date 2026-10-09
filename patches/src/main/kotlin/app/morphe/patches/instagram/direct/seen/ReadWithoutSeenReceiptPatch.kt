/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val THREAD_SEEN_PATCH = "Read messages without the seen receipt"

/**
 * One-sided chat receipt protection, independent of the view-once receipt patch. In the default
 * selection with its switch off.
 */
@Suppress("unused")
val readWithoutSeenReceiptPatch = bytecodePatch(
    name = "Read messages without the seen receipt",
    description = "Keeps opening a chat from telling people you've seen their messages. Unlike turning off read " +
        "receipts in Instagram's settings, you still see when they've seen yours. Instagram's Mark as read still " +
        "lets them know, whether you long press one chat or pick several. View-once photos and videos have their own " +
        "patch. Off until you turn it on. Switch: HushGram settings > Messages. Ghost mode, at the top of Ads and " +
        "privacy, turns it on with the others.",
) {
    category("Ghost mode")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("threadSeen")
        readWithoutSeenReceipt()
        enableStatus("threadSeen")
    }
}

/**
 * Resolves the receipt's handler and everything Mark as read needs before changing anything, then
 * holds the receipt back, offers Mark as read on a chat's long press and lets the receipts of
 * chats marked read through.
 */
internal fun BytecodePatchContext.readWithoutSeenReceipt() {
    val seen = findThreadSeen()
    val marks = findMarkRead(seen)
    holdBackThreadSeen(seen)
    markReadByHand(marks)
}

/** [holdBackThreadSeen] on the handler this build resolves to, with nothing else hooked. */
internal fun BytecodePatchContext.holdBackThreadSeen() = holdBackThreadSeen(findThreadSeen())

/**
 * Completes the chat receipt's queued task through Instagram's own success callback before any
 * request is built. The chat still clears on this phone, since that happens before the queue runs,
 * and the receipt isn't retried after a restart. The extension is handed the receipt and the
 * account the handler sends it for, so it can let through the one for a message marked read.
 */
internal fun holdBackThreadSeen(found: ThreadSeenTargets) {
    found.handler.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${found.handler.parameterRegister(2)}
            move-object/from16 v1, p0
            iget-object v1, v1, ${found.account}
            invoke-static { v0, v1 }, $HOLD_THREAD_SEEN
            move-result v0
            if-eqz v0, :instagram
            move-object/from16 v1, ${found.handler.parameterRegister(1)}
            const/4 v0, 0x0
            invoke-interface { v1, v0, v0 }, ${found.complete}
            return-void
        """,
        ExternalLabel("instagram", found.handler.getInstruction(0)),
    )
}
