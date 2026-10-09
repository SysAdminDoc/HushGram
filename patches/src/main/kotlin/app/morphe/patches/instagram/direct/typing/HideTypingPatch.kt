/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.typing

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

internal const val TYPING_PATCH = "Hide that you're typing"

/**
 * One-sided: your typing indicator stays home, and other people's still show. In the default
 * selection with its switch off.
 */
@Suppress("unused")
val hideTypingPatch = bytecodePatch(
    name = "Hide that you're typing",
    description = "Keeps the people you're chatting with from seeing when you're typing. Unlike turning off the " +
        "typing indicator in Instagram's settings, you still see when they're typing. Off until you turn it on. " +
        "Switch: HushGram settings > Messages. Ghost mode, at the top of Ads and privacy, turns it on with the " +
        "others.",
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("typing")
        holdBackTyping()
        enableStatus("typing")
    }
}

/**
 * Asks the extension first in Instagram's typing status service, with the typing flag. A held
 * start returns before the service sends anything. A stop sends nothing and only makes the
 * service forget the chat it last reported, so it always runs Instagram's code.
 */
internal fun BytecodePatchContext.holdBackTyping() {
    val found = findTyping()
    val flag = found.service.parameterRegister(0)
    found.service.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { $flag .. $flag }, $HOLD_TYPING
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", found.service.getInstruction(0)),
    )
}
