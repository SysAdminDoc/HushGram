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

internal const val PATCH = "View DM photos and videos anonymously"

/** Opt-in visual receipt protection, independent of story views and ordinary chat receipts. */
@Suppress("unused")
val viewDmMediaAnonymouslyPatch = bytecodePatch(
    name = "View DM photos and videos anonymously",
    description = "Holds back the opened receipt for view-once photos and videos in messages. Ordinary chat and " +
        "voice receipts keep Instagram's behavior. Off until you turn it on. Switch: HushGram settings > Ads and " +
        "privacy. Ghost mode, at the top of the same section, turns it on with the others.",
    default = false,
) {
    category("Ghost mode")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("visualSeen")
        holdBackVisualSeen()
        enableStatus("visualSeen")
    }
}

/**
 * Completes just this visual mutation through Instagram's own success callback. Returning without
 * completion would leave the receipt in the persisted queue to retry after a restart. No request,
 * message object or account identifier passes to the extension, and nothing is retained there.
 */
internal fun BytecodePatchContext.holdBackVisualSeen() {
    val found = findVisualSeen()
    found.handler.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_VISUAL_SEEN
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
