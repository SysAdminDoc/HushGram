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
import app.morphe.patches.instagram.direct.ghost.ghostModeEntryPatch
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
    description = "Opening a chat no longer tells people you've seen their messages. You still see when they've " +
        "seen yours. Mark as read still tells them. Ghost mode turns it on too. Starts off. Turn it on in " +
        "HushGram settings > Messages.",
) {
    category("Ghost mode")
    dependsOn(settingsPatch, instagramExtensionPatch, ghostModeEntryPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("threadSeen")
        readWithoutSeenReceipt()
        enableStatus("threadSeen")
    }
}

/**
 * Resolves the receipt's handler, everything Mark as read needs, where the chat store hears the
 * receipt's state and where the inbox asks whether a chat shows unread before changing anything.
 * Then holds the receipt back, offers Mark as read on a chat's long press, lets the receipts of chats
 * marked read through, keeps a held chat unread on this phone, or, with that turned off, keeps it
 * read until a newer message comes in.
 */
internal fun BytecodePatchContext.readWithoutSeenReceipt() {
    val seen = findThreadSeen()
    val marks = findMarkRead(seen)
    val unread = findKeepUnread(seen)
    val read = findReadHere(marks.chat)
    holdBackThreadSeen(seen)
    markReadByHand(marks)
    keepChatsUnread(unread)
    keepChatsRead(read)
}

/** [holdBackThreadSeen] on the handler this build resolves to, with nothing else hooked. */
internal fun BytecodePatchContext.holdBackThreadSeen() = holdBackThreadSeen(findThreadSeen())

/**
 * Completes the chat receipt's queued task through Instagram's own success callback before any
 * request is built, so the receipt isn't retried after a restart. Whether the chat clears on this
 * phone is up to [keepChatsUnread], since the store hears of the receipt before this handler runs. The extension is handed the receipt and the
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
