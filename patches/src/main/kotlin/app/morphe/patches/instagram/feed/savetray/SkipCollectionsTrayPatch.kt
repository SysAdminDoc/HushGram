/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.savetray

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.video.mutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

internal const val SKIP_TRAY = "$EXTENSION_PACKAGE/feed/SaveTray;->skip()Z"
internal const val SAVE_TRAY_PATCH = "Skip the collections tray"
internal const val TRAY_CHECK = "collections tray check"
internal const val TRAY_COUNT = "direct_collab_collection_feed_creation_nux_impression_count"

/**
 * Instagram's check of whether a save opens the tray that asks you to start a collection: the one
 * (UserSession) boolean instance method loading [TRAY_COUNT]. It answers yes on a server switch, or
 * while the count of times the tray has shown stays under a server limit, and reads that count under
 * this name. On 453 Reels' save button and Feed's take the tray when it's yes and show the Saved
 * message when it's no. 453's 385914510 asks a string pool for the name, so it's found by what it
 * loads rather than by a fingerprint's string. A static method of the same shape is some other
 * reader of the count. None or more than one stops the patch.
 */
internal fun BytecodePatchContext.findTrayCheck(): MutableMethod {
    val found = classesLoadingString(TRAY_COUNT).flatMap { it.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
            method.parameterTypes.map(Any::toString) == listOf("Lcom/instagram/common/session/UserSession;") &&
            loadsString(method, TRAY_COUNT)
    }
    val check = found.singleOrNull() ?: throw PatchException(
        "$SAVE_TRAY_PATCH: expected exactly one $TRAY_CHECK in this Instagram build, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->${it.name}" },
    )
    return mutable(check)
}

/**
 * Asks the extension ahead of the check's first instruction and answers no when it says to skip
 * the tray. The label keeps the original first instruction as the way on. A jump back to the start
 * would land on that label and skip the ask, so the patch refuses one.
 */
internal fun MutableMethod.skipTray() {
    requireLocals(SAVE_TRAY_PATCH, 1)
    if (0 in jumpTargets()) throw PatchException("$SAVE_TRAY_PATCH: something in the $TRAY_CHECK jumps back to its start")
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SKIP_TRAY
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}

/**
 * Saving a post or reel shows the Saved message instead of the collections tray while the switch is
 * on. Included in the default selection with its switch off, so skipping it is the user's pick.
 */
@Suppress("unused")
val skipCollectionsTrayPatch = bytecodePatch(
    name = "Skip the collections tray",
    description = "Saving a post or reel shows Instagram's Saved message instead of the tray that asks you to " +
        "start a collection. Starts off. Turn it on in HushGram settings > Feed.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("saveTray")
        findTrayCheck().skipTray()
        enableStatus("saveTray")
    }
}
